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

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.app.UidObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.RemoteException;
import android.permission.PermissionControllerManager;
import android.provider.DeviceConfig;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
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
    private final @NonNull IActivityManager mIActivityManager;
    private final @NonNull ActivityManagerInternal mActivityManagerInternal;
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
        mIActivityManager = ActivityManager.getService();
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mPermissionControllerManager = new PermissionControllerManager(
                mContext, PermissionThread.getHandler());
        mHandler = context.getMainThreadHandler();
    }

    void startPackageOneTimeSession(@NonNull String packageName, int deviceId, long timeoutMillis,
            long revokeAfterKilledDelayMillis) {
        int uid;
        try {
            uid = mContext.getPackageManager().getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(LOG_TAG,
                    "Unknown package name " + packageName + ", device ID " + deviceId, e);
            return;
        }

        synchronized (mLock) {
            PackageInactivityListener listener = mListeners.get(uid);
            if (listener != null) {
                listener.updateSessionParameters(timeoutMillis, revokeAfterKilledDelayMillis);
                return;
            }
            listener = new PackageInactivityListener(uid, packageName, deviceId, timeoutMillis,
                    revokeAfterKilledDelayMillis);
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

        private static final int STATE_GONE = 0;
        private static final int STATE_TIMER = 1;
        private static final int STATE_ACTIVE = 2;

        private final int mUid;
        private final @NonNull String mPackageName;
        private final int mDeviceId;
        private long mTimeout;
        private long mRevokeAfterKilledDelay;

        private boolean mIsAlarmSet;
        private boolean mIsFinished;

        private long mTimerStart = TIMER_INACTIVE;

        private final Object mInnerLock = new Object();
        private final Object mToken = new Object();
        private final IUidObserver mObserver = new UidObserver() {
            @Override
            public void onUidGone(int uid, boolean disabled) {
                if (uid == mUid) {
                    PackageInactivityListener.this.updateUidState(STATE_GONE);
                }
            }

            @Override
            public void onUidStateChanged(int uid, int procState, long procStateSeq,
                    int capability) {
                if (uid == mUid) {
                    if (procState > ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE
                            && procState != ActivityManager.PROCESS_STATE_NONEXISTENT) {
                        PackageInactivityListener.this.updateUidState(STATE_TIMER);
                    } else {
                        PackageInactivityListener.this.updateUidState(STATE_ACTIVE);
                    }
                }
            }
        };

        private PackageInactivityListener(int uid, @NonNull String packageName, int deviceId,
                long timeout, long revokeAfterkilledDelay) {
            Log.i(LOG_TAG,
                    "Start tracking " + packageName + ". uid=" + uid + " timeout=" + timeout
                            + " killedDelay=" + revokeAfterkilledDelay);

            mUid = uid;
            mPackageName = packageName;
            mDeviceId = deviceId;
            mTimeout = timeout;
            mRevokeAfterKilledDelay = revokeAfterkilledDelay == -1
                    ? DeviceConfig.getLong(
                            DeviceConfig.NAMESPACE_PERMISSIONS, PROPERTY_KILLED_DELAY_CONFIG_KEY,
                            DEFAULT_KILLED_DELAY_MILLIS)
                    : revokeAfterkilledDelay;

            try {
                mIActivityManager.registerUidObserver(mObserver,
                        ActivityManager.UID_OBSERVER_GONE | ActivityManager.UID_OBSERVER_PROCSTATE,
                        ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE,
                        null);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Couldn't check uid proc state", e);
                // Can't register uid observer, just revoke immediately
                synchronized (mInnerLock) {
                    onPackageInactiveLocked();
                }
            }

            updateUidState();
        }

        public void updateSessionParameters(long timeoutMillis, long revokeAfterKilledDelayMillis) {
            synchronized (mInnerLock) {
                mTimeout = Math.min(mTimeout, timeoutMillis);
                mRevokeAfterKilledDelay = Math.min(mRevokeAfterKilledDelay,
                        revokeAfterKilledDelayMillis == -1
                                ? DeviceConfig.getLong(
                                DeviceConfig.NAMESPACE_PERMISSIONS,
                                PROPERTY_KILLED_DELAY_CONFIG_KEY, DEFAULT_KILLED_DELAY_MILLIS)
                                : revokeAfterKilledDelayMillis);
                Log.v(LOG_TAG,
                        "Updated params for " + mPackageName + ", device ID " + mDeviceId
                                + ". timeout=" + mTimeout
                                + " killedDelay=" + mRevokeAfterKilledDelay);
                updateUidState();
            }
        }

        private int getCurrentState() {
            return getStateFromProcState(mActivityManagerInternal.getUidProcessState(mUid));
        }

        private int getStateFromProcState(int procState) {
            if (procState == ActivityManager.PROCESS_STATE_NONEXISTENT) {
                return STATE_GONE;
            } else {
                if (procState > ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE) {
                    return STATE_TIMER;
                } else {
                    return STATE_ACTIVE;
                }
            }
        }

        private void updateUidState() {
            updateUidState(getCurrentState());
        }

        private void updateUidState(int state) {
            Log.v(LOG_TAG, "Updating state for " + mPackageName + " (" + mUid + ")."
                    + " device ID=" + mDeviceId + ", state=" + state);
            synchronized (mInnerLock) {
                // Remove any pending inactivity callback
                mHandler.removeCallbacksAndMessages(mToken);

                if (state == STATE_GONE) {
                    if (mRevokeAfterKilledDelay == 0) {
                        onPackageInactiveLocked();
                        return;
                    }
                    // Delay revocation in case app is restarting
                    mHandler.postDelayed(() -> {
                        int currentState;
                        synchronized (mInnerLock) {
                            currentState = getCurrentState();
                            if (currentState == STATE_GONE) {
                                onPackageInactiveLocked();
                                return;
                            }
                        }
                        if (DEBUG) {
                            Log.d(LOG_TAG, "No longer gone after delayed revocation. "
                                    + "Rechecking for " + mPackageName + " (" + mUid
                                    + "). device ID " + mDeviceId);
                        }
                        updateUidState(currentState);
                    }, mToken, mRevokeAfterKilledDelay);
                    return;
                } else if (state == STATE_TIMER) {
                    if (mTimerStart == TIMER_INACTIVE) {
                        if (DEBUG) {
                            Log.d(LOG_TAG, "Start the timer for "
                                    + mPackageName + " (" + mUid + "). device ID " + mDeviceId);
                        }
                        mTimerStart = System.currentTimeMillis();
                        setAlarmLocked();
                    }
                } else if (state == STATE_ACTIVE) {
                    mTimerStart = TIMER_INACTIVE;
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
                    mIActivityManager.unregisterUidObserver(mObserver);
                } catch (RemoteException e) {
                    Log.e(LOG_TAG, "Unable to unregister uid observer.", e);
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
                Log.d(LOG_TAG, "Scheduling alarm for " + mPackageName + " (" + mUid + ")."
                        + " device ID " + mDeviceId);
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
                    Log.d(LOG_TAG, "Canceling alarm for " + mPackageName + " (" + mUid + ")."
                            + " device ID " + mDeviceId);
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
                        + mPackageName + " (" + mUid + "). device ID " + mDeviceId,
                        new RuntimeException());
            }
            mIsFinished = true;
            cancelAlarmLocked();
            mHandler.post(
                    () -> {
                        Log.i(LOG_TAG, "One time session expired for "
                                + mPackageName + " (" + mUid + "). deviceID " + mDeviceId);
                        mPermissionControllerManager.notifyOneTimePermissionSessionTimeout(
                                mPackageName, mDeviceId);
                    });
            try {
                mIActivityManager.unregisterUidObserver(mObserver);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Unable to unregister uid observer.", e);
            }
            synchronized (mLock) {
                mListeners.remove(mUid);
            }
        }

        @Override
        public void onAlarm() {
            if (DEBUG) {
                Log.d(LOG_TAG, "Alarm received for " + mPackageName + " (" + mUid + ")."
                        + " device ID " + mDeviceId);
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
