package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The sectioned Home (home_sections_layout in res/values/watch_next.xml): under the top bar, a Continue watching
 * section (WatchNextRow in section mode) and an Apps section with the dock apps in dock_apps order and an All apps
 * tile, each with the focused item's name under its row. The app grid becomes the full-screen All apps drawer, which
 * MainActivity shows in place of these sections. This class builds the Apps row and wires its keys; MainActivity
 * owns the focus moves between rows, the glass and the drawer.
 */
final class HomeSections {

    interface Host {
        /** A tile of the Apps row took focus: the glass goes to it. */
        void onSectionTileFocused(View tile);

        /** Up from the Apps row: to Continue watching, else the top bar. */
        void onAppsUp(View tile);

        void openDrawer();
    }

    private static final float FOCUS_SCALE = 1.10f;

    private final Activity activity;
    private final Host host;
    private final AppAdapter adapter;
    private final List<String> dockApps;
    private final float density;
    private final boolean animations;
    private final boolean reducedMotion;
    final int tileW, tileH, gap;

    final View sections;
    final LinearLayout wnSection;
    private final View drawerHeader;
    private final TextView drawerCount;
    private final LinearLayout appsRow;
    private final TextView appsCaption;
    /** The packages of the Apps row's app tiles, in order; the All apps tile comes after them. */
    private final List<String> shownPackages = new ArrayList<>();
    private int focusIndex;

    /**
     * Inflates home_sections.xml into column (activity_main.xml's vertical column) right under the top bar.
     * tileW x tileH is the drawer's tile size, and gap the space between two tiles of its grid, so the rows line up
     * with the drawer's columns.
     */
    HomeSections(Activity activity, Host host, AppAdapter adapter, List<String> dockApps, LinearLayout column,
                 int tileW, int tileH, int gap, boolean animations, boolean reducedMotion) {
        this.activity = activity;
        this.host = host;
        this.adapter = adapter;
        this.dockApps = dockApps;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.tileW = tileW;
        this.tileH = tileH;
        this.gap = gap;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        int before = column.getChildCount();
        LayoutInflater.from(activity).inflate(R.layout.home_sections, column, true);
        // The merge's two views land at the end of the column; move them up under the top bar.
        List<View> added = new ArrayList<>();
        for (int i = before; i < column.getChildCount(); i++) added.add(column.getChildAt(i));
        for (int i = 0; i < added.size(); i++) {
            View v = added.get(i);
            column.removeView(v);
            column.addView(v, 1 + i);
        }
        sections = column.findViewById(R.id.homeSections);
        wnSection = column.findViewById(R.id.wnSection);
        drawerHeader = column.findViewById(R.id.drawerHeader);
        drawerCount = column.findViewById(R.id.drawerCount);
        appsRow = column.findViewById(R.id.appsRow);
        appsCaption = column.findViewById(R.id.appsCaption);
    }

