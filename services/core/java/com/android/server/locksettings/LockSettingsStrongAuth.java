/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.locksettings;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT;

import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.app.admin.DevicePolicyManager;
import android.app.trust.IStrongAuthTracker;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker;

/**
 * Keeps track of requests for strong authentication.
 */
public class LockSettingsStrongAuth {

    private static final String TAG = "LockSettings";
    private static final boolean DEBUG = false;

    private static final int MSG_REQUIRE_STRONG_AUTH = 1;
    private static final int MSG_REGISTER_TRACKER = 2;
    private static final int MSG_UNREGISTER_TRACKER = 3;
    private static final int MSG_REMOVE_USER = 4;
    private static final int MSG_SCHEDULE_STRONG_AUTH_TIMEOUT = 5;
    private static final int MSG_NO_LONGER_REQUIRE_STRONG_AUTH = 6;
    private static final int MSG_SCHEDULE_NON_STRONG_BIOMETRIC_TIMEOUT = 7;
    private static final int MSG_STRONG_BIOMETRIC_UNLOCK = 8;
    private static final int MSG_SCHEDULE_NON_STRONG_BIOMETRIC_IDLE_TIMEOUT = 9;
    private static final int MSG_REFRESH_STRONG_AUTH_TIMEOUT = 10;

    @VisibleForTesting
    protected static final String STRONG_AUTH_TIMEOUT_ALARM_TAG =
            "LockSettingsStrongAuth.timeoutForUser";
    @VisibleForTesting
    protected static final String NON_STRONG_BIOMETRIC_TIMEOUT_ALARM_TAG =
            "LockSettingsPrimaryAuth.nonStrongBiometricTimeoutForUser";
    @VisibleForTesting
    protected static final String NON_STRONG_BIOMETRIC_IDLE_TIMEOUT_ALARM_TAG =
            "LockSettingsPrimaryAuth.nonStrongBiometricIdleTimeoutForUser";

    /**
     * Default and maximum timeout in milliseconds after which unlocking with weak auth times out,
     * i.e. the user has to use a strong authentication method like password, PIN or pattern.
     */
    public static final long DEFAULT_NON_STRONG_BIOMETRIC_TIMEOUT_MS = 24 * 60 * 60 * 1000; // 24h
    public static final long DEFAULT_NON_STRONG_BIOMETRIC_IDLE_TIMEOUT_MS =
            4 * 60 * 60 * 1000; // 4h

    private final RemoteCallbackList<IStrongAuthTracker> mTrackers = new RemoteCallbackList<>();
    @VisibleForTesting
    protected final SparseIntArray mStrongAuthForUser = new SparseIntArray();
    @VisibleForTesting
    protected final SparseBooleanArray mIsNonStrongBiometricAllowedForUser =
            new SparseBooleanArray();
    @VisibleForTesting
    protected final ArrayMap<Integer, StrongAuthTimeoutAlarmListener>
            mStrongAuthTimeoutAlarmListenerForUser = new ArrayMap<>();
    // Track non-strong biometric timeout
    @VisibleForTesting
    protected final ArrayMap<Integer, NonStrongBiometricTimeoutAlarmListener>
            mNonStrongBiometricTimeoutAlarmListener = new ArrayMap<>();
    // Track non-strong biometric idle timeout
    @VisibleForTesting
    protected final ArrayMap<Integer, NonStrongBiometricIdleTimeoutAlarmListener>
            mNonStrongBiometricIdleTimeoutAlarmListener = new ArrayMap<>();

    private final int mDefaultStrongAuthFlags;
    private final boolean mDefaultIsNonStrongBiometricAllowed = true;

    private final Context mContext;
    private final Injector mInjector;
    private final AlarmManager mAlarmManager;

