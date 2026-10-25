package com.example.smartkeyaccess.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import com.example.smartkeyaccess.crypto.CryptoManager
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NfcReaderManager(
    private val scope: CoroutineScope,
    private val onCardProvisioned: () -> Unit = {}
) : NfcAdapter.ReaderCallback {

    private val _status = MutableStateFlow("NFC Reader: Disabled")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _provisionedCard = MutableStateFlow<MasterCardData?>(null)
    val provisionedCard: StateFlow<MasterCardData?> = _provisionedCard.asStateFlow()

    private var isReading = false
    private var readerActivity = WeakReference<Activity>(null)

    private sealed interface ReadResult {
        data class Success(val card: MasterCardData) : ReadResult
        data class Blocked(val technologies: String) : ReadResult
        data class Failure(val reason: String) : ReadResult
    }

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
        readerActivity = WeakReference(activity)
        _status.value = "Scanning... Tap Master Card to back of device"
        return true
    }

    // Disable reader mode when pausing activity
    fun stopReader(activity: Activity) {
        if (!isReading) return
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        adapter?.disableReaderMode(activity)
        isReading = false
        readerActivity.clear()
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

            when (result) {
                is ReadResult.Success -> {
                    val card = result.card
                    _provisionedCard.value = card
                    // Reader Mode disables card emulation while active. After a valid
                    // card is provisioned, return the NFC adapter to HCE mode before
                    // the PN532 begins the APDU exchange.
                    readerActivity.get()?.let { activity ->
                        NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
                    }
                    isReading = false
                    readerActivity.clear()
                    onCardProvisioned()

                    NfcHceBridge.updateProvisionedCard(card.vehicleId, card.masterSecretKey)
                    _status.value = "Card read successfully; Reader Mode stopped. VID: ${CryptoManager.toHex(card.vehicleId)}"
                }
                is ReadResult.Blocked -> {
                    _status.value = "Master Card read BLOCKED: firmware must confirm card type, authentication, and block/page layout. Tag: ${result.technologies}"
                }
                is ReadResult.Failure -> {
                    _status.value = "Master Card read failed: ${result.reason}"
                }
            }
        }
    }

    // Detects the physical tag without guessing where credentials are stored.
    //
    // The firmware contract has not yet defined the exact card technology,
    // authentication key, VID block/page, or MSK blocks/pages. Reading sector 1
    // with the default key or pages 4/8/12 would turn an unconfirmed test layout
    // into production behavior, so raw I/O intentionally remains blocked here.
    // Once the contract is frozen, this method should connect to the confirmed
    // technology, read only the specified raw locations, close the tag in a
    // `finally` block, and return `ReadResult.Success` after parser validation.
    private fun readTagData(tag: Tag): ReadResult {
        val techList = tag.techList.map { it.substringAfterLast(".") }
        val detected = if (techList.isEmpty()) "unknown" else techList.joinToString()
        return ReadResult.Blocked(detected)
    }
}
