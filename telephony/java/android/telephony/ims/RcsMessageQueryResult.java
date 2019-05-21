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

import java.util.List;
import java.util.stream.Collectors;

/**
 * The result of a {@link RcsMessageStore#getRcsMessages(RcsMessageQueryParams)}
 * call. This class allows getting the token for querying the next batch of messages in order to
 * prevent handling large amounts of data at once.
 *
 * @hide
 */
public final class RcsMessageQueryResult {
    private final RcsControllerCall mRcsControllerCall;
    private final RcsMessageQueryResultParcelable mRcsMessageQueryResultParcelable;

    RcsMessageQueryResult(RcsControllerCall rcsControllerCall,
            RcsMessageQueryResultParcelable rcsMessageQueryResultParcelable) {
        mRcsControllerCall = rcsControllerCall;
        mRcsMessageQueryResultParcelable = rcsMessageQueryResultParcelable;
    }

    /**
     * Returns a token to call
     * {@link RcsMessageStore#getRcsMessages(RcsQueryContinuationToken)}
     * to get the next batch of {@link RcsMessage}s.
     */
    @Nullable
    public RcsQueryContinuationToken getContinuationToken() {
        return mRcsMessageQueryResultParcelable.mContinuationToken;
    }

    /**
     * Returns all the {@link RcsMessage}s in the current query result. Call {@link
     * RcsMessageStore#getRcsMessages(RcsQueryContinuationToken)} to get the next batch
     * of {@link RcsMessage}s.
     */
    @NonNull
    public List<RcsMessage> getMessages() {
        return mRcsMessageQueryResultParcelable.mMessageTypeIdPairs.stream()
                .map(typeIdPair -> typeIdPair.getType() == MESSAGE_TYPE_INCOMING
                        ? new RcsIncomingMessage(mRcsControllerCall, typeIdPair.getId())
                        : new RcsOutgoingMessage(mRcsControllerCall, typeIdPair.getId()))
                .collect(Collectors.toList());
    }
}
