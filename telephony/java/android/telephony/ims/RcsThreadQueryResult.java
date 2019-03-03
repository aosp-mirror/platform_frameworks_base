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

import static android.provider.Telephony.RcsColumns.RcsUnifiedThreadColumns.THREAD_TYPE_1_TO_1;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.ims.RcsTypeIdPair;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of a {@link RcsMessageStore#getRcsThreads(RcsThreadQueryParams)}
 * call. This class allows getting the token for querying the next batch of threads in order to
 * prevent handling large amounts of data at once.
 *
 * @hide
 */
public final class RcsThreadQueryResult implements Parcelable {
    // A token for the caller to continue their query for the next batch of results
    private RcsQueryContinuationToken mContinuationToken;
    // The list of thread IDs returned with this query
    private List<RcsTypeIdPair> mRcsThreadIds;

    /**
     * Internal constructor for {@link com.android.internal.telephony.ims.RcsMessageStoreController}
     * to create query results
     *
     * @hide
     */
    public RcsThreadQueryResult(
            RcsQueryContinuationToken continuationToken,
            List<RcsTypeIdPair> rcsThreadIds) {
        mContinuationToken = continuationToken;
        mRcsThreadIds = rcsThreadIds;
    }

    /**
     * Returns a token to call
     * {@link RcsMessageStore#getRcsThreads(RcsQueryContinuationToken)}
     * to get the next batch of {@link RcsThread}s.
     */
    @Nullable
    public RcsQueryContinuationToken getContinuationToken() {
        return mContinuationToken;
    }

    /**
     * Returns all the RcsThreads in the current query result. Call {@link
     * RcsMessageStore#getRcsThreads(RcsQueryContinuationToken)} to get the next batch of
     * {@link RcsThread}s.
     */
    @NonNull
    public List<RcsThread> getThreads() {
        List<RcsThread> rcsThreads = new ArrayList<>();

        for (RcsTypeIdPair typeIdPair : mRcsThreadIds) {
            if (typeIdPair.getType() == THREAD_TYPE_1_TO_1) {
                rcsThreads.add(new Rcs1To1Thread(typeIdPair.getId()));
            } else {
                rcsThreads.add(new RcsGroupThread(typeIdPair.getId()));
            }
        }

        return rcsThreads;
    }

    private RcsThreadQueryResult(Parcel in) {
        mContinuationToken = in.readParcelable(
            RcsQueryContinuationToken.class.getClassLoader());
        mRcsThreadIds = new ArrayList<>();
        in.readList(mRcsThreadIds, Integer.class.getClassLoader());
    }

    public static final @android.annotation.NonNull Creator<RcsThreadQueryResult> CREATOR =
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
        dest.writeParcelable(mContinuationToken, flags);
        dest.writeList(mRcsThreadIds);
    }
}
