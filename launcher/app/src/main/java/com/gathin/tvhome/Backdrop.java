package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The sectioned Home's backdrop (home_sections_layout). The radial gradient behind Home is always there; with
 * home_dynamic_backdrop on it follows focus. A Continue watching card shows its local thumbnail (else poster) across
 * the whole screen under a dark scrim; an app tile tints the gradient with the app's colour; anything else (the All
 * apps tile, the top bar, the drawer) gives the plain gradient.
 *
 * The image is soft without a blur: it is decoded about 100 px wide and stretched with bilinear filtering. Changes
 * wait 150 ms so a quick run along a row decodes nothing on the way, are decoded on one background thread and
 * cross-fade over two stacked ImageViews. The last few images stay in a small cache.
 */
final class Backdrop {

    /** The design's gradient: deep blue left of centre, fading to black at the edges. */
    static final int[] NEUTRAL = {0xFF0B3550, 0xFF061A2A, 0xFF02070D, 0xFF000000};
    private static final int FADE_MS = 200;
    private static final int SETTLE_MS = 80;
    /** Decode size: close to the screen's 960x540 dp so the image stays sharp; about 1 MB each in RGB_565. */
    private static final int DECODE_W = 960, DECODE_H = 540;

    private final Handler handler;
    private final ContentResolver resolver;
    private final GradientDrawable gradient;
    private final boolean animated;
    private final boolean dynamic;
    private final ImageView[] images = new ImageView[2];
    private final View scrim;
    private int front;
    private final LruCache<String, Bitmap> cache = new LruCache<>(4);
    private ExecutorService executor;

    private int[] colors = NEUTRAL.clone();
    private ValueAnimator colorAnimator;
    /** What was asked for last (applied once focus has rested): an image URI, else the tint colour (0 = neutral). */
    private String wantUri;
    /** Pictures that already had their one retry after a failed decode. */
    private final java.util.Set<String> retried = new java.util.HashSet<>();
    private int wantTint;
    /** The image on screen, or null while the gradient shows. */
    private String shownUri;
    private int generation;
    private boolean destroyed;
    private final Runnable apply = this::apply;

