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

package android.telephony.ims;

import static android.provider.Telephony.RcsColumns.RcsEventTypes.ICON_CHANGED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.NAME_CHANGED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.PARTICIPANT_ALIAS_CHANGED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.PARTICIPANT_JOINED_EVENT_TYPE;
import static android.provider.Telephony.RcsColumns.RcsEventTypes.PARTICIPANT_LEFT_EVENT_TYPE;

import android.annotation.CheckResult;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.InvalidParameterException;

/**
 * The parameters to pass into
 * {@link RcsMessageStore#getRcsEvents(RcsEventQueryParams)} in order to select a
 * subset of {@link RcsEvent}s present in the message store.
 *
 * @hide
 */
public final class RcsEventQueryParams implements Parcelable {
    /**
     * Flag to be used with {@link Builder#setEventType(int)} to make
     * {@link RcsMessageStore#getRcsEvents(RcsEventQueryParams)} return all types of
     * {@link RcsEvent}s
     */
    public static final int ALL_EVENTS = -1;

    /**
     * Flag to be used with {@link Builder#setEventType(int)} to make
     * {@link RcsMessageStore#getRcsEvents(RcsEventQueryParams)} return sub-types of
     * {@link RcsGroupThreadEvent}s
     */
    public static final int ALL_GROUP_THREAD_EVENTS = 0;

    /**
     * Flag to be used with {@link Builder#setEventType(int)} to make
     * {@link RcsMessageStore#getRcsEvents(RcsEventQueryParams)} return only
     * {@link RcsParticipantAliasChangedEvent}s
     */
    public static final int PARTICIPANT_ALIAS_CHANGED_EVENT =
            PARTICIPANT_ALIAS_CHANGED_EVENT_TYPE;

    /**
     * Flag to be used with {@link Builder#setEventType(int)} to make
     * {@link RcsMessageStore#getRcsEvents(RcsEventQueryParams)} return only
     * {@link RcsGroupThreadParticipantJoinedEvent}s
     */
    public static final int GROUP_THREAD_PARTICIPANT_JOINED_EVENT =
            PARTICIPANT_JOINED_EVENT_TYPE;

    /**
     * Flag to be used with {@link Builder#setEventType(int)} to make
     * {@link RcsMessageStore#getRcsEvents(RcsEventQueryParams)} return only
     * {@link RcsGroupThreadParticipantLeftEvent}s
     */
    public static final int GROUP_THREAD_PARTICIPANT_LEFT_EVENT =
            PARTICIPANT_LEFT_EVENT_TYPE;

    /**
     * Flag to be used with {@link Builder#setEventType(int)} to make
     * {@link RcsMessageStore#getRcsEvents(RcsEventQueryParams)} return only
     * {@link RcsGroupThreadNameChangedEvent}s
     */
    public static final int GROUP_THREAD_NAME_CHANGED_EVENT = NAME_CHANGED_EVENT_TYPE;

