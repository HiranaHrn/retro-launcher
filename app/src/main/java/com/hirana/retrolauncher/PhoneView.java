package com.hirana.retrolauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 3310 görünümünü çizen ve dokunmaları yöneten ekran. Tüm koordinatlar arka plan resmine göre (841x1870). */
public class PhoneView extends View {

    public interface Host {
        void launch(AppEntry app);
        void appInfo(AppEntry app);
        void call(String number);
        void tone(int key);
    }

    static final float IW = 841f, IH = 1870f;
    static final int K_STAR = 10, K_HASH = 11, K_C = 12, K_OK = 13, K_UP = 14, K_DOWN = 15;
    static final RectF[] KEYS = new RectF[16];
    static final RectF SCREEN = new RectF(27, 35, 809, 1221);

    static {
        KEYS[1] = r(61, 1435, 185, 76);
        KEYS[2] = r(327, 1458, 188, 72);
        KEYS[3] = r(594, 1433, 187, 77);
        KEYS[4] = r(61, 1527, 187, 76);
        KEYS[5] = r(326, 1546, 189, 71);
        KEYS[6] = r(593, 1525, 186, 77);
        KEYS[7] = r(62, 1619, 186, 77);
        KEYS[8] = r(326, 1635, 189, 72);
        KEYS[9] = r(593, 1616, 185, 78);
        KEYS[K_STAR] = r(71, 1713, 179, 78);
        KEYS[0] = r(326, 1724, 190, 75);
        KEYS[K_HASH] = r(592, 1711, 177, 78);
        KEYS[K_C] = r(44, 1279, 218, 141);
        KEYS[K_OK] = r(245, 1269, 349, 96);
        KEYS[K_UP] = r(578, 1280, 217, 70);
        KEYS[K_DOWN] = r(578, 1350, 217, 70);
    }

    static RectF r(float x, float y, float w, float h) {
        return new RectF(x, y, x + w, y + h);
    }

    // Ekran yerleşimi
    static final int COLS = 4, ROWS = 5, PER_PAGE = COLS * ROWS;
    static final float GX0 = 50, GX1 = 788, GY0 = 140, ROW_H = 186;
    static final float ICON = 96;

    static final int LCD = 0xFF84C43C;
    static final int INK = 0xFF0E140C;

    private final Host host;
    private final Bitmap bg;
    private final Map<String, Bitmap> icons = new HashMap<>();
    private final Bitmap[] sig = new Bitmap[5];
    private final Bitmap[] bat = new Bitmap[5];

    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint inkBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint lcdBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint press = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private List<AppEntry> apps = new ArrayList<>();
    private int page = 0, sel = 0;
    private boolean dialMode = false;
    private final StringBuilder number = new StringBuilder();
    private int sigLevel = 0, batLevel = 4;

    // Dokunma durumu
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int pressedKey = -1;
    private boolean longFired = false;
    private boolean screenDown = false, screenLongFired = false;
    private float downX, downY;

    public PhoneView(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        bg = load("bg");
        String[] keys = {"phone", "contacts", "messages", "calendar", "gallery", "camera", "music", "files",
                "settings", "playstore", "internet", "maps", "gmail", "clock", "calculator", "apps",
                "whatsapp", "youtube", "default"};
        for (String k : keys) icons.put(k, load("ic_" + k));
        for (int i = 0; i < 5; i++) {
            sig[i] = load("sig_" + i);
            bat[i] = load("bat_" + i);
        }
        inkBmp.setColorFilter(new PorterDuffColorFilter(INK, PorterDuff.Mode.SRC_IN));
        lcdBmp.setColorFilter(new PorterDuffColorFilter(LCD, PorterDuff.Mode.SRC_IN));
        text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        text.setColor(INK);
        press.setColor(0x55000000);
        setHapticFeedbackEnabled(true);
    }

    private Bitmap load(String name) {
        int id = getResources().getIdentifier(name, "drawable", getContext().getPackageName());
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        return BitmapFactory.decodeResource(getResources(), id, o);
    }

    // ---------- Dışarıdan çağrılanlar ----------

    public void setApps(List<AppEntry> list) {
        apps = list;
        if (sel >= apps.size()) sel = 0;
        page = apps.isEmpty() ? 0 : sel / PER_PAGE;
        invalidate();
    }

    public void setSignal(int level) {
        level = Math.max(0, Math.min(4, level));
        if (level != sigLevel) { sigLevel = level; invalidate(); }
    }

    public void setBattery(int level) {
        level = Math.max(0, Math.min(4, level));
        if (level != batLevel) { batLevel = level; invalidate(); }
    }

