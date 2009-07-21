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

import android.app.ISearchManager;
import android.app.ISearchManagerCallback;
import android.app.SearchDialog;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * This is a simplified version of the Search Manager service.  It no longer handles
 * presentation (UI).  Its function is to maintain the map & list of "searchable"
 * items, which provides a mapping from individual activities (where a user might have
 * invoked search) to specific searchable activities (where the search will be dispatched).
 */
public class SearchManagerService extends ISearchManager.Stub
        implements DialogInterface.OnCancelListener, DialogInterface.OnDismissListener
{
        // general debugging support
    private static final String TAG = "SearchManagerService";
    private static final boolean DBG = false;

        // class maintenance and general shared data
    private final Context mContext;
    private final Handler mHandler;
    private boolean mSearchablesDirty;
    private final Searchables mSearchables;

    final SearchDialog mSearchDialog;
    ISearchManagerCallback mCallback = null;

    private final boolean mDisabledOnBoot;

    private static final String DISABLE_SEARCH_PROPERTY = "dev.disablesearchdialog";

    /**
     * Initializes the Search Manager service in the provided system context.
     * Only one instance of this object should be created!
     *
     * @param context to use for accessing DB, window manager, etc.
     */
    public SearchManagerService(Context context)  {
        mContext = context;
        mHandler = new Handler();
        mSearchablesDirty = true;
        mSearchables = new Searchables(context);
        mSearchDialog = new SearchDialog(context);
        mSearchDialog.setOnCancelListener(this);
        mSearchDialog.setOnDismissListener(this);

        // Setup the infrastructure for updating and maintaining the list
        // of searchable activities.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mIntentReceiver, filter, null, mHandler);

        // After startup settles down, preload the searchables list,
        // which will reduce the delay when the search UI is invoked.
        mHandler.post(mRunUpdateSearchable);

        // allows disabling of search dialog for stress testing runs
        mDisabledOnBoot = !TextUtils.isEmpty(SystemProperties.get(DISABLE_SEARCH_PROPERTY));
    }

    /**
     * Listens for intent broadcasts.
     *
     * The primary purpose here is to refresh the "searchables" list
     * if packages are added/removed.
     */
    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            // First, test for intents that matter at any time
            if (action.equals(Intent.ACTION_PACKAGE_ADDED) ||
                action.equals(Intent.ACTION_PACKAGE_REMOVED) ||
                action.equals(Intent.ACTION_PACKAGE_CHANGED)) {
                mSearchablesDirty = true;
                mHandler.post(mRunUpdateSearchable);
                return;
            }
        }
    };

    /**
     * This runnable (for the main handler / UI thread) will update the searchables list.
     */
    private Runnable mRunUpdateSearchable = new Runnable() {
        public void run() {
            updateSearchablesIfDirty();
        }
    };

    /**
     * Updates the list of searchables, either at startup or in response to
     * a package add/remove broadcast message.
     */
    private void updateSearchables() {
        if (DBG) debug("updateSearchables()");
        mSearchables.buildSearchableList();
        mSearchablesDirty = false;
    }

    /**
     * Updates the list of searchables if needed.
     */
    private void updateSearchablesIfDirty() {
        if (mSearchablesDirty) {
            updateSearchables();
        }
    }

    /**
     * Returns the SearchableInfo for a given activity
     *
     * @param launchActivity The activity from which we're launching this search.
     * @param globalSearch If false, this will only launch the search that has been specifically
     * defined by the application (which is usually defined as a local search).  If no default
     * search is defined in the current application or activity, no search will be launched.
     * If true, this will always launch a platform-global (e.g. web-based) search instead.
     * @return Returns a SearchableInfo record describing the parameters of the search,
     * or null if no searchable metadata was available.
     */
    public SearchableInfo getSearchableInfo(ComponentName launchActivity, boolean globalSearch) {
        updateSearchablesIfDirty();
        SearchableInfo si = null;
        if (globalSearch) {
            si = mSearchables.getDefaultSearchable();
        } else {
            if (launchActivity == null) {
                Log.e(TAG, "getSearchableInfo(), activity == null");
                return null;
            }
            si = mSearchables.getSearchableInfo(launchActivity);
        }

        return si;
    }

    /**
     * Returns a list of the searchable activities that can be included in global search.
     */
    public List<SearchableInfo> getSearchablesInGlobalSearch() {
        updateSearchablesIfDirty();
        return mSearchables.getSearchablesInGlobalSearchList();
    }
    /**
     * Launches the search UI on the main thread of the service.
     *
     * @see SearchManager#startSearch(String, boolean, ComponentName, Bundle, boolean)
     */
    public void startSearch(final String initialQuery,
            final boolean selectInitialQuery,
            final ComponentName launchActivity,
            final Bundle appSearchData,
            final boolean globalSearch,
            final ISearchManagerCallback searchManagerCallback) {
        if (DBG) debug("startSearch()");
        Runnable task = new Runnable() {
            public void run() {
                performStartSearch(initialQuery,
                        selectInitialQuery,
                        launchActivity,
                        appSearchData,
                        globalSearch,
                        searchManagerCallback);
            }
        };
        mHandler.post(task);
    }

    /**
     * Actually launches the search. This must be called on the service UI thread.
     */
    /*package*/ void performStartSearch(String initialQuery,
            boolean selectInitialQuery,
            ComponentName launchActivity,
            Bundle appSearchData,
            boolean globalSearch,
            ISearchManagerCallback searchManagerCallback) {
        if (DBG) debug("performStartSearch()");

        if (mDisabledOnBoot) {
            Log.d(TAG, "ignoring start search request because " + DISABLE_SEARCH_PROPERTY
                    + " system property is set.");
            return;
        }

        mSearchDialog.show(initialQuery, selectInitialQuery, launchActivity, appSearchData,
                globalSearch);
        if (searchManagerCallback != null) {
            mCallback = searchManagerCallback;
        }
    }

    /**
     * Cancels the search dialog. Can be called from any thread.
     */
    public void stopSearch() {
        if (DBG) debug("stopSearch()");
        mHandler.post(new Runnable() {
            public void run() {
                performStopSearch();
            }
        });
    }

    /**
     * Cancels the search dialog. Must be called from the service UI thread.
     */
    /*package*/ void performStopSearch() {
        if (DBG) debug("performStopSearch()");
        mSearchDialog.cancel();
    }

    /**
     * Determines if the Search UI is currently displayed.
     *
     * @see SearchManager#isVisible()
     */
    public boolean isVisible() {
        return postAndWait(mIsShowing, false, "isShowing()");
    }

    private final Callable<Boolean> mIsShowing = new Callable<Boolean>() {
        public Boolean call() {
            return mSearchDialog.isShowing();
        }
    };

    public Bundle onSaveInstanceState() {
        return postAndWait(mOnSaveInstanceState, null, "onSaveInstanceState()");
    }

    private final Callable<Bundle> mOnSaveInstanceState = new Callable<Bundle>() {
        public Bundle call() {
            if (mSearchDialog.isShowing()) {
                return mSearchDialog.onSaveInstanceState();
            } else {
                return null;
            }
        }
    };

    public void onRestoreInstanceState(final Bundle searchDialogState) {
        if (searchDialogState != null) {
            mHandler.post(new Runnable() {
                public void run() {
                    mSearchDialog.onRestoreInstanceState(searchDialogState);
                }
            });
        }
    }

    public void onConfigurationChanged(final Configuration newConfig) {
        mHandler.post(new Runnable() {
            public void run() {
                if (mSearchDialog.isShowing()) {
                    mSearchDialog.onConfigurationChanged(newConfig);
                }
            }
        });
    }

    /**
     * Called by {@link SearchDialog} when it goes away.
     */
    public void onDismiss(DialogInterface dialog) {
        if (DBG) debug("onDismiss()");
        if (mCallback != null) {
            try {
                mCallback.onDismiss();
            } catch (RemoteException ex) {
                Log.e(TAG, "onDismiss() failed: " + ex);
            }
        }
    }

    /**
     * Called by {@link SearchDialog} when the user or activity cancels search.
     * When this is called, {@link #onDismiss} is called too.
     */
    public void onCancel(DialogInterface dialog) {
        if (DBG) debug("onCancel()");
        if (mCallback != null) {
            try {
                mCallback.onCancel();
            } catch (RemoteException ex) {
                Log.e(TAG, "onCancel() failed: " + ex);
            }
        }
    }

    /**
     * Returns a list of the searchable activities that handle web searches.
     */
    public List<SearchableInfo> getSearchablesForWebSearch() {
        updateSearchablesIfDirty();
        return mSearchables.getSearchablesForWebSearchList();
    }

    /**
     * Returns the default searchable activity for web searches.
     */
    public SearchableInfo getDefaultSearchableForWebSearch() {
        updateSearchablesIfDirty();
        return mSearchables.getDefaultSearchableForWebSearch();
    }

    /**
     * Sets the default searchable activity for web searches.
     */
    public void setDefaultWebSearch(ComponentName component) {
        mSearchables.setDefaultWebSearch(component);
    }

    /**
     * Runs an operation on the handler for the service, blocks until it returns,
     * and returns the value returned by the operation.
     *
     * @param <V> Return value type.
     * @param callable Operation to run.
     * @param errorResult Value to return if the operations throws an exception.
     * @param name Operation name to include in error log messages.
     * @return The value returned by the operation.
     */
    private <V> V postAndWait(Callable<V> callable, V errorResult, String name) {
        FutureTask<V> task = new FutureTask<V>(callable);
        mHandler.post(task);
        try {
            return task.get();
        } catch (InterruptedException ex) {
            Log.e(TAG, "Error calling " + name + ": " + ex);
            return errorResult;
        } catch (ExecutionException ex) {
            Log.e(TAG, "Error calling " + name + ": " + ex);
            return errorResult;
        }
    }

    private static void debug(String msg) {
        Thread thread = Thread.currentThread();
        Log.d(TAG, msg + " (" + thread.getName() + "-" + thread.getId() + ")");
    }

}
