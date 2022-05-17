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

import static android.app.ActivityManager.RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
import static android.app.ActivityManager.RESTRICTION_LEVEL_RESTRICTED_BUCKET;
import static android.app.ActivityManager.RESTRICTION_LEVEL_UNKNOWN;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_SYSTEM;
import static android.app.usage.UsageStatsManager.REASON_MAIN_USAGE;
import static android.app.usage.UsageStatsManager.REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_USER_INTERACTION;
import static android.os.PowerExemptionManager.REASON_DENIED;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_FGS;
import static android.os.PowerExemptionManager.REASON_PROC_STATE_TOP;
import static android.os.PowerExemptionManager.reasonCodeToString;

import static com.android.server.am.BaseAppStateTracker.ONE_MINUTE;

import android.annotation.NonNull;
import android.app.ActivityManager.RestrictionLevel;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ProcessMap;
import com.android.server.am.BaseAppStateTimeSlotEventsTracker.BaseAppStateTimeSlotEventsPolicy;
import com.android.server.am.BaseAppStateTimeSlotEventsTracker.SimpleAppStateTimeslotEvents;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;

/**
 * Base class to track {@link #BaseAppStateTimeSlotEvents}.
 */
abstract class BaseAppStateTimeSlotEventsTracker
        <T extends BaseAppStateTimeSlotEventsPolicy, U extends SimpleAppStateTimeslotEvents>
        extends BaseAppStateEventsTracker<T, U> {
    static final String TAG = "BaseAppStateTimeSlotEventsTracker";

    static final boolean DEBUG_APP_STATE_TIME_SLOT_EVENT_TRACKER = false;

    // Unlocked since it's only accessed in single thread.
    private final ArrayMap<U, Integer> mTmpPkgs = new ArrayMap<>();

    private H mHandler;

    BaseAppStateTimeSlotEventsTracker(Context context, AppRestrictionController controller,
            Constructor<? extends Injector<T>> injector, Object outerContext) {
        super(context, controller, injector, outerContext);
        mHandler = new H(this);
    }

    void onNewEvent(String packageName, int uid) {
        mHandler.obtainMessage(H.MSG_NEW_EVENT, uid, 0, packageName).sendToTarget();
    }

    void handleNewEvent(String packageName, int uid) {
        if (mInjector.getPolicy().shouldExempt(packageName, uid) != REASON_DENIED) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        boolean notify = false;
        int totalEvents;
        synchronized (mLock) {
            U pkgEvents = mPkgEvents.get(uid, packageName);
            if (pkgEvents == null) {
                pkgEvents = createAppStateEvents(uid, packageName);
                mPkgEvents.put(uid, packageName, pkgEvents);
            }
            pkgEvents.addEvent(now, SimpleAppStateTimeslotEvents.DEFAULT_INDEX);
            totalEvents = pkgEvents.getTotalEvents(now, SimpleAppStateTimeslotEvents.DEFAULT_INDEX);
            notify = totalEvents >= mInjector.getPolicy().getNumOfEventsThreshold();
        }
        if (notify) {
            mInjector.getPolicy().onExcessiveEvents(
                    packageName, uid, totalEvents, now);
        }
    }

    void onMonitorEnabled(boolean enabled) {
        if (!enabled) {
            synchronized (mLock) {
                mPkgEvents.clear();
            }
        }
    }

    void onNumOfEventsThresholdChanged(int threshold) {
        final long now = SystemClock.elapsedRealtime();
        synchronized (mLock) {
            SparseArray<ArrayMap<String, U>> pkgEvents = mPkgEvents.getMap();
            for (int i = pkgEvents.size() - 1; i >= 0; i--) {
                final ArrayMap<String, U> pkgs = pkgEvents.valueAt(i);
                for (int j = pkgs.size() - 1; j >= 0; j--) {
                    final U pkg = pkgs.valueAt(j);
                    int totalEvents = pkg.getTotalEvents(now,
                            SimpleAppStateTimeslotEvents.DEFAULT_INDEX);
                    if (totalEvents >= threshold) {
                        mTmpPkgs.put(pkg, totalEvents);
                    }
                }
            }
        }
        for (int i = mTmpPkgs.size() - 1; i >= 0; i--) {
            final U pkg = mTmpPkgs.keyAt(i);
            mInjector.getPolicy().onExcessiveEvents(
                    pkg.mPackageName, pkg.mUid, mTmpPkgs.valueAt(i), now);
        }
        mTmpPkgs.clear();
    }

    @GuardedBy("mLock")
    int getTotalEventsLocked(int uid, long now) {
        final U events = getUidEventsLocked(uid);
        if (events == null) {
            return 0;
        }
        return events.getTotalEvents(now, SimpleAppStateTimeslotEvents.DEFAULT_INDEX);
    }

    private void trimEvents() {
        final long now = SystemClock.elapsedRealtime();
        trim(Math.max(0, now - mInjector.getPolicy().getMaxTrackingDuration()));
    }

    @Override
    void onUserInteractionStarted(String packageName, int uid) {
        mInjector.getPolicy().onUserInteractionStarted(packageName, uid);
    }

    static class H extends Handler {
        static final int MSG_NEW_EVENT = 0;

        final BaseAppStateTimeSlotEventsTracker mTracker;

        H(BaseAppStateTimeSlotEventsTracker tracker) {
            super(tracker.mBgHandler.getLooper());
            mTracker = tracker;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_NEW_EVENT:
                    mTracker.handleNewEvent((String) msg.obj, msg.arg1);
                    break;
            }
        }
    }

    static class BaseAppStateTimeSlotEventsPolicy<E extends BaseAppStateTimeSlotEventsTracker>
            extends BaseAppStateEventsPolicy<E> {

        final String mKeyNumOfEventsThreshold;
        final int mDefaultNumOfEventsThreshold;

        @NonNull
        private final Object mLock;

        @GuardedBy("mLock")
        private final ProcessMap<Long> mExcessiveEventPkgs = new ProcessMap<>();

        long mTimeSlotSize = DEBUG_APP_STATE_TIME_SLOT_EVENT_TRACKER
                    ? SimpleAppStateTimeslotEvents.DEFAULT_TIME_SLOT_SIZE_DEBUG
                    : SimpleAppStateTimeslotEvents.DEFAULT_TIME_SLOT_SIZE;

        volatile int mNumOfEventsThreshold;

        BaseAppStateTimeSlotEventsPolicy(@NonNull Injector injector, @NonNull E tracker,
                @NonNull String keyTrackerEnabled, boolean defaultTrackerEnabled,
                @NonNull String keyMaxTrackingDuration, long defaultMaxTrackingDuration,
                @NonNull String keyNumOfEventsThreshold, int defaultNumOfEventsThreshold) {
            super(injector, tracker, keyTrackerEnabled, defaultTrackerEnabled,
                    keyMaxTrackingDuration, defaultMaxTrackingDuration);
            mKeyNumOfEventsThreshold = keyNumOfEventsThreshold;
            mDefaultNumOfEventsThreshold = defaultNumOfEventsThreshold;
            mLock = tracker.mLock;
        }

        @Override
        public void onSystemReady() {
            super.onSystemReady();
            updateNumOfEventsThreshold();
        }

        @Override
        public void onPropertiesChanged(String name) {
            if (mKeyNumOfEventsThreshold.equals(name)) {
                updateNumOfEventsThreshold();
            } else {
                super.onPropertiesChanged(name);
            }
        }

        @Override
        public void onTrackerEnabled(boolean enabled) {
            mTracker.onMonitorEnabled(enabled);
        }

        @Override
        public void onMaxTrackingDurationChanged(long maxDuration) {
            mTracker.mBgHandler.post(mTracker::trimEvents);
        }

        private void updateNumOfEventsThreshold() {
            final int threshold = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    mKeyNumOfEventsThreshold,
                    mDefaultNumOfEventsThreshold);
            if (threshold != mNumOfEventsThreshold) {
                mNumOfEventsThreshold = threshold;
                mTracker.onNumOfEventsThresholdChanged(threshold);
            }
        }

        int getNumOfEventsThreshold() {
            return mNumOfEventsThreshold;
        }

        long getTimeSlotSize() {
            return mTimeSlotSize;
        }

        @VisibleForTesting
        void setTimeSlotSize(long size) {
            mTimeSlotSize = size;
        }

        String getEventName() {
            return "event";
        }

        void onExcessiveEvents(String packageName, int uid, int numOfEvents, long now) {
            boolean notifyController = false;
            synchronized (mLock) {
                Long ts = mExcessiveEventPkgs.get(packageName, uid);
                if (ts == null) {
                    if (DEBUG_APP_STATE_TIME_SLOT_EVENT_TRACKER) {
                        Slog.i(TAG, "Excessive amount of " + getEventName() + " from "
                                + packageName + "/" + UserHandle.formatUid(uid) + ": " + numOfEvents
                                + " over " + TimeUtils.formatDuration(getMaxTrackingDuration()));
                    }
                    mExcessiveEventPkgs.put(packageName, uid, now);
                    notifyController = true;
                }
            }
            if (notifyController) {
                mTracker.mAppRestrictionController.refreshAppRestrictionLevelForUid(
                        uid, REASON_MAIN_FORCED_BY_SYSTEM,
                        REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE, true);
            }
        }

        /**
         * Whether or not we should ignore the incoming event.
         */
        @ReasonCode int shouldExempt(String packageName, int uid) {
            if (mTracker.isUidOnTop(uid)) {
                if (DEBUG_APP_STATE_TIME_SLOT_EVENT_TRACKER) {
                    Slog.i(TAG, "Ignoring event from " + packageName + "/"
                            + UserHandle.formatUid(uid) + ": top");
                }
                return REASON_PROC_STATE_TOP;
            }
            if (mTracker.mAppRestrictionController.hasForegroundServices(packageName, uid)) {
                if (DEBUG_APP_STATE_TIME_SLOT_EVENT_TRACKER) {
                    Slog.i(TAG, "Ignoring event " + packageName + "/"
                            + UserHandle.formatUid(uid) + ": has active FGS");
                }
                return REASON_PROC_STATE_FGS;
            }
            final @ReasonCode int reason = shouldExemptUid(uid);
            if (reason != REASON_DENIED) {
                if (DEBUG_APP_STATE_TIME_SLOT_EVENT_TRACKER) {
                    Slog.i(TAG, "Ignoring event " + packageName + "/" + UserHandle.formatUid(uid)
                            + ": " + reasonCodeToString(reason));
                }
                return reason;
            }
            return REASON_DENIED;
        }

        @Override
        @RestrictionLevel
        public int getProposedRestrictionLevel(String packageName, int uid,
                @RestrictionLevel int maxLevel) {
            synchronized (mLock) {
                final int level = mExcessiveEventPkgs.get(packageName, uid) == null
                        || !mTracker.mAppRestrictionController.isAutoRestrictAbusiveAppEnabled()
                        ? RESTRICTION_LEVEL_ADAPTIVE_BUCKET
                        : RESTRICTION_LEVEL_RESTRICTED_BUCKET;
                if (maxLevel > RESTRICTION_LEVEL_RESTRICTED_BUCKET) {
                    return level;
                } else if (maxLevel == RESTRICTION_LEVEL_RESTRICTED_BUCKET) {
                    return RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
                }
                return RESTRICTION_LEVEL_UNKNOWN;
            }
        }

        void onUserInteractionStarted(String packageName, int uid) {
            boolean notifyController = false;
            synchronized (mLock) {
                notifyController = mExcessiveEventPkgs.remove(packageName, uid) != null;
            }
            mTracker.mAppRestrictionController.refreshAppRestrictionLevelForUid(uid,
                    REASON_MAIN_USAGE, REASON_SUB_USAGE_USER_INTERACTION, true);
        }

        @Override
        void dump(PrintWriter pw, String prefix) {
            super.dump(pw, prefix);
            if (isEnabled()) {
                pw.print(prefix);
                pw.print(mKeyNumOfEventsThreshold);
                pw.print('=');
                pw.println(mDefaultNumOfEventsThreshold);
            }
            pw.print(prefix);
            pw.print("event_time_slot_size=");
            pw.println(getTimeSlotSize());
        }
    }

    /**
     * A simple time-slot based event table, with only one track of events.
     */
    static class SimpleAppStateTimeslotEvents extends BaseAppStateTimeSlotEvents {
        static final int DEFAULT_INDEX = 0;
        static final long DEFAULT_TIME_SLOT_SIZE = 15 * ONE_MINUTE;
        static final long DEFAULT_TIME_SLOT_SIZE_DEBUG = ONE_MINUTE;

        SimpleAppStateTimeslotEvents(int uid, @NonNull String packageName,
                long timeslotSize, @NonNull String tag,
                @NonNull MaxTrackingDurationConfig maxTrackingDurationConfig) {
            super(uid, packageName, 1, timeslotSize, tag, maxTrackingDurationConfig);
        }

        SimpleAppStateTimeslotEvents(SimpleAppStateTimeslotEvents other) {
            super(other);
        }

        @Override
        String formatEventTypeLabel(int index) {
            return "";
        }

        @Override
        String formatEventSummary(long now, int index) {
            if (mEvents[DEFAULT_INDEX] == null || mEvents[DEFAULT_INDEX].size() == 0) {
                return "(none)";
            }
            final int total = getTotalEvents(now, DEFAULT_INDEX);
            return "total=" + total + ", latest="
                    + getTotalEventsSince(mCurSlotStartTime[DEFAULT_INDEX], now, DEFAULT_INDEX)
                    + "(slot=" + TimeUtils.formatTime(mCurSlotStartTime[DEFAULT_INDEX], now) + ")";
        }
    }
}
