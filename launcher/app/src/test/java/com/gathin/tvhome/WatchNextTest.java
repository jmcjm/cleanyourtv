package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** What the Continue watching row keeps from the Watch Next table, and how it labels a card. */
public class WatchNextTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String JELLYFIN = "org.jellyfin.androidtv";

    private static WatchNext.Program row(long id, String pkg, long daysAgo, long position, long duration) {
        return new WatchNext.Program(id, pkg, "Show " + id, null, "1", "3", null, "intent:#Intent;end",
                0, daysAgo < 0 ? 0 : NOW - daysAgo * WatchNext.DAY_MS, position, duration);
    }

    private static long[] ids(List<WatchNext.Program> rows) {
        long[] out = new long[rows.size()];
        for (int i = 0; i < out.length; i++) out[i] = rows.get(i).id;
        return out;
    }

    @Test
    public void keepsOnlyTheListedPackages() {
        List<WatchNext.Program> rows = Arrays.asList(row(1, JELLYFIN, 1, 0, 0), row(2, "com.netflix.ninja", 1, 0, 0));
        assertEquals(1, WatchNext.pick(rows, Collections.singleton(JELLYFIN), NOW, 0, 10).size());
        assertEquals(2, WatchNext.pick(rows, Collections.emptySet(), NOW, 0, 10).size());
    }

    @Test
    public void dropsOldRowsButKeepsUndatedOnesLast() {
        List<WatchNext.Program> rows = Arrays.asList(row(1, JELLYFIN, 90, 0, 0), row(2, JELLYFIN, -1, 0, 0),
                row(3, JELLYFIN, 2, 0, 0));
        long sixtyDays = 60 * WatchNext.DAY_MS;
        assertEquals(Arrays.toString(new long[]{3, 2}),
                Arrays.toString(ids(WatchNext.pick(rows, Collections.emptySet(), NOW, sixtyDays, 10))));
        assertEquals(3, WatchNext.pick(rows, Collections.emptySet(), NOW, 0, 10).size());
    }

    @Test
    public void dropsWatchedRowsAndKeepsNextEpisodesWithoutAPosition() {
        List<WatchNext.Program> rows = Arrays.asList(row(1, JELLYFIN, 1, 99, 100), row(2, JELLYFIN, 1, 94, 100),
                row(3, JELLYFIN, 1, 0, 0));
        assertEquals(Arrays.toString(new long[]{2, 3}),
                Arrays.toString(ids(WatchNext.pick(rows, Collections.emptySet(), NOW, 0, 10))));
    }

    @Test
    public void newestFirstAndCapped() {
        List<WatchNext.Program> rows = Arrays.asList(row(1, JELLYFIN, 5, 0, 0), row(2, JELLYFIN, 1, 0, 0),
                row(3, JELLYFIN, 3, 0, 0));
        assertEquals(Arrays.toString(new long[]{2, 3}),
                Arrays.toString(ids(WatchNext.pick(rows, Collections.emptySet(), NOW, 0, 2))));
    }

    @Test
    public void aRowWithoutTitleOrPackageIsLeftOut() {
        WatchNext.Program untitled = new WatchNext.Program(1, JELLYFIN, " ", null, null, null, null, null, 0, NOW, 0, 0);
        WatchNext.Program anonymous = new WatchNext.Program(2, null, "Show", null, null, null, null, null, 0, NOW, 0, 0);
        assertTrue(WatchNext.pick(Arrays.asList(untitled, anonymous), Collections.emptySet(), NOW, 0, 10).isEmpty());
    }

    @Test
    public void progressIsUnknownWithoutPositionOrDuration() {
        assertEquals(-1f, WatchNext.progress(0, 1000), 0f);
        assertEquals(-1f, WatchNext.progress(500, 0), 0f);
        assertEquals(0.5f, WatchNext.progress(500, 1000), 0.0001f);
        assertEquals(1f, WatchNext.progress(2000, 1000), 0f);
    }

    @Test
    public void episodeLineLeavesOutWhatTheRowDoesNotFill() {
        assertEquals("S1 · E3 · Pilot", WatchNext.episodeLine("1", "3", "Pilot"));
        assertEquals("E3", WatchNext.episodeLine("", "3", null));
        assertEquals("Pilot", WatchNext.episodeLine(null, null, "Pilot"));
        assertEquals("", WatchNext.episodeLine(null, "", ""));
    }

    @Test
    public void onlyLocalImagesAreLoaded() {
        assertTrue(WatchNext.localImage("content://org.jellyfin.androidtv.integration/image/1"));
        assertTrue(WatchNext.localImage("android.resource://com.spotify.tv.android/123"));
        assertFalse(WatchNext.localImage("https://example.com/poster.jpg"));
        assertFalse(WatchNext.localImage(null));
        assertEquals("content://a/2", WatchNext.localPoster("https://example.com/1.jpg", "content://a/2"));
        assertNull(WatchNext.localPoster("https://example.com/1.jpg", "http://example.com/2.jpg"));
    }

    @Test
    public void sampleSizeKeepsTheImageAtLeastCardSized() {
        assertEquals(1, WatchNext.sampleSize(320, 180, 320, 180));
        assertEquals(2, WatchNext.sampleSize(1000, 600, 320, 180));
        assertEquals(4, WatchNext.sampleSize(1280, 720, 320, 180));
        assertEquals(4, WatchNext.sampleSize(1920, 1080, 320, 180));
        assertEquals(1, WatchNext.sampleSize(0, 0, 320, 180));
    }

    @Test
    public void countsRowsPerPackage() {
        Map<String, Integer> counts = WatchNext.perPackage(Arrays.asList(row(1, JELLYFIN, 1, 0, 0),
                row(2, JELLYFIN, 1, 0, 0), row(3, "com.netflix.ninja", 1, 0, 0)));
        assertEquals(Integer.valueOf(2), counts.get(JELLYFIN));
        assertEquals(Integer.valueOf(1), counts.get("com.netflix.ninja"));
    }

    @Test
    public void dropReasonNamesTheRuleThatLeftARowOut() {
        java.util.Set<String> jellyfin = Collections.singleton(JELLYFIN);
        long sixtyDays = 60 * WatchNext.DAY_MS;
        assertEquals("package", WatchNext.dropReason(row(1, "com.netflix.ninja", 1, 0, 0), jellyfin, NOW, sixtyDays));
        assertEquals("age", WatchNext.dropReason(row(2, JELLYFIN, 90, 0, 0), jellyfin, NOW, sixtyDays));
        assertEquals("watched", WatchNext.dropReason(row(3, JELLYFIN, 1, 99, 100), jellyfin, NOW, sixtyDays));
        assertNull(WatchNext.dropReason(row(4, JELLYFIN, 1, 10, 100), jellyfin, NOW, sixtyDays));
        WatchNext.Program untitled = new WatchNext.Program(5, JELLYFIN, "", null, null, null, null, null, 0, NOW, 0, 0);
        assertEquals("no title", WatchNext.dropReason(untitled, jellyfin, NOW, sixtyDays));
    }

    @Test
    public void describeGivesAgeProgressAndVerdict() {
        String line = WatchNext.describe(row(1, JELLYFIN, 3, 250, 1000), NOW, "kept");
        assertTrue(line, line.contains("age=3.0d") && line.contains("(25%)") && line.endsWith("-> kept"));
        String undated = WatchNext.describe(row(2, JELLYFIN, -1, 0, 0), NOW, "dropped: age");
        assertTrue(undated, undated.contains("age=none") && undated.contains("(no progress)"));
    }
}
