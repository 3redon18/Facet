package com.facet.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.Calendar;

/** Shows the lock screen face, either full screen or as a small live preview in the editor. */
public class FaceView extends View {
    Style style;
    float lift;          // swipe offset in px
    float hint = 1;
    boolean preview;
    int selected = -1, dragIndex = -1;
    float dragX;
    int editTarget;                // 0 = clock, 1 = dates
    boolean edit, guide;           // clock editing mode
    boolean cardViews;             // the notification cards are real views on top of this one
    Runnable onFrame;              // called after each draw
    Face.Now last;                 // the last frame drawn (card positions for tapping)
    private final Runnable onNotes = new Runnable() {
        public void run() { invalidate(); }
    };
    private final AndroidPen pen;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean ticking;
    private Day cachedDay;
    private String cachedKey = "";

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!ticking) return;
            invalidate();
            long now = System.currentTimeMillis();
            long next = (style != null && style.secs == 1) ? 1000 - now % 1000 : 60000 - now % 60000;
            handler.postDelayed(this, Math.max(50, next + 20));
        }
    };

    public FaceView(Context c) {
        super(c);
        pen = new AndroidPen(c);
        setFocusable(false);
    }

    /** Example cards for the editor preview, until notification access is turned on. */
    private static java.util.List<Face.Note> samples() {
        java.util.List<Face.Note> l = new java.util.ArrayList<Face.Note>();
        String[][] d = {{"Messages", "Example message", "Notifications will show here"}, {"Calendar", "Example event", "3:30 – 4:30 PM"}};
        for (String[] x : d) { Face.Note n = new Face.Note(); n.app = x[0]; n.title = x[1]; n.text = x[2]; n.when = "now"; l.add(n); }
        return l;
    }

    void setStyle(Style s) {
        style = s;
        cachedKey = "";
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ticking = true;
        handler.post(tick);
        NotifListener.watch(onNotes);
    }

    @Override
    protected void onDetachedFromWindow() {
        ticking = false;
        handler.removeCallbacks(tick);
        NotifListener.unwatch(onNotes);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (style == null) return;
        Calendar now = Calendar.getInstance();
        String key = now.get(Calendar.DAY_OF_YEAR) + ":" + now.get(Calendar.HOUR_OF_DAY) + ":" + now.get(Calendar.MINUTE)
                + ":" + style.sunset + style.city + style.h24;
        if (!key.equals(cachedKey)) { cachedDay = Day.compute(now, style); cachedKey = key; }
        Face.Now n = new Face.Now();
        n.time = now;
        n.day = cachedDay;
        n.lift = lift;
        n.hint = hint;
        n.showHint = true;
        n.selected = selected;
        n.dragIndex = dragIndex;
        n.dragX = dragX;
        n.edit = edit;
        n.editTarget = editTarget;
        n.cardViews = cardViews && !preview;
        n.guide = guide;
        if (style.notes == 1) {
            n.notes = NotifListener.current();
            if (!NotifListener.hasAccess(getContext())) n.noteProblem = "Notification access is off · open Facet to allow it";
            else if (!NotifListener.connected()) { n.noteProblem = "Not connected to notifications yet · open Facet"; NotifListener.reconnect(getContext()); }
            if (preview && n.notes.isEmpty() && !NotifListener.connected()) n.notes = samples();
        }
        last = n;
        Intent bat = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int lv = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (lv >= 0) n.battery = Math.round(lv * 100f / Math.max(1, sc));
            int stt = bat.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
            n.charging = stt == BatteryManager.BATTERY_STATUS_CHARGING || stt == BatteryManager.BATTERY_STATUS_FULL;
        }
        if (preview) {
            // the face is designed for the full screen: draw at full size, then shrink to fit the preview
            int fw = getRootView().getWidth() > 0 ? getResources().getDisplayMetrics().widthPixels : getWidth();
            int fh = getResources().getDisplayMetrics().heightPixels;
            float k = Math.min(getWidth() / (float) fw, getHeight() / (float) fh);
            canvas.save();
            canvas.translate((getWidth() - fw * k) / 2, (getHeight() - fh * k) / 2);
            canvas.scale(k, k);
            canvas.clipRect(0, 0, fw, fh);
            pen.begin(canvas, fw, fh);
            Face.draw(pen, style, n);
            canvas.restore();
        } else {
            pen.begin(canvas, getWidth(), getHeight());
            Face.draw(pen, style, n);
            if (onFrame != null) post(onFrame);
        }
    }
}
