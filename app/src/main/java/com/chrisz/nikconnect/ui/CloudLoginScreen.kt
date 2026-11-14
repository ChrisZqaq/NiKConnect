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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.chrisz.nikconnect.cloud.CLOUD_INDEX_URL

import com.chrisz.nikconnect.cloud.CloudCapture
import com.chrisz.nikconnect.cloud.CloudOidc

import com.chrisz.nikconnect.cloud.guidFromJwt
import com.chrisz.nikconnect.cloud.guidFromUrl
import com.chrisz.nikconnect.cloud.isGuidLike

/** 在当前页面 origin 内扫描 localStorage/sessionStorage/cookie，提取 access_token（JWT 以 eyJ 开头，不含 |）。 */
private val TOKEN_EXTRACT_JS = """
(function(){
  // 1) 尼康云创用的是 keycloak-js 实例，token 常驻在实例内存里（不落 localStorage）
  try{
    var cands = [window.keycloak, window.__keycloak, window.kc];
    try{ if(window.__NEXT_DATA__ && window.__NEXT_DATA__.keycloak) cands.push(window.__NEXT_DATA__.keycloak); }catch(e){}
    for(var ci=0; ci<cands.length; ci++){
      var k = cands[ci];
      if(k && typeof k.token === 'string' && k.token.length > 40){
        var gk = '';
        try{ gk = (k.tokenParsed && (k.tokenParsed.guid || k.tokenParsed.userGuid)) || ''; }catch(e){}
        return k.token + '|' + gk;
      }
    }
  }catch(e){}
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

private fun tokenFromHeaders(headers: Map<String, String>?): String? =
    CloudCapture.accessTokenFromHeaders(headers)

private fun guidFromHeaders(headers: Map<String, String>?): String? = CloudCapture.guidFromHeaders(headers)

private fun tokenFromUrl(url: String): String? = CloudCapture.accessTokenFromUrl(url)

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
    // 一次进程只探一次，避免每次登录都往外发请求
    var probeDone by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    var oidcTried by remember { mutableStateOf(false) }
    var oidcVerifier by remember { mutableStateOf<String?>(null) }
    var oidcState by remember { mutableStateOf("") }
    var interactiveTried by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /**
     * 调试用：在**页面上下文**里用同一份 token 打一次 PB002（body 故意非法），
     * 用来分辨 403 是「账号权限」还是「我们的请求形状」。
     * 只在 debuggable 构建（CloudDebug.enabled）里跑，且每次进程只跑一次。
     */
    fun probeCreateBookInPage(token: String?, guid: String?) {
        val view = webView ?: return
        if (probeDone) return
        probeDone = true
        val js = """
            (function(){
              var kc = window.keycloak || window.__keycloak || window.kc || null;
              var tk = (kc && kc.token) || '$token';
              var gd = (kc && kc.tokenParsed && kc.tokenParsed.guid) || '$guid';
              if(!tk || !gd){ console.log('PROBE skip no-credentials'); return; }
              function hit(body, tag){
                return fetch('https://api.user.ir.imagingcloud.nikon.com.cn/publicbook/'+gd,{
                  method:'POST',
                  headers:{'Authorization':'Bearer '+tk},
                  body:JSON.stringify(body)
                }).then(function(r){return r.text().then(function(b){console.log('PROBE '+tag+' '+r.status+' '+(b||'').slice(0,80));});})
                 .catch(function(e){console.log('PROBE '+tag+' err '+e);});
              }
              return hit({name:''}, 'empty-name');
            })();
        """.trimIndent()
        runCatching { view.evaluateJavascript(js, null) }
    }

    fun finishWith(token: String?, guid: String?, refresh: String?) {
        if (completed) return
        completed = true
        if (com.chrisz.nikconnect.cloud.CloudDebug.enabled) probeCreateBookInPage(token, guid ?: capturedGuid)
        onDone(CloudAuthState(token = token, guid = guid?.takeIf { it.isNotBlank() }, refreshToken = refresh))
    }

    /**
     * 用自己的 OIDC 授权码流程换 token。
     *
     * `prompt=none`：WebView 里已有 Nikon ID 会话就直接回码；没有就回 `error=login_required`，
     * 那时才把用户放回网页去登录。这条路的目的是绕开 Next 前端在第三方 iframe 里判会话的毛病。
     */
    fun startOidc(promptNone: Boolean = true) {
        val view = webView ?: return
        val (verifier, challenge) = CloudOidc.newPkce()
        val state = CloudOidc.newState()
        oidcVerifier = verifier
        oidcState = state
        oidcTried = true
        runCatching { android.util.Log.d("NikConnect", "oidc start promptNone=$promptNone") }
        view.loadUrl(CloudOidc.authorizeUrl(state, challenge, promptNone))
    }

    fun handleCallback(url: String) {
        val cb = CloudOidc.parseCallback(url) ?: return
        if (cb.code == null) {
            runCatching { android.util.Log.d("NikConnect", "oidc no-code error=${cb.error}") }
            // 没有 Keycloak 会话：改成交互模式，让 Keycloak 自己的登录页出来（尼康云创用 Nikon ID 代理登录）
            if (cb.error == "login_required" && !interactiveTried) {
                interactiveTried = true
                webView?.post { startOidc(promptNone = false) }
            }
            return
        }
        // CSRF：state 必须存在且与本机生成的完全一致。原来"空 state 就放行"等于没校验。
        if (cb.state.isBlank() || cb.state != oidcState) return
        val verifier = oidcVerifier
        runCatching { android.util.Log.d("NikConnect", "oidc code received") }
        scope.launch {
            val http = com.chrisz.nikconnect.cloud.CloudHttp()
            val raw = runCatching {
                http.post(
                    com.chrisz.nikconnect.cloud.AUTH_HOST + "/protocol/openid-connect/token",
                    CloudOidc.exchangeBody(cb.code, CloudOidc.REDIRECT_URI, verifier),
                    emptyMap(),
                    "OIDC_CODE",
                    contentType = "application/x-www-form-urlencoded",
                )
            }.getOrElse { e ->
                runCatching {
                    android.util.Log.d(
                        "NikConnect",
                        "oidc exchange failed: ${e::class.simpleName} ${e.message}",
                    )
                }
                oidcTried = false
                webView?.post { webView?.loadUrl(CLOUD_INDEX_URL) }
                return@launch
            }
            val parsed = com.chrisz.nikconnect.cloud.parseTokenResponse(raw)
            if (parsed == null) {
                oidcTried = false
                webView?.post { webView?.loadUrl(CLOUD_INDEX_URL) }
                return@launch
            }
            val (access, refresh) = parsed
            // 账号 ID 从 userinfo 拿权威值；拿不到也不阻塞登录
            val guid = runCatching {
                val body = http.get(
                    com.chrisz.nikconnect.cloud.AUTH_HOST + "/protocol/openid-connect/userinfo",
                    mapOf("Authorization" to "Bearer $access"),
                    "OIDC_USERINFO",
                )
                com.chrisz.nikconnect.cloud.parseIdentityForLogin(body)
            }.getOrNull()
            runCatching { android.util.Log.d("NikConnect", "oidc ok guid=${guid?.takeLast(6)}") }
            runCatching { android.util.Log.d("NikConnect", "our-token ${CloudCapture.jwtSummary(access)}") }
            webView?.post { finishWith(access, guid, refresh) }
        }
    }

    /** 抓到 token 立刻返回 App，用户不需要做任何确认动作。 */
    fun acceptToken(token: String?, guid: String?) {
        if (completed) return
        val t = token?.trim().orEmpty()
        val g = guid?.trim().orEmpty().ifEmpty { if (t.length > 40) guidFromJwt(t).orEmpty() else "" }
        if (g.isNotEmpty() && capturedGuid.isEmpty()) capturedGuid = g
        if (t.length <= 40 || capturedToken.isNotEmpty()) return
        capturedToken = t
        runCatching { android.util.Log.d("NikConnect", "token captured len=${t.length}") }
        runCatching { android.util.Log.d("NikConnect", "spa-token ${CloudCapture.jwtSummary(t)}") }
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

    // 登录完成后 token 可能晚几秒才出现在 DOM/JS 里，轮询几次比"只探一次"稳
    LaunchedEffect(Unit) {
        repeat(15) {
            kotlinx.coroutines.delay(2_000)
            if (completed) return@LaunchedEffect
            val view = webView ?: return@LaunchedEffect
            view.evaluateJavascript(TOKEN_EXTRACT_JS) { value ->
                val (token, guid) = CloudCapture.parseProbeResult(value)
                if (token != null || guid != null) view.post { acceptToken(token, guid) }
            }
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
                    // 只有 debuggable 包才允许 chrome://inspect 连进来；release 里开着等于把
                    // 登录页的 DOM 与存储暴露给同一台电脑上的任何 adb 使用者（评审 F13）。
                    val debuggable = (ctx.applicationInfo.flags and
                        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    WebView.setWebContentsDebuggingEnabled(debuggable)
                    // 把页面 console 转进 logcat：调试构建里用来观察页面上下文发起的请求结果
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                            if (!debuggable) return true
                            runCatching {
                                android.util.Log.d("NikConnect", "console ${msg.message()}")
                            }
                            return true
                        }
                    }
                    // 登录要跨 accounts.cld.nikon.com.cn ↔ imagingcloud.nikon.com.cn 两个域，
                    // 不放第三方 cookie 时 SSO 会"转一圈回到落地页"，看起来就是点登录没反应。
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.domStorageEnabled = true
                    webView = this
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            super.onReceivedError(view, request, error)
                            runCatching {
                                android.util.Log.d(
                                    "NikConnect",
                                    "load error ${request?.url?.host} ${error?.errorCode} ${error?.description}",
                                )
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val uri = request?.url ?: return false
                            val navLog = "nav ${uri.host}${uri.path.orEmpty().take(60)}"
                            runCatching { android.util.Log.d("NikConnect", navLog) }
                            if (uri.toString().startsWith(CloudOidc.REDIRECT_URI, ignoreCase = true)) {
                                // 授权码回跳：不要真的去加载这个地址，直接换 token
                                view?.post { handleCallback(uri.toString()) }
                                return true
                            }
                            val scheme = uri.scheme.orEmpty().lowercase()
                            // 自定义 scheme（含 App 自己的回调）不在 WebView 里处理，避免任意 intent 被页面触发
                            if (scheme != "https" && scheme != "http") return true
                            if (isTrustedUri(uri.toString())) return false
                            runCatching { android.util.Log.d("NikConnect", "nav external -> browser: ${uri.host}") }
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
                            if (url.contains("/protocol/openid-connect/token") || url.contains("/protocol/openid-connect/auth")) {
                                runCatching { android.util.Log.d("NikConnect", "oidc ${url.take(120)}") }
                            }
                            if (url.contains("accounts.cld.nikon.com.cn") || url.contains("auth.accounts")) {
                                runCatching {
                                    android.util.Log.d(
                                        "NikConnect",
                                        "sso ${request?.method} ${request?.url?.host}${request?.url?.path?.take(50)}",
                                    )
                                }
                            }
                            if (url.contains("api.user.")) {
                                // 只打 key 不打 value：token 不能进日志
                                runCatching {
                                    android.util.Log.d(
                                        "NikConnect",
                                        "xhr ${request?.url?.host}${request?.url?.path?.take(40)} keys=${headers?.keys?.joinToString(",")}",
                                    )
                                }
                            }
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
                            runCatching {
                                android.util.Log.d(
                                    "NikConnect",
                                    "page start ${url?.let { java.net.URI(it).host }}${url?.let { java.net.URI(it).path }?.take(70)}",
                                )
                            }
                            val direct = url?.takeIf(::isTrustedUri)?.let(::tokenFromUrl)
                            if (direct != null) view?.post { acceptToken(direct, null) }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            runCatching {
                                val cookies = android.webkit.CookieManager.getInstance()
                                val cloud = cookies.getCookie("https://imagingcloud.nikon.com.cn").orEmpty()
                                val sso = cookies.getCookie("https://accounts.cld.nikon.com.cn").orEmpty()
                                fun names(raw: String) = raw.split("; ").mapNotNull { it.substringBefore('=').takeIf(String::isNotBlank) }
                                android.util.Log.d(
                                    "NikConnect",
                                    "page done ${url?.take(90)} cloud=[${names(cloud).joinToString(",")}] sso=[${names(sso).joinToString(",")}]",
                                )
                            }
                            val cookies = runCatching {
                                android.webkit.CookieManager.getInstance()
                                    .getCookie("https://imagingcloud.nikon.com.cn").orEmpty()
                            }.getOrDefault("")
                            // 登录已完成（云创域有 cookie）但前端迟迟不发带 token 的请求时，
                            // 主动打开「云创 → 相机」页：该页一定会用 Bearer 调 C001，token 与 guid 一起落到请求头里。
                            if (!completed && !oidcTried && cookies.isNotEmpty() &&
                                url?.contains("/recipe/index") == true
                            ) {
                                view?.post { startOidc() }
                            }
                            // 兼容 token 落在 localStorage / keycloak 实例上的情况
                            view?.evaluateJavascript(TOKEN_EXTRACT_JS) { value ->
                                val (token, guid) = CloudCapture.parseProbeResult(value)
                                if (token == null && guid == null) return@evaluateJavascript
                                view.post { acceptToken(token, guid) }
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
