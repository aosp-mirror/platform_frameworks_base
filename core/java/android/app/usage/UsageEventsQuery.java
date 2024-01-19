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
import android.annotation.UserIdInt;
import android.app.usage.UsageEvents.Event;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.util.ArraySet;

import com.android.internal.util.ArrayUtils;

import java.util.Arrays;

/**
 * An Object-Oriented representation for a {@link UsageEvents} query.
 * Used by {@link UsageStatsManager#queryEvents(UsageEventsQuery)} call.
 */
@FlaggedApi(Flags.FLAG_FILTER_BASED_EVENT_QUERY_API)
public final class UsageEventsQuery implements Parcelable {
    private final @CurrentTimeMillisLong long mBeginTimeMillis;
    private final @CurrentTimeMillisLong long mEndTimeMillis;
    private final @Event.EventType int[] mEventTypes;
    private final @UserIdInt int mUserId;

    private UsageEventsQuery(@NonNull Builder builder) {
        mBeginTimeMillis = builder.mBeginTimeMillis;
        mEndTimeMillis = builder.mEndTimeMillis;
        mEventTypes = ArrayUtils.convertToIntArray(builder.mEventTypes);
        mUserId = builder.mUserId;
    }

    private UsageEventsQuery(Parcel in) {
        mBeginTimeMillis = in.readLong();
        mEndTimeMillis = in.readLong();
        int eventTypesLength = in.readInt();
        mEventTypes = new int[eventTypesLength];
        in.readIntArray(mEventTypes);
        mUserId = in.readInt();
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
     * Retrieves the usage event types for the query.
     * <p>Note that an empty array indicates querying all usage event types, and it may
     * cause additional system overhead when calling
     * {@link UsageStatsManager#queryEvents(UsageEventsQuery)}. Apps are encouraged to
     * provide a list of event types via {@link Builder#setEventTypes(int...)}</p>
     *
     * @return an array contains the usage event types that was previously set using
     *         {@link Builder#setEventTypes(int...)} or an empty array if no value has been set.
     */
    public @NonNull @Event.EventType int[] getEventTypes() {
        return Arrays.copyOf(mEventTypes, mEventTypes.length);
    }

    /** @hide */
    public @UserIdInt int getUserId() {
        return mUserId;
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
        dest.writeInt(mUserId);
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

    /**
     * Builder for UsageEventsQuery.
     */
    public static final class Builder {
        private final @CurrentTimeMillisLong long mBeginTimeMillis;
        private final @CurrentTimeMillisLong long mEndTimeMillis;
        private final ArraySet<Integer> mEventTypes = new ArraySet<>();
        private @UserIdInt int mUserId = UserHandle.USER_NULL;

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
         * Sets the list of usage event types to be included in the query.
         *
         * <p>Note: </p> An empty array will be returned by
         * {@link UsageEventsQuery#getEventTypes()} without calling this method, which indicates
         * querying for all event types. Apps are encouraged to provide a list of event types.
         * Only the matching types supplied will be used to query.
         *
         * @param eventTypes the array of the usage event types. See {@link UsageEvents.Event}.
         * @throws NullPointerException if {@code eventTypes} is {@code null} or empty.
         * @throws IllegalArgumentException if any of event types are invalid.
         * @see UsageEventsQuery#getEventTypes()
         * @see UsageStatsManager#queryEvents(UsageEventsQuery)
         */
        public @NonNull Builder setEventTypes(@NonNull @Event.EventType int... eventTypes) {
            if (eventTypes == null || eventTypes.length == 0) {
                throw new NullPointerException("eventTypes is null or empty");
            }

            mEventTypes.clear();
            for (int i = 0; i < eventTypes.length; i++) {
                final int eventType = eventTypes[i];
                if (eventType < Event.NONE || eventType > Event.MAX_EVENT_TYPE) {
                    throw new IllegalArgumentException("Invalid usage event type: " + eventType);
                }
                mEventTypes.add(eventType);
            }
            return this;
        }

        /**
         * Specifices the user id for the query.
         * @param userId for whom the query should be performed.
         * @hide
         */
        public @NonNull Builder setUserId(@UserIdInt int userId) {
            mUserId = userId;
            return this;
        }
    }
}
