package com.chrisz.nikconnect.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 云创登录结果：token 与用户 guid 由 WebView 抓取。
 * token 来源：登录完成后从 keycloak session cookie / localStorage 读取失败时，
 * 降级用「用户手动粘贴 token」输入框（Web 端 localStorage key: keycloak access_token）。
 */
data class CloudAuthState(
    val token: String? = null,
    val guid: String? = null,
)

/**
 * Nikon ID 登录 WebView。登录完成后回跳云创页面，从 Cookie/localStorage 提取 token。
 * 授权码流程（redirect 到 app scheme）在本 App 未注册 URI scheme，故用登录站点内 token 提取。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CloudLoginScreen(
    onDone: (CloudAuthState) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var manualToken by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.weight(1f))
            Text("登录尼康云创", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showManual = !showManual }) { Text("手动输入") }
        }
        if (showManual) {
            OutlinedTextField(
                value = manualToken,
                onValueChange = { manualToken = it },
                label = { Text("粘贴 access_token（浏览器 F12 → Application → Local Storage）") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                minLines = 2,
            )
            OutlinedTextField(
                value = remember { mutableStateOf("") }.value,
                onValueChange = {},
                label = { Text("guid（相机注册 ID，云创 Web 我的相机页 URL 中取）") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                enabled = false,
            )
            Button(
                onClick = { onDone(CloudAuthState(token = manualToken.trim(), guid = null)) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("使用该 token") }
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 尝试从云创域名 localStorage 提取 keycloak token
                            view?.evaluateJavascript(
                                """
                                (function(){
                                  try {
                                    var kc = null;
                                    for (var i = 0; i < localStorage.length; i++) {
                                      var k = localStorage.key(i);
                                      if (k && k.indexOf('kc-c0004') === 0) { kc = localStorage.getItem(k); break; }
                                    }
                                    if (kc) { return kc; }
                                    return '';
                                  } catch(e) { return ''; }
                                })()
                                """.trimIndent(),
                            ) { value ->
                                if (value != null && value.length > 40) {
                                    val t = value.trim('"').trim()
                                    if (t.contains("access_token") || t.length > 200) {
                                        // kc-c0004 存储 JSON 形如 {"access_token":"...","...}
                                        val token = extractJsonField(t, "access_token") ?: t
                                        val guid = extractJsonField(t, "guid")
                                        onDone(CloudAuthState(token = token, guid = guid))
                                    }
                                }
                            }
                        }
                    }
                    loadUrl("https://imagingcloud.nikon.com.cn/recipe/index/")
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Text(
            "登录完成后自动回到 App。若未自动返回，用「手动输入」粘贴 token。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

internal fun extractJsonField(json: String, field: String): String? {
    val marker = "\"$field\""
    val idx = json.indexOf(marker)
    if (idx < 0) return null
    var i = idx + marker.length
    while (i < json.length && json[i] != ':') i++
    i++
    while (i < json.length && json[i] == ' ') i++
    if (i >= json.length || json[i] != '"') return null
    i++
    val sb = StringBuilder()
    while (i < json.length && json[i] != '"') {
        if (json[i] == '\\' && i + 1 < json.length) { i++; sb.append(json[i]) } else sb.append(json[i])
        i++
    }
    return sb.toString()
}
