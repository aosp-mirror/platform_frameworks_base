/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.accessibility.accessibilitymenu.view;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;

import static java.lang.Math.max;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService;
import com.android.systemui.accessibility.accessibilitymenu.R;
import com.android.systemui.accessibility.accessibilitymenu.activity.A11yMenuSettingsActivity.A11yMenuPreferenceFragment;
import com.android.systemui.accessibility.accessibilitymenu.model.A11yMenuShortcut;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides functionality for Accessibility menu layout in a11y menu overlay. There are functions to
 * configure or update Accessibility menu layout when orientation and display size changed, and
 * functions to toggle menu visibility when button clicked or screen off.
 */
public class A11yMenuOverlayLayout {

    /** Predefined default shortcuts when large button setting is off. */
    private static final int[] SHORTCUT_LIST_DEFAULT = {
        A11yMenuShortcut.ShortcutId.ID_ASSISTANT_VALUE.ordinal(),
        A11yMenuShortcut.ShortcutId.ID_A11YSETTING_VALUE.ordinal(),
        A11yMenuShortcut.ShortcutId.ID_POWER_VALUE.ordinal(),
        A11yMenuShortcut.ShortcutId.ID_VOLUME_DOWN_VALUE.ordinal(),
        A11yMenuShortcut.ShortcutId.ID_VOLUME_UP_VALUE.ordinal(),
        A11yMenuShortcut.ShortcutId.ID_RECENT_VALUE.ordinal(),
        A11yMenuShortcut.ShortcutId.ID_BRIGHTNESS_DOWN_VALUE.ordinal(),
        A11yMenuShortcut.ShortcutId.ID_BRIGHTNESS_UP_VALUE.ordinal(),
        A11yMenuShortcut.ShortcutId.ID_LOCKSCREEN_VALUE.ordinal(),
        A11yMenuShortcut.ShortcutId.ID_QUICKSETTING_VALUE.ordinal(),
        A11yMenuShortcut.ShortcutId.ID_NOTIFICATION_VALUE.ordinal(),
        A11yMenuShortcut.ShortcutId.ID_SCREENSHOT_VALUE.ordinal()
    };

    /** Predefined default shortcuts when large button setting is on. */
    private static final int[] LARGE_SHORTCUT_LIST_DEFAULT = {
            A11yMenuShortcut.ShortcutId.ID_ASSISTANT_VALUE.ordinal(),
            A11yMenuShortcut.ShortcutId.ID_A11YSETTING_VALUE.ordinal(),
            A11yMenuShortcut.ShortcutId.ID_POWER_VALUE.ordinal(),
            A11yMenuShortcut.ShortcutId.ID_RECENT_VALUE.ordinal(),
            A11yMenuShortcut.ShortcutId.ID_VOLUME_DOWN_VALUE.ordinal(),
            A11yMenuShortcut.ShortcutId.ID_VOLUME_UP_VALUE.ordinal(),
            A11yMenuShortcut.ShortcutId.ID_BRIGHTNESS_DOWN_VALUE.ordinal(),
            A11yMenuShortcut.ShortcutId.ID_BRIGHTNESS_UP_VALUE.ordinal(),
            A11yMenuShortcut.ShortcutId.ID_LOCKSCREEN_VALUE.ordinal(),
            A11yMenuShortcut.ShortcutId.ID_QUICKSETTING_VALUE.ordinal(),
            A11yMenuShortcut.ShortcutId.ID_NOTIFICATION_VALUE.ordinal(),
            A11yMenuShortcut.ShortcutId.ID_SCREENSHOT_VALUE.ordinal()
    };



    private final AccessibilityMenuService mService;
    private final WindowManager mWindowManager;
    private final DisplayManager mDisplayManager;
    private ViewGroup mLayout;
    private WindowManager.LayoutParams mLayoutParameter;
    private A11yMenuViewPager mA11yMenuViewPager;
    private Handler mHandler;
    private AccessibilityManager mAccessibilityManager;

    public A11yMenuOverlayLayout(AccessibilityMenuService service) {
        mService = service;
        mWindowManager = mService.getSystemService(WindowManager.class);
        mDisplayManager = mService.getSystemService(DisplayManager.class);
        configureLayout();
        mHandler = new Handler(Looper.getMainLooper());
        mAccessibilityManager = mService.getSystemService(AccessibilityManager.class);
    }

    /** Creates Accessibility menu layout and configure layout parameters. */
    public View configureLayout() {
        return configureLayout(A11yMenuViewPager.DEFAULT_PAGE_INDEX);
    }

