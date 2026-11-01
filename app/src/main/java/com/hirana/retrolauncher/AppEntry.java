package com.hirana.retrolauncher;

import android.content.ComponentName;

public class AppEntry {
    public String label;
    public final ComponentName component;
    public String iconKey; // null = varsayılan ikon

    public AppEntry(String label, ComponentName component) {
        this.label = label;
        this.component = component;
    }

    public String pkg() {
        return component.getPackageName();
    }
}