    /**
     * Flag to be used with {@link Builder#setEventType(int)} to make
     * {@link RcsMessageStore#getRcsEvents(RcsEventQueryParams)} return only
     * {@link RcsGroupThreadIconChangedEvent}s
     */
    public static final int GROUP_THREAD_ICON_CHANGED_EVENT = ICON_CHANGED_EVENT_TYPE;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ALL_EVENTS, ALL_GROUP_THREAD_EVENTS, PARTICIPANT_ALIAS_CHANGED_EVENT,
            GROUP_THREAD_PARTICIPANT_JOINED_EVENT, GROUP_THREAD_PARTICIPANT_LEFT_EVENT,
            GROUP_THREAD_NAME_CHANGED_EVENT, GROUP_THREAD_ICON_CHANGED_EVENT})
    public @interface EventType {
    }

    /**
     * Flag to be used with {@link Builder#setSortProperty(int)} that makes the result set sorted
     * in the order of creation for faster query results.
     */
    public static final int SORT_BY_CREATION_ORDER = 0;

    /**
     * Flag to be used with {@link Builder#setSortProperty(int)} that makes the result set sorted
     * with respect to {@link RcsEvent#getTimestamp()}
     */
    public static final int SORT_BY_TIMESTAMP = 1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SORT_BY_CREATION_ORDER, SORT_BY_TIMESTAMP})
    public @interface SortingProperty {
    }

    /**
     * The key to pass into a Bundle, for usage in RcsProvider.query(Bundle)
     * @hide - not meant for public use
     */
    public static final String EVENT_QUERY_PARAMETERS_KEY = "event_query_parameters";

    // Which types of events the results should be limited to
    private @EventType int mEventType;
    // The property which the results should be sorted against
    private int mSortingProperty;
    // Whether the results should be sorted in ascending order
    private boolean mIsAscending;
    // The number of results that should be returned with this query
    private int mLimit;
    // The thread that the results are limited to
    private int mThreadId;

    RcsEventQueryParams(@EventType int eventType, int threadId,
            @SortingProperty int sortingProperty, boolean isAscending, int limit) {
        mEventType = eventType;
        mSortingProperty = sortingProperty;
        mIsAscending = isAscending;
        mLimit = limit;
        mThreadId = threadId;
    }

    /**
     * @return Returns the type of {@link RcsEvent}s that this {@link RcsEventQueryParams} is
     * set to query for.
     */
    public @EventType int getEventType() {
        return mEventType;
    }

    /**
     * @return Returns the number of {@link RcsEvent}s to be returned from the query. A value of
     * 0 means there is no set limit.
     */
    public int getLimit() {
        return mLimit;
    }

    /**
     * @return Returns the property where the results should be sorted against.
     * @see SortingProperty
     */
    public int getSortingProperty() {
        return mSortingProperty;
    }

    /**
     * @return Returns {@code true} if the result set will be sorted in ascending order,
     * {@code false} if it will be sorted in descending order.
     */
    public boolean getSortDirection() {
        return mIsAscending;
    }

    /**
     * @return Returns the ID of the {@link RcsGroupThread} that the results are limited to. As this
     * API exposes an ID, it should stay hidden.
     *
     * @hide
     */
    public int getThreadId() {
        return mThreadId;
    }

    /**
     * A helper class to build the {@link RcsEventQueryParams}.
     */
    public static class Builder {
        private @EventType int mEventType;
        private @SortingProperty int mSortingProperty;
        private boolean mIsAscending;
        private int mLimit = 100;
        private int mThreadId;

        /**
         * Creates a new builder for {@link RcsEventQueryParams} to be used in
         * {@link RcsMessageStore#getRcsEvents(RcsEventQueryParams)}
         */
        public Builder() {
            // empty implementation
        }

        /**
         * Desired number of events to be returned from the query. Passing in 0 will return all
         * existing events at once. The limit defaults to 100.
         *
         * @param limit The number to limit the query result to.
         * @return The same instance of the builder to chain parameters.
         * @throws InvalidParameterException If the given limit is negative.
         */
        @CheckResult
        public Builder setResultLimit(@IntRange(from = 0) int limit)
                throws InvalidParameterException {
            if (limit < 0) {
                throw new InvalidParameterException("The query limit must be non-negative");
            }

            mLimit = limit;
            return this;
        }

        /**
         * Sets the type of events to be returned from the query.
         *
         * @param eventType The type of event to be returned.
         * @return The same instance of the builder to chain parameters.
         */
        @CheckResult
        public Builder setEventType(@EventType int eventType) {
            mEventType = eventType;
            return this;
        }

        /**
         * Sets the property where the results should be sorted against. Defaults to
         * {@link RcsEventQueryParams.SortingProperty#SORT_BY_CREATION_ORDER}
         *
         * @param sortingProperty against which property the results should be sorted
         * @return The same instance of the builder to chain parameters.
         */
        @CheckResult
        public Builder setSortProperty(@SortingProperty int sortingProperty) {
            mSortingProperty = sortingProperty;
            return this;
        }

        /**
         * Sets whether the results should be sorted ascending or descending
         *
         * @param isAscending whether the results should be sorted ascending
         * @return The same instance of the builder to chain parameters.
         */
        @CheckResult
        public Builder setSortDirection(boolean isAscending) {
            mIsAscending = isAscending;
            return this;
        }

        /**
         * Limits the results to the given {@link RcsGroupThread}. Setting this value prevents
         * returning any instances of {@link RcsParticipantAliasChangedEvent}.
         *
         * @param groupThread The thread to limit the results to.
         * @return The same instance of the builder to chain parameters.
         */
        @CheckResult
        public Builder setGroupThread(@NonNull RcsGroupThread groupThread) {
            mThreadId = groupThread.getThreadId();
            return this;
        }

        /**
         * Builds the {@link RcsEventQueryParams} to use in
         * {@link RcsMessageStore#getRcsEvents(RcsEventQueryParams)}
         *
         * @return An instance of {@link RcsEventQueryParams} to use with the event query.
         */
        public RcsEventQueryParams build() {
            return new RcsEventQueryParams(mEventType, mThreadId, mSortingProperty,
                    mIsAscending, mLimit);
        }
    }

    private RcsEventQueryParams(Parcel in) {
        mEventType = in.readInt();
        mThreadId = in.readInt();
        mSortingProperty = in.readInt();
        mIsAscending = in.readBoolean();
        mLimit = in.readInt();
    }

    public static final Creator<RcsEventQueryParams> CREATOR =
            new Creator<RcsEventQueryParams>() {
                @Override
                public RcsEventQueryParams createFromParcel(Parcel in) {
                    return new RcsEventQueryParams(in);
                }

                @Override
                public RcsEventQueryParams[] newArray(int size) {
                    return new RcsEventQueryParams[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mEventType);
        dest.writeInt(mThreadId);
        dest.writeInt(mSortingProperty);
        dest.writeBoolean(mIsAscending);
        dest.writeInt(mLimit);
    }
}
