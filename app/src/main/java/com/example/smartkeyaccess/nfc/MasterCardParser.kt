package com.example.smartkeyaccess.nfc

// Master card credentials extracted from physical NFC tag
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

    // Parse from raw continuous payload (at least 40 bytes: 8B VID + 32B MSK)
    fun parseRaw(rawBytes: ByteArray): MasterCardData? {
        if (rawBytes.size < VID_LENGTH + MSK_LENGTH) return null
        val vid = rawBytes.copyOfRange(0, VID_LENGTH)
        val msk = rawBytes.copyOfRange(VID_LENGTH, VID_LENGTH + MSK_LENGTH)
        val data = MasterCardData(vid, msk)
        return if (data.isValid()) data else null
    }

    // Parse for Mifare Classic (16-byte blocks)
    // VID (8B) located in vidBlock, MSK (32B) spread across mskBlock1 and mskBlock2
    fun parseMifareClassic(vidBlock: ByteArray, mskBlock1: ByteArray, mskBlock2: ByteArray): MasterCardData? {
        if (vidBlock.size < 16 || mskBlock1.size < 16 || mskBlock2.size < 16) return null
        val vid = vidBlock.copyOfRange(0, VID_LENGTH)
        val msk = ByteArray(MSK_LENGTH)
        System.arraycopy(mskBlock1, 0, msk, 0, 16)
        System.arraycopy(mskBlock2, 0, msk, 16, 16)
        val data = MasterCardData(vid, msk)
        return if (data.isValid()) data else null
    }

    // Parse for Mifare Ultralight (each readPages call returns 4 pages = 16 bytes)
    fun parseMifareUltralight(pagesChunk1: ByteArray, pagesChunk2: ByteArray, pagesChunk3: ByteArray): MasterCardData? {
        val combined = pagesChunk1 + pagesChunk2 + pagesChunk3
        return parseRaw(combined)
    }
}
