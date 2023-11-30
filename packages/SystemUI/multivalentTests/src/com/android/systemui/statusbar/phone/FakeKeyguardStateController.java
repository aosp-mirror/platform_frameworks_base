/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.systemui.statusbar.policy.KeyguardStateController;

/**
 * Mock implementation of KeyguardStateController which tracks showing and occluded states
 * based on {@link #notifyKeyguardState(boolean showing, boolean occluded)}}.
 */
public class FakeKeyguardStateController implements KeyguardStateController {
    private boolean mShowing;
    private boolean mOccluded;
    private boolean mCanDismissLockScreen;

    @Override
    public void notifyKeyguardState(boolean showing, boolean occluded) {
        mShowing = showing;
        mOccluded = occluded;
    }

    @Override
    public boolean isShowing() {
        return mShowing;
    }

    @Override
    public boolean isOccluded() {
        return mOccluded;
    }

    public void setCanDismissLockScreen(boolean canDismissLockScreen) {
        mCanDismissLockScreen = canDismissLockScreen;
    }

    @Override
    public boolean canDismissLockScreen() {
        return mCanDismissLockScreen;
    }

    @Override
    public boolean isPrimaryBouncerShowing() {
        return false;
    }

    @Override
    public boolean isKeyguardScreenRotationAllowed() {
        return false;
    }

    @Override
    public boolean isMethodSecure() {
        return true;
    }

    @Override
    public boolean isTrusted() {
        return false;
    }

    @Override
    public boolean isKeyguardGoingAway() {
        return false;
    }

    @Override
    public boolean isKeyguardFadingAway() {
        return false;
    }

    @Override
    public boolean isLaunchTransitionFadingAway() {
        return false;
    }

    @Override
    public long getKeyguardFadingAwayDuration() {
        return 0;
    }

    @Override
    public long getKeyguardFadingAwayDelay() {
        return 0;
    }

    @Override
    public long calculateGoingToFullShadeDelay() {
        return 0;
    }

    @Override
    public float getDismissAmount() {
        return 0f;
    }

    @Override
    public boolean isDismissingFromSwipe() {
        return false;
    }

    @Override
    public boolean isFlingingToDismissKeyguard() {
        return false;
    }

    @Override
    public boolean isFlingingToDismissKeyguardDuringSwipeGesture() {
        return false;
    }

    @Override
    public boolean isSnappingKeyguardBackAfterSwipe() {
        return false;
    }

    @Override
    public void notifyPanelFlingStart(boolean dismiss) {
    }

    @Override
    public void notifyPanelFlingEnd() {
    }

    @Override
    public void addCallback(Callback listener) {
    }

    @Override
    public void removeCallback(Callback listener) {
    }
}
