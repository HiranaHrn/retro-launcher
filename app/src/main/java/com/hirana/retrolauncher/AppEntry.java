package com.hirana.retrolauncher;

import android.content.ComponentName;
import android.graphics.Bitmap;

public class AppEntry {
    public String label;
    public final ComponentName component; // null = programın kendi bölümü
    public String iconKey;   // hazır piksel ikon adı (ic_xxx)
    public Bitmap autoIcon;  // hazır ikon yoksa otomatik pikselleştirilmiş ikon
    public String special;   // "settings" = Retro ayarları

    public AppEntry(String label, ComponentName component) {
        this.label = label;
        this.component = component;
    }

    public String pkg() {
        return component == null ? "" : component.getPackageName();
    }
}
