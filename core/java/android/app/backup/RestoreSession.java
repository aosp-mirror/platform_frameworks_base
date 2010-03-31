/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app.backup;

import android.app.backup.RestoreObserver;
import android.app.backup.RestoreSet;
import android.app.backup.IRestoreObserver;
import android.app.backup.IRestoreSession;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

/**
 * Interface for managing a restore session.
 * @hide
 */
public class RestoreSession {
    static final String TAG = "RestoreSession";

    final Context mContext;
    IRestoreSession mBinder;
    RestoreObserverWrapper mObserver = null;

    /**
     * Ask the current transport what the available restore sets are.
     *
     * @param observer a RestoreObserver object whose restoreSetsAvailable() method will
     *   be called on the application's main thread in order to supply the results of
     *   the restore set lookup by the backup transport.  This parameter must not be
     *   null.
     * @return Zero on success, nonzero on error.  The observer's restoreSetsAvailable()
     *   method will only be called if this method returned zero.
     */
    public int getAvailableRestoreSets(RestoreObserver observer) {
        int err = -1;
        RestoreObserverWrapper obsWrapper = new RestoreObserverWrapper(mContext, observer);
        try {
            err = mBinder.getAvailableRestoreSets(obsWrapper);
        } catch (RemoteException e) {
            Log.d(TAG, "Can't contact server to get available sets");
        }
        return err;
    }

    /**
     * Restore the given set onto the device, replacing the current data of any app
     * contained in the restore set with the data previously backed up.
     *
     * <p>Callers must hold the android.permission.BACKUP permission to use this method.
     *
     * @return Zero on success; nonzero on error.  The observer will only receive
     *   progress callbacks if this method returned zero.
     * @param token The token from {@link #getAvailableRestoreSets()} corresponding to
     *   the restore set that should be used.
     * @param observer If non-null, this binder points to an object that will receive
     *   progress callbacks during the restore operation.
     */
    public int restoreAll(long token, RestoreObserver observer) {
        int err = -1;
        if (mObserver != null) {
            Log.d(TAG, "restoreAll() called during active restore");
            return -1;
        }
        mObserver = new RestoreObserverWrapper(mContext, observer);
        try {
            err = mBinder.restoreAll(token, mObserver);
        } catch (RemoteException e) {
            Log.d(TAG, "Can't contact server to restore");
        }
        return err;
    }

    /**
     * Restore a single application from backup.  The data will be restored from the
     * current backup dataset if the given package has stored data there, or from
     * the dataset used during the last full device setup operation if the current
     * backup dataset has no matching data.  If no backup data exists for this package
     * in either source, a nonzero value will be returned.
     *
     * @return Zero on success; nonzero on error.  The observer will only receive
     *   progress callbacks if this method returned zero.
     * @param packageName The name of the package whose data to restore.  If this is
     *   not the name of the caller's own package, then the android.permission.BACKUP
     *   permission must be held.
     * @param observer If non-null, this binder points to an object that will receive
     *   progress callbacks during the restore operation.
     */
    public int restorePackage(String packageName, RestoreObserver observer) {
        int err = -1;
        if (mObserver != null) {
            Log.d(TAG, "restorePackage() called during active restore");
            return -1;
        }
        mObserver = new RestoreObserverWrapper(mContext, observer);
        try {
            err = mBinder.restorePackage(packageName, mObserver);
        } catch (RemoteException e) {
            Log.d(TAG, "Can't contact server to restore package");
        }
        return err;
    }

    /**
     * End this restore session.  After this method is called, the RestoreSession
     * object is no longer valid.
     *
     * <p><b>Note:</b> The caller <i>must</i> invoke this method to end the restore session,
     *   even if {@link #restorePackage(String, RestoreObserver)} failed.
     */
    public void endRestoreSession() {
        try {
            mBinder.endRestoreSession();
        } catch (RemoteException e) {
            Log.d(TAG, "Can't contact server to get available sets");
        } finally {
            mBinder = null;
        }
    }

    /*
     * Nonpublic implementation here
     */

    RestoreSession(Context context, IRestoreSession binder) {
        mContext = context;
        mBinder = binder;
    }

    /*
     * We wrap incoming binder calls with a private class implementation that
     * redirects them into main-thread actions.  This serializes the restore
     * progress callbacks nicely within the usual main-thread lifecycle pattern.
     */
    private class RestoreObserverWrapper extends IRestoreObserver.Stub {
        final Handler mHandler;
        final RestoreObserver mAppObserver;

        static final int MSG_RESTORE_STARTING = 1;
        static final int MSG_UPDATE = 2;
        static final int MSG_RESTORE_FINISHED = 3;
        static final int MSG_RESTORE_SETS_AVAILABLE = 4;

        RestoreObserverWrapper(Context context, RestoreObserver appObserver) {
            mHandler = new Handler(context.getMainLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                    case MSG_RESTORE_STARTING:
                        mAppObserver.restoreStarting(msg.arg1);
                        break;
                    case MSG_UPDATE:
                        mAppObserver.onUpdate(msg.arg1, (String)msg.obj);
                        break;
                    case MSG_RESTORE_FINISHED:
                        mAppObserver.restoreFinished(msg.arg1);
                        break;
                    case MSG_RESTORE_SETS_AVAILABLE:
                        mAppObserver.restoreSetsAvailable((RestoreSet[])msg.obj);
                        break;
                    }
                }
            };
            mAppObserver = appObserver;
        }

        // Binder calls into this object just enqueue on the main-thread handler
        public void restoreSetsAvailable(RestoreSet[] result) {
            mHandler.sendMessage(
                    mHandler.obtainMessage(MSG_RESTORE_SETS_AVAILABLE, result));
        }

        public void restoreStarting(int numPackages) {
            mHandler.sendMessage(
                    mHandler.obtainMessage(MSG_RESTORE_STARTING, numPackages, 0));
        }

        public void onUpdate(int nowBeingRestored, String currentPackage) {
            mHandler.sendMessage(
                    mHandler.obtainMessage(MSG_UPDATE, nowBeingRestored, 0, currentPackage));
        }

        public void restoreFinished(int error) {
            mHandler.sendMessage(
                    mHandler.obtainMessage(MSG_RESTORE_FINISHED, error, 0));
        }
    }
}
