package com.facet.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.Gravity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.HorizontalScrollView;
import android.widget.SeekBar;
import android.view.ScaleGestureDetector;

import java.util.List;

/**
 * The custom lock screen. It sits on top of Android's own lock screen; unlocking it hands over to the
 * system, so a PIN (if you have one) is still asked for.
 *
 * Touch: swipe up to unlock, tap a notification to open it, swipe a notification sideways to clear it.
 * Keypad: up/down pick a notification, middle opens it (or unlocks if none is picked), left clears it.
 */
public class LockActivity extends Activity {
    private FaceView face;
    private float downX, downY;
    private int mode;           // 0 none, 1 vertical (unlock), 2 sideways on a card
    private boolean unlocking;
    private Face.Note openAfterUnlock;
    private FrameLayout root, panel;
    private Face.Note panelNote;
    private EditText replyBox;
    private NoteList list;
    private long lastMinute;
    private float peekDownX, peekDownY;
    private boolean peekOnList, centerHeld;
    private final Runnable onNotes = new Runnable() { public void run() { refreshList(); } };
    private final Runnable layoutList = new Runnable() {
        public void run() {
            Face.Now n = face.last;
            if (n == null || list == null) return;
            boolean show = !editing && face.style.notes == 1 && !n.notes.isEmpty();
            list.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) list.getLayoutParams();
            int top = Math.round(n.notesTop), h = Math.max(dp(80), Math.round(n.notesBottom - n.notesTop));
            if (lp.topMargin != top || lp.height != h) { lp.topMargin = top; lp.height = h; list.setLayoutParams(lp); }
            list.setTranslationY(-face.lift);
            list.setAlpha(1 - Math.min(1, face.lift / (face.getHeight() * 0.35f)));
            long minute = System.currentTimeMillis() / 60000;
            if (minute != lastMinute) { lastMinute = minute; refreshList(); }   // "now" turns into a time
        }
    };
    private final NoteList.Host host = new NoteList.Host() {
        public void open(Face.Note n) { openAfterUnlock = n; unlock(); }
        public void settings(Face.Note n) {
            // the app's notification settings open on the real screen, so this asks for your PIN first
            afterUnlockIntent = new Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                    .putExtra("android.provider.extra.APP_PACKAGE", n.pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            unlock();
        }
        public void toast(String s) { Toast.makeText(LockActivity.this, s, Toast.LENGTH_SHORT).show(); }
        public void showKeyboard(View v) { LockActivity.this.showKeyboard(v); }
        public void replying(boolean on) { LockActivity.this.setReplying(on); }
        public void hideKeyboard(View v) {
            InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    };
    // clock editing
    private boolean editing;
    private int editKind;          // 0 clock, 1 wallpaper
    private Intent afterUnlockIntent;
    private LinearLayout sheet;
    private FrameLayout sheetBody;
    private int tab;
    private int[] snapshot;
    private float rawX, rawY, lastTX, lastTY;
    private boolean draggingClock;
    private ScaleGestureDetector scaler;
    private SeekBar sizeBar;
    private final List<View> fontChips = new java.util.ArrayList<View>();
    private final List<View> colorChips = new java.util.ArrayList<View>();
    private final List<View> hebChips = new java.util.ArrayList<View>();
    private final List<View> dateChips = new java.util.ArrayList<View>();
    private final Runnable longPress = new Runnable() {
        public void run() {
            if (mode == 0 && !editing && panel == null) {
                mode = 3;
                face.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                if (inClock(downX, downY)) enterEdit(0);
                else if (inDates(downX, downY)) enterEdit(1);
                else enterWallpaperEdit();
            }
        }
    };
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        face = new FaceView(this);
        face.setStyle(Facet.load(this));
        root = new FrameLayout(this);
        root.addView(face, new FrameLayout.LayoutParams(-1, -1));
        face.cardViews = true;
        face.onFrame = layoutList;
        list = new NoteList(this, host);
        root.addView(list, new FrameLayout.LayoutParams(-1, dp(80)));
        setContentView(root);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(Facet.NOTIF_LOCK);
        scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            public boolean onScale(ScaleGestureDetector d) {
                if (!editing) return false;
                setSize(Math.round(face.style.clockSize * d.getScaleFactor()));
                return true;
            }
        });
        face.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) { return onTouchFace(e); }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        unlocking = false;
        openAfterUnlock = null;
        closePanel();
        if (editing) cancelEdit();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        face.setStyle(Facet.load(this));
        refreshList();
        face.lift = 0;
        face.selected = -1;
        face.dragIndex = -1;
        face.invalidate();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(Facet.NOTIF_LOCK);
    }

    private void refreshList() {
        if (list != null && face != null && face.style != null) list.setNotes(NotifListener.current(), face.style);
    }

    @Override
    protected void onStart() {
        super.onStart();
        NotifListener.watch(onNotes);
        refreshList();
    }

    @Override
    protected void onStop() {
        NotifListener.unwatch(onNotes);
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (editing) cancelEdit();   // never closes the lock screen
    }

    private boolean inside(View v, View ancestor) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == ancestor) return true;
        return false;
    }

    /** Watches touches without taking them: a swipe up that starts on the notifications unlocks, if they can't scroll further. */
    private boolean peekAtEnd;
    private int peekScroll;

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (!editing && panel == null && list != null) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    peekDownX = e.getRawX(); peekDownY = e.getRawY();
                    int[] loc = new int[2];
                    list.getLocationOnScreen(loc);
                    peekOnList = list.getVisibility() == View.VISIBLE && peekDownY >= loc[1] && peekDownY <= loc[1] + list.getHeight();
                    // a swipe only unlocks if the list was already at its end when the finger went down, so scrolling to see more never unlocks
                    peekAtEnd = !list.canScroll() || list.atBottom();
                    peekScroll = list.getScrollY();
                    break;
                }
                case MotionEvent.ACTION_UP: {
                    if (peekOnList && !list.isTyping() && peekAtEnd && list.getScrollY() == peekScroll) {
                        float dy = peekDownY - e.getRawY(), dx = Math.abs(e.getRawX() - peekDownX);
                        if (dy > face.getHeight() * 0.18f && dy > dx * 1.5f) unlock();
                    }
                    peekOnList = false;
                    break;
                }
            }
        }
        return super.dispatchTouchEvent(e);
    }

    private boolean replying;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !replying) applyImmersive();
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /**
     * A fullscreen window can't be panned up for the soft keyboard -- it just covers the content.
     * So while a reply box is open, drop fullscreen so the keyboard pushes the card into view; restore after.
     */
    private void setReplying(boolean on) {
        replying = on;
        if (on) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            applyImmersive();
        }
    }

    // ---------------- touch ----------------
    private boolean inDates(float x, float y) {
        Face.Now n = face.last;
        if (n == null) return false;
        float[] r = n.datesRect;
        return r[3] > r[1] && x >= r[0] && x <= r[2] && y >= r[1] - 6 && y <= r[3] + 6;
    }

    private boolean inClock(float x, float y) {
        Face.Now n = face.last;
        if (n == null) return false;
        float[] r = n.clockRect;
        return x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3];
    }

    private boolean onTouchFace(MotionEvent e) {
        if (editing) return onTouchEdit(e);
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y; mode = 0;
                handler.removeCallbacks(longPress);
                handler.postDelayed(longPress, 450);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX, dy = y - downY;
                if (mode == 3) return true;
                if (mode == 0 && Math.hypot(dx, dy) > 14) { handler.removeCallbacks(longPress); mode = 1; }
                if (mode == 1) { face.lift = Math.max(0, -dy); face.invalidate(); }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(longPress);
                if (mode == 3) { mode = 0; return true; }
                if (mode == 1) {
                    if (face.lift > face.getHeight() * 0.18f) unlock();
                    else springBack();
                }
                mode = 0;
                return true;
            }
        }
        return false;
    }

    // ---------------- clock editing (hold the clock) ----------------
    // The clock and the dates are two separate objects: touch one to edit it; each moves and resizes on its own.
    private int editTarget;      // 0 clock, 1 dates

    private void initPos() {
        Style st = face.style;
        float W = face.getWidth(), H = face.getHeight();
        if (editTarget == 0 && (st.clockX < 0 || st.clockY < 0)) {   // leave automatic placement where it is
            st.clockX = Math.round(face.last.clockCx / W * 1000);
            st.clockY = Math.round(face.last.clockCy / H * 1000);
        } else if (editTarget == 1 && (st.dateX < 0 || st.dateY < 0)) {
            st.dateX = Math.round(face.last.datesCx / W * 1000);
            st.dateY = Math.round(face.last.datesTopY / H * 1000);
        }
    }

    private int curX() { return editTarget == 0 ? face.style.clockX : face.style.dateX; }
    private int curY() { return editTarget == 0 ? face.style.clockY : face.style.dateY; }

    private void setXY(int x, int y) {
        Style st = face.style;
        x = Math.max(60, Math.min(940, x));
        y = editTarget == 0 ? Math.max(90, Math.min(780, y)) : Math.max(30, Math.min(900, y));
        if (editTarget == 0) { st.clockX = x; st.clockY = y; } else { st.dateX = x; st.dateY = y; }
    }

    private boolean onTouchEdit(MotionEvent e) {
        if (editKind == 1) return true;
        scaler.onTouchEvent(e);
        float W = face.getWidth(), H = face.getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                lastTX = e.getX(); lastTY = e.getY();
                int hit = inClock(lastTX, lastTY) ? 0 : inDates(lastTX, lastTY) ? 1 : -1;
                draggingClock = hit >= 0;
                if (hit >= 0 && hit != editTarget) setTarget(hit);
                if (draggingClock) { initPos(); rawX = curX(); rawY = curY(); }
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (draggingClock && e.getPointerCount() == 1 && !scaler.isInProgress()) {
                    rawX += (e.getX() - lastTX) / W * 1000;
                    rawY += (e.getY() - lastTY) / H * 1000;
                    rawX = Math.max(60, Math.min(940, rawX));
                    boolean snap = Math.abs(rawX - 500) < 14;
                    setXY(snap ? 500 : Math.round(rawX), Math.round(rawY));
                    face.guide = snap;
                    face.invalidate();
                }
                lastTX = e.getX(); lastTY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                face.guide = false;
                draggingClock = false;
                face.invalidate();
                return true;
        }
        return true;
    }

    private void setSize(int v) {
        if (editTarget == 0) {
            v = Math.max(60, Math.min(260, v));
            face.style.clockSize = v;
            if (sizeBar != null && sizeBar.getProgress() != v - 60) sizeBar.setProgress(v - 60);
        } else {
            v = Math.max(40, Math.min(250, v));
            face.style.dateSize = v;
            if (sizeBar != null && sizeBar.getProgress() != v - 40) sizeBar.setProgress(v - 40);
        }
        face.invalidate();
    }

    private void nudge(int dx, int dy) {
        initPos();
        setXY(curX() + dx, curY() + dy);
        face.invalidate();
    }

    /** Switch which object is being edited (touching the other one does this): the sheet changes to match. */
    private void setTarget(int t) {
        editTarget = t;
        face.editTarget = t;
        if (sheet != null) {
            root.removeView(sheet);
            sheet = null; sheetBody = null; sizeBar = null;
            fontChips.clear(); colorChips.clear(); hebChips.clear(); dateChips.clear();
            buildSheet();
        }
        face.invalidate();
    }

    private void enterWallpaperEdit() {
        Style st = face.style;
        snapshot = new int[]{st.bg, st.photo, st.dim, st.cardCol, st.cardText, st.cardAlpha};
        editing = true;
        editKind = 1;
        wallGroup = 0;
        face.cardViews = false;   // draw the cards ourselves so colour/opacity preview live
        face.selected = -1;
        face.invalidate();
        buildSheet();
    }

    private void enterEdit(int target) {
        Style st = face.style;
        snapshot = new int[]{st.font, st.clockCol, st.clockSize, st.clockX, st.clockY, st.hebCol, st.dateCol, st.dateX, st.dateY, st.dateSize, st.dateFont};
        editing = true;
        editKind = 0;
        editTarget = target;
        face.editTarget = target;
        face.edit = true;
        face.selected = -1;
        face.invalidate();
        buildSheet();
    }

    private void cancelEdit() {
        if (!editing) return;
        Style st = face.style;
        if (editKind == 1) { st.bg = snapshot[0]; st.photo = snapshot[1]; st.dim = snapshot[2]; st.cardCol = snapshot[3]; st.cardText = snapshot[4]; st.cardAlpha = snapshot[5]; }
        else { st.font = snapshot[0]; st.clockCol = snapshot[1]; st.clockSize = snapshot[2]; st.clockX = snapshot[3]; st.clockY = snapshot[4]; st.hebCol = snapshot[5]; st.dateCol = snapshot[6]; st.dateX = snapshot[7]; st.dateY = snapshot[8]; st.dateSize = snapshot[9]; st.dateFont = snapshot[10]; }
        endEdit();
    }

    private void saveEdit() {
        if (!editing) return;
        Facet.save(this, face.style);
        endEdit();
    }

    private void endEdit() {
        editing = false;
        face.edit = false;
        face.cardViews = true;
        face.guide = false;
        if (sheet != null) root.removeView(sheet);
        sheet = null; sheetBody = null; sizeBar = null;
        fontChips.clear(); colorChips.clear(); hebChips.clear(); dateChips.clear();
        face.invalidate();
        refreshList();   // the cards are real views: repaint them with the colours just chosen
    }

    private void back() {
        if (panel != null) closePanel();
        else if (editing) cancelEdit();
    }

    private TextView sheetText(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView tabButton(String label, final int idx) {
        TextView t = sheetText(label, 15, 0xFFB7BFCC);
        t.setTag(Integer.valueOf(idx));
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(idx); } });
        return t;
    }

    private View focusable(View v) {
        v.setFocusable(true);
        v.setClickable(true);
        return v;
    }

    private GradientDrawable ring(int fill, int stroke, int strokeDp, float radiusDp) {
        GradientDrawable g = bg(fill, radiusDp);
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    /** The bottom sheet, like the iPhone's: Cancel / Done, then Font, Color, Size and Move. */
    private void buildSheet() {
        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sbg = bg(0xF2151A25, 22);
        sbg.setCornerRadii(new float[]{dp(22), dp(22), dp(22), dp(22), 0, 0, 0, 0});
        sheet.setBackground(sbg);
        sheet.setPadding(dp(14), dp(10), dp(14), dp(10));
        sheet.setClickable(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView cancel = sheetText("Cancel", 16, 0xFFF5F5F7);
        focusable(cancel); cancel.setPadding(dp(8), dp(8), dp(8), dp(8));
        cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { cancelEdit(); } });
        TextView title = sheetText(editKind == 1 ? "Appearance" : editTarget == 0 ? "Clock" : "Dates", 16, 0xFFB7BFCC);
        TextView done = sheetText("Done", 16, 0xFFE3B85A);
        done.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        focusable(done); done.setPadding(dp(8), dp(8), dp(8), dp(8));
        done.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveEdit(); } });
        top.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(done, new LinearLayout.LayoutParams(-2, -2));
        sheet.addView(top);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabRow = tabs;
        if (editKind == 1) {
            sheet.addView(wallSegment());   // Wallpaper | Cards
            fillWallTabs();
        } else {
            if (editTarget == 0) {
                tabs.addView(tabButton("Font", 0));
                tabs.addView(tabButton("Color", 1));
                tabs.addView(tabButton("Size", 2));
                tabs.addView(tabButton("Move", 3));
            } else {
                tabs.addView(tabButton("Font", 5));
                tabs.addView(tabButton("Color", 4));
                tabs.addView(tabButton("Size", 2));
                tabs.addView(tabButton("Move", 3));
            }
        }
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabs);
        sheet.addView(tabScroll);

        sheetBody = new FrameLayout(this);
        sheet.addView(sheetBody, new LinearLayout.LayoutParams(-1, dp(96)));
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        showTab(editKind == 1 ? (wallGroup == 0 ? 0 : 6) : (editTarget == 1 ? 5 : 0));
        done.requestFocus();
    }

    private void showTab(int idx) {
        tab = idx;
        LinearLayout tabs = tabRow;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView t = (TextView) tabs.getChildAt(i);
            boolean on = t.getTag() instanceof Integer && (Integer) t.getTag() == idx;
            t.setTextColor(on ? 0xFFE3B85A : 0xFFB7BFCC);
            t.setBackground(on ? ring(0x22E3B85A, 0, 0, 12) : null);
        }
        sheetBody.removeAllViews();
        View v;
        if (editKind == 1) v = idx == 0 ? backgroundTab() : idx == 1 ? dimTab() : idx == 2 ? photoTab()
                : idx == 6 ? cardColorTab() : idx == 7 ? cardTextTab() : cardFadeTab();
        else v = (idx == 0 || idx == 5) ? fontTab() : idx == 1 ? colorTab() : idx == 2 ? sizeTab() : idx == 4 ? datesTab() : moveTab();
        sheetBody.addView(v, new FrameLayout.LayoutParams(-1, -1));
        refreshChips();
    }

    private LinearLayout tabRow;
    private int wallGroup;   // 0 = Wallpaper (background/dim/photo), 1 = Cards (colour/text/opacity)

    /** The two-way switch at the top of the Appearance sheet. */
    private View wallSegment() {
        LinearLayout seg = new LinearLayout(this);
        seg.setGravity(Gravity.CENTER);
        seg.setPadding(0, dp(2), 0, dp(4));
        seg.addView(segButton("Background", 0));
        seg.addView(segButton("Cards", 1));
        return seg;
    }

    private TextView segButton(String label, final int group) {
        boolean on = wallGroup == group;
        TextView t = sheetText(label, 14, on ? 0xFF10131A : 0xFFB7BFCC);
        if (on) t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        focusable(t);
        t.setPadding(dp(18), dp(7), dp(18), dp(7));
        t.setBackground(bg(on ? 0xFFE3B85A : 0x14FFFFFF, 16));
        t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setWallGroup(group); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = lp.rightMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private void setWallGroup(int g) {
        if (wallGroup == g) return;
        wallGroup = g;
        if (sheet != null) root.removeView(sheet);
        buildSheet();   // rebuild with the other group's tabs
    }

    private void fillWallTabs() {
        tabRow.removeAllViews();
        if (wallGroup == 0) {
            tabRow.addView(tabButton("Color", 0));
            tabRow.addView(tabButton("Dim", 1));
            tabRow.addView(tabButton("Photo", 2));
        } else {
            tabRow.addView(tabButton("Color", 6));
            tabRow.addView(tabButton("Text", 7));
            tabRow.addView(tabButton("Opacity", 8));
        }
    }

    // ---- notification cards (from holding the wallpaper) ----
    private View cardColorTab() { return cardColorStrip("cardCol"); }
    private View cardTextTab() { return cardColorStrip("cardText"); }

    /** A row of colour chips (plus an "auto" chip) that sets a card colour live. */
    private View cardColorStrip(final String key) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(2), dp(10), dp(2), dp(10));
        strip.addView(cardChip(key, -1, "auto"));
        for (int i = 0; i < Style.COLORS.length; i++) strip.addView(cardChip(key, i, null));
        hs.addView(strip);
        return hs;
    }

    private View cardChip(final String key, final int idx, String label) {
        FrameLayout cell = new FrameLayout(this);
        focusable(cell);
        cell.setPadding(dp(3), dp(3), dp(3), dp(3));
        boolean sel = face.style.get(key) == idx;
        GradientDrawable g = bg(idx < 0 ? 0x33FFFFFF : (0xFF000000 | Style.COLORS[idx]), 18);
        g.setStroke(dp(sel ? 3 : 1), sel ? 0xFFE3B85A : 0x55FFFFFF);
        View chip = new View(this);
        chip.setBackground(g);
        cell.addView(chip, new FrameLayout.LayoutParams(dp(30), dp(30)));
        if (label != null) {
            TextView tv = sheetText(label, 9, 0xFFF5F5F7);
            cell.addView(tv, new FrameLayout.LayoutParams(dp(30), dp(30)));
        }
        cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { face.style.set(key, idx); face.invalidate(); showTab(tab); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(4);
        cell.setLayoutParams(lp);
        return cell;
    }

    /** The card opacity slider. */
    private View cardFadeTab() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), 0, dp(4), 0);
        row.addView(sheetText("\u25A2", 16, 0xFFB7BFCC));   // faint square
        sizeBar = new SeekBar(this);
        sizeBar.setMax(80);
        sizeBar.setProgress(Math.max(0, Math.min(80, face.style.cardAlpha - 20)));
        sizeBar.setKeyProgressIncrement(5);
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) { face.style.cardAlpha = 20 + p; face.invalidate(); } }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = dp(8); lp.rightMargin = dp(8);
        row.addView(sizeBar, lp);
        row.addView(sheetText("\u25A0", 16, 0xFFF5F5F7));   // solid square
        return row;
    }

    // ---- wallpaper ----
    private boolean havePhoto() { return AndroidPen.photoFile(this).exists(); }

    private View backgroundTab() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(0, dp(8), 0, dp(8));
        colorChips.clear();
        for (int i = 0; i < Style.BGS.length; i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            cell.setPadding(dp(4), dp(4), dp(4), dp(4));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(10));
            g.setStroke(dp(1), 0x44FFFFFF);
            if (i == Style.PHOTO) g.setColor(0xFF3A3F4A);
            else { g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM); g.setColors(new int[]{0xFF000000 | Style.BGS[i][0], 0xFF000000 | Style.BGS[i][1]}); }
            View sw = new View(this);
            sw.setBackground(g);
            cell.addView(sw, new FrameLayout.LayoutParams(dp(44), dp(64)));
            if (i == Style.PHOTO) cell.addView(sheetText("photo", 10, 0xFFF5F5F7), new FrameLayout.LayoutParams(dp(44), dp(64)));
            focusable(cell);
            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (idx == Style.PHOTO && !havePhoto()) { changePhoto(); return; }
                    face.style.bg = idx;
                    if (idx == Style.PHOTO) face.style.photo = 1;
                    face.invalidate(); refreshChips();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(4);
            strip.addView(cell, lp);
            colorChips.add(cell);
        }
        hs.addView(strip);
        return hs;
    }

    private View dimTab() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), 0, dp(4), 0);
        row.addView(sheetText("☀", 18, 0xFFF5F5F7));
        sizeBar = new SeekBar(this);
        sizeBar.setMax(80);
        sizeBar.setProgress(face.style.dim);
        sizeBar.setKeyProgressIncrement(5);
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) { face.style.dim = p; face.invalidate(); } }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = dp(8); lp.rightMargin = dp(8);
        row.addView(sizeBar, lp);
        row.addView(sheetText("☾", 22, 0xFFB7BFCC));
        return row;
    }

    private View photoTab() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button b = button(havePhoto() ? "Choose a different photo" : "Choose a photo", true);
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { changePhoto(); } });
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        TextView hint = sheetText("Asks for your PIN first", 12, 0xFFB7BFCC);
        hint.setPadding(dp(10), 0, 0, 0);
        row.addView(hint, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    /** The photo picker can't open over the lock screen, so save, unlock, then open Facet's picker. */
    private void changePhoto() {
        face.style.bg = Style.PHOTO;
        saveEdit();
        afterUnlockIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("pick_photo", true);
        unlock();
    }

    private View fontTab() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(0, dp(8), 0, dp(8));
        fontChips.clear();
        boolean dates = editTarget == 1;
        int first = dates ? -1 : 0;     // the dates can also follow the clock's font
        for (int i = first; i < Style.FONTS.length; i++) {
            final int idx = i;
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            TextView sample = sheetText(i < 0 ? "Aa" : "10:09", 28, 0xFFF5F5F7);
            sample.setTypeface(i < 0 ? AndroidPen.face(this, Face.CLOCK_FONTS[face.style.font]) : AndroidPen.face(this, Face.CLOCK_FONTS[i]));
            chip.addView(sample);
            chip.addView(sheetText(i < 0 ? "same as clock" : Style.FONTS[i], 11, 0xFFB7BFCC));
            focusable(chip);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (editTarget == 1) face.style.dateFont = idx + 1; else face.style.font = idx;
                    face.invalidate(); refreshChips();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            strip.addView(chip, lp);
            fontChips.add(chip);
        }
        hs.addView(strip);
        return hs;
    }

    private View colorTab() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(0, dp(10), 0, dp(10));
        colorChips.clear();
        for (int i = 0; i <= Style.COLORS.length; i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            cell.setPadding(dp(4), dp(4), dp(4), dp(4));
            View dot = new View(this);
            if (i == Style.CLEAR) dot.setBackground(ring(0x33FFFFFF, 0xCCFFFFFF, 2, 20));
            else dot.setBackground(ring(0xFF000000 | Style.COLORS[i], 0x44FFFFFF, 1, 20));
            cell.addView(dot, new FrameLayout.LayoutParams(dp(40), dp(40)));
            if (i == Style.CLEAR) {
                TextView l = sheetText("clear", 10, 0xFFF5F5F7);
                cell.addView(l, new FrameLayout.LayoutParams(dp(40), dp(40)));
            }
            focusable(cell);
            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { face.style.clockCol = idx; face.invalidate(); refreshChips(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(4);
            strip.addView(cell, lp);
            colorChips.add(cell);
        }
        hs.addView(strip);
        return hs;
    }

    /** One row of colours for the date. */
    private View datesTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        hebChips.clear(); dateChips.clear();
        col.addView(dateRow("Date", false));
        return col;
    }

    private View dateRow(String label, final boolean heb) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = sheetText(label, 13, 0xFFB7BFCC);
        t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.addView(t, new LinearLayout.LayoutParams(dp(56), -2));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < Style.COLORS.length; i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            cell.setPadding(dp(3), dp(3), dp(3), dp(3));
            View dot = new View(this);
            dot.setBackground(ring(0xFF000000 | Style.COLORS[i], 0x44FFFFFF, 1, 16));
            cell.addView(dot, new FrameLayout.LayoutParams(dp(30), dp(30)));
            focusable(cell);
            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (heb) face.style.hebCol = idx; else face.style.dateCol = idx;
                    face.invalidate(); refreshChips();
                }
            });
            strip.addView(cell, new LinearLayout.LayoutParams(-2, -2));
            (heb ? hebChips : dateChips).add(cell);
        }
        hs.addView(strip);
        row.addView(hs, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private View sizeTab() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), 0, dp(4), 0);
        row.addView(sheetText("A", 14, 0xFFB7BFCC));
        sizeBar = new SeekBar(this);
        int lo = editTarget == 0 ? 60 : 40;
        sizeBar.setMax(editTarget == 0 ? 200 : 210);
        sizeBar.setProgress((editTarget == 0 ? face.style.clockSize : face.style.dateSize) - lo);
        sizeBar.setKeyProgressIncrement(5);
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) setSize(p + (editTarget == 0 ? 60 : 40)); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = dp(8); lp.rightMargin = dp(8);
        row.addView(sizeBar, lp);
        row.addView(sheetText("A", 30, 0xFFF5F5F7));
        return row;
    }

    private Button moveButton(String label, final int dx, final int dy) {
        Button b = button(label, false);
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (dx == 0 && dy == 0) { initPos(); setXY(500, curY()); face.invalidate(); }
                else nudge(dx, dy);
            }
        });
        return b;
    }

    private View moveTab() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String[] labels = {"◀", "▲", "▼", "▶", "Center", "Reset"};
        int[][] d = {{-20, 0}, {0, -20}, {0, 20}, {20, 0}, {0, 0}, {0, 0}};
        for (int i = 0; i < labels.length; i++) {
            Button b = moveButton(labels[i], d[i][0], d[i][1]);
            if (i == 5) b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (editTarget == 0) { face.style.clockX = -1; face.style.clockY = -1; } else { face.style.dateX = -1; face.style.dateY = -1; }
                    face.invalidate();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, i >= 4 ? 1.6f : 1);
            if (i > 0) lp.leftMargin = dp(5);
            row.addView(b, lp);
        }
        return row;
    }

    private void refreshChips() {
        Style st = face.style;
        for (int i = 0; i < hebChips.size(); i++) hebChips.get(i).setBackground(ring(0, 0xFFE3B85A, i == st.hebCol ? 2 : 0, 20));
        for (int i = 0; i < dateChips.size(); i++) dateChips.get(i).setBackground(ring(0, 0xFFE3B85A, i == st.dateCol ? 2 : 0, 20));
        int picked = editTarget == 1 ? st.dateFont : st.font;   // chips start at "same as clock" for the dates
        for (int i = 0; i < fontChips.size(); i++) fontChips.get(i).setBackground(ring(i == picked ? 0x22E3B85A : 0x11FFFFFF, 0xFFE3B85A, i == picked ? 2 : 0, 12));
        for (int i = 0; i < colorChips.size(); i++) { boolean sel = i == (editKind == 1 ? st.bg : st.clockCol); colorChips.get(i).setBackground(ring(0, 0xFFE3B85A, sel ? 2 : 0, editKind == 1 ? 14 : 24)); }
    }

    private void springBack() {
        animate(face.lift, 0, new Setter() { public void set(float v) { face.lift = v; } });
    }

    private interface Setter { void set(float v); }

    private void animate(float from, final float to, final Setter s) {
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(200);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator va) {
                float v = (Float) va.getAnimatedValue();
                s.set(va.getAnimatedFraction() >= 1 ? to : v);
                face.invalidate();
            }
        });
        a.start();
    }

    // ---------------- notifications ----------------
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable bg(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(primary ? 0xFF10131A : 0xFFF5F5F7);
        b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        b.setBackground(bg(primary ? 0xFFE3B85A : 0xFF2A3142, 12));
        b.setMinHeight(dp(48));
        b.setFocusable(true);
        return b;
    }

    private void showKeyboard(final View v) {
        handler.postDelayed(new Runnable() {
            public void run() {
                InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(v, 0);
            }
        }, 150);
    }

    private void finishAction(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        closePanel();
    }

    private void closePanel() {
        if (panel == null) return;
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null && replyBox != null) im.hideSoftInputFromWindow(replyBox.getWindowToken(), 0);
        root.removeView(panel);
        panel = null; panelNote = null; replyBox = null;
        face.selected = -1;
        face.invalidate();
    }

    // ---------------- unlocking ----------------
    private void unlock() {
        if (unlocking) return;
        unlocking = true;
        final KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardLocked()) { done(); return; }
        // Ask Android to dismiss its own lock screen: instantly if there's no PIN, otherwise it asks for it.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        try { // Android 8+: the proper request (no callback needed)
            KeyguardManager.class.getMethod("requestDismissKeyguard", Activity.class,
                    Class.forName("android.app.KeyguardManager$KeyguardDismissCallback")).invoke(km, this, null);
        } catch (Throwable ignored) { }
        handler.post(new Runnable() {
            int tries = 0, unlockedFor = 0;
            public void run() {
                // Only leave once Android's lock screen has stayed gone; closing too early lets it come back.
                if (!km.isKeyguardLocked()) { if (++unlockedFor >= 6) { done(); return; } }
                else unlockedFor = 0;
                if (++tries < 600) handler.postDelayed(this, 50);
                else { unlocking = false; openAfterUnlock = null; face.lift = 0; face.invalidate(); }
            }
        });
    }

    private void done() {
        Face.Note note = openAfterUnlock;
        openAfterUnlock = null;
        if (afterUnlockIntent != null) {
            try { startActivity(afterUnlockIntent); } catch (Exception ignored) { }
            afterUnlockIntent = null;
        }
        if (note != null && note.open instanceof PendingIntent) {
            try { ((PendingIntent) note.open).send(); } catch (Exception ignored) { }
            if (note.autoCancel) NotifListener.dismiss(note);
        }
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    // ---------------- keypad ----------------
    /** Keys while a card is expanded: up/down move between buttons, middle presses, left closes. */
    private boolean panelKey(KeyEvent e) {
        boolean typing = replyBox != null && replyBox.hasFocus();
        int code = Facet.translate(e.getKeyCode());
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) { if (!down) back(); return true; }
        if (typing && !Facet.isMedia(e.getKeyCode())) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) { if (down) { View f = replyBox.focusSearch(View.FOCUS_DOWN); if (f != null) f.requestFocus(); } return true; }
            return super.dispatchKeyEvent(e);
        }
        View cur = getCurrentFocus();
        if (cur instanceof SeekBar && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            return super.dispatchKeyEvent(new KeyEvent(e.getAction(), code));   // left/right change the size
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    int dir = code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : View.FOCUS_UP;
                    View n = cur == null ? null : cur.focusSearch(dir);
                    if (n != null) n.requestFocus();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) {
                    // left/right hop between side-by-side buttons; left from the first one closes
                    View n = cur == null ? null : cur.focusSearch(code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                    if (n != null && n != cur) n.requestFocus();
                    else if (code == KeyEvent.KEYCODE_DPAD_LEFT) back();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!down && cur != null) cur.performClick();
                return true;
        }
        if (Facet.isMedia(e.getKeyCode())) return true;
        return super.dispatchKeyEvent(e);
    }

    /** The Sundial's three buttons, which a pocket presses all by itself. */
    private static boolean isKeypad(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = Facet.translate(e.getKeyCode());
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // Keypad off: the buttons do nothing here, and are still swallowed so a press in a pocket
        // can't reach the music app either. Everything else (volume, power) carries on as usual.
        if (face.style == null || face.style.keypad == 0) {
            if (list != null && list.isTyping()) return super.dispatchKeyEvent(e);   // writing a reply
            if (isKeypad(code) || Facet.isMedia(e.getKeyCode())) return true;
            return super.dispatchKeyEvent(e);
        }
        if (panel != null || editing) return panelKey(e);
        View cur = getCurrentFocus();
        boolean inList = cur != null && cur != list && inside(cur, list);
        if (list.isTyping() && !Facet.isMedia(e.getKeyCode())) return super.dispatchKeyEvent(e);   // writing a reply
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    if (!inList) list.focusFirst();
                    else { View n = cur.focusSearch(View.FOCUS_DOWN); if (n != null && n != list && inside(n, list)) n.requestFocus(); }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    if (!inList) unlock();
                    else {
                        View n = cur.focusSearch(View.FOCUS_UP);
                        if (n != null && n != list && inside(n, list)) n.requestFocus(); else list.requestFocus();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) centerHeld = false;
                    else if (!centerHeld && inList) { centerHeld = true; cur.performLongClick(); }   // hold = the card's controls
                } else {
                    if (centerHeld) centerHeld = false;
                    else if (inList) list.centerPress(cur);
                    else unlock();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && inList) {
                    if (cur.getTag() instanceof NoteList.Tag) {
                        // sideways skips a track on a player, and clears any other card
                        if (!list.mediaKey(cur, code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)) list.clearFocused(cur);
                    }
                    else {
                        View n = cur.focusSearch(code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                        if (n != null && n != list && inside(n, list)) n.requestFocus();
                    }
                }
                return true;
        }
        if (Facet.isMedia(e.getKeyCode())) return true; // don't start music from the lock screen
        return super.dispatchKeyEvent(e);
    }
}
