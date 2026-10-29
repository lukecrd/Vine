package it.vine.monitor;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.webkit.*;
import android.widget.LinearLayout;
import org.json.JSONObject;

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
        uiWebView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        uiWebView.addJavascriptInterface(new Bridge(), "AndroidBridge");

        amazonWebView = new WebView(this);
        amazonWebView.getSettings().setJavaScriptEnabled(true);
        amazonWebView.getSettings().setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(amazonWebView, true);
        amazonWebView.setWebViewClient(new WebViewClient());
        amazonWebView.loadUrl("https://www.amazon.it/vine/vine-items?queue=potluck");

        layout.addView(uiWebView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.54f));
        layout.addView(amazonWebView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.46f));
        setContentView(layout);

        uiWebView.loadUrl("file:///android_asset/popup.html");
        uiWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                String bgRunner =
                    "window.__state = Object.assign({running:false,baseline:false,seen:{},events:[],scanMode:'fixed',notifMode:'sound',soundType:'chime',soundVolume:80,nextScan:null,status:'Pronto. Avvia per creare la baseline silenziosa.'}, JSON.parse(AndroidBridge.loadState() || '{}'));" +
                    "window.__state.running = false; window.__state.nextScan = null;" +
                    "window.__timer = null;" +
                    "window.__save = function() { AndroidBridge.saveState(JSON.stringify(window.__state)); window.__notifyStateUpdate(window.__state); };" +
                    "window.__nextDelayMs = function() {" +
                    "  return window.__state.scanMode === 'random' ? Math.floor(60000 + Math.random() * 120000) : 60000;" +
                    "};" +
                    "window.__scheduleNext = function() {" +
                    "  clearTimeout(window.__timer);" +
                    "  if (!window.__state.running) { window.__state.nextScan = null; return; }" +
                    "  const ms = window.__nextDelayMs();" +
                    "  window.__state.nextScan = Date.now() + ms;" +
                    "  window.__timer = setTimeout(window.__triggerScan, ms);" +
                    "};" +
                    "window.__triggerScan = function() {" +
                    "  if (!window.__state.running) return;" +
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
                    "    window.__scheduleNext();" +
                    "    window.__save();" +
                    "    if ((window.__state.notifMode || 'sound') !== 'off') {" +
                    "      for (const it of fresh) AndroidBridge.notifyProduct(it.id, it.title, it.url);" +
                    "    }" +
                    "  } catch(e) {" +
                    "    if (/CAPTCHA|Accesso|Sessione scaduta/i.test(e.message)) {" +
                    "      window.__state.running = false; window.__state.nextScan = null; clearTimeout(window.__timer);" +
                    "      AndroidBridge.setServiceActive(false);" +
                    "      window.__state.status = 'Sospeso: ' + e.message; window.__save();" +
                    "    } else {" +
                    "      window.__state.status = 'In attesa (standby/rete): ' + e.message + ' — Riprovo al prossimo ciclo.';" +
                    "      window.__scheduleNext();" +
                    "      window.__save();" +
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
        @JavascriptInterface public void playSystemSound() {
            try {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                Ringtone rt = RingtoneManager.getRingtone(MainActivity.this, uri);
                if (rt != null) rt.play();
            } catch (Exception ignored) {}
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
        @JavascriptInterface public String handleMessage(String rawJson) {
            try {
                JSONObject msg = new JSONObject(rawJson);
                String type = msg.optString("type", "status");
                String scanMode = msg.optString("scanMode", "");
                String notifMode = msg.optString("notifMode", "");
                String soundType = msg.optString("soundType", "");
                int soundVolume = msg.optInt("soundVolume", 0);
                handler.post(() -> {
                    String updateCfg = "";
                    if (!scanMode.isEmpty()) updateCfg += "window.__state.scanMode='" + scanMode + "';";
                    if (!notifMode.isEmpty()) updateCfg += "window.__state.notifMode='" + notifMode + "';";
                    if (!soundType.isEmpty()) updateCfg += "window.__state.soundType='" + soundType + "';";
                    if (soundVolume > 0) updateCfg += "window.__state.soundVolume=" + soundVolume + ";";
                    if ("config".equals(type)) {
                        uiWebView.evaluateJavascript(updateCfg + "if(window.__state.running) window.__scheduleNext(); window.__save();", null);
                    } else if ("start".equals(type)) {
                        setServiceActive(true);
                        uiWebView.evaluateJavascript(updateCfg + "window.__state.running=true; window.__state.status='Lettura in corso…'; window.__save(); window.__triggerScan();", null);
                    } else if ("stop".equals(type)) {
                        setServiceActive(false);
                        uiWebView.evaluateJavascript("window.__state.running=false; window.__state.nextScan=null; clearTimeout(window.__timer); window.__state.status='Fermo — memoria dei prodotti conservata.'; window.__save();", null);
                    } else {
                        uiWebView.evaluateJavascript("if(window.__state) window.__notifyStateUpdate(window.__state);", null);
                    }
                });
            } catch (Exception ignored) {}
            String saved = prefs.getString("state_json", "{\"running\":false,\"baseline\":false,\"seen\":{},\"events\":[],\"scanMode\":\"fixed\",\"notifMode\":\"sound\",\"soundType\":\"chime\",\"soundVolume\":80,\"nextScan\":null,\"status\":\"Pronto.\"}");
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