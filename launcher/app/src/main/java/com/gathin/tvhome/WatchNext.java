package com.gathin.tvhome;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Continue watching row's data (watch_next_row): the rows apps publish to TvContract.WatchNextPrograms, read
 * with READ_TV_LISTINGS only (docs/watch-next.md). Without ACCESS_ALL_EPG_DATA the provider refuses a selection, so
 * the whole table is read with an explicit projection and filtered here. The filter, sort and labels are plain Java
 * so they can be tested without a TV (WatchNextTest).
 */
final class WatchNext {

    static final String TAG = "WatchNext";
    /** A row this far into its programme counts as watched: apps leave such rows behind at 99% for months. */
    static final float WATCHED = 0.95f;
    static final long DAY_MS = 24L * 60 * 60 * 1000;

    static final class Program {
        final long id;
        final String pkg;
        final String title;
        final String episodeTitle;
        final String season;
        final String episode;
        /** The first local (content:// or android.resource://) image of poster and thumbnail, or null. */
        final String poster;
        final String intentUri;
        final int type;
        /** 0 when the row has no engagement time. */
        final long engaged;
        final long position;
        final long duration;
        /**
         * home_dynamic_backdrop: the image behind Home while this card has focus, the first local one of thumbnail
         * (often an episode still) and poster, or null. Set by query(); thumbnailBackdrop says which it was.
         */
        String backdrop;
        boolean thumbnailBackdrop;

        Program(long id, String pkg, String title, String episodeTitle, String season, String episode, String poster,
                String intentUri, int type, long engaged, long position, long duration) {
            this.id = id;
            this.pkg = pkg;
            this.title = title == null ? "" : title.trim();
            this.episodeTitle = episodeTitle == null ? "" : episodeTitle.trim();
            this.season = season == null ? "" : season.trim();
            this.episode = episode == null ? "" : episode.trim();
            this.poster = poster;
            this.intentUri = intentUri;
            this.type = type;
            this.engaged = engaged;
            this.position = position;
            this.duration = duration;
        }

        float progress() {
            return WatchNext.progress(position, duration);
        }

        String episodeLine() {
            return WatchNext.episodeLine(season, episode, episodeTitle);
        }

        /** What a card shows: two rows that differ here are drawn differently. */
        String signature() {
            return id + "|" + title + "|" + episodeLine() + "|" + poster + "|" + Math.round(progress() * 100);
        }
    }

    private WatchNext() {
    }

    /** Share watched, 0..1, or -1 when the row gives no position or no duration (Jellyfin's next-episode rows). */
    static float progress(long position, long duration) {
        if (position <= 0 || duration <= 0) return -1f;
        return Math.min(1f, position / (float) duration);
    }

    /** "S1 · E3 · Episode title", leaving out the parts the row does not fill; "" when it fills none. */
    static String episodeLine(String season, String episode, String episodeTitle) {
        StringBuilder line = new StringBuilder();
        if (season != null && !season.isEmpty()) line.append('S').append(season);
        if (episode != null && !episode.isEmpty()) {
            if (line.length() > 0) line.append(" · ");
            line.append('E').append(episode);
        }
        if (episodeTitle != null && !episodeTitle.isEmpty()) {
            if (line.length() > 0) line.append(" · ");
            line.append(episodeTitle);
        }
        return line.toString();
    }

    /** Images a card may load: from a provider or an app's resources, never from the network. */
    static boolean localImage(String uri) {
        return uri != null && (uri.startsWith("content://") || uri.startsWith("android.resource://"));
    }

    /** The poster if it is local, else the thumbnail if that is, else null. */
    static String localPoster(String poster, String thumbnail) {
        if (localImage(poster)) return poster;
        return localImage(thumbnail) ? thumbnail : null;
    }

    /** The largest power of two that keeps the decoded image at least as large as the card on both sides. */
    static int sampleSize(int width, int height, int wantWidth, int wantHeight) {
        int sample = 1;
        if (width <= 0 || height <= 0 || wantWidth <= 0 || wantHeight <= 0) return sample;
        while (width / (sample * 2) >= wantWidth && height / (sample * 2) >= wantHeight) sample *= 2;
        return sample;
    }

