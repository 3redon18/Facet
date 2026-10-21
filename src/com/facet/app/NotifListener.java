package com.facet.app;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.format.DateFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the phone's notifications (with the user's "Notification access" permission) so the lock screen
 * can show them. Nothing leaves the phone.
 */
public class NotifListener extends NotificationListenerService {
    private static NotifListener live;
    private static List<Face.Note> notes = new ArrayList<Face.Note>();
    private static final List<Runnable> watchers = new ArrayList<Runnable>();
    private static final Map<String, Bitmap> iconCache = new HashMap<String, Bitmap>();
    private final Handler main = new Handler(Looper.getMainLooper());

    // ---------------- for the rest of the app ----------------
    public static boolean hasAccess(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (s == null) return false;
        // Android may store either "com.facet.app/com.facet.app.NotifListener" or "com.facet.app/.NotifListener"
        for (String part : s.split(":"))
            if (part.startsWith(c.getPackageName() + "/") && part.endsWith("NotifListener")) return true;
        return false;
    }

    static volatile int lastActive = -1, lastShown = 0;
    static volatile long lastUpdate = 0;
    public static final String TEST_TAG = "luach-test";

    /** One line for the settings screen saying where things stand. */
    public static String status(Context c) {
        boolean access = hasAccess(c);
        if (!access) return "Notification access: OFF";
        if (live == null) return "Notification access: on · not connected yet";
        if (lastActive < 0) return "Notification access: on · connected · waiting for notifications";
        return "Notification access: on · connected · " + lastActive + " on the phone, " + lastShown + " shown";
    }

    /**
     * After an app update Android sometimes forgets to reconnect the reader. Nudge it: ask for a rebind
     * (Android 7+), and as a fallback switch the service off and on again.
     */
    private static long lastReconnect;

