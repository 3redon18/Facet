package com.facet.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Settings, with a live preview of the lock screen at the top. Works by touch or with the Sundial keypad. */
public class MainActivity extends Activity {
    private static final int BG = 0xFF11141B, CARD = 0xFF1A1F2A, TEXT = 0xFFEDE9E0, DIM = 0xFF9AA2B2, ACCENT = 0xFFE3B85A;
    private static final int PICK_PHOTO = 7;

    private Style st;
    private FaceView preview;
    private LinearLayout list;
    private final List<Runnable> refreshers = new ArrayList<Runnable>();
    private TextView permissionNote;
    private View photoRemove;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        st = Facet.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // live preview
        FrameLayout pv = new FrameLayout(this);
        pv.setBackgroundColor(0xFF0A0C10);
        int ph = Math.round(getResources().getDisplayMetrics().heightPixels * 0.30f);
        preview = new FaceView(this);
        preview.preview = true;
        preview.setStyle(st);
        pv.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ph));
        root.addView(pv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ph));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(6), dp(10), dp(24));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        build();
        Facet.refresh(this);
    }

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable statusTick = new Runnable() {
        public void run() {
            for (Runnable r : refreshers) r.run();
            preview.invalidate();
            ui.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        NotifListener.reconnect(this);
        ui.post(statusTick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(statusTick);
        super.onPause();
    }

    private void changed() {
        Facet.save(this, st);
        preview.setStyle(st);
        Facet.refresh(this);
        for (Runnable r : refreshers) r.run();
    }

    // ---------------- building the list ----------------
    private void build() {
        section("Lock screen");
        toggle("Use the custom lock screen", "lockOn");
        final TextView accessNeeded = note("");
        accessNeeded.setFocusable(true);
        accessNeeded.setBackground(rowBg());
        accessNeeded.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askNotificationAccess(); }
        });
        refreshers.add(new Runnable() {
            public void run() {
                boolean need = st.lockOn == 1 && !NotifListener.hasAccess(MainActivity.this);
                accessNeeded.setVisibility(need ? View.VISIBLE : View.GONE);
                accessNeeded.setText("The lock screen needs \"Notification access\" to start when the screen turns off. Press here to allow it.");
                accessNeeded.setTextColor(ACCENT);
            }
        });
        permissionNote = note("");
        permissionNote.setFocusable(true);
        permissionNote.setBackground(rowBg());
        permissionNote.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askOverlay(); }
        });
        refreshers.add(new Runnable() {
            public void run() {
                boolean ok = Settings.canDrawOverlays(MainActivity.this);
                permissionNote.setVisibility(st.lockOn == 1 && !ok ? View.VISIBLE : View.GONE);
                permissionNote.setText("To appear reliably when the screen turns off, the lock screen needs "
                        + "\"Display over other apps\". Press here to allow it.");
                permissionNote.setTextColor(ACCENT);
            }
        });
        toggle("Keypad works the lock screen", "keypad");
        note("Off by default. The three buttons sit where a pocket presses them, and on the lock screen they "
                + "could unlock the phone or clear a notification on their own. Left off, they do nothing here "
                + "and can't start music either, and you work the lock screen by touch.");
        button("Preview it full screen", new Runnable() {
            public void run() {
                startActivity(new Intent(MainActivity.this, LockActivity.class));
            }
        });

        section("Notifications on the lock screen");
        toggle("Show notifications", "notes");
        final TextView access = note("");
        access.setFocusable(true);
        access.setBackground(rowBg());
        access.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askNotificationAccess(); }
        });
        refreshers.add(new Runnable() {
            public void run() {
                boolean ok = NotifListener.hasAccess(MainActivity.this);
                access.setVisibility(st.notes == 1 ? View.VISIBLE : View.GONE);
                access.setText(ok ? "Notification access is on. Your notifications appear under the date: tap one to open it, "
                        + "swipe it sideways to clear it." : "Press here to turn on \"Notification access\" for Facet, "
                        + "so it can show your notifications. They stay on the phone.");
                access.setTextColor(ok ? DIM : ACCENT);
            }
        });
        final TextView status = note("");
        refreshers.add(new Runnable() {
            public void run() {
                status.setVisibility(st.notes == 1 ? View.VISIBLE : View.GONE);
                status.setText(NotifListener.status(MainActivity.this));
            }
        });
        button("Send a test notification", new Runnable() {
            public void run() {
                NotifListener.sendTest(MainActivity.this);
                Toast.makeText(MainActivity.this, "Sent. Watch the preview at the top.", Toast.LENGTH_SHORT).show();
            }
        });
        toggle("Hide what they say", "notePrivate");
        note("Hold the wallpaper on the lock screen to set the notification cards' color, text color and opacity.");

        section("Clock");
        slider("Clock size", "clockSize", 60, 230);
        choice("Font", "font", Style.FONTS);
        toggle("24-hour time", "h24");
        toggle("Show seconds", "secs");
        choice("Position", "pos", new String[]{"upper", "middle"});
        note("Tip: hold the clock on the lock screen to edit it.");
        button("Reset clock position", new Runnable() { public void run() { st.set("clockX", -1); st.set("clockY", -1); changed(); } });
        swatches("Clock color", "clockCol", false);

        section("Background");
        swatches("Background", "bg", true);
        button("Choose a photo…", new Runnable() {
            public void run() { pickPhoto(); }
        });
        photoRemove = button("Remove the photo", new Runnable() {
            public void run() {
                AndroidPen.photoFile(MainActivity.this).delete();
                AndroidPen.forgetPhoto();
                st.photo = 0;
                if (st.bg == Style.PHOTO) st.bg = 1;
                changed();
            }
        });
        refreshers.add(new Runnable() {
            public void run() { photoRemove.setVisibility(st.photo == 1 ? View.VISIBLE : View.GONE); }
        });
        slider("Photo darkness", "dim", 0, 80);

        section("Text under the clock");
        swatches("Text color", "textCol", false);
        choice("Date font", "dateFont", dateFontNames());
        swatches("Date color", "dateCol", false);
        button("Date uses the text color", new Runnable() { public void run() { st.set("dateCol", -1); changed(); } });
        toggle("Show the date", "greg");
        toggle("Battery", "battery");

        section("Reset");
        button("Reset all settings", new Runnable() {
            public void run() {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setMessage("Put every setting back to how it started? Your photo stays on the phone.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Reset", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                Style fresh = new Style();
                                fresh.lockOn = st.lockOn;
                                st.copyFrom(fresh);
                                changed();
                            }
                        }).show();
            }
        });

        note("Keypad in these settings: up and down move through the list, left and right change a setting, "
                + "the middle button selects.\n\nOn the lock screen: swipe up to unlock, tap a notification once to light it up and show the conversation, "
                + "tap it again to open the app, tap the arrow to open it in place, swipe it sideways to clear it, hold the clock or the background to edit.\n\n"
                + "If you use a PIN, the phone still asks for it after you swipe up.");
        for (Runnable r : refreshers) r.run();
    }

    private void section(String title) {
        TextView t = new TextView(this);
        t.setText(title.toUpperCase());
        t.setTextColor(ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setLetterSpacing(0.12f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(6), dp(18), dp(6), dp(6));
        list.addView(t);
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(dp(8), dp(12), dp(8), dp(8));
        list.addView(t);
        return t;
    }

    private StateListDrawable rowBg() {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(CARD);
        normal.setCornerRadius(dp(6));
        GradientDrawable focus = new GradientDrawable();
        focus.setColor(0xFF263046);
        focus.setCornerRadius(dp(6));
        focus.setStroke(dp(2), ACCENT);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focus);
        s.addState(new int[]{android.R.attr.state_pressed}, focus);
        s.addState(new int[]{}, normal);
        return s;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.setBackground(rowBg());
        r.setFocusable(true);
        r.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        list.addView(r, lp);
        return r;
    }

    private TextView label(LinearLayout r, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return t;
    }

    private TextView value(LinearLayout r) {
        TextView v = new TextView(this);
        v.setTextColor(ACCENT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setGravity(Gravity.END);
        r.addView(v);
        return v;
    }

    private void toggle(String text, final String key) {
        final LinearLayout r = row();
        label(r, text);
        final TextView v = value(r);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                st.set(key, st.get(key) == 1 ? 0 : 1);
                if (key.equals("lockOn") && st.lockOn == 1 && !Settings.canDrawOverlays(MainActivity.this))
                    Toast.makeText(MainActivity.this, "One more step: allow \"Display over other apps\" below", Toast.LENGTH_LONG).show();
                changed();
            }
        });
        refreshers.add(new Runnable() {
            public void run() {
                boolean on = st.get(key) == 1;
                v.setText(on ? "● on" : "○ off");
                v.setTextColor(on ? ACCENT : DIM);
            }
        });
    }

    private void choice(String text, final String key, final String[] options) {
        final LinearLayout r = row();
        label(r, text);
        final TextView v = value(r);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { step(key, options.length, 1); }
        });
        r.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View x, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) { step(key, options.length, -1); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { step(key, options.length, 1); return true; }
                return false;
            }
        });
        refreshers.add(new Runnable() {
            public void run() { v.setText("‹ " + options[Math.max(0, Math.min(options.length - 1, st.get(key)))] + " ›"); }
        });
    }

    private void step(String key, int n, int d) {
        st.set(key, ((st.get(key) + d) % n + n) % n);
        changed();
    }

    private void slider(String text, final String key, final int min, final int max) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(6));
        GradientDrawable bg0 = new GradientDrawable();
        bg0.setCornerRadius(dp(6));
        bg0.setColor(CARD);
        box.setBackground(bg0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        list.addView(box, lp);
        LinearLayout top = new LinearLayout(this);
        box.addView(top);
        label(top, text);
        final TextView v = value(top);
        final SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setKeyProgressIncrement(Math.max(1, (max - min) / 20));
        sb.setProgress(st.get(key) - min);
        sb.setFocusable(true);
        final LinearLayout boxRef = box;
        sb.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View x, boolean f) {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(6));
                g.setColor(f ? 0xFF263046 : CARD);
                if (f) g.setStroke(dp(2), ACCENT);
                boxRef.setBackground(g);
            }
        });
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                st.set(key, p + min);
                Facet.save(MainActivity.this, st);
                preview.setStyle(st);
                v.setText(String.valueOf(p + min));
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { changed(); }
        });
        box.addView(sb);
        refreshers.add(new Runnable() {
            public void run() { v.setText(String.valueOf(st.get(key))); sb.setProgress(st.get(key) - min); }
        });
    }

    private void swatches(String text, final String key, final boolean backgrounds) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(dp(12), dp(10), dp(12), dp(4));
        list.addView(t);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout strip = new LinearLayout(this);
        strip.setPadding(dp(8), dp(2), dp(8), dp(6));
        hs.addView(strip);
        list.addView(hs);
        int n = backgrounds ? Style.BGS.length : key.equals("clockCol") ? Style.COLORS.length + 1 : Style.COLORS.length;
        final List<View> sws = new ArrayList<View>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setPadding(dp(3), dp(3), dp(3), dp(3));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(4));
            if (backgrounds) {
                int[] c = Style.BGS[i];
                if (i == Style.PHOTO) { g.setColor(0xFF3A3F4A); }
                else { g.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM); g.setColors(new int[]{0xFF000000 | c[0], 0xFF000000 | c[1]}); }
            } else if (i == Style.CLEAR) { g.setColor(0x33FFFFFF); } else g.setColor(0xFF000000 | Style.COLORS[i]);
            g.setStroke(dp(1), 0x33FFFFFF);
            View chip = new View(this);
            chip.setBackground(g);
            cell.addView(chip, new FrameLayout.LayoutParams(dp(34), dp(backgrounds ? 44 : 30)));
            if (!backgrounds && i == Style.CLEAR) {
                TextView cl = new TextView(this);
                cl.setText("clear");
                cl.setTextColor(TEXT);
                cl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
                cl.setGravity(Gravity.CENTER);
                cell.addView(cl, new FrameLayout.LayoutParams(dp(34), dp(30)));
            }
            if (backgrounds && i == Style.PHOTO) {
                TextView ph = new TextView(this);
                ph.setText("photo");
                ph.setTextColor(TEXT);
                ph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
                ph.setGravity(Gravity.CENTER);
                cell.addView(ph, new FrameLayout.LayoutParams(dp(34), dp(44)));
            }
            cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    if (backgrounds && idx == Style.PHOTO && st.photo == 0) { pickPhoto(); return; }
                    st.set(key, idx);
                    changed();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(4);
            strip.addView(cell, lp);
            sws.add(cell);
        }
        refreshers.add(new Runnable() {
            public void run() {
                for (int i = 0; i < sws.size(); i++) {
                    GradientDrawable ring = new GradientDrawable();
                    ring.setCornerRadius(dp(6));
                    ring.setColor(Color.TRANSPARENT);
                    boolean sel = st.get(key) == i;
                    GradientDrawable focus = new GradientDrawable();
                    focus.setCornerRadius(dp(6));
                    focus.setColor(0x22FFFFFF);
                    focus.setStroke(dp(2), 0xFFFFFFFF);
                    if (sel) ring.setStroke(dp(2), ACCENT);
                    StateListDrawable s = new StateListDrawable();
                    s.addState(new int[]{android.R.attr.state_focused}, focus);
                    s.addState(new int[]{}, ring);
                    sws.get(i).setBackground(s);
                }
            }
        });
    }

    private View button(String text, final Runnable action) {
        LinearLayout r = row();
        TextView t = label(r, text);
        t.setTextColor(ACCENT);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        return r;
    }

    // ---------------- photo & permission ----------------
    @Override
    protected void onPostCreate(Bundle b) {
        super.onPostCreate(b);
        if (getIntent() != null && getIntent().getBooleanExtra("pick_photo", false)) {
            getIntent().removeExtra("pick_photo");
            pickPhoto();   // opened from the lock screen's wallpaper editor
        }
    }

    private static String[] dateFontNames() {
        String[] n = new String[Style.FONTS.length + 1];
        n[0] = "same as clock";
        for (int i = 0; i < Style.FONTS.length; i++) n[i + 1] = Style.FONTS[i];
        return n;
    }

    private void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, PICK_PHOTO);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "This phone doesn't have a photo picker. Install a gallery or files app first.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_PHOTO || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Uri u = data.getData();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(u);
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 900 && o.outHeight / (sample * 2) >= 900) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            in = getContentResolver().openInputStream(u);
            Bitmap bm = BitmapFactory.decodeStream(in, null, o2);
            in.close();
            if (bm == null) throw new Exception("unreadable");
            bm = upright(u, bm);
            File f = AndroidPen.photoFile(this);
            FileOutputStream out = new FileOutputStream(f);
            bm.compress(Bitmap.CompressFormat.JPEG, 88, out);
            out.close();
            AndroidPen.forgetPhoto();
            st.photo = 1;
            st.bg = Style.PHOTO;
            changed();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open that photo.", Toast.LENGTH_LONG).show();
        }
    }

    /** Phone photos are often stored sideways or flipped with a note saying how to turn them; apply it. */
    private Bitmap upright(Uri u, Bitmap bm) {
        File tmp = new File(getCacheDir(), "exif.tmp");
        try {
            InputStream in = getContentResolver().openInputStream(u);
            FileOutputStream o = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            o.close(); in.close();
            int ori = new android.media.ExifInterface(tmp.getAbsolutePath())
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
            android.graphics.Matrix m = new android.graphics.Matrix();
            switch (ori) {
                case 2: m.postScale(-1, 1); break;
                case 3: m.postRotate(180); break;
                case 4: m.postScale(1, -1); break;
                case 5: m.postRotate(90); m.postScale(-1, 1); break;
                case 6: m.postRotate(90); break;
                case 7: m.postRotate(270); m.postScale(-1, 1); break;
                case 8: m.postRotate(270); break;
                default: return bm;
            }
            return Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
        } catch (Throwable t) {
            return bm;
        } finally {
            tmp.delete();
        }
    }

    private void askNotificationAccess() {
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open the setting. Look in Settings › Apps › Special app access › Notification access.", Toast.LENGTH_LONG).show();
        }
    }

    private void askOverlay() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e2) { Toast.makeText(this, "Couldn't open the setting on this phone.", Toast.LENGTH_LONG).show(); }
        }
    }

    // ---------------- Sundial keys ----------------
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (!Facet.isMedia(e.getKeyCode())) return super.dispatchKeyEvent(e);
        int code = Facet.translate(e.getKeyCode());
        KeyEvent t = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), code, e.getRepeatCount(), e.getMetaState());
        boolean handled = super.dispatchKeyEvent(t);
        // Android only moves focus for real arrow keys, so do it ourselves for the Sundial's left/right
        if (!handled && e.getAction() == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            View f = getCurrentFocus();
            if (f != null) {
                View next = f.focusSearch(code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                if (next != null && next != f) handled = next.requestFocus();
            }
        }
        // left that nothing used means "back"
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) leftUsed = handled;
            else if (!leftUsed) onBackPressed();
        }
        return true;
    }

    private boolean leftUsed;
}
