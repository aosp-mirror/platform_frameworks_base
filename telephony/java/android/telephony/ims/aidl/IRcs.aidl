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

package android.telephony.ims.aidl;

import android.telephony.ims.RcsParticipant;
import android.telephony.ims.Rcs1To1Thread;
import android.telephony.ims.RcsThreadQueryContinuationToken;
import android.telephony.ims.RcsThreadQueryParameters;
import android.telephony.ims.RcsThreadQueryResult;

/**
 * RPC definition between RCS storage APIs and phone process.
 * {@hide}
 */
interface IRcs {
    // RcsMessageStore APIs
    RcsThreadQueryResult getRcsThreads(in RcsThreadQueryParameters queryParameters);

    RcsThreadQueryResult getRcsThreadsWithToken(
        in RcsThreadQueryContinuationToken continuationToken);

    void deleteThread(int threadId);

    Rcs1To1Thread createRcs1To1Thread(in RcsParticipant participant);

    // RcsThread APIs
    int getMessageCount(int rcsThreadId);

    // RcsParticipant APIs
    RcsParticipant createRcsParticipant(String canonicalAddress);

    void updateRcsParticipantCanonicalAddress(int id, String canonicalAddress);

    void updateRcsParticipantAlias(int id, String alias);
}