    // TODO(b/78292783): Find a better way to inflate layout in the test.
    /**
     * Creates Accessibility menu layout, configure layout parameters and apply index to ViewPager.
     *
     * @param pageIndex the index of the ViewPager to show.
     */
    public View configureLayout(int pageIndex) {

        int lastVisibilityState = View.GONE;
        if (mLayout != null) {
            lastVisibilityState = mLayout.getVisibility();
            mWindowManager.removeView(mLayout);
            mLayout = null;
        }

        if (mLayoutParameter == null) {
            initLayoutParams();
        }

        final Display display = mDisplayManager.getDisplay(DEFAULT_DISPLAY);
        final Context context = mService.createDisplayContext(display).createWindowContext(
                TYPE_ACCESSIBILITY_OVERLAY, null);
        mLayout = new FrameLayout(context);
        updateLayoutPosition();
        inflateLayoutAndSetOnTouchListener(mLayout, context);
        mA11yMenuViewPager = new A11yMenuViewPager(mService, context);
        mA11yMenuViewPager.configureViewPagerAndFooter(mLayout, createShortcutList(), pageIndex);
        mWindowManager.addView(mLayout, mLayoutParameter);
        mLayout.setVisibility(lastVisibilityState);

        return mLayout;
    }

    public void clearLayout() {
        if (mLayout != null) {
            mWindowManager.removeView(mLayout);
            mLayout.setOnTouchListener(null);
            mLayout = null;
        }
    }

    /** Updates view layout with new layout parameters only. */
    public void updateViewLayout() {
        if (mLayout == null || mLayoutParameter == null) {
            return;
        }
        updateLayoutPosition();
        mWindowManager.updateViewLayout(mLayout, mLayoutParameter);
    }

    private void initLayoutParams() {
        mLayoutParameter = new WindowManager.LayoutParams();
        mLayoutParameter.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        mLayoutParameter.format = PixelFormat.TRANSLUCENT;
        mLayoutParameter.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        mLayoutParameter.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        mLayoutParameter.setTitle(mService.getString(R.string.accessibility_menu_service_name));
    }

    private void inflateLayoutAndSetOnTouchListener(ViewGroup view, Context displayContext) {
        LayoutInflater inflater = LayoutInflater.from(displayContext);
        inflater.inflate(R.layout.paged_menu, view);
        view.setOnTouchListener(mService);
    }

    /**
     * Loads shortcut data from default shortcut ID array.
     *
     * @return A list of default shortcuts
     */
    private List<A11yMenuShortcut> createShortcutList() {
        List<A11yMenuShortcut> shortcutList = new ArrayList<>();

        for (int shortcutId :
                (A11yMenuPreferenceFragment.isLargeButtonsEnabled(mService)
                        ? LARGE_SHORTCUT_LIST_DEFAULT : SHORTCUT_LIST_DEFAULT)) {
            shortcutList.add(new A11yMenuShortcut(shortcutId));
        }
        return shortcutList;
    }

    /** Updates a11y menu layout position by configuring layout params. */
    private void updateLayoutPosition() {
        final Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        final Configuration configuration = mService.getResources().getConfiguration();
        final int orientation = configuration.orientation;
        if (display != null && orientation == Configuration.ORIENTATION_LANDSCAPE) {
            final boolean ltr = configuration.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR;
            switch (display.getRotation()) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_180:
                    mLayoutParameter.gravity =
                            (ltr ? Gravity.END : Gravity.START) | Gravity.BOTTOM
                                    | Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL;
                    mLayoutParameter.width = WindowManager.LayoutParams.WRAP_CONTENT;
                    mLayoutParameter.height = WindowManager.LayoutParams.MATCH_PARENT;
                    mLayoutParameter.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                    mLayoutParameter.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
                    mLayout.setBackgroundResource(R.drawable.shadow_90deg);
                    break;
                case Surface.ROTATION_90:
                case Surface.ROTATION_270:
                    mLayoutParameter.gravity =
                            (ltr ? Gravity.START : Gravity.END) | Gravity.BOTTOM
                                    | Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL;
                    mLayoutParameter.width = WindowManager.LayoutParams.WRAP_CONTENT;
                    mLayoutParameter.height = WindowManager.LayoutParams.MATCH_PARENT;
                    mLayoutParameter.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                    mLayoutParameter.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
                    mLayout.setBackgroundResource(R.drawable.shadow_270deg);
                    break;
                default:
                    break;
            }
        } else {
            mLayoutParameter.gravity = Gravity.BOTTOM;
            mLayoutParameter.width = WindowManager.LayoutParams.MATCH_PARENT;
            mLayoutParameter.height = WindowManager.LayoutParams.WRAP_CONTENT;
            mLayout.setBackgroundResource(R.drawable.shadow_0deg);
        }

