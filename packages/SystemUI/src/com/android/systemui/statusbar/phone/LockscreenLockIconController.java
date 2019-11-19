/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import com.android.systemui.statusbar.SuperStatusBarViewFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

/** Controls the {@link LockIcon} in the lockscreen. */
@Singleton
public class LockscreenLockIconController {

    private final LockIcon mLockIcon;

    @Inject
    public LockscreenLockIconController(SuperStatusBarViewFactory superStatusBarViewFactory) {
        mLockIcon = superStatusBarViewFactory.getLockIcon();
    }

    /**
     * Called whenever the scrims become opaque, transparent or semi-transparent.
     */
    public void onScrimVisibilityChanged(Integer scrimsVisible) {
        if (mLockIcon != null) {
            mLockIcon.onScrimVisibilityChanged(scrimsVisible);
        }
    }

    /**
     * Propagate {@link StatusBar} pulsing state.
     */
    public void setPulsing(boolean pulsing) {
        if (mLockIcon != null) {
            mLockIcon.setPulsing(pulsing);
        }
    }

    /**
     * Called when the biometric authentication mode changes.
     *
     * @param wakeAndUnlock If the type is {@link BiometricUnlockController#isWakeAndUnlock()}
     * @param isUnlock      If the type is {@link BiometricUnlockController#isBiometricUnlock()} ()
     */
    public void onBiometricAuthModeChanged(boolean wakeAndUnlock, boolean isUnlock) {
        if (mLockIcon != null) {
            mLockIcon.onBiometricAuthModeChanged(wakeAndUnlock, isUnlock);
        }
    }

    /**
     * When we're launching an affordance, like double pressing power to open camera.
     */
    public void onShowingLaunchAffordanceChanged(Boolean showing) {
        if (mLockIcon != null) {
            mLockIcon.onShowingLaunchAffordanceChanged(showing);
        }
    }

    /** Sets whether the bouncer is showing. */
    public void setBouncerShowingScrimmed(boolean bouncerShowing) {
        if (mLockIcon != null) {
            mLockIcon.setBouncerShowingScrimmed(bouncerShowing);
        }
    }

    /**
     * When {@link KeyguardBouncer} starts to be dismissed and starts to play its animation.
     */
    public void onBouncerPreHideAnimation() {
        if (mLockIcon != null) {
            mLockIcon.onBouncerPreHideAnimation();
        }
    }
}
