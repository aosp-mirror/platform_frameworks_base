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

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.biometrics.BiometricSourceType;
import android.media.AudioManager;
import android.os.Build;
import android.os.Trace;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.TelephonyIntents;
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
    private static final boolean DEBUG_AUTH_WITH_ADB = false;
    private static final String AUTH_BROADCAST_KEY = "debug_trigger_auth";

    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ArrayList<OnUnlockMethodChangedListener> mListeners = new ArrayList<>();
    /** Whether the user configured a secure unlock method (PIN, password, etc.) */
    private boolean mSecure;
    /** Whether the unlock method is currently insecure (insecure method or trusted environment) */
    private boolean mCanSkipBouncer;
    private boolean mTrustManaged;
    private boolean mTrusted;
    private boolean mDebugUnlocked = false;

    private UnlockMethodCache(Context ctx) {
        mLockPatternUtils = new LockPatternUtils(ctx);
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(ctx);
        KeyguardUpdateMonitor.getInstance(ctx).registerCallback(mCallback);
        update(true /* updateAlways */);
        if (Build.IS_DEBUGGABLE && DEBUG_AUTH_WITH_ADB) {
            // Watch for interesting updates
            final IntentFilter filter = new IntentFilter();
            filter.addAction(AUTH_BROADCAST_KEY);
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (DEBUG_AUTH_WITH_ADB && AUTH_BROADCAST_KEY.equals(intent.getAction())) {
                        mDebugUnlocked = !mDebugUnlocked;
                        update(true /* updateAlways */);
                    }
                }
            }, filter, null, null);
        }
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

    public boolean isTrusted() {
        return mTrusted;
    }

    /**
     * @return whether the lockscreen is currently insecure, and the bouncer won't be shown
     */
    public boolean canSkipBouncer() {
        return mCanSkipBouncer;
    }

    public void addListener(OnUnlockMethodChangedListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(OnUnlockMethodChangedListener listener) {
        mListeners.remove(listener);
    }

    private void update(boolean updateAlways) {
        Trace.beginSection("UnlockMethodCache#update");
        int user = KeyguardUpdateMonitor.getCurrentUser();
        boolean secure = mLockPatternUtils.isSecure(user);
        boolean canSkipBouncer = !secure || mKeyguardUpdateMonitor.getUserCanSkipBouncer(user)
                || (Build.IS_DEBUGGABLE && DEBUG_AUTH_WITH_ADB && mDebugUnlocked);
        boolean trustManaged = mKeyguardUpdateMonitor.getUserTrustIsManaged(user);
        boolean trusted = mKeyguardUpdateMonitor.getUserHasTrust(user);
        boolean changed = secure != mSecure || canSkipBouncer != mCanSkipBouncer ||
                trustManaged != mTrustManaged;
        if (changed || updateAlways) {
            mSecure = secure;
            mCanSkipBouncer = canSkipBouncer;
            mTrusted = trusted;
            mTrustManaged = trustManaged;
            notifyListeners();
        }
        Trace.endSection();
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
        public void onStartedWakingUp() {
            update(false /* updateAlways */);
        }

        @Override
        public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType) {
            Trace.beginSection("KeyguardUpdateMonitorCallback#onBiometricAuthenticated");
            if (!mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed()) {
                Trace.endSection();
                return;
            }
            update(false /* updateAlways */);
            Trace.endSection();
        }

        @Override
        public void onFaceUnlockStateChanged(boolean running, int userId) {
            update(false /* updateAlways */);
        }

        @Override
        public void onStrongAuthStateChanged(int userId) {
            update(false /* updateAlways */);
        }

        @Override
        public void onScreenTurnedOff() {
            update(false /* updateAlways */);
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            update(false /* updateAlways */);
        }
    };

    public boolean isTrustManaged() {
        return mTrustManaged;
    }

    public static interface OnUnlockMethodChangedListener {
        void onUnlockMethodStateChanged();
    }
}