        // Adjusts the y position of a11y menu layout to make the layout not to overlap bottom
        // navigation bar window.
        updateLayoutByWindowInsetsIfNeeded();
        mLayout.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    if (updateLayoutByWindowInsetsIfNeeded()) {
                        mWindowManager.updateViewLayout(mLayout, mLayoutParameter);
                    }
                    return view.onApplyWindowInsets(insets);
                });
    }

    /**
     * Returns {@code true} if the a11y menu layout params
     * should be updated by {@link WindowManager} immediately due to window insets change.
     * This method adjusts the layout position and size to
     * make a11y menu not to overlap navigation bar window.
     */
    private boolean updateLayoutByWindowInsetsIfNeeded() {
        boolean shouldUpdateLayout = false;
        WindowMetrics windowMetrics = mWindowManager.getCurrentWindowMetrics();
        Insets windowInsets = windowMetrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        int xOffset = max(windowInsets.left, windowInsets.right);
        int yOffset = windowInsets.bottom;
        Rect windowBound = windowMetrics.getBounds();
        if (mLayoutParameter.x != xOffset || mLayoutParameter.y != yOffset) {
            mLayoutParameter.x = xOffset;
            mLayoutParameter.y = yOffset;
            shouldUpdateLayout = true;
        }
        // for gestural navigation mode and the landscape mode,
        // the layout height should be decreased by system bar
        // and display cutout inset to fit the new
        // frame size that doesn't overlap the navigation bar window.
        int orientation = mService.getResources().getConfiguration().orientation;
        if (mLayout.getHeight() != mLayoutParameter.height
                && orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mLayoutParameter.height = windowBound.height() - yOffset;
            shouldUpdateLayout = true;
        }
        return shouldUpdateLayout;
    }

    /**
     * Gets the current page index when device configuration changed. {@link
     * AccessibilityMenuService#onConfigurationChanged(Configuration)}
     *
     * @return the current index of the ViewPager.
     */
    public int getPageIndex() {
        if (mA11yMenuViewPager != null) {
            return mA11yMenuViewPager.mViewPager.getCurrentItem();
        }
        return A11yMenuViewPager.DEFAULT_PAGE_INDEX;
    }

    /**
     * Hides a11y menu layout. And return if layout visibility has been changed.
     *
     * @return {@code true} layout visibility is toggled off; {@code false} is unchanged
     */
    public boolean hideMenu() {
        if (mLayout.getVisibility() == View.VISIBLE) {
            mLayout.setVisibility(View.GONE);
            return true;
        }
        return false;
    }

    /** Toggles a11y menu layout visibility. */
    public void toggleVisibility() {
        mLayout.setVisibility((mLayout.getVisibility() == View.VISIBLE) ? View.GONE : View.VISIBLE);
    }

    /** Shows hint text on a minimal Snackbar-like text view. */
    public void showSnackbar(String text) {
        final int animationDurationMs = 300;
        final int timeoutDurationMs = mAccessibilityManager.getRecommendedTimeoutMillis(2000,
                AccessibilityManager.FLAG_CONTENT_TEXT);

        final TextView snackbar = mLayout.findViewById(R.id.snackbar);
        if (snackbar == null) {
            return;
        }
        snackbar.setText(text);
        if (com.android.systemui.accessibility.accessibilitymenu
                .Flags.a11yMenuSnackbarLiveRegion()) {
            snackbar.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        }

        // Remove any existing fade-out animation before starting any new animations.
        mHandler.removeCallbacksAndMessages(null);

        if (snackbar.getVisibility() != View.VISIBLE) {
            snackbar.setAlpha(0f);
            snackbar.setVisibility(View.VISIBLE);
            snackbar.animate().alpha(1f).setDuration(animationDurationMs).setListener(null);
        }
        mHandler.postDelayed(() -> snackbar.animate().alpha(0f).setDuration(
                animationDurationMs).setListener(
                new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(@NonNull Animator animation) {
                            snackbar.setVisibility(View.GONE);
                        }
                    }), timeoutDurationMs);
    }
}
