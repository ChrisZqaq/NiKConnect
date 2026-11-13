package com.chrisz.nikconnect.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/**
 * 可信域：只有尼康自己的页面/接口才允许贡献 token 与 guid。
 * 登录链路会经过 auth.accounts.cld.nikon.com.cn、imagingcloud.nikon.com.cn 等，均以 nikon.com.cn 结尾。
 */
private val TRUSTED_HOST_SUFFIXES = listOf("nikon.com.cn", "nikon.com", "nikonimaging.com")

private fun isTrustedUri(uri: String?): Boolean {
    val host = runCatching { java.net.URI(uri.orEmpty()).host }.getOrNull() ?: return false
    return TRUSTED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
}

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
    /** Keycloak 的 refresh_token：有了它 access_token 过期能自动续，不用用户再点登录。 */
    val refreshToken: String? = null,
)

/**
 * 读 keycloak-js 的 refresh_token。
 *
 * 尼康云创的 Web 端用标准 keycloak-js（`_app-*.js` 里有 `refreshToken=I`、`onTokenExpired`），
 * 实例挂在页面上下文里。不同构建挂的位置不一样，这里把常见位置都试一遍；
 * 都取不到就返回空，不影响登录主流程。
 */
private val REFRESH_PROBE_JS = """
(function(){
  try{
    var k = window.keycloak || window.__keycloak || window.kc || (window.__NEXT_DATA__ && window.__NEXT_DATA__.keycloak);
    if (k && k.refreshToken) return k.refreshToken;
  }catch(e){}
  try{
    for (var p in window){
      try{
        var v = window[p];
        if (v && typeof v === 'object' && typeof v.refreshToken === 'string' && v.refreshToken.length > 20) return v.refreshToken;
      }catch(e){}
    }
  }catch(e){}
  return '';
})()
""".trimIndent()

/**
 * Nikon ID 登录页：一个 WebView，登录成功自动返回。
 *
 * 没有手动输入、没有复制、没有「提取」按钮——手机上不存在 F12，
 * 用户唯一要做的就是在网页里完成登录；token 从页面自己发出的请求头里取。
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
    var capturedToken by remember { mutableStateOf("") }
    var capturedGuid by remember { mutableStateOf("") }
    var completed by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }

    /** 抓到 token 立刻返回 App，用户不需要做任何确认动作。 */
    fun acceptToken(token: String?, guid: String?) {
        if (completed) return
        val t = token?.trim().orEmpty()
        val g = guid?.trim().orEmpty().ifEmpty { if (t.length > 40) guidFromJwt(t).orEmpty() else "" }
        if (g.isNotEmpty() && capturedGuid.isEmpty()) capturedGuid = g
        if (t.length <= 40 || capturedToken.isNotEmpty()) return
        capturedToken = t
        // 顺手把 keycloak 的 refresh_token 读出来：拿不到就照旧返回（只是少了自动续期）
        webView?.evaluateJavascript(REFRESH_PROBE_JS) { raw ->
            val refresh = raw?.trim()?.trim('"')?.takeIf { it.length > 20 && it != "null" }
            webView?.post {
                if (completed) return@post
                completed = true
                onDone(CloudAuthState(token = t, guid = g.ifBlank { null }, refreshToken = refresh))
            }
        } ?: run {
            completed = true
            onDone(CloudAuthState(token = t, guid = g.ifBlank { null }))
        }
    }

    // 25 秒还没抓到：给一句可操作的提示，而不是让用户对着白屏等
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(25_000)
        slow = true
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
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭")
            }
            Text("登录尼康云创", style = MaterialTheme.typography.titleMedium)
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webView = this
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val uri = request?.url ?: return false
                            val scheme = uri.scheme.orEmpty().lowercase()
                            // 自定义 scheme（含 App 自己的回调）不在 WebView 里处理，避免任意 intent 被页面触发
                            if (scheme != "https" && scheme != "http") return true
                            if (isTrustedUri(uri.toString())) return false
                            // 尼康域名之外的链接交给系统浏览器，不在承载登录态的 WebView 里打开
                            runCatching {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                            }
                            return true
                        }

                        /** 只要页面自身调了云创接口，请求头里就带着 access_token——这里是最可靠的抓取点。 */
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val headers = request?.requestHeaders
                            val url = request?.url?.toString().orEmpty()
                            // 非尼康域名的请求一律不参与凭据捕获（防第三方页面塞 token/guid）
                            if (!isTrustedUri(url)) return null
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
                            val direct = url?.takeIf(::isTrustedUri)?.let(::tokenFromUrl)
                            if (direct != null) view?.post { acceptToken(direct, null) }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 兼容 token 落在 localStorage 的情况
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
        if (slow && !completed) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "完成登录后会自动返回",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { webView?.reload() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("重新加载")
                }
            }
        }
    }
}
