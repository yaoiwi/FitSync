package com.fitsync.feature.login

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fitsync.data.db.AccountRegion
import com.fitsync.data.network.GarminEndpoints
import com.fitsync.ui.components.liquidGlass
import com.fitsync.ui.theme.FitSyncTheme
import com.fitsync.ui.theme.MiuixCanvas
import com.fitsync.ui.theme.MiuixPrimary
import com.fitsync.ui.theme.MiuiRed
import com.fitsync.ui.theme.MiuiText1
import com.fitsync.ui.theme.MiuiText2
import com.fitsync.ui.theme.MiuiText3
import com.fitsync.ui.theme.MiuixSummary
import kotlinx.coroutines.delay
import java.net.URLEncoder

/**
 * 用系统 WebView 完成 Garmin 登录。
 *
 * 为什么需要它：Garmin 的边缘防护会对非浏览器发起的登录 POST 直接返回 403
 * `Access is Denied.`。WebView 是真实浏览器引擎，JS 挑战、TLS 指纹、Cookie 都由它处理。
 *
 * **为什么用 `/sso/signin` 而不是 `/mobile/sso/...`**：
 * 后者是 React SPA（`/mobile/assets/index-*.js`），要等 JS 包与外部字体全部加载完才渲染，
 * 在 WebView 里极易长时间白屏转圈（真机已复现）。`/sso/signin` 是**服务端渲染**的 HTML，
 * 响应里直接带 `login-form` / `username` / `password` / `_csrf`，不需要等 JS。
 *
 * 登录成功后 ticket 出现在重定向 URL 上（`?ticket=ST-...`），
 * 而 `service` 用的是 `mobileService`，与后续 `preauthorized` 换 token 的 service 一致
 * （CAS 要求 service 匹配，否则 ticket 不被接受）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GarminWebLoginScreen(
    regionCode: String,
    onTicket: (String, String?) -> Unit,
    onCancel: () -> Unit,
) {
    val region = AccountRegion.from(regionCode)
    val url = remember(regionCode) { webLoginUrl(region) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // ticket 与签发它的 SSO 会话绑定，取 ticket 时必须把 Cookie 一起交给网络层
    fun deliverTicket(ticket: String) {
        val cookie = runCatching {
            android.webkit.CookieManager.getInstance().getCookie("https://${region.ssoHost}")
        }.getOrNull() ?: runCatching {
            android.webkit.CookieManager.getInstance().getCookie(url)
        }.getOrNull()
        onTicket(ticket, cookie)
    }

    // 15 秒还没加载完就给出可操作提示，避免一直干等
    LaunchedEffect(url) {
        delay(15_000)
        if (loading && loadError == null) {
            loadError = "15 秒内没有加载完成，可能是网络问题"
        }
    }

    val glassTint = if (isSystemInDarkTheme()) {
        Color(0xFF1C1C1E).copy(alpha = 0.80f)
    } else {
        Color.White.copy(alpha = 0.86f)
    }

    FitSyncTheme {
        Box(modifier = Modifier.fillMaxSize().background(MiuixCanvas)) {

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        // 用标准 Chrome 移动 UA：WebView 默认 UA 带 "; wv"，
                        // 容易被边缘防护判成非浏览器并直接拒绝
                        settings.userAgentString = BROWSER_UA

                        addJavascriptInterface(TicketBridge { t -> deliverTicket(t) }, "FGSBridge")
                        webChromeClient = WebChromeClient()

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: android.graphics.Bitmap?,
                            ) {
                                super.onPageStarted(view, url, favicon)
                                loading = true
                                extractTicket(url.orEmpty())?.let {
                                    loading = false
                                    view?.stopLoading()
                                    deliverTicket(it)
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                loading = false
                                if (loadError?.contains("15 秒") == true) loadError = null
                                view.evaluateJavascript(HOOK_JS, null)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: android.webkit.WebResourceError,
                            ) {
                                // 跳转到 service 域名失败很正常（该地址只用于带 ticket），
                                // 先看 URL 里有没有 ticket，有就照常收尾
                                extractTicket(request.url.toString())?.let {
                                    deliverTicket(it)
                                    return
                                }
                                if (request.isForMainFrame) {
                                    loading = false
                                    loadError = "${error.errorCode} ${error.description}"
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val target = request.url.toString()
                                extractTicket(target)?.let {
                                    deliverTicket(it)
                                    return true
                                }
                                return false
                            }
                        }
                        loadUrl(url)
                    }
                },
                update = { },
            )

            // 顶部玻璃条：浮在网页上，返回 + 状态 + 刷新
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .liquidGlass(
                        shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp),
                        tint = glassTint,
                        highlightAlpha = 0.35f,
                    )
                    .statusBarsPadding()
                    .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "←",
                    fontSize = 22.sp,
                    color = MiuiText1,
                    modifier = Modifier
                        .clickable { onCancel() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = "登录 Garmin",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuiText1,
                    )
                    Text(
                        text = when {
                            loadError != null -> "加载失败 · 可点右侧刷新"
                            loading -> "正在加载…"
                            else -> "官方页面 · 密码不经过本应用"
                        },
                        fontSize = 11.sp,
                        color = if (loadError != null) MiuiRed else MiuixSummary,
                        maxLines = 1,
                    )
                }
                Text(
                    text = "刷新",
                    fontSize = 14.sp,
                    color = MiuixPrimary,
                    modifier = Modifier
                        .clickable {
                            loadError = null
                            loading = true
                            webView?.reload()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            // 底部玻璃条
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .liquidGlass(
                        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                        tint = glassTint,
                        highlightAlpha = 0.35f,
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "登录成功后会自动返回，不需要手动关闭",
                    fontSize = 11.5.sp,
                    color = MiuixSummary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "取消",
                    fontSize = 15.sp,
                    color = MiuixPrimary,
                    modifier = Modifier
                        .clickable { onCancel() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private class TicketBridge(private val onTicket: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onTicket(ticket: String?) {
        if (ticket.isNullOrBlank()) return
        main.post { onTicket(ticket) }
    }
}

private fun extractTicket(text: String): String? {
    Regex("""[?&]ticket=([A-Za-z0-9\-_.]+)""").find(text)?.let { return it.groupValues[1] }
    return Regex("""(ST-[A-Za-z0-9\-_.]+)""").find(text)?.groupValues?.get(1)
}

/**
 * 登录页地址：服务端渲染版本，`service` 用移动端服务地址，
 * 保证换到的 ticket 之后能在 `preauthorized?service=` 里通过校验。
 */
