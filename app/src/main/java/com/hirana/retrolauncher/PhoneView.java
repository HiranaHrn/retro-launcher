package com.hirana.retrolauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Telefon gövdesini, yeşil ekranı ve tuş takımını çizer; tüm tuş ve ekran mantığı burada. */
public class PhoneView extends View {

    // ================= Dış bağlantılar =================

    public interface Host {
        void launch(AppEntry app);
        void appInfo(AppEntry app);
        void call(String number);
        void openNotifAccess();
        void openHomeSettings();
        void pickContact(int slot);
        void requestContacts();
        List<Contact> contacts();
    }

    public static class Contact {
        public final String name, number, t9, digits;

        public Contact(String name, String number) {
            this.name = name;
            this.number = number;
            this.t9 = toT9(name);
            this.digits = number.replaceAll("[^0-9]", "");
        }
    }

    public static class Status {
        public int signal, battery = 100, wifi = -1, notif, missed, sms;
        public boolean charging, bt, data, airplane, mute, vibrate, alarm, headphones, location, notifAccess;
        public String operator = "";
        public float lux = -1;
    }

    static String toT9(String s) {
        String l = s.toLowerCase(new Locale("tr"));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            char d;
            if ("abcç".indexOf(c) >= 0) d = '2';
            else if ("def".indexOf(c) >= 0) d = '3';
            else if ("ghıiğ".indexOf(c) >= 0) d = '4';
            else if ("jkl".indexOf(c) >= 0) d = '5';
            else if ("mnoö".indexOf(c) >= 0) d = '6';
            else if ("pqrsş".indexOf(c) >= 0) d = '7';
            else if ("tuvü".indexOf(c) >= 0) d = '8';
            else if ("wxyz".indexOf(c) >= 0) d = '9';
            else if (c >= '0' && c <= '9') d = c;
            else d = ' ';
            b.append(d);
        }
        return b.toString();
    }

    // ================= Sabitler =================

    static final float IW = 819f, IH = 1920f;
    static final float S1 = 150, S2 = 900, S3 = 1045; // gövde dilimleri
    static final float SCR_L = 40, SCR_R = 777, SCR_T = 55, SCR_B = 1019;
    static final float CW = SCR_R - SCR_L;

    static final int K_STAR = 10, K_HASH = 11, K_C = 12, K_OK = 13, K_UP = 14, K_DOWN = 15;
    static final RectF[] KEYS = new RectF[16];

    static {
        KEYS[K_OK] = r(298, 1065, 222, 224);
        KEYS[K_UP] = r(558, 1072, 228, 97);
        KEYS[K_DOWN] = r(558, 1188, 228, 98);
        KEYS[K_C] = r(32, 1076, 230, 204);
        KEYS[1] = r(47, 1330, 223, 101);
        KEYS[2] = r(297, 1330, 224, 101);
        KEYS[3] = r(548, 1330, 223, 102);
        KEYS[4] = r(47, 1466, 223, 101);
        KEYS[5] = r(296, 1466, 225, 101);
        KEYS[6] = r(548, 1466, 224, 102);
        KEYS[7] = r(47, 1601, 223, 103);
        KEYS[8] = r(297, 1602, 224, 102);
        KEYS[9] = r(548, 1602, 224, 103);
        KEYS[K_STAR] = r(48, 1738, 222, 103);
        KEYS[0] = r(297, 1739, 225, 103);
        KEYS[K_HASH] = r(549, 1739, 223, 103);
    }

    static RectF r(float x, float y, float w, float h) {
        return new RectF(x, y, x + w, y + h);
    }

    static final int M_IDLE = 0, M_MENU = 1, M_DIAL = 2, M_LIST = 3;
    static final int COLS = 4;
    static final int INK = 0xFF121A0E;
    static final int LCD = 0xFFAECB4E;

    // Ayarlar
    static class Opt {
        final String key, title, icon;
        final String[] labels;
        final int def;

        Opt(String key, String title, String icon, int def, String... labels) {
            this.key = key;
            this.title = title;
            this.icon = icon;
            this.def = def;
            this.labels = labels;
        }
    }

    static final Opt O_LIGHT = new Opt("light", "Ekran ışığı", "set_light", 1, "Parlak", "Normal", "Kısık", "Karanlık");
    static final Opt O_COLOR = new Opt("color", "Ekran rengi", "set_color", 0, "Yeşil", "Mavi", "Turuncu", "Gri");
    static final Opt O_ANIM = new Opt("anim", "Animasyon", "set_anim", 0, "Çay", "Kuş", "Kedi", "Gün batımı", "Kapalı");
    static final Opt O_SOUND = new Opt("sound", "Tuş sesi", "set_sound", 0, "Açık", "Kapalı");
    static final Opt O_VIB = new Opt("vib", "Titreşim", "set_vibrate", 0, "Açık", "Kapalı");
    static final Opt O_KEYLIGHT = new Opt("keylight", "Tuş ışığı", "set_keylight", 2, "Kapalı", "Hep açık", "Otomatik", "Akşam 19-07");
    static final Opt O_KEYPOW = new Opt("keypow", "Işık gücü", "set_keylight", 1, "Az", "Orta", "Çok");
    static final Opt O_CLOCK = new Opt("clock", "Saat", "set_clock", 0, "24 saat", "12 saat");
    static final Opt[] OPTS = {O_LIGHT, O_COLOR, O_ANIM, O_SOUND, O_VIB, O_KEYLIGHT, O_KEYPOW, O_CLOCK};
    static final String[] ANIMS = {"tea", "bird", "cat", "sun"};

    // Liste ekranı
    interface Builder {
        void build(ListScreen ls);
    }

    static class Row {
        Bitmap icon;
        String label, value;
        boolean radio, checked;
        Runnable action;
    }

    static class ListScreen {
        String title = "";
        final List<Row> rows = new ArrayList<>();
        int sel = 0, top = 0;
        Builder builder;
    }

    // ================= Durum =================

    private final Host host;
    private final SharedPreferences sp;
    private final Bitmap body, bodyLit, batVertical;
    private final Map<String, Bitmap> bmps = new HashMap<>();
    private final Bitmap[] sig = new Bitmap[5], bat = new Bitmap[5];
    private final Bitmap[][] anims = new Bitmap[4][4];

    private final Paint bmpP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint litP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint themeP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint inkBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint lcdBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint press = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF(), tmp = new RectF(), scr = new RectF();
    private final Path scrPath = new Path();

    private float s = 1, topH, midH, botH, keyH, CH = 960;

    private List<AppEntry> apps = new ArrayList<>();
    private int mode = M_IDLE, prevMode = M_IDLE;
    private int page = 0, sel = 0;
    private final StringBuilder number = new StringBuilder();
    private final List<Contact> matches = new ArrayList<>();
    private int dialSel = -1;
    private final ArrayDeque<ListScreen> stack = new ArrayDeque<>();
    private Status st = new Status();

    private boolean locked = false;
    private long menuOpenedAt = 0, unlockArm = 0;
    private String msg = null;
    private long msgUntil = 0;
    private int frame = 0;
    private float lightAlpha = 0;
    private int themeApplied = -1;
    private boolean active = false;

    private ToneGenerator toneGen;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int pressedKey = -1;
    private boolean longFired = false;
    private boolean screenDown = false, screenLongFired = false;
    private float downX, downY;

    public PhoneView(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        sp = ctx.getSharedPreferences("retro", Context.MODE_PRIVATE);
        body = load("body");
        bodyLit = load("body_lit");
        String[] names = {"phone", "contacts", "messages", "calendar", "gallery", "camera", "music", "files",
                "settings", "playstore", "internet", "maps", "gmail", "clock", "calculator", "apps", "whatsapp",
                "youtube", "default", "instagram", "facebook", "messenger", "telegram", "tiktok", "x", "snapchat",
                "spotify", "netflix", "chrome", "google", "drive", "photos", "zoom", "trendyol", "hepsiburada",
                "getir", "yemeksepeti", "edevlet", "bank", "weather", "notes", "radio", "shazam"};
        for (String n : names) bmps.put("ic_" + n, load("ic_" + n));
        String[] stNames = {"wifi_1", "wifi_2", "wifi_3", "bluetooth", "data", "mail", "missed", "alarm", "mute",
                "vibrate", "headphones", "lock", "airplane", "location", "charge"};
        for (String n : stNames) bmps.put("st_" + n, load("st_" + n));
        String[] setNames = {"light", "color", "anim", "sound", "vibrate", "keylight", "clock", "game", "ringtone", "info"};
        for (String n : setNames) bmps.put("set_" + n, load("set_" + n));
        for (int i = 0; i < 5; i++) {
            sig[i] = load("sig_" + i);
            bat[i] = load("bat_" + i);
        }
        for (int a = 0; a < 4; a++) for (int f = 0; f < 4; f++) anims[a][f] = load("anim_" + ANIMS[a] + "_" + f);
        Matrix m = new Matrix();
        m.postRotate(-90);
        batVertical = Bitmap.createBitmap(bat[0], 0, 0, bat[0].getWidth(), bat[0].getHeight(), m, true);

        inkBmp.setColorFilter(new PorterDuffColorFilter(INK, PorterDuff.Mode.SRC_IN));
        lcdBmp.setColorFilter(new PorterDuffColorFilter(LCD, PorterDuff.Mode.SRC_IN));
        text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        text.setColor(INK);
        press.setColor(0x48000000);
    }

    private Bitmap load(String name) {
        int id = getResources().getIdentifier(name, "drawable", getContext().getPackageName());
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        Bitmap b = id == 0 ? null : BitmapFactory.decodeResource(getResources(), id, o);
        if (b == null) b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        return b;
    }

    private int opt(Opt o) {
        int v = sp.getInt(o.key, o.def);
        return (v < 0 || v >= o.labels.length) ? o.def : v;
    }

    // ================= Dışarıdan çağrılanlar =================

    public void setApps(List<AppEntry> list) {
        apps = list;
        if (sel >= apps.size()) sel = 0;
        invalidate();
    }

    public void setStatus(Status s) {
        st = s;
        if (mode == M_LIST) rebuildTop();
        invalidate();
    }

    public void setActive(boolean on) {
        active = on;
        handler.removeCallbacks(tick);
        if (on) handler.post(tick);
    }

    public void goHome() {
        if (mode == M_DIAL) clearDial();
        stack.clear();
        mode = M_IDLE;
        page = 0;
        sel = 0;
        invalidate();
    }

    public void back() {
        if (locked) return;
        if (mode == M_DIAL) exitDial();
        else if (mode == M_LIST) popList();
        else if (mode == M_MENU) mode = M_IDLE;
        invalidate();
    }

    public void clearDial() {
        number.setLength(0);
        matches.clear();
        dialSel = -1;
        if (mode == M_DIAL) mode = prevMode == M_DIAL ? M_IDLE : prevMode;
        invalidate();
    }

    public void refreshList() {
        if (mode == M_LIST) rebuildTop();
        invalidate();
    }

    public void showMsg(String m) {
        msg = m;
        msgUntil = SystemClock.uptimeMillis() + 2200;
        invalidate();
        handler.postDelayed(this::invalidate, 2300);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            frame = (frame + 1) % 4;
            if (mode == M_IDLE) invalidate();
            if (active) handler.postDelayed(this, 550);
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacksAndMessages(null);
        if (toneGen != null) {
            toneGen.release();
            toneGen = null;
        }
    }

    // ================= Yerleşim =================

    private void layoutBody() {
        int W = getWidth(), H = getHeight();
        s = W / IW;
        topH = S1 * s;
        botH = (S3 - S2) * s;
        keyH = (IH - S3) * s;
        midH = Math.max(120 * s, H - topH - botH - keyH);
        scr.set(SCR_L * s, SCR_T * s, SCR_R * s, devY(SCR_B));
        CH = scr.height() / s;
        scrPath.reset();
        scrPath.addRoundRect(scr, 24 * s, 24 * s, Path.Direction.CW);
    }

    private float devY(float y) {
        if (y <= S1) return y * s;
        if (y <= S2) return topH + (y - S1) * midH / (S2 - S1);
        if (y <= S3) return topH + midH + (y - S2) * s;
        return getHeight() - (IH - y) * s;
    }

    private void drawSlices(Canvas c, Bitmap b, Paint p, boolean keypadOnly) {
        int W = getWidth(), H = getHeight();
        if (!keypadOnly) {
            src.set(0, 0, (int) IW, (int) S1);
            dst.set(0, 0, W, topH);
            c.drawBitmap(b, src, dst, p);
            src.set(0, (int) S1, (int) IW, (int) S2);
            dst.set(0, topH, W, topH + midH);
            c.drawBitmap(b, src, dst, p);
            src.set(0, (int) S2, (int) IW, (int) S3);
            dst.set(0, topH + midH, W, topH + midH + botH);
            c.drawBitmap(b, src, dst, p);
        }
        src.set(0, (int) S3, (int) IW, (int) IH);
        dst.set(0, H - keyH, W, H);
        c.drawBitmap(b, src, dst, p);
    }

    private void applyTheme() {
        int t = opt(O_COLOR);
        if (t == themeApplied) return;
        themeApplied = t;
        float[][] tint = {{1, 1, 1}, {0.62f, 0.95f, 1.30f}, {1.32f, 0.93f, 0.36f}, {1.02f, 1.04f, 0.98f}};
        float[] k = tint[t];
        ColorMatrix cm = new ColorMatrix(new float[]{
                0.3f * k[0], 0.59f * k[0], 0.11f * k[0], 0, 0,
                0.3f * k[1], 0.59f * k[1], 0.11f * k[1], 0, 0,
                0.3f * k[2], 0.59f * k[2], 0.11f * k[2], 0, 0,
                0, 0, 0, 1, 0});
        themeP.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    // ================= Çizim =================

    @Override
    protected void onDraw(Canvas c) {
        layoutBody();
        applyTheme();
        drawSlices(c, body, bmpP, false);

        if (opt(O_COLOR) != 0) {
            c.save();
            c.clipPath(scrPath);
            drawSlices(c, body, themeP, false);
            c.restore();
        }

        // Tuş ışığı
        float target = keyLightTarget();
        if (Math.abs(target - lightAlpha) > 0.01f) {
            lightAlpha += Math.signum(target - lightAlpha) * 0.06f;
            lightAlpha = Math.max(0, Math.min(1, lightAlpha));
            postInvalidateOnAnimation();
        } else {
            lightAlpha = target;
        }
        if (lightAlpha > 0.01f) {
            float[] pow = {0.3f, 0.5f, 0.8f};
            litP.setAlpha((int) (255 * lightAlpha * pow[opt(O_KEYPOW)]));
            drawSlices(c, bodyLit, litP, true);
        }

        // Ekran içeriği
        c.save();
        c.translate(scr.left, scr.top);
        c.scale(s, s);
        c.clipRect(0, 0, CW, CH);
        switch (mode) {
            case M_IDLE:
                drawIdle(c);
                break;
            case M_MENU:
                drawMenu(c);
                break;
            case M_DIAL:
                drawDial(c);
                break;
            case M_LIST:
                drawList(c);
                break;
        }
        drawMsg(c);
        c.restore();

        // Ekran ışığı (kısma)
        int[] dim = {0, 18, 60, 110};
        int d = dim[opt(O_LIGHT)];
        if (d > 0) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(d << 24);
            c.drawPath(scrPath, fill);
        }

        // Basılı tuş
        if (pressedKey >= 0) {
            RectF k = KEYS[pressedKey];
            tmp.set(k.left * s + 4, devY(k.top) + 4, k.right * s - 4, devY(k.bottom) - 4);
            if (pressedKey == K_OK) c.drawOval(tmp, press);
            else c.drawRoundRect(tmp, 26 * s, 26 * s, press);
        }
    }

    private float keyLightTarget() {
        int m = opt(O_KEYLIGHT);
        if (m == 1) return 1;
        if (m == 2) return (st.lux >= 0 && st.lux < 15) ? 1 : 0;
        if (m == 3) {
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            return (h >= 19 || h < 7) ? 1 : 0;
        }
        return 0;
    }

    private void drawIcon(Canvas c, Bitmap b, float x, float y, float h, boolean inverted) {
        if (b == null) return;
        float w = h * b.getWidth() / b.getHeight();
        tmp.set(x, y, x + w, y + h);
        c.drawBitmap(b, null, tmp, inverted ? lcdBmp : inkBmp);
    }

    private float iconW(Bitmap b, float h) {
        return b == null ? 0 : h * b.getWidth() / b.getHeight();
    }

    private void textAt(Canvas c, String t, float x, float y, float size, Paint.Align a, int color) {
        text.setTextSize(size);
        text.setTextAlign(a);
        text.setColor(color);
        c.drawText(t, x, y, text);
    }

    private String fit(String t, float maxW) {
        if (t == null) return "";
        if (text.measureText(t) <= maxW) return t;
        while (t.length() > 1 && text.measureText(t + "…") > maxW) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    private int batIdx() {
        int p = st.battery;
        return p < 10 ? 0 : p < 35 ? 1 : p < 60 ? 2 : p < 85 ? 3 : 4;
    }

    /** Durum simgeleri satırı; sığdığı kadarını çizer. */
    private void drawStatusIcons(Canvas c, float x, float xEnd, float y, float h) {
        List<Bitmap> list = new ArrayList<>();
        if (st.airplane) list.add(bmps.get("st_airplane"));
        if (st.wifi >= 0) list.add(bmps.get("st_wifi_" + Math.min(3, st.wifi + 1)));
        else if (st.data) list.add(bmps.get("st_data"));
        if (st.bt) list.add(bmps.get("st_bluetooth"));
        if (st.mute) list.add(bmps.get("st_mute"));
        else if (st.vibrate) list.add(bmps.get("st_vibrate"));
        if (st.alarm) list.add(bmps.get("st_alarm"));
        if (st.headphones) list.add(bmps.get("st_headphones"));
        if (st.location) list.add(bmps.get("st_location"));
        if (st.charging) list.add(bmps.get("st_charge"));

        if (st.notif > 0) {
            Bitmap mail = bmps.get("st_mail");
            float w = iconW(mail, h);
            String n = String.valueOf(st.notif);
            text.setTextSize(h * 1.05f);
            float tw = text.measureText(n);
            if (x + w + 6 + tw <= xEnd) {
                drawIcon(c, mail, x, y, h, false);
                textAt(c, n, x + w + 6, y + h * 0.92f, h * 1.05f, Paint.Align.LEFT, INK);
                x += w + 6 + tw + 14;
            }
        }
        for (Bitmap b : list) {
            float w = iconW(b, h);
            if (x + w > xEnd) break;
            drawIcon(c, b, x, y, h, false);
            x += w + 12;
        }
    }

    private void drawTopStatus(Canvas c) {
        Bitmap sg = sig[Math.max(0, Math.min(4, st.signal))];
        drawIcon(c, sg, 16, 12, 42, false);
        float sw = iconW(sg, 42);
        Bitmap bt = bat[batIdx()];
        float bw = iconW(bt, 30);
        drawIcon(c, bt, CW - 16 - bw, 18, 30, false);
        drawStatusIcons(c, 16 + sw + 18, CW - 16 - bw - 14, 18, 30);
    }

    private void drawSoft(Canvas c, String label) {
        textAt(c, label, CW / 2, CH - 26, 46, Paint.Align.CENTER, INK);
    }

    private void drawIdle(Canvas c) {
        boolean h12 = opt(O_CLOCK) == 1;
        String time = new SimpleDateFormat(h12 ? "h:mm" : "HH:mm", Locale.getDefault()).format(new Date());
        textAt(c, time, CW - 16, 64, 62, Paint.Align.RIGHT, INK);
        text.setTextSize(32);
        textAt(c, fit(st.operator, CW - 250), 18, 50, 32, Paint.Align.LEFT, INK);
        drawStatusIcons(c, 18, CW - 18, 86, 30);

        // Dikey sinyal ve pil çubukları
        float barsBottom = CH * 0.62f;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(INK);
        for (int i = 0; i < 4; i++) {
            float w = 12 + i * 6, y = barsBottom - i * 46;
            if (i < st.signal) c.drawRect(14, y - 32, 14 + w, y, fill);
            if (i < batIdx()) c.drawRect(CW - 14 - w, y - 32, CW - 14, y, fill);
        }
        drawIcon(c, sig[0], 12, barsBottom + 12, 46, false);
        float bvw = iconW(batVertical, 46);
        drawIcon(c, batVertical, CW - 12 - bvw, barsBottom + 12, 46, false);

        // Animasyon
        int a = opt(O_ANIM);
        if (a < 4) {
            Bitmap f = anims[a][frame];
            float maxH = CH * 0.36f, w = 250, h = w * f.getHeight() / f.getWidth();
            if (h > maxH) {
                h = maxH;
                w = h * f.getWidth() / f.getHeight();
            }
            float top = CH * 0.16f + (CH * 0.36f - h) / 2;
            tmp.set(CW / 2 - w / 2, top, CW / 2 + w / 2, top + h);
            c.drawBitmap(f, null, tmp, inkBmp);
        }

        // Bilgi satırı
        float y = CH * 0.72f;
        if (locked) {
            Bitmap lk = bmps.get("st_lock");
            text.setTextSize(36);
            String t = "Tuşlar kilitli";
            float tw = text.measureText(t), lw = iconW(lk, 36);
            float x0 = CW / 2 - (tw + lw + 12) / 2;
            drawIcon(c, lk, x0, y - 32, 36, false);
            textAt(c, t, x0 + lw + 12, y, 36, Paint.Align.LEFT, INK);
        } else {
            String info;
            if (st.missed > 0) info = st.missed + " cevapsız arama";
            else if (st.sms > 0) info = st.sms + " yeni mesaj";
            else info = new SimpleDateFormat("EEEE d MMMM", new Locale("tr")).format(new Date());
            textAt(c, info, CW / 2, y, 36, Paint.Align.CENTER, INK);
        }
        drawSoft(c, locked ? "Kilidi aç" : "Menü");
    }

    private int gridRows() {
        return Math.max(2, (int) ((CH - 80 - 130) / 185));
    }

    private int perPage() {
        return gridRows() * COLS;
    }

    private Bitmap appIcon(AppEntry a) {
        Bitmap b = a.iconKey != null ? bmps.get("ic_" + a.iconKey) : a.autoIcon;
        return b != null ? b : bmps.get("ic_default");
    }

    private void drawMenu(Canvas c) {
        drawTopStatus(c);
        int n = apps.size();
        if (n == 0) {
            drawSoft(c, "");
            return;
        }
        int per = perPage(), rows = gridRows();
        int pages = (n + per - 1) / per;
        if (page >= pages) page = pages - 1;
        float cw = CW / COLS, gy = 78, rh = (CH - 78 - 130) / rows;
        float icon = Math.min(92, rh - 70);
        for (int i = 0; i < per; i++) {
            int idx = page * per + i;
            if (idx >= n) break;
            AppEntry a = apps.get(idx);
            float left = (i % COLS) * cw, top = gy + (i / COLS) * rh, cx = left + cw / 2;
            boolean selected = idx == sel;
            if (selected) {
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(INK);
                tmp.set(left + 4, top + 4, left + cw - 4, top + rh - 4);
                c.drawRoundRect(tmp, 14, 14, fill);
            }
            float iy = top + (rh - icon - 44) / 2;
            tmp.set(cx - icon / 2, iy, cx + icon / 2, iy + icon);
            c.drawBitmap(appIcon(a), null, tmp, selected ? lcdBmp : inkBmp);
            text.setTextSize(30);
            float ts = 30;
            float tw = text.measureText(a.label);
            if (tw > cw - 10) ts = Math.max(22, 30 * (cw - 10) / tw);
            text.setTextSize(ts);
            textAt(c, fit(a.label, cw - 10), cx, iy + icon + 38, ts, Paint.Align.CENTER, selected ? LCD : INK);
        }
        if (pages > 1) {
            float size = 20, gap = 36, total = (pages - 1) * gap;
            float x0 = CW / 2 - total / 2, y = CH - 102;
            fill.setColor(INK);
            for (int p = 0; p < pages; p++) {
                float x = x0 + p * gap;
                tmp.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2);
                if (p == page) {
                    fill.setStyle(Paint.Style.FILL);
                } else {
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(4);
                }
                c.drawRoundRect(tmp, 5, 5, fill);
            }
            fill.setStyle(Paint.Style.FILL);
        }
        drawSoft(c, "Seç");
    }

    private void drawDial(Canvas c) {
        drawTopStatus(c);
        // T9 eşleşmeleri
        float y = 80;
        for (int i = 0; i < matches.size(); i++) {
            Contact ct = matches.get(i);
            boolean selected = i == dialSel;
            if (selected) {
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(INK);
                tmp.set(6, y, CW - 6, y + 84);
                c.drawRoundRect(tmp, 12, 12, fill);
            }
            int col = selected ? LCD : INK;
            text.setTextSize(36);
            textAt(c, fit(ct.name, CW - 30), 18, y + 38, 36, Paint.Align.LEFT, col);
            textAt(c, ct.number, 18, y + 74, 28, Paint.Align.LEFT, col);
            y += 92;
        }
        // Numara
        float size = number.length() > 11 ? 80 : 104;
        text.setTextSize(size);
        float maxW = CW - 40;
        int perLine = Math.max(1, (int) (maxW / text.measureText("0")));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < number.length(); i += perLine) {
            lines.add(number.substring(i, Math.min(number.length(), i + perLine)));
        }
        float ly = CH - 120;
        for (int i = lines.size() - 1; i >= 0 && ly > y + size * 0.8f; i--) {
            textAt(c, lines.get(i), CW - 20, ly, size, Paint.Align.RIGHT, INK);
            ly -= size * 1.08f;
        }
        drawSoft(c, "Ara");
    }

    private void drawList(Canvas c) {
        ListScreen ls = stack.peek();
        if (ls == null) return;
        text.setTextSize(38);
        textAt(c, fit(ls.title, CW - 40), CW / 2, 52, 38, Paint.Align.CENTER, INK);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(INK);
        c.drawRect(16, 66, CW - 16, 70, fill);

        float rowH = 90, y0 = 80;
        int visible = Math.max(1, (int) ((CH - y0 - 90) / rowH));
        if (ls.sel < ls.top) ls.top = ls.sel;
        if (ls.sel >= ls.top + visible) ls.top = ls.sel - visible + 1;
        for (int i = ls.top; i < ls.rows.size() && i < ls.top + visible; i++) {
            Row r = ls.rows.get(i);
            float y = y0 + (i - ls.top) * rowH;
            boolean selected = i == ls.sel;
            int col = selected ? LCD : INK;
            if (selected) {
                fill.setColor(INK);
                tmp.set(6, y + 2, CW - 6, y + rowH - 4);
                c.drawRoundRect(tmp, 12, 12, fill);
            }
            float x = 22;
            if (r.icon != null) {
                drawIcon(c, r.icon, 16, y + 14, 58, selected);
                x = 96;
            }
            float right = CW - 20;
            if (r.radio) {
                fill.setColor(col);
                tmp.set(right - 30, y + 28, right, y + 58);
                if (r.checked) {
                    fill.setStyle(Paint.Style.FILL);
                } else {
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(4);
                }
                c.drawRect(tmp, fill);
                fill.setStyle(Paint.Style.FILL);
                right -= 44;
            }
            float valW = 0;
            if (r.value != null) {
                text.setTextSize(28);
                valW = text.measureText(r.value) + 14;
                textAt(c, r.value, right, y + 56, 28, Paint.Align.RIGHT, col);
            }
            text.setTextSize(34);
            textAt(c, fit(r.label, right - valW - x), x, y + 57, 34, Paint.Align.LEFT, col);
        }
        // Kaydırma göstergesi
        if (ls.rows.size() > visible) {
            float trackH = visible * rowH, th = trackH * visible / ls.rows.size();
            float ty = y0 + trackH * ls.top / ls.rows.size();
            fill.setColor(INK);
            c.drawRect(CW - 5, ty, CW - 1, ty + th, fill);
        }
        drawSoft(c, "Seç");
    }

    private void drawMsg(Canvas c) {
        if (msg == null || SystemClock.uptimeMillis() > msgUntil) return;
        text.setTextSize(36);
        float maxW = CW - 100;
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : msg.split(" ")) {
            String t = line.length() == 0 ? w : line + " " + w;
            if (text.measureText(t) > maxW && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(w);
            } else {
                line = new StringBuilder(t);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        float h = lines.size() * 46 + 40, top = CH / 2 - h / 2;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(INK);
        tmp.set(30, top, CW - 30, top + h);
        c.drawRoundRect(tmp, 16, 16, fill);
        for (int i = 0; i < lines.size(); i++) {
            textAt(c, lines.get(i), CW / 2, top + 54 + i * 46, 36, Paint.Align.CENTER, LCD);
        }
    }

    // ================= Ses ve titreşim =================

    private void feedback(int k, boolean longPress) {
        if (opt(O_VIB) == 0) {
            performHapticFeedback(longPress ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
        if (opt(O_SOUND) != 0 || longPress) return;
        try {
            if (toneGen == null) toneGen = new ToneGenerator(AudioManager.STREAM_DTMF, 60);
            if (k <= 11) toneGen.startTone(k, 110); // DTMF 0-9, *, #
            else toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 60);
        } catch (Exception ignored) {
        }
    }

    // ================= Tuş mantığı =================

    private void onKey(int k) {
        long now = SystemClock.uptimeMillis();
        if (locked) {
            if (k == K_OK) {
                unlockArm = now;
                showMsg("Şimdi * tuşuna basın");
            } else if (k == K_STAR && now - unlockArm < 2000) {
                locked = false;
                unlockArm = 0;
                showMsg("Tuş kilidi açıldı");
            } else {
                showMsg("Kilidi açmak için ● ve * tuşlarına basın");
            }
            return;
        }
        if (mode == M_MENU && k == K_STAR && menuOpenedAt > 0 && now - menuOpenedAt < 2000) {
            locked = true;
            menuOpenedAt = 0;
            mode = M_IDLE;
            showMsg("Tuşlar kilitlendi");
            return;
        }
        boolean digit = k <= 9 || k == K_STAR || k == K_HASH;
        if (digit && mode != M_LIST) {
            typeChar(k <= 9 ? (char) ('0' + k) : (k == K_STAR ? '*' : '#'));
            invalidate();
            return;
        }
        switch (mode) {
            case M_IDLE:
                if (k == K_OK || k == K_UP || k == K_DOWN) {
                    mode = M_MENU;
                    menuOpenedAt = k == K_OK ? now : 0;
                }
                break;
            case M_MENU: {
                int n = apps.size();
                menuOpenedAt = 0;
                if (k == K_OK && n > 0) openItem(apps.get(sel));
                else if (k == K_C) mode = M_IDLE;
                else if (k == K_UP && n > 0) {
                    sel = (sel - 1 + n) % n;
                    page = sel / perPage();
                } else if (k == K_DOWN && n > 0) {
                    sel = (sel + 1) % n;
                    page = sel / perPage();
                }
                break;
            }
            case M_DIAL:
                if (k == K_C) {
                    if (number.length() > 0) number.setLength(number.length() - 1);
                    if (number.length() == 0) exitDial();
                    else updateMatches();
                } else if (k == K_OK) {
                    if (dialSel >= 0 && dialSel < matches.size()) host.call(matches.get(dialSel).number);
                    else if (number.length() > 0) host.call(number.toString());
                } else if (k == K_UP && !matches.isEmpty()) {
                    dialSel = dialSel <= -1 ? matches.size() - 1 : dialSel - 1;
                } else if (k == K_DOWN && !matches.isEmpty()) {
                    dialSel = dialSel >= matches.size() - 1 ? -1 : dialSel + 1;
                }
                break;
            case M_LIST: {
                ListScreen ls = stack.peek();
                if (ls == null) {
                    mode = M_MENU;
                    break;
                }
                int n = ls.rows.size();
                if (k == K_C) popList();
                else if (k == K_UP && n > 0) ls.sel = (ls.sel - 1 + n) % n;
                else if (k == K_DOWN && n > 0) ls.sel = (ls.sel + 1) % n;
                else if (k == K_OK && n > 0) runRow(ls.rows.get(ls.sel));
                break;
            }
        }
        invalidate();
    }

    private void onKeyLong(int k) {
        if (locked) {
            onKey(k);
            return;
        }
        if (k == K_C) {
            if (mode == M_DIAL) exitDial();
            else {
                stack.clear();
                mode = M_IDLE;
            }
        } else if ((k == K_STAR || k == 0) && mode != M_LIST) {
            typeChar('+');
        } else if (k >= 2 && k <= 9 && mode != M_DIAL && mode != M_LIST) {
            String num = sp.getString("sd" + k + "num", null);
            if (num != null) host.call(num);
            else showMsg("Hızlı arama " + k + " boş. Ayarlar > Hızlı arama");
        } else {
            onKey(k);
        }
        invalidate();
    }

    private void typeChar(char ch) {
        if (mode != M_DIAL) {
            prevMode = mode == M_LIST ? M_MENU : mode;
            if (mode == M_LIST) stack.clear();
            mode = M_DIAL;
            number.setLength(0);
            dialSel = -1;
            host.requestContacts();
        }
        if (number.length() < 40) number.append(ch);
        updateMatches();
    }

    private void exitDial() {
        number.setLength(0);
        matches.clear();
        dialSel = -1;
        mode = prevMode;
    }

    private void updateMatches() {
        matches.clear();
        String q = number.toString();
        if (q.length() >= 2 && q.matches("[0-9]+")) {
            List<Contact> all = host.contacts();
            if (all != null) {
                for (Contact ct : all) {
                    boolean hit = ct.digits.contains(q) || ct.t9.startsWith(q) || ct.t9.contains(" " + q);
                    if (hit) matches.add(ct);
                    if (matches.size() >= 3) break;
                }
            }
        }
        if (dialSel >= matches.size()) dialSel = -1;
    }

    private void openItem(AppEntry a) {
        if ("settings".equals(a.special)) {
            stack.clear();
            pushList(this::buildSettings);
            mode = M_LIST;
        } else {
            host.launch(a);
        }
    }

    // ================= Ayarlar listeleri =================

    private void pushList(Builder b) {
        ListScreen ls = new ListScreen();
        ls.builder = b;
        b.build(ls);
        stack.push(ls);
    }

    private void popList() {
        if (!stack.isEmpty()) stack.pop();
        if (stack.isEmpty()) mode = M_MENU;
        else rebuildTop();
    }

    private void rebuildTop() {
        ListScreen ls = stack.peek();
        if (ls == null || ls.builder == null) return;
        int keep = ls.sel;
        ls.rows.clear();
        ls.builder.build(ls);
        ls.sel = Math.max(0, Math.min(keep, ls.rows.size() - 1));
    }

    private void runRow(Row r) {
        if (r.action != null) r.action.run();
        invalidate();
    }

    private Row row(Bitmap icon, String label, String value, Runnable action) {
        Row r = new Row();
        r.icon = icon;
        r.label = label;
        r.value = value;
        r.action = action;
        return r;
    }

    private void buildSettings(ListScreen ls) {
        ls.title = "Ayarlar";
        for (Opt o : OPTS) {
            ls.rows.add(row(bmps.get(o.icon), o.title, o.labels[opt(o)], () -> pushList(l -> buildOptions(l, o))));
        }
        ls.rows.add(row(bmps.get("ic_phone"), "Hızlı arama", null, () -> pushList(this::buildSpeed)));
        ls.rows.add(row(bmps.get("st_mail"), "Bildirimler", st.notifAccess ? "Açık" : "Kapalı", host::openNotifAccess));
        ls.rows.add(row(bmps.get("ic_apps"), "Ana ekran seçimi", null, host::openHomeSettings));
        ls.rows.add(row(bmps.get("set_info"), "Hakkında", null, () -> showMsg("Retro Launcher " + version())));
    }

    private void buildOptions(ListScreen ls, Opt o) {
        ls.title = o.title;
        int cur = opt(o);
        for (int i = 0; i < o.labels.length; i++) {
            final int v = i;
            Row r = row(null, o.labels[i], null, () -> {
                sp.edit().putInt(o.key, v).apply();
                popList();
            });
            r.radio = true;
            r.checked = i == cur;
            ls.rows.add(r);
        }
        ls.sel = cur;
    }

    private void buildSpeed(ListScreen ls) {
        ls.title = "Hızlı arama";
        for (int d = 2; d <= 9; d++) {
            final int slot = d;
            String name = sp.getString("sd" + d + "name", null);
            if (name == null) {
                ls.rows.add(row(null, d + "  Boş", null, () -> host.pickContact(slot)));
            } else {
                ls.rows.add(row(null, d + "  " + name, null, () -> pushList(l -> {
                    l.title = slot + "  " + name;
                    l.rows.add(row(null, "Değiştir", null, () -> {
                        popList();
                        host.pickContact(slot);
                    }));
                    l.rows.add(row(null, "Sil", null, () -> {
                        sp.edit().remove("sd" + slot + "name").remove("sd" + slot + "num").apply();
                        popList();
                    }));
                })));
            }
        }
    }

    private String version() {
        try {
            return getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ================= Dokunma =================

    private int hitKey(float x, float y) {
        if (y < getHeight() - keyH) return -1;
        float ix = x / s, iy = IH - (getHeight() - y) / s;
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].contains(ix, iy)) return i;
        for (int i = 0; i < KEYS.length; i++) {
            RectF k = KEYS[i];
            if (ix >= k.left - 10 && ix <= k.right + 10 && iy >= k.top - 12 && iy <= k.bottom + 12) return i;
        }
        return -1;
    }

    /** Ekran içi koordinat (birim). */
    private float cx(float x) {
        return (x - scr.left) / s;
    }

    private float cy(float y) {
        return (y - scr.top) / s;
    }

    private int cellAt(float x, float y) {
        if (mode != M_MENU) return -1;
        int rows = gridRows(), per = perPage();
        float cw = CW / COLS, gy = 78, rh = (CH - 78 - 130) / rows;
        if (x < 0 || x >= CW || y < gy || y >= gy + rows * rh) return -1;
        int idx = page * per + (int) ((y - gy) / rh) * COLS + (int) (x / cw);
        return idx < apps.size() ? idx : -1;
    }

    private int listRowAt(float y) {
        ListScreen ls = stack.peek();
        if (ls == null || y < 80) return -1;
        int i = ls.top + (int) ((y - 80) / 90);
        return i < ls.rows.size() ? i : -1;
    }

    private final Runnable keyLong = new Runnable() {
        @Override
        public void run() {
            if (pressedKey >= 0) {
                longFired = true;
                feedback(pressedKey, true);
                onKeyLong(pressedKey);
            }
        }
    };

    private final Runnable screenLong = new Runnable() {
        @Override
        public void run() {
            if (!screenDown || locked || mode != M_MENU) return;
            int idx = cellAt(downX, downY);
            if (idx >= 0 && apps.get(idx).component != null) {
                screenLongFired = true;
                feedback(K_OK, true);
                sel = idx;
                invalidate();
                host.appInfo(apps.get(idx));
            }
        }
    };

    private void onScreenTap(float x, float y) {
        if (locked) {
            showMsg("Kilidi açmak için ● ve * tuşlarına basın");
            return;
        }
        switch (mode) {
            case M_IDLE:
                mode = M_MENU;
                menuOpenedAt = 0;
                break;
            case M_MENU: {
                int idx = cellAt(x, y);
                if (idx >= 0) {
                    sel = idx;
                    invalidate();
                    openItem(apps.get(idx));
                }
                break;
            }
            case M_DIAL: {
                if (y >= 80) {
                    int i = (int) ((y - 80) / 92);
                    if (i < matches.size()) {
                        dialSel = i;
                        host.call(matches.get(i).number);
                    }
                }
                break;
            }
            case M_LIST: {
                ListScreen ls = stack.peek();
                int i = listRowAt(y);
                if (ls != null && i >= 0) {
                    ls.sel = i;
                    runRow(ls.rows.get(i));
                }
                break;
            }
        }
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int k = hitKey(x, y);
                if (k >= 0) {
                    pressedKey = k;
                    longFired = false;
                    feedback(k, false);
                    handler.postDelayed(keyLong, 550);
                    invalidate();
                } else if (scr.contains(x, y)) {
                    screenDown = true;
                    screenLongFired = false;
                    downX = cx(x);
                    downY = cy(y);
                    handler.postDelayed(screenLong, 600);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pressedKey >= 0 && hitKey(x, y) != pressedKey) {
                    handler.removeCallbacks(keyLong);
                    pressedKey = -1;
                    invalidate();
                }
                if (screenDown && (Math.abs(cx(x) - downX) > 25 || Math.abs(cy(y) - downY) > 25)) {
                    handler.removeCallbacks(screenLong);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (pressedKey >= 0) {
                    handler.removeCallbacks(keyLong);
                    int k = pressedKey;
                    pressedKey = -1;
                    if (!longFired) onKey(k);
                    invalidate();
                } else if (screenDown) {
                    handler.removeCallbacks(screenLong);
                    screenDown = false;
                    if (!screenLongFired) {
                        float dx = cx(x) - downX, dy = cy(y) - downY;
                        if (Math.abs(dx) > 110 && Math.abs(dx) > Math.abs(dy) && mode == M_MENU && !locked) {
                            int per = perPage();
                            int pages = (apps.size() + per - 1) / per;
                            if (pages > 1) {
                                page = dx < 0 ? (page + 1) % pages : (page - 1 + pages) % pages;
                                sel = page * per;
                                feedback(K_UP, false);
                                invalidate();
                            }
                        } else if (Math.abs(dy) > 110 && Math.abs(dy) > Math.abs(dx) && mode == M_LIST) {
                            ListScreen ls = stack.peek();
                            if (ls != null && !ls.rows.isEmpty()) {
                                int step = dy < 0 ? 4 : -4;
                                ls.sel = Math.max(0, Math.min(ls.rows.size() - 1, ls.sel + step));
                                invalidate();
                            }
                        } else if (Math.abs(dx) < 30 && Math.abs(dy) < 30) {
                            onScreenTap(downX, downY);
                        }
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(keyLong);
                handler.removeCallbacks(screenLong);
                pressedKey = -1;
                screenDown = false;
                invalidate();
                return true;
            }
        }
        return true;
    }
}
