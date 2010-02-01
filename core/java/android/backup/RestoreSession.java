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

package android.backup;

import android.backup.IRestoreSession;
import android.backup.RestoreObserver;
import android.backup.RestoreSet;
import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

/**
 * Interface for applications to use when managing a restore session.
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
     * @return A bundle containing two elements:  an int array under the key
     *   "tokens" whose entries are a transport-private identifier for each backup set;
     *   and a String array under the key "names" whose entries are the user-meaningful
     *   text corresponding to the backup sets at each index in the tokens array.
     *   On error, returns null.
     */
    public RestoreSet[] getAvailableRestoreSets() {
        try {
            return mBinder.getAvailableRestoreSets();
        } catch (RemoteException e) {
            Log.d(TAG, "Can't contact server to get available sets");
            return null;
        }
    }

    /**
     * Restore the given set onto the device, replacing the current data of any app
     * contained in the restore set with the data previously backed up.
     *
     * @return Zero on success; nonzero on error.  The observer will only receive
     *   progress callbacks if this method returned zero.
     * @param token The token from {@link #getAvailableRestoreSets()} corresponding to
     *   the restore set that should be used.
     * @param observer If non-null, this argument points to an object that will receive
     *   progress callbacks during the restore operation. These callbacks will occur
     *   on the main thread of the application.
     */
    public int performRestore(long token, RestoreObserver observer) {
        int err = -1;
        if (mObserver != null) {
            Log.d(TAG, "performRestore() called during active restore");
            return -1;
        }
        mObserver = new RestoreObserverWrapper(mContext, observer);
        try {
            err = mBinder.performRestore(token, mObserver);
        } catch (RemoteException e) {
            Log.d(TAG, "Can't contact server to perform restore");
        }
        return err;
    }

    /**
     * End this restore session.  After this method is called, the RestoreSession
     * object is no longer valid.
     *
     * <p><b>Note:</b> The caller <i>must</i> invoke this method to end the restore session,
     *   even if {@link #getAvailableRestoreSets()} or
     *   {@link #performRestore(long, RestoreObserver)} failed.
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

        RestoreObserverWrapper(Context context, RestoreObserver appObserver) {
            mHandler = new Handler(context.getMainLooper());
            mAppObserver = appObserver;
        }

        // Wrap the IRestoreObserver -> RestoreObserver callthrough in Runnables
        // posted to the app's main thread looper.
        class RestoreStartingRunnable implements Runnable {
            int mNumPackages;

            RestoreStartingRunnable(int numPackages) {
                mNumPackages = numPackages;
            }

            public void run() {
                mAppObserver.restoreStarting(mNumPackages);
            }
        }

        class OnUpdateRunnable implements Runnable {
            int mNowRestoring;

            OnUpdateRunnable(int nowRestoring) {
                mNowRestoring = nowRestoring;
            }

            public void run() {
                mAppObserver.onUpdate(mNowRestoring);
            }
        }

        class RestoreFinishedRunnable implements Runnable {
            int mError;

            RestoreFinishedRunnable(int error) {
                mError = error;
            }

            public void run() {
                mAppObserver.restoreFinished(mError);
            }
        }

        // The actual redirection code is quite simple using just the
        // above Runnable subclasses
        public void restoreStarting(int numPackages) {
            mHandler.post(new RestoreStartingRunnable(numPackages));
        }

        public void onUpdate(int nowBeingRestored) {
            mHandler.post(new OnUpdateRunnable(nowBeingRestored));
        }

        public void restoreFinished(int error) {
            mHandler.post(new RestoreFinishedRunnable(error));
        }
    }
}