    /**
     * The gradient goes on root's background. With dynamic (home_dynamic_backdrop) the two image views and the scrim
     * are added to root right above the view `below` (the sky), under everything else.
     */
    Backdrop(Context context, FrameLayout root, View below, Handler handler, int screenW, boolean dynamic,
             boolean animations, boolean reducedMotion) {
        this.handler = handler;
        this.resolver = context.getContentResolver();
        this.dynamic = dynamic;
        this.animated = animations && !reducedMotion;
        gradient = new GradientDrawable();
        gradient.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gradient.setColors(colors);
        gradient.setGradientCenter(0.34f, 0.42f);
        gradient.setGradientRadius(screenW * 0.72f);
        root.setBackground(gradient);
        if (!dynamic) {
            scrim = null;
            return;
        }
        int at = root.indexOfChild(below) + 1;
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(context);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            images[i] = v;
            root.addView(v, at++, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        scrim = new View(context);
        scrim.setBackground(scrimDrawable());
        scrim.setAlpha(0f);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(scrim, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * 60% black over the whole image, darker still towards the left and the bottom, where the headers, captions and
     * tiles are.
     */
    private static Drawable scrimDrawable() {
        GradientDrawable left = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x99000000, 0x33000000, 0x00000000});
        GradientDrawable bottom = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x4D000000, 0xB3000000});
        return new LayerDrawable(new Drawable[]{new ColorDrawable(0x99000000), left, bottom});
    }

    /**
     * Decodes the first cards' images ahead of focus, so moving onto a card shows its image at once. An app's image
     * provider can take seconds when the app is not running (Jellyfin starts and fetches the picture from its server).
     */
    void prefetch(java.util.List<String> uris) {
        if (!dynamic || destroyed || uris.isEmpty()) return;
        if (executor == null) executor = Executors.newSingleThreadExecutor();
        for (String uri : uris) {
            if (uri == null || cache.get(uri) != null) continue;
            executor.execute(() -> {
                if (destroyed || cache.get(uri) != null) return;
                Bitmap b = WatchNext.decode(resolver, uri, DECODE_W, DECODE_H);
                if (b == null) return;
                handler.post(() -> {
                    if (destroyed) return;
                    cache.put(uri, b);
                    // The card already has focus and is still waiting for this picture: show it now.
                    if (uri.equals(wantUri) && !uri.equals(shownUri)) showBitmap(uri, b);
                });
            });
        }
    }

    /** A Continue watching card took focus: its image, or the plain gradient when it has no local one. */
    void showImage(String uri) {
        if (!dynamic) return;
        request(uri, 0);
    }

    /** An app tile took focus: the gradient in its colour; 0 for the plain gradient. */
    void showTint(int color) {
        if (!dynamic) return;
        request(null, color);
    }

    private void request(String uri, int tint) {
        wantUri = uri;
        wantTint = tint;
        handler.removeCallbacks(apply);
        handler.postDelayed(apply, SETTLE_MS);
    }

    private void apply() {
        if (destroyed) return;
        final String uri = wantUri;
        if (uri == null) {
            showGradient(wantTint == 0 ? NEUTRAL : tinted(wantTint));
            return;
        }
        if (uri.equals(shownUri)) return;
        Bitmap cached = cache.get(uri);
        if (cached != null) {
            showBitmap(uri, cached);
            return;
        }
        if (executor == null) executor = Executors.newSingleThreadExecutor();
        final int run = ++generation;
        executor.execute(() -> {
            Bitmap b = WatchNext.decode(resolver, uri, DECODE_W, DECODE_H);
            handler.post(() -> {
                if (destroyed || run != generation || !uri.equals(wantUri)) return;
                if (b == null) {
                    showGradient(NEUTRAL);
                    // The provider can fail for a moment (the same picture being read for its card, the app
                    // starting): one more try shortly, as long as the same card still has focus.
                    if (!retried.contains(uri)) {
                        retried.add(uri);
                        handler.postDelayed(() -> {
                            if (!destroyed && uri.equals(wantUri)) { shownUri = null; apply(); }
                        }, 600);
                    }
                    return;
                }
                retried.remove(uri);
                cache.put(uri, b);
                showBitmap(uri, b);
            });
        });
    }

    private void showBitmap(String uri, Bitmap b) {
        shownUri = uri;
        animateColors(NEUTRAL);
        ImageView back = images[1 - front], top = images[front];
        back.animate().cancel();
        top.animate().cancel();
        back.setImageBitmap(b);
        // A cross-fade: the new image comes up while the old one goes, under a scrim that stays up between images.
        if (animated) {
            back.animate().alpha(1f).setDuration(FADE_MS).start();
            top.animate().alpha(0f).setDuration(FADE_MS).start();
            scrim.animate().alpha(1f).setDuration(FADE_MS).start();
        } else {
            back.setAlpha(1f);
            top.setAlpha(0f);
            scrim.setAlpha(1f);
        }
        front = 1 - front;
    }

    private void showGradient(int[] target) {
        animateColors(target);
        if (shownUri == null) return;
        shownUri = null;
        for (ImageView v : images) {
            v.animate().cancel();
            if (animated) v.animate().alpha(0f).setDuration(FADE_MS).start();
            else v.setAlpha(0f);
        }
        if (animated) scrim.animate().alpha(0f).setDuration(FADE_MS).start();
        else scrim.setAlpha(0f);
    }

    private void animateColors(int[] target) {
        if (java.util.Arrays.equals(colors, target)) return;
        if (colorAnimator != null) colorAnimator.cancel();
        if (!animated) {
            colors = target.clone();
            gradient.setColors(colors);
            return;
        }
        final int[] from = colors.clone();
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS);
        colorAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            int[] now = new int[target.length];
            for (int i = 0; i < now.length; i++) now[i] = mix(from[i], target[i], t);
            colors = now;
            gradient.setColors(now);
        });
        colorAnimator.start();
    }

    /** The gradient in an app's colour, kept dark: the centre a deep shade of it, the edges as before. */
    static int[] tinted(int color) {
        return new int[]{mix(color | 0xFF000000, 0xFF000000, 0.68f), mix(color | 0xFF000000, 0xFF000000, 0.84f),
                NEUTRAL[2], NEUTRAL[3]};
    }

    /** a moved towards b by t (0..1), per channel. */
    static int mix(int a, int b, float t) {
        int r = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
            r |= (Math.round(ca + (cb - ca) * t) & 0xFF) << shift;
        }
        return r;
    }

    /** Memory is short (Home is in the background): drop the images; the next focus decodes again. */
    void trim() {
        cache.evictAll();
        if (!dynamic) return;
        for (ImageView v : images) {
            v.animate().cancel();
            v.setImageDrawable(null);
            v.setAlpha(0f);
        }
        scrim.setAlpha(0f);
        shownUri = null;
    }

    void pause() {
        handler.removeCallbacks(apply);
    }

    void destroy() {
        destroyed = true;
        pause();
        if (colorAnimator != null) colorAnimator.cancel();
        if (executor != null) executor.shutdownNow();
    }
}
