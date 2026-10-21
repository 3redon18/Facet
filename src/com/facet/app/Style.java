package com.facet.app;

/** Every setting in one place. Stored as ints so it's easy to save and to test. */
public final class Style {
    public interface Store {
        int get(String key, int def);
        void put(String key, int value);
    }

    // status bar
    public int sunset = 1, city = 0;
    // lock screen
    public int lockOn = 0, clockSize = 130, font = 0, h24 = 0, secs = 0, bg = 1, clockCol = 1, textCol = 8,
            hebLine = 2, greg = 1, events = 1, battery = 1, pos = 0, photo = 0, dim = 35, notes = 1, notePrivate = 0,
            hebCol = -1, dateCol = -1,
            dateX = -1, dateY = -1, dateSize = 100, dateFont = 0, tach = 1, minhag = 1, israeli = 0, keypad = 0,
            cardCol = -1, cardText = -1, cardAlpha = 90,   // notification cards: colour, text colour (-1 = match the background), and opacity %   // keypad: let the keypad work the lock screen (off: a pocket cannot press anything)   // tach: show the Tachnun / Hallel tags; minhag: 0 Ashkenazi, 1 Chassidic, 2 Chabad; israeli: show Yom HaAtzmaut etc.
              // dateFont: 0 = same as the clock, else font number + 1
              // the dates on their own: where (-1 = under the clock) and how big (%)   // colours of the Hebrew date and the other dates; -1 = same as the text colour
            clockX = -1, clockY = -1; // where you dragged the clock to, in thousandths of the screen; -1 = automatic

    public static final String[] FONTS = {"modern", "serif", "mono", "pixel", "heavy", "thin", "narrow", "casual", "script", "slab"};
    public static final String[] BG_NAMES = {"black", "midnight", "forest", "plum", "dusk", "sea", "sand", "paper", "photo"};
    public static final int[][] BGS = {
            {0x000000, 0x000000}, {0x0B1220, 0x1D2846}, {0x0D1E16, 0x1E3A2A}, {0x1C0F22, 0x3E1E44},
            {0x2B3A78, 0xB86A8E}, {0x081E38, 0x2E679C}, {0xEDE2C8, 0xD6C29A}, {0xF6F3EC, 0xE9E4D8}, {0x101010, 0x101010}};
    public static final int PHOTO = 8;
    public static final String[] COLOR_NAMES = {"white", "cream", "gold", "sky", "mint", "rose", "coral", "ink", "grey"};
    public static final int[] COLORS = {0xFFFFFF, 0xF3EBD8, 0xE3B85A, 0x8FC7FF, 0x8FD3C4, 0xF4A6B8, 0xFF8A6A, 0x1A1A1A, 0xA3A9B4};
    /** Clock colour number for the see-through "clear" glass look (only for the clock). */
    public static final int CLEAR = 9;
    /** A date line's colour: its own pick, or the general text colour if it has none. */
    public static int pick(int own, int fallback) { return own >= 0 && own < COLORS.length ? COLORS[own] : color(fallback); }
    public static int color(int i) { return i >= 0 && i < COLORS.length ? COLORS[i] : 0xFFFFFF; }
    public static final String[] HEB_LINE = {"hebrew", "english", "both", "off"};
    public static final String[] NOTIF_LANG = {"hebrew", "english", "both"};

    private static final String[] KEYS = {"sunset", "city", "lockOn", "clockSize", "font", "h24",
            "secs", "bg", "clockCol", "textCol", "hebLine", "greg", "events", "battery", "pos", "photo", "dim", "notes", "notePrivate",
            "clockX", "clockY", "hebCol", "dateCol", "dateX", "dateY", "dateSize", "dateFont", "tach", "minhag", "israeli", "keypad", "cardCol", "cardText", "cardAlpha"};

    private int[] fields() {
        return new int[]{sunset, city, lockOn, clockSize, font, h24, secs, bg, clockCol, textCol,
                hebLine, greg, events, battery, pos, photo, dim, notes, notePrivate, clockX, clockY, hebCol, dateCol, dateX, dateY, dateSize, dateFont, tach, minhag, israeli, keypad, cardCol, cardText, cardAlpha};
    }

    public int get(String key) {
        int[] f = fields();
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return f[i];
        throw new IllegalArgumentException(key);
    }

    public void set(String key, int v) {
        switch (key) {
            case "sunset": sunset = v; break;
            case "city": city = v; break;
            case "lockOn": lockOn = v; break;
            case "clockSize": clockSize = v; break;
            case "font": font = v; break;
            case "h24": h24 = v; break;
            case "secs": secs = v; break;
            case "bg": bg = v; break;
            case "clockCol": clockCol = v; break;
            case "textCol": textCol = v; break;
            case "hebLine": hebLine = v; break;
            case "greg": greg = v; break;
            case "events": events = v; break;
            case "battery": battery = v; break;
            case "pos": pos = v; break;
            case "photo": photo = v; break;
            case "dim": dim = v; break;
            case "notes": notes = v; break;
            case "notePrivate": notePrivate = v; break;
            case "clockX": clockX = v; break;
            case "clockY": clockY = v; break;
            case "hebCol": hebCol = v; break;
            case "dateCol": dateCol = v; break;
            case "dateX": dateX = v; break;
            case "dateY": dateY = v; break;
            case "dateSize": dateSize = v; break;
            case "dateFont": dateFont = v; break;
            case "tach": tach = v; break;
            case "minhag": minhag = v; break;
            case "israeli": israeli = v; break;
            case "keypad": keypad = v; break;
            case "cardCol": cardCol = v; break;
            case "cardText": cardText = v; break;
            case "cardAlpha": cardAlpha = v; break;
            default: throw new IllegalArgumentException(key);
        }
    }

    /** Copy every setting from another Style. */
    public void copyFrom(Style o) { for (String k : KEYS) set(k, o.get(k)); }

    public void load(Store s) {
        int[] f = fields();
        for (int i = 0; i < KEYS.length; i++) set(KEYS[i], s.get(KEYS[i], f[i]));
    }

    public void save(Store s) {
        int[] f = fields();
        for (int i = 0; i < KEYS.length; i++) s.put(KEYS[i], f[i]);
    }

    /** True when the background is light, so the editor can suggest dark text. */
    public boolean lightBackground() {
        return bg == 6 || bg == 7;
    }
}
