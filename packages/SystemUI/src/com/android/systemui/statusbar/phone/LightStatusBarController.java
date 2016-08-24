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

import android.graphics.Rect;
import android.view.View;

import com.android.systemui.statusbar.policy.BatteryController;

import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;

/**
 * Controls how light status bar flag applies to the icons.
 */
public class LightStatusBarController {

    private final StatusBarIconController mIconController;
    private final BatteryController mBatteryController;
    private FingerprintUnlockController mFingerprintUnlockController;

    private int mFullscreenStackVisibility;
    private int mDockedStackVisibility;
    private boolean mFullscreenLight;
    private boolean mDockedLight;

    private final Rect mLastFullscreenBounds = new Rect();
    private final Rect mLastDockedBounds = new Rect();

    public LightStatusBarController(StatusBarIconController iconController,
            BatteryController batteryController) {
        mIconController = iconController;
        mBatteryController = batteryController;
    }

    public void setFingerprintUnlockController(
            FingerprintUnlockController fingerprintUnlockController) {
        mFingerprintUnlockController = fingerprintUnlockController;
    }

    public void onSystemUiVisibilityChanged(int fullscreenStackVis, int dockedStackVis, int mask,
            Rect fullscreenStackBounds, Rect dockedStackBounds, boolean sbModeChanged,
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

            mFullscreenLight = isLight(newFullscreen, statusBarMode);
            mDockedLight = isLight(newDocked, statusBarMode);
            update(fullscreenStackBounds, dockedStackBounds);
        }
        mFullscreenStackVisibility = newFullscreen;
        mDockedStackVisibility = newDocked;
        mLastFullscreenBounds.set(fullscreenStackBounds);
        mLastDockedBounds.set(dockedStackBounds);
    }

    private boolean isLight(int vis, int statusBarMode) {
        boolean isTransparentBar = (statusBarMode == MODE_TRANSPARENT
                || statusBarMode == MODE_LIGHTS_OUT_TRANSPARENT);
        boolean allowLight = isTransparentBar && !mBatteryController.isPowerSave();
        boolean light = (vis & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0;
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

    private void update(Rect fullscreenStackBounds, Rect dockedStackBounds) {
        boolean hasDockedStack = !dockedStackBounds.isEmpty();

        // If both are light or fullscreen is light and there is no docked stack, all icons get
        // dark.
        if ((mFullscreenLight && mDockedLight) || (mFullscreenLight && !hasDockedStack)) {
            mIconController.setIconsDarkArea(null);
            mIconController.setIconsDark(true, animateChange());

        }

        // If no one is light or the fullscreen is not light and there is no docked stack,
        // all icons become white.
        else if ((!mFullscreenLight && !mDockedLight) || (!mFullscreenLight && !hasDockedStack)) {
            mIconController.setIconsDark(false, animateChange());

        }

        // Not the same for every stack, magic!
        else {
            Rect bounds = mFullscreenLight ? fullscreenStackBounds : dockedStackBounds;
            if (bounds.isEmpty()) {
                mIconController.setIconsDarkArea(null);
            } else {
                mIconController.setIconsDarkArea(bounds);
            }
            mIconController.setIconsDark(true, animateChange());
        }
    }
}