    public LockSettingsStrongAuth(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    protected LockSettingsStrongAuth(Context context, Injector injector) {
        mContext = context;
        mInjector = injector;
        mDefaultStrongAuthFlags = mInjector.getDefaultStrongAuthFlags(context);
        mAlarmManager = mInjector.getAlarmManager(context);
    }

    /**
     * Class for injecting dependencies into LockSettingsStrongAuth.
     */
    @VisibleForTesting
    public static class Injector {

        /**
         * Allows to mock AlarmManager for testing.
         */
        @VisibleForTesting
        public AlarmManager getAlarmManager(Context context) {
            return context.getSystemService(AlarmManager.class);
        }

        /**
         * Allows to get different default StrongAuthFlags for testing.
         */
        @VisibleForTesting
        public int getDefaultStrongAuthFlags(Context context) {
            return StrongAuthTracker.getDefaultFlags(context);
        }

        /**
         * Allows to get different triggerAtMillis values when setting alarms for testing.
         */
        @VisibleForTesting
        public long getNextAlarmTimeMs(long timeout) {
            return SystemClock.elapsedRealtime() + timeout;
        }

        /**
         * Wraps around {@link SystemClock#elapsedRealtime}, which returns the number of
         * milliseconds since boot, including time spent in sleep.
         */
        @VisibleForTesting
        public long getElapsedRealtimeMs() {
            return SystemClock.elapsedRealtime();
        }
    }

    private void handleAddStrongAuthTracker(IStrongAuthTracker tracker) {
        mTrackers.register(tracker);

        for (int i = 0; i < mStrongAuthForUser.size(); i++) {
            int key = mStrongAuthForUser.keyAt(i);
            int value = mStrongAuthForUser.valueAt(i);
            try {
                tracker.onStrongAuthRequiredChanged(value, key);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception while adding StrongAuthTracker.", e);
            }
        }

        for (int i = 0; i < mIsNonStrongBiometricAllowedForUser.size(); i++) {
            int key = mIsNonStrongBiometricAllowedForUser.keyAt(i);
            boolean value = mIsNonStrongBiometricAllowedForUser.valueAt(i);
            try {
                tracker.onIsNonStrongBiometricAllowedChanged(value, key);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception while adding StrongAuthTracker: "
                        + "IsNonStrongBiometricAllowedChanged.", e);
            }
        }
    }

    private void handleRemoveStrongAuthTracker(IStrongAuthTracker tracker) {
        mTrackers.unregister(tracker);
    }

    private void handleRequireStrongAuth(int strongAuthReason, int userId) {
        if (userId == UserHandle.USER_ALL) {
            for (int i = 0; i < mStrongAuthForUser.size(); i++) {
                int key = mStrongAuthForUser.keyAt(i);
                handleRequireStrongAuthOneUser(strongAuthReason, key);
            }
        } else {
            handleRequireStrongAuthOneUser(strongAuthReason, userId);
        }
    }

    private void handleRequireStrongAuthOneUser(int strongAuthReason, int userId) {
        int oldValue = mStrongAuthForUser.get(userId, mDefaultStrongAuthFlags);
        int newValue = strongAuthReason == STRONG_AUTH_NOT_REQUIRED
                ? STRONG_AUTH_NOT_REQUIRED
                : (oldValue | strongAuthReason);
        if (oldValue != newValue) {
            mStrongAuthForUser.put(userId, newValue);
            notifyStrongAuthTrackers(newValue, userId);
        }
    }

    private void handleNoLongerRequireStrongAuth(int strongAuthReason, int userId) {
        if (userId == UserHandle.USER_ALL) {
            for (int i = 0; i < mStrongAuthForUser.size(); i++) {
                int key = mStrongAuthForUser.keyAt(i);
                handleNoLongerRequireStrongAuthOneUser(strongAuthReason, key);
            }
        } else {
            handleNoLongerRequireStrongAuthOneUser(strongAuthReason, userId);
        }
    }

    private void handleNoLongerRequireStrongAuthOneUser(int strongAuthReason, int userId) {
        int oldValue = mStrongAuthForUser.get(userId, mDefaultStrongAuthFlags);
        int newValue = oldValue & ~strongAuthReason;
        if (oldValue != newValue) {
            mStrongAuthForUser.put(userId, newValue);
            notifyStrongAuthTrackers(newValue, userId);
        }
    }

    private void handleRemoveUser(int userId) {
        int index = mStrongAuthForUser.indexOfKey(userId);
        if (index >= 0) {
            mStrongAuthForUser.removeAt(index);
            notifyStrongAuthTrackers(mDefaultStrongAuthFlags, userId);
        }

        index = mIsNonStrongBiometricAllowedForUser.indexOfKey(userId);
        if (index >= 0) {
            mIsNonStrongBiometricAllowedForUser.removeAt(index);
            notifyStrongAuthTrackersForIsNonStrongBiometricAllowed(
                    mDefaultIsNonStrongBiometricAllowed, userId);
        }
    }

