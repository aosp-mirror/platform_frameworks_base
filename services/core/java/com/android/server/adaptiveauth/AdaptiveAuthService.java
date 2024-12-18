/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.adaptiveauth;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_ADAPTIVE_AUTH_REQUEST;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.biometrics.AuthenticationStateListener;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.events.AuthenticationAcquiredInfo;
import android.hardware.biometrics.events.AuthenticationErrorInfo;
import android.hardware.biometrics.events.AuthenticationFailedInfo;
import android.hardware.biometrics.events.AuthenticationHelpInfo;
import android.hardware.biometrics.events.AuthenticationStartedInfo;
import android.hardware.biometrics.events.AuthenticationStoppedInfo;
import android.hardware.biometrics.events.AuthenticationSucceededInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockSettingsInternal;
import com.android.internal.widget.LockSettingsStateListener;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.util.Objects;

/**
 * @hide
 */
public class AdaptiveAuthService extends SystemService {
    private static final String TAG = "AdaptiveAuthService";
    private static final boolean DEBUG = Build.IS_DEBUGGABLE && Log.isLoggable(TAG, Log.DEBUG);

    @VisibleForTesting
    static final int MAX_ALLOWED_FAILED_AUTH_ATTEMPTS = 5;
    private static final int MSG_REPORT_PRIMARY_AUTH_ATTEMPT = 1;
    private static final int MSG_REPORT_BIOMETRIC_AUTH_ATTEMPT = 2;
    private static final int AUTH_SUCCESS = 1;
    private static final int AUTH_FAILURE = 0;
    private static final int TYPE_PRIMARY_AUTH = 0;
    private static final int TYPE_BIOMETRIC_AUTH = 1;

    private final LockPatternUtils mLockPatternUtils;
    private final LockSettingsInternal mLockSettings;
    private final BiometricManager mBiometricManager;
    private final KeyguardManager mKeyguardManager;
    private final WindowManagerInternal mWindowManager;
    private final UserManagerInternal mUserManager;
    @VisibleForTesting
    final SparseIntArray mFailedAttemptsForUser = new SparseIntArray();
    private final SparseLongArray mLastLockedTimestamp = new SparseLongArray();

    public AdaptiveAuthService(Context context) {
        this(context, new LockPatternUtils(context));
    }

    @VisibleForTesting
    public AdaptiveAuthService(Context context, LockPatternUtils lockPatternUtils) {
        super(context);
        mLockPatternUtils = lockPatternUtils;
        mLockSettings = Objects.requireNonNull(
                LocalServices.getService(LockSettingsInternal.class));
        mBiometricManager = Objects.requireNonNull(
                context.getSystemService(BiometricManager.class));
        mKeyguardManager = Objects.requireNonNull(context.getSystemService(KeyguardManager.class));
        mWindowManager = Objects.requireNonNull(
                LocalServices.getService(WindowManagerInternal.class));
        mUserManager = Objects.requireNonNull(LocalServices.getService(UserManagerInternal.class));
    }

