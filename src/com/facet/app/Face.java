package com.facet.app;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Draws the lock screen. Knows nothing about Android, so it can also be rendered on a computer. */
public final class Face {
    public static final int MODERN = 0, SERIF = 1, MONO = 2, PIXEL = 3, HEBREW = 4, BOLD = 5,
            HEAVY = 6, THIN = 7, NARROW = 8, CASUAL = 9, SCRIPT = 10, SLAB = 11;
    /** Style.font number -> font the pen draws with. */
    static final int[] CLOCK_FONTS = {MODERN, SERIF, MONO, PIXEL, HEAVY, THIN, NARROW, CASUAL, SCRIPT, SLAB};

    public interface Pen {
        int w();
        int h();
        void gradient(int top, int bottom);
        /** Draw the chosen photo, darkened by dim%. Returns false if there isn't one. */
        boolean photo(int dim);
        void text(String s, float x, float baseline, float size, int font, int argb, int align);
        /** Big text as clear glass: see-through, with a bright rim and a soft shadow. */
        void glass(String s, float x, float baseline, float size, int font, int align);
        float measure(String s, float size, int font);
        void circle(float cx, float cy, float r, int argb);
        void rect(float l, float t, float r, float b, int argb);
        void roundRect(float l, float t, float r, float b, float radius, int argb);
        /** Draw a notification's icon (whatever the platform stored in Note.icon), tinted. */
        void icon(Object icon, float l, float t, float size, int argb);
    }

    /** One notification shown on the lock screen. */
    public static final class Note {
        public String key, app, title, text, when;
        public Object icon, open;   // platform objects: an icon bitmap, and what to launch
        public boolean autoCancel, clearable = true;
        public String pkg, channelId;   // for the hold-to-manage controls
        public Object user;
        // conversations
        public boolean messaging, group;
        public Object avatar;                                 // the contact's photo, for a messaging card
        public int messageCount;
        public String full;                                   // long text, when there's more than one line
        public List<String[]> messages = new ArrayList<String[]>(); // {sender, text}, oldest first
        // what the app says may be shown over a locked screen
        public String publicTitle, publicText;
        // downloads and other progress
        public int progress = -1, progressMax;
        public boolean progressSpinning;
        public Object picture;                                // BigPictureStyle image, shown when opened up
        // music and podcast players
        public boolean media;
        public Object art, session;                           // album art bitmap, MediaSession.Token
        public int tint;                                      // a colour pulled from the art, for a faint wash
        // the app's own buttons
        public List<Object> actions = new ArrayList<Object>();
        public List<Object> actionIcons = new ArrayList<Object>();  // the button icons, for a media player
        public List<String> actionLabels = new ArrayList<String>();
        public List<Boolean> actionReply = new ArrayList<Boolean>();
    }

    /** What changes from moment to moment. */
    public static final class Now {
        public Calendar time;
        public Day day;
        public int battery = -1;
        public boolean charging;
        public float hint = 1;      // unlock hint opacity
        public float lift = 0;      // how far the face has been swiped up, in px
        public boolean showHint = true;
        public List<Note> notes = new ArrayList<Note>();
        public int selected = -1;   // card picked with the keypad
        public int dragIndex = -1;  // card being swiped sideways
        public float dragX = 0;
        /** Filled in while drawing: where each visible card is (l, t, r, b), in the same order as notes. */
        public final List<float[]> cards = new ArrayList<float[]>();
        public int more = 0;        // notifications that didn't fit
        public String noteProblem;  // why notifications can't be shown, if they can't
        public boolean cardViews;   // the lock screen draws the notification cards itself (as views): leave room, don't draw them
        public float notesTop, notesBottom;   // where the cards go
        public int editTarget;      // while editing: 0 = the clock, 1 = the dates
        public float datesCx, datesTopY;   // where the dates are (centre x, top y), before any swipe lift
        public boolean edit;        // clock editing: hide everything but the clock and its dates
        public boolean guide;       // show the centre guide while dragging
        /** Filled in while drawing: the clock's centre and its box (l, t, r, b). */
        public float clockCx, clockCy;
        public final float[] clockRect = new float[4];
        public final float[] datesRect = new float[4];   // the date lines under the clock
    }

    static int argb(int rgb, float a) {
        return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (rgb & 0xFFFFFF);
    }

