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

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.Context;
import android.os.PowerExemptionManager;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.am.BaseAppStateEvents.MaxTrackingDurationConfig;
import com.android.server.am.BaseAppStateEventsTracker.BaseAppStateEventsPolicy;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.util.LinkedList;

/**
 * Base class to track certain state event of apps.
 */
abstract class BaseAppStateEventsTracker
        <T extends BaseAppStateEventsPolicy, U extends BaseAppStateEvents>
        extends BaseAppStateTracker<T> implements BaseAppStateEvents.Factory<U> {
    static final boolean DEBUG_BASE_APP_STATE_EVENTS_TRACKER = false;

    @GuardedBy("mLock")
    final UidProcessMap<U> mPkgEvents = new UidProcessMap<>();

    @GuardedBy("mLock")
    final ArraySet<Integer> mTopUids = new ArraySet<>();

    BaseAppStateEventsTracker(Context context, AppRestrictionController controller,
            Constructor<? extends Injector<T>> injector, Object outerContext) {
        super(context, controller, injector, outerContext);
    }

    @VisibleForTesting
    void reset() {
        synchronized (mLock) {
            mPkgEvents.clear();
            mTopUids.clear();
        }
    }

    @GuardedBy("mLock")
    U getUidEventsLocked(int uid) {
        U events = null;
        final ArrayMap<String, U> map = mPkgEvents.getMap().get(uid);
        if (map == null) {
            return null;
        }
        for (int i = map.size() - 1; i >= 0; i--) {
            final U event = map.valueAt(i);
            if (event != null) {
                if (events == null) {
                    events = createAppStateEvents(uid, event.mPackageName);
                }
                events.add(event);
            }
        }
        return events;
    }

    void trim(long earliest) {
        synchronized (mLock) {
            trimLocked(earliest);
        }
    }

    @GuardedBy("mLock")
    void trimLocked(long earliest) {
        final SparseArray<ArrayMap<String, U>> map = mPkgEvents.getMap();
        for (int i = map.size() - 1; i >= 0; i--) {
            final ArrayMap<String, U> val = map.valueAt(i);
            for (int j = val.size() - 1; j >= 0; j--) {
                final U v = val.valueAt(j);
                v.trim(earliest);
                if (v.isEmpty()) {
                    val.removeAt(j);
                }
            }
            if (val.size() == 0) {
                map.removeAt(i);
            }
        }
    }

    boolean isUidOnTop(int uid) {
        synchronized (mLock) {
            return mTopUids.contains(uid);
        }
    }

    @GuardedBy("mLock")
    void onUntrackingUidLocked(int uid) {
    }

    @Override
    void onUidProcStateChanged(final int uid, final int procState) {
        synchronized (mLock) {
            if (mPkgEvents.getMap().indexOfKey(uid) < 0) {
                // If we're not tracking its events, ignore its UID state changes.
                return;
            }
            onUidProcStateChangedUncheckedLocked(uid, procState);
        }
    }

    @GuardedBy("mLock")
    void onUidProcStateChangedUncheckedLocked(final int uid, final int procState) {
        if (procState < ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE) {
            mTopUids.add(uid);
        } else {
            mTopUids.remove(uid);
        }
    }

    @Override
    void onUidGone(final int uid) {
        synchronized (mLock) {
            mTopUids.remove(uid);
        }
    }

    @Override
    void onUidRemoved(final int uid) {
        synchronized (mLock) {
            mPkgEvents.getMap().remove(uid);
            onUntrackingUidLocked(uid);
        }
    }

    @Override
    void onUserRemoved(final @UserIdInt int userId) {
        synchronized (mLock) {
            final SparseArray<ArrayMap<String, U>> map = mPkgEvents.getMap();
            for (int i = map.size() - 1; i >= 0; i--) {
                final int uid = map.keyAt(i);
                if (UserHandle.getUserId(uid) == userId) {
                    map.removeAt(i);
                    onUntrackingUidLocked(uid);
                }
            }
        }
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        final T policy = mInjector.getPolicy();
        synchronized (mLock) {
            final long now = SystemClock.elapsedRealtime();
            final SparseArray<ArrayMap<String, U>> map = mPkgEvents.getMap();
            for (int i = map.size() - 1; i >= 0; i--) {
                final int uid = map.keyAt(i);
                final ArrayMap<String, U> val = map.valueAt(i);
                for (int j = val.size() - 1; j >= 0; j--) {
                    final String packageName = val.keyAt(j);
                    final U events = val.valueAt(j);
                    dumpEventHeaderLocked(pw, prefix, packageName, uid, events, policy);
                    dumpEventLocked(pw, prefix, events, now);
                }
            }
        }
        dumpOthers(pw, prefix);
        policy.dump(pw, prefix);
    }

    void dumpOthers(PrintWriter pw, String prefix) {
    }

    @GuardedBy("mLock")
    void dumpEventHeaderLocked(PrintWriter pw, String prefix, String packageName, int uid, U events,
            T policy) {
        pw.print(prefix);
        pw.print("* ");
        pw.print(packageName);
        pw.print('/');
        pw.print(UserHandle.formatUid(uid));
        pw.print(" exemption=");
        pw.println(policy.getExemptionReasonString(packageName, uid, events.mExemptReason));
    }

    @GuardedBy("mLock")
    void dumpEventLocked(PrintWriter pw, String prefix, U events, long now) {
        events.dump(pw, "  " + prefix, now);
    }

    abstract static class BaseAppStateEventsPolicy<V extends BaseAppStateEventsTracker>
            extends BaseAppStatePolicy<V> implements MaxTrackingDurationConfig {
        /**
         * The key to the maximum duration we'd keep tracking, events earlier than that
         * will be discarded.
         */
        final @NonNull String mKeyMaxTrackingDuration;

        /**
         * The default to the {@link #mMaxTrackingDuration}.
         */
        final long mDefaultMaxTrackingDuration;

        /**
         * The maximum duration we'd keep tracking, events earlier than that will be discarded.
         */
        volatile long mMaxTrackingDuration;

        BaseAppStateEventsPolicy(@NonNull Injector<?> injector, @NonNull V tracker,
                @NonNull String keyTrackerEnabled, boolean defaultTrackerEnabled,
                @NonNull String keyMaxTrackingDuration, long defaultMaxTrackingDuration) {
            super(injector, tracker, keyTrackerEnabled, defaultTrackerEnabled);
            mKeyMaxTrackingDuration = keyMaxTrackingDuration;
            mDefaultMaxTrackingDuration = defaultMaxTrackingDuration;
        }

        @Override
        public void onPropertiesChanged(String name) {
            if (mKeyMaxTrackingDuration.equals(name)) {
                updateMaxTrackingDuration();
            } else {
                super.onPropertiesChanged(name);
            }
        }

        @Override
        public void onSystemReady() {
            super.onSystemReady();
            updateMaxTrackingDuration();
        }

        /**
         * Called when the maximum duration we'd keep tracking has been changed.
         */
        public abstract void onMaxTrackingDurationChanged(long maxDuration);

        void updateMaxTrackingDuration() {
            long max = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    mKeyMaxTrackingDuration, mDefaultMaxTrackingDuration);
            if (max != mMaxTrackingDuration) {
                mMaxTrackingDuration = max;
                onMaxTrackingDurationChanged(max);
            }
        }

        @Override
        public long getMaxTrackingDuration() {
            return mMaxTrackingDuration;
        }

        String getExemptionReasonString(String packageName, int uid, @ReasonCode int reason) {
            return PowerExemptionManager.reasonCodeToString(reason);
        }

        @Override
        void dump(PrintWriter pw, String prefix) {
            super.dump(pw, prefix);
            if (isEnabled()) {
                pw.print(prefix);
                pw.print(mKeyMaxTrackingDuration);
                pw.print('=');
                pw.println(mMaxTrackingDuration);
            }
        }
    }

    /**
     * Simple event table, with only one track of events.
     */
    static class SimplePackageEvents extends BaseAppStateTimeEvents {
        static final int DEFAULT_INDEX = 0;

        SimplePackageEvents(int uid, String packageName,
                MaxTrackingDurationConfig maxTrackingDurationConfig) {
            super(uid, packageName, 1, TAG, maxTrackingDurationConfig);
            mEvents[DEFAULT_INDEX] = new LinkedList<Long>();
        }

        long getTotalEvents(long now) {
            return getTotalEvents(now, DEFAULT_INDEX);
        }

        long getTotalEventsSince(long since, long now) {
            return getTotalEventsSince(since, now, DEFAULT_INDEX);
        }

        @Override
        String formatEventTypeLabel(int index) {
            return "";
        }
    }
}