    @Override
    public void onStart() {}

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            init();
        }
    }

    @VisibleForTesting
    void init() {
        mLockSettings.registerLockSettingsStateListener(mLockSettingsStateListener);
        mBiometricManager.registerAuthenticationStateListener(mAuthenticationStateListener);
    }

    private final LockSettingsStateListener mLockSettingsStateListener =
            new LockSettingsStateListener() {
                @Override
                public void onAuthenticationSucceeded(int userId) {
                    if (DEBUG) {
                        Slog.d(TAG, "LockSettingsStateListener#onAuthenticationSucceeded");
                    }
                    mHandler.obtainMessage(MSG_REPORT_PRIMARY_AUTH_ATTEMPT, AUTH_SUCCESS, userId)
                            .sendToTarget();
                }

                @Override
                public void onAuthenticationFailed(int userId) {
                    Slog.i(TAG, "LockSettingsStateListener#onAuthenticationFailed");
                    mHandler.obtainMessage(MSG_REPORT_PRIMARY_AUTH_ATTEMPT, AUTH_FAILURE, userId)
                            .sendToTarget();
                }
            };

    private final AuthenticationStateListener mAuthenticationStateListener =
            new AuthenticationStateListener.Stub() {
                @Override
                public void onAuthenticationAcquired(AuthenticationAcquiredInfo authInfo) {}

                @Override
                public void onAuthenticationError(AuthenticationErrorInfo authInfo) {}

                @Override
                public void onAuthenticationFailed(AuthenticationFailedInfo authInfo) {
                    Slog.i(TAG, "AuthenticationStateListener#onAuthenticationFailed");
                    mHandler.obtainMessage(MSG_REPORT_BIOMETRIC_AUTH_ATTEMPT, AUTH_FAILURE,
                                    authInfo.getUserId()).sendToTarget();
                }

                @Override
                public void onAuthenticationHelp(AuthenticationHelpInfo authInfo) {}

                @Override
                public void onAuthenticationStarted(AuthenticationStartedInfo authInfo) {}

                @Override
                public void onAuthenticationStopped(AuthenticationStoppedInfo authInfo) {}

                @Override
                public void onAuthenticationSucceeded(AuthenticationSucceededInfo authInfo) {
                    if (DEBUG) {
                        Slog.d(TAG, "AuthenticationStateListener#onAuthenticationSucceeded");
                    }
                    mHandler.obtainMessage(MSG_REPORT_BIOMETRIC_AUTH_ATTEMPT, AUTH_SUCCESS,
                                    authInfo.getUserId()).sendToTarget();
                }
            };

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REPORT_PRIMARY_AUTH_ATTEMPT:
                    handleReportPrimaryAuthAttempt(msg.arg1 != AUTH_FAILURE, msg.arg2);
                    break;
                case MSG_REPORT_BIOMETRIC_AUTH_ATTEMPT:
                    handleReportBiometricAuthAttempt(msg.arg1 != AUTH_FAILURE, msg.arg2);
                    break;
            }
        }
    };

    private void handleReportPrimaryAuthAttempt(boolean success, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "handleReportPrimaryAuthAttempt: success=" + success
                    + ", userId=" + userId);
        }
        reportAuthAttempt(TYPE_PRIMARY_AUTH, success, userId);
    }

    private void handleReportBiometricAuthAttempt(boolean success, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "handleReportBiometricAuthAttempt: success=" + success
                    + ", userId=" + userId);
        }
        reportAuthAttempt(TYPE_BIOMETRIC_AUTH, success, userId);
    }

    private void reportAuthAttempt(int authType, boolean success, int userId) {
        // Disable adaptive auth for automotive devices by default
        if (getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            return;
        }

        if (success) {
            // Deleting the entry effectively resets the counter of failed attempts for the user
            mFailedAttemptsForUser.delete(userId);

            // Collect metrics if the device was locked by adaptive auth before
            if (mLastLockedTimestamp.indexOfKey(userId) >= 0) {
                final long lastLockedTime = mLastLockedTimestamp.get(userId);
                collectTimeElapsedSinceLastLocked(
                        lastLockedTime, SystemClock.elapsedRealtime(), authType);

                // Remove the entry for the last locked time because a successful auth just happened
                // and metrics have been collected
                mLastLockedTimestamp.delete(userId);
            }
            return;
        }

        final int numFailedAttempts = mFailedAttemptsForUser.get(userId, 0) + 1;
        Slog.i(TAG, "reportAuthAttempt: numFailedAttempts=" + numFailedAttempts
                + ", userId=" + userId);
        mFailedAttemptsForUser.put(userId, numFailedAttempts);

        // Don't lock again if the device is already locked and if Keyguard is already showing and
        // isn't trivially dismissible
        if (mKeyguardManager.isDeviceLocked(userId) && mKeyguardManager.isKeyguardLocked()) {
            Slog.d(TAG, "Not locking the device because the device is already locked.");
            return;
        }

        if (numFailedAttempts < MAX_ALLOWED_FAILED_AUTH_ATTEMPTS) {
            Slog.d(TAG, "Not locking the device because the number of failed attempts is below"
                    + " the threshold.");
            return;
        }

        //TODO: additionally consider the trust signal before locking device
        lockDevice(userId);
    }

    private static void collectTimeElapsedSinceLastLocked(long lastLockedTime, long authTime,
            int authType) {
        final int unlockType =  switch (authType) {
            case TYPE_PRIMARY_AUTH -> FrameworkStatsLog
                    .ADAPTIVE_AUTH_UNLOCK_AFTER_LOCK_REPORTED__UNLOCK_TYPE__PRIMARY_AUTH;
            case TYPE_BIOMETRIC_AUTH -> FrameworkStatsLog
                    .ADAPTIVE_AUTH_UNLOCK_AFTER_LOCK_REPORTED__UNLOCK_TYPE__BIOMETRIC_AUTH;
            default -> FrameworkStatsLog
                    .ADAPTIVE_AUTH_UNLOCK_AFTER_LOCK_REPORTED__UNLOCK_TYPE__UNKNOWN;
        };

        if (DEBUG) {
            Slog.d(TAG, "collectTimeElapsedSinceLastLockedForUser: "
                    + "lastLockedTime=" + lastLockedTime
                    + ", authTime=" + authTime
                    + ", unlockType=" + unlockType);
        }

        // This usually shouldn't happen, and just check out of an abundance of caution
        if (lastLockedTime > authTime) {
            return;
        }

        // Log to statsd
        FrameworkStatsLog.write(FrameworkStatsLog.ADAPTIVE_AUTH_UNLOCK_AFTER_LOCK_REPORTED,
                lastLockedTime, authTime, unlockType);
    }

    /**
     * Locks the device and requires primary auth or biometric auth for unlocking
     */
    private void lockDevice(int userId) {
        // Require either primary auth or biometric auth to unlock the device again. Keyguard and
        // bouncer will also check the StrongAuthFlag for the user to display correct strings for
        // explaining why the device is locked
        mLockPatternUtils.requireStrongAuth(SOME_AUTH_REQUIRED_AFTER_ADAPTIVE_AUTH_REQUEST, userId);

        // If userId is a profile that has a different parent userId (regardless of its profile
        // type, or whether it's a profile with unified challenges or not), its parent userId that
        // owns the Keyguard will also be locked
        final int parentUserId = mUserManager.getProfileParentId(userId);
        Slog.i(TAG, "lockDevice: userId=" + userId + ", parentUserId=" + parentUserId);
        if (parentUserId != userId) {
            mLockPatternUtils.requireStrongAuth(SOME_AUTH_REQUIRED_AFTER_ADAPTIVE_AUTH_REQUEST,
                    parentUserId);
        }

        // Lock the device
        mWindowManager.lockNow();

        // Record the time that the device is locked by adaptive auth to collect metrics when the
        // next successful primary or biometric auth happens
        mLastLockedTimestamp.put(userId, SystemClock.elapsedRealtime());
    }
}
