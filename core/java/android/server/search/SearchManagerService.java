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

import com.android.internal.content.PackageMonitor;

import android.app.ISearchManager;
import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.util.List;

/**
 * The search manager service handles the search UI, and maintains a registry of searchable
 * activities.
 */
public class SearchManagerService extends ISearchManager.Stub {

    // general debugging support
    private static final String TAG = "SearchManagerService";

    // Context that the service is running in.
    private final Context mContext;

    // This field is initialized lazily in getSearchables(), and then never modified.
    private Searchables mSearchables;

    private ContentObserver mGlobalSearchObserver;

    /**
     * Initializes the Search Manager service in the provided system context.
     * Only one instance of this object should be created!
     *
     * @param context to use for accessing DB, window manager, etc.
     */
    public SearchManagerService(Context context)  {
        mContext = context;
        mContext.registerReceiver(new BootCompletedReceiver(),
                new IntentFilter(Intent.ACTION_BOOT_COMPLETED));
        mGlobalSearchObserver = new GlobalSearchProviderObserver(
                mContext.getContentResolver());
    }

    private synchronized Searchables getSearchables() {
        if (mSearchables == null) {
            Log.i(TAG, "Building list of searchable activities");
            new MyPackageMonitor().register(mContext, null, true);
            mSearchables = new Searchables(mContext);
            mSearchables.buildSearchableList();
        }
        return mSearchables;
    }

    /**
     * Creates the initial searchables list after boot.
     */
    private final class BootCompletedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            new Thread() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    mContext.unregisterReceiver(BootCompletedReceiver.this);
                    getSearchables();
                }
            }.start();
        }
    }

    /**
     * Refreshes the "searchables" list when packages are added/removed.
     */
    class MyPackageMonitor extends PackageMonitor {

        @Override
        public void onSomePackagesChanged() {
            updateSearchables();
        }

        @Override
        public void onPackageModified(String pkg) {
            updateSearchables();
        }

        private void updateSearchables() {
            // Update list of searchable activities
            getSearchables().buildSearchableList();
            // Inform all listeners that the list of searchables has been updated.
            Intent intent = new Intent(SearchManager.INTENT_ACTION_SEARCHABLES_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            mContext.sendBroadcast(intent);
        }
    }

    class GlobalSearchProviderObserver extends ContentObserver {
        private final ContentResolver mResolver;

        public GlobalSearchProviderObserver(ContentResolver resolver) {
            super(null);
            mResolver = resolver;
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.SEARCH_GLOBAL_SEARCH_ACTIVITY),
                    false /* notifyDescendants */,
                    this);
        }

        @Override
        public void onChange(boolean selfChange) {
            getSearchables().buildSearchableList();
            Intent intent = new Intent(SearchManager.INTENT_GLOBAL_SEARCH_ACTIVITY_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            mContext.sendBroadcast(intent);
        }

    }

    //
    // Searchable activities API
    //

    /**
     * Returns the SearchableInfo for a given activity.
     *
     * @param launchActivity The activity from which we're launching this search.
     * @return Returns a SearchableInfo record describing the parameters of the search,
     * or null if no searchable metadata was available.
     */
    public SearchableInfo getSearchableInfo(final ComponentName launchActivity) {
        if (launchActivity == null) {
            Log.e(TAG, "getSearchableInfo(), activity == null");
            return null;
        }
        return getSearchables().getSearchableInfo(launchActivity);
    }

    /**
     * Returns a list of the searchable activities that can be included in global search.
     */
    public List<SearchableInfo> getSearchablesInGlobalSearch() {
        return getSearchables().getSearchablesInGlobalSearchList();
    }

    public List<ResolveInfo> getGlobalSearchActivities() {
        return getSearchables().getGlobalSearchActivities();
    }

    /**
     * Gets the name of the global search activity.
     */
    public ComponentName getGlobalSearchActivity() {
        return getSearchables().getGlobalSearchActivity();
    }

    /**
     * Gets the name of the web search activity.
     */
    public ComponentName getWebSearchActivity() {
        return getSearchables().getWebSearchActivity();
    }

}
