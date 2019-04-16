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
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.InvalidParameterException;

/**
 * The parameters to pass into
 * {@link RcsMessageStore#getRcsParticipants(RcsParticipantQueryParams)} in order to select a
 * subset of {@link RcsThread}s present in the message store.
 *
 * @hide
 */
public final class RcsParticipantQueryParams implements Parcelable {
    /**
     * Flag to set with {@link Builder#setSortProperty(int)} to sort the results in the order of
     * creation time for faster query results
     */
    public static final int SORT_BY_CREATION_ORDER = 0;

    /**
     * Flag to set with {@link Builder#setSortProperty(int)} to sort depending on the
     * {@link RcsParticipant} aliases
     */
    public static final int SORT_BY_ALIAS = 1;

    /**
     * Flag to set with {@link Builder#setSortProperty(int)} to sort depending on the
     * {@link RcsParticipant} canonical addresses
     */
    public static final int SORT_BY_CANONICAL_ADDRESS = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SORT_BY_CREATION_ORDER, SORT_BY_ALIAS, SORT_BY_CANONICAL_ADDRESS})
    public @interface SortingProperty {
    }

    // The SQL "like" statement to filter against participant aliases
    private String mAliasLike;
    // The SQL "like" statement to filter against canonical addresses
    private String mCanonicalAddressLike;
    // The property to sort the result against
    private @SortingProperty int mSortingProperty;
    // Whether to sort the result in ascending order
    private boolean mIsAscending;
    // The number of results to be returned from the query
    private int mLimit;
    // Used to limit the results to participants of a single thread
    private int mThreadId;

    /**
     * @hide
     */
    public static final String PARTICIPANT_QUERY_PARAMETERS_KEY = "participant_query_parameters";

    RcsParticipantQueryParams(int rcsThreadId, String aliasLike, String canonicalAddressLike,
            @SortingProperty int sortingProperty, boolean isAscending,
            int limit) {
        mThreadId = rcsThreadId;
        mAliasLike = aliasLike;
        mCanonicalAddressLike = canonicalAddressLike;
        mSortingProperty = sortingProperty;
        mIsAscending = isAscending;
        mLimit = limit;
    }

    /**
     * This is used in {@link com.android.internal.telephony.ims.RcsMessageStoreController} to get
     * the thread that the result query should be limited to.
     *
     * As we do not expose any sort of integer ID's to public usage, this should be hidden.
     *
     * @hide - not meant for public use
     */
    public int getThreadId() {
        return mThreadId;
    }

    /**
     * @return Returns the SQL-inspired "LIKE" clause that will be used to match
     * {@link RcsParticipant}s with respect to their aliases
     *
     * @see RcsParticipant#getAlias()
     */
    public String getAliasLike() {
        return mAliasLike;
    }

    /**
     * @return Returns the SQL-inspired "LIKE" clause that will be used to match
     * {@link RcsParticipant}s with respect to their canonical addresses.
     *
     * @see RcsParticipant#getCanonicalAddress()
     */
    public String getCanonicalAddressLike() {
        return mCanonicalAddressLike;
    }

    /**
     * @return Returns the number of {@link RcsParticipant}s to be returned from the query. A value
     * of 0 means there is no set limit.
     */
    public int getLimit() {
        return mLimit;
    }

    /**
     * @return Returns the property that will be used to sort the result against.
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
     * A helper class to build the {@link RcsParticipantQueryParams}.
     */
    public static class Builder {
        private String mAliasLike;
        private String mCanonicalAddressLike;
        private @SortingProperty int mSortingProperty;
        private boolean mIsAscending;
        private int mLimit = 100;
        private int mThreadId;

        /**
         * Creates a new builder for {@link RcsParticipantQueryParams} to be used in
         * {@link RcsMessageStore#getRcsParticipants(RcsParticipantQueryParams)}
         */
        public Builder() {
            // empty implementation
        }

        /**
         * Limits the resulting {@link RcsParticipant}s to only the given {@link RcsThread}
         *
         * @param rcsThread The thread that the participants should be searched in.
         * @return The same {@link Builder} to chain methods.
         */
        @CheckResult
        public Builder setThread(RcsThread rcsThread) {
            mThreadId = rcsThread.getThreadId();
            return this;
        }

        /**
         * Sets an SQL-inspired "like" clause to match with participant aliases. Using a percent
         * sign ('%') wildcard matches any sequence of zero or more characters. Using an underscore
         * ('_') wildcard matches any single character. Not using any wildcards would only perform a
         * string match.The input string is case-insensitive.
         *
         * The input "An%e" would match {@link RcsParticipant}s with names Anne, Annie, Antonie,
         * while the input "An_e" would only match Anne.
         *
         * @param likeClause The like clause to use for matching {@link RcsParticipant} aliases.
         * @return The same {@link Builder} to chain methods
         */
        @CheckResult
        public Builder setAliasLike(String likeClause) {
            mAliasLike = likeClause;
            return this;
        }

        /**
         * Sets an SQL-inspired "like" clause to match with participant addresses. Using a percent
         * sign ('%') wildcard matches any sequence of zero or more characters. Using an underscore
         * ('_') wildcard matches any single character. Not using any wildcards would only perform a
         * string match. The input string is case-insensitive.
         *
         * The input "+999%111" would match {@link RcsParticipant}s with addresses like "+9995111"
         * or "+99955555111", while the input "+999_111" would only match "+9995111".
         *
         * @param likeClause The like clause to use for matching {@link RcsParticipant} canonical
         *                   addresses.
         * @return The same {@link Builder} to chain methods
         */
        @CheckResult
        public Builder setCanonicalAddressLike(String likeClause) {
            mCanonicalAddressLike = likeClause;
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
         * {@link RcsParticipantQueryParams.SortingProperty#SORT_BY_CREATION_ORDER}
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
         * Builds the {@link RcsParticipantQueryParams} to use in
         * {@link RcsMessageStore#getRcsParticipants(RcsParticipantQueryParams)}
         *
         * @return An instance of {@link RcsParticipantQueryParams} to use with the participant
         * query.
         */
        public RcsParticipantQueryParams build() {
            return new RcsParticipantQueryParams(mThreadId, mAliasLike, mCanonicalAddressLike,
                    mSortingProperty, mIsAscending, mLimit);
        }
    }

    /**
     * Parcelable boilerplate below.
     */
    private RcsParticipantQueryParams(Parcel in) {
        mAliasLike = in.readString();
        mCanonicalAddressLike = in.readString();
        mSortingProperty = in.readInt();
        mIsAscending = in.readByte() == 1;
        mLimit = in.readInt();
        mThreadId = in.readInt();
    }

    public static final @android.annotation.NonNull Creator<RcsParticipantQueryParams> CREATOR =
            new Creator<RcsParticipantQueryParams>() {
                @Override
                public RcsParticipantQueryParams createFromParcel(Parcel in) {
                    return new RcsParticipantQueryParams(in);
                }

                @Override
                public RcsParticipantQueryParams[] newArray(int size) {
                    return new RcsParticipantQueryParams[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mAliasLike);
        dest.writeString(mCanonicalAddressLike);
        dest.writeInt(mSortingProperty);
        dest.writeByte((byte) (mIsAscending ? 1 : 0));
        dest.writeInt(mLimit);
        dest.writeInt(mThreadId);
    }

}
