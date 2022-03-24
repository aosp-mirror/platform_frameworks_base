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

import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;

import android.content.Context;
import android.os.SystemClock;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.am.BaseAppStateEvents.MaxTrackingDurationConfig;
import com.android.server.am.BaseAppStateEventsTracker.BaseAppStateEventsPolicy;
import com.android.server.am.BaseAppStateTimeEvents.BaseTimeEvent;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.util.LinkedList;

/**
 * Base class to track certain binary state event of apps.
 */
abstract class BaseAppStateDurationsTracker
        <T extends BaseAppStateEventsPolicy, U extends BaseAppStateDurations>
        extends BaseAppStateEventsTracker<T, U> {
    static final boolean DEBUG_BASE_APP_STATE_DURATION_TRACKER = false;

    @GuardedBy("mLock")
    final SparseArray<UidStateDurations> mUidStateDurations = new SparseArray<>();

    BaseAppStateDurationsTracker(Context context, AppRestrictionController controller,
            Constructor<? extends Injector<T>> injector, Object outerContext) {
        super(context, controller, injector, outerContext);
    }

    @Override
    void onUidProcStateChanged(final int uid, final int procState) {
        synchronized (mLock) {
            if (mPkgEvents.getMap().indexOfKey(uid) < 0) {
                // If we're not tracking its events, ignore its UID state changes.
                return;
            }
            onUidProcStateChangedUncheckedLocked(uid, procState);
            UidStateDurations uidStateDurations = mUidStateDurations.get(uid);
            if (uidStateDurations == null) {
                uidStateDurations = new UidStateDurations(uid, mInjector.getPolicy());
                mUidStateDurations.put(uid, uidStateDurations);
            }
            uidStateDurations.addEvent(procState < PROCESS_STATE_FOREGROUND_SERVICE,
                    SystemClock.elapsedRealtime());
        }
    }

    @Override
    void onUidGone(final int uid) {
        onUidProcStateChanged(uid, PROCESS_STATE_NONEXISTENT);
    }

    @Override
    @GuardedBy("mLock")
    void trimLocked(long earliest) {
        super.trimLocked(earliest);
        for (int i = mUidStateDurations.size() - 1; i >= 0; i--) {
            final UidStateDurations u = mUidStateDurations.valueAt(i);
            u.trim(earliest);
            if (u.isEmpty()) {
                mUidStateDurations.removeAt(i);
            }
        }
    }

    @Override
    @GuardedBy("mLock")
    void onUntrackingUidLocked(int uid) {
        mUidStateDurations.remove(uid);
    }

    long getTotalDurations(String packageName, int uid, long now, int index, boolean bgOnly) {
        synchronized (mLock) {
            final U durations = mPkgEvents.get(uid, packageName);
            if (durations == null) {
                return 0;
            }
            if (bgOnly) {
                final UidStateDurations uidDurations = mUidStateDurations.get(uid);
                if (uidDurations != null && !uidDurations.isEmpty()) {
                    final U res = createAppStateEvents(durations);
                    res.subtract(uidDurations, index, UidStateDurations.DEFAULT_INDEX);
                    return res.getTotalDurations(now, index);
                }
            }
            return durations.getTotalDurations(now, index);
        }
    }

    long getTotalDurations(String packageName, int uid, long now, int index) {
        return getTotalDurations(packageName, uid, now, index, true /* bgOnly */);
    }

    long getTotalDurations(String packageName, int uid, long now) {
        return getTotalDurations(packageName, uid, now, SimplePackageDurations.DEFAULT_INDEX);
    }

    long getTotalDurations(int uid, long now, int index, boolean bgOnly) {
        synchronized (mLock) {
            final U durations = getUidEventsLocked(uid);
            if (durations == null) {
                return 0;
            }
            if (bgOnly) {
                final UidStateDurations uidDurations = mUidStateDurations.get(uid);
                if (uidDurations != null && !uidDurations.isEmpty()) {
                    durations.subtract(uidDurations, index, UidStateDurations.DEFAULT_INDEX);
                }
            }
            return durations.getTotalDurations(now, index);
        }
    }

    long getTotalDurations(int uid, long now, int index) {
        return getTotalDurations(uid, now, index, true /* bgOnly */);
    }

    long getTotalDurations(int uid, long now) {
        return getTotalDurations(uid, now, SimplePackageDurations.DEFAULT_INDEX);
    }

    long getTotalDurationsSince(String packageName, int uid, long since, long now, int index,
            boolean bgOnly) {
        synchronized (mLock) {
            final U durations = mPkgEvents.get(uid, packageName);
            if (durations == null) {
                return 0;
            }
            if (bgOnly) {
                final UidStateDurations uidDurations = mUidStateDurations.get(uid);
                if (uidDurations != null && !uidDurations.isEmpty()) {
                    final U res = createAppStateEvents(durations);
                    res.subtract(uidDurations, index, UidStateDurations.DEFAULT_INDEX);
                    return res.getTotalDurationsSince(since, now, index);
                }
            }
            return durations.getTotalDurationsSince(since, now, index);
        }
    }

    long getTotalDurationsSince(String packageName, int uid, long since, long now, int index) {
        return getTotalDurationsSince(packageName, uid, since, now, index, true /* bgOnly */);
    }

    long getTotalDurationsSince(String packageName, int uid, long since, long now) {
        return getTotalDurationsSince(packageName, uid, since, now,
                SimplePackageDurations.DEFAULT_INDEX);
    }

    long getTotalDurationsSince(int uid, long since, long now, int index, boolean bgOnly) {
        synchronized (mLock) {
            final U durations = getUidEventsLocked(uid);
            if (durations == null) {
                return 0;
            }
            if (bgOnly) {
                final UidStateDurations uidDurations = mUidStateDurations.get(uid);
                if (uidDurations != null && !uidDurations.isEmpty()) {
                    durations.subtract(uidDurations, index, UidStateDurations.DEFAULT_INDEX);
                }
            }
            return durations.getTotalDurationsSince(since, now, index);
        }
    }

    long getTotalDurationsSince(int uid, long since, long now, int index) {
        return getTotalDurationsSince(uid, since, now, index, true /* bgOnly */);
    }

    long getTotalDurationsSince(int uid, long since, long now) {
        return getTotalDurationsSince(uid, since, now, SimplePackageDurations.DEFAULT_INDEX);
    }

    @VisibleForTesting
    @Override
    void reset() {
        super.reset();
        synchronized (mLock) {
            mUidStateDurations.clear();
        }
    }

    @Override
    @GuardedBy("mLock")
    void dumpEventLocked(PrintWriter pw, String prefix, U events, long now) {
        final UidStateDurations uidDurations = mUidStateDurations.get(events.mUid);
        pw.print("  " + prefix);
        pw.println("(bg only)");
        if (uidDurations == null || uidDurations.isEmpty()) {
            events.dump(pw, "    " + prefix, now);
            return;
        }
        final U bgEvents = createAppStateEvents(events);
        bgEvents.subtract(uidDurations, SimplePackageDurations.DEFAULT_INDEX);
        bgEvents.dump(pw, "    " + prefix, now);
        pw.print("  " + prefix);
        pw.println("(fg + bg)");
        events.dump(pw, "    " + prefix, now);
    }

    /**
     * Simple duration table, with only one track of durations.
     */
    static class SimplePackageDurations extends BaseAppStateDurations<BaseTimeEvent> {
        static final int DEFAULT_INDEX = 0;

        SimplePackageDurations(int uid, String packageName,
                MaxTrackingDurationConfig maxTrackingDurationConfig) {
            super(uid, packageName, 1, TAG, maxTrackingDurationConfig);
            mEvents[DEFAULT_INDEX] = new LinkedList<BaseTimeEvent>();
        }

        SimplePackageDurations(SimplePackageDurations other) {
            super(other);
        }

        void addEvent(boolean active, long now) {
            addEvent(active, new BaseTimeEvent(now), DEFAULT_INDEX);
        }

        long getTotalDurations(long now) {
            return getTotalDurations(now, DEFAULT_INDEX);
        }

        long getTotalDurationsSince(long since, long now) {
            return getTotalDurationsSince(since, now, DEFAULT_INDEX);
        }

        boolean isActive() {
            return isActive(DEFAULT_INDEX);
        }

        @Override
        String formatEventTypeLabel(int index) {
            return "";
        }
    }

    static class UidStateDurations extends SimplePackageDurations {
        UidStateDurations(int uid, MaxTrackingDurationConfig maxTrackingDurationConfig) {
            super(uid, "", maxTrackingDurationConfig);
        }

        UidStateDurations(UidStateDurations other) {
            super(other);
        }
    }
}
