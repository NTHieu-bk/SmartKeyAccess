package com.example.smartkeyaccess.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import android.os.Bundle
import com.example.smartkeyaccess.crypto.CryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NfcReaderManager(
    private val scope: CoroutineScope
) : NfcAdapter.ReaderCallback {

    private val _status = MutableStateFlow("NFC Reader: Disabled")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _provisionedCard = MutableStateFlow<MasterCardData?>(null)
    val provisionedCard: StateFlow<MasterCardData?> = _provisionedCard.asStateFlow()

    private var isReading = false

    // Enable reader mode to scan physical contactless cards
    fun startReader(activity: Activity): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        if (adapter == null) {
            _status.value = "Device does not support hardware NFC"
            return false
        }
        if (!adapter.isEnabled) {
            _status.value = "NFC is disabled in system settings"
            return false
        }

        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }

        adapter.enableReaderMode(activity, this, flags, options)
        isReading = true
        _status.value = "Scanning... Tap Master Card to back of device"
        return true
    }

    // Disable reader mode when pausing activity
    fun stopReader(activity: Activity) {
        if (!isReading) return
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        adapter?.disableReaderMode(activity)
        isReading = false
        _status.value = "NFC Reader: Disabled"
    }

    // Callback invoked by Android platform when tag is discovered
    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) return

        scope.launch {
            _status.value = "Tag detected: reading block data..."
            val result = withContext(Dispatchers.IO) {
                readTagData(tag)
            }

            if (result != null && result.isValid()) {
                _provisionedCard.value = result
                // Cache VID and MSK in HCE Bridge for subsequent PN532 handshake
                NfcHceBridge.updateProvisionedCard(result.vehicleId, result.masterSecretKey)
                _status.value = "Card read successfully! VID: ${CryptoManager.toHex(result.vehicleId)}"
            } else {
                _status.value = "Failed to read card or invalid card format"
            }
        }
    }

    // Perform I/O on background IO dispatcher
    private fun readTagData(tag: Tag): MasterCardData? {
        val techList = tag.techList.map { it.substringAfterLast(".") }

        // Attempt reading via MifareClassic technology
        if (techList.contains("MifareClassic")) {
            val mfc = MifareClassic.get(tag)
            if (mfc != null) {
                try {
                    mfc.connect()
                    val keyA = MifareClassic.KEY_DEFAULT
                    val sector = 1
                    val auth = mfc.authenticateSectorWithKeyA(sector, keyA)
                    if (auth) {
                        val firstBlock = mfc.sectorToBlock(sector)
                        val block0 = mfc.readBlock(firstBlock)
                        val block1 = mfc.readBlock(firstBlock + 1)
                        val block2 = mfc.readBlock(firstBlock + 2)
                        val parsed = MasterCardParser.parseMifareClassic(block0, block1, block2)
                        if (parsed != null) return parsed
                    }
                } catch (_: Exception) {
                } finally {
                    try { mfc.close() } catch (_: Exception) {}
                }
            }
        }

        // Attempt reading via MifareUltralight / NTAG
        if (techList.contains("MifareUltralight")) {
            val mfu = MifareUltralight.get(tag)
            if (mfu != null) {
                try {
                    mfu.connect()
                    val chunk1 = mfu.readPages(4)  // pages 4..7 (16B)
                    val chunk2 = mfu.readPages(8)  // pages 8..11 (16B)
                    val chunk3 = mfu.readPages(12) // pages 12..15 (16B)
                    val parsed = MasterCardParser.parseMifareUltralight(chunk1, chunk2, chunk3)
                    if (parsed != null) return parsed
                } catch (_: Exception) {
                } finally {
                    try { mfu.close() } catch (_: Exception) {}
                }
            }
        }

        // Fallback reading via raw NfcA transceive
        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            try {
                nfcA.connect()
                val cmd = byteArrayOf(0x30.toByte(), 0x04.toByte())
                val res = nfcA.transceive(cmd)
                if (res.size >= 16) {
                    val cmd2 = byteArrayOf(0x30.toByte(), 0x08.toByte())
                    val res2 = nfcA.transceive(cmd2)
                    val cmd3 = byteArrayOf(0x30.toByte(), 0x0C.toByte())
                    val res3 = nfcA.transceive(cmd3)
                    val parsed = MasterCardParser.parseMifareUltralight(res, res2, res3)
                    if (parsed != null) return parsed
                }
            } catch (_: Exception) {
            } finally {
                try { nfcA.close() } catch (_: Exception) {}
            }
        }

        return null
    }
}