    public static void draw(Pen p, Style st, Now n) {
        int W = p.w(), H = p.h();
        float s = W / 480f;
        boolean usedPhoto = st.bg == Style.PHOTO && st.photo == 1 && p.photo(st.dim);
        if (!usedPhoto) {
            int[] g = Style.BGS[st.bg == Style.PHOTO ? 1 : st.bg];
            p.gradient(g[0], g[1]);
        }
        int clock = Style.color(st.clockCol), text = Style.color(st.textCol);
        boolean glass = st.clockCol == Style.CLEAR;
        float lift = -n.lift;
        float fade = 1 - Math.min(1, n.lift / (H * 0.35f));

        // ---- time ----
        Calendar c = n.time;
        int hh = c.get(Calendar.HOUR_OF_DAY), mm = c.get(Calendar.MINUTE), ss = c.get(Calendar.SECOND);
        String time = st.h24 == 1 ? hh + ":" + two(mm) : ((hh + 11) % 12 + 1) + ":" + two(mm);
        String side1 = st.h24 == 1 ? "" : (hh < 12 ? "AM" : "PM");
        String side2 = st.secs == 1 ? two(ss) : "";
        int font = CLOCK_FONTS[Math.max(0, Math.min(CLOCK_FONTS.length - 1, st.font))];
        boolean custom = st.clockX >= 0 && st.clockY >= 0;
        float size = st.clockSize * s;
        boolean hasNotes = !n.edit && st.notes == 1 && !n.notes.isEmpty();
        if (hasNotes && !custom) size = Math.min(size, Math.max(84 * s, size * 0.72f));
        float sideSize = Math.max(14 * s, size * 0.2f);
        float tw = p.measure(time, size, font);
        float sw = Math.max(side1.isEmpty() ? 0 : p.measure(side1, sideSize, font == PIXEL ? PIXEL : MODERN),
                side2.isEmpty() ? 0 : p.measure(side2, sideSize, font == PIXEL ? PIXEL : MODERN));
        float gap = sw > 0 ? 8 * s : 0;
        float maxW = W * 0.9f;
        if (tw + gap + sw > maxW) {   // too big for the screen: shrink to fit
            float k = maxW / (tw + gap + sw);
            size *= k; sideSize *= k; tw *= k; sw *= k; gap *= k;
        }
        float bw = tw + gap + sw;
        float cy = (custom ? st.clockY / 1000f * H : hasNotes ? H * 0.17f + size * 0.3f : st.pos == 0 ? H * 0.30f : H * 0.44f) + lift;
        float cxc = custom ? st.clockX / 1000f * W : W / 2f;
        cxc = Math.max(bw / 2 + 8 * s, Math.min(W - bw / 2 - 8 * s, cxc));
        float base = cy + size * 0.36f;
        float x0 = cxc - bw / 2;
        n.clockCx = cxc; n.clockCy = cy - lift;
        n.clockRect[0] = x0 - 14 * s; n.clockRect[2] = x0 + bw + 14 * s;
        n.clockRect[1] = base - size * 0.80f - 8 * s; n.clockRect[3] = base + size * 0.16f + 8 * s;
        if (n.edit) {
            outline(p, n.clockRect, s, n.editTarget == 0);
            if (n.guide) p.rect(W / 2f - s * 0.5f, 0, W / 2f + s * 0.5f, H, 0x99E3B85A);
        }
        if (glass) p.glass(time, x0 + tw / 2, base, size, font, 0);
        else p.text(time, x0 + tw / 2, base, size, font, argb(clock, fade), 0);
        int sideFont = font == PIXEL ? PIXEL : MODERN;
        if (!side1.isEmpty()) p.text(side1, x0 + tw + gap, base - size * 0.72f + sideSize, sideSize, sideFont, argb(clock, 0.75f * fade), -1);
        if (!side2.isEmpty()) p.text(side2, x0 + tw + gap, base - (side1.isEmpty() ? size * 0.72f - sideSize : 0), sideSize, sideFont, argb(clock, 0.55f * fade), -1);

        // ---- lines under the clock ----
        List<Object[]> lines = new ArrayList<Object[]>(); // text, size, font, color, alpha
        Day d = n.day;
        int textFont = font == PIXEL ? PIXEL : font == MONO ? MONO : font == SERIF ? SERIF : MODERN;
        if (st.dateFont > 0) textFont = CLOCK_FONTS[Math.max(0, Math.min(CLOCK_FONTS.length - 1, st.dateFont - 1))];
        if (st.greg == 1) {
            String g = String.format(Locale.US, "%tA, %<tB %<te", c);
            lines.add(new Object[]{g, 22 * s, textFont, Style.pick(st.dateCol, st.textCol), 1f});
        }
        // measure the date lines first (their size is its own setting), then place the block
        float k = Math.max(0.3f, st.dateSize / 100f);
        int nl = lines.size();
        float[] fits = new float[nl], widths = new float[nl];
        float blockH = 0, blockW = 0;
        for (int q = 0; q < nl; q++) {
            Object[] l = lines.get(q);
            float sz = (Float) l[1] * k;
            String t = (String) l[0];
            int f = (Integer) l[2];
            float fit = sz;
            while (p.measure(t, fit, f) > W * 0.92f && fit > 8 * s) fit -= s * 0.5f;
            fits[q] = fit; widths[q] = p.measure(t, fit, f);
            blockH += sz * 1.5f;
            blockW = Math.max(blockW, widths[q]);
        }
        List<String> chips = new ArrayList<String>();   // custom day tags removed in Facet
        int chipFont = textFont;
        float chipSize = 14 * s * k, chipPad = 11 * s * k, chipGap = 8 * s * k, chipH = chipSize * 1.9f;
        float[] chipW = new float[chips.size()];
        float chipsTotal = 0;
        for (int q = 0; q < chips.size(); q++) {
            chipW[q] = p.measure(chips.get(q), chipSize, chipFont) + chipPad * 2;
            chipsTotal += chipW[q] + (q > 0 ? chipGap : 0);
        }
        boolean stacked = chipsTotal > W * 0.9f;       // too wide for one row: one tag per row
        float chipsH = chips.isEmpty() ? 0 : stacked ? chips.size() * chipH + (chips.size() - 1) * chipGap : chipH;
        float chipsGapTop = chips.isEmpty() ? 0 : (nl > 0 ? 10 * s * k : 0);
        if (!chips.isEmpty()) {
            blockH += chipsGapTop + chipsH;
            for (float cw : chipW) blockW = Math.max(blockW, stacked ? cw : chipsTotal);
        }
        boolean datesCustom = st.dateX >= 0 && st.dateY >= 0;
        float dcx = datesCustom ? st.dateX / 1000f * W : cxc;
        dcx = Math.max(blockW / 2 + 8 * s, Math.min(W - blockW / 2 - 8 * s, dcx));
        float datesTop = datesCustom ? st.dateY / 1000f * H + lift : base + size * 0.12f + 26 * s;
        float y = datesTop;
        float[] dr = n.datesRect;
        dr[0] = dcx - blockW / 2 - 14 * s; dr[2] = dcx + blockW / 2 + 14 * s;
        dr[1] = datesTop - 6 * s; dr[3] = datesTop + blockH + 6 * s;
        if (n.edit && nl > 0) outline(p, dr, s, n.editTarget == 1);
        for (int q = 0; q < nl; q++) {
            Object[] l = lines.get(q);
            float sz = (Float) l[1] * k;
            y += sz * 1.15f;
            p.text((String) l[0], dcx, y, fits[q], (Integer) l[2], argb((Integer) l[3], (Float) l[4] * fade), 0);
            y += sz * 0.35f;
        }
        if (!chips.isEmpty()) {
            int cc = Style.pick(st.dateCol, st.textCol);
            float cy2 = y + chipsGapTop;   // just under the last line
            float rowW = chipsTotal;
            float cx0 = dcx - rowW / 2;
            for (int q = 0; q < chips.size(); q++) {
                float w = chipW[q];
                float left = stacked ? dcx - w / 2 : cx0;
                p.roundRect(left, cy2, left + w, cy2 + chipH, chipH / 2, argb(cc, 0.16f * fade));
                p.text(chips.get(q), left + w / 2, cy2 + chipH / 2 + chipSize * 0.35f, chipSize, chipFont, argb(cc, 0.95f * fade), 0);
                if (stacked) cy2 += chipH + chipGap; else cx0 += w + chipGap;
            }
            y = datesTop + blockH;
        }
        if (nl == 0 && chips.isEmpty()) { dr[1] = dr[3] = 0; }
        n.datesCx = dcx;
        n.datesTopY = datesTop - lift;

        // ---- notification cards ----
        float by = H - 66 * s;
        n.cards.clear();
        n.more = 0;
        if (n.edit) return;
        // the cards go under whichever of the clock and dates sits higher up
        float flowEnd = y;
        if (datesCustom) flowEnd = Math.max(n.clockRect[3] + 10 * s, y < H * 0.55f ? y : 0);
        n.notesTop = flowEnd + 14 * s; n.notesBottom = by - 8 * s;
        if (hasNotes && n.cardViews) { /* drawn by the lock screen as real views */ }
        else if (hasNotes) drawNotes(p, st, n, flowEnd + 14 * s, by - 26 * s, s, fade);
        else if (st.notes == 1 && n.noteProblem != null)
            p.text(n.noteProblem, W / 2f, flowEnd + 34 * s, 14 * s, MODERN, argb(clock, 0.85f * fade), 0);

        // ---- battery and unlock hint ----
        if (st.battery == 1 && n.battery >= 0) {
            String b = n.battery + "%";
            float bs = 16 * s, bwid = p.measure(b, bs, MODERN);
            float bx = W / 2f - (bwid + 22 * s) / 2;
            // a little battery outline
            int col = argb(text, 0.75f * fade);
            p.rect(bx, by - 11 * s, bx + 18 * s, by - 10 * s, col);
            p.rect(bx, by, bx + 18 * s, by + 1 * s, col);
            p.rect(bx, by - 11 * s, bx + 1 * s, by + 1 * s, col);
            p.rect(bx + 17 * s, by - 11 * s, bx + 18 * s, by + 1 * s, col);
            int lvl = n.battery <= 15 ? 0xFF6A5A : (n.charging ? 0x8FD3C4 : text);
            p.rect(bx + 2 * s, by - 9 * s, bx + 2 * s + 14 * s * n.battery / 100f, by - 1 * s, argb(lvl, 0.9f * fade));
            p.rect(bx + 18 * s, by - 7 * s, bx + 20 * s, by - 3 * s, col);
            p.text(b, bx + 24 * s, by, bs, MODERN, col, -1);
        }
        if (n.showHint && n.hint > 0) {
            float hy = H - 28 * s;
            p.text("swipe up to open", W / 2f, hy, 15 * s, MODERN, argb(text, 0.6f * n.hint * fade), 0);
            float ay = hy - 22 * s;
            int ac = argb(text, 0.5f * n.hint * fade);
            for (int i = 0; i < 6; i++) { // a small chevron
                p.rect(W / 2f - 6 * s + i * s, ay + 6 * s - i * s, W / 2f - 5 * s + i * s, ay + 7 * s - i * s, ac);
                p.rect(W / 2f + 5 * s - i * s, ay + 6 * s - i * s, W / 2f + 6 * s - i * s, ay + 7 * s - i * s, ac);
            }
        }
    }

