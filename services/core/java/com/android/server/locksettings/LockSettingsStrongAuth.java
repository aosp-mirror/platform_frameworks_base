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
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT;

import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.app.admin.DevicePolicyManager;
import android.app.trust.IStrongAuthTracker;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseIntArray;

import com.android.internal.widget.LockPatternUtils.StrongAuthTracker;

/**
 * Keeps track of requests for strong authentication.
 */
public class LockSettingsStrongAuth {

    private static final String TAG = "LockSettings";

    private static final int MSG_REQUIRE_STRONG_AUTH = 1;
    private static final int MSG_REGISTER_TRACKER = 2;
    private static final int MSG_UNREGISTER_TRACKER = 3;
    private static final int MSG_REMOVE_USER = 4;
    private static final int MSG_SCHEDULE_STRONG_AUTH_TIMEOUT = 5;

    private static final String STRONG_AUTH_TIMEOUT_ALARM_TAG =
            "LockSettingsStrongAuth.timeoutForUser";

    private final RemoteCallbackList<IStrongAuthTracker> mTrackers = new RemoteCallbackList<>();
    private final SparseIntArray mStrongAuthForUser = new SparseIntArray();
    private final ArrayMap<Integer, StrongAuthTimeoutAlarmListener>
            mStrongAuthTimeoutAlarmListenerForUser = new ArrayMap<>();
    private final int mDefaultStrongAuthFlags;
    private final Context mContext;

    private AlarmManager mAlarmManager;
    private BiometricManager mBiometricManager;

    public LockSettingsStrongAuth(Context context) {
        mContext = context;
        mDefaultStrongAuthFlags = StrongAuthTracker.getDefaultFlags(context);
        mAlarmManager = context.getSystemService(AlarmManager.class);
    }

    public void systemReady() {
        if (BiometricManager.hasBiometrics(mContext)) {
            mBiometricManager = mContext.getSystemService(BiometricManager.class);
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

    private void handleRemoveUser(int userId) {
        int index = mStrongAuthForUser.indexOfKey(userId);
        if (index >= 0) {
            mStrongAuthForUser.removeAt(index);
            notifyStrongAuthTrackers(mDefaultStrongAuthFlags, userId);
        }
    }

    private void handleScheduleStrongAuthTimeout(int userId) {
        final DevicePolicyManager dpm =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        long when = SystemClock.elapsedRealtime() + dpm.getRequiredStrongAuthTimeout(null, userId);
        // cancel current alarm listener for the user (if there was one)
        StrongAuthTimeoutAlarmListener alarm = mStrongAuthTimeoutAlarmListenerForUser.get(userId);
        if (alarm != null) {
            mAlarmManager.cancel(alarm);
        } else {
            alarm = new StrongAuthTimeoutAlarmListener(userId);
            mStrongAuthTimeoutAlarmListenerForUser.put(userId, alarm);
        }
        // schedule a new alarm listener for the user
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, when, STRONG_AUTH_TIMEOUT_ALARM_TAG,
                alarm, mHandler);
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

    public void reportUnlock(int userId) {
        requireStrongAuth(STRONG_AUTH_NOT_REQUIRED, userId);
    }

    public void reportSuccessfulStrongAuthUnlock(int userId) {
        if (mBiometricManager != null) {
            byte[] token = null; /* TODO: pass real auth token once HAL supports it */
            mBiometricManager.resetTimeout(token);
        }

        final int argNotUsed = 0;
        mHandler.obtainMessage(MSG_SCHEDULE_STRONG_AUTH_TIMEOUT, userId, argNotUsed).sendToTarget();
    }

    private class StrongAuthTimeoutAlarmListener implements OnAlarmListener {

        private final int mUserId;

        public StrongAuthTimeoutAlarmListener(int userId) {
            mUserId = userId;
        }

        @Override
        public void onAlarm() {
            requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_TIMEOUT, mUserId);
        }
    }

    private final Handler mHandler = new Handler() {
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
            }
        }
    };

}