    /**
     * Re-schedule the strong auth timeout alarm with latest information on the most recent
     * successful strong auth time and strong auth timeout from device policy.
     */
    private void rescheduleStrongAuthTimeoutAlarm(long strongAuthTime, int userId) {
        final DevicePolicyManager dpm =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        // cancel current alarm listener for the user (if there was one)
        StrongAuthTimeoutAlarmListener alarm = mStrongAuthTimeoutAlarmListenerForUser.get(userId);
        if (alarm != null) {
            mAlarmManager.cancel(alarm);
            alarm.setLatestStrongAuthTime(strongAuthTime);
        } else {
            alarm = new StrongAuthTimeoutAlarmListener(strongAuthTime, userId);
            mStrongAuthTimeoutAlarmListenerForUser.put(userId, alarm);
        }
        // AlarmManager.set() correctly handles the case where nextAlarmTime has already been in
        // the past (by firing the listener straight away), so nothing special for us to do here.
        long nextAlarmTime = strongAuthTime + dpm.getRequiredStrongAuthTimeout(null, userId);

        // schedule a new alarm listener for the user
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, nextAlarmTime,
                STRONG_AUTH_TIMEOUT_ALARM_TAG, alarm, mHandler);
    }

    private void handleScheduleStrongAuthTimeout(int userId) {
        rescheduleStrongAuthTimeoutAlarm(mInjector.getElapsedRealtimeMs(), userId);

        // cancel current non-strong biometric alarm listener for the user (if there was one)
        cancelNonStrongBiometricAlarmListener(userId);
        // cancel current non-strong biometric idle alarm listener for the user (if there was one)
        cancelNonStrongBiometricIdleAlarmListener(userId);
        // re-allow unlock with non-strong biometrics
        setIsNonStrongBiometricAllowed(true, userId);
    }

    private void handleRefreshStrongAuthTimeout(int userId) {
        StrongAuthTimeoutAlarmListener alarm = mStrongAuthTimeoutAlarmListenerForUser.get(userId);
        if (alarm != null) {
            rescheduleStrongAuthTimeoutAlarm(alarm.getLatestStrongAuthTime(), userId);
        }
    }

    private void handleScheduleNonStrongBiometricTimeout(int userId) {
        if (DEBUG) Slog.d(TAG, "handleScheduleNonStrongBiometricTimeout for userId=" + userId);
        long nextAlarmTime = mInjector.getNextAlarmTimeMs(DEFAULT_NON_STRONG_BIOMETRIC_TIMEOUT_MS);
        NonStrongBiometricTimeoutAlarmListener alarm = mNonStrongBiometricTimeoutAlarmListener
                .get(userId);
        if (alarm != null) {
            // Unlock with non-strong biometric will not affect the existing non-strong biometric
            // timeout alarm
            if (DEBUG) {
                Slog.d(TAG, "There is an existing alarm for non-strong biometric"
                        + " fallback timeout, so do not re-schedule");
            }
        } else {
            if (DEBUG) {
                Slog.d(TAG, "Schedule a new alarm for non-strong biometric fallback timeout");
            }
            alarm = new NonStrongBiometricTimeoutAlarmListener(userId);
            mNonStrongBiometricTimeoutAlarmListener.put(userId, alarm);
            // schedule a new alarm listener for the user
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, nextAlarmTime,
                    NON_STRONG_BIOMETRIC_TIMEOUT_ALARM_TAG, alarm, mHandler);
        }

        // cancel current non-strong biometric idle alarm listener for the user (if there was one)
        cancelNonStrongBiometricIdleAlarmListener(userId);
    }

    private void handleStrongBiometricUnlock(int userId) {
        if (DEBUG) Slog.d(TAG, "handleStrongBiometricUnlock for userId=" + userId);
        // cancel current non-strong biometric alarm listener for the user (if there was one)
        cancelNonStrongBiometricAlarmListener(userId);
        // cancel current non-strong biometric idle alarm listener for the user (if there was one)
        cancelNonStrongBiometricIdleAlarmListener(userId);
        // re-allow unlock with non-strong biometrics
        setIsNonStrongBiometricAllowed(true, userId);
    }

    private void cancelNonStrongBiometricAlarmListener(int userId) {
        if (DEBUG) Slog.d(TAG, "cancelNonStrongBiometricAlarmListener for userId=" + userId);
        NonStrongBiometricTimeoutAlarmListener alarm = mNonStrongBiometricTimeoutAlarmListener
                .get(userId);
        if (alarm != null) {
            if (DEBUG) Slog.d(TAG, "Cancel alarm for non-strong biometric fallback timeout");
            mAlarmManager.cancel(alarm);
            // need to remove the alarm when cancelled by primary auth or strong biometric
            mNonStrongBiometricTimeoutAlarmListener.remove(userId);
        }
    }

    private void cancelNonStrongBiometricIdleAlarmListener(int userId) {
        if (DEBUG) Slog.d(TAG, "cancelNonStrongBiometricIdleAlarmListener for userId=" + userId);
        // cancel idle alarm listener by any unlocks (i.e. primary auth, strong biometric,
        // non-strong biometric)
        NonStrongBiometricIdleTimeoutAlarmListener alarm =
                mNonStrongBiometricIdleTimeoutAlarmListener.get(userId);
        if (alarm != null) {
            if (DEBUG) Slog.d(TAG, "Cancel alarm for non-strong biometric idle timeout");
            mAlarmManager.cancel(alarm);
        }
    }

    @VisibleForTesting
    protected void setIsNonStrongBiometricAllowed(boolean allowed, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "setIsNonStrongBiometricAllowed for allowed=" + allowed
                    + ", userId=" + userId);
        }
        if (userId == UserHandle.USER_ALL) {
            for (int i = 0; i < mIsNonStrongBiometricAllowedForUser.size(); i++) {
                int key = mIsNonStrongBiometricAllowedForUser.keyAt(i);
                setIsNonStrongBiometricAllowedOneUser(allowed, key);
            }
        } else {
            setIsNonStrongBiometricAllowedOneUser(allowed, userId);
        }
    }

    private void setIsNonStrongBiometricAllowedOneUser(boolean allowed, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "setIsNonStrongBiometricAllowedOneUser for allowed=" + allowed
                    + ", userId=" + userId);
        }
        boolean oldValue = mIsNonStrongBiometricAllowedForUser.get(userId,
                mDefaultIsNonStrongBiometricAllowed);
        if (allowed != oldValue) {
            if (DEBUG) {
                Slog.d(TAG, "mIsNonStrongBiometricAllowedForUser value changed:"
                        + " oldValue=" + oldValue + ", allowed=" + allowed);
            }
            mIsNonStrongBiometricAllowedForUser.put(userId, allowed);
            notifyStrongAuthTrackersForIsNonStrongBiometricAllowed(allowed, userId);
        }
    }

    private void handleScheduleNonStrongBiometricIdleTimeout(int userId) {
        if (DEBUG) Slog.d(TAG, "handleScheduleNonStrongBiometricIdleTimeout for userId=" + userId);
        long nextAlarmTime =
                mInjector.getNextAlarmTimeMs(DEFAULT_NON_STRONG_BIOMETRIC_IDLE_TIMEOUT_MS);
        // cancel current alarm listener for the user (if there was one)
        NonStrongBiometricIdleTimeoutAlarmListener alarm =
                mNonStrongBiometricIdleTimeoutAlarmListener.get(userId);
        if (alarm != null) {
            if (DEBUG) Slog.d(TAG, "Cancel existing alarm for non-strong biometric idle timeout");
            mAlarmManager.cancel(alarm);
        } else {
            alarm = new NonStrongBiometricIdleTimeoutAlarmListener(userId);
            mNonStrongBiometricIdleTimeoutAlarmListener.put(userId, alarm);
        }
        // schedule a new alarm listener for the user
        if (DEBUG) Slog.d(TAG, "Schedule a new alarm for non-strong biometric idle timeout");
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, nextAlarmTime,
                NON_STRONG_BIOMETRIC_IDLE_TIMEOUT_ALARM_TAG, alarm, mHandler);
    }

    private void notifyStrongAuthTrackers(int strongAuthReason, int userId) {
        int i = mTrackers.beginBroadcast();
        try {
            while (i > 0) {
                i--;
                try {
                    mTrackers.getBroadcastItem(i).onStrongAuthRequiredChanged(
                            strongAuthReason, userId);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Exception while notifying StrongAuthTracker.", e);
                }
            }
        } finally {
            mTrackers.finishBroadcast();
        }
    }

    private void notifyStrongAuthTrackersForIsNonStrongBiometricAllowed(boolean allowed,
            int userId) {
        if (DEBUG) {
            Slog.d(TAG, "notifyStrongAuthTrackersForIsNonStrongBiometricAllowed"
                    + " for allowed=" + allowed + ", userId=" + userId);
        }
        int i = mTrackers.beginBroadcast();
        try {
            while (i > 0) {
                i--;
                try {
                    mTrackers.getBroadcastItem(i).onIsNonStrongBiometricAllowedChanged(
                            allowed, userId);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Exception while notifying StrongAuthTracker: "
                            + "IsNonStrongBiometricAllowedChanged.", e);
                }
            }
        } finally {
            mTrackers.finishBroadcast();
        }
    }

    public void registerStrongAuthTracker(IStrongAuthTracker tracker) {
        mHandler.obtainMessage(MSG_REGISTER_TRACKER, tracker).sendToTarget();
    }

    public void unregisterStrongAuthTracker(IStrongAuthTracker tracker) {
        mHandler.obtainMessage(MSG_UNREGISTER_TRACKER, tracker).sendToTarget();
    }

    public void removeUser(int userId) {
        final int argNotUsed = 0;
        mHandler.obtainMessage(MSG_REMOVE_USER, userId, argNotUsed).sendToTarget();
    }

    public void requireStrongAuth(int strongAuthReason, int userId) {
        if (userId == UserHandle.USER_ALL || userId >= UserHandle.USER_SYSTEM) {
            mHandler.obtainMessage(MSG_REQUIRE_STRONG_AUTH, strongAuthReason,
                    userId).sendToTarget();
        } else {
            throw new IllegalArgumentException(
                    "userId must be an explicit user id or USER_ALL");
        }
    }

    void noLongerRequireStrongAuth(int strongAuthReason, int userId) {
        if (userId == UserHandle.USER_ALL || userId >= UserHandle.USER_SYSTEM) {
            mHandler.obtainMessage(MSG_NO_LONGER_REQUIRE_STRONG_AUTH, strongAuthReason,
                    userId).sendToTarget();
        } else {
            throw new IllegalArgumentException(
                    "userId must be an explicit user id or USER_ALL");
        }
    }

    public void reportUnlock(int userId) {
        requireStrongAuth(STRONG_AUTH_NOT_REQUIRED, userId);
    }

    /**
     * Report successful unlocking with primary auth
     */
    public void reportSuccessfulStrongAuthUnlock(int userId) {
        final int argNotUsed = 0;
        mHandler.obtainMessage(MSG_SCHEDULE_STRONG_AUTH_TIMEOUT, userId, argNotUsed).sendToTarget();
    }

    /**
     * Refreshes pending strong auth timeout with the latest admin requirement set by device policy.
     */
    public void refreshStrongAuthTimeout(int userId) {
        mHandler.obtainMessage(MSG_REFRESH_STRONG_AUTH_TIMEOUT, userId, 0).sendToTarget();
    }

    /**
     * Report successful unlocking with biometric
     */
    public void reportSuccessfulBiometricUnlock(boolean isStrongBiometric, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "reportSuccessfulBiometricUnlock for isStrongBiometric="
                    + isStrongBiometric + ", userId=" + userId);
        }
        final int argNotUsed = 0;
        if (isStrongBiometric) { // unlock with strong biometric
            mHandler.obtainMessage(MSG_STRONG_BIOMETRIC_UNLOCK, userId, argNotUsed)
                    .sendToTarget();
        } else { // unlock with non-strong biometric (i.e. weak or convenience)
            mHandler.obtainMessage(MSG_SCHEDULE_NON_STRONG_BIOMETRIC_TIMEOUT, userId, argNotUsed)
                    .sendToTarget();
        }
    }

    /**
     * Schedule idle timeout for non-strong biometric (i.e. weak or convenience)
     */
    public void scheduleNonStrongBiometricIdleTimeout(int userId) {
        if (DEBUG) Slog.d(TAG, "scheduleNonStrongBiometricIdleTimeout for userId=" + userId);
        final int argNotUsed = 0;
        mHandler.obtainMessage(MSG_SCHEDULE_NON_STRONG_BIOMETRIC_IDLE_TIMEOUT, userId, argNotUsed)
                .sendToTarget();
    }

    /**
     * Alarm of fallback timeout for primary auth
     */
    @VisibleForTesting
    protected class StrongAuthTimeoutAlarmListener implements OnAlarmListener {

        private long mLatestStrongAuthTime;
        private final int mUserId;

        public StrongAuthTimeoutAlarmListener(long latestStrongAuthTime, int userId) {
            mLatestStrongAuthTime = latestStrongAuthTime;
            mUserId = userId;
        }

        /**
         * Sets the most recent time when a successful strong auth happened, in number of
         * milliseconds.
         */
        public void setLatestStrongAuthTime(long strongAuthTime) {
            mLatestStrongAuthTime = strongAuthTime;
        }

        /**
         * Returns the most recent time when a successful strong auth happened, in number of
         * milliseconds.
         */
        public long getLatestStrongAuthTime() {
            return mLatestStrongAuthTime;
        }

        @Override
        public void onAlarm() {
            requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_TIMEOUT, mUserId);
        }
    }

    /**
     * Alarm of fallback timeout for non-strong biometric (i.e. weak or convenience)
     */
    @VisibleForTesting
    protected class NonStrongBiometricTimeoutAlarmListener implements OnAlarmListener {

        private final int mUserId;

        NonStrongBiometricTimeoutAlarmListener(int userId) {
            mUserId = userId;
        }

        @Override
        public void onAlarm() {
            requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT, mUserId);
        }
    }

    /**
     * Alarm of idle timeout for non-strong biometric (i.e. weak or convenience biometric)
     */
    @VisibleForTesting
    protected class NonStrongBiometricIdleTimeoutAlarmListener implements OnAlarmListener {

        private final int mUserId;

        NonStrongBiometricIdleTimeoutAlarmListener(int userId) {
            mUserId = userId;
        }

        @Override
        public void onAlarm() {
            // disallow unlock with non-strong biometrics
            setIsNonStrongBiometricAllowed(false, mUserId);
        }
    }

    @VisibleForTesting
    protected final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REGISTER_TRACKER:
                    handleAddStrongAuthTracker((IStrongAuthTracker) msg.obj);
                    break;
                case MSG_UNREGISTER_TRACKER:
                    handleRemoveStrongAuthTracker((IStrongAuthTracker) msg.obj);
                    break;
                case MSG_REQUIRE_STRONG_AUTH:
                    handleRequireStrongAuth(msg.arg1, msg.arg2);
                    break;
                case MSG_REMOVE_USER:
                    handleRemoveUser(msg.arg1);
                    break;
                case MSG_SCHEDULE_STRONG_AUTH_TIMEOUT:
                    handleScheduleStrongAuthTimeout(msg.arg1);
                    break;
                case MSG_REFRESH_STRONG_AUTH_TIMEOUT:
                    handleRefreshStrongAuthTimeout(msg.arg1);
                    break;
                case MSG_NO_LONGER_REQUIRE_STRONG_AUTH:
                    handleNoLongerRequireStrongAuth(msg.arg1, msg.arg2);
                    break;
                case MSG_SCHEDULE_NON_STRONG_BIOMETRIC_TIMEOUT:
                    handleScheduleNonStrongBiometricTimeout(msg.arg1);
                    break;
                case MSG_STRONG_BIOMETRIC_UNLOCK:
                    handleStrongBiometricUnlock(msg.arg1);
                    break;
                case MSG_SCHEDULE_NON_STRONG_BIOMETRIC_IDLE_TIMEOUT:
                    handleScheduleNonStrongBiometricIdleTimeout(msg.arg1);
                    break;
            }
        }
    };

    public void dump(IndentingPrintWriter pw) {
        pw.println("PrimaryAuthFlags state:");
        pw.increaseIndent();
        for (int i = 0; i < mStrongAuthForUser.size(); i++) {
            final int key = mStrongAuthForUser.keyAt(i);
            final int value = mStrongAuthForUser.valueAt(i);
            pw.println("userId=" + key + ", primaryAuthFlags=" + Integer.toHexString(value));
        }
        pw.println();
        pw.decreaseIndent();

        pw.println("NonStrongBiometricAllowed state:");
        pw.increaseIndent();
        for (int i = 0; i < mIsNonStrongBiometricAllowedForUser.size(); i++) {
            final int key = mIsNonStrongBiometricAllowedForUser.keyAt(i);
            final boolean value = mIsNonStrongBiometricAllowedForUser.valueAt(i);
            pw.println("userId=" + key + ", allowed=" + value);
        }
        pw.println();
        pw.decreaseIndent();
    }
}
