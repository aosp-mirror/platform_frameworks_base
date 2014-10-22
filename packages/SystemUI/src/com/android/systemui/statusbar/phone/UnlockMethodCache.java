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
    private boolean mMethodInsecure;
    private boolean mTrustManaged;
    private boolean mFaceUnlockRunning;

    private UnlockMethodCache(Context ctx) {
        mLockPatternUtils = new LockPatternUtils(ctx);
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(ctx);
        KeyguardUpdateMonitor.getInstance(ctx).registerCallback(mCallback);
        updateMethodSecure(true /* updateAlways */);
    }

    public static UnlockMethodCache getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new UnlockMethodCache(context);
        }
        return sInstance;
    }

    /**
     * @return whether the current security method is secure, i. e. the bouncer will be shown
     */
    public boolean isMethodInsecure() {
        return mMethodInsecure;
    }

    public void addListener(OnUnlockMethodChangedListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(OnUnlockMethodChangedListener listener) {
        mListeners.remove(listener);
    }

    private void updateMethodSecure(boolean updateAlways) {
        int user = mLockPatternUtils.getCurrentUser();
        boolean methodInsecure = !mLockPatternUtils.isSecure() ||
                mKeyguardUpdateMonitor.getUserHasTrust(user);
        boolean trustManaged = mKeyguardUpdateMonitor.getUserTrustIsManaged(user);
        boolean faceUnlockRunning = mKeyguardUpdateMonitor.isFaceUnlockRunning(user)
                && trustManaged;
        boolean changed = methodInsecure != mMethodInsecure || trustManaged != mTrustManaged
                || faceUnlockRunning != mFaceUnlockRunning;
        if (changed || updateAlways) {
            mMethodInsecure = methodInsecure;
            mTrustManaged = trustManaged;
            mFaceUnlockRunning = faceUnlockRunning;
            notifyListeners(mMethodInsecure);
        }
    }

    private void notifyListeners(boolean secure) {
        for (OnUnlockMethodChangedListener listener : mListeners) {
            listener.onMethodSecureChanged(secure);
        }
    }

    private final KeyguardUpdateMonitorCallback mCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onUserSwitchComplete(int userId) {
            updateMethodSecure(false /* updateAlways */);
        }

        @Override
        public void onTrustChanged(int userId) {
            updateMethodSecure(false /* updateAlways */);
        }

        @Override
        public void onTrustManagedChanged(int userId) {
            updateMethodSecure(false /* updateAlways */);
        }

        @Override
        public void onScreenTurnedOn() {
            updateMethodSecure(false /* updateAlways */);
        }

        @Override
        public void onFingerprintRecognized(int userId) {
            updateMethodSecure(false /* updateAlways */);
        }

        @Override
        public void onFaceUnlockStateChanged(boolean running, int userId) {
            updateMethodSecure(false /* updateAlways */);
        }
    };

    public boolean isTrustManaged() {
        return mTrustManaged;
    }

    public boolean isFaceUnlockRunning() {
        return mFaceUnlockRunning;
    }

    public static interface OnUnlockMethodChangedListener {
        void onMethodSecureChanged(boolean methodSecure);
    }
}
