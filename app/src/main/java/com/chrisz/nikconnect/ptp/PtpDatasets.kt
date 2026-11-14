package com.chrisz.nikconnect.ptp

internal data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val operationsSupported: List<Int>,
) {
    val supportsPartialObject: Boolean get() = OpCode.GET_PARTIAL_OBJECT in operationsSupported
}

/**
 * 一页照片 + 本次列表使用的句柄快照。
 *
 * 分页时要把 [snapshot] 原样传回 [PtpIpClient.listPhotos]，否则每页重取句柄表会因相机新增/删除
 * 文件而漂移（评审 F10）。
 */
internal data class PhotoPage(
    val total: Int,
    val photos: List<PhotoObject>,
    val hasMore: Boolean,
    val snapshot: List<Long>,
)

internal data class PhotoObject(
    val handle: Long,
    val storageId: Long,
    val objectFormat: Int,
    val size: Long,
    val filename: String,
    val captureDate: String,
    val thumbnailSize: Long,
) {
    val isJpeg: Boolean
        get() = objectFormat == 0x3801 || filename.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg")

    val isRaw: Boolean
        get() = objectFormat in setOf(0x3800, 0x3802) || filename.substringAfterLast('.', "").lowercase() in setOf("nef", "nrw")
}

internal fun parseDeviceInfo(data: ByteArray): DeviceInfo {
    val reader = LittleEndianReader(data)
    reader.u16() // StandardVersion
    reader.u32() // VendorExtensionID
    reader.u16() // VendorExtensionVersion
    reader.ptpString() // VendorExtensionDesc
    reader.u16() // FunctionalMode
    val operations = reader.u16Array()
    reader.u16Array() // EventsSupported
    reader.u16Array() // DevicePropertiesSupported
    reader.u16Array() // CaptureFormats
    reader.u16Array() // ImageFormats
    val manufacturer = reader.ptpString()
    val model = reader.ptpString()
    val version = reader.ptpString()
    reader.ptpString() // SerialNumber: parsed but deliberately not shown in the UI.
    return DeviceInfo(manufacturer, model, version, operations)
}

internal fun parseObjectInfo(handle: Long, data: ByteArray): PhotoObject {
    val reader = LittleEndianReader(data)
    val storageId = reader.u32()
    val format = reader.u16()
    reader.u16() // ProtectionStatus
    val size = reader.u32()
    reader.u16() // ThumbFormat
    val thumbnailSize = reader.u32()
    repeat(5) { reader.u32() } // ThumbWidth/Height, ImageWidth/Height, ImageBitDepth
    reader.u32() // ParentObject
    reader.u16() // AssociationType
    reader.u32() // AssociationDesc
    reader.u32() // SequenceNumber
    val filename = reader.ptpString()
    val captureDate = reader.ptpString()
    reader.ptpString() // ModificationDate
    reader.ptpString() // Keywords
    return PhotoObject(handle, storageId, format, size, filename, captureDate, thumbnailSize)
}

internal fun decodeU32Array(data: ByteArray): List<Long> {
    val reader = LittleEndianReader(data)
    val count = reader.u32()
    require(count <= Int.MAX_VALUE.toLong() && count * 4 <= reader.remaining.toLong()) {
        "UInt32 数组长度异常：$count"
    }
    return List(count.toInt()) { reader.u32() }
}
