package com.hirana.retrolauncher;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity implements PhoneView.Host {

    private static final int REQ_CALL = 7;

    private PhoneView view;
    private ToneGenerator toneGen;
    private String pendingNumber;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
        view = new PhoneView(this, this);
        setContentView(view);
        try {
            toneGen = new ToneGenerator(AudioManager.STREAM_DTMF, 70);
        } catch (Exception e) {
            toneGen = null;
        }
        hideBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        loadApps();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        handler.post(signalPoll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
        handler.removeCallbacks(signalPoll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (toneGen != null) toneGen.release();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        view.reset();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        view.back();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ---------- Pil ve sinyal ----------

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level < 0 || scale <= 0) return;
            int pct = level * 100 / scale;
            int idx = pct < 10 ? 0 : pct < 35 ? 1 : pct < 60 ? 2 : pct < 85 ? 3 : 4;
            view.setBattery(idx);
        }
    };

    private final Runnable signalPoll = new Runnable() {
        @Override
        public void run() {
            int level = 0;
            try {
                TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null && tm.getSimState() == TelephonyManager.SIM_STATE_READY) {
                    SignalStrength ss = tm.getSignalStrength();
                    if (ss != null) level = ss.getLevel();
                }
            } catch (Exception ignored) {
            }
            view.setSignal(level);
            handler.postDelayed(this, 5000);
        }
    };

    // ---------- Uygulama listesi ----------

    private static class Spec {
        final String key, label;
        final Set<String> pkgs = new LinkedHashSet<>();
        final String[] words;

        Spec(String key, String label, String... words) {
            this.key = key;
            this.label = label;
            this.words = words;
        }
    }

    private void addCategory(Spec s, String category) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo r : getPackageManager().queryIntentActivities(i, 0)) {
                s.pkgs.add(r.activityInfo.packageName);
            }
        } catch (Exception ignored) {
        }
    }

    private void addResolve(Spec s, Intent i) {
        try {
            PackageManager pm = getPackageManager();
            ResolveInfo def = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (def != null && def.activityInfo != null && !"android".equals(def.activityInfo.packageName)) {
                s.pkgs.add(def.activityInfo.packageName);
            }
            for (ResolveInfo r : pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)) {
                s.pkgs.add(r.activityInfo.packageName);
            }
        } catch (Exception ignored) {
        }
    }

    private static void addPkgs(Spec s, String... pkgs) {
        Collections.addAll(s.pkgs, pkgs);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        List<AppEntry> all = new ArrayList<>();
        for (ResolveInfo r : ris) {
            if (r.activityInfo == null) continue;
            if (getPackageName().equals(r.activityInfo.packageName)) continue;
            CharSequence l = r.loadLabel(pm);
            all.add(new AppEntry(l == null ? r.activityInfo.packageName : l.toString(),
                    new ComponentName(r.activityInfo.packageName, r.activityInfo.name)));
        }

        // Ekranda görünme sırası
        Spec phone = new Spec("phone", "Telefon", "telefon", "phone", "dial", "arama");
        Spec contacts = new Spec("contacts", "Kişiler", "kişi", "kisi", "rehber", "contact");
        Spec messages = new Spec("messages", "Mesajlar");
        Spec calendar = new Spec("calendar", "Takvim");
        Spec gallery = new Spec("gallery", "Galeri");
        Spec camera = new Spec("camera", "Kamera");
        Spec music = new Spec("music", "Müzik");
        Spec files = new Spec("files", "Dosyalar");
        Spec settings = new Spec("settings", "Ayarlar");
        Spec play = new Spec("playstore", "Play Store");
        Spec internet = new Spec("internet", "İnternet");
        Spec maps = new Spec("maps", "Haritalar");
        Spec gmail = new Spec("gmail", "Gmail");
        Spec clock = new Spec("clock", "Saat");
        Spec calc = new Spec("calculator", "Hesap Mak.");
        Spec whatsapp = new Spec("whatsapp", "WhatsApp");
        Spec youtube = new Spec("youtube", "YouTube");

        // Telefon
        try {
            TelecomManager tc = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            if (tc != null && tc.getDefaultDialerPackage() != null) phone.pkgs.add(tc.getDefaultDialerPackage());
        } catch (Exception ignored) {
        }
        addResolve(phone, new Intent(Intent.ACTION_DIAL));
        addPkgs(phone, "com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer", "com.android.contacts");
        // Kişiler
        addCategory(contacts, Intent.CATEGORY_APP_CONTACTS);
        addPkgs(contacts, "com.google.android.contacts", "com.samsung.android.app.contacts", "com.android.contacts");
        // Mesajlar
        try {
            String sms = Telephony.Sms.getDefaultSmsPackage(this);
            if (sms != null) messages.pkgs.add(sms);
        } catch (Exception ignored) {
        }
        addCategory(messages, Intent.CATEGORY_APP_MESSAGING);
        addPkgs(messages, "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms");
        // Takvim
        addCategory(calendar, Intent.CATEGORY_APP_CALENDAR);
        addPkgs(calendar, "com.google.android.calendar", "com.samsung.android.calendar", "com.android.calendar");
        // Galeri
        addCategory(gallery, Intent.CATEGORY_APP_GALLERY);
        addPkgs(gallery, "com.sec.android.gallery3d", "com.miui.gallery", "com.google.android.apps.photos", "com.android.gallery3d");
        // Kamera
        addResolve(camera, new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
        addPkgs(camera, "com.sec.android.app.camera", "com.android.camera", "com.google.android.GoogleCamera");
        // Müzik
        addCategory(music, Intent.CATEGORY_APP_MUSIC);
        addPkgs(music, "com.google.android.apps.youtube.music", "com.spotify.music", "com.miui.player", "com.sec.android.app.music");
        // Dosyalar
        addCategory(files, "android.intent.category.APP_FILES");
        addPkgs(files, "com.sec.android.app.myfiles", "com.google.android.apps.nbu.files",
                "com.mi.android.globalFileexplorer", "com.android.fileexplorer", "com.google.android.documentsui");
        // Diğerleri
        addPkgs(settings, "com.android.settings");
        addPkgs(play, "com.android.vending");
        addCategory(internet, Intent.CATEGORY_APP_BROWSER);
        addPkgs(internet, "com.android.chrome", "com.sec.android.app.sbrowser");
        addCategory(maps, Intent.CATEGORY_APP_MAPS);
        addPkgs(maps, "com.google.android.apps.maps");
        addPkgs(gmail, "com.google.android.gm");
        addResolve(clock, new Intent(AlarmClock.ACTION_SHOW_ALARMS));
        addPkgs(clock, "com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock");
        addCategory(calc, Intent.CATEGORY_APP_CALCULATOR);
        addPkgs(calc, "com.google.android.calculator", "com.sec.android.app.popupcalculator",
                "com.miui.calculator", "com.android.calculator2");
        addPkgs(whatsapp, "com.whatsapp", "com.whatsapp.w4b");
        addPkgs(youtube, "com.google.android.youtube");

        Spec[] display = {phone, contacts, messages, calendar, gallery, camera, music, files, settings,
                play, internet, maps, gmail, clock, calc, whatsapp, youtube};
        // Önce paket adı kesin olanlar eşleşsin
        Spec[] process = {whatsapp, youtube, play, settings, gmail, phone, contacts, messages, camera,
                clock, calc, calendar, gallery, files, internet, maps, music};

        java.util.Map<Spec, AppEntry> found = new java.util.HashMap<>();
        Set<AppEntry> used = new java.util.HashSet<>();
        for (Spec s : process) {
            AppEntry hit = null;
            for (String p : s.pkgs) {
                List<AppEntry> cands = new ArrayList<>();
                for (AppEntry a : all) if (!used.contains(a) && a.pkg().equals(p)) cands.add(a);
                if (cands.isEmpty()) continue;
                hit = cands.get(0);
                if (cands.size() > 1 && s.words.length > 0) {
                    for (AppEntry a : cands) {
                        String l = a.label.toLowerCase(new Locale("tr"));
                        for (String w : s.words) if (l.contains(w)) { hit = a; break; }
                        if (hit != cands.get(0)) break;
                    }
                }
                break;
            }
            if (hit != null) {
                used.add(hit);
                found.put(s, hit);
            }
        }

        List<AppEntry> result = new ArrayList<>();
        for (Spec s : display) {
            AppEntry a = found.get(s);
            if (a != null) {
                a.iconKey = s.key;
                a.label = s.label;
                result.add(a);
            }
        }
        List<AppEntry> rest = new ArrayList<>();
        for (AppEntry a : all) if (!used.contains(a)) rest.add(a);
        final Collator col = Collator.getInstance(new Locale("tr"));
        Collections.sort(rest, (x, y) -> col.compare(x.label, y.label));
        result.addAll(rest);
        view.setApps(result);
    }

    // ---------- PhoneView.Host ----------

    @Override
    public void launch(AppEntry app) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(app.component);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Açılamadı", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void appInfo(AppEntry app) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", app.pkg(), null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void call(String number) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            startCall(number, true);
        } else {
            pendingNumber = number;
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQ_CALL);
        }
    }

    private void startCall(String number, boolean direct) {
        try {
            Intent i = new Intent(direct ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.fromParts("tel", number, null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            view.clearDial();
        } catch (Exception e) {
            Toast.makeText(this, "Arama yapılamadı", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CALL && pendingNumber != null) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            startCall(pendingNumber, ok);
            pendingNumber = null;
        }
    }

    @Override
    public void tone(int key) {
        if (toneGen == null || key < 0 || key > 11) return;
        try {
            toneGen.startTone(key, 120); // TONE_DTMF_0..9, S(*), P(#) sırası tuş numaralarıyla aynı
        } catch (Exception ignored) {
        }
    }
}
