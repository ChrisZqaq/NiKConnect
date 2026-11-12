package com.chrisz.nikconnect.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 云创封面加载器：内存 LruCache + 磁盘缓存。
 *
 * 云创封面是 CloudFront 签名地址（约 1 小时有效、不绑 IP），所以只缓存**字节**、按 URL 做 key；
 * URL 换了自然重新取，过期也不用主动清理。
 * 不引入第三方图片库：只需 JPEG 解码 + 两级缓存，省一个依赖。
 */
internal object CloudImageLoader {

    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(context: Context, url: String, maxWidth: Int = 1_200): Bitmap? =
        withContext(Dispatchers.IO) {
            memory.get(url)?.let { return@withContext it }
            val dir = File(context.cacheDir, "cloud_covers").apply { mkdirs() }
            val file = File(dir, sha256(url) + ".jpg")
            val bytes = if (file.isFile && file.length() > 0) {
                file.readBytes()
            } else {
                download(url)?.also { raw -> runCatching { file.writeBytes(raw) } }
            } ?: return@withContext null
            decode(bytes, maxWidth)?.also { memory.put(url, it) }
        }

    private fun download(url: String): ByteArray? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "NiKConnect/0.5 (Android)")
            val code = conn.responseCode
            if (code !in 200..299) return null
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun decode(bytes: ByteArray, maxWidth: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxWidth) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/** 带占位与失败兜底的远程图片。 */
@Composable
internal fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, url) {
        value = if (url.isNullOrBlank()) {
            null
        } else {
            CloudImageLoader.load(context, url)?.asImageBitmap()
        }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        placeholder()
    }
}