    /**
     * The rows the card row shows: from the listed packages (all when the list is empty), engaged within maxAgeMs
     * (0 keeps every age; a row with no engagement time is kept and sorts last), not yet watched, with a title; next
     * episodes first, then rows stopped part-way, then new ones, newest engagement first within each; at most max.
     */
    static List<Program> pick(Collection<Program> rows, Collection<String> packages, long now, long maxAgeMs, int max) {
        List<Program> kept = new ArrayList<>();
        for (Program p : rows) if (dropReason(p, packages, now, maxAgeMs) == null) kept.add(p);
        kept.sort((a, b) -> {
            int byType = Integer.compare(typeRank(a.type), typeRank(b.type));
            return byType != 0 ? byType : Long.compare(b.engaged, a.engaged);
        });
        return kept.size() > max ? new ArrayList<>(kept.subList(0, max)) : kept;
    }

    /** Order of the row: next episodes (type 1) first, then rows stopped part-way (0), then new (2) and the rest. */
    static int typeRank(int type) {
        switch (type) {
            case 1: return 0;
            case 0: return 1;
            case 2: return 2;
            default: return 3;
        }
    }

    /** Why pick() leaves the row out before the cap: "package", "no title", "age" or "watched"; null keeps it. */
    static String dropReason(Program p, Collection<String> packages, long now, long maxAgeMs) {
        if (p.pkg == null || !packages.isEmpty() && !packages.contains(p.pkg)) return "package";
        if (p.title.isEmpty()) return "no title";
        if (maxAgeMs > 0 && p.engaged > 0 && now - p.engaged > maxAgeMs) return "age";
        if (p.progress() >= WATCHED) return "watched";
        return null;
    }

    /**
     * One debug line for a row and what pick() did with it, for telling the filter's choices apart on a TV:
     * title, watch_next_type, age of the last engagement, position/duration and the verdict.
     */
    static String describe(Program p, long now, String verdict) {
        String age = p.engaged <= 0 ? "none"
                : String.format(java.util.Locale.ROOT, "%.1fd", (now - p.engaged) / (double) DAY_MS);
        float progress = p.progress();
        return "row \"" + p.title + "\" " + p.episodeLine() + " type=" + p.type + " age=" + age
                + " pos=" + p.position + "/" + p.duration + "ms"
                + (progress < 0 ? " (no progress)" : " (" + Math.round(progress * 100) + "%)") + " -> " + verdict;
    }

    /** Rows per package, for the log line that tells "the app publishes nothing" from "the filter dropped it". */
    static Map<String, Integer> perPackage(Collection<Program> rows) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Program p : rows) {
            String pkg = p.pkg == null ? "?" : p.pkg;
            Integer n = counts.get(pkg);
            counts.put(pkg, n == null ? 1 : n + 1);
        }
        return counts;
    }

    // ---- reading the provider (needs a TV, off the main thread) --------------------------------------------------

    /** Named, never null: a null projection fails on some sets with "no such column: split_id". */
    private static final String[] PROJECTION = {
            TvContract.WatchNextPrograms._ID,
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
    };

    /** Every Watch Next row this app may see, or an empty list when the provider refuses or fails. */
    static List<Program> query(ContentResolver resolver) {
        List<Program> rows = new ArrayList<>();
        // No selection: without ACCESS_ALL_EPG_DATA the provider throws SecurityException for one.
        try (Cursor c = resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, PROJECTION, null, null, null)) {
            if (c == null) return rows;
            while (c.moveToNext()) {
                Program p = new Program(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                        c.getString(5), localPoster(c.getString(6), c.getString(7)), c.getString(8),
                        c.isNull(9) ? -1 : c.getInt(9), longOrZero(c, 10), longOrZero(c, 11), longOrZero(c, 12));
                p.thumbnailBackdrop = localImage(c.getString(7));
                p.backdrop = localPoster(c.getString(7), c.getString(6));
                rows.add(p);
            }
        } catch (RuntimeException e) {
            // SecurityException without the permission, SQLiteException on a provider that lacks a column.
            Log.w(TAG, "query failed: " + e);
        }
        return rows;
    }

    private static long longOrZero(Cursor c, int column) {
        return c.isNull(column) ? 0 : c.getLong(column);
    }

    /** A local image decoded at about the card's size (RGB_565), or null. Never opens a network URI. */
    static Bitmap decode(ContentResolver resolver, String uri, int width, int height) {
        if (!localImage(uri)) return null;
        Uri u = Uri.parse(uri);
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = resolver.openInputStream(u)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = resolver.openInputStream(u)) {
                Bitmap b = in == null ? null : BitmapFactory.decodeStream(in, null, opts);
                if (b == null) Log.w(TAG, "poster " + uri + ": no image (" + bounds.outWidth + "x" + bounds.outHeight + ")");
                return b;
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "poster " + uri + ": " + e);
            return null;
        }
    }
}
