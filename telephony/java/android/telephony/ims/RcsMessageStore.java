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

import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.Rlog;
import android.telephony.ims.aidl.IRcs;

/**
 * RcsMessageStore is the application interface to RcsProvider and provides access methods to
 * RCS related database tables.
 * @hide - TODO make this public
 */
public class RcsMessageStore {
    static final String TAG = "RcsMessageStore";

    /**
     * Returns the first chunk of existing {@link RcsThread}s in the common storage.
     * @param queryParameters Parameters to specify to return a subset of all RcsThreads.
     *                        Passing a value of null will return all threads.
     */
    @WorkerThread
    public RcsThreadQueryResult getRcsThreads(@Nullable RcsThreadQueryParameters queryParameters) {
        try {
            IRcs iRcs = IRcs.Stub.asInterface(ServiceManager.getService("ircs"));
            if (iRcs != null) {
                return iRcs.getRcsThreads(queryParameters);
            }
        } catch (RemoteException re) {
            Rlog.e(TAG, "RcsMessageStore: Exception happened during getRcsThreads", re);
        }

        return null;
    }

    /**
     * Returns the next chunk of {@link RcsThread}s in the common storage.
     * @param continuationToken A token to continue the query to get the next chunk. This is
     *                          obtained through {@link RcsThreadQueryResult#nextChunkToken}.
     */
    @WorkerThread
    public RcsThreadQueryResult getRcsThreads(RcsThreadQueryContinuationToken continuationToken) {
        try {
            IRcs iRcs = IRcs.Stub.asInterface(ServiceManager.getService("ircs"));
            if (iRcs != null) {
                return iRcs.getRcsThreadsWithToken(continuationToken);
            }
        } catch (RemoteException re) {
            Rlog.e(TAG, "RcsMessageStore: Exception happened during getRcsThreads", re);
        }

        return null;
    }

    /**
     * Creates a new 1 to 1 thread with the given participant and persists it in the storage.
     */
    @WorkerThread
    public Rcs1To1Thread createRcs1To1Thread(RcsParticipant recipient) {
        try {
            IRcs iRcs = IRcs.Stub.asInterface(ServiceManager.getService("ircs"));
            if (iRcs != null) {
                return iRcs.createRcs1To1Thread(recipient);
            }
        } catch (RemoteException re) {
            Rlog.e(TAG, "RcsMessageStore: Exception happened during createRcs1To1Thread", re);
        }

        return null;
    }

    /**
     * Delete the {@link RcsThread} identified by the given threadId.
     * @param threadId threadId of the thread to be deleted.
     */
    @WorkerThread
    public void deleteThread(int threadId) {
        try {
            IRcs iRcs = IRcs.Stub.asInterface(ServiceManager.getService("ircs"));
            if (iRcs != null) {
                iRcs.deleteThread(threadId);
            }
        } catch (RemoteException re) {
            Rlog.e(TAG, "RcsMessageStore: Exception happened during deleteThread", re);
        }
    }

    /**
     * Creates a new participant and persists it in the storage.
     * @param canonicalAddress The defining address (e.g. phone number) of the participant.
     */
    public RcsParticipant createRcsParticipant(String canonicalAddress) {
        try {
            IRcs iRcs = IRcs.Stub.asInterface(ServiceManager.getService("ircs"));
            if (iRcs != null) {
                return iRcs.createRcsParticipant(canonicalAddress);
            }
        } catch (RemoteException re) {
            Rlog.e(TAG, "RcsMessageStore: Exception happened during createRcsParticipant", re);
        }

        return null;
    }
}
