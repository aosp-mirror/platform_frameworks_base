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
import android.text.TextUtils;
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
    private final @UserIdInt int mUserId;
    private final String[] mPackageNames;

    private UsageEventsQuery(@NonNull Builder builder) {
        mBeginTimeMillis = builder.mBeginTimeMillis;
        mEndTimeMillis = builder.mEndTimeMillis;
        mEventTypes = ArrayUtils.convertToIntArray(builder.mEventTypes);
        mUserId = builder.mUserId;
        mPackageNames = builder.mPackageNames.toArray(new String[builder.mPackageNames.size()]);
    }

    private UsageEventsQuery(Parcel in) {
        mBeginTimeMillis = in.readLong();
        mEndTimeMillis = in.readLong();
        int eventTypesLength = in.readInt();
        mEventTypes = new int[eventTypesLength];
        in.readIntArray(mEventTypes);
        mUserId = in.readInt();
        int packageNamesLength = in.readInt();
        mPackageNames = new String[packageNamesLength];
        in.readStringArray(mPackageNames);
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

    /**
     * Retrieves a {@code Set} of package names for the query.
     * <p>Note that an empty set indicates querying usage events for all packages, and
     * it may cause additional system overhead when calling
     * {@link UsageStatsManager#queryEvents(UsageEventsQuery)}. Apps are encouraged to
     * provide a list of package names via {@link Builder#setPackageNames(String...)}</p>
     *
     * @return a {@code Set} contains the package names that was previously set through
     *         {@link Builder#setPackageNames(String...)} or an empty set if no value has been set.
     */
    public @NonNull Set<String> getPackageNames() {
        if (ArrayUtils.isEmpty(mPackageNames)) {
            return Collections.emptySet();
        }

        final HashSet<String> pkgNameSet = new HashSet<>();
        for (String pkgName: mPackageNames) {
            pkgNameSet.add(pkgName);
        }
        return pkgNameSet;
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
        dest.writeInt(mPackageNames.length);
        dest.writeStringArray(mPackageNames);
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
        private final ArraySet<String> mPackageNames = new ArraySet<>();

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

        /**
         * Sets the list of package names to be included in the query.
         *
         * <p>Note: </p> An empty {@code Set} will be returned by
         * {@link UsageEventsQuery#getPackageNames()} without calling this method, which indicates
         * querying usage events for all packages. Apps are encouraged to provide a list of package
         * names. Only the matching names supplied will be used to query.
         *
         * @param pkgNames the array of the package names, each package name should be a non-empty
         *                 string, {@code null} or empty string("") is omitted.
         * @see UsageEventsQuery#getPackageNames()
         * @see UsageStatsManager#queryEvents(UsageEventsQuery)
         * @throws NullPointerException if {@code pkgNames} is {@code null} or empty.
         */
        public @NonNull Builder setPackageNames(@NonNull String... pkgNames) {
            if (pkgNames == null || pkgNames.length == 0) {
                throw new NullPointerException("pkgNames is null or empty");
            }
            mPackageNames.clear();
            for (int i = 0; i < pkgNames.length; i++) {
                if (!TextUtils.isEmpty(pkgNames[i])) {
                    mPackageNames.add(pkgNames[i]);
                }
            }

            return this;
        }
    }
}
