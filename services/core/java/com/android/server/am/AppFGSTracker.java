/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.am;

import static android.app.ActivityManager.RESTRICTION_LEVEL_UNKNOWN;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.AppRestrictionController.DEVICE_CONFIG_SUBNAMESPACE_PREFIX;
import static com.android.server.am.BaseAppStateTracker.ONE_DAY;
import static com.android.server.am.BaseAppStateTracker.ONE_HOUR;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager.RestrictionLevel;
import android.app.ActivityManagerInternal.ForegroundServiceStateListener;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ProcessMap;
import com.android.server.am.AppFGSTracker.AppFGSPolicy;
import com.android.server.am.BaseAppStateTracker.Injector;

import java.lang.reflect.Constructor;
import java.util.LinkedList;

/**
 * The tracker for monitoring abusive (long-running) FGS.
 */
final class AppFGSTracker extends BaseAppStateTracker<AppFGSPolicy>
        implements ForegroundServiceStateListener {
    static final String TAG = TAG_WITH_CLASS_NAME ? "AppFGSTracker" : TAG_AM;

    static final boolean DEBUG_BACKGROUND_FGS_TRACKER = false;

    private final MyHandler mHandler;

    @GuardedBy("mLock")
    private final ProcessMap<PackageDurations> mPkgFgsDurations = new ProcessMap<>();

    // Unlocked since it's only accessed in single thread.
    private final ArraySet<PackageDurations> mTmpPkgDurations = new ArraySet<>();

    @Override
    public void onForegroundServiceStateChanged(String packageName,
            int uid, int pid, boolean started) {
        mHandler.obtainMessage(started ? MyHandler.MSG_FOREGROUND_SERVICES_STARTED
                : MyHandler.MSG_FOREGROUND_SERVICES_STOPPED, pid, uid, packageName).sendToTarget();
    }

    private static class MyHandler extends Handler {
        static final int MSG_FOREGROUND_SERVICES_STARTED = 0;
        static final int MSG_FOREGROUND_SERVICES_STOPPED = 1;
        static final int MSG_CHECK_LONG_RUNNING_FGS = 2;

        private final AppFGSTracker mTracker;

        MyHandler(AppFGSTracker tracker) {
            super(tracker.mBgHandler.getLooper());
            mTracker = tracker;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_FOREGROUND_SERVICES_STARTED:
                    mTracker.handleForegroundServicesChanged(
                            (String) msg.obj, msg.arg1, msg.arg2, true);
                    break;
                case MSG_FOREGROUND_SERVICES_STOPPED:
                    mTracker.handleForegroundServicesChanged(
                            (String) msg.obj, msg.arg1, msg.arg2, false);
                    break;
                case MSG_CHECK_LONG_RUNNING_FGS:
                    mTracker.checkLongRunningFgs();
                    break;
            }
        }
    }

    AppFGSTracker(Context context, AppRestrictionController controller) {
        this(context, controller, null, null);
    }

    AppFGSTracker(Context context, AppRestrictionController controller,
            Constructor<? extends Injector<AppFGSPolicy>> injector, Object outerContext) {
        super(context, controller, injector, outerContext);
        mHandler = new MyHandler(this);
        mInjector.setPolicy(new AppFGSPolicy(mInjector, this));
    }

    @Override
    void onSystemReady() {
        super.onSystemReady();
        mInjector.getActivityManagerInternal().addForegroundServiceStateListener(this);
    }

    @VisibleForTesting
    void reset() {
        mHandler.removeMessages(MyHandler.MSG_CHECK_LONG_RUNNING_FGS);
        synchronized (mLock) {
            mPkgFgsDurations.clear();
        }
    }

    private void handleForegroundServicesChanged(String packageName, int pid, int uid,
            boolean started) {
        if (!mInjector.getPolicy().isEnabled() || mInjector.getPolicy().shouldExemptUid(uid)) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        boolean longRunningFGSGone = false;
        synchronized (mLock) {
            PackageDurations pkg = mPkgFgsDurations.get(packageName, uid);
            if (pkg == null) {
                pkg = new PackageDurations(uid, packageName);
                mPkgFgsDurations.put(packageName, uid, pkg);
            }
            final boolean wasLongRunning = pkg.isLongRunning();
            pkg.addEvent(started, now);
            longRunningFGSGone = wasLongRunning && !pkg.hasForegroundServices();
            if (longRunningFGSGone) {
                pkg.setIsLongRunning(false);
            }
            // Reschedule the checks.
            scheduleDurationCheckLocked(now);
        }
        if (longRunningFGSGone) {
            // The long-running FGS is gone, cancel the notification.
            mInjector.getPolicy().onLongRunningFgsGone(packageName, uid);
        }
    }

    @GuardedBy("mLock")
    private void scheduleDurationCheckLocked(long now) {
        // Look for the active FGS with longest running time till now.
        final ArrayMap<String, SparseArray<PackageDurations>> map = mPkgFgsDurations.getMap();
        long longest = -1;
        for (int i = map.size() - 1; i >= 0; i--) {
            final SparseArray<PackageDurations> val = map.valueAt(i);
            for (int j = val.size() - 1; j >= 0; j--) {
                final PackageDurations pkg = val.valueAt(j);
                if (!pkg.hasForegroundServices() || pkg.isLongRunning()) {
                    // No FGS or it's a known long-running FGS, ignore it.
                    continue;
                }
                longest = Math.max(pkg.getTotalDurations(now), longest);
            }
        }
        // Schedule a check in the future.
        mHandler.removeMessages(MyHandler.MSG_CHECK_LONG_RUNNING_FGS);
        if (longest >= 0) {
            final long future = Math.max(0,
                    mInjector.getPolicy().getFgsLongRunningThreshold() - longest);
            if (DEBUG_BACKGROUND_FGS_TRACKER) {
                Slog.i(TAG, "Scheduling a FGS duration check at "
                        + TimeUtils.formatDuration(future));
            }
            mHandler.sendEmptyMessageDelayed(MyHandler.MSG_CHECK_LONG_RUNNING_FGS, future);
        } else if (DEBUG_BACKGROUND_FGS_TRACKER) {
            Slog.i(TAG, "Not scheduling FGS duration check");
        }
    }

    private void checkLongRunningFgs() {
        final AppFGSPolicy policy = mInjector.getPolicy();
        final ArraySet<PackageDurations> pkgWithLongFgs = mTmpPkgDurations;
        final long now = SystemClock.elapsedRealtime();
        final long threshold = policy.getFgsLongRunningThreshold();
        final long windowSize = policy.getFgsLongRunningWindowSize();
        final long trimTo = Math.max(0, now - windowSize);

        synchronized (mLock) {
            final ArrayMap<String, SparseArray<PackageDurations>> map = mPkgFgsDurations.getMap();
            for (int i = map.size() - 1; i >= 0; i--) {
                final SparseArray<PackageDurations> val = map.valueAt(i);
                for (int j = val.size() - 1; j >= 0; j--) {
                    final PackageDurations pkg = val.valueAt(j);
                    if (pkg.hasForegroundServices() && !pkg.isLongRunning()) {
                        final long totalDuration = pkg.getTotalDurations(now);
                        if (totalDuration >= threshold) {
                            pkgWithLongFgs.add(pkg);
                            pkg.setIsLongRunning(true);
                            if (DEBUG_BACKGROUND_FGS_TRACKER) {
                                Slog.i(TAG, pkg.mPackageName
                                        + "/" + UserHandle.formatUid(pkg.mUid)
                                        + " has FGS running for "
                                        + TimeUtils.formatDuration(totalDuration)
                                        + " over " + TimeUtils.formatDuration(windowSize));
                            }
                        }
                    }
                    // Trim the duration list, we don't need to keep track of all old records.
                    pkg.trim(trimTo);
                }
            }
        }

        for (int i = pkgWithLongFgs.size() - 1; i >= 0; i--) {
            final PackageDurations pkg = pkgWithLongFgs.valueAt(i);
            policy.onLongRunningFgs(pkg.mPackageName, pkg.mUid);
        }
        pkgWithLongFgs.clear();

        synchronized (mLock) {
            scheduleDurationCheckLocked(now);
        }
    }

    private void onBgFgsMonitorEnabled(boolean enabled) {
        if (enabled) {
            synchronized (mLock) {
                scheduleDurationCheckLocked(SystemClock.elapsedRealtime());
            }
        } else {
            mHandler.removeMessages(MyHandler.MSG_CHECK_LONG_RUNNING_FGS);
            synchronized (mLock) {
                mPkgFgsDurations.clear();
            }
        }
    }

    private void onBgFgsLongRunningThresholdChanged() {
        synchronized (mLock) {
            if (mInjector.getPolicy().isEnabled()) {
                scheduleDurationCheckLocked(SystemClock.elapsedRealtime());
            }
        }
    }

    @Override
    void onUidRemoved(final int uid) {
        synchronized (mLock) {
            final ArrayMap<String, SparseArray<PackageDurations>> map = mPkgFgsDurations.getMap();
            for (int i = map.size() - 1; i >= 0; i--) {
                final SparseArray<PackageDurations> val = map.valueAt(i);
                final int index = val.indexOfKey(uid);
                if (index >= 0) {
                    val.removeAt(index);
                    if (val.size() == 0) {
                        map.removeAt(i);
                    }
                }
            }
        }
    }

    @Override
    void onUserRemoved(final @UserIdInt int userId) {
        synchronized (mLock) {
            final ArrayMap<String, SparseArray<PackageDurations>> map = mPkgFgsDurations.getMap();
            for (int i = map.size() - 1; i >= 0; i--) {
                final SparseArray<PackageDurations> val = map.valueAt(i);
                for (int j = val.size() - 1; j >= 0; j--) {
                    final int uid = val.keyAt(j);
                    if (UserHandle.getUserId(uid) == userId) {
                        val.removeAt(j);
                    }
                }
                if (val.size() == 0) {
                    map.removeAt(i);
                }
            }
        }
    }

    /**
     * Tracks the durations with active FGS for a given package.
     */
    static class PackageDurations {
        final int mUid;
        final String mPackageName;

        /**
         * A list of timestamps when the FGS start/stop occurred, we may trim/modify the start time
         * in this list, so don't use this timestamp anywhere else.
         */
        final LinkedList<Long> mStartStopTime = new LinkedList<>();

        private long mKnownDuration;
        private int mNest; // A counter to track in case that the package gets multiple FGS starts.
        private boolean mIsLongRunning;

        PackageDurations(int uid, String packageName) {
            mUid = uid;
            mPackageName = packageName;
        }

        void addEvent(boolean startFgs, long now) {
            final int size = mStartStopTime.size();
            final boolean hasForegroundServices = hasForegroundServices();

            if (startFgs) {
                mNest++;
            } else {
                if (DEBUG_BACKGROUND_FGS_TRACKER && mNest <= 0) {
                    Slog.wtf(TAG, "Under-counted FGS start event mNest=" + mNest);
                    return;
                }
                mNest--;
                if (mNest == 0) {
                    mKnownDuration += now - mStartStopTime.getLast();
                    mIsLongRunning = false;
                }
            }
            if (startFgs == hasForegroundServices) {
                // It's actually the same state as we have now, don't record the event time.
                return;
            }
            if (DEBUG_BACKGROUND_FGS_TRACKER) {
                if (startFgs != ((mStartStopTime.size() & 1) == 0)) {
                    Slog.wtf(TAG, "Unmatched start/stop event, current=" + mStartStopTime.size());
                    return;
                }
            }
            mStartStopTime.add(now);
        }

        void setIsLongRunning(boolean isLongRunning) {
            mIsLongRunning = isLongRunning;
        }

        boolean isLongRunning() {
            return mIsLongRunning;
        }

        /**
         * Remove/trim earlier durations with start time older than the given timestamp.
         */
        void trim(long earliest) {
            while (mStartStopTime.size() > 1) {
                final long current = mStartStopTime.peek();
                if (current >= earliest) {
                    break; // All we have are newer than the given timestamp.
                }
                // Check the timestamp of FGS stop event.
                if (mStartStopTime.get(1) > earliest) {
                    // Trim the duration by moving the start time.
                    mStartStopTime.set(0, earliest);
                    break;
                }
                // Discard the 1st duration as it's older than the given timestamp.
                mStartStopTime.pop();
                mStartStopTime.pop();
            }
            mKnownDuration = 0;
            if (mStartStopTime.size() == 1) {
                // Trim the duration by moving the start time.
                mStartStopTime.set(0, Math.max(earliest, mStartStopTime.peek()));
                return;
            }
            // Update the known durations.
            int index = 0;
            long last = 0;
            for (long timestamp : mStartStopTime) {
                if ((index & 1) == 1) {
                    mKnownDuration += timestamp - last;
                } else {
                    last = timestamp;
                }
                index++;
            }
        }

        long getTotalDurations(long now) {
            return hasForegroundServices()
                    ? mKnownDuration + (now - mStartStopTime.getLast()) : mKnownDuration;
        }

        boolean hasForegroundServices() {
            return mNest > 0;
        }

        @Override
        public String toString() {
            return mPackageName + "/" + UserHandle.formatUid(mUid)
                    + " hasForegroundServices=" + hasForegroundServices()
                    + " totalDurations=" + getTotalDurations(SystemClock.elapsedRealtime());
        }
    }

    static final class AppFGSPolicy extends BaseAppStatePolicy<AppFGSTracker> {
        /**
         * Whether or not we should enable the monitoring on abusive FGS.
         */
        static final String KEY_BG_FGS_MONITOR_ENABLED =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "fgs_monitor_enabled";

        /**
         * The size of the sliding window in which the accumulated FGS durations are checked
         * against the threshold.
         */
        static final String KEY_BG_FGS_LONG_RUNNING_WINDOW =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "fgs_long_running_window";

        /**
         * The threshold at where the accumulated FGS durations are considered as "long-running"
         * within the given window.
         */
        static final String KEY_BG_FGS_LONG_RUNNING_THRESHOLD =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "fgs_long_running_threshold";

        /**
         * Default value to {@link #mBgFgsMonitorEnabled}.
         */
        static final boolean DEFAULT_BG_FGS_MONITOR_ENABLED = true;

        /**
         * Default value to {@link #mBgFgsLongRunningWindowMs}.
         */
        static final long DEFAULT_BG_FGS_LONG_RUNNING_WINDOW = ONE_DAY;

        /**
         * Default value to {@link #mBgFgsLongRunningThresholdMs}.
         */
        static final long DEFAULT_BG_FGS_LONG_RUNNING_THRESHOLD = 20 * ONE_HOUR;

        /**
         * @see #KEY_BG_FGS_MONITOR_ENABLED.
         */
        private volatile boolean mBgFgsMonitorEnabled = DEFAULT_BG_FGS_MONITOR_ENABLED;

        /**
         * @see #KEY_BG_FGS_LONG_RUNNING_WINDOW.
         */
        private volatile long mBgFgsLongRunningWindowMs = DEFAULT_BG_FGS_LONG_RUNNING_WINDOW;

        /**
         * @see #KEY_BG_FGS_LONG_RUNNING_THRESHOLD.
         */
        private volatile long mBgFgsLongRunningThresholdMs = DEFAULT_BG_FGS_LONG_RUNNING_THRESHOLD;

        @NonNull
        private final Object mLock;

        AppFGSPolicy(@NonNull Injector injector, @NonNull AppFGSTracker tracker) {
            super(injector, tracker);
            mLock = tracker.mLock;
        }

        @Override
        public void onSystemReady() {
            updateBgFgsMonitorEnabled();
            updateBgFgsLongRunningThreshold();
        }

        @Override
        public void onPropertiesChanged(String name) {
            switch (name) {
                case KEY_BG_FGS_MONITOR_ENABLED:
                    updateBgFgsMonitorEnabled();
                    break;
                case KEY_BG_FGS_LONG_RUNNING_WINDOW:
                case KEY_BG_FGS_LONG_RUNNING_THRESHOLD:
                    updateBgFgsLongRunningThreshold();
                    break;
            }
        }

        private void updateBgFgsMonitorEnabled() {
            final boolean enabled = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_FGS_MONITOR_ENABLED,
                    DEFAULT_BG_FGS_MONITOR_ENABLED);
            if (enabled != mBgFgsMonitorEnabled) {
                mBgFgsMonitorEnabled = enabled;
                mTracker.onBgFgsMonitorEnabled(enabled);
            }
        }

        private void updateBgFgsLongRunningThreshold() {
            final long window = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_FGS_LONG_RUNNING_WINDOW,
                    DEFAULT_BG_FGS_LONG_RUNNING_WINDOW);
            final long threshold = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_FGS_LONG_RUNNING_THRESHOLD,
                    DEFAULT_BG_FGS_LONG_RUNNING_THRESHOLD);
            if (threshold != mBgFgsLongRunningThresholdMs || window != mBgFgsLongRunningWindowMs) {
                mBgFgsLongRunningWindowMs = window;
                mBgFgsLongRunningThresholdMs = threshold;
                mTracker.onBgFgsLongRunningThresholdChanged();
            }
        }

        @Override
        public boolean isEnabled() {
            return mBgFgsMonitorEnabled;
        }

        long getFgsLongRunningThreshold() {
            return mBgFgsLongRunningThresholdMs;
        }

        long getFgsLongRunningWindowSize() {
            return mBgFgsLongRunningWindowMs;
        }

        void onLongRunningFgs(String packageName, int uid) {
            mTracker.mAppRestrictionController.postLongRunningFgsIfNecessary(packageName, uid);
        }

        void onLongRunningFgsGone(String packageName, int uid) {
            mTracker.mAppRestrictionController
                    .cancelLongRunningFGSNotificationIfNecessary(packageName, uid);
        }

        @Override
        public @RestrictionLevel int getProposedRestrictionLevel(String packageName, int uid) {
            return RESTRICTION_LEVEL_UNKNOWN;
        }
    }
}
