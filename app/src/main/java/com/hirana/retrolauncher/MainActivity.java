package com.hirana.retrolauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.CallLog;
import android.provider.ContactsContract;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity implements PhoneView.Host {

    private static final int REQ_CALL = 7, REQ_CONTACTS = 8, REQ_CALLLOG = 9, REQ_PICK = 100;

    private PhoneView view;
    private SharedPreferences sp;
    private String pendingNumber;
    private int pendingSlot = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PhoneView.Status status = new PhoneView.Status();
    private volatile List<PhoneView.Contact> contacts = new ArrayList<>();
    private static final Map<String, android.graphics.Bitmap> autoIconCache = new HashMap<>();
    private SensorManager sensors;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
        sp = getSharedPreferences("retro", MODE_PRIVATE);
        view = new PhoneView(this, this);
        setContentView(view);
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        hideStatusBar();
        loadContacts();
        if (!sp.getBoolean("askedCallLog", false)) {
            sp.edit().putBoolean("askedCallLog", true).apply();
            requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG}, REQ_CALLLOG);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideStatusBar();
        loadApps();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        handler.post(statusPoll);
        view.setActive(true);
        try {
            Sensor light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (light != null) sensors.registerListener(lightListener, light, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
        handler.removeCallbacks(statusPoll);
        view.setActive(false);
        try {
            sensors.unregisterListener(lightListener);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        view.goHome();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideStatusBar();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        view.back();
    }

    /** Sadece üst durum çubuğu gizlenir; alttaki 3 tuş görünür kalır. */
    @SuppressWarnings("deprecation")
    private void hideStatusBar() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(true);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars());
                c.show(WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    // ================= Durum bilgileri =================

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) status.battery = level * 100 / scale;
            status.charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            view.setStatus(status);
        }
    };

    private final SensorEventListener lightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            float lux = e.values[0];
            boolean changed = (status.lux < 15) != (lux < 15) || status.lux < 0;
            status.lux = lux;
            if (changed) view.setStatus(status);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    private final Runnable statusPoll = new Runnable() {
        @Override
        public void run() {
            readStatus();
            view.setStatus(status);
            handler.postDelayed(this, 2000);
        }
    };

    @SuppressWarnings("deprecation")
    private void readStatus() {
        // Sinyal ve operatör
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            int level = 0;
            String op = "";
            if (tm != null && tm.getSimState() == TelephonyManager.SIM_STATE_READY) {
                SignalStrength ss = tm.getSignalStrength();
                if (ss != null) level = ss.getLevel();
                op = tm.getNetworkOperatorName();
            }
            status.signal = level;
            status.operator = op == null ? "" : op;
        } catch (Exception ignored) {
        }
        // Wi-Fi ve mobil veri
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            boolean wifi = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            status.data = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
            if (wifi) {
                int lvl = 2;
                try {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    lvl = WifiManager.calculateSignalLevel(wm.getConnectionInfo().getRssi(), 3);
                } catch (Exception ignored) {
                }
                status.wifi = lvl;
            } else {
                status.wifi = -1;
            }
        } catch (Exception e) {
            status.wifi = -1;
            status.data = false;
        }
        // Bluetooth
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            status.bt = ad != null && ad.isEnabled();
        } catch (Exception e) {
            status.bt = false;
        }
        // Uçak modu, ses, alarm, kulaklık, konum
        try {
            status.airplane = Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        } catch (Exception ignored) {
        }
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int rm = am.getRingerMode();
            status.mute = rm == AudioManager.RINGER_MODE_SILENT;
            status.vibrate = rm == AudioManager.RINGER_MODE_VIBRATE;
            boolean hp = false;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    hp = true;
                }
            }
            status.headphones = hp;
        } catch (Exception ignored) {
        }
        try {
            AlarmManager alm = (AlarmManager) getSystemService(ALARM_SERVICE);
            status.alarm = alm.getNextAlarmClock() != null;
        } catch (Exception ignored) {
        }
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            status.location = lm.isLocationEnabled();
        } catch (Exception ignored) {
        }
        // Cevapsız aramalar (arama kaydından)
        status.callLogAccess = checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
        status.notif = 0;
        status.sms = 0;
        status.missed = 0;
        if (status.callLogAccess) {
            try {
                Cursor c = getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls._ID},
                        CallLog.Calls.TYPE + "=" + CallLog.Calls.MISSED_TYPE + " AND " + CallLog.Calls.NEW + "=1",
                        null, null);
                if (c != null) {
                    status.missed = c.getCount();
                    c.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ================= Uygulama listesi =================

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
            for (ResolveInfo r : getPackageManager().queryIntentActivities(i, 0)) s.pkgs.add(r.activityInfo.packageName);
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

    /** Hazır piksel ikonu olan popüler uygulamalar (paket adı → ikon). */
    private static final Map<String, String> POPULAR = new HashMap<>();

    static {
        String[][] p = {
                {"com.instagram.android", "instagram"}, {"com.facebook.katana", "facebook"}, {"com.facebook.lite", "facebook"},
                {"com.facebook.orca", "messenger"}, {"org.telegram.messenger", "telegram"},
                {"com.zhiliaoapp.musically", "tiktok"}, {"com.ss.android.ugc.trill", "tiktok"},
                {"com.twitter.android", "x"}, {"com.snapchat.android", "snapchat"}, {"com.spotify.music", "spotify"},
                {"com.netflix.mediaclient", "netflix"}, {"com.android.chrome", "chrome"},
                {"com.google.android.googlequicksearchbox", "google"}, {"com.google.android.apps.docs", "drive"},
                {"com.google.android.apps.photos", "photos"}, {"us.zoom.videomeetings", "zoom"},
                {"trendyol.com", "trendyol"}, {"com.pozitron.hepsiburada", "hepsiburada"}, {"com.getir", "getir"},
                {"com.inovel.app.yemeksepeti", "yemeksepeti"}, {"tr.gov.turkiye.edevlet.kapisi", "edevlet"},
                {"com.ziraat.ziraatmobil", "bank"}, {"com.ykb.android", "bank"}, {"com.garanti.cepsubesi", "bank"},
                {"com.akbank.android.apps.akbank_direkt", "bank"}, {"com.pozitron.iscep", "bank"},
                {"com.vakifbank.mobile", "bank"}, {"com.tmobtech.halkbank", "bank"}, {"com.finansbank.mobile.cepsube", "bank"},
                {"com.denizbank.mobildeniz", "bank"}, {"com.teb", "bank"}, {"com.ingbanktr.ingmobil", "bank"},
                {"com.kuveytturk.mobil", "bank"}, {"finansbank.enpara", "bank"}, {"com.mobillium.papara", "bank"},
                {"com.sec.android.daemonapp", "weather"}, {"com.google.android.apps.weather", "weather"},
                {"com.samsung.android.app.notes", "notes"}, {"com.google.android.keep", "notes"},
                {"com.sec.android.app.fm", "radio"}, {"com.shazam.android", "shazam"}
        };
        for (String[] e : p) POPULAR.put(e[0], e[1]);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        List<AppEntry> all = new ArrayList<>();
        Map<AppEntry, ResolveInfo> infoOf = new HashMap<>();
        for (ResolveInfo r : ris) {
            if (r.activityInfo == null || getPackageName().equals(r.activityInfo.packageName)) continue;
            CharSequence l = r.loadLabel(pm);
            AppEntry a = new AppEntry(l == null ? r.activityInfo.packageName : l.toString(),
                    new ComponentName(r.activityInfo.packageName, r.activityInfo.name));
            all.add(a);
            infoOf.put(a, r);
        }

        Spec phone = new Spec("phone", "Telefon", "telefon", "phone", "dial", "arama");
        Spec contactsS = new Spec("contacts", "Kişiler", "kişi", "kisi", "rehber", "contact");
        Spec messages = new Spec("messages", "Mesajlar");
        Spec calendar = new Spec("calendar", "Takvim");
        Spec gallery = new Spec("gallery", "Galeri");
        Spec camera = new Spec("camera", "Kamera");
        Spec music = new Spec("music", "Müzik");
        Spec files = new Spec("files", "Dosyalar");
        Spec settings = new Spec("settings", "Sistem");
        Spec play = new Spec("playstore", "Play Store");
        Spec internet = new Spec("internet", "İnternet");
        Spec maps = new Spec("maps", "Haritalar");
        Spec gmail = new Spec("gmail", "Gmail");
        Spec clock = new Spec("clock", "Saat");
        Spec calc = new Spec("calculator", "Hesap Mak.");
        Spec whatsapp = new Spec("whatsapp", "WhatsApp");
        Spec youtube = new Spec("youtube", "YouTube");

        try {
            TelecomManager tc = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tc != null && tc.getDefaultDialerPackage() != null) phone.pkgs.add(tc.getDefaultDialerPackage());
        } catch (Exception ignored) {
        }
        addResolve(phone, new Intent(Intent.ACTION_DIAL));
        addPkgs(phone, "com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer", "com.android.contacts");
        addCategory(contactsS, Intent.CATEGORY_APP_CONTACTS);
        addPkgs(contactsS, "com.google.android.contacts", "com.samsung.android.app.contacts", "com.android.contacts");
        try {
            String smsPkg = Telephony.Sms.getDefaultSmsPackage(this);
            if (smsPkg != null) messages.pkgs.add(smsPkg);
        } catch (Exception ignored) {
        }
        addCategory(messages, Intent.CATEGORY_APP_MESSAGING);
        addPkgs(messages, "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms");
        addCategory(calendar, Intent.CATEGORY_APP_CALENDAR);
        addPkgs(calendar, "com.google.android.calendar", "com.samsung.android.calendar", "com.android.calendar");
        addCategory(gallery, Intent.CATEGORY_APP_GALLERY);
        addPkgs(gallery, "com.sec.android.gallery3d", "com.miui.gallery", "com.android.gallery3d");
        addResolve(camera, new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
        addPkgs(camera, "com.sec.android.app.camera", "com.android.camera", "com.google.android.GoogleCamera");
        addCategory(music, Intent.CATEGORY_APP_MUSIC);
        addPkgs(music, "com.google.android.apps.youtube.music", "com.miui.player", "com.sec.android.app.music");
        addCategory(files, "android.intent.category.APP_FILES");
        addPkgs(files, "com.sec.android.app.myfiles", "com.google.android.apps.nbu.files",
                "com.mi.android.globalFileexplorer", "com.android.fileexplorer", "com.google.android.documentsui");
        addPkgs(settings, "com.android.settings");
        addPkgs(play, "com.android.vending");
        addCategory(internet, Intent.CATEGORY_APP_BROWSER);
        addPkgs(internet, "com.sec.android.app.sbrowser", "com.android.chrome");
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

        Spec[] display = {phone, contactsS, messages, calendar, gallery, camera, music, files, settings,
                play, internet, maps, gmail, clock, calc, whatsapp, youtube};
        Spec[] process = {whatsapp, youtube, play, settings, gmail, phone, contactsS, messages, camera,
                clock, calc, calendar, gallery, files, internet, maps, music};

        Map<Spec, AppEntry> found = new HashMap<>();
        Set<AppEntry> used = new HashSet<>();
        Locale tr = new Locale("tr");
        for (Spec s : process) {
            AppEntry hit = null;
            for (String p : s.pkgs) {
                List<AppEntry> cands = new ArrayList<>();
                for (AppEntry a : all) if (!used.contains(a) && a.pkg().equals(p)) cands.add(a);
                if (cands.isEmpty()) continue;
                hit = cands.get(0);
                if (cands.size() > 1 && s.words.length > 0) {
                    outer:
                    for (AppEntry a : cands) {
                        String l = a.label.toLowerCase(tr);
                        for (String w : s.words) {
                            if (l.contains(w)) {
                                hit = a;
                                break outer;
                            }
                        }
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
        AppEntry mine = new AppEntry("Ayarlar", null);
        mine.special = "settings";
        mine.iconKey = "settings";
        result.add(mine);
        for (Spec s : display) {
            AppEntry a = found.get(s);
            if (a != null) {
                a.iconKey = s.key;
                a.label = s.label;
                result.add(a);
            }
        }
        final Collator col = Collator.getInstance(tr);
        List<AppEntry> popular = new ArrayList<>(), rest = new ArrayList<>();
        for (AppEntry a : all) {
            if (used.contains(a)) continue;
            String key = POPULAR.get(a.pkg());
            if (key != null) {
                a.iconKey = key;
                popular.add(a);
            } else {
                String ck = a.component.flattenToShortString();
                android.graphics.Bitmap b = autoIconCache.get(ck);
                if (b == null) {
                    try {
                        b = IconMaker.make(infoOf.get(a).loadIcon(pm));
                    } catch (Exception ignored) {
                    }
                    if (b != null) autoIconCache.put(ck, b);
                }
                a.autoIcon = b;
                rest.add(a);
            }
        }
        Collections.sort(popular, (x, y) -> col.compare(x.label, y.label));
        Collections.sort(rest, (x, y) -> col.compare(x.label, y.label));
        result.addAll(popular);
        result.addAll(rest);
        view.setApps(result);
    }

    // ================= Rehber =================

    private void loadContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return;
        new Thread(() -> {
            List<PhoneView.Contact> list = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            try {
                ContentResolver cr = getContentResolver();
                Cursor c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER},
                        null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
                if (c != null) {
                    while (c.moveToNext()) {
                        String name = c.getString(0), num = c.getString(1);
                        if (name == null || num == null) continue;
                        String k = name + "|" + num.replaceAll("[^0-9]", "");
                        if (seen.add(k)) list.add(new PhoneView.Contact(name, num));
                    }
                    c.close();
                }
            } catch (Exception ignored) {
            }
            contacts = list;
        }).start();
    }

    @Override
    public void requestContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) return;
        if (sp.getBoolean("askedContacts", false)) return;
        sp.edit().putBoolean("askedContacts", true).apply();
        requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS);
    }

    @Override
    public List<PhoneView.Contact> contacts() {
        return contacts;
    }

    // ================= PhoneView.Host =================

    @Override
    public void launch(AppEntry app) {
        if (app.component == null) return;
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
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg(), null));
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
    public void requestCallLog() {
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Cevapsız aramalar zaten açık", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sp.getBoolean("askedCallLog", false) && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALL_LOG)) {
            appInfo(new AppEntry("", new ComponentName(getPackageName(), MainActivity.class.getName())));
            Toast.makeText(this, "İzinler > Arama kaydı > İzin ver", Toast.LENGTH_LONG).show();
            return;
        }
        sp.edit().putBoolean("askedCallLog", true).apply();
        requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG}, REQ_CALLLOG);
    }

    @Override
    public void openHomeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void pickContact(int slot) {
        pendingSlot = slot;
        try {
            Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "Rehber açılamadı", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Cursor c = getContentResolver().query(data.getData(),
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null);
            if (c != null) {
                if (c.moveToFirst() && pendingSlot >= 2) {
                    sp.edit().putString("sd" + pendingSlot + "name", c.getString(0))
                            .putString("sd" + pendingSlot + "num", c.getString(1)).apply();
                }
                c.close();
            }
        } catch (Exception ignored) {
        }
        view.refreshList();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CALL && pendingNumber != null) {
            startCall(pendingNumber, ok);
            pendingNumber = null;
        } else if (requestCode == REQ_CONTACTS && ok) {
            loadContacts();
        }
    }
}
