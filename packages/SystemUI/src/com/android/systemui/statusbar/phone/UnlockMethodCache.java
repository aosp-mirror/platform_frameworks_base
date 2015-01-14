/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.Context;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;

import java.util.ArrayList;

/**
 * Caches whether the current unlock method is insecure, taking trust into account. This information
 * might be a little bit out of date and should not be used for actual security decisions; it should
 * be only used for visual indications.
 */
public class UnlockMethodCache {

    private static UnlockMethodCache sInstance;

    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ArrayList<OnUnlockMethodChangedListener> mListeners = new ArrayList<>();
    /** Whether the user configured a secure unlock method (PIN, password, etc.) */
    private boolean mSecure;
    /** Whether the unlock method is currently insecure (insecure method or trusted environment) */
    private boolean mCurrentlyInsecure;
    private boolean mTrustManaged;
    private boolean mFaceUnlockRunning;

    private UnlockMethodCache(Context ctx) {
        mLockPatternUtils = new LockPatternUtils(ctx);
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(ctx);
        KeyguardUpdateMonitor.getInstance(ctx).registerCallback(mCallback);
        update(true /* updateAlways */);
    }

    public static UnlockMethodCache getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new UnlockMethodCache(context);
        }
        return sInstance;
    }

    /**
     * @return whether the user configured a secure unlock method like PIN, password, etc.
     */
    public boolean isMethodSecure() {
        return mSecure;
    }

    /**
     * @return whether the lockscreen is currently insecure, i. e. the bouncer won't be shown
     */
    public boolean isCurrentlyInsecure() {
        return mCurrentlyInsecure;
    }

    public void addListener(OnUnlockMethodChangedListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(OnUnlockMethodChangedListener listener) {
        mListeners.remove(listener);
    }

    private void update(boolean updateAlways) {
        int user = mLockPatternUtils.getCurrentUser();
        boolean secure = mLockPatternUtils.isSecure();
        boolean currentlyInsecure = !secure ||  mKeyguardUpdateMonitor.getUserHasTrust(user);
        boolean trustManaged = mKeyguardUpdateMonitor.getUserTrustIsManaged(user);
        boolean faceUnlockRunning = mKeyguardUpdateMonitor.isFaceUnlockRunning(user)
                && trustManaged;
        boolean changed = secure != mSecure || currentlyInsecure != mCurrentlyInsecure ||
                trustManaged != mTrustManaged  || faceUnlockRunning != mFaceUnlockRunning;
        if (changed || updateAlways) {
            mSecure = secure;
            mCurrentlyInsecure = currentlyInsecure;
            mTrustManaged = trustManaged;
            mFaceUnlockRunning = faceUnlockRunning;
            notifyListeners();
        }
    }

    private void notifyListeners() {
        for (OnUnlockMethodChangedListener listener : mListeners) {
            listener.onUnlockMethodStateChanged();
        }
    }

    private final KeyguardUpdateMonitorCallback mCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onUserSwitchComplete(int userId) {
            update(false /* updateAlways */);
        }

        @Override
        public void onTrustChanged(int userId) {
            update(false /* updateAlways */);
        }

        @Override
        public void onTrustManagedChanged(int userId) {
            update(false /* updateAlways */);
        }

        @Override
        public void onScreenTurnedOn() {
            update(false /* updateAlways */);
        }

        @Override
        public void onFingerprintRecognized(int userId) {
            update(false /* updateAlways */);
        }

        @Override
        public void onFaceUnlockStateChanged(boolean running, int userId) {
            update(false /* updateAlways */);
        }
    };

    public boolean isTrustManaged() {
        return mTrustManaged;
    }

    public boolean isFaceUnlockRunning() {
        return mFaceUnlockRunning;
    }

    public static interface OnUnlockMethodChangedListener {
        void onUnlockMethodStateChanged();
    }
}
