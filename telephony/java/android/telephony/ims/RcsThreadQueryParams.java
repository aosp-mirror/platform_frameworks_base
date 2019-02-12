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

import android.annotation.CheckResult;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The parameters to pass into {@link RcsMessageStore#getRcsThreads(RcsThreadQueryParams)} in
 * order to select a subset of {@link RcsThread}s present in the message store.
 */
public final class RcsThreadQueryParams implements Parcelable {
    /**
     * Bitmask flag to be used with {@link Builder#setThreadType(int)} to make
     * {@link RcsMessageStore#getRcsThreads(RcsThreadQueryParams)} return
     * {@link RcsGroupThread}s.
     */
    public static final int THREAD_TYPE_GROUP = 0x0001;

    /**
     * Bitmask flag to be used with {@link Builder#setThreadType(int)} to make
     * {@link RcsMessageStore#getRcsThreads(RcsThreadQueryParams)} return
     * {@link Rcs1To1Thread}s.
     */
    public static final int THREAD_TYPE_1_TO_1 = 0x0002;

    // The type of threads to be filtered with the query
    private final int mThreadType;
    // The list of participants that are expected in the resulting threads
    private final List<Integer> mRcsParticipantIds;
    // The number of RcsThread's that should be returned with this query
    private final int mLimit;
    // The property which the result of the query should be sorted against
    private final @SortingProperty int mSortingProperty;
    // Whether the sorting should be done in ascending
    private final boolean mIsAscending;

    /**
     * Flag to be used with {@link Builder#setSortProperty(int)} to denote that the results should
     * be sorted in the order of {@link RcsThread} creation time for faster results.
     */
    public static final int SORT_BY_CREATION_ORDER = 0;

    /**
     * Flag to be used with {@link Builder#setSortProperty(int)} to denote that the results should
     * be sorted according to the timestamp of {@link RcsThread#getSnippet()}
     */
    public static final int SORT_BY_TIMESTAMP = 1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SORT_BY_CREATION_ORDER, SORT_BY_TIMESTAMP})
    public @interface SortingProperty {
    }

    /**
     * @hide
     */
    public static final String THREAD_QUERY_PARAMETERS_KEY = "thread_query_parameters";

    RcsThreadQueryParams(int threadType, Set<RcsParticipant> participants,
            int limit, int sortingProperty, boolean isAscending) {
        mThreadType = threadType;
        mRcsParticipantIds = convertParticipantSetToIdList(participants);
        mLimit = limit;
        mSortingProperty = sortingProperty;
        mIsAscending = isAscending;
    }

    private static List<Integer> convertParticipantSetToIdList(Set<RcsParticipant> participants) {
        List<Integer> ids = new ArrayList<>(participants.size());
        for (RcsParticipant participant : participants) {
            ids.add(participant.getId());
        }
        return ids;
    }

    /**
     * This is used in {@link com.android.internal.telephony.ims.RcsMessageStoreController} to get
     * the list of participant IDs.
     *
     * As we don't expose any integer ID's to API users, this should stay hidden
     *
     * @hide - not meant for public use
     */
    public List<Integer> getRcsParticipantsIds() {
        return Collections.unmodifiableList(mRcsParticipantIds);
    }

    /**
     * @return Returns the bitmask flag for types of {@link RcsThread}s that this query should
     * return.
     */
    public int getThreadType() {
        return mThreadType;
    }

    /**
     * @return Returns the number of {@link RcsThread}s to be returned from the query. A value
     * of 0 means there is no set limit.
     */
    public int getLimit() {
        return mLimit;
    }

    /**
     * @return Returns the property that will be used to sort the result against.
     * @see SortingProperty
     */
    public @SortingProperty int getSortingProperty() {
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
     * A helper class to build the {@link RcsThreadQueryParams}.
     */
    public static class Builder {
        private int mThreadType;
        private Set<RcsParticipant> mParticipants;
        private int mLimit = 100;
        private @SortingProperty int mSortingProperty;
        private boolean mIsAscending;

        /**
         * Constructs a {@link RcsThreadQueryParams.Builder} to help build an
         * {@link RcsThreadQueryParams}
         */
        public Builder() {
            mParticipants = new HashSet<>();
        }

        /**
         * Limits the query to only return group threads.
         *
         * @param threadType Whether to limit the query result to group threads.
         * @return The same instance of the builder to chain parameters.
         * @see RcsThreadQueryParams#THREAD_TYPE_GROUP
         * @see RcsThreadQueryParams#THREAD_TYPE_1_TO_1
         */
        @CheckResult
        public Builder setThreadType(int threadType) {
            mThreadType = threadType;
            return this;
        }

        /**
         * Limits the query to only return threads that contain the given participant. If this
         * property was not set, participants will not be taken into account while querying for
         * threads.
         *
         * @param participant The participant that must be included in all of the returned threads.
         * @return The same instance of the builder to chain parameters.
         */
        @CheckResult
        public Builder setParticipant(@NonNull RcsParticipant participant) {
            mParticipants.add(participant);
            return this;
        }

        /**
         * Limits the query to only return threads that contain the given list of participants. If
         * this property was not set, participants will not be taken into account while querying
         * for threads.
         *
         * @param participants An iterable list of participants that must be included in all of the
         *                     returned threads.
         * @return The same instance of the builder to chain parameters.
         */
        @CheckResult
        public Builder setParticipants(@NonNull List<RcsParticipant> participants) {
            mParticipants.addAll(participants);
            return this;
        }

        /**
         * Desired number of threads to be returned from the query. Passing in 0 will return all
         * existing threads at once. The limit defaults to 100.
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
         * Sets the property where the results should be sorted against. Defaults to
         * {@link SortingProperty#SORT_BY_CREATION_ORDER}
         *
         * @param sortingProperty whether to sort in ascending order or not
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
         * Builds the {@link RcsThreadQueryParams} to use in
         * {@link RcsMessageStore#getRcsThreads(RcsThreadQueryParams)}
         *
         * @return An instance of {@link RcsThreadQueryParams} to use with the thread query.
         */
        public RcsThreadQueryParams build() {
            return new RcsThreadQueryParams(mThreadType, mParticipants, mLimit,
                    mSortingProperty, mIsAscending);
        }
    }

    /**
     * Parcelable boilerplate below.
     */
    private RcsThreadQueryParams(Parcel in) {
        mThreadType = in.readInt();
        mRcsParticipantIds = new ArrayList<>();
        in.readList(mRcsParticipantIds, Integer.class.getClassLoader());
        mLimit = in.readInt();
        mSortingProperty = in.readInt();
        mIsAscending = in.readByte() == 1;
    }

    public static final Creator<RcsThreadQueryParams> CREATOR =
            new Creator<RcsThreadQueryParams>() {
                @Override
                public RcsThreadQueryParams createFromParcel(Parcel in) {
                    return new RcsThreadQueryParams(in);
                }

                @Override
                public RcsThreadQueryParams[] newArray(int size) {
                    return new RcsThreadQueryParams[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mThreadType);
        dest.writeList(mRcsParticipantIds);
        dest.writeInt(mLimit);
        dest.writeInt(mSortingProperty);
        dest.writeByte((byte) (mIsAscending ? 1 : 0));
    }
}
