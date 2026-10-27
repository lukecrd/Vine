package it.evoluzionetech.vine.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReaderTest {
    private fun count(start: Int, end: Int, total: Int) = "Visualizzazione di $start–$end di $total risultati"

    /** Serves pages by number; records the requested URLs. */
    private class Site(val pages: (Int) -> String) {
        val urls = mutableListOf<String>()
        val fetch: suspend (String) -> String = { url ->
            urls += url
            pages(Regex("&page=(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: 1)
        }
    }

    private fun fails(site: Site, timeoutMs: Long = 40_000): String? =
        assertThrows(IllegalStateException::class.java) { runBlocking { Reader.scanAll(site.fetch, timeoutMs) } }.message

    @Test fun `paginazione completa con soli URL potluck`() {
        val site = Site { if (it == 1) page(listOf(A), count(1, 1, 2)) else page(listOf(B), count(2, 2, 2)) }
        assertEquals(listOf(A, B), runBlocking { Reader.scanAll(site.fetch) })
        assertEquals(listOf(VineCore.TARGET, VineCore.TARGET + "&page=2"), site.urls)
        site.urls.forEach { assertEquals(true, VineCore.validURL(it)) }
    }

    @Test fun `una pagina e coda vuota`() {
        assertEquals(listOf(A), runBlocking { Reader.scanAll(Site { page() }.fetch) })
        assertEquals(emptyList<Item>(), runBlocking { Reader.scanAll(Site { page(emptyList(), count(0, 0, 0)) }.fetch) })
    }

    @Test fun `catalogo cambiato tra pagine`() {
        val site = Site { if (it == 1) page(listOf(A), count(1, 1, 2)) else page(listOf(B), count(2, 2, 3)) }
        assertEquals("Catalogo cambiato durante la scansione: riprendere manualmente.", fails(site))
    }

    @Test fun `paginazione con buchi`() {
        val site = Site { if (it == 1) page(listOf(A), count(1, 1, 3)) else page(listOf(B), count(3, 3, 3)) }
        assertEquals("Paginazione incompleta o non riconosciuta.", fails(site))
    }

    @Test fun `duplicati tra pagine`() {
        val site = Site { if (it == 1) page(listOf(A), count(1, 1, 2)) else page(listOf(A), count(2, 2, 2)) }
        assertEquals("Duplicati tra pagine: scansione non affidabile.", fails(site))
    }

    @Test fun `limite difensivo di 100 pagine`() {
        val site = Site { n -> page(listOf(item(n)), count(n, n, 1000)) }
        assertEquals("Limite difensivo di 100 pagine raggiunto.", fails(site))
        assertEquals(100, site.urls.size)
    }

    @Test fun `errore HTTP e CAPTCHA a meta scansione`() {
        assertEquals("Risposta HTTP 503: controlli sospesi.", fails(Site { error("Risposta HTTP 503: controlli sospesi.") }))
        val site = Site { if (it == 1) page(listOf(A), count(1, 1, 2)) else page(blocked = true) }
        assertEquals("Accesso o CAPTCHA richiesto. Intervento manuale necessario.", fails(site))
    }

    @Test fun `rete lenta`() {
        val site = Site { "" }
        val slow: suspend (String) -> String = { delay(5_000); site.fetch(it) }
        assertEquals(
            "Rete lenta: scansione interrotta dopo 40 secondi.",
            assertThrows(IllegalStateException::class.java) { runBlocking { Reader.scanAll(slow, timeoutMs = 50) } }.message,
        )
    }
}
