# Vine — nuovi prodotti · Android (progetto)

Porting dell'estensione Chrome in un'app Android nativa. Stesso comportamento: solo lettura della coda potluck, baseline silenziosa, avviso per ogni ASIN nuovo, sospensione su qualunque anomalia. Uso personale, APK installato a mano (niente Play Store).

## Scelta della piattaforma

| Opzione | Esito |
|---|---|
| Estensione su Firefox per Android | Scartata: con schermo spento Android congela Firefox e la scheda Vine viene scaricata, i controlli si fermano. |
| App nativa Kotlin (scelta) | Unica via per ~60 s con schermo spento: foreground service + wake lock. |
| App con WebView nascosta che esegue `core.js` | Ripiego se Amazon presenta troppi CAPTCHA alle GET native. Stesso lavoro di scheduling, più fragile. |

## Stack

- Kotlin, una sola Activity Compose, nessuna DI e nessuna Navigation.
- Jsoup per il parsing (stessi selettori di `core.js`, gira nei test JVM).
- `HttpURLConnection` (libreria standard) con i cookie letti da `CookieManager` della WebView; i `Set-Cookie` di risposta vengono riscritti in `CookieManager`. User-Agent = `WebSettings.getDefaultUserAgent()`.
- DataStore + kotlinx.serialization: un solo oggetto `State` in JSON (equivalente di `chrome.storage.local`), scrittura atomica.
- minSdk 26, target/compileSdk 36. Pacchetto `it.evoluzionetech.vine`.

## Mappatura dei file

| Estensione | Android |
|---|---|
| `core.js` `validURL/asin/read/reconcile` | `core/VineCore.kt`, stesse regole e stessi messaggi |
| `reader.js` paginazione, 100 pagine, 40 s | `core/Reader.kt` `scanAll()` con `withTimeout(40_000)` |
| `background.js` `scan/stop/serial` | `core/Monitor.kt` (`Mutex` al posto di `serial`) + `MonitorService.kt` |
| `chrome.storage` | `data/Store.kt` |
| `fetch` con sessione | `net/Http.kt` |
| `popup.*` | `ui/MainActivity.kt` (stato, Avvia/Ferma, registro) + `ui/LoginScreen.kt` (WebView) |
| `test.cjs` | `src/test`: `VineCoreTest`, `ReaderTest`, `MonitorTest` con fixture HTML reali ripulite |

Nota Jsoup: `selectFirst` può restituire l'elemento stesso; nei tile cercare tra i discendenti.

## Login

La WebView carica la coda potluck; Amazon reindirizza al sign-in. Quando l'URL torna valido per `validURL()`, si chiama `CookieManager.flush()` e si chiude la WebView. L'app non legge mai le credenziali. Il polling non usa WebView.

## Scheduler

- Foreground service di tipo `specialUse` (`dataSync` su Android 15 si ferma dopo 6 h/24 h; `shortService` dura 3 minuti), con notifica fissa di stato.
- Ciclo in coroutine: scansione, poi attesa casuale uniforme tra min e max minuti (impostabile 1–3, predefinito 1–3), estratta di nuovo prima di ogni controllo (`Monitor.delayMs`). `PARTIAL_WAKE_LOCK` riacquisito a ogni giro con timeout 70 s, così non resta mai bloccato.
- Al primo Avvia: richiesta di `POST_NOTIFICATIONS` ed esenzione dall'ottimizzazione batteria. Sui telefoni Xiaomi/Samsung/OnePlus/Huawei serve anche l'avvio automatico (dontkillmyapp.com).
- Nessun `BOOT_COMPLETED`: dopo riavvio o chiusura forzata lo stato diventa "Fermo… Premi Avvia", come in Chrome.
- Intervallo reale: 60 s a schermo acceso o in carica; 60–120 s a schermo spento con le esenzioni, senza garanzia; da 15 min a ore senza esenzioni. La UI dice "circa ogni minuto" e mostra l'ultimo controllo.
- Dopo l'MVP: fallback WorkManager ogni 15 min se l'utente rifiuta l'esenzione.

## Comportamenti da conservare

1. Solo GET verso URL che superano `validURL()` (potluck, solo parametri `queue` e `page`). Nessun click, carrello, ordine o richiesta di prodotto.
2. Redirect non seguiti: qualunque 3xx, 401/403/429/503, CAPTCHA, login, layout sconosciuto, conteggio incoerente o catalogo cambiato tra pagine sospende senza nuovi tentativi e senza aggiornare i già visti. Ripresa solo manuale.
3. Prima scansione silenziosa (baseline).
4. Stato ed eventi salvati insieme prima delle notifiche, deduplicati per ASIN, mai riproposti dopo un riavvio.
5. Nessuna cancellazione silenziosa dei vecchi ASIN. Registro consultabile anche con notifiche silenziate.

## Sicurezza

- Cookie solo in `CookieManager`: mai copiati, loggati o esportati. Logout = `removeAllCookies` + `flush` + cancellazione dei già visti.
- `android:allowBackup="false"` e `dataExtractionRules` che escludono tutto.
- `network_security_config`: niente cleartext, solo CA di sistema. Nessun bypass degli errori SSL, nessun pinning (Amazon ruota i certificati).
- WebView di login: nessun `addJavascriptInterface`, `allowFileAccess`/`allowContentAccess` false, Safe Browsing attivo, `onReceivedSslError` → `cancel()`, navigazione limitata a `*.amazon.it` (altri link nel browser esterno).
- Notifiche `VISIBILITY_PRIVATE` con versione pubblica generica "Nuovo prodotto Vine". Tocco → `https://www.amazon.it/dp/<ASIN validato>` con `PendingIntent` `FLAG_IMMUTABLE`.
- Release con R8, `debuggable=false`, nessun log di rete.

Permessi: `INTERNET`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

## Rischi

- Le condizioni d'uso Amazon vietano robot ed estrazione dati: ~1.440 GET al giorno possono portare a blocco o esclusione da Vine. Disclaimer da accettare al primo avvio; valutare un intervallo predefinito più lungo e uno stop automatico dopo N ore.
- Amazon può bloccare il login nella WebView o presentare più CAPTCHA alle GET native (impronta TLS diversa da Chrome).
- I produttori che chiudono le app in background possono fermare il servizio anche con le esenzioni.
- Consumo batteria del wake lock da misurare.
- Paginazione e coda vuota non verificate dal vivo, come nell'estensione.

## Ordine di lavoro

1. Prova sul telefono: login nella WebView e una GET con i cookie che restituisca la griglia (rischio principale, prima di tutto il resto).
2. Port di `VineCore` + `Reader` con i test JUnit su HTML reale.
3. Store, Monitor, foreground service, notifiche, schermata.
4. Misura di intervallo reale e batteria a schermo spento per una notte.
