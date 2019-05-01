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

import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;

import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.BatteryController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controls how light status bar flag applies to the icons.
 */
@Singleton
public class LightBarController implements BatteryController.BatteryStateChangeCallback, Dumpable {

    private static final float NAV_BAR_INVERSION_SCRIM_ALPHA_THRESHOLD = 0.1f;

    private final SysuiDarkIconDispatcher mStatusBarIconController;
    private final BatteryController mBatteryController;
    private BiometricUnlockController mBiometricUnlockController;

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

    /**
     * {@code true} if {@link #mHasLightNavigationBar} should be ignored and forcefully make
     * {@link #mNavigationLight} {@code false}.
     */
    private boolean mForceDarkForScrim;

    private final Rect mLastFullscreenBounds = new Rect();
    private final Rect mLastDockedBounds = new Rect();
    private boolean mQsCustomizing;

    private boolean mDirectReplying;
    private boolean mNavbarColorManagedByIme;

    @Inject
    public LightBarController(Context ctx, DarkIconDispatcher darkIconDispatcher,
            BatteryController batteryController) {
        mDarkModeColor = Color.valueOf(ctx.getColor(R.color.dark_mode_icon_color_single_tone));
        mStatusBarIconController = (SysuiDarkIconDispatcher) darkIconDispatcher;
        mBatteryController = batteryController;
        mBatteryController.addCallback(this);
    }

    public void setNavigationBar(LightBarTransitionsController navigationBar) {
        mNavigationBarController = navigationBar;
        updateNavigation();
    }

    public void setBiometricUnlockController(
            BiometricUnlockController biometricUnlockController) {
        mBiometricUnlockController = biometricUnlockController;
    }

    public void onSystemUiVisibilityChanged(int fullscreenStackVis, int dockedStackVis,
            int mask, Rect fullscreenStackBounds, Rect dockedStackBounds, boolean sbModeChanged,
            int statusBarMode, boolean navbarColorManagedByIme) {
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
        mNavbarColorManagedByIme = navbarColorManagedByIme;
        mLastFullscreenBounds.set(fullscreenStackBounds);
        mLastDockedBounds.set(dockedStackBounds);
    }

    public void onNavigationVisibilityChanged(int vis, int mask, boolean nbModeChanged,
            int navigationBarMode, boolean navbarColorManagedByIme) {
        int oldVis = mSystemUiVisibility;
        int newVis = (oldVis & ~mask) | (vis & mask);
        int diffVis = newVis ^ oldVis;
        if ((diffVis & View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) != 0
                || nbModeChanged) {
            boolean last = mNavigationLight;
            mHasLightNavigationBar = isLight(vis, navigationBarMode,
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            mNavigationLight = mHasLightNavigationBar
                    && (mDirectReplying && mNavbarColorManagedByIme || !mForceDarkForScrim)
                    && !mQsCustomizing;
            if (mNavigationLight != last) {
                updateNavigation();
            }
        }
        mSystemUiVisibility = newVis;
        mLastNavigationBarMode = navigationBarMode;
        mNavbarColorManagedByIme = navbarColorManagedByIme;
    }

    private void reevaluate() {
        onSystemUiVisibilityChanged(mFullscreenStackVisibility,
                mDockedStackVisibility, 0 /* mask */, mLastFullscreenBounds, mLastDockedBounds,
                true /* sbModeChange*/, mLastStatusBarMode, mNavbarColorManagedByIme);
        onNavigationVisibilityChanged(mSystemUiVisibility, 0 /* mask */, true /* nbModeChanged */,
                mLastNavigationBarMode, mNavbarColorManagedByIme);
    }

    public void setQsCustomizing(boolean customizing) {
        if (mQsCustomizing == customizing) return;
        mQsCustomizing = customizing;
        reevaluate();
    }

    /**
     * Sets whether the direct-reply is in use or not.
     * @param directReplying {@code true} when the direct-reply is in-use.
     */
    public void setDirectReplying(boolean directReplying) {
        if (mDirectReplying == directReplying) return;
        mDirectReplying = directReplying;
        reevaluate();
    }

    public void setScrimState(ScrimState scrimState, float scrimBehindAlpha,
            GradientColors scrimInFrontColor) {
        boolean forceDarkForScrimLast = mForceDarkForScrim;
        // For BOUNCER/BOUNCER_SCRIMMED cases, we assume that alpha is always below threshold.
        // This enables IMEs to control the navigation bar color.
        // For other cases, scrim should be able to veto the light navigation bar.
        mForceDarkForScrim = scrimState != ScrimState.BOUNCER
                && scrimState != ScrimState.BOUNCER_SCRIMMED
                && scrimBehindAlpha >= NAV_BAR_INVERSION_SCRIM_ALPHA_THRESHOLD
                && !scrimInFrontColor.supportsDarkText();
        if (mHasLightNavigationBar && (mForceDarkForScrim != forceDarkForScrimLast)) {
            reevaluate();
        }
    }

    private boolean isLight(int vis, int barMode, int flag) {
        boolean isTransparentBar = (barMode == MODE_TRANSPARENT
                || barMode == MODE_LIGHTS_OUT_TRANSPARENT);
        boolean light = (vis & flag) != 0;
        return isTransparentBar && light;
    }

    private boolean animateChange() {
        if (mBiometricUnlockController == null) {
            return false;
        }
        int unlockMode = mBiometricUnlockController.getMode();
        return unlockMode != BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
                && unlockMode != BiometricUnlockController.MODE_WAKE_AND_UNLOCK;
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

        pw.print(" mForceDarkForScrim="); pw.print(mForceDarkForScrim);
        pw.print(" mQsCustomizing="); pw.print(mQsCustomizing);
        pw.print(" mDirectReplying="); pw.println(mDirectReplying);
        pw.print(" mNavbarColorManagedByIme="); pw.println(mNavbarColorManagedByIme);

        pw.println();

        LightBarTransitionsController transitionsController =
                mStatusBarIconController.getTransitionsController();
        if (transitionsController != null) {
            pw.println(" StatusBarTransitionsController:");
            transitionsController.dump(fd, pw, args);
            pw.println();
        }

        if (mNavigationBarController != null) {
            pw.println(" NavigationBarTransitionsController:");
            mNavigationBarController.dump(fd, pw, args);
            pw.println();
        }
    }
}
