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
import android.annotation.WorkerThread;

/**
 * Rcs1To1Thread represents a single RCS conversation thread with a total of two
 * {@link RcsParticipant}s. Please see Section 5 (1-to-1 Messaging) - GSMA RCC.71 (RCS Universal
 * Profile Service Definition Document)
 */
public class Rcs1To1Thread extends RcsThread {
    private int mThreadId;

    /**
     * Public constructor only for RcsMessageStoreController to initialize new threads.
     *
     * @hide
     */
    public Rcs1To1Thread(int threadId) {
        super(threadId);
        mThreadId = threadId;
    }

    /**
     * @return Returns {@code false} as this is always a 1 to 1 thread.
     */
    @Override
    public boolean isGroup() {
        return false;
    }

    /**
     * {@link Rcs1To1Thread}s can fall back to SMS as a back-up protocol. This function returns the
     * thread id to be used to query {@code content://mms-sms/conversation/#} to get the fallback
     * thread.
     *
     * @return The thread id to be used to query the mms-sms authority
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @WorkerThread
    public long getFallbackThreadId() throws RcsMessageStoreException {
        return RcsControllerCall.call(iRcs -> iRcs.get1To1ThreadFallbackThreadId(mThreadId));
    }

    /**
     * If the RCS client allows falling back to SMS, it needs to create an MMS-SMS thread in the
     * SMS/MMS Provider( see {@link android.provider.Telephony.MmsSms#CONTENT_CONVERSATIONS_URI}.
     * Use this function to link the {@link Rcs1To1Thread} to the MMS-SMS thread. This function
     * also updates the storage.
     *
     * @throws RcsMessageStoreException if the value could not be persisted into storage
     */
    @WorkerThread
    public void setFallbackThreadId(long fallbackThreadId) throws RcsMessageStoreException {
        RcsControllerCall.callWithNoReturn(
                iRcs -> iRcs.set1To1ThreadFallbackThreadId(mThreadId, fallbackThreadId));
    }

    /**
     * @return Returns the {@link RcsParticipant} that receives the messages sent in this thread.
     * @throws RcsMessageStoreException if the value could not be read from the storage
     */
    @NonNull
    @WorkerThread
    public RcsParticipant getRecipient() throws RcsMessageStoreException {
        return new RcsParticipant(
                RcsControllerCall.call(iRcs -> iRcs.get1To1ThreadOtherParticipantId(mThreadId)));
    }
}
