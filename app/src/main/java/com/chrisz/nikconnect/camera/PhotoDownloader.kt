package com.chrisz.nikconnect.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.chrisz.nikconnect.ptp.PhotoObject
import com.chrisz.nikconnect.ptp.PtpIpClient
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class PhotoDownloader(
    private val context: Context,
    private val client: PtpIpClient,
) {
    suspend fun download(
        photo: PhotoObject,
        onProgress: (bytesWritten: Long) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        require(photo.isJpeg || photo.isRaw) { "仅支持 JPEG/RAW（NEF/NRW）下载" }
        val directory = File(context.cacheDir, "downloads").apply { mkdirs() }
        val part = partialFile(directory, client.connectionId, photo)
        if (part.length() > photo.size) part.delete()
        val resumeOffset = part.length()
        onProgress(resumeOffset)

        try {
            RandomAccessFile(part, "rw").use { output ->
                client.copyObject(photo, resumeOffset) { offset, data ->
                    output.seek(offset)
                    output.write(data)
                    output.fd.sync()
                    onProgress(offset + data.size)
                }
            }
            currentCoroutineContext().ensureActive()
            if (part.length() != photo.size) {
                throw IOException("文件长度不完整，已保留 ${part.length()} 字节断点")
            }
            currentCoroutineContext().ensureActive()
            client.ensureConnected()
            publishImage(part, photo)
        } catch (error: Throwable) {
            // A failed or cancelled connection can never resume this fragment again.
            if (error is CancellationException || !client.isConnected) part.delete()
            throw error
        }
    }

    /** 按 RAW/JPEG 选 MIME 发布到系统相册；文件名保留相机原始扩展名。 */
    private fun publishImage(part: File, photo: PhotoObject): Uri {
        val resolver = context.contentResolver
        // 同名同大小已存在 → 视为已下载，直接收下：否则 MediaStore 会另存成「名字 (1).JPG」，
        // 同样的照片在相册里出现两份（真机踩过）。
        existingImage(photo)?.let { existing ->
            part.delete()
            return existing
        }
        val isRaw = photo.isRaw && !photo.isJpeg
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photo.filename)
            put(MediaStore.Images.Media.MIME_TYPE, if (isRaw) "image/x-nikon-nef" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NiKConnect")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("无法创建系统相册项目")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                part.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
            } ?: throw IOException("无法写入系统相册")
            val published = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            if (published == 0) throw IOException("无法发布到系统相册")
            part.delete()
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    /** 相册里是否已有同名同大小的文件；大小不一致视为旧残件，删掉后重新写入。 */
    private fun existingImage(photo: PhotoObject): Uri? = runCatching {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(photo.filename, "Pictures/NiKConnect/")
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val size = cursor.getLong(1)
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                if (size == photo.size) return@use uri
                context.contentResolver.delete(uri, null, null)
            }
            null
        }
    }.getOrNull()

    companion object {
        fun cleanupStalePartials(context: Context) {
            cleanupPartialFiles(File(context.cacheDir, "downloads"))
        }

        fun cleanupPartials(context: Context, connectionId: String) {
            cleanupPartialFiles(File(context.cacheDir, "downloads"), connectionId)
        }
    }
}

internal fun cleanupPartialFiles(directory: File, connectionId: String? = null) {
    val prefix = connectionId?.let { "$it-" }
    directory.listFiles()
        ?.filter {
            it.isFile &&
                it.name.endsWith(".part") &&
                (prefix == null || it.name.startsWith(prefix))
        }
        ?.forEach { it.delete() }
}

internal fun partialFile(directory: File, connectionId: String, photo: PhotoObject): File {
    require(connectionId.isNotBlank()) { "相机连接标识为空，请重新连接" }
    val safeName = photo.filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return File(directory, "$connectionId-${photo.handle.toString(16)}-${photo.size}-$safeName.part")
}
