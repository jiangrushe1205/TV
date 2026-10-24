package cn.v65.live.playback

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView

object OfficialSite {
    fun permitsNavigation(url: String): Boolean = runCatching {
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        uri.scheme == "https" && (host == "yangshipin.cn" || host.endsWith(".yangshipin.cn"))
    }.getOrDefault(false)

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, probe: Boolean) {
        if (webView.context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Reuse fresh HTTP resources and revalidate expired responses with the server.
            // Do not force cached live manifests, tickets or login responses past their expiry.
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = probe
            useWideViewPort = true
            loadWithOverviewMode = true
            // Keep the installed engine version truthful while asking for the desktop layout.
            userAgentString = userAgentString.replace("; wv", "").replace(" Mobile", "").replace(Regex("Android [^;)]+;?"), "X11; Linux x86_64;")
        }
        webView.setBackgroundColor(android.graphics.Color.BLACK)
    }
}
