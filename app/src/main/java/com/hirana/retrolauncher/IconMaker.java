package com.hirana.retrolauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

/** Uygulamanın kendi renkli ikonunu siyah piksel ikona çevirir. */
public final class IconMaker {

    private IconMaker() {
    }

    public static Bitmap make(Drawable d) {
        try {
            final int G = 28, N = 40, OFF = 6;
            Bitmap src = Bitmap.createBitmap(G, G, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(src);
            d.setBounds(0, 0, G, G);
            d.draw(c);
            int[] px = new int[G * G];
            src.getPixels(px, 0, G, 0, 0, G, G);

            int[] hist = new int[256];
            int[] lum = new int[G * G];
            int opaque = 0;
            for (int i = 0; i < px.length; i++) {
                int a = px[i] >>> 24;
                if (a > 128) {
                    int r = (px[i] >> 16) & 0xFF, g = (px[i] >> 8) & 0xFF, b = px[i] & 0xFF;
                    int l = (r * 299 + g * 587 + b * 114) / 1000;
                    lum[i] = l;
                    hist[l]++;
                    opaque++;
                } else {
                    lum[i] = -1;
                }
            }
            boolean[] m = new boolean[N * N];
            if (opaque > 0) {
                int thr = otsu(hist, opaque);
                int dark = 0;
                for (int l : lum) if (l >= 0 && l < thr) dark++;
                boolean all = dark == 0 || dark == opaque;
                boolean glyphDark = dark * 2 < opaque;
                for (int y = 0; y < G; y++) {
                    for (int x = 0; x < G; x++) {
                        int l = lum[y * G + x];
                        if (l < 0) continue;
                        if (all || (l < thr) == glyphDark) m[(y + OFF) * N + x + OFF] = true;
                    }
                }
            }
            // Yuvarlak köşeli çerçeve
            for (int i = 3; i < N - 3; i++) {
                for (int t = 0; t < 2; t++) {
                    m[t * N + i] = true;
                    m[(N - 1 - t) * N + i] = true;
                    m[i * N + t] = true;
                    m[i * N + (N - 1 - t)] = true;
                }
            }
            int[][] corners = {{2, 1}, {1, 2}, {2, 2}};
            for (int[] p : corners) {
                int x = p[0], y = p[1];
                m[y * N + x] = true;
                m[y * N + (N - 1 - x)] = true;
                m[(N - 1 - y) * N + x] = true;
                m[(N - 1 - y) * N + (N - 1 - x)] = true;
            }
            int[] out = new int[N * N];
            for (int i = 0; i < out.length; i++) out[i] = m[i] ? 0xFF000000 : 0;
            Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
            b.setPixels(out, 0, N, 0, 0, N, N);
            return Bitmap.createScaledBitmap(b, 160, 160, false);
        } catch (Exception e) {
            return null;
        }
    }

    private static int otsu(int[] hist, int total) {
        long sum = 0;
        for (int i = 0; i < 256; i++) sum += (long) i * hist[i];
        long sumB = 0;
        int wB = 0;
        double best = -1;
        int thr = 128;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;
            sumB += (long) t * hist[t];
            double mB = (double) sumB / wB, mF = (double) (sum - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > best) {
                best = between;
                thr = t + 1;
            }
        }
        return thr;
    }
}