private fun webLoginUrl(region: AccountRegion): String {
    val ep = runCatching { GarminEndpoints.forRegion(region) }.getOrNull()
        ?: return "https://sso.garmin.cn/sso/signin"
    // 关键：service 用 sso/embed，而不是 connect / mobile 的地址。
    // 登录成功后 CAS 会跳到 {ssoOrigin}/sso/embed?ticket=ST-...，
    // 这个 ticket 才能被 `preauthorized?login-url=.../sso/embed` 接受。
    // 用 mobileService 作为 service 时 ticket 与换 token 的 login-url 不匹配，
    // 服务端会回 401 "Expected parameters oauth token or SSO ticket were not provided"。
    val embed = URLEncoder.encode("${ep.ssoOrigin}/sso/embed", "UTF-8")
    return "${ep.ssoBase}/signin?id=gauth-widget&embedWidget=true" +
        "&gauthHost=$embed&service=$embed&source=$embed" +
        "&redirectAfterAccountLoginUrl=$embed" +
        "&redirectAfterAccountCreationUrl=$embed"
}

private const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

/**
 * 拦截 fetch / XHR，把登录响应里的 serviceTicketId 交给原生层。
 * 用 clone() 读响应，不影响页面本身的逻辑。
 */
private const val HOOK_JS = """
(function () {
  if (window.__fgsHooked) return;
  window.__fgsHooked = true;
  function scan(obj) {
    try {
      if (obj && obj.serviceTicketId) {
        FGSBridge.onTicket(String(obj.serviceTicketId));
      }
    } catch (e) {}
  }
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function () {
      return origFetch.apply(this, arguments).then(function (resp) {
        try {
          resp.clone().json().then(scan).catch(function () {});
        } catch (e) {}
        return resp;
      });
    };
  }
  var origOpen = XMLHttpRequest.prototype.open;
  var origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function () { return origOpen.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function () {
    var xhr = this;
    xhr.addEventListener('load', function () {
      try { scan(JSON.parse(xhr.responseText)); } catch (e) {}
    });
    return origSend.apply(this, arguments);
  };
})();
"""
