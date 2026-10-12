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

    private val _status = MutableStateFlow("NFC Reader: Chưa bật")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _provisionedCard = MutableStateFlow<MasterCardData?>(null)
    val provisionedCard: StateFlow<MasterCardData?> = _provisionedCard.asStateFlow()

    private var isReading = false

    // Bật chế độ Reader để đọc thẻ vật lý
    fun startReader(activity: Activity): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        if (adapter == null) {
            _status.value = "Thiết bị không hỗ trợ NFC phần cứng"
            return false
        }
        if (!adapter.isEnabled) {
            _status.value = "NFC đang tắt, vui lòng bật trong cài đặt máy"
            return false
        }

        // Cờ cấu hình đọc các loại thẻ không chạm phổ biến (Type A, B, F, V)
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
        _status.value = "NFC Reader đang quét... Vui lòng áp thẻ Master Card vào lưng máy"
        return true
    }

    // Tắt chế độ Reader khi thoát hoặc pause app
    fun stopReader(activity: Activity) {
        if (!isReading) return
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        adapter?.disableReaderMode(activity)
        isReading = false
        _status.value = "NFC Reader: Đã tắt"
    }

    // Callback của Android khi phát hiện có thẻ chạm vào
    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) return

        scope.launch {
            _status.value = "Phát hiện thẻ: Đang đọc dữ liệu block..."
            val result = withContext(Dispatchers.IO) {
                readTagData(tag)
            }

            if (result != null && result.isValid()) {
                _provisionedCard.value = result
                // Lưu VID và MSK vào HCE Bridge để chuẩn bị bắt tay với PN532
                NfcHceBridge.updateProvisionedCard(result.vehicleId, result.masterSecretKey)
                _status.value = "Đọc thẻ thành công! VID: ${CryptoManager.toHex(result.vehicleId)}"
            } else {
                _status.value = "Đọc thẻ thất bại hoặc thẻ không đúng cấu trúc Master Card"
            }
        }
    }

    // Đọc I/O trên background thread
    private fun readTagData(tag: Tag): MasterCardData? {
        val techList = tag.techList.map { it.substringAfterLast(".") }

        // 1. Thử đọc theo MifareClassic (nếu thẻ là Mifare Classic 1K/4K)
        if (techList.contains("MifareClassic")) {
            val mfc = MifareClassic.get(tag)
            if (mfc != null) {
                try {
                    mfc.connect()
                    // Dùng key mặc định thường dùng của thẻ trắng (Key A: 0xFF...FF)
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
                } catch (e: Exception) {
                    // Tiếp tục fallback nếu đọc classic lỗi
                } finally {
                    try { mfc.close() } catch (_: Exception) {}
                }
            }
        }

        // 2. Thử đọc theo MifareUltralight / NTAG
        if (techList.contains("MifareUltralight")) {
            val mfu = MifareUltralight.get(tag)
            if (mfu != null) {
                try {
                    mfu.connect()
                    // Mỗi lần readPages đọc 4 pages = 16 bytes
                    val chunk1 = mfu.readPages(4)  // pages 4..7 (16B)
                    val chunk2 = mfu.readPages(8)  // pages 8..11 (16B)
                    val chunk3 = mfu.readPages(12) // pages 12..15 (16B)
                    val parsed = MasterCardParser.parseMifareUltralight(chunk1, chunk2, chunk3)
                    if (parsed != null) return parsed
                } catch (e: Exception) {
                    // Bỏ qua nếu lỗi
                } finally {
                    try { mfu.close() } catch (_: Exception) {}
                }
            }
        }

        // 3. Fallback đọc qua transceive NfcA cơ bản
        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            try {
                nfcA.connect()
                // Lệnh đọc READ (0x30) page 4
                val cmd = byteArrayOf(0x30.toByte(), 0x04.toByte())
                val res = nfcA.transceive(cmd)
                if (res.size >= 16) {
                    // Nếu thẻ có dữ liệu mẫu 40B
                    val cmd2 = byteArrayOf(0x30.toByte(), 0x08.toByte())
                    val res2 = nfcA.transceive(cmd2)
                    val cmd3 = byteArrayOf(0x30.toByte(), 0x0C.toByte())
                    val res3 = nfcA.transceive(cmd3)
                    val parsed = MasterCardParser.parseMifareUltralight(res, res2, res3)
                    if (parsed != null) return parsed
                }
            } catch (e: Exception) {
                // Bỏ qua
            } finally {
                try { nfcA.close() } catch (_: Exception) {}
            }
        }

        return null
    }
}
