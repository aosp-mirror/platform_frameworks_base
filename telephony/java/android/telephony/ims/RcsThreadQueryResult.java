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

import java.util.List;
import java.util.stream.Collectors;


/**
 * The result of a {@link RcsMessageStore#getRcsThreads(RcsThreadQueryParams)}
 * call. This class allows getting the token for querying the next batch of threads in order to
 * prevent handling large amounts of data at once.
 *
 * @hide
 */
public final class RcsThreadQueryResult {
    private final RcsControllerCall mRcsControllerCall;
    private final RcsThreadQueryResultParcelable mRcsThreadQueryResultParcelable;

    RcsThreadQueryResult(RcsControllerCall rcsControllerCall,
            RcsThreadQueryResultParcelable rcsThreadQueryResultParcelable) {
        mRcsControllerCall = rcsControllerCall;
        mRcsThreadQueryResultParcelable = rcsThreadQueryResultParcelable;
    }

    /**
     * Returns a token to call
     * {@link RcsMessageStore#getRcsThreads(RcsQueryContinuationToken)}
     * to get the next batch of {@link RcsThread}s.
     */
    @Nullable
    public RcsQueryContinuationToken getContinuationToken() {
        return mRcsThreadQueryResultParcelable.mContinuationToken;
    }

    /**
     * Returns all the RcsThreads in the current query result. Call {@link
     * RcsMessageStore#getRcsThreads(RcsQueryContinuationToken)} to get the next batch of
     * {@link RcsThread}s.
     */
    @NonNull
    public List<RcsThread> getThreads() {
        return mRcsThreadQueryResultParcelable.mRcsThreadIds.stream()
                .map(typeIdPair -> typeIdPair.getType() == THREAD_TYPE_1_TO_1
                        ? new Rcs1To1Thread(mRcsControllerCall, typeIdPair.getId())
                        : new RcsGroupThread(mRcsControllerCall, typeIdPair.getId()))
                .collect(Collectors.toList());
    }
}
