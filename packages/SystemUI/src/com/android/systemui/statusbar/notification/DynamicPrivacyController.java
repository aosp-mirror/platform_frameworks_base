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

package com.android.systemui.statusbar.notification;

import android.util.ArraySet;

import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A controller which dynamically controls the visibility of Notification content
 */
@Singleton
public class DynamicPrivacyController implements KeyguardStateController.Callback {

    private final KeyguardStateController mKeyguardStateController;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final StatusBarStateController mStateController;
    private ArraySet<Listener> mListeners = new ArraySet<>();

    private boolean mLastDynamicUnlocked;
    private boolean mCacheInvalid;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    @Inject
    DynamicPrivacyController(NotificationLockscreenUserManager notificationLockscreenUserManager,
            KeyguardStateController keyguardStateController,
            StatusBarStateController stateController) {
        mLockscreenUserManager = notificationLockscreenUserManager;
        mStateController = stateController;
        mKeyguardStateController = keyguardStateController;
        mKeyguardStateController.addCallback(this);
        mLastDynamicUnlocked = isDynamicallyUnlocked();
    }

    @Override
    public void onUnlockedChanged() {
        if (isDynamicPrivacyEnabled()) {
            // We only want to notify our listeners if dynamic privacy is actually active
            boolean dynamicallyUnlocked = isDynamicallyUnlocked();
            if (dynamicallyUnlocked != mLastDynamicUnlocked || mCacheInvalid) {
                mLastDynamicUnlocked = dynamicallyUnlocked;
                for (Listener listener : mListeners) {
                    listener.onDynamicPrivacyChanged();
                }
            }
            mCacheInvalid = false;
        } else {
            mCacheInvalid = true;
        }
    }

    private boolean isDynamicPrivacyEnabled() {
        return !mLockscreenUserManager.shouldHideNotifications(
                mLockscreenUserManager.getCurrentUserId());
    }

    public boolean isDynamicallyUnlocked() {
        return (mKeyguardStateController.canDismissLockScreen()
                || mKeyguardStateController.isKeyguardGoingAway()
                || mKeyguardStateController.isKeyguardFadingAway())
                && isDynamicPrivacyEnabled();
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    /**
     * Is the notification shade currently in a locked down mode where it's fully showing but the
     * contents aren't revealed yet?
     */
    public boolean isInLockedDownShade() {
        if (!mStatusBarKeyguardViewManager.isShowing()
                || !mKeyguardStateController.isMethodSecure()) {
            return false;
        }
        int state = mStateController.getState();
        if (state != StatusBarState.SHADE && state != StatusBarState.SHADE_LOCKED) {
            return false;
        }
        if (!isDynamicPrivacyEnabled() || isDynamicallyUnlocked()) {
            return false;
        }
        return true;
    }

    public void setStatusBarKeyguardViewManager(
            StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
    }

    public interface Listener {
        void onDynamicPrivacyChanged();
    }
}