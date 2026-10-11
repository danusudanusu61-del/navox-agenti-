package com.navox.agent;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.speech.RecognizerIntent;
import android.app.SearchManager;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

public class MainActivity extends Activity {
    WebView web;
    String pendingCmd;
    boolean loaded = false;
    static final int VOICE = 1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#FCFBF8"));
        getWindow().setNavigationBarColor(Color.parseColor("#FCFBF8"));
        int uiFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) uiFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(uiFlags);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#FCFBF8"));
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "NAVOX");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("file:")) return false;
                openUrl(u, "");
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                loaded = true;
                deliverCmd();
            }
        });
        pendingCmd = getIntent().getStringExtra("cmd");
        if (getSharedPreferences("navox", 0).getBoolean("wake", false) && !ListenService.running) {
            new Bridge().startListening();
        }
        web.loadUrl("file:///android_asset/index.html");
    }

    boolean openUrl(String url, String pkg) {
        boolean hasPkg = pkg != null && !pkg.isEmpty();
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (hasPkg) i.setPackage(pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return hasPkg && openUrl(url, "");
        }
    }

    boolean start(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void js(String code) {
        web.evaluateJavascript(code, null);
    }

    void deliverCmd() {
        if (loaded && pendingCmd != null) {
            js("window.onWakeCommand&&window.onWakeCommand(" + JSONObject.quote(pendingCmd) + ")");
            pendingCmd = null;
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String c = i.getStringExtra("cmd");
        if (c != null) {
            pendingCmd = c;
            deliverCmd();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        js("window.onNativeResume&&window.onNativeResume()");
    }

    @Override
    public void onRequestPermissionsResult(int rq, String[] p, int[] r) {
        super.onRequestPermissionsResult(rq, p, r);
        js("window.onNativeResume&&window.onNativeResume()");
    }

    class Bridge {
        @JavascriptInterface
        public boolean launch(String pkg) {
            try {
                PackageManager pm = getPackageManager();
                Intent i = pm.getLaunchIntentForPackage(pkg);
                if (i == null) {
                    Intent q = new Intent(Intent.ACTION_MAIN);
                    q.addCategory(Intent.CATEGORY_LAUNCHER);
                    q.setPackage(pkg);
                    java.util.List<ResolveInfo> l = pm.queryIntentActivities(q, 0);
                    if (l.isEmpty()) return false;
                    i = new Intent(Intent.ACTION_MAIN);
                    i.addCategory(Intent.CATEGORY_LAUNCHER);
                    i.setClassName(pkg, l.get(0).activityInfo.name);
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean openUrl(String url, String pkg) {
            return MainActivity.this.openUrl(url, pkg);
        }

        @JavascriptInterface
        public boolean dial(String n) {
            return start(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))));
        }

        @JavascriptInterface
        public boolean sms(String n, String body) {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n)));
            i.putExtra("sms_body", body);
            return start(i);
        }

        @JavascriptInterface
        public boolean alarm(int h, int m, String label) {
            Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
            i.putExtra(AlarmClock.EXTRA_HOUR, h);
            i.putExtra(AlarmClock.EXTRA_MINUTES, m);
            i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            return start(i);
        }

        @JavascriptInterface
        public boolean timer(int sec, String label) {
            Intent i = new Intent(AlarmClock.ACTION_SET_TIMER);
            i.putExtra(AlarmClock.EXTRA_LENGTH, sec);
            i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            return start(i);
        }

        @JavascriptInterface
        public boolean settings(String w) {
            String a = Settings.ACTION_SETTINGS;
            if (w.equals("wifi")) a = Settings.ACTION_WIFI_SETTINGS;
            else if (w.equals("bluetooth")) a = Settings.ACTION_BLUETOOTH_SETTINGS;
            else if (w.equals("location")) a = Settings.ACTION_LOCATION_SOURCE_SETTINGS;
            else if (w.equals("display")) a = Settings.ACTION_DISPLAY_SETTINGS;
            else if (w.equals("sound")) a = Settings.ACTION_SOUND_SETTINGS;
            else if (w.equals("airplane")) a = Settings.ACTION_AIRPLANE_MODE_SETTINGS;
            else if (w.equals("battery")) a = Settings.ACTION_BATTERY_SAVER_SETTINGS;
            return start(new Intent(a));
        }

        @JavascriptInterface
        public boolean play(String q) {
            Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
            i.putExtra(SearchManager.QUERY, q);
            i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
            return start(i);
        }

        @JavascriptInterface
        public boolean torch(boolean on) {
            try {
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                for (String id : cm.getCameraIdList()) {
                    Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (f != null && f) {
                        cm.setTorchMode(id, on);
                        return true;
                    }
                }
            } catch (Exception e) { }
            return false;
        }

        @JavascriptInterface
        public String setupStatus() {
            JSONObject o = new JSONObject();
            try {
                o.put("mic", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
                o.put("notif", Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED);
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                o.put("battery", pm.isIgnoringBatteryOptimizations(getPackageName()));
                o.put("overlay", Settings.canDrawOverlays(MainActivity.this));
                o.put("listening", ListenService.running);
            } catch (Exception e) { }
            return o.toString();
        }

        @JavascriptInterface
        public void askMic() {
            runOnUiThread(new Runnable() {
                public void run() {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
                }
            });
        }

        @JavascriptInterface
        public void askNotif() {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
                }
            });
        }

        @JavascriptInterface
        public void askBattery() {
            runOnUiThread(new Runnable() {
                public void run() {
                    start(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                }
            });
        }

        @JavascriptInterface
        public void askOverlay() {
            runOnUiThread(new Runnable() {
                public void run() {
                    start(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                }
            });
        }

        @JavascriptInterface
        public void setLang(String l) {
            getSharedPreferences("navox", 0).edit().putString("lang", l).apply();
        }

        @JavascriptInterface
        public boolean startListening() {
            try {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false;
                getSharedPreferences("navox", 0).edit().putBoolean("wake", true).apply();
                Intent s = new Intent(MainActivity.this, ListenService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean stopListening() {
            getSharedPreferences("navox", 0).edit().putBoolean("wake", false).apply();
            return stopService(new Intent(MainActivity.this, ListenService.class));
        }

        @JavascriptInterface
        public String getApps() {
            JSONArray a = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                Intent q = new Intent(Intent.ACTION_MAIN);
                q.addCategory(Intent.CATEGORY_LAUNCHER);
                for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
                    JSONObject o = new JSONObject();
                    o.put("n", r.loadLabel(pm).toString());
                    o.put("p", r.activityInfo.packageName);
                    a.put(o);
                }
            } catch (Exception e) { }
            return a.toString();
        }

        @JavascriptInterface
        public void listen(final String lang) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
                        startActivityForResult(i, VOICE);
                    } catch (Exception e) {
                        js("window.onNativeVoiceError&&window.onNativeVoiceError()");
                    }
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int rq, int rs, Intent d) {
        super.onActivityResult(rq, rs, d);
        if (rq != VOICE) return;
        if (rs == RESULT_OK && d != null) {
            ArrayList<String> l = d.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (l != null && !l.isEmpty()) {
                js("window.onNativeVoice(" + JSONObject.quote(l.get(0)) + ")");
                return;
            }
        }
        js("window.onNativeVoiceError&&window.onNativeVoiceError()");
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
