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

import android.content.Context;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.phone.UnlockMethodCache;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A controller which dynamically controls the visibility of Notification content
 */
@Singleton
public class DynamicPrivacyController implements UnlockMethodCache.OnUnlockMethodChangedListener {

    private final UnlockMethodCache mUnlockMethodCache;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private ArraySet<Listener> mListeners = new ArraySet<>();

    private boolean mLastDynamicUnlocked;
    private boolean mCacheInvalid;

    @Inject
    DynamicPrivacyController(Context context,
            NotificationLockscreenUserManager notificationLockscreenUserManager) {
        this(notificationLockscreenUserManager, UnlockMethodCache.getInstance(context));
    }

    @VisibleForTesting
    DynamicPrivacyController(NotificationLockscreenUserManager notificationLockscreenUserManager,
            UnlockMethodCache unlockMethodCache) {
        mLockscreenUserManager = notificationLockscreenUserManager;
        mUnlockMethodCache = unlockMethodCache;
        mUnlockMethodCache.addListener(this);
        mLastDynamicUnlocked = isDynamicallyUnlocked();
    }

    @Override
    public void onUnlockMethodStateChanged() {
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
        return mUnlockMethodCache.canSkipBouncer() && isDynamicPrivacyEnabled();
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public interface Listener {
        void onDynamicPrivacyChanged();
    }
}