    public void reset() {
        dialMode = false;
        number.setLength(0);
        page = 0;
        sel = 0;
        invalidate();
    }

    public void clearDial() {
        dialMode = false;
        number.setLength(0);
        invalidate();
    }

    /** Geri tuşu: true dönerse olay işlendi. */
    public boolean back() {
        if (dialMode) { clearDial(); return true; }
        if (page != 0 || sel != 0) { reset(); return true; }
        return true;
    }

    // ---------- Çizim ----------

    @Override
    protected void onDraw(Canvas c) {
        float sx = getWidth() / IW, sy = getHeight() / IH;
        c.save();
        c.scale(sx, sy);
        tmp.set(0, 0, IW, IH);
        c.drawBitmap(bg, null, tmp, bmp);

        drawStatus(c);
        if (dialMode) drawDial(c);
        else drawGrid(c);
        drawSoftLabel(c);

        if (pressedKey >= 0) {
            RectF k = KEYS[pressedKey];
            if (pressedKey == K_UP || pressedKey == K_DOWN) {
                tmp.set(KEYS[K_UP].left + 8, KEYS[K_UP].top + 8, KEYS[K_DOWN].right - 8, KEYS[K_DOWN].bottom - 8);
            } else {
                tmp.set(k.left + 6, k.top + 6, k.right - 6, k.bottom - 6);
            }
            c.drawOval(tmp, press);
        }
        c.restore();
    }

    private void drawStatus(Canvas c) {
        Bitmap s = sig[sigLevel];
        float h = 54, w = h * s.getWidth() / s.getHeight();
        tmp.set(58, 62, 58 + w, 62 + h);
        c.drawBitmap(s, null, tmp, inkBmp);

        Bitmap b = bat[batLevel];
        h = 40;
        w = h * b.getWidth() / b.getHeight();
        tmp.set(780 - w, 70, 780, 70 + h);
        c.drawBitmap(b, null, tmp, inkBmp);
    }

    private void drawGrid(Canvas c) {
        int n = apps.size();
        if (n == 0) return;
        int pages = (n + PER_PAGE - 1) / PER_PAGE;
        if (page >= pages) page = pages - 1;
        float cw = (GX1 - GX0) / COLS;

        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= n) break;
            AppEntry a = apps.get(idx);
            int col = i % COLS, row = i / COLS;
            float left = GX0 + col * cw, top = GY0 + row * ROW_H;
            float cx = left + cw / 2;
            boolean selected = idx == sel;

            if (selected) {
                fill.setColor(INK);
                tmp.set(left + 4, top + 6, left + cw - 4, top + ROW_H - 6);
                c.drawRoundRect(tmp, 14, 14, fill);
            }
            Bitmap ic = icons.get(a.iconKey != null ? a.iconKey : "default");
            if (ic == null) ic = icons.get("default");
            tmp.set(cx - ICON / 2, top + 26, cx + ICON / 2, top + 26 + ICON);
            c.drawBitmap(ic, null, tmp, selected ? lcdBmp : inkBmp);

