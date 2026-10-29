package it.vine.monitor;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.webkit.*;
import android.widget.LinearLayout;

public class MainActivity extends Activity {
    private WebView uiWebView, amazonWebView;
    private SharedPreferences prefs;
    private static final String ALERT_CHANNEL = "vine_new_product";
    private final Handler handler = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "BatteryLife"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("vine_storage", MODE_PRIVATE);
        NotificationChannel ch = new NotificationChannel(ALERT_CHANNEL, "Nuovi Prodotti Vine", NotificationManager.IMPORTANCE_HIGH);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        uiWebView = new WebView(this);
        uiWebView.getSettings().setJavaScriptEnabled(true);
        uiWebView.getSettings().setAllowFileAccess(true);
        uiWebView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        amazonWebView = new WebView(this);
        amazonWebView.getSettings().setJavaScriptEnabled(true);
        amazonWebView.getSettings().setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(amazonWebView, true);
        amazonWebView.setWebViewClient(new WebViewClient());
        amazonWebView.loadUrl("https://www.amazon.it/vine/vine-items?queue=potluck");

        layout.addView(uiWebView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.48f));
        layout.addView(amazonWebView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.52f));
        setContentView(layout);

        uiWebView.loadUrl("file:///android_asset/popup.html");
        uiWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                String bgRunner =
                    "window.__state = JSON.parse(AndroidBridge.loadState() || '{\"running\":false,\"baseline\":false,\"seen\":{},\"events\":[],\"nextScan\":null,\"status\":\"Pronto. Avvia per creare la baseline silenziosa.\"}');" +
                    "window.__state.running = false; window.__state.nextScan = null;" +
                    "window.__timer = null;" +
                    "window.__save = function() { AndroidBridge.saveState(JSON.stringify(window.__state)); window.__notifyStateUpdate(window.__state); };" +
                    "window.__triggerScan = function() {" +
                    "  if (!window.__state.running) return;" +
                    "  window.__state.nextScan = Date.now() + 60000; window.__save();" +
                    "  AndroidBridge.scanAmazonPage();" +
                    "};" +
                    "window.onScanRawResult = function(raw) {" +
                    "  try {" +
                    "    const r = typeof raw === 'string' ? JSON.parse(raw) : raw;" +
                    "    if (!r.ok) throw Error(r.error);" +
                    "    const snap = VineCore.read(new DOMParser().parseFromString(r.html, 'text/html'), r.url);" +
                    "    const wasBase = window.__state.baseline;" +
                    "    window.__state = VineCore.reconcile(window.__state, snap.items, Date.now());" +
                    "    const fresh = window.__state.fresh; delete window.__state.fresh;" +
                    "    window.__state.status = wasBase ? 'Attivo — ultima scansione completata.' : 'Attivo — baseline silenziosa salvata.';" +
                    "    window.__save();" +
                    "    for (const it of fresh) AndroidBridge.notifyProduct(it.id, it.title, it.url);" +
                    "  } catch(e) {" +
                    "    if (/CAPTCHA|Accesso|Sessione scaduta/i.test(e.message)) {" +
                    "      window.__state.running = false; window.__state.nextScan = null; clearInterval(window.__timer);" +
                    "      AndroidBridge.setServiceActive(false);" +
                    "      window.__state.status = 'Sospeso: ' + e.message; window.__save();" +
                    "    } else {" +
                    "      window.__state.status = 'In attesa (standby/rete): ' + e.message + ' — Riprovo al prossimo ciclo.'; window.__save();" +
                    "    }" +
                    "  }" +
                    "};" +
                    "window.__notifyStateUpdate(window.__state);";
                uiWebView.evaluateJavascript(bgRunner, null);
            }
        });
    }

    public class Bridge {
        @JavascriptInterface public String loadState() { return prefs.getString("state_json", ""); }
        @JavascriptInterface public void saveState(String json) { prefs.edit().putString("state_json", json).apply(); }
        @JavascriptInterface public void setServiceActive(boolean active) {
            Intent svc = new Intent(MainActivity.this, KeepAliveService.class);
            if (active) startForegroundService(svc); else stopService(svc);
        }
        @JavascriptInterface public void notifyProduct(String id, String title, String url) {
            PendingIntent pi = PendingIntent.getActivity(MainActivity.this, id.hashCode(), new Intent(Intent.ACTION_VIEW, Uri.parse(url)), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(MainActivity.this, ALERT_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Nuovo prodotto Vine")
                    .setContentText(title)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            getSystemService(NotificationManager.class).notify(id.hashCode(), n);
        }
        @JavascriptInterface public String handleMessage(String type) {
            handler.post(() -> {
                if ("start".equals(type)) {
                    setServiceActive(true);
                    uiWebView.evaluateJavascript("window.__state.running=true; window.__state.status='Lettura in corso…'; clearInterval(window.__timer); window.__timer=setInterval(window.__triggerScan,60000); window.__triggerScan();", null);
                } else if ("stop".equals(type)) {
                    setServiceActive(false);
                    uiWebView.evaluateJavascript("window.__state.running=false; window.__state.nextScan=null; clearInterval(window.__timer); window.__state.status='Fermo — memoria dei prodotti conservata.'; window.__save();", null);
                } else {
                    uiWebView.evaluateJavascript("if(window.__state) window.__notifyStateUpdate(window.__state);", null);
                }
            });
            String saved = prefs.getString("state_json", "{\"running\":false,\"baseline\":false,\"seen\":{},\"events\":[],\"nextScan\":null,\"status\":\"Pronto.\"}");
            return "{\"ok\":true,\"state\":" + saved + "}";
        }
        @JavascriptInterface public void scanAmazonPage() {
            handler.post(() -> {
                String js = "(async function(){try{const r=await fetch('https://www.amazon.it/vine/vine-items?queue=potluck',{credentials:'same-origin',cache:'no-store'});return JSON.stringify({ok:true,url:r.url,html:await r.text()});}catch(e){return JSON.stringify({ok:false,error:e.message});}})()";
                amazonWebView.evaluateJavascript(js, res -> uiWebView.evaluateJavascript("window.onScanRawResult(" + res + ");", null));
            });
        }
    }
}
