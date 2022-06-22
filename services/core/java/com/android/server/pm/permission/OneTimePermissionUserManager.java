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

package com.android.server.pm.permission;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.permission.PermissionControllerManager;
import android.provider.DeviceConfig;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.PermissionThread;

/**
 * Class that handles one-time permissions for a user
 */
public class OneTimePermissionUserManager {

    private static final String LOG_TAG = OneTimePermissionUserManager.class.getSimpleName();

    private static final boolean DEBUG = false;
    private static final long DEFAULT_KILLED_DELAY_MILLIS = 5000;
    public static final String PROPERTY_KILLED_DELAY_CONFIG_KEY =
            "one_time_permissions_killed_delay_millis";

    private final @NonNull Context mContext;
    private final @NonNull ActivityManager mActivityManager;
    private final @NonNull AlarmManager mAlarmManager;
    private final @NonNull PermissionControllerManager mPermissionControllerManager;

    private final Object mLock = new Object();

    private final BroadcastReceiver mUninstallListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_UID_REMOVED.equals(intent.getAction())) {
                int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                PackageInactivityListener listener = mListeners.get(uid);
                if (listener != null) {
                    if (DEBUG) {
                        Log.d(LOG_TAG, "Removing  the inactivity listener for " + uid);
                    }
                    listener.cancel();
                    mListeners.remove(uid);
                }
            }
        }
    };

    /** Maps the uid to the PackageInactivityListener */
    @GuardedBy("mLock")
    private final SparseArray<PackageInactivityListener> mListeners = new SparseArray<>();
    private final Handler mHandler;

    OneTimePermissionUserManager(@NonNull Context context) {
        mContext = context;
        mActivityManager = context.getSystemService(ActivityManager.class);
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mPermissionControllerManager = new PermissionControllerManager(
                mContext, PermissionThread.getHandler());
        mHandler = context.getMainThreadHandler();
    }

    /**
     * Starts a one-time permission session for a given package. A one-time permission session is
     * ended if app becomes inactive. Inactivity is defined as the package's uid importance level
     * staying > importanceToResetTimer for timeoutMillis milliseconds. If the package's uid
     * importance level goes <= importanceToResetTimer then the timer is reset and doesn't start
     * until going > importanceToResetTimer.
     * <p>
     * When this timeoutMillis is reached if the importance level is <= importanceToKeepSessionAlive
     * then the session is extended until either the importance goes above
     * importanceToKeepSessionAlive which will end the session or <= importanceToResetTimer which
     * will continue the session and reset the timer.
     * </p>
     * <p>
     * Importance levels are defined in {@link android.app.ActivityManager.RunningAppProcessInfo}.
     * </p>
     * <p>
     * Once the session ends PermissionControllerService#onNotifyOneTimePermissionSessionTimeout
     * is invoked.
     * </p>
     * <p>
     * Note that if there is currently an active session for a package a new one isn't created and
     * the existing one isn't changed.
     * </p>
     * @param packageName The package to start a one-time permission session for
     * @param timeoutMillis Number of milliseconds for an app to be in an inactive state
     * @param revokeAfterKilledDelayMillis Number of milliseconds to wait after the process dies
     *                                     before ending the session. Set to -1 to use default value
     *                                     for the device.
     * @param importanceToResetTimer The least important level to uid must be to reset the timer
     * @param importanceToKeepSessionAlive The least important level the uid must be to keep the
     *                                     session alive
     *
     * @hide
     */
    void startPackageOneTimeSession(@NonNull String packageName, long timeoutMillis,
            long revokeAfterKilledDelayMillis, int importanceToResetTimer,
            int importanceToKeepSessionAlive) {
        int uid;
        try {
            uid = mContext.getPackageManager().getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Unknown package name " + packageName, e);
            return;
        }

        synchronized (mLock) {
            PackageInactivityListener listener = mListeners.get(uid);
            if (listener != null) {
                listener.updateSessionParameters(timeoutMillis, revokeAfterKilledDelayMillis,
                        importanceToResetTimer, importanceToKeepSessionAlive);
                return;
            }
            listener = new PackageInactivityListener(uid, packageName, timeoutMillis,
                    revokeAfterKilledDelayMillis, importanceToResetTimer,
                    importanceToKeepSessionAlive);
            mListeners.put(uid, listener);
        }
    }

    /**
     * Stops the one-time permission session for the package. The callback to the end of session is
     * not invoked. If there is no one-time session for the package then nothing happens.
     *
     * @param packageName Package to stop the one-time permission session for
     */
    void stopPackageOneTimeSession(@NonNull String packageName) {
        int uid;
        try {
            uid = mContext.getPackageManager().getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG, "Unknown package name " + packageName, e);
            return;
        }

        synchronized (mLock) {
            PackageInactivityListener listener = mListeners.get(uid);
            if (listener != null) {
                mListeners.remove(uid);
                listener.cancel();
            }
        }
    }

    /**
     * Register to listen for Uids being uninstalled. This must be done outside of the
     * PermissionManagerService lock.
     */
    void registerUninstallListener() {
        mContext.registerReceiver(mUninstallListener, new IntentFilter(Intent.ACTION_UID_REMOVED));
    }

    /**
     * A class which watches a package for inactivity and notifies the permission controller when
     * the package becomes inactive
     */
    private class PackageInactivityListener implements AlarmManager.OnAlarmListener {

        private static final long TIMER_INACTIVE = -1;

        private final int mUid;
        private final @NonNull String mPackageName;
        private long mTimeout;
        private long mRevokeAfterKilledDelay;
        private int mImportanceToResetTimer;
        private int mImportanceToKeepSessionAlive;

        private boolean mIsAlarmSet;
        private boolean mIsFinished;

        private long mTimerStart = TIMER_INACTIVE;

        private final ActivityManager.OnUidImportanceListener mStartTimerListener;
        private final ActivityManager.OnUidImportanceListener mSessionKillableListener;
        private final ActivityManager.OnUidImportanceListener mGoneListener;

        private final Object mInnerLock = new Object();
        private final Object mToken = new Object();

        private PackageInactivityListener(int uid, @NonNull String packageName, long timeout,
                long revokeAfterkilledDelay, int importanceToResetTimer,
                int importanceToKeepSessionAlive) {

            Log.i(LOG_TAG,
                    "Start tracking " + packageName + ". uid=" + uid + " timeout=" + timeout
                            + " killedDelay=" + revokeAfterkilledDelay
                            + " importanceToResetTimer=" + importanceToResetTimer
                            + " importanceToKeepSessionAlive=" + importanceToKeepSessionAlive);

            mUid = uid;
            mPackageName = packageName;
            mTimeout = timeout;
            mRevokeAfterKilledDelay = revokeAfterkilledDelay == -1
                    ? DeviceConfig.getLong(
                            DeviceConfig.NAMESPACE_PERMISSIONS, PROPERTY_KILLED_DELAY_CONFIG_KEY,
                            DEFAULT_KILLED_DELAY_MILLIS)
                    : revokeAfterkilledDelay;
            mImportanceToResetTimer = importanceToResetTimer;
            mImportanceToKeepSessionAlive = importanceToKeepSessionAlive;

            mStartTimerListener =
                    (changingUid, importance) -> onImportanceChanged(changingUid, importance);
            mSessionKillableListener =
                    (changingUid, importance) -> onImportanceChanged(changingUid, importance);
            mGoneListener =
                    (changingUid, importance) -> onImportanceChanged(changingUid, importance);

            mActivityManager.addOnUidImportanceListener(mStartTimerListener,
                    importanceToResetTimer);
            mActivityManager.addOnUidImportanceListener(mSessionKillableListener,
                    importanceToKeepSessionAlive);
            mActivityManager.addOnUidImportanceListener(mGoneListener, IMPORTANCE_CACHED);

            onImportanceChanged(mUid, mActivityManager.getPackageImportance(packageName));
        }

        public void updateSessionParameters(long timeoutMillis, long revokeAfterKilledDelayMillis,
                int importanceToResetTimer, int importanceToKeepSessionAlive) {
            synchronized (mInnerLock) {
                mTimeout = Math.min(mTimeout, timeoutMillis);
                mRevokeAfterKilledDelay = Math.min(mRevokeAfterKilledDelay,
                        revokeAfterKilledDelayMillis == -1
                                ? DeviceConfig.getLong(
                                DeviceConfig.NAMESPACE_PERMISSIONS,
                                PROPERTY_KILLED_DELAY_CONFIG_KEY, DEFAULT_KILLED_DELAY_MILLIS)
                                : revokeAfterKilledDelayMillis);
                mImportanceToResetTimer = Math.min(importanceToResetTimer, mImportanceToResetTimer);
                mImportanceToKeepSessionAlive = Math.min(importanceToKeepSessionAlive,
                        mImportanceToKeepSessionAlive);
                Log.v(LOG_TAG,
                        "Updated params for " + mPackageName + ". timeout=" + mTimeout
                                + " killedDelay=" + mRevokeAfterKilledDelay
                                + " importanceToResetTimer=" + mImportanceToResetTimer
                                + " importanceToKeepSessionAlive=" + mImportanceToKeepSessionAlive);
                onImportanceChanged(mUid, mActivityManager.getPackageImportance(mPackageName));
            }
        }

        private void onImportanceChanged(int uid, int importance) {
            if (uid != mUid) {
                return;
            }

            Log.v(LOG_TAG, "Importance changed for " + mPackageName + " (" + mUid + ")."
                    + " importance=" + importance);
            synchronized (mInnerLock) {
                // Remove any pending inactivity callback
                mHandler.removeCallbacksAndMessages(mToken);

                if (importance > IMPORTANCE_CACHED) {
                    if (mRevokeAfterKilledDelay == 0) {
                        onPackageInactiveLocked();
                        return;
                    }
                    // Delay revocation in case app is restarting
                    mHandler.postDelayed(() -> {
                        int imp = mActivityManager.getUidImportance(mUid);
                        if (imp > IMPORTANCE_CACHED) {
                            onPackageInactiveLocked();
                        } else {
                            if (DEBUG) {
                                Log.d(LOG_TAG, "No longer gone after delayed revocation. "
                                        + "Rechecking for " + mPackageName + " (" + mUid + ").");
                            }
                            onImportanceChanged(mUid, imp);
                        }
                    }, mToken, mRevokeAfterKilledDelay);
                    return;
                }
                if (importance > mImportanceToResetTimer) {
                    if (mTimerStart == TIMER_INACTIVE) {
                        if (DEBUG) {
                            Log.d(LOG_TAG, "Start the timer for "
                                    + mPackageName + " (" + mUid + ").");
                        }
                        mTimerStart = System.currentTimeMillis();
                    }
                } else {
                    mTimerStart = TIMER_INACTIVE;
                }
                if (importance > mImportanceToKeepSessionAlive) {
                    setAlarmLocked();
                } else {
                    cancelAlarmLocked();
                }
            }
        }

        /**
         * Stop watching the package for inactivity
         */
        private void cancel() {
            synchronized (mInnerLock) {
                mIsFinished = true;
                cancelAlarmLocked();
                try {
                    mActivityManager.removeOnUidImportanceListener(mStartTimerListener);
                } catch (IllegalArgumentException e) {
                    Log.e(LOG_TAG, "Could not remove start timer listener", e);
                }
                try {
                    mActivityManager.removeOnUidImportanceListener(mSessionKillableListener);
                } catch (IllegalArgumentException e) {
                    Log.e(LOG_TAG, "Could not remove session killable listener", e);
                }
                try {
                    mActivityManager.removeOnUidImportanceListener(mGoneListener);
                } catch (IllegalArgumentException e) {
                    Log.e(LOG_TAG, "Could not remove gone listener", e);
                }
            }
        }

        /**
         * Set the alarm which will callback when the package is inactive
         */
        @GuardedBy("mInnerLock")
        private void setAlarmLocked() {
            if (mIsAlarmSet) {
                return;
            }

            if (DEBUG) {
                Log.d(LOG_TAG, "Scheduling alarm for " + mPackageName + " (" + mUid + ").");
            }
            long revokeTime = mTimerStart + mTimeout;
            if (revokeTime > System.currentTimeMillis()) {
                mAlarmManager.setExact(AlarmManager.RTC_WAKEUP, revokeTime, LOG_TAG, this,
                        mHandler);
                mIsAlarmSet = true;
            } else {
                mIsAlarmSet = true;
                onAlarm();
            }
        }

        /**
         * Cancel the alarm
         */
        @GuardedBy("mInnerLock")
        private void cancelAlarmLocked() {
            if (mIsAlarmSet) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Canceling alarm for " + mPackageName + " (" + mUid + ").");
                }
                mAlarmManager.cancel(this);
                mIsAlarmSet = false;
            }
        }

        /**
         * Called when the package is considered inactive. This is the end of the session
         */
        @GuardedBy("mInnerLock")
        private void onPackageInactiveLocked() {
            if (mIsFinished) {
                return;
            }
            if (DEBUG) {
                Log.d(LOG_TAG, "onPackageInactiveLocked stack trace for "
                        + mPackageName + " (" + mUid + ").", new RuntimeException());
            }
            mIsFinished = true;
            cancelAlarmLocked();
            mHandler.post(
                    () -> {
                        Log.i(LOG_TAG, "One time session expired for "
                                + mPackageName + " (" + mUid + ").");

                        mPermissionControllerManager.notifyOneTimePermissionSessionTimeout(
                                mPackageName);
                    });
            mActivityManager.removeOnUidImportanceListener(mStartTimerListener);
            mActivityManager.removeOnUidImportanceListener(mSessionKillableListener);
            mActivityManager.removeOnUidImportanceListener(mGoneListener);
            synchronized (mLock) {
                mListeners.remove(mUid);
            }
        }

        @Override
        public void onAlarm() {
            if (DEBUG) {
                Log.d(LOG_TAG, "Alarm received for " + mPackageName + " (" + mUid + ").");
            }
            synchronized (mInnerLock) {
                if (!mIsAlarmSet) {
                    return;
                }
                mIsAlarmSet = false;
                onPackageInactiveLocked();
            }
        }
    }
}
