/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.app.Service;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.Trace;

import com.android.internal.annotations.GuardedBy;

import java.util.HashMap;

/**
 * Simplified @link android.content.AbstractThreadedSyncAdapter. Folds that
 * behaviour into a service to which the system can bind when requesting an
 * anonymous (providerless/accountless) sync.
 * <p>
 * In order to perform an anonymous sync operation you must extend this service,
 * implementing the abstract methods. This service must then be declared in the
 * application's manifest as usual. You can use this service for other work, however you
 * <b> must not </b> override the onBind() method unless you know what you're doing,
 * which limits the usefulness of this service for other work.
 *
 * <pre>
 * &lt;service ndroid:name=".MyAnonymousSyncService" android:permission="android.permission.SYNC" /&gt;
 * </pre>
 * Like @link android.content.AbstractThreadedSyncAdapter this service supports
 * multiple syncs at the same time. Each incoming startSync() with a unique tag
 * will spawn a thread to do the work of that sync. If startSync() is called
 * with a tag that already exists, a SyncResult.ALREADY_IN_PROGRESS is returned.
 * Remember that your service will spawn multiple threads if you schedule multiple syncs
 * at once, so if you mutate local objects you must ensure synchronization.
 */
public abstract class SyncService extends Service {

    /** SyncAdapter Instantiation that any anonymous syncs call. */
    private final AnonymousSyncAdapterImpl mSyncAdapter = new AnonymousSyncAdapterImpl();

    /** Keep track of on-going syncs, keyed by tag. */
    @GuardedBy("mLock")
    private final HashMap<Bundle, AnonymousSyncThread>
            mSyncThreads = new HashMap<Bundle, AnonymousSyncThread>();
    /** Lock object for accessing the SyncThreads HashMap. */
    private final Object mSyncThreadLock = new Object();

    @Override
    public IBinder onBind(Intent intent) {
        return mSyncAdapter.asBinder();
    }

    /** {@hide} */
    private class AnonymousSyncAdapterImpl extends IAnonymousSyncAdapter.Stub {

        @Override
        public void startSync(ISyncContext syncContext, Bundle extras) {
            // Wrap the provided Sync Context because it may go away by the time
            // we call it.
            final SyncContext syncContextClient = new SyncContext(syncContext);
            boolean alreadyInProgress = false;
            synchronized (mSyncThreadLock) {
                if (mSyncThreads.containsKey(extras)) {
                    // Don't want to call back to SyncManager while still
                    // holding lock.
                    alreadyInProgress = true;
                } else {
                    AnonymousSyncThread syncThread = new AnonymousSyncThread(
                            syncContextClient, extras);
                    mSyncThreads.put(extras, syncThread);
                    syncThread.start();
                }
            }
            if (alreadyInProgress) {
                syncContextClient.onFinished(SyncResult.ALREADY_IN_PROGRESS);
            }
        }

        /**
         * Used by the SM to cancel a specific sync using the {@link
         * com.android.server.content.SyncManager.ActiveSyncContext} as a handle.
         */
        @Override
        public void cancelSync(ISyncContext syncContext) {
            AnonymousSyncThread runningSync = null;
            synchronized (mSyncThreadLock) {
                for (AnonymousSyncThread thread : mSyncThreads.values()) {
                    if (thread.mSyncContext.getSyncContextBinder() == syncContext.asBinder()) {
                        runningSync = thread;
                        break;
                    }
                }
            }
            if (runningSync != null) {
                runningSync.interrupt();
            }
        }
    }

    /**
     * {@hide}
     * Similar to {@link android.content.AbstractThreadedSyncAdapter.SyncThread}. However while
     * the ATSA considers an already in-progress sync to be if the account provided is currently
     * syncing, this anonymous sync has no notion of account and therefore considers a sync unique
     * if the provided bundle is different.
     */
    private class AnonymousSyncThread extends Thread {
        private final SyncContext mSyncContext;
        private final Bundle mExtras;

        public AnonymousSyncThread(SyncContext syncContext, Bundle extras) {
            mSyncContext = syncContext;
            mExtras = extras;
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            Trace.traceBegin(Trace.TRACE_TAG_SYNC_MANAGER, getApplication().getPackageName());

            SyncResult syncResult = new SyncResult();
            try {
                if (isCancelled()) {
                    return;
                }
                // Run the sync based off of the provided code.
                SyncService.this.onPerformSync(mExtras, syncResult);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYNC_MANAGER);
                if (!isCancelled()) {
                    mSyncContext.onFinished(syncResult);
                }
                // Synchronize so that the assignment will be seen by other
                // threads
                // that also synchronize accesses to mSyncThreads.
                synchronized (mSyncThreadLock) {
                    mSyncThreads.remove(mExtras);
                }
            }
        }

        private boolean isCancelled() {
            return Thread.currentThread().isInterrupted();
        }
    }

    /**
     * Initiate an anonymous sync using this service. SyncAdapter-specific
     * parameters may be specified in extras, which is guaranteed to not be
     * null.
     */
    public abstract void onPerformSync(Bundle extras, SyncResult syncResult);

}
