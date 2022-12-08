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

import static java.lang.Math.max;

import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService;
import com.android.systemui.accessibility.accessibilitymenu.R;
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

    private final AccessibilityMenuService mService;
    private final WindowManager mWindowManager;
    private ViewGroup mLayout;
    private WindowManager.LayoutParams mLayoutParameter;
    private A11yMenuViewPager mA11yMenuViewPager;

    public A11yMenuOverlayLayout(AccessibilityMenuService service) {
        mService = service;
        mWindowManager = mService.getSystemService(WindowManager.class);
        configureLayout();
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

        mLayout = new FrameLayout(mService);
        updateLayoutPosition();
        inflateLayoutAndSetOnTouchListener(mLayout);
        mA11yMenuViewPager = new A11yMenuViewPager(mService);
        mA11yMenuViewPager.configureViewPagerAndFooter(mLayout, createShortcutList(), pageIndex);
        mWindowManager.addView(mLayout, mLayoutParameter);
        mLayout.setVisibility(lastVisibilityState);

        return mLayout;
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

    private void inflateLayoutAndSetOnTouchListener(ViewGroup view) {
        LayoutInflater inflater = LayoutInflater.from(mService);
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
        for (int shortcutId : SHORTCUT_LIST_DEFAULT) {
            shortcutList.add(new A11yMenuShortcut(shortcutId));
        }
        return shortcutList;
    }

    /** Updates a11y menu layout position by configuring layout params. */
    private void updateLayoutPosition() {
        Display display = mLayout.getDisplay();
        final int orientation = mService.getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            switch (display.getRotation()) {
                case Surface.ROTATION_90:
                case Surface.ROTATION_180:
                    mLayoutParameter.gravity =
                            Gravity.END | Gravity.BOTTOM
                                    | Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL;
                    mLayoutParameter.width = WindowManager.LayoutParams.WRAP_CONTENT;
                    mLayoutParameter.height = WindowManager.LayoutParams.MATCH_PARENT;
                    mLayoutParameter.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                    mLayoutParameter.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
                    mLayout.setBackgroundResource(R.drawable.shadow_90deg);
                    break;
                case Surface.ROTATION_0:
                case Surface.ROTATION_270:
                    mLayoutParameter.gravity =
                            Gravity.START | Gravity.BOTTOM
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

    /** Shows hint text on Toast. */
    public void showToast(String text) {
        final View viewPos = mLayout.findViewById(R.id.coordinatorLayout);
        Toast.makeText(viewPos.getContext(), text, Toast.LENGTH_SHORT).show();
    }
}