    public static void reconnect(Context c) {
        if (live != null || !hasAccess(c)) return;
        if (System.currentTimeMillis() - lastReconnect < 10000) return;
        lastReconnect = System.currentTimeMillis();
        ComponentName cn = new ComponentName(c, NotifListener.class);
        try {
            NotificationListenerService.class.getMethod("requestRebind", ComponentName.class).invoke(null, cn);
        } catch (Throwable ignored) { }
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) { }
    }

    /** Posts a notification from Facet that the lock screen will show, to check everything works. */
    public static void sendTest(Context c) {
        android.app.Notification n = new android.app.Notification.Builder(c)
                .setSmallIcon(android.graphics.drawable.Icon.createWithResource(c, R.drawable.icon_small))
                .setContentTitle("Test notification")
                .setContentText("If you can see this on your lock screen, it's working")
                .setAutoCancel(true)
                .setPriority(android.app.Notification.PRIORITY_DEFAULT)
                .build();
        ((android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(TEST_TAG, 99, n);
    }

    public static boolean connected() {
        return live != null;
    }

    public static synchronized List<Face.Note> current() {
        return new ArrayList<Face.Note>(notes);
    }

    public static void watch(Runnable r) { synchronized (watchers) { watchers.add(r); } }

    public static void unwatch(Runnable r) { synchronized (watchers) { watchers.remove(r); } }

    /** Swipe-to-clear from the lock screen. */
    public static void dismiss(Face.Note n) {
        if (live != null && n.clearable) {
            try { live.cancelNotification(n.key); } catch (Exception ignored) { }
        }
    }

    // ---------------- the service ----------------
    // Android keeps this service alive for as long as notification access is on, so it also watches for the
    // screen turning off and puts the lock screen up. That way nothing has to sit in the notification shade.
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { Facet.showLock(c); }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try { registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF)); } catch (Exception ignored) { }
        Facet.refresh(this);
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(screenOff); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        live = this;
        rebuild();
    }

    // (Android 7+ calls this; no @Override because we build against Android 6)
    public void onListenerDisconnected() {
        if (live == this) live = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        rebuild();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        rebuild();
    }

    private void rebuild() {
        StatusBarNotification[] all;
        try { all = getActiveNotifications(); } catch (Exception e) { return; }
        if (all == null) all = new StatusBarNotification[0];
        // Android ranks notifications itself; the lock screen shows them in that order, not by time.
        final java.util.Map<String, Integer> rank = new java.util.HashMap<String, Integer>();
        Set<String> tooQuiet = new HashSet<String>();
        try {
            RankingMap rm = getCurrentRanking();
            if (rm != null) {
                String[] order = rm.getOrderedKeys();
                if (order != null) for (int i = 0; i < order.length; i++) rank.put(order[i], i);
                Ranking r = new Ranking();
                for (StatusBarNotification s : all) {
                    if (!rm.getRanking(s.getKey(), r)) continue;
                    Object imp = r.getClass().getMethod("getImportance").invoke(r);   // Android 7+
                    if (imp instanceof Integer && (Integer) imp <= 1) tooQuiet.add(s.getKey());  // IMPORTANCE_MIN
                }
            }
        } catch (Throwable ignored) { }
        // group summaries are skipped when the group's own messages are showing
        Set<String> groupsWithChildren = new HashSet<String>();
        for (StatusBarNotification s : all)
            if ((s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0 && s.getNotification().getGroup() != null)
                groupsWithChildren.add(s.getPackageName() + "/" + s.getNotification().getGroup());
        List<StatusBarNotification> keep = new ArrayList<StatusBarNotification>();
        for (StatusBarNotification s : all) {
            Notification n = s.getNotification();
            if (s.getPackageName().equals(getPackageName()) && !TEST_TAG.equals(s.getTag())) continue;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 && n.getGroup() != null
                    && groupsWithChildren.contains(s.getPackageName() + "/" + n.getGroup())) continue;
            if (n.priority <= Notification.PRIORITY_MIN || tooQuiet.contains(s.getKey())) continue;
            // an app can mark a notification secret: Android never shows it over a locked screen
            if (n.visibility == Notification.VISIBILITY_SECRET) continue;
            if ("android".equals(s.getPackageName()) || "com.android.systemui".equals(s.getPackageName())) continue;
            Bundle x = n.extras;
            CharSequence title = x == null ? null : x.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = x == null ? null : x.getCharSequence(Notification.EXTRA_TEXT);
            if ((title == null || title.length() == 0) && (text == null || text.length() == 0)) continue;
            keep.add(s);
        }
        Collections.sort(keep, new Comparator<StatusBarNotification>() {
            public int compare(StatusBarNotification a, StatusBarNotification b) {
                Integer ra = rank.get(a.getKey()), rb = rank.get(b.getKey());
                if (ra != null && rb != null && !ra.equals(rb)) return ra - rb;
                return Long.compare(b.getNotification().when != 0 ? b.getNotification().when : b.getPostTime(),
                        a.getNotification().when != 0 ? a.getNotification().when : a.getPostTime());
            }
        });
        PackageManager pm = getPackageManager();
        List<Face.Note> out = new ArrayList<Face.Note>();
        for (StatusBarNotification s : keep) {
            Notification n = s.getNotification();
            Bundle x = n.extras;
            Face.Note note = new Face.Note();
            note.key = s.getKey();
            CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = x.getCharSequence(Notification.EXTRA_TEXT);
            if (text == null) text = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
            note.title = title == null ? "" : title.toString();
            note.text = text == null ? "" : text.toString();
            try {
                ApplicationInfo ai = pm.getApplicationInfo(s.getPackageName(), 0);
                note.app = pm.getApplicationLabel(ai).toString();
            } catch (Exception e) { note.app = s.getPackageName(); }
            long when = n.when != 0 ? n.when : s.getPostTime();
            long age = System.currentTimeMillis() - when;
            boolean showWhen = x == null || x.getBoolean(Notification.EXTRA_SHOW_WHEN, n.when != 0);
            note.when = !showWhen ? "" : age < 60000 ? "now" : DateFormat.getTimeFormat(this).format(new java.util.Date(when));
            if (n.publicVersion != null && n.publicVersion.extras != null) {
                CharSequence pt = n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence px = n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT);
                note.publicTitle = pt == null ? null : pt.toString();
                note.publicText = px == null ? null : px.toString();
            }
            if (x != null) {
                int pmax = x.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
                boolean spin = x.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
                if (pmax > 0 || spin) {
                    note.progress = x.getInt(Notification.EXTRA_PROGRESS, 0);
                    note.progressMax = pmax;
                    note.progressSpinning = spin;
                }
                Object pic = x.get(Notification.EXTRA_PICTURE);
                if (pic instanceof android.graphics.Bitmap) note.picture = pic;
            }
            note.icon = iconFor(s);
            note.avatar = largeBitmap(n);   // messaging apps put the contact's photo here
            note.open = n.contentIntent;
            note.autoCancel = (n.flags & Notification.FLAG_AUTO_CANCEL) != 0;
            note.clearable = s.isClearable();
            note.pkg = s.getPackageName();
            note.user = s.getUser();
            try { note.channelId = (String) Notification.class.getMethod("getChannelId").invoke(n); } catch (Throwable ignored) { }
            readConversation(note, x);
            readMedia(note, n, x);
            readActions(this, note, n, s.getPackageName());
            out.add(note);
        }
        // Android keeps the player above the list and people's messages above app notifications.
        // This is a stable sort, so the system's ranking still decides the order inside each of the three.
        Collections.sort(out, new Comparator<Face.Note>() {
            public int compare(Face.Note a, Face.Note b) { return band(a) - band(b); }
            private int band(Face.Note n) { return n.media ? 0 : n.messaging ? 1 : 2; }
        });
        synchronized (NotifListener.class) { notes = out; }
        lastActive = all.length;
        lastShown = out.size();
        lastUpdate = System.currentTimeMillis();
        main.post(new Runnable() {
            public void run() {
                List<Runnable> ws;
                synchronized (watchers) { ws = new ArrayList<Runnable>(watchers); }
                for (Runnable r : ws) r.run();
            }
        });
    }

    // ---------------- hold a notification: manage it like stock Android ----------------
    /** The notification channel behind a card: {name, importance}, or null when Android won't say. */
    public static Object[] channelInfo(Face.Note note) {
        try {
            Object ch = findChannel(note);
            if (ch == null) return null;
            String name = String.valueOf(ch.getClass().getMethod("getName").invoke(ch));
            int imp = (Integer) ch.getClass().getMethod("getImportance").invoke(ch);
            return new Object[]{name, imp};
        } catch (Throwable t) { return null; }
    }

    private static Object findChannel(Face.Note note) throws Exception {
        if (live == null || note.channelId == null || note.pkg == null || note.user == null) return null;
        java.lang.reflect.Method m = NotificationListenerService.class.getMethod("getNotificationChannels", String.class, android.os.UserHandle.class);
        List<?> chans = (List<?>) m.invoke(live, note.pkg, note.user);
        for (Object ch : chans) if (note.channelId.equals(ch.getClass().getMethod("getId").invoke(ch))) return ch;
        return null;
    }

    /** Hides a notification for a while; Android posts it again afterwards. */
    public static boolean snooze(Face.Note note, long ms) {
        try {
            if (live == null) return false;
            NotificationListenerService.class.getMethod("snoozeNotification", String.class, long.class).invoke(live, note.key, ms);
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Makes this kind of notification silent (importance 2) or alerting (importance 3 or higher). */
    public static boolean setImportance(Face.Note note, int importance) {
        try {
            Object ch = findChannel(note);
            if (ch == null) return false;
            ch.getClass().getMethod("setImportance", int.class).invoke(ch, importance);
            Class<?> chc = Class.forName("android.app.NotificationChannel");
            NotificationListenerService.class.getMethod("updateNotificationChannel", String.class, android.os.UserHandle.class, chc)
                    .invoke(live, note.pkg, note.user, ch);
            return true;
        } catch (Throwable t) { return false; }
    }

    // ---------------- conversations ----------------
    /**
     * Messaging apps (WhatsApp, Signal, SMS...) put the recent messages in the notification as a list of
     * {sender, text}. Inbox-style apps (email) give a list of lines. Fall back to the long text.
     */
    private static void readConversation(Face.Note note, Bundle x) {
        CharSequence convo = x.getCharSequence("android.conversationTitle");
        android.os.Parcelable[] msgs = x.getParcelableArray("android.messages");
        if (msgs != null && msgs.length > 0) {
            note.messaging = true;
            boolean group = convo != null || x.getBoolean("android.isGroupConversation");
            for (android.os.Parcelable pm : msgs) {
                if (!(pm instanceof Bundle)) continue;
                Bundle b = (Bundle) pm;
                CharSequence t = b.getCharSequence("text");
                if (t == null) continue;
                String who = senderName(b);
                note.messages.add(new String[]{who, t.toString()});
            }
            note.messageCount = note.messages.size();
            // Android already wrote the right one-line preview into the notification's title and text
            // (the newest incoming message, your own replies skipped); keep those, and only fill a gap.
            if ((note.text == null || note.text.isEmpty()) && !note.messages.isEmpty()) {
                String[] last = note.messages.get(note.messages.size() - 1);
                note.text = (group && !last[0].isEmpty() ? last[0] + ": " : "") + last[1];
            }
            if ((note.title == null || note.title.isEmpty()) && group && convo != null) note.title = convo.toString();
            note.group = group;
            return;
        }
        CharSequence[] lines = x.getCharSequenceArray("android.textLines");
        if (lines != null && lines.length > 0) {
            for (CharSequence l : lines) if (l != null) note.messages.add(new String[]{"", l.toString()});
            note.messageCount = note.messages.size();
            return;
        }
        CharSequence big = x.getCharSequence("android.bigText");
        if (big != null && big.length() > (note.text == null ? 0 : note.text.length())) note.full = big.toString();
    }

    /** The player's own media session, for apps whose notification doesn't hand it over. */
    private Object sessionFor(String pkg) {
        try {
            android.media.session.MediaSessionManager sm =
                    (android.media.session.MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<android.media.session.MediaController> cs = sm.getActiveSessions(new ComponentName(this, NotifListener.class));
            for (android.media.session.MediaController c : cs) if (pkg != null && pkg.equals(c.getPackageName())) return c.getSessionToken();
        } catch (Throwable ignored) { }
        return null;
    }

    private boolean hasSession(String pkg) { return sessionFor(pkg) != null; }

    /** A music or podcast player: the album art and the session that its buttons talk to. */
    private void readMedia(Face.Note note, Notification n, Bundle x) {
        Object tok = x.get("android.mediaSession");
        String template = x.getString("android.template");
        boolean styled = tok instanceof android.media.session.MediaSession.Token
                || (template != null && template.contains("MediaStyle"));
        if (!styled) {
            // some players (Spotify, for one) post a plain notification and keep the controls in their session:
            // find the player's own session and treat the notification as the player card
            if (!Notification.CATEGORY_TRANSPORT.equals(n.category) && !((n.flags & Notification.FLAG_ONGOING_EVENT) != 0 && hasSession(note.pkg))) return;
            tok = sessionFor(note.pkg);
            if (tok == null) return;
        } else if (!(tok instanceof android.media.session.MediaSession.Token)) {
            tok = sessionFor(note.pkg);
        }
        note.media = true;
        note.session = tok;
        if (tok instanceof android.media.session.MediaSession.Token) {
            // show what's playing, whatever words the notification itself used
            try {
                android.media.MediaMetadata m0 =
                        new android.media.session.MediaController(this, (android.media.session.MediaSession.Token) tok).getMetadata();
                if (m0 != null) {
                    CharSequence t0 = m0.getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
                    CharSequence a0 = m0.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
                    if (t0 != null && t0.length() > 0) { note.title = t0.toString(); note.text = a0 == null ? "" : a0.toString(); }
                }
            } catch (Throwable ignored) { }
        }
        android.graphics.Bitmap art = null;
        // the session's own metadata usually carries full-size album art; the notification icon is tiny
        if (tok instanceof android.media.session.MediaSession.Token) {
            try {
                android.media.MediaMetadata md =
                        new android.media.session.MediaController(this, (android.media.session.MediaSession.Token) tok).getMetadata();
                if (md != null) {
                    String[] keys = {android.media.MediaMetadata.METADATA_KEY_ALBUM_ART,
                            android.media.MediaMetadata.METADATA_KEY_ART,
                            android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON};
                    for (String k : keys) { android.graphics.Bitmap b = md.getBitmap(k); if (b != null) { art = b; break; } }
                }
            } catch (Throwable ignored) { }
        }
        if (art == null) {
            try {
                android.graphics.drawable.Icon li = n.getLargeIcon();
                android.graphics.drawable.Drawable d = li == null ? null : li.loadDrawable(this);
                if (d instanceof android.graphics.drawable.BitmapDrawable) art = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            } catch (Throwable ignored) { }
        }
        if (art == null) art = n.largeIcon;
        note.art = art;
        note.tint = art == null ? 0 : dominantColor(art);
    }

    /** A single strong colour from the artwork, for a faint card wash: the most colourful pixel, or the average if it's all grey. */
    static int dominantColor(android.graphics.Bitmap bmp) {
        try {
            android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(bmp, 24, 24, true);
            int best = 0; float bestScore = -1; long ar = 0, ag = 0, ab = 0, cnt = 0;
            for (int y = 0; y < s.getHeight(); y++) for (int x = 0; x < s.getWidth(); x++) {
                int c = s.getPixel(x, y);
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                ar += r; ag += g; ab += b; cnt++;
                float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                float sat = mx == 0 ? 0 : (mx - mn) / mx, bri = mx / 255f;
                float score = sat * bri;                 // colourful and not too dark
                if (score > bestScore) { bestScore = score; best = c; }
            }
            if (bestScore < 0.18f && cnt > 0)            // a grey cover: just average it
                best = ((int) (ar / cnt) << 16) | ((int) (ag / cnt) << 8) | (int) (ab / cnt);
            return 0xFF000000 | (best & 0xFFFFFF);
        } catch (Throwable t) { return 0; }
    }

    /** Sends play, pause, next or previous to the player behind a media card. dir: -1 back, 0 play/pause, 1 forward. */
    public static boolean transport(android.content.Context c, Face.Note note, int dir) {
        if (!(note.session instanceof android.media.session.MediaSession.Token)) return false;
        try {
            android.media.session.MediaController mc =
                    new android.media.session.MediaController(c, (android.media.session.MediaSession.Token) note.session);
            android.media.session.MediaController.TransportControls t = mc.getTransportControls();
            if (dir < 0) t.skipToPrevious();
            else if (dir > 0) t.skipToNext();
            else {
                android.media.session.PlaybackState ps = mc.getPlaybackState();
                if (ps != null && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING) t.pause();
                else t.play();
            }
            return true;
        } catch (Throwable t) { return false; }
    }

    /** How far into the track a player is: {position, length, playing} in ms, or null when it won't say. */
    public static long[] mediaProgress(android.content.Context c, Face.Note note) {
        if (!(note.session instanceof android.media.session.MediaSession.Token)) return null;
        try {
            android.media.session.MediaController mc =
                    new android.media.session.MediaController(c, (android.media.session.MediaSession.Token) note.session);
            android.media.session.PlaybackState ps = mc.getPlaybackState();
            android.media.MediaMetadata md = mc.getMetadata();
            if (ps == null || md == null) return null;
            long len = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
            if (len <= 0) return null;
            boolean playing = ps.getState() == android.media.session.PlaybackState.STATE_PLAYING;
            long pos = ps.getPosition();
            // the player only reports where it was a moment ago, so carry it forward at the speed it's running
            if (playing && ps.getLastPositionUpdateTime() > 0)
                pos += (long) ((android.os.SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime())
                        * (ps.getPlaybackSpeed() == 0 ? 1 : ps.getPlaybackSpeed()));
            return new long[]{Math.max(0, Math.min(len, pos)), len, playing ? 1 : 0};
        } catch (Throwable t) { return null; }
    }

    /** Jumps to a point in the track. */
    public static boolean mediaSeek(android.content.Context c, Face.Note note, long ms) {
        if (!(note.session instanceof android.media.session.MediaSession.Token)) return false;
        try {
            new android.media.session.MediaController(c, (android.media.session.MediaSession.Token) note.session)
                    .getTransportControls().seekTo(ms);
            return true;
        } catch (Throwable t) { return false; }
    }

    /** The large icon, which messaging apps use for the contact's photo. */
    private Bitmap largeBitmap(Notification n) {
        try {
            android.graphics.drawable.Icon li = n.getLargeIcon();
            if (li != null) {
                android.graphics.drawable.Drawable d = li.loadDrawable(this);
                if (d instanceof android.graphics.drawable.BitmapDrawable) return ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            }
        } catch (Throwable ignored) { }
        return n.largeIcon;
    }

    private static String senderName(Bundle b) {
        CharSequence s = b.getCharSequence("sender");
        if (s != null) return s.toString();
        Object person = b.get("sender_person"); // Android 9+: a Person object
        if (person != null) {
            try {
                Object name = person.getClass().getMethod("getName").invoke(person);
                if (name != null) return name.toString();
            } catch (Throwable ignored) { }
        }
        return "You"; // messages you sent have no sender
    }

    /** Buttons the app offers on its notification (Reply, Mark as read, ...). */
    private static void readActions(android.content.Context c, Face.Note note, Notification n, String pkg) {
        if (n.actions == null) return;
        for (Notification.Action a : n.actions) {
            if (a == null || a.title == null || a.actionIntent == null) continue;
            android.app.RemoteInput[] ri = a.getRemoteInputs();
            boolean reply = false;
            if (ri != null) for (android.app.RemoteInput r : ri) if (r.getAllowFreeFormInput()) reply = true;
            note.actions.add(a);
            note.actionLabels.add(a.title.toString());
            note.actionReply.add(reply);
            note.actionIcons.add(actionIcon(c, a, pkg));
        }
    }

    /** Presses one of the notification's buttons, with typed text if it's a reply. Returns false if it failed. */
    public static boolean runAction(Context c, Face.Note note, int i, String text) {
        try {
            Notification.Action a = (Notification.Action) note.actions.get(i);
            android.app.RemoteInput[] ri = a.getRemoteInputs();
            if (ri != null && ri.length > 0 && text != null) {
                android.content.Intent fill = new android.content.Intent();
                Bundle results = new Bundle();
                for (android.app.RemoteInput r : ri) results.putCharSequence(r.getResultKey(), text);
                android.app.RemoteInput.addResultsToIntent(ri, fill, results);
                a.actionIntent.send(c, 0, fill);
            } else {
                a.actionIntent.send();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The notification's small icon as a white silhouette (the lock screen tints it). */
    private Bitmap iconFor(StatusBarNotification s) {
        String key = s.getPackageName() + ":" + s.getNotification().icon;
        Bitmap b = iconCache.get(key);
        if (b != null) return b;
        try {
            Icon ic = s.getNotification().getSmallIcon();
            Drawable d = ic != null ? ic.loadDrawable(createPackageContext(s.getPackageName(), 0)) : null;
            if (d == null) d = getPackageManager().getApplicationIcon(s.getPackageName());
            int size = 64;
            b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            iconCache.put(key, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** The app's own icon for one of its buttons, so a media card looks like the player's own controls. */
    private static Object actionIcon(android.content.Context c, Notification.Action a, String pkg) {
        try {
            android.graphics.drawable.Icon ic = a.getIcon();
            if (ic != null) return ic.loadDrawable(c);
        } catch (Throwable ignored) { }
        try {
            if (a.icon != 0)
                return c.getPackageManager().getResourcesForApplication(pkg).getDrawable(a.icon, null);
        } catch (Throwable ignored) { }
        return null;
    }
}
