package com.chrisz.zfrelay.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.chrisz.zfrelay.ptp.PhotoObject
import com.chrisz.zfrelay.ptp.PtpIpClient
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
        require(photo.isJpeg) { "第一版仅下载原始 JPEG，RAW 支持将在真机验证后开启" }
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
            if (part.length() != photo.size) {
                throw IOException("文件长度不完整，已保留 ${part.length()} 字节断点")
            }
            currentCoroutineContext().ensureActive()
            client.ensureConnected()
            publishJpeg(part, photo.filename)
        } catch (error: Throwable) {
            // A failed or cancelled connection can never resume this fragment again.
            if (error is CancellationException || !client.isConnected) part.delete()
            throw error
        }
    }

    private fun publishJpeg(part: File, filename: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ZF Relay")
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
