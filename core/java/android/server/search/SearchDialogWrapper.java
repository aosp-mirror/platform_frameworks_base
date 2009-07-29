/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.server.search;

import android.app.ISearchManagerCallback;
import android.app.SearchDialog;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

/**
 * Runs an instance of {@link SearchDialog} on its own thread.
 */
class SearchDialogWrapper
implements DialogInterface.OnCancelListener, DialogInterface.OnDismissListener {

    private static final String TAG = "SearchManagerService";
    private static final boolean DBG = false;

    private static final String SEARCH_UI_THREAD_NAME = "SearchDialog";
    private static final int SEARCH_UI_THREAD_PRIORITY =
        android.os.Process.THREAD_PRIORITY_DEFAULT;

    // Takes no arguments
    private static final int MSG_INIT = 0;
    // Takes these arguments:
    // arg1: selectInitialQuery, 0 = false, 1 = true
    // arg2: globalSearch, 0 = false, 1 = true
    // obj: searchManagerCallback
    // data[KEY_INITIAL_QUERY]: initial query
    // data[KEY_LAUNCH_ACTIVITY]: launch activity
    // data[KEY_APP_SEARCH_DATA]: app search data
    private static final int MSG_START_SEARCH = 1;
    // Takes no arguments
    private static final int MSG_STOP_SEARCH = 2;
    // arg1 is activity id
    private static final int MSG_ACTIVITY_RESUMING = 3;
    // obj is the reason
    private static final int MSG_CLOSING_SYSTEM_DIALOGS = 4;

    private static final String KEY_INITIAL_QUERY = "q";
    private static final String KEY_LAUNCH_ACTIVITY = "a";
    private static final String KEY_APP_SEARCH_DATA = "d";
    private static final String KEY_IDENT= "i";

    // Context used for getting search UI resources
    private final Context mContext;

    // Handles messages on the search UI thread.
    private final SearchDialogHandler mSearchUiThread;

    // The search UI
    SearchDialog mSearchDialog;

    // If the search UI is visible, this is the callback for the client that showed it.
    ISearchManagerCallback mCallback = null;

    // Identity of last activity that started search.
    private int mStartedIdent = 0;
    
    // Identity of currently resumed activity.
    private int mResumedIdent = 0;

    // True if we have registered our receivers.
    private boolean mReceiverRegistered;

    private volatile boolean mVisible = false;
    
    /**
     * Creates a new search dialog wrapper and a search UI thread. The search dialog itself will
     * be created some asynchronously on the search UI thread.
     *
     * @param context Context used for getting search UI resources.
     */
    public SearchDialogWrapper(Context context) {
        mContext = context;

        // Create the search UI thread
        HandlerThread t = new HandlerThread(SEARCH_UI_THREAD_NAME, SEARCH_UI_THREAD_PRIORITY);
        t.start();
        mSearchUiThread = new SearchDialogHandler(t.getLooper());

        // Create search UI on the search UI thread
        mSearchUiThread.sendEmptyMessage(MSG_INIT);
    }

    public boolean isVisible() {
        return mVisible;
    }

    /**
     * Initializes the search UI.
     * Must be called from the search UI thread.
     */
    private void init() {
        mSearchDialog = new SearchDialog(mContext);
        mSearchDialog.setOnCancelListener(this);
        mSearchDialog.setOnDismissListener(this);
    }

    private void registerBroadcastReceiver() {
        if (!mReceiverRegistered) {
            IntentFilter filter = new IntentFilter(
                    Intent.ACTION_CONFIGURATION_CHANGED);
            mContext.registerReceiver(mBroadcastReceiver, filter, null,
                    mSearchUiThread);
            mReceiverRegistered = true;
        }
    }

    private void unregisterBroadcastReceiver() {
        if (mReceiverRegistered) {
            mContext.unregisterReceiver(mBroadcastReceiver);
            mReceiverRegistered = false;
        }
    }

    /**
     * Closes the search dialog when requested by the system (e.g. when a phone call comes in).
     */
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                if (DBG) debug(Intent.ACTION_CONFIGURATION_CHANGED);
                performOnConfigurationChanged();
            }
        }
    };

    //
    // External API
    //

    /**
     * Launches the search UI.
     * Can be called from any thread.
     *
     * @see SearchManager#startSearch(String, boolean, ComponentName, Bundle, boolean)
     */
    public void startSearch(final String initialQuery,
            final boolean selectInitialQuery,
            final ComponentName launchActivity,
            final Bundle appSearchData,
            final boolean globalSearch,
            final ISearchManagerCallback searchManagerCallback,
            int ident) {
        if (DBG) debug("startSearch()");
        Message msg = Message.obtain();
        msg.what = MSG_START_SEARCH;
        msg.arg1 = selectInitialQuery ? 1 : 0;
        msg.arg2 = globalSearch ? 1 : 0;
        msg.obj = searchManagerCallback;
        Bundle msgData = msg.getData();
        msgData.putString(KEY_INITIAL_QUERY, initialQuery);
        msgData.putParcelable(KEY_LAUNCH_ACTIVITY, launchActivity);
        msgData.putBundle(KEY_APP_SEARCH_DATA, appSearchData);
        msgData.putInt(KEY_IDENT, ident);
        mSearchUiThread.sendMessage(msg);
        // be a little more eager in setting this so isVisible will return the correct value if
        // called immediately after startSearch
        mVisible = true;
    }

    /**
     * Cancels the search dialog.
     * Can be called from any thread.
     */
    public void stopSearch() {
        if (DBG) debug("stopSearch()");
        mSearchUiThread.sendEmptyMessage(MSG_STOP_SEARCH);
        // be a little more eager in setting this so isVisible will return the correct value if
        // called immediately after stopSearch
        mVisible = false;
    }

    /**
     * Updates the currently resumed activity.
     * Can be called from any thread.
     */
    public void activityResuming(int ident) {
        if (DBG) debug("activityResuming(ident=" + ident + ")");
        Message msg = Message.obtain();
        msg.what = MSG_ACTIVITY_RESUMING;
        msg.arg1 = ident;
        mSearchUiThread.sendMessage(msg);
    }

    /**
     * Handles closing of system windows/dialogs
     * Can be called from any thread.
     */
    public void closingSystemDialogs(String reason) {
        if (DBG) debug("closingSystemDialogs(reason=" + reason + ")");
        Message msg = Message.obtain();
        msg.what = MSG_CLOSING_SYSTEM_DIALOGS;
        msg.obj = reason;
        mSearchUiThread.sendMessage(msg);
    }

    //
    // Implementation methods that run on the search UI thread
    //

    private class SearchDialogHandler extends Handler {

        public SearchDialogHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_INIT:
                    init();
                    break;
                case MSG_START_SEARCH:
                    handleStartSearchMessage(msg);
                    break;
                case MSG_STOP_SEARCH:
                    performStopSearch();
                    break;
                case MSG_ACTIVITY_RESUMING:
                    performActivityResuming(msg.arg1);
                    break;
                case MSG_CLOSING_SYSTEM_DIALOGS:
                    performClosingSystemDialogs((String)msg.obj);
                    break;
            }
        }

        private void handleStartSearchMessage(Message msg) {
            Bundle msgData = msg.getData();
            String initialQuery = msgData.getString(KEY_INITIAL_QUERY);
            boolean selectInitialQuery = msg.arg1 != 0;
            ComponentName launchActivity =
                    (ComponentName) msgData.getParcelable(KEY_LAUNCH_ACTIVITY);
            Bundle appSearchData = msgData.getBundle(KEY_APP_SEARCH_DATA);
            boolean globalSearch = msg.arg2 != 0;
            ISearchManagerCallback searchManagerCallback = (ISearchManagerCallback) msg.obj;
            int ident = msgData.getInt(KEY_IDENT);
            performStartSearch(initialQuery, selectInitialQuery, launchActivity,
                    appSearchData, globalSearch, searchManagerCallback, ident);
        }

    }

    /**
     * Actually launches the search UI.
     * This must be called on the search UI thread.
     */
    void performStartSearch(String initialQuery,
            boolean selectInitialQuery,
            ComponentName launchActivity,
            Bundle appSearchData,
            boolean globalSearch,
            ISearchManagerCallback searchManagerCallback,
            int ident) {
        if (DBG) debug("performStartSearch()");

        registerBroadcastReceiver();
        mCallback = searchManagerCallback;

        // clean up any hidden dialog that we were waiting to resume
        if (mStartedIdent != 0) {
            mSearchDialog.dismiss();
        }

        mStartedIdent = ident;
        if (DBG) Log.v(TAG, "******************* DIALOG: start");

        mSearchDialog.show(initialQuery, selectInitialQuery, launchActivity, appSearchData,
                globalSearch);
        mVisible = true;
    }

    /**
     * Actually cancels the search UI.
     * This must be called on the search UI thread.
     */
    void performStopSearch() {
        if (DBG) debug("performStopSearch()");
        if (DBG) Log.v(TAG, "******************* DIALOG: cancel");
        mSearchDialog.cancel();
        mVisible = false;
        mStartedIdent = 0;
    }

    /**
     * Updates the resumed activity
     * This must be called on the search UI thread.
     */
    void performActivityResuming(int ident) {
        if (DBG) debug("performResumingActivity(): mStartedIdent="
                + mStartedIdent + ", resuming: " + ident);
        this.mResumedIdent = ident;
        if (mStartedIdent != 0) {
            if (mStartedIdent == mResumedIdent) {
                // we are resuming into the activity where we previously hid the dialog, bring it
                // back
                if (DBG) Log.v(TAG, "******************* DIALOG: show");
                mSearchDialog.show();
                mVisible = true;
            } else {
                // resuming into some other activity; hide ourselves in case we ever come back
                // so we can show ourselves quickly again
                if (DBG) Log.v(TAG, "******************* DIALOG: hide");
                mSearchDialog.hide();
                mVisible = false;
            }
        }
    }

    /**
     * Updates due to system dialogs being closed
     * This must be called on the search UI thread.
     */
    void performClosingSystemDialogs(String reason) {
        if (DBG) debug("performClosingSystemDialogs(): mStartedIdent="
                + mStartedIdent + ", reason: " + reason);
        if (!"search".equals(reason)) {
            if (DBG) debug(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            performStopSearch();
        }
    }

    /**
     * Must be called from the search UI thread.
     */
    void performOnConfigurationChanged() {
        if (DBG) debug("performOnConfigurationChanged()");
        mSearchDialog.onConfigurationChanged();
    }

    /**
     * Called by {@link SearchDialog} when it goes away.
     */
    public void onDismiss(DialogInterface dialog) {
        if (DBG) debug("onDismiss()");
        mStartedIdent = 0;
        mVisible = false;
        callOnDismiss();

        // we don't need the callback anymore, release it
        mCallback = null;
        unregisterBroadcastReceiver();
    }


    /**
     * Called by {@link SearchDialog} when the user or activity cancels search.
     * Whenever this method is called, {@link #onDismiss} is always called afterwards.
     */
    public void onCancel(DialogInterface dialog) {
        if (DBG) debug("onCancel()");
        callOnCancel();
    }

    private void callOnDismiss() {
        if (mCallback == null) return;
        try {
            // should be safe to do on the search UI thread, since it's a oneway interface
            mCallback.onDismiss();
        } catch (DeadObjectException ex) {
            // The process that hosted the callback has died, do nothing
        } catch (RemoteException ex) {
            Log.e(TAG, "onDismiss() failed: " + ex);
        }
    }

    private void callOnCancel() {
        if (mCallback != null) {
            try {
                // should be safe to do on the search UI thread, since it's a oneway interface
                mCallback.onCancel();
            } catch (DeadObjectException ex) {
                // The process that hosted the callback has died, do nothing
            } catch (RemoteException ex) {
                Log.e(TAG, "onCancel() failed: " + ex);
            }
        }
    }

    private static void debug(String msg) {
        Thread thread = Thread.currentThread();
        Log.d(TAG, msg + " (" + thread.getName() + "-" + thread.getId() + ")");
    }
}
