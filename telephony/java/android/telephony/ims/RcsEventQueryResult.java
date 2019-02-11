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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

/**
 * The result of a {@link RcsMessageStore#getRcsEvents(RcsEventQueryParams)}
 * call. This class allows getting the token for querying the next batch of events in order to
 * prevent handling large amounts of data at once.
 */
public final class RcsEventQueryResult implements Parcelable {
    private RcsQueryContinuationToken mContinuationToken;
    private List<RcsEvent> mEvents;

    /**
     * Internal constructor for {@link com.android.internal.telephony.ims.RcsMessageStoreController}
     * to create query results
     *
     * @hide
     */
    public RcsEventQueryResult(
            RcsQueryContinuationToken continuationToken,
            List<RcsEvent> events) {
        mContinuationToken = continuationToken;
        mEvents = events;
    }

    /**
     * Returns a token to call
     * {@link RcsMessageStore#getRcsEvents(RcsQueryContinuationToken)}
     * to get the next batch of {@link RcsEvent}s.
     */
    public RcsQueryContinuationToken getContinuationToken() {
        return mContinuationToken;
    }

    /**
     * Returns all the {@link RcsEvent}s in the current query result. Call {@link
     * RcsMessageStore#getRcsEvents(RcsQueryContinuationToken)} to get the next batch
     * of {@link RcsEvent}s.
     */
    public List<RcsEvent> getEvents() {
        return mEvents;
    }

    private RcsEventQueryResult(Parcel in) {
        mContinuationToken = in.readParcelable(RcsQueryContinuationToken.class.getClassLoader());
    }

    public static final Creator<RcsEventQueryResult> CREATOR = new Creator<RcsEventQueryResult>() {
        @Override
        public RcsEventQueryResult createFromParcel(Parcel in) {
            return new RcsEventQueryResult(in);
        }

        @Override
        public RcsEventQueryResult[] newArray(int size) {
            return new RcsEventQueryResult[size];
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
