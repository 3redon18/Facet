package com.facet.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The notifications on the lock screen, built from real views the way Android's own lock screen does it:
 *  - tap a card to open it up in place (the whole conversation, plus Reply / Mark as read and the app's other buttons)
 *  - hold a card for its controls
 *  - several notifications from one app are stacked into one card; tap the stack to spread them out
 *  - swipe a card sideways to clear it
 */
final class NoteList extends ScrollView {
    interface Host {
        void open(Face.Note n);          // unlock, then open the app
        void settings(Face.Note n);      // unlock, then open the app's notification settings
        void toast(String s);
        void showKeyboard(View v);
        void replying(boolean on);   // drop/restore fullscreen so the keyboard can show the reply box
        void hideKeyboard(View v);
    }

    /** What a card remembers about itself, so keys and swipes know what they're acting on. */
    static final class Tag {
        final Face.Note note;
        final List<Face.Note> stack;     // non-null for a collapsed stack of several notifications
        Tag(Face.Note n, List<Face.Note> s) { note = n; stack = s; }
    }

    /** Corner radius of a card, in dp: Android's own notifications are very round. */
    private static final float RADIUS = 24;

    private final Host host;
    private final LinearLayout col;
    private final float density;
    private Style st = new Style();
    private int cardBg, main, sub, accent;
    private boolean lightCards;
    private List<Face.Note> notes = new ArrayList<Face.Note>();
    private final Set<String> expanded = new HashSet<String>();
    private final Set<String> openStacks = new HashSet<String>();
    private String gutsKey, replyKey;
    private int replyIdx;
    private String draft = "";
    private EditText replyBox;
    private boolean touchingCard;
    /** A real double-tap window, in ms: a slower second tap is a separate tap, not "open". */
    private static final long DOUBLE_TAP = 450;
    private String lastTapKey;
    private long lastTapAt;
    // the seek bar on a player, and the once-a-second nudge that keeps it moving
    private android.widget.SeekBar seekBar;
    private TextView seekElapsed;
    private Face.Note seekNote;
    private boolean seekDragging;
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tickSeek = new Runnable() {
        public void run() {
            updateSeek();
            ticker.postDelayed(this, 1000);
        }
    };

    NoteList(Context c, Host h) {
        super(c);
        host = h;
        density = c.getResources().getDisplayMetrics().density;
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFillViewport(false);
        // the list itself holds the keypad focus when no card is picked, so a card isn't picked by accident
        setFocusable(true);
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        try { View.class.getMethod("setDefaultFocusHighlightEnabled", boolean.class).invoke(this, false); } catch (Throwable ignored) { }
        setClipToPadding(false);
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(dp(26));
        setPadding(0, 0, 0, dp(6));
        col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        addView(col, new LayoutParams(-1, -2));
    }

    private int dp(float v) { return (int) (v * density + 0.5f); }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ticker.post(tickSeek);
    }

    @Override
    protected void onDetachedFromWindow() {
        ticker.removeCallbacks(tickSeek);          // nothing ticks once the lock screen is gone
        super.onDetachedFromWindow();
    }

    /** Walks the seek bar forward without rebuilding the card, which would lose your place. */
    private void updateSeek() {
        if (seekBar == null || seekNote == null || seekDragging) return;
        long[] pr = NotifListener.mediaProgress(getContext(), seekNote);
        if (pr == null) return;
        seekBar.setMax((int) (pr[1] / 1000));
        seekBar.setProgress((int) (pr[0] / 1000));
        if (seekElapsed != null) seekElapsed.setText(clock(pr[0]));
    }

    /** 9:05, or 1:09:05 for something long. */
    private static String clock(long ms) {
        long t = ms / 1000, h = t / 3600, m = (t % 3600) / 60, sec = t % 60;
        if (h > 0) return h + ":" + (m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec;
        return m + ":" + (sec < 10 ? "0" : "") + sec;
    }

    boolean canScroll() {
        View child = getChildAt(0);
        return child != null && child.getHeight() > getHeight() - getPaddingTop() - getPaddingBottom();
    }

    boolean atBottom() {
        View child = getChildAt(0);
        return child == null || getScrollY() + getHeight() >= child.getHeight() - dp(4);
    }

    // ---------------- data in ----------------
    void setNotes(List<Face.Note> n, Style style) {
        notes = n;
        st = style;
        int[] cc = Face.cardColors(style);
        int br = (cc[0] >> 16) & 255, bgc = (cc[0] >> 8) & 255, bbc = cc[0] & 255;
        lightCards = (br * 299 + bgc * 587 + bbc * 114) / 1000 > 150;   // a light card needs dark buttons
        cardBg = (Math.round(cc[1] * 2.55f) << 24) | cc[0];
        main = 0xFF000000 | cc[2];
        sub = 0xFF000000 | cc[3];
        accent = lightCards ? 0xFF8A5A00 : 0xFFE3B85A;
        rebuild();
    }

    private String scrollTo;     // card to bring into view after the next rebuild

    private void rebuild() {
        if (replyBox != null) draft = replyBox.getText().toString();
        View f = findFocus();
        Object focusTag = f == null ? null : f.getTag();
        String focusKey = focusTag instanceof Tag ? ((Tag) focusTag).note.key : null;
        col.removeAllViews();
        replyBox = null;
        seekBar = null; seekElapsed = null; seekNote = null; seekDragging = false;

        // forget state for notifications that are gone
        Set<String> live = new HashSet<String>();
        for (Face.Note n : notes) live.add(n.key);
        expanded.retainAll(live);
        if (gutsKey != null && !live.contains(gutsKey)) gutsKey = null;
        if (replyKey != null && !live.contains(replyKey)) { replyKey = null; host.replying(false); }

        // group by app, newest first
        Map<String, List<Face.Note>> byApp = new LinkedHashMap<String, List<Face.Note>>();
        for (Face.Note n : notes) {
            // players and message threads each stand on their own; other apps still bundle together
            String k = (n.media || n.messaging) ? n.key : (n.pkg == null ? n.app : n.pkg);
            List<Face.Note> g = byApp.get(k);
            if (g == null) { g = new ArrayList<Face.Note>(); byApp.put(k, g); }
            g.add(n);
        }
        for (Map.Entry<String, List<Face.Note>> e : byApp.entrySet()) {
            List<Face.Note> g = e.getValue();
            View v;
            if (g.size() == 1) v = card(g.get(0), null);
            else if (openStacks.contains(e.getKey())) v = openStack(e.getKey(), g);
            else v = collapsedStack(e.getKey(), g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(6);
            col.addView(v, lp);
        }
        addClearAll();
        if (focusKey != null) {
            View again = findByKey(col, focusKey);
            if (again != null) again.requestFocus();
        }
        if (replyBox != null && replyKey != null && draft.length() > 0) replyBox.setText(draft);
        final String want = scrollTo;
        scrollTo = null;
        if (want != null) post(new Runnable() {
            public void run() {
                View v = findByKey(col, want);
                if (v == null) return;
                View top = v;
                while (top.getParent() != col && top.getParent() instanceof View) top = (View) top.getParent();
                int y = top.getTop(), h = top.getHeight();
                int visible = getHeight() - getPaddingBottom();
                // show the whole card if it fits, otherwise start at its top
                smoothScrollTo(0, h <= visible ? Math.max(0, y + h - visible) : Math.max(0, y - dp(2)));
            }
        });
    }

    /** The "Clear all" button Android puts under the stack, when there's anything clearable to clear. */
    private void addClearAll() {
        final List<Face.Note> clearable = new ArrayList<Face.Note>();
        for (Face.Note n : notes) if (n.clearable && !n.media) clearable.add(n);   // ongoing notifications and players stay
        if (clearable.size() < 2) return;                                          // one card already has its own swipe
        TextView b = text("Clear all", 13, main, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setBackground(cardBackground(RADIUS));
        b.setFocusable(true);
        b.setClickable(true);
        b.setTag("pill");
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { for (Face.Note n : clearable) NotifListener.dismiss(n); }
        });
        LinearLayout wrap = new LinearLayout(getContext());
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding(0, dp(2), 0, dp(4));
        wrap.addView(b, new LinearLayout.LayoutParams(-2, -2));
        col.addView(wrap, new LinearLayout.LayoutParams(-1, -2));
    }

    private View findByKey(View v, String key) {
        Object t = v.getTag();
        if (t instanceof Tag && ((Tag) t).note.key.equals(key)) return v;
        if (t instanceof String && t.equals(key)) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByKey(g.getChildAt(i), key);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ---------------- looks ----------------
    private GradientDrawable shape(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private StateListDrawable cardBackground(float radiusDp) { return cardBackground(radiusDp, false); }

    private StateListDrawable cardBackground(float radiusDp, boolean armed) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable focus = shape(cardBg, radiusDp);
        focus.setStroke(dp(2), accent);
        s.addState(new int[]{android.R.attr.state_focused}, focus);
        if (armed) {
            // lit up, so it's clear the card is waiting for a second tap
            GradientDrawable lit = shape(cardBg, radiusDp);
            lit.setStroke(dp(2), accent);
            s.addState(new int[]{}, lit);
        } else {
            s.addState(new int[]{}, shape(cardBg, radiusDp));
        }
        return s;
    }

    /**
     * Android's lock screen never opens an app on one tap. A single tap opens or closes the preview;
     * only a quick second tap goes to the app. A slow second tap (e.g. to close what you just opened)
     * does NOT open or unlock -- that was the bug.
     */
    private boolean doubleTapOpen(Face.Note n) {
        long now = android.os.SystemClock.uptimeMillis();
        boolean quick = n.key.equals(lastTapKey) && now - lastTapAt < DOUBLE_TAP;
        lastTapKey = quick ? null : n.key;
        lastTapAt = now;
        return quick;
    }

    /** True if this finger-down is the second tap of a double tap; opens the app right away, without waiting for the finger to lift. */
    private boolean secondTapDown(Face.Note n) {
        if (!n.key.equals(lastTapKey) || android.os.SystemClock.uptimeMillis() - lastTapAt >= DOUBLE_TAP) return false;
        lastTapKey = null;
        host.open(n);
        return true;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** A small rounded button (Reply, Mark as read, Snooze...). */
    private TextView pill(String label, boolean primary) {
        TextView t = text(label, 13, primary ? (lightCards ? 0xFFFFFFFF : 0xFF10131A) : main, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(10), dp(7), dp(10), dp(7));
        int fill = primary ? accent : (lightCards ? 0x1A000000 : 0x26FFFFFF);
        StateListDrawable s = new StateListDrawable();
        GradientDrawable focus = shape(fill, 14);
        focus.setStroke(dp(2), lightCards ? 0xFF15171C : 0xFFFFFFFF);
        s.addState(new int[]{android.R.attr.state_focused}, focus);
        s.addState(new int[]{android.R.attr.state_pressed}, shape(fill & 0x00FFFFFF | 0x88000000, 14));
        s.addState(new int[]{}, shape(fill, 14));
        t.setBackground(s);
        t.setFocusable(true);
        t.setClickable(true);
        t.setTag("pill");
        return t;
    }

    /** The small line at the top of a card: app icon, app name, time, and the chevron that opens it up. */
    private View header(Face.Note n, String extra, boolean openNow, final Runnable toggle) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (n.messaging && n.avatar instanceof Bitmap) {
            // the contact's photo, round, the way a messaging notification shows it
            ImageView iv = new ImageView(getContext());
            iv.setImageBitmap((Bitmap) n.avatar);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            final int sz = dp(22);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                public void getOutline(View v, android.graphics.Outline o) { o.setOval(0, 0, sz, sz); }
            });
            iv.setClipToOutline(true);
            row.addView(iv, new LinearLayout.LayoutParams(sz, sz));
        } else if (n.icon instanceof Bitmap) {
            ImageView iv = new ImageView(getContext());
            iv.setImageBitmap((Bitmap) n.icon);
            iv.setColorFilter(sub);
            row.addView(iv, new LinearLayout.LayoutParams(dp(14), dp(14)));
        }
        String app = n.app == null ? "" : n.app;
        if (extra != null) app += "  ·  " + extra;
        else if (n.when != null && n.when.length() > 0) app += "  ·  " + n.when;
        TextView t = text(app, 12, sub, false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(7), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (toggle != null) {
            TextView c = text(openNow ? "⌃" : "⌄", 16, sub, false);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(16), 0, dp(2), dp(4));   // a big enough target for a fingertip
            c.setTag("pill");
            c.setClickable(true);
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { toggle.run(); }
            });
            row.addView(c);
        }
        return row;
    }

    // ---------------- one notification ----------------
    private boolean expandable(Face.Note n) {
        if (st.notePrivate == 1) return false;
        return !n.actions.isEmpty() || n.messages.size() > 1 || n.full != null || n.messaging
                || n.picture != null || (n.text != null && n.text.length() > 70);
    }

    /**
     * One notification, laid out the way Android's own does it: tap the card to open the app,
     * tap the chevron to open the card up in place, hold it for its controls, swipe it sideways to clear it.
     * stack != null: this card is the face of a group of notifications from one app.
     */
    private View card(final Face.Note n, final List<Face.Note> stack) {
        if (stack == null && n.key.equals(gutsKey)) return controls(n);
        if (stack == null && n.media) return mediaCard(n);
        final boolean priv = st.notePrivate == 1;
        final boolean exp = stack == null && !priv && expanded.contains(n.key);
        final boolean canExpand = stack == null && expandable(n);

        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(12), dp(12));
        card.setBackground(cardBackground(RADIUS));
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(new Tag(n, stack));

        Runnable toggle = null;
        if (stack != null) toggle = new Runnable() {
            public void run() { openStacks.add(keyOf(n)); rebuild(); }
        };
        else if (canExpand) toggle = new Runnable() {
            public void run() { setExpanded(n, !expanded.contains(n.key)); }
        };
        card.addView(header(n, stack != null ? stack.size() + " notifications" : null, exp, toggle));

        String title = priv ? (n.publicTitle != null ? n.publicTitle : n.app)
                : (n.title != null && n.title.length() > 0 ? n.title : n.app);
        TextView tt = text(title == null ? "" : title, 15, main, true);
        tt.setSingleLine(true);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        tt.setPadding(0, dp(3), 0, 0);
        card.addView(tt);

        if (priv) {
            // Android swaps in the version the app marked safe for a locked screen, if it gave one
            card.addView(text(n.publicText != null ? n.publicText
                    : n.messaging ? "New message" : "New notification", 14, main, false));
        } else if (exp) {
            addExpandedBody(card, n);
        } else {
            TextView body = text(n.text == null ? "" : n.text, 14, main, false);
            body.setMaxLines(2);
            body.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(body);
        }

        if (!priv && n.progress >= 0) card.addView(progressBar(n));
        if (exp && !priv && n.picture instanceof Bitmap) card.addView(picture((Bitmap) n.picture));

        if (exp) addActions(card, n);

        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (stack != null) { openStacks.add(keyOf(n)); rebuild(); return; }
                if (doubleTapOpen(n)) { host.open(n); return; }   // quick second tap opens the app
                if (canExpand) setExpanded(n, !expanded.contains(n.key));   // one tap opens or closes the preview
            }
        });
        if (stack == null) {
            card.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) { gutsKey = n.key; scrollTo = n.key; rebuild(); return true; }
            });
        }
        swipeToClear(card, n, stack);
        return card;
    }

    /** The bar a download or an upload shows while it runs. */
    private View progressBar(Face.Note n) {
        android.widget.ProgressBar b = new android.widget.ProgressBar(getContext(), null,
                android.R.attr.progressBarStyleHorizontal);
        b.setIndeterminate(n.progressSpinning);
        if (!n.progressSpinning) {
            b.setMax(Math.max(1, n.progressMax));
            b.setProgress(Math.max(0, Math.min(n.progressMax, n.progress)));
        }
        b.getProgressDrawable().setColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(5));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** The image a BigPictureStyle notification carries, shown once the card is opened up. */
    private View picture(Bitmap bm) {
        ImageView iv = new ImageView(getContext());
        iv.setImageBitmap(bm);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setAdjustViewBounds(false);
        final float r = dp(12);
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        iv.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(150));
        lp.topMargin = dp(10);
        iv.setLayoutParams(lp);
        return iv;
    }

    private void setExpanded(Face.Note n, boolean open) {
        if (open) { expanded.add(n.key); scrollTo = n.key; }
        else { expanded.remove(n.key); if (n.key.equals(replyKey)) { replyKey = null; host.replying(false); } }
        rebuild();
    }

    /**
     * The middle keypad button. There's no chevron to aim at with a keypad, so the button opens a card up
     * when there's more to see, and opens the app when there isn't.
     */
    boolean centerPress(View focused) {
        if (focused == null) return false;
        Object t = focused.getTag();
        if (!(t instanceof Tag)) { focused.performClick(); return true; }
        Tag tag = (Tag) t;
        if (tag.note.media && NotifListener.transport(getContext(), tag.note, 0)) return true;
        if (tag.stack != null) { openStacks.add(keyOf(tag.note)); rebuild(); return true; }
        if (expanded.contains(tag.note.key)) { setExpanded(tag.note, false); return true; }
        if (expandable(tag.note)) { setExpanded(tag.note, !expanded.contains(tag.note.key)); return true; }
        host.open(tag.note);
        return true;
    }

    private String keyOf(Face.Note n) { return n.pkg == null ? n.app : n.pkg; }

    private void addExpandedBody(LinearLayout card, Face.Note n) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(2), 0, 0);
        if (!n.messages.isEmpty()) {
            int from = Math.max(0, n.messages.size() - 6);
            for (int i = from; i < n.messages.size(); i++) {
                String[] m = n.messages.get(i);
                boolean mine = "You".equals(m[0]);
                SpannableStringBuilder sb = new SpannableStringBuilder();
                if (!m[0].isEmpty() && (mine || n.group)) {
                    sb.append(m[0]).append(": ");
                    sb.setSpan(new StyleSpan(Typeface.BOLD), 0, m[0].length() + 1, 0);
                }
                sb.append(m[1]);
                TextView t = text("", 14, main, false);
                t.setText(sb);
                t.setPadding(0, dp(1), 0, dp(1));
                box.addView(t);
            }
        } else {
            TextView t = text(n.full != null ? n.full : (n.text == null ? "" : n.text), 14, main, false);
            t.setMaxLines(8);
            t.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(t);
        }
        card.addView(box);
    }

    /** Reply, Mark as read and the app's other buttons, with the reply box when Reply is pressed. */
    private void addActions(LinearLayout card, final Face.Note n) {
        if (n.key.equals(replyKey)) {
            LinearLayout r = new LinearLayout(getContext());
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(8), 0, 0);
            final EditText et = new EditText(getContext());
            replyBox = et;
            et.setHint("Reply to " + (n.title == null || n.title.isEmpty() ? n.app : n.title));
            et.setHintTextColor(sub);
            et.setTextColor(main);
            et.setTextSize(14);
            et.setTag("reply");
            et.setBackground(shape(lightCards ? 0x14000000 : 0x26FFFFFF, 18));
            et.setPadding(dp(12), dp(7), dp(12), dp(7));
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            et.setImeOptions(EditorInfo.IME_ACTION_SEND);
            et.setSingleLine(true);
            r.addView(et, new LinearLayout.LayoutParams(0, -2, 1));
            TextView send = pill("➤", true);
            send.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.leftMargin = dp(8);
            r.addView(send, sl);
            final View.OnClickListener sendIt = new View.OnClickListener() {
                public void onClick(View v) {
                    String s = et.getText().toString().trim();
                    if (s.isEmpty()) return;
                    boolean ok = NotifListener.runAction(getContext(), n, replyIdx, s);
                    host.toast(ok ? "Sent" : "That didn't work");
                    host.hideKeyboard(et);
                    replyKey = null; draft = "";
                    host.replying(false);
                    rebuild();
                }
            };
            send.setOnClickListener(sendIt);
            et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                    if (actionId == EditorInfo.IME_ACTION_SEND) { sendIt.onClick(v); return true; }
                    return false;
                }
            });
            card.addView(r);
            et.post(new Runnable() {
                public void run() { et.requestFocus(); host.showKeyboard(et); }
            });
        }
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        int count = 0;
        int widest = Math.round(getWidth() * 0.42f);
        for (int i = 0; i < n.actions.size() && count < 2; i++) {
            final int idx = i;
            final boolean reply = n.actionReply.get(i);
            TextView p = pill(n.actionLabels.get(i), reply);
            if (widest > 0) p.setMaxWidth(widest);
            p.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (reply) { replyKey = n.key; replyIdx = idx; draft = ""; scrollTo = n.key; host.replying(true); rebuild(); }
                    else {
                        boolean ok = NotifListener.runAction(getContext(), n, idx, null);
                        host.toast(ok ? "Done" : "That didn't work");
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (count > 0) lp.leftMargin = dp(8);
            row.addView(p, lp);
            count++;
        }
        TextView open = pill("Open", false);
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.open(n); }
        });
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-2, -2);
        if (count > 0) ol.leftMargin = dp(8);
        row.addView(open, ol);        card.addView(row);
    }

    // ---------------- music and podcast players ----------------
    /**
     * A player, the way Android shows one: the album art fills the card behind a scrim, with the track,
     * the artist and the player's own buttons on top. Swiping does nothing, because a player is ongoing.
     */
    private View mediaCard(final Face.Note n) {
        final boolean priv = st.notePrivate == 1;
        boolean hasArt = n.art instanceof Bitmap;
        int tint = n.tint != 0 ? n.tint : accent;

        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(11), dp(12), dp(11));
        // the card keeps the normal look, with a faint wash of the artwork's own colour over it
        GradientDrawable bg = shape(cardBg, RADIUS);
        LayerDrawable lay = hasArt
                ? new LayerDrawable(new android.graphics.drawable.Drawable[]{bg, washFrom(tint)})
                : null;
        box.setBackground(lay != null ? lay : (android.graphics.drawable.Drawable) shape(cardBg, RADIUS));
        box.setFocusable(true);
        box.setClickable(true);
        box.setTag(new Tag(n, null));

        // ---- top row: the artwork as a crisp rounded thumbnail, with the track beside it ----
        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        final int thumb = dp(58);
        final float tr = dp(12);
        FrameLayout artWrap = new FrameLayout(getContext());
        artWrap.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), tr); }
        });
        artWrap.setClipToOutline(true);
        if (hasArt) {
            ImageView art = new ImageView(getContext());
            art.setImageBitmap((Bitmap) n.art);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);   // album art is square, so this shows it whole
            artWrap.addView(art, new FrameLayout.LayoutParams(-1, -1));
        } else {
            View ph = new View(getContext());
            ph.setBackground(shape((tint & 0xFFFFFF) | 0x55000000, 12));
            artWrap.addView(ph, new FrameLayout.LayoutParams(-1, -1));
            TextView note = text("\u266A", 22, main, true);     // a music note when there's no cover
            note.setGravity(Gravity.CENTER);
            artWrap.addView(note, new FrameLayout.LayoutParams(-1, -1));
        }
        top.addView(artWrap, new LinearLayout.LayoutParams(thumb, thumb));

        LinearLayout txt = new LinearLayout(getContext());
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(12), 0, 0, 0);
        TextView appn = text(n.app == null ? "" : n.app, 12, sub, false);
        appn.setSingleLine(true); appn.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(appn);
        String title = priv ? n.app : (n.title != null && n.title.length() > 0 ? n.title : n.app);
        TextView tt = text(title == null ? "" : title, 16, main, true);
        tt.setSingleLine(true); tt.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(tt);
        if (!priv && n.text != null && n.text.length() > 0) {
            TextView ar = text(n.text, 13, sub, false);
            ar.setSingleLine(true); ar.setEllipsize(TextUtils.TruncateAt.END);
            txt.addView(ar);
        }
        top.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(top);

        // ---- the seek bar ----
        final long[] pr = priv ? null : NotifListener.mediaProgress(getContext(), n);
        if (pr != null) {
            final android.widget.SeekBar sb = new android.widget.SeekBar(getContext());
            styleSeek(sb);
            sb.setMax((int) (pr[1] / 1000));
            sb.setProgress((int) (pr[0] / 1000));
            final TextView left = text(clock(pr[0]), 11, sub, false);
            sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(android.widget.SeekBar b, int v, boolean fromUser) {
                    if (fromUser) left.setText(clock(v * 1000L));
                }
                public void onStartTrackingTouch(android.widget.SeekBar b) {
                    seekDragging = true;
                    requestDisallowInterceptTouchEvent(true);
                }
                public void onStopTrackingTouch(android.widget.SeekBar b) {
                    seekDragging = false;
                    NotifListener.mediaSeek(getContext(), n, b.getProgress() * 1000L);
                }
            });
            box.addView(sb, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout times = new LinearLayout(getContext());
            times.setOrientation(LinearLayout.HORIZONTAL);
            times.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
            times.addView(text(clock(pr[1]), 11, sub, false));
            box.addView(times);
            seekBar = sb; seekElapsed = left; seekNote = n;
        }

        // ---- the transport buttons, centred under the bar ----
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(pr != null ? 6 : 10), 0, 0);
        int shown = 0;
        for (int i = 0; i < n.actionIcons.size() && shown < 5; i++) {
            Object ic = n.actionIcons.get(i);
            if (!(ic instanceof android.graphics.drawable.Drawable)) continue;
            final int idx = i;
            row.addView(mediaButton(((android.graphics.drawable.Drawable) ic).mutate(),
                    new View.OnClickListener() { public void onClick(View v) { NotifListener.runAction(getContext(), n, idx, null); } },
                    shown > 0));
            shown++;
        }
        // the app's icons didn't load (some players don't share them): fall back to standard controls that always work
        if (shown == 0 && n.session instanceof android.media.session.MediaSession.Token) {
            boolean playing = pr != null && pr[2] == 1;
            row.addView(mediaButton(sysIcon(android.R.drawable.ic_media_previous),
                    new View.OnClickListener() { public void onClick(View v) { NotifListener.transport(getContext(), n, -1); } }, false));
            row.addView(mediaButton(sysIcon(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                    new View.OnClickListener() { public void onClick(View v) { if (NotifListener.transport(getContext(), n, 0)) postDelayed(new Runnable(){ public void run(){ rebuild(); } }, 300); } }, true));
            row.addView(mediaButton(sysIcon(android.R.drawable.ic_media_next),
                    new View.OnClickListener() { public void onClick(View v) { NotifListener.transport(getContext(), n, 1); } }, true));
            shown = 3;
        }
        if (shown > 0) box.addView(row);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (doubleTapOpen(n)) host.open(n); }
        });
        box.setOnTouchListener(new View.OnTouchListener() {
            boolean ignore;
            public boolean onTouch(View view, MotionEvent e) {
                int a = e.getActionMasked();
                if (ignore) { if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) ignore = false; return true; }
                if (a == MotionEvent.ACTION_DOWN && secondTapDown(n)) { ignore = true; return true; }
                return false;
            }
        });
        box.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { gutsKey = n.key; scrollTo = n.key; rebuild(); return true; }
        });
        return box;
    }

    /** A thin track with a small round thumb, instead of the platform's chunky square one. */
    private void styleSeek(android.widget.SeekBar sb) {
        int h = dp(3);
        GradientDrawable track = new GradientDrawable();
        track.setColor((main & 0xFFFFFF) | 0x40000000);   // faint
        track.setCornerRadius(dp(2)); track.setSize(-1, h);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(main); fill.setCornerRadius(dp(2)); fill.setSize(-1, h);
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                fill, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        LayerDrawable bar = new LayerDrawable(new android.graphics.drawable.Drawable[]{track, clip});
        bar.setId(0, android.R.id.background);
        bar.setId(1, android.R.id.progress);
        sb.setProgressDrawable(bar);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL); thumb.setColor(main); thumb.setSize(dp(13), dp(13));
        sb.setThumb(thumb); sb.setThumbOffset(dp(7));
        sb.setSplitTrack(false);
        sb.setMinimumHeight(h);
        sb.setPadding(0, dp(10), 0, dp(2));
    }

    private ImageView mediaButton(android.graphics.drawable.Drawable icon, View.OnClickListener click, boolean leftGap) {
        ImageView b = new ImageView(getContext());
        b.setImageDrawable(icon);
        b.setColorFilter(main);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setBackground(shape(0, RADIUS));
        b.setClickable(true);
        b.setTag("pill");
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        if (leftGap) lp.leftMargin = dp(16);
        b.setLayoutParams(lp);
        return b;
    }

    private android.graphics.drawable.Drawable sysIcon(int res) {
        return getResources().getDrawable(res, null).mutate();
    }

        /** A faint top-to-bottom wash of the artwork colour, laid over the card. */
    private GradientDrawable washFrom(int color) {
        int c = color & 0xFFFFFF;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x33000000 | c, 0x0D000000 | c});     // ~20% fading to ~5%
        g.setCornerRadius(dp(RADIUS));
        return g;
    }

    /** Keypad on a player: sideways skips a track, the middle button plays or pauses. */
    boolean mediaKey(View focused, int dir) {
        if (focused == null || !(focused.getTag() instanceof Tag)) return false;
        Face.Note n = ((Tag) focused.getTag()).note;
        return n.media && NotifListener.transport(getContext(), n, dir);
    }

    // ---------------- hold: the controls ----------------
    private View controls(final Face.Note n) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(shape(cardBg, 18));
        card.setTag(new Tag(n, null));

        LinearLayout head = new LinearLayout(getContext());
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (n.icon instanceof Bitmap) {
            ImageView iv = new ImageView(getContext());
            iv.setImageBitmap((Bitmap) n.icon);
            iv.setColorFilter(main);
            head.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
        }
        LinearLayout names = new LinearLayout(getContext());
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(12), 0, 0, 0);
        boolean convo = n.messaging && n.title != null && n.title.length() > 0;
        TextView a = text(convo ? n.title : n.app, 16, main, true);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(a);
        names.addView(text(convo ? n.app : "Notifications from this app", 12, sub, false));
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(head);

        TextView label = text("Snooze", 12, sub, true);
        label.setPadding(0, dp(8), 0, dp(3));
        card.addView(label);
        LinearLayout snooze = new LinearLayout(getContext());
        String[] names2 = {"15 min", "1 hour", "2 hours"};
        final long[] ms = {15 * 60000L, 60 * 60000L, 120 * 60000L};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            TextView p = pill(names2[i], false);
            p.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean ok = NotifListener.snooze(n, ms[k]);
                    host.toast(ok ? "Snoozed" : "Android wouldn't snooze it");
                    gutsKey = null;
                    rebuild();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = dp(8);
            snooze.addView(p, lp);
        }
        card.addView(snooze);

        TextView note = text("Only watch-paired apps can switch a category to Silent. Settings opens it for you.", 11, sub, false);
        note.setPadding(0, dp(6), 0, 0);
        card.addView(note);

        LinearLayout row = new LinearLayout(getContext());
        row.setPadding(0, dp(8), 0, 0);
        TextView settings = pill("Settings", false);
        settings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { gutsKey = null; host.settings(n); }
        });
        TextView done = pill("Done", true);
        done.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { gutsKey = null; rebuild(); }
        });
        row.addView(settings, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -2, 1);
        dl.leftMargin = dp(8);
        row.addView(done, dl);
        card.addView(row);
        return card;
    }

    // ---------------- stacks ----------------
    private View collapsedStack(final String key, List<Face.Note> g) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(card(g.get(0), g));
        // the edges of the cards underneath, so a group looks like a stack of paper
        for (int i = 1; i <= Math.min(2, g.size() - 1); i++) {
            View strip = new View(getContext());
            GradientDrawable sd = new GradientDrawable();
            sd.setColor((Math.round((cardBg >>> 24) * (i == 1 ? 0.7f : 0.45f)) << 24) | (cardBg & 0xFFFFFF));
            float r = dp(RADIUS - 6);
            sd.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
            strip.setBackground(sd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(5));
            lp.leftMargin = lp.rightMargin = dp(7 * i);
            lp.topMargin = -dp(2);
            box.addView(strip, lp);
        }
        return box;
    }

    private View openStack(final String key, final List<Face.Note> g) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        Face.Note first = g.get(0);

        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(7), dp(12), dp(7));
        head.setBackground(cardBackground(RADIUS));
        head.setFocusable(true);
        head.setClickable(true);
        head.setTag("stackhead:" + key);
        if (first.icon instanceof Bitmap) {
            ImageView iv = new ImageView(getContext());
            iv.setImageBitmap((Bitmap) first.icon);
            iv.setColorFilter(sub);
            head.addView(iv, new LinearLayout.LayoutParams(dp(14), dp(14)));
        }
        TextView t = text((first.app == null ? "" : first.app) + "  ·  " + g.size() + " notifications", 12, sub, false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(7), 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        boolean anyClearable = false;
        for (Face.Note n : g) if (n.clearable) anyClearable = true;
        if (anyClearable) {
            TextView clear = text("Clear all", 12, accent, true);
            clear.setPadding(dp(10), dp(5), dp(10), dp(5));
            clear.setBackground(shape(lightCards ? 0x14000000 : 0x26FFFFFF, 14));
            clear.setTag("pill");
            clear.setFocusable(true);
            clear.setClickable(true);
            clear.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { for (Face.Note n : g) NotifListener.dismiss(n); openStacks.remove(key); }
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.rightMargin = dp(4);
            head.addView(clear, cl);
        }
        TextView chev = text("⌃", 16, sub, false);
        chev.setPadding(dp(10), 0, dp(2), dp(4));
        head.addView(chev);
        head.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openStacks.remove(key); rebuild(); }
        });
        box.addView(head);
        for (Face.Note n : g) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(4);
            box.addView(card(n, null), lp);
        }
        return box;
    }

    // ---------------- swipe to clear ----------------
    private void swipeToClear(final View v, final Face.Note n, final List<Face.Note> stack) {
        final float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop() * 2;
        v.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            boolean swiping, ignore;
            public boolean onTouch(View view, MotionEvent e) {
                if (ignore) { if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) ignore = false; return true; }
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY(); swiping = false; ignore = false;
                        if (stack == null && secondTapDown(n)) { ignore = true; return true; }
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!swiping && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f && n.clearable) {
                            swiping = true;
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                            view.cancelLongPress();
                            view.setPressed(false);
                        }
                        if (swiping) {
                            view.setTranslationX(dx);
                            view.setAlpha(Math.max(0.2f, 1 - Math.abs(dx) / (view.getWidth() * 0.8f)));
                            return true;
                        }
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (swiping) {
                            swiping = false;
                            float dx = view.getTranslationX();
                            long held = e.getEventTime() - e.getDownTime();
                            boolean flung = held > 0 && Math.abs(dx) / held > 0.6f && Math.abs(dx) > slop * 2;   // a quick flick counts
                            if (e.getActionMasked() == MotionEvent.ACTION_UP && (flung || Math.abs(dx) > view.getWidth() * 0.3f)) {
                                dismiss(view, n, stack, dx > 0 ? view.getWidth() : -view.getWidth());
                            } else {
                                view.animate().translationX(0).alpha(1).setDuration(180).start();
                            }
                            return true;
                        }
                        return false;
                }
                return false;
            }
        });
    }

    private void dismiss(View v, final Face.Note n, final List<Face.Note> stack, float to) {
        v.animate().translationX(to).alpha(0).setDuration(160).withEndAction(new Runnable() {
            public void run() {
                if (stack != null) for (Face.Note x : stack) NotifListener.dismiss(x);
                else NotifListener.dismiss(n);
            }
        }).start();
    }

    /** Keypad: left or right on a focused card clears it. Returns true if it did. */
    boolean clearFocused(View focused) {
        if (focused == null || !(focused.getTag() instanceof Tag)) return false;
        Tag t = (Tag) focused.getTag();
        if (!t.note.clearable) return false;
        dismiss(focused, t.note, t.stack, focused.getWidth());
        return true;
    }

    /** Moves focus to the first card (keypad). */
    boolean focusFirst() {
        for (int i = 0; i < col.getChildCount(); i++) {
            View v = col.getChildAt(i);
            View f = firstFocusable(v);
            if (f != null) { f.requestFocus(); return true; }
        }
        return false;
    }

    private View firstFocusable(View v) {
        if (v.isFocusable() && v.getVisibility() == View.VISIBLE) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = firstFocusable(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    boolean isTyping() { return replyBox != null && replyBox.hasFocus(); }

    // ---------------- touches: only grab ones that start on the cards ----------------
    /** True when a touch landed on the cards themselves, rather than the empty space below them. */
    private boolean onContent(MotionEvent e) {
        float y = e.getY() + getScrollY();
        return y >= 0 && y <= col.getHeight() + dp(3);
    }

    /**
     * This has to run here, not in onTouchEvent: a card consumes the first touch itself, so by the time
     * onTouchEvent is reached the finger-down is long gone and there is nothing left to test.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) touchingCard = onContent(e);
        if (!touchingCard) return false;         // empty space: the lock screen behind gets it (hold to edit, swipe to unlock)
        return super.onInterceptTouchEvent(e);   // on the cards: scroll as soon as the finger moves up or down
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) touchingCard = onContent(e);
        if (!touchingCard) return false;
        return super.onTouchEvent(e);
    }
}
