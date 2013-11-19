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
import android.util.SparseArray;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

/**
 * Simplified @link android.content.AbstractThreadedSyncAdapter. Folds that
 * behaviour into a service to which the system can bind when requesting an
 * anonymous (providerless/accountless) sync.
 * <p>
 * In order to perform an anonymous sync operation you must extend this service, implementing the
 * abstract methods. This service must be declared in the application's manifest as usual. You
 * can use this service for other work, however you <b> must not </b> override the onBind() method
 * unless you know what you're doing, which limits the usefulness of this service for other work.
 * <p>A {@link SyncService} can either be active or inactive. Different to an
 * {@link AbstractThreadedSyncAdapter}, there is no
 * {@link ContentResolver#setSyncAutomatically(android.accounts.Account account, String provider, boolean sync)},
 * as well as no concept of initialisation (you can handle your own if needed).
 *
 * <pre>
 * &lt;service android:name=".MySyncService"/&gt;
 * </pre>
 * Like @link android.content.AbstractThreadedSyncAdapter this service supports
 * multiple syncs at the same time. Each incoming startSync() with a unique tag
 * will spawn a thread to do the work of that sync. If startSync() is called
 * with a tag that already exists, a SyncResult.ALREADY_IN_PROGRESS is returned.
 * Remember that your service will spawn multiple threads if you schedule multiple syncs
 * at once, so if you mutate local objects you must ensure synchronization.
 */
public abstract class SyncService extends Service {
    private static final String TAG = "SyncService";

    private final SyncAdapterImpl mSyncAdapter = new SyncAdapterImpl();

    /** Keep track of on-going syncs, keyed by bundle. */
    @GuardedBy("mSyncThreadLock")
    private final SparseArray<SyncThread>
            mSyncThreads = new SparseArray<SyncThread>();
    /** Lock object for accessing the SyncThreads HashMap. */
    private final Object mSyncThreadLock = new Object();
    /**
     * Default key for if this sync service does not support parallel operations. Currently not
     * sure if null keys will make it into the ArrayMap for KLP, so keeping our default for now.
     */
    private static final int KEY_DEFAULT = 0;
    /** Identifier for this sync service. */
    private ComponentName mServiceComponent;

    /** {@hide} */
    public IBinder onBind(Intent intent) {
        mServiceComponent = new ComponentName(this, getClass());
        return mSyncAdapter.asBinder();
    }

    /** {@hide} */
    private class SyncAdapterImpl extends ISyncServiceAdapter.Stub {
        @Override
        public void startSync(ISyncContext syncContext, Bundle extras) {
            // Wrap the provided Sync Context because it may go away by the time
            // we call it.
            final SyncContext syncContextClient = new SyncContext(syncContext);
            boolean alreadyInProgress = false;
            final int extrasAsKey = extrasToKey(extras);
            synchronized (mSyncThreadLock) {
                if (mSyncThreads.get(extrasAsKey) == null) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "starting sync for : " + mServiceComponent);
                    }
                    // Start sync.
                    SyncThread syncThread = new SyncThread(syncContextClient, extras);
                    mSyncThreads.put(extrasAsKey, syncThread);
                    syncThread.start();
                } else {
                    // Don't want to call back to SyncManager while still
                    // holding lock.
                    alreadyInProgress = true;
                }
            }
            if (alreadyInProgress) {
                syncContextClient.onFinished(SyncResult.ALREADY_IN_PROGRESS);
            }
        }

        /**
         * Used by the SM to cancel a specific sync using the
         * com.android.server.content.SyncManager.ActiveSyncContext as a handle.
         */
        @Override
        public void cancelSync(ISyncContext syncContext) {
            SyncThread runningSync = null;
            synchronized (mSyncThreadLock) {
                for (int i = 0; i < mSyncThreads.size(); i++) {
                    SyncThread thread = mSyncThreads.valueAt(i);
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
     * 
     * @param extras Bundle for which to compute hash
     * @return an integer hash that is equal to that of another bundle if they both contain the
     * same key -> value mappings, however, not necessarily in order.
     * Based on the toString() representation of the value mapped.
     */
    private int extrasToKey(Bundle extras) {
        int hash = KEY_DEFAULT; // Empty bundle, or no parallel operations enabled.
        if (parallelSyncsEnabled()) {
            for (String key : extras.keySet()) {
                String mapping = key + " " + extras.get(key).toString();
                hash += mapping.hashCode();
            }
        }
        return hash;
    }

    /**
     * {@hide}
     * Similar to {@link android.content.AbstractThreadedSyncAdapter.SyncThread}. However while
     * the ATSA considers an already in-progress sync to be if the account provided is currently
     * syncing, this anonymous sync has no notion of account and considers a sync unique if the
     * provided bundle is different.
     */
    private class SyncThread extends Thread {
        private final SyncContext mSyncContext;
        private final Bundle mExtras;
        private final int mThreadsKey;

        public SyncThread(SyncContext syncContext, Bundle extras) {
            mSyncContext = syncContext;
            mExtras = extras;
            mThreadsKey = extrasToKey(extras);
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            Trace.traceBegin(Trace.TRACE_TAG_SYNC_MANAGER, getApplication().getPackageName());

            SyncResult syncResult = new SyncResult();
            try {
                if (isCancelled()) return;
                // Run the sync.
                SyncService.this.onPerformSync(mExtras, syncResult);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYNC_MANAGER);
                if (!isCancelled()) {
                    mSyncContext.onFinished(syncResult);
                }
                // Synchronize so that the assignment will be seen by other
                // threads that also synchronize accesses to mSyncThreads.
                synchronized (mSyncThreadLock) {
                    mSyncThreads.remove(mThreadsKey);
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

    /**
     * Override this function to indicated whether you want to support parallel syncs.
     * <p>If you override and return true multiple threads will be spawned within your Service to
     * handle each concurrent sync request.
     *
     * @return false to indicate that this service does not support parallel operations by default.
     */
    protected boolean parallelSyncsEnabled() {
        return false;
    }
}
