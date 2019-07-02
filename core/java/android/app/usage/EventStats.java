/**
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package android.app.usage;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Contains usage statistics for an event type for a specific
 * time range.
 */
public final class EventStats implements Parcelable {

    /**
     * {@hide}
     */
    public int mEventType;

    /**
     * {@hide}
     */
    public long mBeginTimeStamp;

    /**
     * {@hide}
     */
    public long mEndTimeStamp;

    /**
     * {@hide}
     */
    public long mLastEventTime;

    /**
     * {@hide}
     */
    public long mTotalTime;

    /**
     * {@hide}
     */
    public int mCount;

    /**
     * {@hide}
     */
    public EventStats() {
    }

    public EventStats(EventStats stats) {
        mEventType = stats.mEventType;
        mBeginTimeStamp = stats.mBeginTimeStamp;
        mEndTimeStamp = stats.mEndTimeStamp;
        mLastEventTime = stats.mLastEventTime;
        mTotalTime = stats.mTotalTime;
        mCount = stats.mCount;
    }

    /**
     * Return the type of event this is usage for.  May be one of the event
     * constants in {@link UsageEvents.Event}.
     */
    public int getEventType() {
        return mEventType;
    }

    /**
     * Get the beginning of the time range this {@link android.app.usage.EventStats} represents,
     * measured in milliseconds since the epoch.
     * <p/>
     * See {@link System#currentTimeMillis()}.
     */
    public long getFirstTimeStamp() {
        return mBeginTimeStamp;
    }

    /**
     * Get the end of the time range this {@link android.app.usage.EventStats} represents,
     * measured in milliseconds since the epoch.
     * <p/>
     * See {@link System#currentTimeMillis()}.
     */
    public long getLastTimeStamp() {
        return mEndTimeStamp;
    }

    /**
     * Get the last time this event triggered, measured in milliseconds since the epoch.
     * <p/>
     * See {@link System#currentTimeMillis()}.
     */
    public long getLastEventTime() {
        return mLastEventTime;
    }

    /**
     * Return the number of times that this event occurred over the interval.
     */
    public int getCount() {
        return mCount;
    }

    /**
     * Get the total time this event was active, measured in milliseconds.
     */
    public long getTotalTime() {
        return mTotalTime;
    }

    /**
     * Add the statistics from the right {@link EventStats} to the left. The event type for
     * both {@link UsageStats} objects must be the same.
     * @param right The {@link EventStats} object to merge into this one.
     * @throws java.lang.IllegalArgumentException if the event types of the two
     *         {@link UsageStats} objects are different.
     */
    public void add(EventStats right) {
        if (mEventType != right.mEventType) {
            throw new IllegalArgumentException("Can't merge EventStats for event #"
                    + mEventType + " with EventStats for event #" + right.mEventType);
        }

        // We use the mBeginTimeStamp due to a bug where UsageStats files can overlap with
        // regards to their mEndTimeStamp.
        if (right.mBeginTimeStamp > mBeginTimeStamp) {
            mLastEventTime = Math.max(mLastEventTime, right.mLastEventTime);
        }
        mBeginTimeStamp = Math.min(mBeginTimeStamp, right.mBeginTimeStamp);
        mEndTimeStamp = Math.max(mEndTimeStamp, right.mEndTimeStamp);
        mTotalTime += right.mTotalTime;
        mCount += right.mCount;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mEventType);
        dest.writeLong(mBeginTimeStamp);
        dest.writeLong(mEndTimeStamp);
        dest.writeLong(mLastEventTime);
        dest.writeLong(mTotalTime);
        dest.writeInt(mCount);
    }

    public static final @android.annotation.NonNull Creator<EventStats> CREATOR = new Creator<EventStats>() {
        @Override
        public EventStats createFromParcel(Parcel in) {
            EventStats stats = new EventStats();
            stats.mEventType = in.readInt();
            stats.mBeginTimeStamp = in.readLong();
            stats.mEndTimeStamp = in.readLong();
            stats.mLastEventTime = in.readLong();
            stats.mTotalTime = in.readLong();
            stats.mCount = in.readInt();
            return stats;
        }

        @Override
        public EventStats[] newArray(int size) {
            return new EventStats[size];
        }
    };
}
