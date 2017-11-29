/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.app.WallpaperColors;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;

import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;

/**
 * Controls how light status bar flag applies to the icons.
 */
public class LightBarController implements BatteryController.BatteryStateChangeCallback, Dumpable {

    private static final float NAV_BAR_INVERSION_SCRIM_ALPHA_THRESHOLD = 0.1f;

    private final DarkIconDispatcher mStatusBarIconController;
    private final BatteryController mBatteryController;
    private FingerprintUnlockController mFingerprintUnlockController;

    private LightBarTransitionsController mNavigationBarController;
    private int mSystemUiVisibility;
    private int mFullscreenStackVisibility;
    private int mDockedStackVisibility;
    private boolean mFullscreenLight;
    private boolean mDockedLight;
    private int mLastStatusBarMode;
    private int mLastNavigationBarMode;
    private final Color mDarkModeColor;

    /**
     * Whether the navigation bar should be light factoring in already how much alpha the scrim has
     */
    private boolean mNavigationLight;

    /**
     * Whether the flags indicate that a light status bar is requested. This doesn't factor in the
     * scrim alpha yet.
     */
    private boolean mHasLightNavigationBar;
    private boolean mScrimAlphaBelowThreshold;
    private boolean mInvertLightNavBarWithScrim;
    private float mScrimAlpha;

    private final Rect mLastFullscreenBounds = new Rect();
    private final Rect mLastDockedBounds = new Rect();
    private boolean mQsCustomizing;

    public LightBarController(Context ctx) {
        mDarkModeColor = Color.valueOf(ctx.getColor(R.color.dark_mode_icon_color_single_tone));
        mStatusBarIconController = Dependency.get(DarkIconDispatcher.class);
        mBatteryController = Dependency.get(BatteryController.class);
        mBatteryController.addCallback(this);
    }

    public void setNavigationBar(LightBarTransitionsController navigationBar) {
        mNavigationBarController = navigationBar;
        updateNavigation();
    }

    public void setFingerprintUnlockController(
            FingerprintUnlockController fingerprintUnlockController) {
        mFingerprintUnlockController = fingerprintUnlockController;
    }

