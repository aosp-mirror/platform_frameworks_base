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

import static android.provider.Telephony.RcsColumns.RcsUnifiedMessageColumns.MESSAGE_TYPE_INCOMING;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.ims.RcsTypeIdPair;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of a {@link RcsMessageStore#getRcsMessages(RcsMessageQueryParams)}
 * call. This class allows getting the token for querying the next batch of messages in order to
 * prevent handling large amounts of data at once.
 *
 * @hide
 */
public final class RcsMessageQueryResult implements Parcelable {
    // The token to continue the query to get the next batch of results
    private RcsQueryContinuationToken mContinuationToken;
    // The message type and message ID pairs for all the messages in this query result
    private List<RcsTypeIdPair> mMessageTypeIdPairs;

    /**
     * Internal constructor for {@link com.android.internal.telephony.ims.RcsMessageStoreController}
     * to create query results
     *
     * @hide
     */
    public RcsMessageQueryResult(
            RcsQueryContinuationToken continuationToken,
            List<RcsTypeIdPair> messageTypeIdPairs) {
        mContinuationToken = continuationToken;
        mMessageTypeIdPairs = messageTypeIdPairs;
    }

    /**
     * Returns a token to call
     * {@link RcsMessageStore#getRcsMessages(RcsQueryContinuationToken)}
     * to get the next batch of {@link RcsMessage}s.
     */
    @Nullable
    public RcsQueryContinuationToken getContinuationToken() {
        return mContinuationToken;
    }

    /**
     * Returns all the {@link RcsMessage}s in the current query result. Call {@link
     * RcsMessageStore#getRcsMessages(RcsQueryContinuationToken)} to get the next batch
     * of {@link RcsMessage}s.
     */
    @NonNull
    public List<RcsMessage> getMessages() {
        List<RcsMessage> messages = new ArrayList<>();
        for (RcsTypeIdPair typeIdPair : mMessageTypeIdPairs) {
            if (typeIdPair.getType() == MESSAGE_TYPE_INCOMING) {
                messages.add(new RcsIncomingMessage(typeIdPair.getId()));
            } else {
                messages.add(new RcsOutgoingMessage(typeIdPair.getId()));
            }
        }

        return messages;
    }

    private RcsMessageQueryResult(Parcel in) {
        mContinuationToken = in.readParcelable(
                RcsQueryContinuationToken.class.getClassLoader());
        in.readTypedList(mMessageTypeIdPairs, RcsTypeIdPair.CREATOR);
    }

    public static final Creator<RcsMessageQueryResult> CREATOR =
            new Creator<RcsMessageQueryResult>() {
                @Override
                public RcsMessageQueryResult createFromParcel(Parcel in) {
                    return new RcsMessageQueryResult(in);
                }

                @Override
                public RcsMessageQueryResult[] newArray(int size) {
                    return new RcsMessageQueryResult[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mContinuationToken, flags);
        dest.writeTypedList(mMessageTypeIdPairs);
    }
}
