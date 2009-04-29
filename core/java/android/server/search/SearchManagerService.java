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
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Handler;

/**
 * This is a simplified version of the Search Manager service.  It no longer handles
 * presentation (UI).  Its function is to maintain the map & list of "searchable" 
 * items, which provides a mapping from individual activities (where a user might have
 * invoked search) to specific searchable activities (where the search will be dispatched).
 */
public class SearchManagerService extends ISearchManager.Stub
{
        // general debugging support
    private static final String TAG = "SearchManagerService";
    private static final boolean DEBUG = false;
    
        // configuration choices
    private static final boolean IMMEDIATE_SEARCHABLES_UPDATE = true;

        // class maintenance and general shared data
    private final Context mContext;
    private final Handler mHandler;
    private boolean mSearchablesDirty;
    private Searchables mSearchables;
    
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
        if (IMMEDIATE_SEARCHABLES_UPDATE) {
            mHandler.post(mRunUpdateSearchable);
        }
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
                if (IMMEDIATE_SEARCHABLES_UPDATE) {
                    mHandler.post(mRunUpdateSearchable);
                }
                return;
            }
        }
    };
    
    /**
     * This runnable (for the main handler / UI thread) will update the searchables list.
     */
    private Runnable mRunUpdateSearchable = new Runnable() {
        public void run() {
            if (mSearchablesDirty) {
                updateSearchables();
            }
        } 
    };

    /**
     * Updates the list of searchables, either at startup or in response to
     * a package add/remove broadcast message.
     */
    private void updateSearchables() {
        mSearchables.buildSearchableList();
        mSearchablesDirty = false;
        
        // TODO SearchableInfo should be the source of truth about whether a searchable exists.
        // As it stands, if the package exists but is misconfigured in some way, then this
        // would fail, and needs to be fixed.
        ComponentName defaultSearch = new ComponentName(
                "com.android.globalsearch", 
                "com.android.globalsearch.GlobalSearch");
        
        try {
            mContext.getPackageManager().getActivityInfo(defaultSearch, 0);
        } catch (NameNotFoundException e) {
            defaultSearch = new ComponentName(
                    "com.android.googlesearch",
                    "com.android.googlesearch.GoogleSearch");
        }
        
        mSearchables.setDefaultSearchable(defaultSearch);
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
        // final check.  however we should try to avoid this, because
        // it slows down the entry into the UI.
        if (mSearchablesDirty) {
            updateSearchables();
        }
        SearchableInfo si = null;
        if (globalSearch) {
            si = mSearchables.getDefaultSearchable();
        } else {
            si = mSearchables.getSearchableInfo(launchActivity);
        }

        return si;
    }

}
