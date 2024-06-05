/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors.fingerprint.hidl;

import static android.Manifest.permission.RESET_FINGERPRINT_LOCKOUT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.sensors.LockoutTracker;

import java.util.function.Function;

/**
 * Tracks and enforces biometric lockout for biometric sensors that do not support lockout in the
 * HAL.
 */
public class LockoutFrameworkImpl implements LockoutTracker {

    private static final String TAG = "LockoutFrameworkImpl";
    private static final String ACTION_LOCKOUT_RESET =
            "com.android.server.biometrics.sensors.fingerprint.ACTION_LOCKOUT_RESET";
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED = 5;
    private static final int MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT = 20;
    private static final long FAIL_LOCKOUT_TIMEOUT_MS = 30 * 1000;
    private static final String KEY_LOCKOUT_RESET_USER = "lockout_reset_user";

    private final class LockoutReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Slog.v(TAG, "Resetting lockout: " + intent.getAction());
            if (ACTION_LOCKOUT_RESET.equals(intent.getAction())) {
                final int user = intent.getIntExtra(KEY_LOCKOUT_RESET_USER, 0);
                resetFailedAttemptsForUser(false /* clearAttemptCounter */, user);
            }
        }
    }

    /**
     * Used to subscribe for callbacks when lockout state changes.
     */
    public interface LockoutResetCallback {
        void onLockoutReset(int userId);
    }

    private final LockoutResetCallback mLockoutResetCallback;
    private final SparseBooleanArray mTimedLockoutCleared;
    private final SparseIntArray mFailedAttempts;
    private final AlarmManager mAlarmManager;
    private final LockoutReceiver mLockoutReceiver;
    private final Handler mHandler;
    private final Function<Integer, PendingIntent> mLockoutResetIntent;

    public LockoutFrameworkImpl(@NonNull Context context,
            @NonNull LockoutResetCallback lockoutResetCallback) {
        this(context, lockoutResetCallback, (userId) -> PendingIntent.getBroadcast(context, userId,
                new Intent(ACTION_LOCKOUT_RESET).putExtra(KEY_LOCKOUT_RESET_USER, userId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE),
                null /* handler */);
    }

    public LockoutFrameworkImpl(@NonNull Context context,
            @NonNull LockoutResetCallback lockoutResetCallback,
            @NonNull Handler handler) {
        this(context, lockoutResetCallback, (userId) -> PendingIntent.getBroadcast(context, userId,
                new Intent(ACTION_LOCKOUT_RESET).putExtra(KEY_LOCKOUT_RESET_USER, userId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE),
                handler);
    }

    @VisibleForTesting
    LockoutFrameworkImpl(@NonNull Context context,
            @NonNull LockoutResetCallback lockoutResetCallback,
            @NonNull Function<Integer, PendingIntent> lockoutResetIntent,
            @Nullable Handler handler) {
        mLockoutResetCallback = lockoutResetCallback;
        mTimedLockoutCleared = new SparseBooleanArray();
        mFailedAttempts = new SparseIntArray();
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mLockoutReceiver = new LockoutReceiver();
        mHandler = handler == null ? new Handler(Looper.getMainLooper()) : handler;
        mLockoutResetIntent = lockoutResetIntent;

        context.registerReceiver(mLockoutReceiver, new IntentFilter(ACTION_LOCKOUT_RESET),
                RESET_FINGERPRINT_LOCKOUT, null /* handler */, Context.RECEIVER_EXPORTED);
    }

    // Attempt counter should only be cleared when Keyguard goes away or when
    // a biometric is successfully authenticated. Lockout should eventually be done below the HAL.
    // See AuthenticationClient#shouldFrameworkHandleLockout().
    @Override
    public void resetFailedAttemptsForUser(boolean clearAttemptCounter, int userId) {
        if (getLockoutModeForUser(userId) != LOCKOUT_NONE) {
            Slog.v(TAG, "Reset biometric lockout for user: " + userId
                    + ", clearAttemptCounter: " + clearAttemptCounter);
        }
        if (clearAttemptCounter) {
            mFailedAttempts.put(userId, 0);
        }
        mTimedLockoutCleared.put(userId, true);
        // If we're asked to reset failed attempts externally (i.e. from Keyguard),
        // the alarm might still be pending; remove it.
        cancelLockoutResetForUser(userId);
        mLockoutResetCallback.onLockoutReset(userId);
    }

    @Override
    public void addFailedAttemptForUser(int userId) {
        mFailedAttempts.put(userId, mFailedAttempts.get(userId, 0) + 1);
        mTimedLockoutCleared.put(userId, false);

        if (getLockoutModeForUser(userId) != LOCKOUT_NONE) {
            scheduleLockoutResetForUser(userId);
        }
    }

    @Override
    @LockoutMode
    public int getLockoutModeForUser(int userId) {
        final int failedAttempts = mFailedAttempts.get(userId, 0);
        if (failedAttempts >= MAX_FAILED_ATTEMPTS_LOCKOUT_PERMANENT) {
            return LOCKOUT_PERMANENT;
        } else if (failedAttempts > 0
                && !mTimedLockoutCleared.get(userId, false)
                && (failedAttempts % MAX_FAILED_ATTEMPTS_LOCKOUT_TIMED == 0)) {
            return LOCKOUT_TIMED;
        }
        return LOCKOUT_NONE;
    }

    @Override
    public void setLockoutModeForUser(int userId, int mode) {}

    private void cancelLockoutResetForUser(int userId) {
        mAlarmManager.cancel(mLockoutResetIntent.apply(userId));
    }

    private void scheduleLockoutResetForUser(int userId) {
        mHandler.post(() -> {
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + FAIL_LOCKOUT_TIMEOUT_MS,
                    mLockoutResetIntent.apply(userId));
        });
    }
}
