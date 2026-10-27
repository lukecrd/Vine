package it.evoluzionetech.vine.core

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLDecoder

@Serializable
data class Item(val id: String, val title: String, val url: String)

@Serializable
data class Event(val id: String, val title: String, val url: String, val at: Long)

@Serializable
data class State(
    val running: Boolean = false,
    val baseline: Boolean = false,
    val seen: Map<String, Long> = emptyMap(),
    val events: List<Event> = emptyList(),
    val status: String = "Pronto. Avvia per creare la baseline silenziosa.",
    val last: Long? = null,
    val count: Int = 0,
    val accepted: Boolean = false, // first-run ToS disclaimer
    val minMinutes: Int = 1, // random check interval, see Monitor.delayMs
    val maxMinutes: Int = 3,
    val next: Long? = null, // planned time of the next check
)

data class Snapshot(val items: List<Item>, val start: Long, val end: Long, val total: Long)

/** Pure logic ported from core.js. No network or page actions. */
object VineCore {
    const val TARGET = "https://www.amazon.it/vine/vine-items?queue=potluck"

    // JS \s (includes NBSP and other Unicode spaces); Java \s is ASCII-only.
    private const val S = "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
    private val COUNT = Regex("Visualizzazione$S+di$S+([\\d.]+)$S*[–-]$S*([\\d.]+)$S+di$S+([\\d.]+)$S+risultati", RegexOption.IGNORE_CASE)
    private val DP = Regex("^/dp/([A-Z0-9]{10})(?:/|$)")
    private val ASIN = Regex("^[A-Z0-9]{10}$")
    private const val MAX_SAFE = 9007199254740991L

    private fun amazonIt(u: URL) =
        u.protocol == "https" && u.host.equals("www.amazon.it", ignoreCase = true) && (u.port == -1 || u.port == 443)

    fun validURL(value: String?): Boolean = try {
        val u = URL(value)
        val params = (u.query ?: "").split('&').filter { it.isNotEmpty() }.map {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        amazonIt(u) && u.path == "/vine/vine-items" &&
            params.firstOrNull { it.first == "queue" }?.second == "potluck" &&
            params.all { it.first == "queue" || it.first == "page" }
    } catch (e: Exception) {
        false
    }

    fun asin(value: String?): String? = value?.takeIf { ASIN.matches(it) }

    // Jsoup select() includes the element itself; querySelector searches descendants only.
    private fun Element.find(css: String): Element? = select(css).firstOrNull { it !== this }
    private fun Element.findAll(css: String): List<Element> = select(css).filter { it !== this }

    fun read(html: String, url: String): Snapshot {
        if (!validURL(url)) error("Sessione scaduta o pagina diversa da potluck.")
        val doc = Jsoup.parse(html, url)
        if (doc.selectFirst("input[type=\"password\"], #captchacharacters, form[action*=\"validateCaptcha\"], iframe[src*=\"captcha\"]") != null)
            error("Accesso o CAPTCHA richiesto. Intervento manuale necessario.")
        val selected = doc.selectFirst("#vvp-items-button--recommended [aria-checked=\"true\"]")
        val box = doc.selectFirst("#vvp-items-grid-container")
        if (selected == null || box == null) error("Pagina non riconosciuta: sessione, blocco o struttura cambiata.")
        val summary = box.findAll("p").joinToString(" ") { it.wholeText() }
        // Empty-state wording is intentionally NOT guessed. Unknown empty layouts pause safely.
        // Shows the unmatched text so a changed Amazon wording can be fixed without guessing.
        val count = COUNT.find(summary) ?: error("Conteggio risultati non verificabile (anche pagina vuota): monitor sospeso. Testo letto: «${summary.replace(Regex("$S+"), " ").trim().take(200)}»")
        val (start, end, total) = count.groupValues.drop(1).map { it.replace(".", "").toLongOrNull() ?: -1L }
        val items = box.findAll(".vvp-item-tile").map { tile ->
            val id = asin(tile.find("[data-asin]")?.attr("data-asin"))
            val link = tile.find(".vvp-item-product-title-container a[href]")
            val href = try { URL(URL(TARGET), link?.attr("href") ?: "") } catch (e: Exception) { null }
            val linkID = href?.path?.let { DP.find(it)?.groupValues?.get(1) }
            val full = link?.find(".a-truncate-full")?.wholeText()
            val title = (if (!full.isNullOrEmpty()) full else link?.wholeText() ?: "").trim()
            if (id == null || id != linkID || !amazonIt(href!!) || title.isEmpty())
                error("Prodotto incompleto o identificatore incoerente.")
            Item(id, title, "https://www.amazon.it/dp/$id")
        }
        if (total !in 0..MAX_SAFE || (if (total == 0L) items.isNotEmpty() else end - start + 1 != items.size.toLong()) ||
            items.map { it.id }.toSet().size != items.size
        ) error("Conteggio prodotti incoerente.")
        if (total != 0L && (start < 1 || end < start || end > total)) error("Intervallo risultati incoerente.")
        return Snapshot(items, start, end, total)
    }

    /** Returns the new state and the items to notify (none during the silent baseline). */
    fun reconcile(state: State, items: List<Item>, now: Long): Pair<State, List<Event>> {
        val seen = state.seen.toMutableMap()
        val fresh = mutableListOf<Event>()
        for (item in items) if (item.id !in seen) {
            seen[item.id] = now
            if (state.baseline) fresh += Event(item.id, item.title, item.url, now)
        }
        return state.copy(baseline = true, seen = seen, last = now, count = items.size, events = state.events + fresh) to fresh
    }
}
