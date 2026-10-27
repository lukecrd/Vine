@file:OptIn(ExperimentalMaterial3Api::class)

package it.evoluzionetech.vine.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.evoluzionetech.vine.MonitorService
import it.evoluzionetech.vine.core.Monitor
import it.evoluzionetech.vine.core.State
import it.evoluzionetech.vine.core.VineCore
import it.evoluzionetech.vine.data.store
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.ITALY)

private enum class Screen { Main, Settings, Login }

/** Which destructive action is waiting for confirmation. */
private enum class Confirm { Logout, Clear }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { VineTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }

    private fun open(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    private fun batteryExempt() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife") // sideloaded personal app: the exemption is what keeps the cadence with the screen off
    private fun askBatteryExemption() {
        if (!batteryExempt())
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
    }

    private fun openNotificationSettings() = runCatching {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    @Composable
    private fun App() {
        val monitor = remember { MonitorService.monitor(this) }
        val state by store.data.collectAsState(initial = null)
        val scope = rememberCoroutineScope()
        var screen by remember { mutableStateOf(Screen.Main) }
        var confirm by remember { mutableStateOf<Confirm?>(null) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val service = Intent(this, MonitorService::class.java)

        // No BOOT_COMPLETED and no sticky restart: a "running" state without a live service is stale.
        LaunchedEffect(Unit) {
            monitor.update { if (it.running && !MonitorService.alive) it.copy(running = false, status = "Fermo dopo riavvio o chiusura dell'app. Premi Avvia.") else it }
        }
        // Drives "prossimo controllo tra ~X" and refreshes system settings shown on screen.
        LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }

        val start = {
            scope.launch {
                monitor.start()
                startForegroundService(service)
                askBatteryExemption()
            }
            Unit
        }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
        val stopService = { stopService(service); Unit }

        val s = state ?: return
        if (!s.accepted) return Disclaimer(onAccept = { scope.launch { monitor.update { it.copy(accepted = true) } } }, onExit = { finish() })

        when (screen) {
            Screen.Login -> Page("Apri Vine / accedi", onBack = { screen = Screen.Main }) { padding ->
                LoginScreen(onDone = { screen = Screen.Main }, modifier = Modifier.padding(padding))
            }
            Screen.Settings -> {
                BackHandler { screen = Screen.Main }
                Page("Impostazioni", onBack = { screen = Screen.Main }) { padding ->
                    SettingsScreen(
                        s, now, padding,
                        onInterval = { min, max -> scope.launch { monitor.setInterval(min, max) } },
                        onLogin = { screen = Screen.Login },
                        onConfirm = { confirm = it },
                    )
                }
            }
            Screen.Main -> Page(
                "Vine · nuovi prodotti",
                actions = {
                    IconButton(onClick = { screen = Screen.Settings }) { Icon(Icons.Filled.Settings, contentDescription = "Impostazioni") }
                },
            ) { padding ->
                MainScreen(
                    s, now, padding,
                    onStart = {
                        MonitorService.channels(this)
                        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
                    },
                    onStop = {
                        stopService()
                        scope.launch { monitor.stop("Fermo — memoria dei prodotti conservata.") }
                    },
                    onLogin = { screen = Screen.Login },
                    onOpen = { open(it) },
                )
            }
        }

        confirm?.let { c ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text(if (c == Confirm.Logout) "Uscire da Amazon?" else "Cancellare i dati?") },
                text = {
                    Text(
                        if (c == Confirm.Logout) "Il monitor si ferma, la sessione Amazon viene chiusa e i prodotti già visti vengono dimenticati. Il registro resta."
                        else "Il monitor si ferma e vengono cancellati registro e prodotti già visti. Il prossimo Avvia crea una nuova baseline silenziosa. Non si può annullare."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = null
                        stopService()
                        if (c == Confirm.Logout) {
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            scope.launch { monitor.logout() }
                        } else scope.launch { monitor.clear() }
                    }) { Text(if (c == Confirm.Logout) "Esci" else "Cancella", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annulla") } },
            )
        }
    }

    @Composable
    private fun Page(
        title: String,
        onBack: (() -> Unit)? = null,
        actions: @Composable () -> Unit = {},
        content: @Composable (PaddingValues) -> Unit,
    ) = Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro") }
                },
                actions = { actions() },
            )
        },
        content = content,
    )

    @Composable
    private fun Disclaimer(onAccept: () -> Unit, onExit: () -> Unit) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Prima di iniziare", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                "Le condizioni d'uso di Amazon vietano robot ed estrazione automatica di dati. Questa app legge la coda " +
                    "Vine «Consigliati per te» con il tuo account a intervalli casuali tra 1 e 3 minuti (centinaia di richieste al giorno): " +
                    "Amazon può bloccare l'account o escluderti dal programma Vine."
            )
            Text("L'app fa solo letture: nessun click, richiesta di prodotto, carrello o ordine. Le credenziali restano nella pagina di accesso Amazon e l'app non le legge.")
            Text("Usandola accetti questo rischio.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text("Accetto il rischio") }
                OutlinedButton(onClick = onExit) { Text("Esci") }
            }
        }
    }

    @Composable
    private fun MainScreen(
        s: State,
        now: Long,
        padding: PaddingValues,
        onStart: () -> Unit,
        onStop: () -> Unit,
        onLogin: () -> Unit,
        onOpen: (String) -> Unit,
    ) {
        val suspended = !s.running && s.status.startsWith("Sospeso")
        val colors = MaterialTheme.colorScheme
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when { s.running -> colors.primaryContainer; suspended -> colors.errorContainer; else -> colors.surfaceVariant },
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            when { s.running -> "Attivo"; suspended -> "Sospeso"; else -> "Fermo" },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(s.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        Text("Ultimo controllo: ${s.last?.let { dateFormat.format(Date(it)) } ?: "mai"}", style = MaterialTheme.typography.bodyMedium)
                        Text("Prossimo controllo: ${nextCheck(s, now)}", style = MaterialTheme.typography.bodyMedium)
                        Text("Già visti: ${s.seen.size} · Intervallo: ${range(s)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                if (s.running) OutlinedButton(onClick = onStop, Modifier.fillMaxWidth()) { Text("Ferma") }
                else Button(onClick = onStart, Modifier.fillMaxWidth()) { Text("Avvia / Riprendi") }
                if (!s.running) OutlinedButton(onClick = onLogin, Modifier.fillMaxWidth()) { Text("Apri Vine / accedi") }
            }
            item {
                Text(
                    "Nuovi prodotti rilevati (${s.events.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                )
            }
            if (s.events.isEmpty()) item { Text("Nessun nuovo prodotto rilevato.", color = colors.onSurfaceVariant) }
            items(s.events.asReversed(), key = { "${it.id}-${it.at}" }) { e ->
                val asin = VineCore.asin(e.id)
                ListItem(
                    headlineContent = { Text(e.title, maxLines = 3, overflow = TextOverflow.Ellipsis, color = colors.primary) },
                    supportingContent = { Text("${dateFormat.format(Date(e.at))} · $asin") },
                    modifier = Modifier.clickableIf(asin != null, "Apri il prodotto su Amazon") { onOpen("https://www.amazon.it/dp/$asin") },
                )
                HorizontalDivider()
            }
            item {
                Text(
                    "La prima scansione è silenziosa. Nessuna richiesta o ordine automatico. Con lo schermo spento " +
                        "l'intervallo può allungarsi: concedi l'esenzione dall'ottimizzazione batteria (Impostazioni).",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun SettingsScreen(
        s: State,
        @Suppress("UNUSED_PARAMETER") now: Long, // recomposes every second so system states below stay current
        padding: PaddingValues,
        onInterval: (Int, Int) -> Unit,
        onLogin: () -> Unit,
        onConfirm: (Confirm) -> Unit,
    ) {
        var range by remember(s.minMinutes, s.maxMinutes) { mutableStateOf(s.minMinutes.toFloat()..s.maxMinutes.toFloat()) }
        val notifications = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Intervallo dei controlli")
            Text("Attesa casuale tra ${range.start.roundToInt()} e ${range.endInclusive.roundToInt()} minuti, estratta di nuovo prima di ogni controllo.")
            RangeSlider(
                value = range,
                onValueChange = { range = it },
                valueRange = Monitor.MINUTES.first.toFloat()..Monitor.MINUTES.last.toFloat(),
                steps = Monitor.MINUTES.last - Monitor.MINUTES.first - 1,
                onValueChangeFinished = { onInterval(range.start.roundToInt(), range.endInclusive.roundToInt()) },
            )

            HorizontalDivider()
            Section("Notifiche e batteria")
            Text(if (notifications) "Notifiche di sistema: consentite." else "Notifiche di sistema: disattivate. I nuovi prodotti restano nel registro.")
            OutlinedButton(onClick = { openNotificationSettings() }, Modifier.fillMaxWidth()) { Text("Impostazioni notifiche") }
            Text(if (batteryExempt()) "Ottimizzazione batteria: esclusa." else "Ottimizzazione batteria: attiva, i controlli a schermo spento possono ritardare.")
            if (!batteryExempt()) OutlinedButton(onClick = { askBatteryExemption() }, Modifier.fillMaxWidth()) { Text("Escludi dall'ottimizzazione batteria") }

            HorizontalDivider()
            Section("Account Amazon")
            Text("Accedi o risolvi un CAPTCHA nella pagina Vine. L'app non legge le credenziali.")
            OutlinedButton(onClick = onLogin, Modifier.fillMaxWidth(), enabled = !s.running) { Text("Apri Vine / accedi") }
            if (s.running) Text("Ferma il monitor per aprire la pagina.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onConfirm(Confirm.Logout) }, Modifier.fillMaxWidth()) { Text("Esci da Amazon e dimentica i già visti") }

            HorizontalDivider()
            Section("Dati")
            Text("Già visti: ${s.seen.size} · Eventi nel registro: ${s.events.size}")
            Button(
                onClick = { onConfirm(Confirm.Clear) },
                Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Cancella registro e già visti") }
        }
    }

    @Composable
    private fun Section(title: String) =
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
}

private fun range(s: State) = if (s.minMinutes == s.maxMinutes) "${s.minMinutes} min" else "${s.minMinutes}–${s.maxMinutes} min"

private fun nextCheck(s: State, now: Long): String {
    val next = s.next
    if (!s.running || next == null) return "—"
    val sec = (next - now) / 1000
    return when {
        sec <= 0 -> "in corso…"
        sec < 60 -> "tra ~$sec s"
        else -> "tra ~${(sec + 30) / 60} min"
    }
}

private fun Modifier.clickableIf(enabled: Boolean, label: String, onClick: () -> Unit) =
    if (enabled) clickable(onClickLabel = label, onClick = onClick) else this

@Composable
private fun VineTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
