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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of a {@link RcsMessageStore#getRcsParticipants(RcsParticipantQueryParams)}
 * call. This class allows getting the token for querying the next batch of participants in order to
 * prevent handling large amounts of data at once.
 */
public final class RcsParticipantQueryResult implements Parcelable {
    // A token for the caller to continue their query for the next batch of results
    private RcsQueryContinuationToken mContinuationToken;
    // The list of participant IDs returned with this query
    private List<Integer> mParticipants;

    /**
     * Internal constructor for {@link com.android.internal.telephony.ims.RcsMessageStoreController}
     * to create query results
     *
     * @hide
     */
    public RcsParticipantQueryResult(
            RcsQueryContinuationToken continuationToken,
            List<Integer> participants) {
        mContinuationToken = continuationToken;
        mParticipants = participants;
    }

    /**
     * Returns a token to call
     * {@link RcsMessageStore#getRcsParticipants(RcsQueryContinuationToken)}
     * to get the next batch of {@link RcsParticipant}s.
     */
    @Nullable
    public RcsQueryContinuationToken getContinuationToken() {
        return mContinuationToken;
    }

    /**
     * Returns all the {@link RcsParticipant}s in the current query result. Call {@link
     * RcsMessageStore#getRcsParticipants(RcsQueryContinuationToken)} to get the next
     * batch of {@link RcsParticipant}s.
     */
    @NonNull
    public List<RcsParticipant> getParticipants() {
        List<RcsParticipant> participantList = new ArrayList<>();
        for (Integer participantId : mParticipants) {
            participantList.add(new RcsParticipant(participantId));
        }

        return participantList;
    }

    private RcsParticipantQueryResult(Parcel in) {
        mContinuationToken = in.readParcelable(
                RcsQueryContinuationToken.class.getClassLoader());
    }

    public static final Creator<RcsParticipantQueryResult> CREATOR =
            new Creator<RcsParticipantQueryResult>() {
                @Override
                public RcsParticipantQueryResult createFromParcel(Parcel in) {
                    return new RcsParticipantQueryResult(in);
                }

                @Override
                public RcsParticipantQueryResult[] newArray(int size) {
                    return new RcsParticipantQueryResult[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mContinuationToken, flags);
    }
}