    public void onSystemUiVisibilityChanged(int fullscreenStackVis, int dockedStackVis,
            int mask, Rect fullscreenStackBounds, Rect dockedStackBounds, boolean sbModeChanged,
            int statusBarMode) {
        int oldFullscreen = mFullscreenStackVisibility;
        int newFullscreen = (oldFullscreen & ~mask) | (fullscreenStackVis & mask);
        int diffFullscreen = newFullscreen ^ oldFullscreen;
        int oldDocked = mDockedStackVisibility;
        int newDocked = (oldDocked & ~mask) | (dockedStackVis & mask);
        int diffDocked = newDocked ^ oldDocked;
        if ((diffFullscreen & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0
                || (diffDocked & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0
                || sbModeChanged
                || !mLastFullscreenBounds.equals(fullscreenStackBounds)
                || !mLastDockedBounds.equals(dockedStackBounds)) {

            mFullscreenLight = isLight(newFullscreen, statusBarMode,
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            mDockedLight = isLight(newDocked, statusBarMode, View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            updateStatus(fullscreenStackBounds, dockedStackBounds);
        }

        mFullscreenStackVisibility = newFullscreen;
        mDockedStackVisibility = newDocked;
        mLastStatusBarMode = statusBarMode;
        mLastFullscreenBounds.set(fullscreenStackBounds);
        mLastDockedBounds.set(dockedStackBounds);
    }

    public void onNavigationVisibilityChanged(int vis, int mask, boolean nbModeChanged,
            int navigationBarMode) {
        int oldVis = mSystemUiVisibility;
        int newVis = (oldVis & ~mask) | (vis & mask);
        int diffVis = newVis ^ oldVis;
        if ((diffVis & View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) != 0
                || nbModeChanged) {
            boolean last = mNavigationLight;
            mHasLightNavigationBar = isLight(vis, navigationBarMode,
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            mNavigationLight = mHasLightNavigationBar
                    && (mScrimAlphaBelowThreshold || !mInvertLightNavBarWithScrim)
                    && !mQsCustomizing;
            if (mNavigationLight != last) {
                updateNavigation();
            }
        }
        mSystemUiVisibility = newVis;
        mLastNavigationBarMode = navigationBarMode;
    }

    private void reevaluate() {
        onSystemUiVisibilityChanged(mFullscreenStackVisibility,
                mDockedStackVisibility, 0 /* mask */, mLastFullscreenBounds, mLastDockedBounds,
                true /* sbModeChange*/, mLastStatusBarMode);
        onNavigationVisibilityChanged(mSystemUiVisibility, 0 /* mask */, true /* nbModeChanged */,
                mLastNavigationBarMode);
    }

    public void setQsCustomizing(boolean customizing) {
        if (mQsCustomizing == customizing) return;
        mQsCustomizing = customizing;
        reevaluate();
    }

    public void setScrimAlpha(float alpha) {
        mScrimAlpha = alpha;
        boolean belowThresholdBefore = mScrimAlphaBelowThreshold;
        mScrimAlphaBelowThreshold = mScrimAlpha < NAV_BAR_INVERSION_SCRIM_ALPHA_THRESHOLD;
        if (mHasLightNavigationBar && belowThresholdBefore != mScrimAlphaBelowThreshold) {
            reevaluate();
        }
    }

    public void setScrimColor(GradientColors colors) {
        boolean invertLightNavBarWithScrimBefore = mInvertLightNavBarWithScrim;
        mInvertLightNavBarWithScrim = !colors.supportsDarkText();
        if (mHasLightNavigationBar
                && invertLightNavBarWithScrimBefore != mInvertLightNavBarWithScrim) {
            reevaluate();
        }
    }

    private boolean isLight(int vis, int barMode, int flag) {
        boolean isTransparentBar = (barMode == MODE_TRANSPARENT
                || barMode == MODE_LIGHTS_OUT_TRANSPARENT);
        boolean allowLight = isTransparentBar && !mBatteryController.isPowerSave();
        boolean light = (vis & flag) != 0;
        return allowLight && light;
    }

    private boolean animateChange() {
        if (mFingerprintUnlockController == null) {
            return false;
        }
        int unlockMode = mFingerprintUnlockController.getMode();
        return unlockMode != FingerprintUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
                && unlockMode != FingerprintUnlockController.MODE_WAKE_AND_UNLOCK;
    }

    private void updateStatus(Rect fullscreenStackBounds, Rect dockedStackBounds) {
        boolean hasDockedStack = !dockedStackBounds.isEmpty();

        // If both are light or fullscreen is light and there is no docked stack, all icons get
        // dark.
        if ((mFullscreenLight && mDockedLight) || (mFullscreenLight && !hasDockedStack)) {
            mStatusBarIconController.setIconsDarkArea(null);
            mStatusBarIconController.getTransitionsController().setIconsDark(true, animateChange());

        }

        // If no one is light or the fullscreen is not light and there is no docked stack,
        // all icons become white.
        else if ((!mFullscreenLight && !mDockedLight) || (!mFullscreenLight && !hasDockedStack)) {
            mStatusBarIconController.getTransitionsController().setIconsDark(
                    false, animateChange());
        }

        // Not the same for every stack, magic!
        else {
            Rect bounds = mFullscreenLight ? fullscreenStackBounds : dockedStackBounds;
            if (bounds.isEmpty()) {
                mStatusBarIconController.setIconsDarkArea(null);
            } else {
                mStatusBarIconController.setIconsDarkArea(bounds);
            }
            mStatusBarIconController.getTransitionsController().setIconsDark(true, animateChange());
        }
    }

    private void updateNavigation() {
        if (mNavigationBarController != null) {
            mNavigationBarController.setIconsDark(
                    mNavigationLight, animateChange());
        }
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {

    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        reevaluate();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("LightBarController: ");
        pw.print(" mSystemUiVisibility=0x"); pw.print(
                Integer.toHexString(mSystemUiVisibility));
        pw.print(" mFullscreenStackVisibility=0x"); pw.print(
                Integer.toHexString(mFullscreenStackVisibility));
        pw.print(" mDockedStackVisibility=0x"); pw.println(
                Integer.toHexString(mDockedStackVisibility));

        pw.print(" mFullscreenLight="); pw.print(mFullscreenLight);
        pw.print(" mDockedLight="); pw.println(mDockedLight);

        pw.print(" mLastFullscreenBounds="); pw.print(mLastFullscreenBounds);
        pw.print(" mLastDockedBounds="); pw.println(mLastDockedBounds);

        pw.print(" mNavigationLight="); pw.print(mNavigationLight);
        pw.print(" mHasLightNavigationBar="); pw.println(mHasLightNavigationBar);

        pw.print(" mLastStatusBarMode="); pw.print(mLastStatusBarMode);
        pw.print(" mLastNavigationBarMode="); pw.println(mLastNavigationBarMode);

        pw.print(" mScrimAlpha="); pw.print(mScrimAlpha);
        pw.print(" mScrimAlphaBelowThreshold="); pw.println(mScrimAlphaBelowThreshold);
        pw.println();
        pw.println(" StatusBarTransitionsController:");
        mStatusBarIconController.getTransitionsController().dump(fd, pw, args);
        pw.println();
        pw.println(" NavigationBarTransitionsController:");
        mNavigationBarController.dump(fd, pw, args);
        pw.println();
    }
}
