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

import static android.os.PowerExemptionManager.REASON_DENIED;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.LinkedList;

/**
 * A helper class to track the occurrences of certain events.
 */
abstract class BaseAppStateEvents<E> {
    static final boolean DEBUG_BASE_APP_STATE_EVENTS = false;
    final int mUid;
    final @NonNull String mPackageName;
    final @NonNull String mTag;
    final @NonNull MaxTrackingDurationConfig mMaxTrackingDurationConfig;

    /**
     * The events we're tracking.
     *
     * <p>
     * The meaning of the events is up to the derived classes, i.e., it could be a series of
     * individual events, or a series of event pairs (i.e., start/stop event). The implementations
     * of {@link #add}, {@link #trim} etc. in this class are based on the individual events.
     * </p>
     */
    final LinkedList<E>[] mEvents;

    /**
     * In case the data we're tracking here is ignored, here is why.
     */
    @ReasonCode int mExemptReason = REASON_DENIED;

    BaseAppStateEvents(int uid, @NonNull String packageName, int numOfEventTypes,
            @NonNull String tag, @NonNull MaxTrackingDurationConfig maxTrackingDurationConfig) {
        mUid = uid;
        mPackageName = packageName;
        mTag = tag;
        mMaxTrackingDurationConfig = maxTrackingDurationConfig;
        mEvents = new LinkedList[numOfEventTypes];
    }

    BaseAppStateEvents(@NonNull BaseAppStateEvents other) {
        mUid = other.mUid;
        mPackageName = other.mPackageName;
        mTag = other.mTag;
        mMaxTrackingDurationConfig = other.mMaxTrackingDurationConfig;
        mEvents = new LinkedList[other.mEvents.length];
        for (int i = 0; i < mEvents.length; i++) {
            if (other.mEvents[i] != null) {
                mEvents[i] = new LinkedList<E>(other.mEvents[i]);
            }
        }
    }

    /**
     * Add an individual event.
     */
    void addEvent(E event, long now, int index) {
        if (mEvents[index] == null) {
            mEvents[index] = new LinkedList<E>();
        }
        final LinkedList<E> events = mEvents[index];
        events.add(event);
        trimEvents(getEarliest(now), index);
    }

    /**
     * Remove/trim earlier events with start time older than the given timestamp.
     */
    void trim(long earliest) {
        for (int i = 0; i < mEvents.length; i++) {
            trimEvents(earliest, i);
        }
    }

    /**
     * Remove/trim earlier events with start time older than the given timestamp.
     */
    abstract void trimEvents(long earliest, int index);

    /**
     * @return {@code true} if there is no events being tracked.
     */
    boolean isEmpty() {
        for (int i = 0; i < mEvents.length; i++) {
            if (mEvents[i] != null && !mEvents[i].isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return {@code true} if there is no events being tracked.
     */
    boolean isEmpty(int index) {
        return mEvents[index] == null || mEvents[index].isEmpty();
    }

    /**
     * Merge the events table from another instance.
     */
    void add(BaseAppStateEvents other) {
        if (mEvents.length != other.mEvents.length) {
            if (DEBUG_BASE_APP_STATE_EVENTS) {
                Slog.wtf(mTag, "Incompatible event table this=" + this + ", other=" + other);
            }
            return;
        }
        for (int i = 0; i < mEvents.length; i++) {
            mEvents[i] = add(mEvents[i], other.mEvents[i]);
        }
    }

    @VisibleForTesting
    LinkedList<E> getRawEvents(int index) {
        return mEvents[index];
    }

    /**
     * Merge the two given events table and return the result.
     */
    abstract LinkedList<E> add(LinkedList<E> events, LinkedList<E> otherEvents);

    /**
     * The number of events since the given time.
     */
    abstract int getTotalEventsSince(long since, long now, int index);

    /**
     * The total number of events we are tracking.
     */
    int getTotalEvents(long now, int index) {
        return getTotalEventsSince(getEarliest(0), now, index);
    }

    /**
     * @return The earliest possible time we're tracking with given timestamp.
     */
    long getEarliest(long now) {
        return Math.max(0, now - mMaxTrackingDurationConfig.getMaxTrackingDuration());
    }

    void dump(PrintWriter pw, String prefix, @ElapsedRealtimeLong long nowElapsed) {
        for (int i = 0; i < mEvents.length; i++) {
            if (mEvents[i] == null) {
                continue;
            }
            pw.print(prefix);
            pw.print(formatEventTypeLabel(i));
            pw.println(formatEventSummary(nowElapsed, i));
        }
    }

    String formatEventSummary(long now, int index) {
        return Integer.toString(getTotalEvents(now, index));
    }

    String formatEventTypeLabel(int index) {
        return Integer.toString(index) + ":";
    }

    @Override
    public String toString() {
        return mPackageName + "/" + UserHandle.formatUid(mUid)
                + " totalEvents[0]=" + formatEventSummary(SystemClock.elapsedRealtime(), 0);
    }

    interface Factory<T extends BaseAppStateEvents> {
        T createAppStateEvents(int uid, String packageName);
        T createAppStateEvents(T other);
    }

    interface MaxTrackingDurationConfig {
        /**
         * @return The mximum duration we'd keep tracking.
         */
        long getMaxTrackingDuration();
    }
}
