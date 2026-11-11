package com.chrisz.nikconnect.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
    var manualGuid by remember { mutableStateOf("") }
    var capturedToken by remember { mutableStateOf("") }
    var capturedGuid by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.stopLoading()
                view.destroy()
            }
        }
    }

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
                value = manualGuid,
                onValueChange = { manualGuid = it },
                label = { Text("相机注册 ID（guid）") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
            )
            Button(
                onClick = {
                    val token = manualToken.trim()
                    if (token.isNotEmpty()) onDone(CloudAuthState(token = token, guid = manualGuid.trim().ifEmpty { null }))
                },
                enabled = manualToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("使用该 token") }
        }
        if (capturedToken.isNotBlank()) {
            Text("已捕获 access_token。登录页不会自动关闭，请复制令牌后返回使用。", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            OutlinedTextField(
                value = capturedGuid,
                onValueChange = { capturedGuid = it },
                label = { Text("相机注册 ID（guid，可选）") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = capturedToken,
                onValueChange = {},
                label = { Text("access_token") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                readOnly = true,
                minLines = 2,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("access_token", capturedToken))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("复制 token") }
                Button(
                    onClick = {
                        completed = true
                        onDone(CloudAuthState(token = capturedToken, guid = capturedGuid.ifBlank { null }))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("返回 App") }
            }
        }
        if (capturedToken.isBlank()) AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webView = this
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
                                if (!completed && value != null && value.length > 40) {
                                    val t = value.trim('"').trim()
                                    if (t.contains("access_token") || t.length > 200) {
                                        // kc-c0004 存储 JSON 形如 {"access_token":"...","...}
                                        val token = extractJsonField(t, "access_token") ?: t
                                        val guid = extractJsonField(t, "guid")
                                        capturedToken = token
                                        capturedGuid = guid.orEmpty()
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
            "登录完成后复制 token，点“返回 App”继续；手动输入也可粘贴 token 并填写 guid。",
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
