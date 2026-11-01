package com.hirana.retrolauncher;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashSet;
import java.util.Set;

/** Bildirim sayısını, cevapsız aramaları ve mesajları sayar. */
public class NotifService extends NotificationListenerService {

    public static volatile int total = 0, missed = 0, sms = 0;
    public static volatile Set<String> phonePkgs = new HashSet<>();
    public static volatile Set<String> smsPkgs = new HashSet<>();

    @Override
    public void onListenerConnected() {
        recount();
    }

    @Override
    public void onListenerDisconnected() {
        total = 0;
        missed = 0;
        sms = 0;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        recount();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        recount();
    }

    private void recount() {
        try {
            StatusBarNotification[] arr = getActiveNotifications();
            int t = 0, m = 0, s = 0;
            if (arr != null) {
                for (StatusBarNotification sbn : arr) {
                    if (sbn.isOngoing() || !sbn.isClearable()) continue;
                    Notification n = sbn.getNotification();
                    if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                    if (getPackageName().equals(sbn.getPackageName())) continue;
                    t++;
                    String pkg = sbn.getPackageName();
                    if (phonePkgs.contains(pkg) || "missed_call".equals(n.category)) m++;
                    else if (smsPkgs.contains(pkg)) s += Math.max(1, n.number);
                }
            }
            total = t;
            missed = m;
            sms = s;
        } catch (Exception ignored) {
        }
    }
}
