package com.facet.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.view.KeyEvent;

import java.util.Calendar;

/** Shared helpers: saved settings, the status-bar notification, and Sidephone key mapping. */
public final class Facet {
    public static final int NOTIF_DATE = 1, NOTIF_LOCK = 2;

    public static Style load(Context c) {
        final SharedPreferences sp = c.getSharedPreferences("luach", Context.MODE_PRIVATE);
        Style st = new Style();
        st.load(new Style.Store() {
            public int get(String k, int d) { return sp.getInt(k, d); }
            public void put(String k, int v) { }
        });
        return st;
    }

    public static void save(Context c, Style st) {
        final SharedPreferences.Editor e = c.getSharedPreferences("luach", Context.MODE_PRIVATE).edit();
        st.save(new Style.Store() {
            public int get(String k, int d) { return d; }
            public void put(String k, int v) { e.putInt(k, v); }
        });
        e.apply();
    }

    /** Clears the old always-on date notification (earlier versions had one). */
    public static void refresh(Context c) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_DATE);
    }

    /** Puts the custom lock screen up when the screen turns off. Called by NotifListener, which Android keeps running. */
    static void showLock(Context c) {
        Style st = load(c);
        if (st.lockOn != 1) return;
        android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
        try { if (tm != null && tm.getCallState() != android.telephony.TelephonyManager.CALL_STATE_IDLE) return; } catch (Exception ignored) { }
        Intent lock = new Intent(c, LockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        boolean started = false;
        if (android.provider.Settings.canDrawOverlays(c)) {
            try { c.startActivity(lock); started = true; } catch (Exception ignored) { }
        }
        if (!started) {
            // Without "display over other apps", ask Android to open it through a full-screen notification.
            PendingIntent pi = PendingIntent.getActivity(c, 1, lock, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c)
                    .setSmallIcon(Icon.createWithResource(c, R.drawable.icon_small))
                    .setContentTitle("Lock screen")
                    .setPriority(Notification.PRIORITY_MAX)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setFullScreenIntent(pi, true)
                    .setSound(null)
                    .setVibrate(null)
                    .setAutoCancel(true)
                    .build();
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_LOCK, n);
        }
    }

    public static Day today(Style st) {
        return Day.compute(Calendar.getInstance(), st);
    }

    /** The Sidephone Sundial sends media keys: middle = play/pause, left = previous, right = next. */
    public static int translate(int code) {
        switch (code) {
            case 85: case 126: case 127: return KeyEvent.KEYCODE_DPAD_CENTER;
            case 87: case 90: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 88: case 89: return KeyEvent.KEYCODE_DPAD_LEFT;
            default: return code;
        }
    }

    public static boolean isMedia(int code) {
        return translate(code) != code;
    }
}
