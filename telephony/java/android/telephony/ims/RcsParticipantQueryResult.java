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

import java.util.List;
import java.util.stream.Collectors;

/**
 * The result of a {@link RcsMessageStore#getRcsParticipants(RcsParticipantQueryParams)}
 * call. This class allows getting the token for querying the next batch of participants in order to
 * prevent handling large amounts of data at once.
 *
 * @hide
 */
public final class RcsParticipantQueryResult {
    private final RcsControllerCall mRcsControllerCall;
    private final RcsParticipantQueryResultParcelable mRcsParticipantQueryResultParcelable;

    RcsParticipantQueryResult(
            RcsControllerCall rcsControllerCall,
            RcsParticipantQueryResultParcelable rcsParticipantQueryResultParcelable) {
        mRcsControllerCall = rcsControllerCall;
        mRcsParticipantQueryResultParcelable = rcsParticipantQueryResultParcelable;
    }

    /**
     * Returns a token to call
     * {@link RcsMessageStore#getRcsParticipants(RcsQueryContinuationToken)}
     * to get the next batch of {@link RcsParticipant}s.
     */
    @Nullable
    public RcsQueryContinuationToken getContinuationToken() {
        return mRcsParticipantQueryResultParcelable.mContinuationToken;
    }

    /**
     * Returns all the {@link RcsParticipant}s in the current query result. Call {@link
     * RcsMessageStore#getRcsParticipants(RcsQueryContinuationToken)} to get the next
     * batch of {@link RcsParticipant}s.
     */
    @NonNull
    public List<RcsParticipant> getParticipants() {
        return mRcsParticipantQueryResultParcelable.mParticipantIds.stream()
                .map(participantId -> new RcsParticipant(mRcsControllerCall, participantId))
                .collect(Collectors.toList());
    }
}
