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
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import java.util.List;

/**
 * The search manager service handles the search UI, and maintains a registry of searchable
 * activities.
 */
public class SearchManagerService extends ISearchManager.Stub {

    // general debugging support
    private static final String TAG = "SearchManagerService";
    private static final boolean DBG = false;

    // Context that the service is running in.
    private final Context mContext;

    // This field is initialized in initialize(), and then never modified.
    // It is volatile since it can be accessed by multiple threads.
    private volatile Searchables mSearchables;

    // This field is initialized in initialize(), and then never modified.
    // It is volatile since it can be accessed by multiple threads.
    private volatile SearchDialogWrapper mSearchDialog;

    /**
     * Initializes the Search Manager service in the provided system context.
     * Only one instance of this object should be created!
     *
     * @param context to use for accessing DB, window manager, etc.
     */
    public SearchManagerService(Context context)  {
        mContext = context;
        // call initialize() after all pending actions on the main system thread have finished
        new Handler().post(new Runnable() {
            public void run() {
                initialize();
            }
        });
    }

    /**
     * Initializes the search UI and the list of searchable activities.
     */
    void initialize() {
        mSearchables = createSearchables();
        mSearchDialog = new SearchDialogWrapper(mContext);
    }

    private Searchables createSearchables() {
        Searchables searchables = new Searchables(mContext);
        searchables.buildSearchableList();

        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        mContext.registerReceiver(mPackageChangedReceiver, packageFilter);

        return searchables;
    }

    /**
     * Refreshes the "searchables" list when packages are added/removed.
     */
    private BroadcastReceiver mPackageChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (Intent.ACTION_PACKAGE_ADDED.equals(action) ||
                    Intent.ACTION_PACKAGE_REMOVED.equals(action) ||
                    Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                if (DBG) Log.d(TAG, "Got " + action);
                // Dismiss search dialog, since the search context may no longer be valid
                mSearchDialog.stopSearch();
                // Update list of searchable activities
                mSearchables.buildSearchableList();
                broadcastSearchablesChanged();
            }
        }
    };

    /**
     * Informs all listeners that the list of searchables has been updated.
     */
    void broadcastSearchablesChanged() {
        mContext.sendBroadcast(
                new Intent(SearchManager.INTENT_ACTION_SEARCHABLES_CHANGED));
    }

    //
    // Searchable activities API
    //

    /**
     * Returns the SearchableInfo for a given activity.
     *
     * @param launchActivity The activity from which we're launching this search.
     * @param globalSearch If false, this will only launch the search that has been specifically
     * defined by the application (which is usually defined as a local search).  If no default
     * search is defined in the current application or activity, no search will be launched.
     * If true, this will always launch a platform-global (e.g. web-based) search instead.
     * @return Returns a SearchableInfo record describing the parameters of the search,
     * or null if no searchable metadata was available.
     */
    public SearchableInfo getSearchableInfo(final ComponentName launchActivity,
            final boolean globalSearch) {
        if (mSearchables == null) return null;
        if (globalSearch) {
            return mSearchables.getDefaultSearchable();
        } else {
            if (launchActivity == null) {
                Log.e(TAG, "getSearchableInfo(), activity == null");
                return null;
            }
            return mSearchables.getSearchableInfo(launchActivity);
        }
    }

    /**
     * Returns a list of the searchable activities that can be included in global search.
     */
    public List<SearchableInfo> getSearchablesInGlobalSearch() {
        if (mSearchables == null) return null;
        return mSearchables.getSearchablesInGlobalSearchList();
    }

    /**
     * Returns a list of the searchable activities that handle web searches.
     * Can be called from any thread.
     */
    public List<SearchableInfo> getSearchablesForWebSearch() {
        if (mSearchables == null) return null;
        return mSearchables.getSearchablesForWebSearchList();
    }

    /**
     * Returns the default searchable activity for web searches.
     * Can be called from any thread.
     */
    public SearchableInfo getDefaultSearchableForWebSearch() {
        if (mSearchables == null) return null;
        return mSearchables.getDefaultSearchableForWebSearch();
    }

    /**
     * Sets the default searchable activity for web searches.
     * Can be called from any thread.
     */
    public void setDefaultWebSearch(final ComponentName component) {
        if (mSearchables == null) return;
        mSearchables.setDefaultWebSearch(component);
        broadcastSearchablesChanged();
    }

    // Search UI API

    /**
     * Launches the search UI. Can be called from any thread.
     *
     * @see SearchManager#startSearch(String, boolean, ComponentName, Bundle, boolean)
     */
    public void startSearch(String initialQuery,
            boolean selectInitialQuery,
            ComponentName launchActivity,
            Bundle appSearchData,
            boolean globalSearch,
            ISearchManagerCallback searchManagerCallback) {
        if (mSearchDialog == null) return;
        mSearchDialog.startSearch(initialQuery,
                selectInitialQuery,
                launchActivity,
                appSearchData,
                globalSearch,
                searchManagerCallback);
    }

    /**
     * Cancels the search dialog. Can be called from any thread.
     */
    public void stopSearch() {
        if (mSearchDialog == null) return;
        mSearchDialog.stopSearch();
    }

}
