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
import android.widget.Toast

/** 在当前页面 origin 内扫描 localStorage/sessionStorage/cookie，提取 access_token（JWT 以 eyJ 开头，不含 |）。 */
private val TOKEN_EXTRACT_JS = """
(function(){
  function pick(str){
    if(!str || str.length < 20) return null;
    var m = str.match(/"access_token"\s*:\s*"([^"]{40,})"/);
    if(m) return {t: m[1], g: (str.match(/"guid"\s*:\s*"([^"]+)"/)||[])[1] || ''};
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
  var r = scan(localStorage) || scan(sessionStorage);
  if(!r){
    try{
      var m = document.cookie.match(/access_token=([^;]+)/);
      if(m){ var t = decodeURIComponent(m[1]); if(t.length > 40) r = {t: t, g: (document.cookie.match(/guid=([^;]+)/)||[])[1] || ''}; }
    }catch(e){}
  }
  return r ? (r.t + '|' + r.g) : '';
})()
""".trimIndent()

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

    /** 从 WebView 当前页提取 token；返回 null 表示未找到。 */
    fun extractFromPage() {
        val view = webView ?: return
        view.evaluateJavascript(TOKEN_EXTRACT_JS) { value ->
            val raw = value?.trim('"', ' ', '|') ?: return@evaluateJavascript
            if (completed || raw.isEmpty()) return@evaluateJavascript
            val tokenPart = raw.substringBefore('|')
            val guidPart = raw.substringAfter('|', "")
            if (tokenPart.length > 40) {
                capturedToken = tokenPart
                capturedGuid = guidPart
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
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
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
        if (capturedToken.isBlank()) {
            TextButton(
                onClick = { extractFromPage() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text("登录完成后点这里提取 token") }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webView = this
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // 登录跳转完成后自动提取一次；未命中时用户可点上方按钮手动提取
                                view?.evaluateJavascript(TOKEN_EXTRACT_JS) { value ->
                                    val raw = value?.trim('"', ' ', '|') ?: return@evaluateJavascript
                                    if (completed || raw.isEmpty()) return@evaluateJavascript
                                    val tokenPart = raw.substringBefore('|')
                                    if (tokenPart.length > 40) {
                                        capturedToken = tokenPart
                                        capturedGuid = raw.substringAfter('|', "")
                                    }
                                }
                            }
                        }
                        loadUrl("https://imagingcloud.nikon.com.cn/recipe/index/")
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        Text(
            "登录完成后：页面会自动提取 token；若未出现，点“提取 token”按钮或用“手动输入”粘贴。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

