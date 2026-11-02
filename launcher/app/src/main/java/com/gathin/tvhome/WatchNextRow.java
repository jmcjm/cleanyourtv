package com.gathin.tvhome;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Rect;
import android.media.tv.TvContract;
import android.os.Handler;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Continue watching row above the dock (watch_next_row in res/values/watch_next.xml). MainActivity places it in
 * the room between the top bar and the name above the dock, and wires the D-pad: Up from the dock enters it, Down
 * leaves it for the dock, Up for the top bar, and Left and Right stop at its ends. With no card, no room or no
 * READ_TV_LISTINGS it is GONE, and Home behaves as it does without it.
 *
 * The table is read on one background thread when Home resumes, and again on a change while Home is in front (a
 * ContentObserver registered only between onResume and onPause). Nothing polls.
 */
final class WatchNextRow {

    interface Host {
        void onWatchNextFocused(View card, WatchNext.Program program);

        /** Up from a card: to the top bar. */
        void onWatchNextUp(View card);

        /** Down from a card: to the dock. */
        void onWatchNextDown();

        void onWatchNextOpen(WatchNext.Program program);

        /** The row went away (no cards left) while one of its cards had focus. */
        void onWatchNextGone();

        /** The row just appeared (its first cards arrived). */
        void onWatchNextShown();

        /** The row's cards changed: the host can prepare what they need, such as their backdrops. */
        void onWatchNextRows(List<WatchNext.Program> rows);
    }

    /** Not in the public SDK's Manifest.permission (it is a TvContract permission), so named here. */
    private static final String READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";
    private static final int MAX_CARDS = 10;
    private static final float DESIGN_W_DP = 160f, DESIGN_H_DP = 90f;
    private static final float MAX_POSTER_DP = 96f, MIN_POSTER_DP = 54f;
    /** Room above and below the cards for the focus zoom. */
    private static final float ZOOM_ROOM_DP = 7f;
    /** Cards start under the name above the dock (activity_main.xml's hero, 68dp from the left). */
    private static final float START_DP = 68f;
    private static final float FOCUS_SCALE = 1.10f;

    private final Activity activity;
    private final Host host;
    private final Handler handler;
    private final float density;
    private final boolean animations;
    private final boolean reducedMotion;
    private final Set<String> packages;
    private final long maxAgeMs;
    private final ContentResolver resolver;

    private final LinearLayout container;
    private final TextView header;
    /** home_sections_layout: the focused card's title and episode line under the row; null for the row above the dock. */
    private final TextView caption, captionSub;
    /** home_sections_layout: the space between cards (the item layout's own margin otherwise). */
    private int gap = -1;
    private final HorizontalScrollView strip;
    private final LinearLayout cards;

    /** Posters by URI, at card size in RGB_565: ten cards come to well under 2 MB. */
    private final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    /** Posters that failed to open or decode, with the time of the failure: tried again after RETRY_MS. */
    private final java.util.Map<String, Long> failed = java.util.Collections.synchronizedMap(new java.util.HashMap<>());
    /** The app's image provider can fail for a moment (its process starting, a download): try again after this. */
    private static final long RETRY_MS = 10_000;

    private ExecutorService executor;
    private int generation;
    private List<WatchNext.Program> shown = Collections.emptyList();
    private String signature = "";
    private int focusIndex;
    private boolean fits;
    private int cardW, cardH;
    private int placedTop = -1, placedBottom = -1;
    /** Height of a card's unit minus its poster: the two lines under it. Measured once. */
    private int linesH = -1;
    private boolean observing;
    private boolean destroyed;
    private boolean permissionLogged;
    /** The room place() was last given, in dp, for the refresh log. */
    private int roomDp = -1;

    private final Runnable refreshNow = this::refresh;
    private final ContentObserver observer;

