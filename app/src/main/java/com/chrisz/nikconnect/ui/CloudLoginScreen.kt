package com.chrisz.nikconnect.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.chrisz.nikconnect.cloud.CLOUD_INDEX_URL
import com.chrisz.nikconnect.cloud.CLOUD_CAMERA_URL
import com.chrisz.nikconnect.cloud.guidFromJwt
import com.chrisz.nikconnect.cloud.guidFromUrl
import com.chrisz.nikconnect.cloud.isGuidLike

/** 在当前页面 origin 内扫描 localStorage/sessionStorage/cookie，提取 access_token（JWT 以 eyJ 开头，不含 |）。 */
private val TOKEN_EXTRACT_JS = """
(function(){
  function pick(str){
    if(!str || str.length < 20) return null;
    var m = str.match(/"access_token"\s*:\s*"([^"]{40,})"/);
    if(m) return {t: m[1], g: (str.match(/"guid"\s*:\s*"([^"]+)"/)||[])[1] || ''};
    return null;
  }
  var GUID = /^[0-9A-HJKMNP-TV-Z]{26}$/;
  var UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
  function scanGuid(store){
    try{
      for(var i=0;i<store.length;i++){
        var k = store.key(i); var v = store.getItem(k) || '';
        if(/guid/i.test(k) && (GUID.test(v) || UUID.test(v))) return v;
        var m = v.match(/"(?:userGuid|user_guid|guid|userId|user_id)"\s*:\s*"([^"]{8,64})"/);
        if(m && (GUID.test(m[1]) || UUID.test(m[1]))) return m[1];
      }
    }catch(e){}
    return null;
  }
  function scan(store){
    try{
      for(var i=0;i<store.length;i++){
        var k = store.key(i); var v = store.getItem(k);
        var r = pick(v || '');
        if(r) return r;
        if(v && v.indexOf('eyJ') === 0 && v.length > 200) return {t: v, g: ''};
      }
    }catch(e){}
    return null;
  }
  var gOnly = scanGuid(localStorage) || scanGuid(sessionStorage) || '';
  var r = scan(localStorage) || scan(sessionStorage);
  if(r && !r.g && gOnly) r.g = gOnly;
  if(!r){
    try{
      var m = document.cookie.match(/access_token=([^;]+)/);
      if(m){ var t = decodeURIComponent(m[1]); if(t.length > 40) r = {t: t, g: (document.cookie.match(/guid=([^;]+)/)||[])[1] || ''}; }
    }catch(e){}
  }
  if(!r && gOnly) return '|' + gOnly;
  return r ? (r.t + '|' + r.g) : '';
})()
""".trimIndent()

/** 从请求头里取云创 token：优先 `access_token`，其次 `Authorization: Bearer`。 */
private fun tokenFromHeaders(headers: Map<String, String>?): String? {
    if (headers.isNullOrEmpty()) return null
    headers.entries.firstOrNull { it.key.equals("access_token", ignoreCase = true) }
        ?.value?.trim()?.trim('"')?.takeIf { it.length > 40 }?.let { return it }
    headers.entries.firstOrNull { it.key.equals("authorization", ignoreCase = true) }
        ?.value?.trim()
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)?.trim()?.trim('"')?.takeIf { it.length > 40 }?.let { return it }
    return null
}

/** 从请求头里取 guid：`guid` / `x-guid` / `user-guid` / `userid` 等常见命名。 */
private fun guidFromHeaders(headers: Map<String, String>?): String? {
    if (headers.isNullOrEmpty()) return null
    val candidate = headers.entries.firstOrNull { entry ->
        val key = entry.key.lowercase()
        key == "guid" || key == "userid" || key.contains("guid")
    }?.value?.trim()?.trim('"') ?: return null
    return candidate.takeIf(::isGuidLike)
}

/** OAuth 隐式/查询串回跳里直接带 token 的情况：`#access_token=...` 与 `?access_token=...`。 */
private fun tokenFromUrl(url: String): String? {
    val marker = "access_token="
    val at = url.indexOf(marker)
    if (at < 0) return null
    return url.substring(at + marker.length)
        .substringBefore('&')
        .substringBefore('#')
        .takeIf { it.length > 40 }
}

/**
 * 云创登录结果：token 与用户 guid 由 WebView 抓取。
 *
 * token 首选来源是页面自身请求云创接口时的请求头 `access_token`——即便网页把 token 放在 HttpOnly cookie 里，
 * JS 读不到，请求头里也一定是明文。抓不到时再退回 localStorage/sessionStorage 扫描与用户手动粘贴。
 */
data class CloudAuthState(
    val token: String? = null,
    val guid: String? = null,
)