            text.setColor(selected ? LCD : INK);
            drawFitText(c, a.label, cx, top + 26 + ICON + 38, cw - 10, 31);
        }

        // Sayfa noktaları
        if (pages > 1) {
            float size = 22, gap = 40;
            float total = (pages - 1) * gap;
            float x0 = (SCREEN.left + SCREEN.right) / 2 - total / 2;
            float y = 1102;
            for (int p = 0; p < pages; p++) {
                float x = x0 + p * gap;
                tmp.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2);
                fill.setColor(INK);
                if (p == page) {
                    fill.setStyle(Paint.Style.FILL);
                } else {
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(4);
                }
                c.drawRoundRect(tmp, 6, 6, fill);
                fill.setStyle(Paint.Style.FILL);
            }
        }
    }

    private void drawFitText(Canvas c, String s, float cx, float y, float maxW, float size) {
        if (s == null) s = "";
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(size);
        float w = text.measureText(s);
        if (w > maxW) {
            text.setTextSize(Math.max(size * maxW / w, size * 0.72f));
            if (text.measureText(s) > maxW) {
                while (s.length() > 1 && text.measureText(s + "…") > maxW) s = s.substring(0, s.length() - 1);
                s = s + "…";
            }
        }
        c.drawText(s, cx, y, text);
    }

    private void drawDial(Canvas c) {
        text.setColor(INK);
        text.setTextAlign(Paint.Align.RIGHT);
        float size = number.length() > 12 ? 88 : 110;
        text.setTextSize(size);
        float maxW = 720;
        int perLine = Math.max(1, (int) (maxW / text.measureText("0")));
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < number.length(); i += perLine) {
            lines.add(number.substring(i, Math.min(number.length(), i + perLine)));
        }
        float lineH = size * 1.1f;
        float y = 1060;
        for (int i = lines.size() - 1; i >= 0 && y > 200; i--) {
            c.drawText(lines.get(i), 778, y, text);
            y -= lineH;
        }
    }

    private void drawSoftLabel(Canvas c) {
        String label = dialMode ? "Ara" : (apps.isEmpty() ? "" : "Seç");
        text.setColor(INK);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(40);
        c.drawText(label, (SCREEN.left + SCREEN.right) / 2, 1172, text);
    }

    // ---------- Tuş mantığı ----------

    private void onKey(int k) {
        int n = apps.size();
        if (k <= 9 || k == K_STAR || k == K_HASH) {
            typeChar(k <= 9 ? (char) ('0' + k) : (k == K_STAR ? '*' : '#'));
            host.tone(k);
        } else if (k == K_C) {
            if (dialMode) {
                if (number.length() > 0) number.setLength(number.length() - 1);
                if (number.length() == 0) dialMode = false;
            } else {
                page = 0;
                sel = 0;
            }
        } else if (k == K_OK) {
            if (dialMode) {
                if (number.length() > 0) host.call(number.toString());
            } else if (n > 0) {
                host.launch(apps.get(sel));
            }
        } else if (k == K_UP && !dialMode && n > 0) {
            sel = (sel - 1 + n) % n;
            page = sel / PER_PAGE;
        } else if (k == K_DOWN && !dialMode && n > 0) {
            sel = (sel + 1) % n;
            page = sel / PER_PAGE;
        }
        invalidate();
    }

    private void typeChar(char ch) {
        if (!dialMode) {
            dialMode = true;
            number.setLength(0);
        }
        if (number.length() < 40) number.append(ch);
    }

    private void onKeyLong(int k) {
        if (k == K_C) {
            clearDial();
        } else if (k == K_STAR || k == 0) {
            typeChar('+');
            host.tone(k);
            invalidate();
        } else {
            onKey(k);
        }
    }

    private final Runnable keyLong = new Runnable() {
        @Override
        public void run() {
            if (pressedKey >= 0) {
                longFired = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                onKeyLong(pressedKey);
            }
        }
    };

    private final Runnable screenLong = new Runnable() {
        @Override
        public void run() {
            if (!screenDown || dialMode) return;
            int idx = cellAt(downX, downY);
            if (idx >= 0) {
                screenLongFired = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                sel = idx;
                invalidate();
                host.appInfo(apps.get(idx));
            }
        }
    };

    private int hitKey(float x, float y) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].contains(x, y)) return i;
        for (int i = 0; i < KEYS.length; i++) {
            RectF k = KEYS[i];
            if (x >= k.left - 8 && x <= k.right + 8 && y >= k.top - 7 && y <= k.bottom + 7) return i;
        }
        return -1;
    }

    private int cellAt(float x, float y) {
        if (dialMode) return -1;
        float cw = (GX1 - GX0) / COLS;
        if (x < GX0 || x >= GX1 || y < GY0 || y >= GY0 + ROWS * ROW_H) return -1;
        int col = (int) ((x - GX0) / cw), row = (int) ((y - GY0) / ROW_H);
        int idx = page * PER_PAGE + row * COLS + col;
        return idx < apps.size() ? idx : -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX() * IW / Math.max(1, getWidth());
        float y = ev.getY() * IH / Math.max(1, getHeight());
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int k = hitKey(x, y);
                if (k >= 0) {
                    pressedKey = k;
                    longFired = false;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    handler.postDelayed(keyLong, 550);
                    invalidate();
                } else if (SCREEN.contains(x, y)) {
                    screenDown = true;
                    screenLongFired = false;
                    downX = x;
                    downY = y;
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
                if (screenDown && (Math.abs(x - downX) > 25 || Math.abs(y - downY) > 25)) {
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
                    if (!screenLongFired && !dialMode) {
                        float dx = x - downX, dy = y - downY;
                        int n = apps.size();
                        int pages = (n + PER_PAGE - 1) / PER_PAGE;
                        if (Math.abs(dx) > 110 && Math.abs(dx) > Math.abs(dy) && pages > 1) {
                            page = dx < 0 ? (page + 1) % pages : (page - 1 + pages) % pages;
                            sel = page * PER_PAGE;
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            invalidate();
                        } else if (Math.abs(dx) < 30 && Math.abs(dy) < 30) {
                            int idx = cellAt(x, y);
                            if (idx >= 0) {
                                sel = idx;
                                invalidate();
                                host.launch(apps.get(idx));
                            }
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
