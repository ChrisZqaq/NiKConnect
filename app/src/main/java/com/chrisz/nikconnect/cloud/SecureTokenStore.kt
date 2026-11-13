package com.chrisz.nikconnect.cloud

import android.content.Context
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 云创 access_token 的本地保存。
 *
 * 为什么不是明文 prefs：token 等价于账号，明文落在 `/data/data/.../shared_prefs` 里，
 * 任何拿到设备（或备份）的人都能直接用它调尼康云创接口。这里用 Android Keystore 里的
 * AES/GCM 密钥加密后再存 prefs，密钥不出 Keystore。
 *
 * 代价是：DB 损坏、恢复出厂、卸载重装（`adb uninstall` 或用 `-r` 之外的路径）都会让旧密文
 * 解不开；此时 [load] 返回 null 并顺手清掉坏数据，用户可以重新登录，不会崩。
 */
internal class SecureTokenStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(token: String, refreshToken: String? = null) {
        if (token.isBlank()) return
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            // 明文是 JSON：老版本存的是裸 token，读取端要兼容（否则升级即掉登录）
            val plain = buildJsonObject {
                put("a", JsonPrimitive(token))
                refreshToken?.takeIf { it.isNotBlank() }?.let { put("r", JsonPrimitive(it)) }
            }.toString()
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + encrypted
            prefs.edit { putString(KEY_TOKEN, Base64.encodeToString(payload, Base64.NO_WRAP)) }
        }
    }

    fun load(): String? = loadPair().first

    /** 读 (access_token, refresh_token)；解不开或没有就返回 (null, null) 并清掉坏数据。 */
    fun loadPair(): Pair<String?, String?> {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null to null
        val decrypted = runCatching {
            val payload = Base64.decode(stored, Base64.NO_WRAP)
            if (payload.size <= IV_LENGTH) return@runCatching null
            val iv = payload.copyOfRange(0, IV_LENGTH)
            val body = payload.copyOfRange(IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
        if (decrypted.isNullOrBlank()) {
            clear()
            return null to null
        }
        val element = runCatching { Json.parseToJsonElement(decrypted) }.getOrNull() as? JsonObject
        if (element == null) {
            // 0.6.14 存的是裸 token
            return decrypted to null
        }
        val access = element.str("a")?.takeIf { it.isNotBlank() }
        val refresh = element.str("r")?.takeIf { it.isNotBlank() }
        if (access == null) {
            clear()
            return null to null
        }
        return access to refresh
    }

    fun clear() {
        prefs.edit { remove(KEY_TOKEN) }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "ui_prefs"
        const val KEY_TOKEN = "cloud_token_enc"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "nikconnect_cloud_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