/**
 * Nikon ID 登录 WebView。登录完成后回跳云创页面，从接口请求头/localStorage 提取 token。
 * 授权码流程（redirect 到 app scheme）在本 App 未注册 URI scheme，故用登录站点内 token 提取。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CloudLoginScreen(
    onDone: (CloudAuthState) -> Unit,
    onDismiss: () -> Unit,
    startUrl: String = CLOUD_INDEX_URL,
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var manualToken by remember { mutableStateOf("") }
    var manualGuid by remember { mutableStateOf("") }
    var capturedToken by remember { mutableStateOf("") }
    var capturedGuid by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var tokenVisible by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }

    /** 统一入库：token 与 guid 各自只在还没有值时补写，避免被 URL 里别的 ULID 覆盖。 */
    fun acceptToken(token: String?, guid: String?) {
        if (completed) return
        val t = token?.trim().orEmpty()
        val g = guid?.trim().orEmpty().ifEmpty { if (t.length > 40) guidFromJwt(t).orEmpty() else "" }
        if (g.isNotEmpty() && capturedGuid.isEmpty()) capturedGuid = g
        if (t.length <= 40 || capturedToken.isNotEmpty()) return
        capturedToken = t
    }

    /** 从 WebView 当前页提取 token；未命中时给出可操作提示（旧版静默无反馈）。 */
    fun extractFromPage() {
        val view = webView ?: run {
            Toast.makeText(context, "页面还没加载好", Toast.LENGTH_SHORT).show()
            return
        }
        view.evaluateJavascript(TOKEN_EXTRACT_JS) { value ->
            val raw = value?.trim('"', ' ', '|').orEmpty()
            if (raw.isEmpty()) {
                Toast.makeText(
                    context,
                    "页面里没有 token：请在网页中点开任意方案，让页面先发出一次接口请求",
                    Toast.LENGTH_LONG,
                ).show()
                return@evaluateJavascript
            }
            acceptToken(raw.substringBefore('|'), raw.substringAfter('|', ""))
        }
    }

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
                label = { Text("粘贴 access_token（一般无需手动，登录后会自动捕获）") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                minLines = 2,
            )
            OutlinedTextField(
                value = manualGuid,
                onValueChange = { manualGuid = it },
                label = { Text("账号 guid（可留空，自动从接口地址提取）") },
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
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已捕获 access_token", style = MaterialTheme.typography.titleSmall)
                    TextField(
                        value = capturedToken,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        visualTransformation = if (tokenVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        textStyle = MaterialTheme.typography.bodySmall,
                        trailingIcon = {
                            TextButton(onClick = { tokenVisible = !tokenVisible }) {
                                Text(if (tokenVisible) "隐藏" else "显示")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = capturedGuid,
                        onValueChange = { capturedGuid = it },
                        label = { Text("账号 guid（可留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("access_token", capturedToken))
                                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("复制 token") }
                        Button(
                            onClick = {
                                completed = true
                                onDone(
                                    CloudAuthState(
                                        token = capturedToken,
                                        guid = capturedGuid.ifBlank { null },
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("返回 App") }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (capturedToken.isEmpty()) "自动捕获：等待接口请求…"
                    else "自动捕获：成功（token ${capturedToken.take(8)}…）",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (capturedToken.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
                Text(
                    "登录后在网页里点开任意方案即可，无需手动操作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (capturedToken.isEmpty()) {
                TextButton(onClick = { webView?.reload() }) { Text("重新加载") }
            }
            TextButton(onClick = { extractFromPage() }) { Text("再查一次") }
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    webView = this
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean = false

                        /** 只要页面自身调了云创接口，请求头里就带着 access_token——这里是最可靠的抓取点。 */
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val headers = request?.requestHeaders
                            val url = request?.url?.toString().orEmpty()
                            val token = tokenFromHeaders(headers)
                            if (token != null) {
                                val guid = guidFromHeaders(headers) ?: guidFromUrl(url)
                                view?.post { acceptToken(token, guid) }
                            } else if (capturedGuid.isEmpty()) {
                                // 只拿到 guid、还没拿到 token 的请求也顺手记下
                                val guid = guidFromHeaders(headers) ?: guidFromUrl(url)
                                if (guid != null) view?.post { acceptToken(null, guid) }
                            }
                            return null
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            val direct = url?.let(::tokenFromUrl)
                            if (direct != null) view?.post { acceptToken(direct, null) }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 兼容 token 落在 localStorage 里的情况；未命中时用户可点上方「提取 token」。
                            view?.evaluateJavascript(TOKEN_EXTRACT_JS) { value ->
                                val raw = value?.trim('"', ' ', '|').orEmpty()
                                if (raw.isEmpty()) return@evaluateJavascript
                                view.post {
                                    acceptToken(raw.substringBefore('|'), raw.substringAfter('|', ""))
                                }
                            }
                        }
                    }
                    loadUrl(startUrl)
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}
