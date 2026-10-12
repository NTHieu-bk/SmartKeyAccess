package com.example.smartkeyaccess.nfc

import com.example.smartkeyaccess.crypto.CryptoManager

// Chứa thông tin đọc được từ thẻ Master Card vật lý
data class MasterCardData(
    val vehicleId: ByteArray,
    val masterSecretKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MasterCardData
        return vehicleId.contentEquals(other.vehicleId) &&
                masterSecretKey.contentEquals(other.masterSecretKey)
    }

    override fun hashCode(): Int {
        var result = vehicleId.contentHashCode()
        result = 31 * result + masterSecretKey.contentHashCode()
        return result
    }

    fun isValid(): Boolean {
        return vehicleId.size == 8 && masterSecretKey.size == 32
    }
}

object MasterCardParser {

    const val VID_LENGTH = 8
    const val MSK_LENGTH = 32

    // Parse từ payload thô (ít nhất 40B: 8B VID + 32B MSK)
    fun parseRaw(rawBytes: ByteArray): MasterCardData? {
        if (rawBytes.size < VID_LENGTH + MSK_LENGTH) return null
        val vid = rawBytes.copyOfRange(0, VID_LENGTH)
        val msk = rawBytes.copyOfRange(VID_LENGTH, VID_LENGTH + MSK_LENGTH)
        val data = MasterCardData(vid, msk)
        return if (data.isValid()) data else null
    }

    // Parse cho thẻ Mifare Classic (mỗi block 16B)
    // VID 8B nằm ở vidBlock (lấy 8B đầu), MSK 32B nằm ở 2 block mskBlock1 + mskBlock2 (mỗi block 16B)
    fun parseMifareClassic(vidBlock: ByteArray, mskBlock1: ByteArray, mskBlock2: ByteArray): MasterCardData? {
        if (vidBlock.size < 16 || mskBlock1.size < 16 || mskBlock2.size < 16) return null
        val vid = vidBlock.copyOfRange(0, VID_LENGTH)
        val msk = ByteArray(MSK_LENGTH)
        System.arraycopy(mskBlock1, 0, msk, 0, 16)
        System.arraycopy(mskBlock2, 0, msk, 16, 16)
        val data = MasterCardData(vid, msk)
        return if (data.isValid()) data else null
    }

    // Parse cho thẻ Mifare Ultralight (mỗi lần readPages đọc 4 pages = 16B)
    fun parseMifareUltralight(pagesChunk1: ByteArray, pagesChunk2: ByteArray, pagesChunk3: ByteArray): MasterCardData? {
        // Gom dữ liệu các page lại rồi parse
        val combined = pagesChunk1 + pagesChunk2 + pagesChunk3
        return parseRaw(combined)
    }
}
