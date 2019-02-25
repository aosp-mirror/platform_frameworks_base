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
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.InvalidParameterException;

/**
 * The parameters to pass into
 * {@link RcsMessageStore#getRcsMessages(RcsMessageQueryParams)} in order to select a
 * subset of {@link RcsMessage}s present in the message store.
 *
 * @hide
 */
public final class RcsMessageQueryParams implements Parcelable {
    /**
     * @hide - not meant for public use
     */
    public static final int THREAD_ID_NOT_SET = -1;

    /**
     * Flag to be used with {@link Builder#setSortProperty(int)} to denote that the results should
     * be sorted in the same order of {@link RcsMessage}s that got persisted into storage for faster
     * results.
     */
    public static final int SORT_BY_CREATION_ORDER = 0;

    /**
     * Flag to be used with {@link Builder#setSortProperty(int)} to denote that the results should
     * be sorted according to the timestamp of {@link RcsMessage#getOriginationTimestamp()}
     */
    public static final int SORT_BY_TIMESTAMP = 1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SORT_BY_CREATION_ORDER, SORT_BY_TIMESTAMP})
    public @interface SortingProperty {
    }

    /**
     * Bitmask flag to be used with {@link Builder#setMessageType(int)} to make
     * {@link RcsMessageStore#getRcsMessages(RcsMessageQueryParams)} return
     * {@link RcsIncomingMessage}s.
     */
    public static final int MESSAGE_TYPE_INCOMING = 0x0001;

    /**
     * Bitmask flag to be used with {@link Builder#setMessageType(int)} to make
     * {@link RcsMessageStore#getRcsMessages(RcsMessageQueryParams)} return
     * {@link RcsOutgoingMessage}s.
     */
    public static final int MESSAGE_TYPE_OUTGOING = 0x0002;

    /**
     * Bitmask flag to be used with {@link Builder#setFileTransferPresence(int)} to make
     * {@link RcsMessageStore#getRcsMessages(RcsMessageQueryParams)} return {@link RcsMessage}s
     * that have an {@link RcsFileTransferPart} attached.
     */
    public static final int MESSAGES_WITH_FILE_TRANSFERS = 0x0004;

    /**
     * Bitmask flag to be used with {@link Builder#setFileTransferPresence(int)} to make
     * {@link RcsMessageStore#getRcsMessages(RcsMessageQueryParams)} return {@link RcsMessage}s
     * that don't have an {@link RcsFileTransferPart} attached.
     */
    public static final int MESSAGES_WITHOUT_FILE_TRANSFERS = 0x0008;

    /**
     * @hide - not meant for public use
     */
    public static final String MESSAGE_QUERY_PARAMETERS_KEY = "message_query_parameters";

    // Whether the result should be filtered against incoming or outgoing messages
    private int mMessageType;
    // Whether the result should have file transfer messages attached or not
    private int mFileTransferPresence;
    // The SQL "Like" clause to filter messages
    private String mMessageLike;
    // The property the messages should be sorted against
    private @SortingProperty int mSortingProperty;
    // Whether the messages should be sorted in ascending order
    private boolean mIsAscending;
    // The number of results that should be returned with this query
    private int mLimit;
    // The thread that the results should be limited to
    private int mThreadId;

    RcsMessageQueryParams(int messageType, int fileTransferPresence, String messageLike,
            int threadId, @SortingProperty int sortingProperty, boolean isAscending, int limit) {
        mMessageType = messageType;
        mFileTransferPresence = fileTransferPresence;
        mMessageLike = messageLike;
        mSortingProperty = sortingProperty;
        mIsAscending = isAscending;
        mLimit = limit;
        mThreadId = threadId;
    }

    /**
     * @return Returns the type of {@link RcsMessage}s that this {@link RcsMessageQueryParams}
     * is set to query for.
     */
    public int getMessageType() {
        return mMessageType;
    }

    /**
     * @return Returns whether the result query should return {@link RcsMessage}s with
     * {@link RcsFileTransferPart}s or not
     */
    public int getFileTransferPresence() {
        return mFileTransferPresence;
    }

    /**
     * @return Returns the SQL-inspired "LIKE" clause that will be used to match {@link RcsMessage}s
     */
    public String getMessageLike() {
        return mMessageLike;
    }

    /**
     * @return Returns the number of {@link RcsThread}s to be returned from the query. A value of
     * 0 means there is no set limit.
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
     * A helper class to build the {@link RcsMessageQueryParams}.
     */
    public static class Builder {
        private @SortingProperty int mSortingProperty;
        private int mMessageType;
        private int mFileTransferPresence;
        private String mMessageLike;
        private boolean mIsAscending;
        private int mLimit = 100;
        private int mThreadId = THREAD_ID_NOT_SET;

        /**
         * Creates a new builder for {@link RcsMessageQueryParams} to be used in
         * {@link RcsMessageStore#getRcsMessages(RcsMessageQueryParams)}
         *
         */
        public Builder() {
            // empty implementation
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
         * Sets the type of messages to be returned from the query.
         *
         * @param messageType The type of message to be returned.
         * @return The same instance of the builder to chain parameters.
         * @see RcsMessageQueryParams#MESSAGE_TYPE_INCOMING
         * @see RcsMessageQueryParams#MESSAGE_TYPE_OUTGOING
         */
        @CheckResult
        public Builder setMessageType(int messageType) {
            mMessageType = messageType;
            return this;
        }

        /**
         * Sets whether file transfer messages should be included in the query result or not.
         *
         * @param fileTransferPresence Whether file transfers should be included in the result
         * @return The same instance of the builder to chain parameters.
         * @see RcsMessageQueryParams#MESSAGES_WITH_FILE_TRANSFERS
         * @see RcsMessageQueryParams#MESSAGES_WITHOUT_FILE_TRANSFERS
         */
        @CheckResult
        public Builder setFileTransferPresence(int fileTransferPresence) {
            mFileTransferPresence = fileTransferPresence;
            return this;
        }

        /**
         * Sets an SQL-inspired "like" clause to match with messages. Using a percent sign ('%')
         * wildcard matches any sequence of zero or more characters. Using an underscore ('_')
         * wildcard matches any single character. Not using any wildcards would only perform a
         * string match. The input string is case-insensitive.
         *
         * The input "Wh%" would match messages "who", "where" and "what", while the input "Wh_"
         * would only match "who"
         *
         * @param messageLike The "like" clause for matching {@link RcsMessage}s.
         * @return The same instance of the builder to chain parameters.
         */
        @CheckResult
        public Builder setMessageLike(String messageLike) {
            mMessageLike = messageLike;
            return this;
        }

        /**
         * Sets the property where the results should be sorted against. Defaults to
         * {@link RcsMessageQueryParams.SortingProperty#SORT_BY_CREATION_ORDER}
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
         * Limits the results to the given thread.
         *
         * @param thread the {@link RcsThread} that results should be limited to. If set to
         *               {@code null}, messages on all threads will be queried
         * @return The same instance of the builder to chain parameters.
         */
        @CheckResult
        public Builder setThread(@Nullable RcsThread thread) {
            if (thread == null) {
                mThreadId = THREAD_ID_NOT_SET;
            } else {
                mThreadId = thread.getThreadId();
            }
            return this;
        }

        /**
         * Builds the {@link RcsMessageQueryParams} to use in
         * {@link RcsMessageStore#getRcsMessages(RcsMessageQueryParams)}
         *
         * @return An instance of {@link RcsMessageQueryParams} to use with the message
         * query.
         */
        public RcsMessageQueryParams build() {
            return new RcsMessageQueryParams(mMessageType, mFileTransferPresence, mMessageLike,
                    mThreadId, mSortingProperty, mIsAscending, mLimit);
        }
    }

    /**
     * Parcelable boilerplate below.
     */
    private RcsMessageQueryParams(Parcel in) {
        mMessageType = in.readInt();
        mFileTransferPresence = in.readInt();
        mMessageLike = in.readString();
        mSortingProperty = in.readInt();
        mIsAscending = in.readBoolean();
        mLimit = in.readInt();
        mThreadId = in.readInt();
    }

    public static final Creator<RcsMessageQueryParams> CREATOR =
            new Creator<RcsMessageQueryParams>() {
                @Override
                public RcsMessageQueryParams createFromParcel(Parcel in) {
                    return new RcsMessageQueryParams(in);
                }

                @Override
                public RcsMessageQueryParams[] newArray(int size) {
                    return new RcsMessageQueryParams[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mMessageType);
        dest.writeInt(mFileTransferPresence);
        dest.writeString(mMessageLike);
        dest.writeInt(mSortingProperty);
        dest.writeBoolean(mIsAscending);
        dest.writeInt(mLimit);
        dest.writeInt(mThreadId);
    }
}
