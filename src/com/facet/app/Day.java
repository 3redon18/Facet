package com.facet.app;

import java.util.Calendar;

/** Facet shows a plain clock and date, so there is nothing per-day to compute. */
public final class Day {
    public static Day compute(Calendar now, Style st) { return new Day(); }
    public String key() { return ""; }
}
