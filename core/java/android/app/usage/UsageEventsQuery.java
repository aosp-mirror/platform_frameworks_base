/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.usage;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.app.usage.UsageEvents.Event;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import com.android.internal.util.ArrayUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * An Object-Oriented representation for a {@link UsageEvents} query.
 * Used by {@link UsageStatsManager#queryEvents(UsageEventsQuery)} call.
 */
@FlaggedApi(Flags.FLAG_FILTER_BASED_EVENT_QUERY_API)
public final class UsageEventsQuery implements Parcelable {
    private final @CurrentTimeMillisLong long mBeginTimeMillis;
    private final @CurrentTimeMillisLong long mEndTimeMillis;
    private final @Event.EventType int[] mEventTypes;

    private UsageEventsQuery(@NonNull Builder builder) {
        mBeginTimeMillis = builder.mBeginTimeMillis;
        mEndTimeMillis = builder.mEndTimeMillis;
        mEventTypes = ArrayUtils.convertToIntArray(builder.mEventTypes);
    }

    private UsageEventsQuery(Parcel in) {
        mBeginTimeMillis = in.readLong();
        mEndTimeMillis = in.readLong();
        int eventTypesLength = in.readInt();
        mEventTypes = new int[eventTypesLength];
        in.readIntArray(mEventTypes);
    }

    /**
     * Returns the inclusive timestamp to indicate the beginning of the range of events.
     * Defined in terms of "Unix time", see {@link java.lang.System#currentTimeMillis}.
     */
    public @CurrentTimeMillisLong long getBeginTimeMillis() {
        return mBeginTimeMillis;
    }

    /**
     * Returns the exclusive timpstamp to indicate the end of the range of events.
     * Defined in terms of "Unix time", see {@link java.lang.System#currentTimeMillis}.
     */
    public @CurrentTimeMillisLong long getEndTimeMillis() {
        return mEndTimeMillis;
    }

    /**
     * Returns the set of usage event types for the query.
     * <em>Note: An empty set indicates query for all usage events. </em>
     */
    public @NonNull Set<Integer> getEventTypes() {
        if (ArrayUtils.isEmpty(mEventTypes)) {
            return Collections.emptySet();
        }

        HashSet<Integer> eventTypeSet = new HashSet<>();
        for (int eventType : mEventTypes) {
            eventTypeSet.add(eventType);
        }
        return eventTypeSet;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mBeginTimeMillis);
        dest.writeLong(mEndTimeMillis);
        dest.writeInt(mEventTypes.length);
        dest.writeIntArray(mEventTypes);
    }

    @NonNull
    public static final Creator<UsageEventsQuery> CREATOR =
            new Creator<UsageEventsQuery>() {
                @Override
                public UsageEventsQuery createFromParcel(Parcel in) {
                    return new UsageEventsQuery(in);
                }

                @Override
                public UsageEventsQuery[] newArray(int size) {
                    return new UsageEventsQuery[size];
                }
            };

    /** @hide */
    public int[] getEventTypeFilter() {
        return Arrays.copyOf(mEventTypes, mEventTypes.length);
    }

    /**
     * Builder for UsageEventsQuery.
     */
    public static final class Builder {
        private final @CurrentTimeMillisLong long mBeginTimeMillis;
        private final @CurrentTimeMillisLong long mEndTimeMillis;
        private final ArraySet<Integer> mEventTypes = new ArraySet<>();

        /**
         * Constructor that specifies the period for which to return events.
         * @param beginTimeMillis Inclusive beginning timestamp, as per
         *                        {@link java.lang.System#currentTimeMillis()}
         * @param endTimeMillis Exclusive ending timestamp, as per
         *                        {@link java.lang.System#currentTimeMillis()}
         *
         * @throws IllegalArgumentException if {@code beginTimeMillis} &lt;
         *                                  {@code endTimeMillis}
         */
        public Builder(@CurrentTimeMillisLong long beginTimeMillis,
                @CurrentTimeMillisLong long endTimeMillis) {
            if (beginTimeMillis < 0 || endTimeMillis < beginTimeMillis) {
                throw new IllegalArgumentException("Invalid period");
            }
            mBeginTimeMillis = beginTimeMillis;
            mEndTimeMillis = endTimeMillis;
        }

        /**
         * Builds a read-only UsageEventsQuery object.
         */
        public @NonNull UsageEventsQuery build() {
            return new UsageEventsQuery(this);
        }

        /**
         * Specifies the list of usage event types to be included in the query.
         * @param eventTypes List of the usage event types. See {@link UsageEvents.Event}
         *
         * @throws llegalArgumentException if the event type is not valid.
         */
        public @NonNull Builder addEventTypes(@NonNull @Event.EventType int... eventTypes) {
            for (int i = 0; i < eventTypes.length; i++) {
                final int eventType = eventTypes[i];
                if (eventType < Event.NONE || eventType > Event.MAX_EVENT_TYPE) {
                    throw new IllegalArgumentException("Invalid usage event type: " + eventType);
                }
                mEventTypes.add(eventType);
            }
            return this;
        }
    }
}
