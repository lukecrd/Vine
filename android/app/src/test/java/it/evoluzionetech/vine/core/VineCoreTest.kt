package it.evoluzionetech.vine.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VineCoreTest {
    private val T = VineCore.TARGET

    @Test fun `URL solo potluck senza filtri, ASIN valido`() {
        assertTrue(VineCore.validURL(T))
        assertTrue(VineCore.validURL("$T&page=2"))
        assertTrue(VineCore.validURL("https://www.amazon.it:443/vine/vine-items?page=3&queue=potluck"))
        for (url in listOf(
            "$T&cn=123", T.replace("potluck", "encore"), "https://evil.test/vine/vine-items?queue=potluck",
            "http://www.amazon.it/vine/vine-items?queue=potluck", "https://www.amazon.it/vine/vine-items",
            "https://www.amazon.it/ap/signin", "https://www.amazon.it.evil.test/vine/vine-items?queue=potluck", "", null,
        )) assertFalse(url.toString(), VineCore.validURL(url))
        assertNull(VineCore.asin("no"))
        assertNull(VineCore.asin("b013leietu"))
        assertEquals("B013LEIETU", VineCore.asin("B013LEIETU"))
    }

    @Test fun `parser struttura osservata, titolo e link canonico`() {
        assertEquals(listOf(A), VineCore.read(page(), T).items)
        val two = VineCore.read(page(listOf(A, B), "Visualizzazione di 1 – 2 di 2 risultati"), T)
        assertEquals(listOf(A, B), two.items)
        assertEquals(2L, two.total)
    }

    @Test fun `parser login, CAPTCHA, coda errata, conteggio incompleto non validi`() {
        val cases = mapOf(
            page(blocked = true) to "Accesso o CAPTCHA richiesto. Intervento manuale necessario.",
            page().replace("<p>", "<form action=\"/errors/validateCaptcha\"></form><p>") to "Accesso o CAPTCHA richiesto. Intervento manuale necessario.",
            page(selected = false) to "Pagina non riconosciuta: sessione, blocco o struttura cambiata.",
            page(count = "Errore") to "Conteggio risultati non verificabile (anche pagina vuota): monitor sospeso. Testo letto: «Errore»",
            page(count = "Visualizzazione di 1–2 di 2 risultati") to "Conteggio prodotti incoerente.",
            page(listOf(A, A), "Visualizzazione di 1–2 di 2 risultati") to "Conteggio prodotti incoerente.",
            page(count = "Visualizzazione di 3–3 di 2 risultati") to "Intervallo risultati incoerente.",
            page(badLink = true) to "Prodotto incompleto o identificatore incoerente.",
        )
        for ((html, message) in cases) assertEquals(message, assertThrows(IllegalStateException::class.java) { VineCore.read(html, T) }.message)
        assertEquals(
            "Sessione scaduta o pagina diversa da potluck.",
            assertThrows(IllegalStateException::class.java) { VineCore.read(page(), "https://www.amazon.it/ap/signin") }.message,
        )
    }

    @Test fun `pagina vuota con conteggio zero esplicito supportata`() {
        assertEquals(0L, VineCore.read(page(emptyList(), "Visualizzazione di 0–0 di 0 risultati"), T).total)
    }

    @Test fun `numeri con separatore delle migliaia`() {
        val s = VineCore.read(page(listOf(A), "Visualizzazione di 1.201–1.201 di 1.500 risultati"), T)
        assertEquals(1201L, s.start)
        assertEquals(1500L, s.total)
    }

    @Test fun `baseline silenziosa e un avviso per nuovo ASIN`() {
        var (s, fresh) = VineCore.reconcile(State(), listOf(A), 1)
        assertEquals(0, fresh.size)
        assertTrue(s.baseline)
        VineCore.reconcile(s, listOf(A, B, B), 2).let { s = it.first; fresh = it.second }
        assertEquals(listOf(B.id), fresh.map { it.id })
        assertEquals(1, s.events.size)
    }

    @Test fun `riavvio, sparizione e ricomparsa non duplicano`() {
        var s = VineCore.reconcile(State(), listOf(A), 1).first
        s = VineCore.reconcile(s, listOf(B), 2).first
        s = Json.decodeFromString(State.serializer(), Json.encodeToString(State.serializer(), s))
        s = VineCore.reconcile(s, emptyList(), 3).first
        val (last, fresh) = VineCore.reconcile(s, listOf(A, B), 4)
        assertEquals(0, fresh.size)
        assertEquals(1, last.events.size)
        assertEquals(2, last.seen.size)
    }
}