    /** The row, added to root under the view `above` (the glass), or null when watch_next_row is off. */
    static WatchNextRow create(Activity activity, FrameLayout root, View above, Host host, Handler handler,
                               boolean animations, boolean reducedMotion) {
        if (!activity.getResources().getBoolean(R.bool.watch_next_row)) return null;
        WatchNextRow row = new WatchNextRow(activity, host, handler, animations, reducedMotion,
                new LinearLayout(activity), null, null, Math.round(START_DP * activity.getResources().getDisplayMetrics().density));
        row.container.setGravity(Gravity.BOTTOM);
        row.container.setVisibility(View.GONE);
        root.addView(row.container, root.indexOfChild(above), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP));
        return row;
    }

    /**
     * home_sections_layout: the row as Home's Continue watching section. section (home_sections.xml's wnSection) holds
     * the header and the two caption lines; the strip goes between them. Cards are the size of the app tiles
     * (tileW x tileH, gap apart), have no lines of their own, and the captions name the focused one. edge is the
     * section's distance from the screen's left edge: the strip runs to both screen edges, so a card scrolls in and
     * out under the screen's edge rather than at the section's margin. Null when watch_next_row is off.
     */
    static WatchNextRow createInSection(Activity activity, LinearLayout section, Host host, Handler handler,
                                        boolean animations, boolean reducedMotion, int tileW, int tileH, int gap,
                                        int edge) {
        if (!activity.getResources().getBoolean(R.bool.watch_next_row)) return null;
        WatchNextRow row = new WatchNextRow(activity, host, handler, animations, reducedMotion, section,
                section.findViewById(R.id.wnCaption), section.findViewById(R.id.wnCaptionSub), edge);
        row.fits = true;
        row.cardW = tileW;
        row.cardH = tileH;
        row.gap = gap;
        ViewGroup.MarginLayoutParams sp = (ViewGroup.MarginLayoutParams) row.strip.getLayoutParams();
        sp.leftMargin = -edge;
        sp.rightMargin = -edge;
        sp.topMargin = Math.round(6 * row.density);
        row.strip.setLayoutParams(sp);
        return row;
    }

    private WatchNextRow(Activity activity, Host host, Handler handler, boolean animations, boolean reducedMotion,
                         LinearLayout container, TextView caption, TextView captionSub, int edge) {
        this.activity = activity;
        this.host = host;
        this.handler = handler;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.resolver = activity.getContentResolver();
        Resources res = activity.getResources();
        Set<String> wanted = new HashSet<>();
        for (String p : res.getStringArray(R.array.watch_next_packages)) if (!p.trim().isEmpty()) wanted.add(p.trim());
        this.packages = wanted;
        this.maxAgeMs = Math.max(0, res.getInteger(R.integer.watch_next_max_age_days)) * WatchNext.DAY_MS;
        this.caption = caption;
        this.captionSub = captionSub;
        this.observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                // An app writes its rows in bursts: read once it has finished.
                handler.removeCallbacks(refreshNow);
                handler.postDelayed(refreshNow, 1000);
            }
        };

        this.container = container;
        container.setOrientation(LinearLayout.VERTICAL);
        container.setClipChildren(false);
        container.setClipToPadding(false);

        if (caption == null) {
            header = new TextView(activity);
            header.setText(R.string.watch_next_title);
            header.setAllCaps(true);
            header.setSingleLine(true);
            header.setTextColor(0x8CFFFFFF);
            header.setTextSize(12f);
            header.setLetterSpacing(0.22f);
            header.setTypeface(UiFont.get(activity, R.string.font_medium));
            header.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.leftMargin = edge + Math.round(2 * density);
            container.addView(header, hp);
        } else {
            header = container.findViewById(R.id.wnHeader);
        }

        final Rect grown = new Rect();
        strip = new HorizontalScrollView(activity) {
            /** Keep a focused card clear of the screen edge by the same margin the first card has. */
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                grown.set(rect);
                grown.inset(-edge, 0);
                return super.computeScrollDeltaToGetChildRectOnScreen(grown);
            }
        };
        // Scrolled in one step before the focus listener runs, so the glass is placed on the card's final spot.
        strip.setSmoothScrollingEnabled(false);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
        strip.setFocusable(false);
        strip.setClipToPadding(false);
        strip.setClipChildren(false);
        cards = new LinearLayout(activity);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setClipChildren(false);
        cards.setClipToPadding(false);
        int zoom = Math.round(ZOOM_ROOM_DP * density);
        cards.setPadding(edge, zoom, edge, zoom);
        strip.addView(cards, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Math.round(2 * density);
        container.addView(strip, container.indexOfChild(header) + 1, sp);
    }

    // ---- lifecycle --------------------------------------------------------------------------------------------

    /** Home in front: read the table now, and again whenever it changes until pause(). */
    void resume() {
        if (destroyed) return;
        if (!permitted()) {
            if (!permissionLogged) Log.i(WatchNext.TAG, "READ_TV_LISTINGS not granted: the row stays hidden");
            permissionLogged = true;
            apply(Collections.emptyList());
            return;
        }
        permissionLogged = false;
        if (!observing) {
            try {
                resolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer);
                observing = true;
            } catch (RuntimeException e) {
                Log.w(WatchNext.TAG, "no observer: " + e);
            }
        }
        refresh();
    }

    void pause() {
        handler.removeCallbacks(refreshNow);
        if (observing) {
            resolver.unregisterContentObserver(observer);
            observing = false;
        }
    }

    void destroy() {
        pause();
        destroyed = true;
        generation++;
        if (executor != null) executor.shutdownNow();
    }

    /** Memory is short: drop the posters; the next refresh decodes the ones it shows again. */
    void trim() {
        posters.evictAll();
        failed.clear();
        signature = "";
        for (int i = 0; i < cards.getChildCount(); i++) {
            ((ImageView) cards.getChildAt(i).findViewById(R.id.wnPoster)).setImageDrawable(null);
        }
    }

    private boolean permitted() {
        return activity.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private void refresh() {
        if (destroyed || !permitted()) return;
        if (executor == null) executor = Executors.newSingleThreadExecutor();
        final int run = ++generation;
        final int w = cardW > 0 ? cardW : Math.round(DESIGN_W_DP * density);
        final int h = cardH > 0 ? cardH : Math.round(DESIGN_H_DP * density);
        final long now = System.currentTimeMillis();
        final int room = roomDp;
        final boolean fit = fits;
        executor.execute(() -> {
            List<WatchNext.Program> all = WatchNext.query(resolver);
            List<WatchNext.Program> kept = WatchNext.pick(all, packages, now, maxAgeMs, MAX_CARDS);
            Log.i(WatchNext.TAG, "refresh: " + all.size() + " rows seen " + WatchNext.perPackage(all) + ", "
                    + kept.size() + " kept for " + (packages.isEmpty() ? "every app" : packages)
                    + " (max age " + maxAgeMs / WatchNext.DAY_MS + " days, watched at "
                    + Math.round(WatchNext.WATCHED * 100) + "%); " + (caption != null ? "Home section" : room < 0 ? "not placed yet"
                    : room + " dp above the dock" + (fit ? "" : ", too little: the row stays hidden")));
            // One line per row, newest first, with the verdict: the info line above only gives the counts.
            List<WatchNext.Program> byAge = new ArrayList<>(all);
            byAge.sort((a, b) -> Long.compare(b.engaged, a.engaged));
            int fromThumbnail = 0, fromPoster = 0;
            for (WatchNext.Program p : kept) {
                if (p.thumbnailBackdrop) fromThumbnail++;
                else if (p.backdrop != null) fromPoster++;
            }
            Log.i(WatchNext.TAG, "backdrops of the kept rows: " + fromThumbnail + " local thumbnail, " + fromPoster
                    + " local poster, " + (kept.size() - fromThumbnail - fromPoster) + " none");
            for (WatchNext.Program p : byAge) {
                String reason = WatchNext.dropReason(p, packages, now, maxAgeMs);
                String verdict = reason != null ? "dropped: " + reason : kept.contains(p) ? "kept" : "dropped: over cap";
                Log.d(WatchNext.TAG, WatchNext.describe(p, now, verdict));
            }
            for (WatchNext.Program p : kept) {
                if (p.poster == null || posters.get(p.poster) != null) continue;
                Long failedAt = failed.get(p.poster);
                if (failedAt != null && android.os.SystemClock.elapsedRealtime() - failedAt < RETRY_MS) continue;
                Bitmap b = WatchNext.decode(resolver, p.poster, w, h);
                if (b != null) {
                    posters.put(p.poster, b);
                    failed.remove(p.poster);
                } else {
                    failed.put(p.poster, android.os.SystemClock.elapsedRealtime());
                }
            }
            handler.post(() -> {
                if (run == generation && !destroyed) apply(kept);
            });
        });
    }

    // ---- layout -----------------------------------------------------------------------------------------------

    /**
     * Fit the row between top (under the top bar) and bottom (above the name over the dock), in root coordinates.
     * The poster takes what the room leaves, up to MAX_POSTER_DP; under MIN_POSTER_DP the row does not show.
     */
    void place(int top, int bottom) {
        if (top == placedTop && bottom == placedBottom) return;
        placedTop = top;
        placedBottom = bottom;
        if (linesH < 0) linesH = measureLines();
        header.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int fixed = header.getMeasuredHeight() + Math.round(2 * density) + 2 * Math.round(ZOOM_ROOM_DP * density) + linesH;
        int poster = Math.min(Math.round(MAX_POSTER_DP * density), bottom - top - fixed);
        fits = poster >= Math.round(MIN_POSTER_DP * density);
        roomDp = Math.round((bottom - top) / density);
        if (fits) {
            cardH = poster;
            cardW = Math.round(poster * DESIGN_W_DP / DESIGN_H_DP);
            for (int i = 0; i < cards.getChildCount(); i++) size(cards.getChildAt(i));
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
        lp.height = Math.max(0, bottom - top);
        lp.topMargin = top;
        container.setLayoutParams(lp);
        updateVisibility();
    }

    /** The grid scrolled down past the dock: the row rides up with it and fades out. */
    void setShift(int shift) {
        container.setTranslationY(-shift);
        float room = Math.max(1, placedBottom - placedTop);
        container.setAlpha(Math.max(0f, 1f - shift / room));
    }

    private int measureLines() {
        View unit = LayoutInflater.from(activity).inflate(R.layout.item_watch_next, cards, false);
        ((TextView) unit.findViewById(R.id.wnTitle)).setText(R.string.watch_next_title);
        ((TextView) unit.findViewById(R.id.wnSub)).setText(R.string.watch_next_title);
        unit.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        return unit.getMeasuredHeight() - unit.findViewById(R.id.wnCard).getLayoutParams().height;
    }

    boolean isShown() {
        return container.getVisibility() == View.VISIBLE;
    }

    boolean hasFocus() {
        return container.hasFocus();
    }

    /** Home pressed: the row starts over at its first card. */
    void resetFocus() {
        focusIndex = 0;
    }

    /** Focus the card the row last had, or its first. False when the row is not showing. */
    boolean focus() {
        if (!isShown() || cards.getChildCount() == 0) return false;
        focusIndex = Math.max(0, Math.min(focusIndex, cards.getChildCount() - 1));
        View card = cards.getChildAt(focusIndex).findViewById(R.id.wnCard);
        if (card.isFocused()) host.onWatchNextFocused(card, shown.get(focusIndex));
        else card.requestFocus();
        return true;
    }

    private void updateVisibility() {
        boolean show = fits && !shown.isEmpty();
        if (show == isShown()) return;
        boolean hadFocus = container.hasFocus();
        container.setVisibility(show ? View.VISIBLE : View.GONE);
        if (hadFocus && !show) host.onWatchNextGone();
        if (show) host.onWatchNextShown();
    }

    // ---- cards ------------------------------------------------------------------------------------------------

    private void apply(List<WatchNext.Program> list) {
        StringBuilder sig = new StringBuilder();
        // Whether each poster is decoded is part of the signature: a poster that arrives on a later refresh rebinds
        // the cards instead of leaving the card without its picture.
        for (WatchNext.Program p : list) {
            sig.append(p.signature()).append(p.poster != null && posters.get(p.poster) != null ? "+" : "-").append('\n');
        }
        if (sig.toString().equals(signature)) {
            updateVisibility();
            return;
        }
        boolean hadFocus = container.hasFocus();
        long focusedId = hadFocus && focusIndex < shown.size() ? shown.get(focusIndex).id : -1;
        signature = sig.toString();
        int keep = focusIndex;
        if (!list.isEmpty()) {
            // The card that had focus keeps it if it is still there; otherwise the one in its place.
            for (int i = 0; i < list.size(); i++) if (list.get(i).id == focusedId) keep = i;
        }
        shown = new ArrayList<>(list);
        host.onWatchNextRows(shown);
        cards.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (int i = 0; i < shown.size(); i++) cards.addView(bind(inflater.inflate(R.layout.item_watch_next, cards, false), i));
        focusIndex = Math.max(0, Math.min(keep, shown.size() - 1));
        if (!shown.isEmpty()) showCaption(shown.get(focusIndex));
        boolean wasShown = isShown();
        updateVisibility();
        if (hadFocus && wasShown && isShown()) focus();
    }

    /** home_sections_layout: the captions under the row name this card (the last focused one while focus is away). */
    private void showCaption(WatchNext.Program p) {
        if (caption == null) return;
        caption.setText(p.title);
        String line = p.episodeLine();
        captionSub.setText(line);
        captionSub.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void size(View unit) {
        if (cardW <= 0) return;
        unit.getLayoutParams().width = cardW;
        View card = unit.findViewById(R.id.wnCard);
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        lp.width = cardW;
        lp.height = cardH;
        card.setLayoutParams(lp);
        card.invalidateOutline();
        if (gap >= 0) ((ViewGroup.MarginLayoutParams) unit.getLayoutParams()).rightMargin = gap;
    }

    private View bind(View unit, int index) {
        WatchNext.Program p = shown.get(index);
        size(unit);
        View card = unit.findViewById(R.id.wnCard);
        ImageView poster = unit.findViewById(R.id.wnPoster);
        TextView fallback = unit.findViewById(R.id.wnFallback);
        Bitmap art = p.poster == null ? null : posters.get(p.poster);
        fallback.setText(p.title);
        fallback.setVisibility(art == null ? View.VISIBLE : View.INVISIBLE);
        if (art != null) {
            // A portrait poster is shown whole on the dark tile rather than cut to a strip across its middle.
            poster.setScaleType(art.getHeight() > art.getWidth() ? ImageView.ScaleType.FIT_CENTER
                    : ImageView.ScaleType.CENTER_CROP);
            poster.setImageBitmap(art);
        }
        float progress = p.progress();
        View track = unit.findViewById(R.id.wnTrack);
        track.setVisibility(progress > 0f ? View.VISIBLE : View.GONE);
        if (progress > 0f) {
            View fill = unit.findViewById(R.id.wnFill);
            fill.setPivotX(0f);
            fill.setScaleX(progress);
        }
        String line = p.episodeLine();
        ((TextView) unit.findViewById(R.id.wnTitle)).setText(p.title);
        ((TextView) unit.findViewById(R.id.wnSub)).setText(line);
        if (caption != null) {
            // The section's captions name the focused card; the cards carry no lines of their own.
            unit.findViewById(R.id.wnTitle).setVisibility(View.GONE);
            unit.findViewById(R.id.wnSub).setVisibility(View.GONE);
        }
        card.setContentDescription(line.isEmpty() ? p.title : p.title + ", " + line);

        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        AppAdapter.cornerRadius(view.getWidth(), density));
            }
        });
        card.setClipToOutline(true);
        card.setOnClickListener(v -> host.onWatchNextOpen(p));
        card.setOnFocusChangeListener((v, focused) -> {
            v.animate().cancel();
            float s = focused && animations ? FOCUS_SCALE : 1f;
            if (!animations) {
                v.setScaleX(1f);
                v.setScaleY(1f);
            } else {
                v.animate().scaleX(s).scaleY(s)
                        .setDuration(reducedMotion ? Motion.REDUCED_MS : focused ? 260 : 180)
                        .setInterpolator(new DecelerateInterpolator(1.5f)).start();
            }
            if (focused) {
                focusIndex = index;
                showCaption(p);
                host.onWatchNextFocused(v, p);
            }
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    host.onWatchNextUp(v);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    host.onWatchNextDown();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return index == 0;                      // the row's ends: focus stays put
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return index == shown.size() - 1;
                default:
                    return false;
            }
        });
        return unit;
    }

    float focusScale() {
        return animations ? FOCUS_SCALE : 1f;
    }
}
