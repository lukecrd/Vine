package it.evoluzionetech.vine.net

import android.webkit.CookieManager
import it.evoluzionetech.vine.core.VineCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.HttpURLConnection
import java.net.URL

/**
 * GET with the login WebView's session. Redirects are never followed: any non-2xx suspends.
 * Never log cookies, headers or bodies.
 */
object Http {
    suspend fun get(userAgent: String, url: String): String = runInterruptible(Dispatchers.IO) {
        if (!VineCore.validURL(url)) error("Indirizzo non consentito.")
        val cookies = CookieManager.getInstance()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            c.setRequestProperty("Accept-Language", "it-IT,it;q=0.9")
            cookies.getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
            val code = c.responseCode
            val setCookies = c.headerFields.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }.values.flatten()
            if (setCookies.isNotEmpty()) {
                setCookies.forEach { cookies.setCookie(url, it) }
                cookies.flush()
            }
            if (code !in 200..299) error("Risposta HTTP $code: controlli sospesi.")
            // shortcut: assumes UTF-8 (amazon.it serves UTF-8), parse the Content-Type charset if that ever changes.
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}