    /** The sections for Home, or the drawer's header (with its app count) while All apps is open. */
    void showDrawer(boolean open, int appCount) {
        sections.setVisibility(open ? View.GONE : View.VISIBLE);
        drawerHeader.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) drawerCount.setText(activity.getResources().getQuantityString(R.plurals.library_app_count, appCount, appCount));
    }

    /** Rebuilds the Apps row from the adapter's tiles (after every reload), keeping focus on the same slot. */
    void bindApps() {
        boolean hadFocus = appsRow.hasFocus();
        shownPackages.clear();
        appsRow.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (String pkg : dockApps) {
            int pos = adapter.positionOfPackage(pkg);
            if (pos < 0 || shownPackages.contains(pkg)) continue;
            shownPackages.add(pkg);
            View tile = inflater.inflate(R.layout.item_home_tile, appsRow, false);
            bindApp(tile, pos);
            add(tile, adapter.labelAt(pos), shownPackages.size() - 1);
        }
        View all = inflater.inflate(R.layout.item_home_tile, appsRow, false);
        all.setBackgroundResource(R.drawable.all_apps_tile);
        all.findViewById(R.id.homeTileIconBox).setVisibility(View.VISIBLE);
        ((ImageView) all.findViewById(R.id.homeTileIcon)).setImageResource(R.drawable.ic_apps);
        ((TextView) all.findViewById(R.id.homeTileLabel)).setText(R.string.all_apps);
        add(all, activity.getString(R.string.all_apps), shownPackages.size());
        focusIndex = Math.max(0, Math.min(focusIndex, appsRow.getChildCount() - 1));
        appsCaption.setText(captionAt(focusIndex));
        if (hadFocus) focusApps();
    }

    /**
     * A drawable of its own for a Home tile, sharing the drawer tile's bitmap: one Drawable drawn by two views would
     * take the bounds and callback of whichever set them last.
     */
    private Drawable copy(Drawable d) {
        Drawable.ConstantState state = d == null ? null : d.getConstantState();
        return state == null ? d : state.newDrawable(activity.getResources());
    }

    private void bindApp(View tile, int pos) {
        Drawable banner = copy(adapter.bannerAt(pos));
        ImageView image = tile.findViewById(R.id.homeTileBanner);
        if (banner != null) {
            image.setImageDrawable(banner);
            // An opaque banner covers the tile: its dark fill underneath would only be drawn for nothing.
            if (banner.getOpacity() == PixelFormat.OPAQUE) tile.setBackground(null);
        } else {
            image.setVisibility(View.GONE);
            tile.findViewById(R.id.homeTileIconBox).setVisibility(View.VISIBLE);
            ((ImageView) tile.findViewById(R.id.homeTileIcon)).setImageDrawable(copy(adapter.iconAt(pos)));
            ((TextView) tile.findViewById(R.id.homeTileLabel)).setText(adapter.labelAt(pos));
        }
    }

    private String captionAt(int index) {
        if (index < shownPackages.size()) {
            int pos = adapter.positionOfPackage(shownPackages.get(index));
            return pos < 0 ? "" : adapter.labelAt(pos);
        }
        return activity.getString(R.string.all_apps);
    }

    private void add(View tile, String label, int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
        lp.rightMargin = gap;
        tile.setContentDescription(label);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        AppAdapter.cornerRadius(view.getWidth(), density));
            }
        });
        tile.setClipToOutline(true);
        tile.setOnClickListener(v -> {
            if (index >= shownPackages.size()) {
                host.openDrawer();
                return;
            }
            // Looked up at the click: a reload since the row was built may have moved the tile.
            int pos = adapter.positionOfPackage(shownPackages.get(index));
            if (pos >= 0) adapter.launchAt(pos);
        });
        tile.setOnFocusChangeListener((v, focused) -> {
            v.animate().cancel();
            if (!animations) {
                v.setScaleX(1f);
                v.setScaleY(1f);
            } else {
                float s = focused ? FOCUS_SCALE : 1f;
                v.animate().scaleX(s).scaleY(s)
                        .setDuration(reducedMotion ? Motion.REDUCED_MS : focused ? 260 : 180)
                        .setInterpolator(new DecelerateInterpolator(1.5f)).start();
            }
            if (focused) {
                focusIndex = index;
                appsCaption.setText(label);
                host.onSectionTileFocused(v);
            }
        });
        tile.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    host.onAppsUp(v);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;                                    // nothing below the Apps row
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return index == 0;                              // the row's ends: focus stays put
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return index == appsRow.getChildCount() - 1;
                default:
                    return false;
            }
        });
        appsRow.addView(tile, lp);
    }

    float focusScale() {
        return animations ? FOCUS_SCALE : 1f;
    }

    /** Focus the Apps row's last focused tile (the first after Home). */
    void focusApps() {
        if (appsRow.getChildCount() == 0) return;
        focusIndex = Math.max(0, Math.min(focusIndex, appsRow.getChildCount() - 1));
        View tile = appsRow.getChildAt(focusIndex);
        if (tile.isFocused()) host.onSectionTileFocused(tile);
        else tile.requestFocus();
    }

    /** Back from the drawer: the All apps tile, where it was opened from. */
    void focusAllApps() {
        focusIndex = appsRow.getChildCount() - 1;
        focusApps();
    }

    /** Home pressed: the row starts over at its first tile. */
    void resetFocus() {
        focusIndex = 0;
    }

    boolean hasFocus() {
        return appsRow.hasFocus();
    }

    /** The package of an app tile of the row, or null for the All apps tile (or a view not in the row). */
    String packageOf(View tile) {
        int index = appsRow.indexOfChild(tile);
        return index >= 0 && index < shownPackages.size() ? shownPackages.get(index) : null;
    }

    static boolean isTile(View v) {
        return v != null && v.getId() == R.id.homeTile;
    }

    /** Memory is short: the app tiles let go of their artwork until the next bindApps(). */
    void clear() {
        for (int i = 0; i < shownPackages.size() && i < appsRow.getChildCount(); i++) {
            ((ImageView) appsRow.getChildAt(i).findViewById(R.id.homeTileBanner)).setImageDrawable(null);
            ((ImageView) appsRow.getChildAt(i).findViewById(R.id.homeTileIcon)).setImageDrawable(null);
        }
    }
}