    /** The box shown around an object while editing; the one being edited is brighter. */
    private static void outline(Pen p, float[] r, float s, boolean active) {
        p.roundRect(r[0], r[1], r[2], r[3], 14 * s, active ? 0x14FFFFFF : 0x0AFFFFFF);
        int oc = active ? 0xCCFFFFFF : 0x55FFFFFF;
        float t = 1.5f * s;
        p.rect(r[0] + 14 * s, r[1], r[2] - 14 * s, r[1] + t, oc);
        p.rect(r[0] + 14 * s, r[3] - t, r[2] - 14 * s, r[3], oc);
        p.rect(r[0], r[1] + 14 * s, r[0] + t, r[3] - 14 * s, oc);
        p.rect(r[2] - t, r[1] + 14 * s, r[2], r[3] - 14 * s, oc);
    }

    /** Card colours: fixed, high-contrast, independent of the lock screen's text colour. */
    static int[] cardColors(Style st) {
        boolean light = st.lightBackground() && st.bg != Style.PHOTO;
        // background, background alpha (x100), main text, secondary text -- the defaults "match the background"
        int bg = light ? 0xFFFFFF : 0x151A25;
        int mainT = light ? 0x15171C : 0xF5F5F7;
        int subT = light ? 0x4F5663 : 0xB7BFCC;
        if (st.cardCol >= 0) bg = Style.color(st.cardCol) & 0xFFFFFF;
        if (st.cardText >= 0) { mainT = Style.color(st.cardText) & 0xFFFFFF; subT = blend(mainT, bg, 0.4f); }
        int alpha = st.cardAlpha >= 0 && st.cardAlpha <= 100 ? st.cardAlpha : 90;
        return new int[]{bg, alpha, mainT, subT};
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg2 = (b >> 8) & 255, bb = b & 255;
        return (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg2 - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    private static void drawNotes(Pen p, Style st, Now n, float top, float bottom, float s, float fade) {
        int W = p.w();
        int clock = Style.color(st.clockCol);
        int[] cc = cardColors(st);
        float gap = 8 * s, mx = 10 * s, pad = 14 * s, ic = 19 * s;
        boolean priv = st.notePrivate == 1;
        float y = top;
        int shown = 0;
        for (int i = 0; i < n.notes.size(); i++) {
            Note note = n.notes.get(i);
            float l0 = mx, r0 = W - mx, tw = r0 - l0 - pad * 2;
            String title = priv ? note.app : (note.title != null && !note.title.isEmpty() ? note.title : note.app);
            String body = priv ? (note.messaging ? "New message" : "New notification") : note.text;
            List<String> lines = wrap(p, body == null ? "" : body, 20 * s, HEBREW, tw, 2);
            float h = pad + 15 * s + 10 * s + 24 * s + lines.size() * 26 * s + pad - 4 * s;
            boolean last = i == n.notes.size() - 1;
            // keep room for the "+ more" line unless this is the last one
            float limit = bottom - (last ? 0 : 22 * s);
            if (y + h > limit && lines.size() > 1) { // too tall: squeeze to one line before giving up
                lines = wrap(p, body == null ? "" : body, 20 * s, HEBREW, tw, 1);
                h -= 26 * s;
            }
            if (y + h > limit && shown > 0) break;
            float dx = i == n.dragIndex ? n.dragX : 0;
            float a = fade * (i == n.dragIndex ? Math.max(0.15f, 1 - Math.abs(dx) / (W * 0.6f)) : 1);
            float l = l0 + dx, r = r0 + dx;
            if (i == n.selected) p.roundRect(l - 3 * s, y - 3 * s, r + 3 * s, y + h + 3 * s, 14 * s, argb(clock, a));
            p.roundRect(l, y, r, y + h, 12 * s, argb(cc[0], cc[1] / 100f * a));
            // app line: icon, name (and how many messages), time
            float ay = y + pad + 13 * s;
            p.icon(note.icon, l + pad, ay - 15 * s, ic, argb(cc[3], a));
            String app = note.app == null ? "" : note.app;
            if (!priv && note.messageCount > 1) app += " · " + note.messageCount + " messages";
            String when = note.when == null ? "" : note.when;
            float whenW = when.isEmpty() ? 0 : p.measure(when, 15 * s, MODERN) + 8 * s;
            p.text(ellipsize(p, app, 15 * s, MODERN, tw - ic - 6 * s - whenW), l + pad + ic + 6 * s, ay, 15 * s, MODERN, argb(cc[3], a), -1);
            if (!when.isEmpty()) p.text(when, r - pad, ay, 15 * s, MODERN, argb(cc[3], a), 1);
            // who, then what they said
            float ty = ay + 10 * s + 22 * s;
            p.text(ellipsize(p, title, 22 * s, BOLD, tw), l + pad, ty, 22 * s, BOLD, argb(cc[2], a), -1);
            float by = ty;
            for (String line : lines) {
                by += 26 * s;
                p.text(line, l + pad, by, 20 * s, HEBREW, argb(cc[2], 0.86f * a), -1);
            }
            n.cards.add(new float[]{l, y, r, y + h});
            y += h + gap;
            shown++;
        }
        n.more = n.notes.size() - shown;
        if (n.more > 0) {
            String m = "+ " + n.more + " more";
            p.text(m, W / 2f, y + 12 * s, 14 * s, MODERN, argb(Style.color(st.textCol), 0.7f * fade), 0);
        }
    }

    /** Word-wraps to at most maxLines, putting "…" on the last line if it doesn't all fit. */
    static List<String> wrap(Pen p, String text, float size, int font, float maxW, int maxLines) {
        List<String> out = new ArrayList<String>();
        String[] words = text.replace('\n', ' ').trim().split("\\s+");
        StringBuilder cur = new StringBuilder();
        int i = 0;
        for (; i < words.length; i++) {
            String tryLine = cur.length() == 0 ? words[i] : cur + " " + words[i];
            if (p.measure(tryLine, size, font) <= maxW || cur.length() == 0) {
                cur.setLength(0); cur.append(tryLine);
            } else {
                out.add(cur.toString());
                cur.setLength(0); cur.append(words[i]);
                if (out.size() == maxLines) break;
            }
        }
        if (out.size() < maxLines && cur.length() > 0) { out.add(cur.toString()); i = words.length; }
        if (i < words.length && !out.isEmpty()) {
            String lastLine = out.get(out.size() - 1) + " " + cur;
            out.set(out.size() - 1, ellipsize(p, lastLine + " …", size, font, maxW));
        }
        for (int k = 0; k < out.size(); k++) out.set(k, ellipsize(p, out.get(k), size, font, maxW));
        if (out.isEmpty()) out.add("");
        return out;
    }

    static String ellipsize(Pen p, String s, float size, int font, float maxW) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        if (p.measure(s, size, font) <= maxW) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (p.measure(s.substring(0, mid) + "…", size, font) <= maxW) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo).trim() + "…";
    }

    static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String x : l) { if (sb.length() > 0) sb.append(sep); sb.append(x); }
        return sb.toString();
    }
}
