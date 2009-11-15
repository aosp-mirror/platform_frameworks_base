/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content;

import android.os.Bundle;
import android.os.RemoteException;
import android.accounts.Account;

/**
 * @hide
 */
public abstract class SyncAdapter {
    private static final String TAG = "SyncAdapter";

    /** Kernel event log tag.  Also listed in data/etc/event-log-tags. */
    public static final int LOG_SYNC_DETAILS = 2743;

    class Transport extends ISyncAdapter.Stub {
        public void startSync(ISyncContext syncContext, String authority, Account account,
                Bundle extras) throws RemoteException {
            SyncAdapter.this.startSync(new SyncContext(syncContext), account, authority, extras);
        }

        public void cancelSync(ISyncContext syncContext) throws RemoteException {
            SyncAdapter.this.cancelSync();
        }
    }

    Transport mTransport = new Transport();

    /**
     * Get the Transport object.
     */
    public final ISyncAdapter getISyncAdapter()
    {
        return mTransport;
    }

    /**
     * Initiate a sync for this account. SyncAdapter-specific parameters may
     * be specified in extras, which is guaranteed to not be null. IPC invocations
     * of this method and cancelSync() are guaranteed to be serialized.
     *
     * @param syncContext the ISyncContext used to indicate the progress of the sync. When
     *   the sync is finished (successfully or not) ISyncContext.onFinished() must be called.
     * @param account the account that should be synced
     * @param authority the authority if the sync request
     * @param extras SyncAdapter-specific parameters
     */
    public abstract void startSync(SyncContext syncContext, Account account, String authority, 
            Bundle extras);

    /**
     * Cancel the most recently initiated sync. Due to race conditions, this may arrive
     * after the ISyncContext.onFinished() for that sync was called. IPC invocations
     * of this method and startSync() are guaranteed to be serialized.
     */
    public abstract void cancelSync();
}
