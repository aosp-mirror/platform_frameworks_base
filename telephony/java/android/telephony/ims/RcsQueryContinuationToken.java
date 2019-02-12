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

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A token for enabling continuation queries. Instances are acquired through
 * {@code getContinuationToken} on result objects after initial query is done.
 *
 * @see RcsEventQueryResult#getContinuationToken()
 * @see RcsMessageQueryResult#getContinuationToken()
 * @see RcsParticipantQueryResult#getContinuationToken()
 * @see RcsThreadQueryResult#getContinuationToken()
 */
public final class RcsQueryContinuationToken implements Parcelable {
    /**
     * Denotes that this {@link RcsQueryContinuationToken} token is meant to allow continuing
     * {@link RcsEvent} queries
     */
    public static final int EVENT_QUERY_CONTINUATION_TOKEN_TYPE = 0;

    /**
     * Denotes that this {@link RcsQueryContinuationToken} token is meant to allow continuing
     * {@link RcsMessage} queries
     */
    public static final int MESSAGE_QUERY_CONTINUATION_TOKEN_TYPE = 1;

    /**
     * Denotes that this {@link RcsQueryContinuationToken} token is meant to allow continuing
     * {@link RcsParticipant} queries
     */
    public static final int PARTICIPANT_QUERY_CONTINUATION_TOKEN_TYPE = 2;

    /**
     * Denotes that this {@link RcsQueryContinuationToken} token is meant to allow continuing
     * {@link RcsThread} queries
     */
    public static final int THREAD_QUERY_CONTINUATION_TOKEN_TYPE = 3;

    /**
     * @hide - not meant for public use
     */
    public static final String QUERY_CONTINUATION_TOKEN = "query_continuation_token";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({EVENT_QUERY_CONTINUATION_TOKEN_TYPE, MESSAGE_QUERY_CONTINUATION_TOKEN_TYPE,
        PARTICIPANT_QUERY_CONTINUATION_TOKEN_TYPE, THREAD_QUERY_CONTINUATION_TOKEN_TYPE})
    public @interface ContinuationTokenType {}

    // The type of query this token should allow to continue
    private @ContinuationTokenType int mQueryType;
    // The raw query string for the initial query
    private final String mRawQuery;
    // The number of results that is returned with each query
    private final int mLimit;
    // The offset value that this query should start the query from
    private int mOffset;

    /**
     * @hide
     */
    public RcsQueryContinuationToken(@ContinuationTokenType int queryType, String rawQuery,
            int limit, int offset) {
        mQueryType = queryType;
        mRawQuery = rawQuery;
        mLimit = limit;
        mOffset = offset;
    }

    /**
     * Returns the original raw query used on {@link com.android.providers.telephony.RcsProvider}
     * @hide
     */
    public String getRawQuery() {
        return mRawQuery;
    }

    /**
     * Returns which index this continuation query should start from
     * @hide
     */
    public int getOffset() {
        return mOffset;
    }

    /**
     * Increments the offset by the amount of result rows returned with the continuation query for
     * the next query.
     * @hide
     */
    public void incrementOffset() {
        mOffset += mLimit;
    }

    /**
     * Returns the type of query that this {@link RcsQueryContinuationToken} is intended to be used
     * to continue.
     */
    public @ContinuationTokenType int getQueryType() {
        return mQueryType;
    }

    private RcsQueryContinuationToken(Parcel in) {
        mQueryType = in.readInt();
        mRawQuery = in.readString();
        mLimit = in.readInt();
        mOffset = in.readInt();
    }

    public static final Creator<RcsQueryContinuationToken> CREATOR =
            new Creator<RcsQueryContinuationToken>() {
                @Override
                public RcsQueryContinuationToken createFromParcel(Parcel in) {
                    return new RcsQueryContinuationToken(in);
                }

                @Override
                public RcsQueryContinuationToken[] newArray(int size) {
                    return new RcsQueryContinuationToken[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mQueryType);
        dest.writeString(mRawQuery);
        dest.writeInt(mLimit);
        dest.writeInt(mOffset);
    }
}
