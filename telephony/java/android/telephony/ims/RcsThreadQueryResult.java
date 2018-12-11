/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

/**
 * The result of a {@link RcsMessageStore#getRcsThreads(RcsThreadQueryContinuationToken,
 * RcsThreadQueryParameters)}
 * call. This class allows getting the token for querying the next batch of threads in order to
 * prevent handling large amounts of data at once.
 *
 * @hide
 */
public class RcsThreadQueryResult implements Parcelable {
    private RcsThreadQueryContinuationToken mContinuationToken;
    private List<RcsThread> mRcsThreads;

    /**
     * Internal constructor for {@link com.android.internal.telephony.ims.RcsMessageStoreController}
     * to create query results
     *
     * @hide
     */
    public RcsThreadQueryResult(
            RcsThreadQueryContinuationToken continuationToken, List<RcsThread> rcsThreads) {
        mContinuationToken = continuationToken;
        mRcsThreads = rcsThreads;
    }

    /**
     * Returns a token to call
     * {@link RcsMessageStore#getRcsThreads(RcsThreadQueryContinuationToken)}
     * to get the next batch of {@link RcsThread}s.
     */
    public RcsThreadQueryContinuationToken nextChunkToken() {
        return mContinuationToken;
    }

    /**
     * Returns all the RcsThreads in the current query result. Call {@link
     * RcsMessageStore#getRcsThreads(RcsThreadQueryContinuationToken)} to get the next batch of
     * {@link RcsThread}s.
     */
    public List<RcsThread> getThreads() {
        return mRcsThreads;
    }

    protected RcsThreadQueryResult(Parcel in) {
        // TODO - implement
    }

    public static final Creator<RcsThreadQueryResult> CREATOR =
            new Creator<RcsThreadQueryResult>() {
                @Override
                public RcsThreadQueryResult createFromParcel(Parcel in) {
                    return new RcsThreadQueryResult(in);
                }

                @Override
                public RcsThreadQueryResult[] newArray(int size) {
                    return new RcsThreadQueryResult[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // TODO - implement
    }
}
