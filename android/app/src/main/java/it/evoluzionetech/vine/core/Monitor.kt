package it.evoluzionetech.vine.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.random.Random

/**
 * scan/stop from background.js. Every state change goes through [mutex] (the JS `serial` chain).
 * [notify] returns false when system alerts are unavailable.
 */
class Monitor(
    private val load: suspend () -> State,
    private val save: suspend (State) -> Unit,
    private val fetch: suspend (String) -> String,
    private val notify: (Event) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val mutex = Mutex()

    companion object {
        val MINUTES = 1..3
        private const val MINUTE = 60_000L

        /** Uniform delay in [min, max] minutes, re-drawn before every check. Stored values are clamped, never trusted. */
        fun delayMs(min: Int, max: Int, random: Random = Random.Default): Long {
            val lo = min.coerceIn(MINUTES)
            val hi = max.coerceIn(lo, MINUTES.last)
            return random.nextLong(lo * MINUTE, hi * MINUTE + 1)
        }
    }

    suspend fun state(): State = mutex.withLock { load() }

    suspend fun update(change: (State) -> State) = mutex.withLock { save(change(load())) }

    suspend fun start() = update { it.copy(running = true, status = "Lettura in corso…") }

    suspend fun stop(reason: String) = update { it.copy(running = false, status = reason) }

    suspend fun setInterval(min: Int, max: Int) {
        require(min in MINUTES && max in MINUTES && min <= max) { "Intervallo non valido: $min–$max minuti." }
        update { it.copy(minMinutes = min, maxMinutes = max) }
    }

    suspend fun logout() = update {
        it.copy(running = false, baseline = false, seen = emptyMap(), status = "Disconnesso: già visti cancellati. Accedi e premi Avvia.")
    }

    suspend fun clear() = update {
        it.copy(running = false, baseline = false, seen = emptyMap(), events = emptyList(), status = "Dati cancellati. Premi Avvia per una nuova baseline silenziosa.")
    }

    /** Returns the wait before the next check, or null when stopped or suspended (no automatic retry, except network errors). */
    suspend fun scan(): Long? = mutex.withLock {
        var s = load()
        if (!s.running) return@withLock null
        try {
            val t = now()
            val wait = delayMs(s.minMinutes, s.maxMinutes, random)
            val items = try {
                Reader.scanAll(fetch)
            } catch (e: IOException) { // no network in standby (Wi-Fi asleep, DNS, timeout): stay active, retry next check
                save(s.copy(next = t + wait, status = "Attivo — rete non disponibile (${e.message ?: e.javaClass.simpleName}), nuovo tentativo al prossimo controllo."))
                return@withLock wait
            }
            val wasBaseline = s.baseline
            val (next, fresh) = VineCore.reconcile(s, items, t)
            s = next.copy(next = t + wait, status = if (wasBaseline) "Attivo — ultima scansione completata." else "Attivo — baseline silenziosa salvata.")
            // Persist events AND seen atomically before system alerts. Alerts are never replayed on restart.
            save(s)
            for (item in fresh) if (!notify(item)) {
                s = s.copy(status = "Attivo — avvisi di sistema non disponibili; nuovi prodotti nel registro.")
                save(s)
            }
            wait
        } catch (e: CancellationException) {
            throw e // service stopped: not an anomaly
        } catch (e: Exception) {
            save(load().copy(running = false, status = "Sospeso: " + (e.message ?: e.javaClass.simpleName) + " Apri Vine e premi Avvia per riprendere."))
            null
        }
    }
}
