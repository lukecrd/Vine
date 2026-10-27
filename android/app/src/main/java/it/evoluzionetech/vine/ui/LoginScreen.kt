package it.evoluzionetech.vine.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import it.evoluzionetech.vine.core.VineCore

private fun amazonIt(uri: Uri) =
    uri.scheme == "https" && uri.host.let { it == "amazon.it" || it?.endsWith(".amazon.it") == true }

/**
 * Amazon sign-in in a locked-down WebView. The app never reads credentials: once the potluck URL loads,
 * cookies are flushed and the screen closes. Polling never uses the WebView.
 */
@SuppressLint("SetJavaScriptEnabled") // Amazon sign-in needs JS; no JS interface is exposed
@Composable
fun LoginScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onDone)
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.safeBrowsingEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame || amazonIt(request.url)) return false
                        if (request.url.scheme == "https" || request.url.scheme == "http")
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        // Close only on the real grid: a CAPTCHA/login served at the potluck URL stays open to be solved by hand.
                        if (VineCore.validURL(url)) view.evaluateJavascript("!!document.querySelector('#vvp-items-grid-container')") {
                            if (it == "true") {
                                CookieManager.getInstance().flush()
                                onDone()
                            }
                        }
                    }

                    @SuppressLint("WebViewClientOnReceivedSslError") // always cancels, never proceeds
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = handler.cancel()
                }
                loadUrl(VineCore.TARGET)
            }
        },
        onRelease = { it.destroy() },
    )
}
