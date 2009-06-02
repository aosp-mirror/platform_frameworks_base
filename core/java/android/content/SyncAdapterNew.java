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

import android.os.*;
import android.os.Process;
import android.accounts.Account;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @hide
 */
public abstract class SyncAdapterNew {
    private static final String TAG = "SyncAdapter";
    private final Context mContext;
    private final String mAuthority;

    /** Kernel event log tag.  Also listed in data/etc/event-log-tags. */
    public static final int LOG_SYNC_DETAILS = 2743;

    public SyncAdapterNew(Context context, String authority) {
        mContext = context;
        mAuthority = authority;
    }

    class Transport extends ISyncAdapter.Stub {
        private final AtomicInteger mNumSyncStarts = new AtomicInteger(0);
        private volatile Thread mSyncThread;

        public void startSync(ISyncContext syncContext, Account account, Bundle extras) {
            boolean alreadyInProgress;
            synchronized (this) {
                if (mSyncThread == null) {
                    mSyncThread = new Thread(
                            new SyncRunnable(new SyncContext(syncContext), account, extras),
                            "SyncAdapterThread-" + mNumSyncStarts.incrementAndGet());
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    mSyncThread.start();
                    alreadyInProgress = false;
                } else {
                    alreadyInProgress = true;
                }
            }

            if (alreadyInProgress) {
                try {
                    syncContext.onFinished(SyncResult.ALREADY_IN_PROGRESS);
                } catch (RemoteException e) {
                    // don't care if the caller is no longer around
                }
            }
        }

        public void cancelSync() {
            synchronized (this) {
                if (mSyncThread != null) {
                    mSyncThread.interrupt();
                }
            }
        }

        private class SyncRunnable implements Runnable {
            private final SyncContext mSyncContext;
            private final Account mAccount;
            private final Bundle mExtras;

            private SyncRunnable(SyncContext syncContext, Account account, Bundle extras) {
                mSyncContext = syncContext;
                mAccount = account;
                mExtras = extras;
            }

            public void run() {
                if (isCanceled()) {
                    return;
                }

                SyncResult syncResult = new SyncResult();
                ContentProviderClient provider = mAuthority != null
                        ? mContext.getContentResolver().acquireContentProviderClient(mAuthority)
                        : null;
                try {
                    SyncAdapterNew.this.performSync(mAccount, mExtras, provider, syncResult);
                } finally {
                    if (provider != null) {
                        provider.release();
                    }
                    if (!isCanceled()) {
                        mSyncContext.onFinished(syncResult);
                    }
                    mSyncThread = null;
                }
            }

            private boolean isCanceled() {
                return Thread.currentThread().isInterrupted();
            }
        }
    }

    Transport mTransport = new Transport();

    /**
     * Get the Transport object.
     */
    public final ISyncAdapter getISyncAdapter() {
        return mTransport;
    }

    /**
     * Perform a sync for this account. SyncAdapter-specific parameters may
     * be specified in extras, which is guaranteed to not be null. Invocations
     * of this method are guaranteed to be serialized.
     *
     * @param account the account that should be synced
     * @param extras SyncAdapter-specific parameters
     */
    public abstract void performSync(Account account, Bundle extras,
            ContentProviderClient provider, SyncResult syncResult);
}