package it.evoluzionetech.vine.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simulated integration, as in test.cjs: persistence, single alert, error suspends, stop. */
class MonitorTest {
    private var saved: String? = null // JSON, like the DataStore file
    private var html = page()
    private var failure: String? = null
    private val notified = mutableListOf<Event>()
    private var alerts = true
    private var clock = 0L

    private fun load() = saved?.let { Json.decodeFromString(State.serializer(), it) } ?: State()

    private val monitor = Monitor(
        load = { load() },
        save = { saved = Json.encodeToString(State.serializer(), it) },
        fetch = { failure?.let { error(it) } ?: html },
        notify = { e ->
            // Saved before alerting: the event is already in the persisted log.
            assertTrue(load().events.any { it.id == e.id && it.at == e.at })
            if (alerts) notified += e
            alerts
        },
        now = { ++clock },
    )

    @Test fun `baseline silenziosa, un avviso per ASIN nuovo, errore sospende, stop`() = runBlocking {
        monitor.scan() // not running: no read
        assertEquals(null, saved)

        monitor.start()
        monitor.scan()
        var s = load()
        assertTrue(s.baseline)
        assertEquals("Attivo — baseline silenziosa salvata.", s.status)
        assertEquals(0, notified.size)

        html = page(listOf(A, B))
        monitor.scan()
        assertEquals(listOf(B.id), notified.map { it.id })
        assertEquals(1, load().events.size)
        assertEquals("Attivo — ultima scansione completata.", load().status)

        monitor.scan() // same items: no duplicate alert
        assertEquals(1, notified.size)

        failure = "Risposta HTTP 503: controlli sospesi."
        monitor.scan()
        s = load()
        assertFalse(s.running)
        assertEquals(2, s.seen.size)
        assertEquals("Sospeso: Risposta HTTP 503: controlli sospesi. Apri Vine e premi Avvia per riprendere.", s.status)

        failure = null
        monitor.scan() // suspended: no automatic retry
        assertEquals(s, load())

        monitor.stop("Fermo — memoria dei prodotti conservata.")
        assertFalse(load().running)
        assertEquals(2, load().seen.size)
    }

    @Test fun `sospensione non aggiorna i gia visti`() = runBlocking {
        monitor.start()
        monitor.scan()
        val before = load()
        html = page(listOf(A, B), "Visualizzazione di 1–2 di 3 risultati") // page 2 then fails the range check
        monitor.scan()
        val after = load()
        assertFalse(after.running)
        assertTrue(after.status.startsWith("Sospeso: "))
        assertEquals(before.seen, after.seen)
        assertEquals(before.events, after.events)
        assertEquals(0, notified.size)
    }

    @Test fun `avvisi non disponibili restano nel registro`() = runBlocking {
        monitor.start()
        monitor.scan()
        alerts = false
        html = page(listOf(A, B))
        monitor.scan()
        assertEquals("Attivo — avvisi di sistema non disponibili; nuovi prodotti nel registro.", load().status)
        assertEquals(listOf(B.id), load().events.map { it.id })
        assertTrue(load().running)
    }

    @Test fun `intervallo casuale sempre in min-max, impostazioni non valide rifiutate o limitate`() = runBlocking {
        val r = kotlin.random.Random(42)
        for ((min, max) in listOf(1 to 3, 1 to 1, 2 to 3, 3 to 3)) repeat(1_000) {
            assertTrue(Monitor.delayMs(min, max, r) in min * 60_000L..max * 60_000L)
        }
        // Stored garbage is clamped into 1..3 minutes, never 0 or unbounded.
        repeat(1_000) { assertTrue(Monitor.delayMs(0, 99, r) in 60_000L..180_000L) }
        repeat(1_000) { assertTrue(Monitor.delayMs(3, 1, r) == 180_000L) }
        // The settings entry point rejects them.
        for ((min, max) in listOf(0 to 3, 1 to 4, 3 to 2)) assertThrows(IllegalArgumentException::class.java) { runBlocking { monitor.setInterval(min, max) } }
        monitor.setInterval(2, 3)
        assertEquals(2 to 3, load().minMinutes to load().maxMinutes)
        // Re-drawn at every check and persisted as the planned next check.
        monitor.start()
        val wait = monitor.scan()!!
        assertTrue(wait in 120_000L..180_000L)
        assertEquals(clock + wait, load().next)
        assertEquals(null, monitor.stop("Fermo").let { monitor.scan() })
    }

    @Test fun `memoria piena ferma i controlli senza perdere i gia visti`() = runBlocking {
        monitor.start()
        monitor.scan()
        val before = saved
        val full = Monitor(
            load = { load() },
            save = { throw java.io.IOException("No space left on device") },
            fetch = { page(listOf(A, B)) },
            notify = { notified += it; true },
        )
        assertThrows(java.io.IOException::class.java) { runBlocking { full.scan() } } // propagates: the service loop stops
        assertEquals(before, saved)
        assertEquals(0, notified.size)
    }

    @Test fun `logout cancella i gia visti e la prossima scansione torna baseline`() = runBlocking {
        monitor.start()
        monitor.scan()
        monitor.logout()
        assertTrue(load().seen.isEmpty())
        assertFalse(load().running)
        monitor.start()
        html = page(listOf(A, B))
        monitor.scan()
        assertEquals(0, notified.size)
    }
}
