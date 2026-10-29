package it.evoluzionetech.vine.core

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException

/** GET only, ported from reader.js. [fetch] returns the body of a 2xx response or throws. */
object Reader {
    suspend fun scanAll(fetch: suspend (String) -> String, timeoutMs: Long = 40_000): List<Item> = try {
        withTimeout(timeoutMs) { pages(fetch) }
    } catch (e: TimeoutCancellationException) { // IOException: a network problem, retried by Monitor.scan
        throw IOException("Rete lenta: scansione interrotta dopo 40 secondi.")
    } catch (e: SocketTimeoutException) { // blocking socket timeout can surface instead of the coroutine one
        throw IOException("Rete lenta: scansione interrotta dopo 40 secondi.")
    }

    private suspend fun pages(fetch: suspend (String) -> String): List<Item> {
        val all = mutableListOf<Item>()
        var total: Long? = null
        var expected = 1L
        for (page in 1..100) {
            val url = VineCore.TARGET + if (page > 1) "&page=$page" else ""
            val snapshot = VineCore.read(fetch(url), url)
            if (total != null && snapshot.total != total) error("Catalogo cambiato durante la scansione: riprendere manualmente.")
            total = snapshot.total
            if (total != 0L && snapshot.start != expected) error("Paginazione incompleta o non riconosciuta.")
            all += snapshot.items
            expected = snapshot.end + 1
            if (all.size.toLong() == total) {
                if (all.map { it.id }.toSet().size.toLong() != total) error("Duplicati tra pagine: scansione non affidabile.")
                return all
            }
        }
        error("Limite difensivo di 100 pagine raggiunto.")
    }
}
