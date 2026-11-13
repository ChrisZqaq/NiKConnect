package com.chrisz.nikconnect.cloud

import java.io.File

/**
 * 云创接口的原始报文落盘（仅 debug 构建）。
 *
 * 为什么要有：`logcat` 一行只能带 200 字，排查「服务端到底返回了什么结构」时不够。
 * 开启后每次请求写一对文件到 `cacheDir/cloud_debug/`，用
 * `adb pull /sdcard/Android/data/<pkg>/cache/cloud_debug` 就能拿到完整 JSON。
 *
 * 只保留最近 [MAX_FILES] 个文件，避免占满缓存；内容不含 token（请求头不落盘）。
 */
internal object CloudDebug {
    private const val MAX_FILES = 40

    /** 由 `MainActivity` 在启动时设置；为空表示不落盘（例如 JVM 单测）。 */
    @Volatile
    var dir: File? = null

    @Volatile
    var enabled: Boolean = false

    fun record(callId: String, request: String?, response: String?) {
        val target = dir ?: return
        if (!enabled) return
        runCatching {
            if (!target.exists()) target.mkdirs()
            val stamp = System.currentTimeMillis()
            val safe = callId.replace(Regex("[^A-Za-z0-9_]"), "_")
            File(target, "$safe-$stamp.req.txt").writeText(request.orEmpty())
            File(target, "$safe-$stamp.res.txt").writeText(response.orEmpty())
            target.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
        }
    }
}
