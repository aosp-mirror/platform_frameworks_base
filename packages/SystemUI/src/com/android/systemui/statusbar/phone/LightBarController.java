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

import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;

import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;

import android.content.Context;
import android.graphics.Color;
import android.view.InsetsFlags;
import android.view.ViewDebug;
import android.view.WindowInsetsController.Appearance;

import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.shared.system.QuickStepContract;
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
    private @Appearance int mAppearance;
    private AppearanceRegion[] mAppearanceRegions = new AppearanceRegion[0];
    private int mStatusBarMode;
    private int mNavigationBarMode;
    private int mNavigationMode;
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

    private boolean mQsCustomizing;

    private boolean mDirectReplying;
    private boolean mNavbarColorManagedByIme;

    @Inject
    public LightBarController(Context ctx, DarkIconDispatcher darkIconDispatcher,
            BatteryController batteryController, NavigationModeController navModeController) {
        mDarkModeColor = Color.valueOf(ctx.getColor(R.color.dark_mode_icon_color_single_tone));
        mStatusBarIconController = (SysuiDarkIconDispatcher) darkIconDispatcher;
        mBatteryController = batteryController;
        mBatteryController.addCallback(this);
        mNavigationMode = navModeController.addListener((mode) -> {
            mNavigationMode = mode;
        });
    }

    public void setNavigationBar(LightBarTransitionsController navigationBar) {
        mNavigationBarController = navigationBar;
        updateNavigation();
    }

    public void setBiometricUnlockController(
            BiometricUnlockController biometricUnlockController) {
        mBiometricUnlockController = biometricUnlockController;
    }

    void onStatusBarAppearanceChanged(AppearanceRegion[] appearanceRegions, boolean sbModeChanged,
            int statusBarMode, boolean navbarColorManagedByIme) {
        final int numStacks = appearanceRegions.length;
        boolean stackAppearancesChanged = mAppearanceRegions.length != numStacks;
        for (int i = 0; i < numStacks && !stackAppearancesChanged; i++) {
            stackAppearancesChanged |= !appearanceRegions[i].equals(mAppearanceRegions[i]);
        }
        if (stackAppearancesChanged || sbModeChanged) {
            mAppearanceRegions = appearanceRegions;
            onStatusBarModeChanged(statusBarMode);
        }
        mNavbarColorManagedByIme = navbarColorManagedByIme;
    }

    void onStatusBarModeChanged(int newBarMode) {
        mStatusBarMode = newBarMode;
        updateStatus();
    }

    void onNavigationBarAppearanceChanged(@Appearance int appearance, boolean nbModeChanged,
            int navigationBarMode, boolean navbarColorManagedByIme) {
        int diff = appearance ^ mAppearance;
        if ((diff & APPEARANCE_LIGHT_NAVIGATION_BARS) != 0 || nbModeChanged) {
            final boolean last = mNavigationLight;
            mHasLightNavigationBar = isLight(appearance, navigationBarMode,
                    APPEARANCE_LIGHT_NAVIGATION_BARS);
            mNavigationLight = mHasLightNavigationBar
                    && (mDirectReplying && mNavbarColorManagedByIme || !mForceDarkForScrim)
                    && !mQsCustomizing;
            if (mNavigationLight != last) {
                updateNavigation();
            }
        }
        mAppearance = appearance;
        mNavigationBarMode = navigationBarMode;
        mNavbarColorManagedByIme = navbarColorManagedByIme;
    }

    void onNavigationBarModeChanged(int newBarMode) {
        mHasLightNavigationBar = isLight(mAppearance, newBarMode, APPEARANCE_LIGHT_NAVIGATION_BARS);
    }

    private void reevaluate() {
        onStatusBarAppearanceChanged(mAppearanceRegions, true /* sbModeChange */, mStatusBarMode,
                mNavbarColorManagedByIme);
        onNavigationBarAppearanceChanged(mAppearance, true /* nbModeChanged */,
                mNavigationBarMode, mNavbarColorManagedByIme);
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

    private static boolean isLight(int appearance, int barMode, int flag) {
        final boolean isTransparentBar = (barMode == MODE_TRANSPARENT
                || barMode == MODE_LIGHTS_OUT_TRANSPARENT);
        final boolean light = (appearance & flag) != 0;
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

    private void updateStatus() {
        final int numStacks = mAppearanceRegions.length;
        int numLightStacks = 0;

        // We can only have maximum one light stack.
        int indexLightStack = -1;

        for (int i = 0; i < numStacks; i++) {
            if (isLight(mAppearanceRegions[i].getAppearance(), mStatusBarMode,
                    APPEARANCE_LIGHT_STATUS_BARS)) {
                numLightStacks++;
                indexLightStack = i;
            }
        }

        // If all stacks are light, all icons get dark.
        if (numLightStacks == numStacks) {
            mStatusBarIconController.setIconsDarkArea(null);
            mStatusBarIconController.getTransitionsController().setIconsDark(true, animateChange());

        }

        // If no one is light, all icons become white.
        else if (numLightStacks == 0) {
            mStatusBarIconController.getTransitionsController().setIconsDark(
                    false, animateChange());
        }

        // Not the same for every stack, magic!
        else {
            mStatusBarIconController.setIconsDarkArea(
                    mAppearanceRegions[indexLightStack].getBounds());
            mStatusBarIconController.getTransitionsController().setIconsDark(true, animateChange());
        }
    }

    private void updateNavigation() {
        if (mNavigationBarController != null
                && !QuickStepContract.isGesturalMode(mNavigationMode)) {
            mNavigationBarController.setIconsDark(mNavigationLight, animateChange());
        }
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        reevaluate();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("LightBarController: ");
        pw.print(" mAppearance="); pw.println(ViewDebug.flagsToString(
                InsetsFlags.class, "appearance", mAppearance));
        final int numStacks = mAppearanceRegions.length;
        for (int i = 0; i < numStacks; i++) {
            final boolean isLight = isLight(mAppearanceRegions[i].getAppearance(), mStatusBarMode,
                    APPEARANCE_LIGHT_STATUS_BARS);
            pw.print(" stack #"); pw.print(i); pw.print(": ");
            pw.print(mAppearanceRegions[i].toString()); pw.print(" isLight="); pw.println(isLight);
        }

        pw.print(" mNavigationLight="); pw.print(mNavigationLight);
        pw.print(" mHasLightNavigationBar="); pw.println(mHasLightNavigationBar);

        pw.print(" mStatusBarMode="); pw.print(mStatusBarMode);
        pw.print(" mNavigationBarMode="); pw.println(mNavigationBarMode);

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
