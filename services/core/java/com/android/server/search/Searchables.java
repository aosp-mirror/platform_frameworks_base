/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.server.search;

import android.app.AppGlobals;
import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.LocalServices;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * This class maintains the information about all searchable activities.
 * This is a hidden class.
 */
public class Searchables {

    private static final String LOG_TAG = "Searchables";

    // static strings used for XML lookups, etc.
    // TODO how should these be documented for the developer, in a more structured way than
    // the current long wordy javadoc in SearchManager.java ?
    private static final String MD_LABEL_DEFAULT_SEARCHABLE = "android.app.default_searchable";
    private static final String MD_SEARCHABLE_SYSTEM_SEARCH = "*";

    private Context mContext;

    private HashMap<ComponentName, SearchableInfo> mSearchablesMap = null;
    private ArrayList<SearchableInfo> mSearchablesList = null;
    private ArrayList<SearchableInfo> mSearchablesInGlobalSearchList = null;
    // Contains all installed activities that handle the global search
    // intent.
    private List<ResolveInfo> mGlobalSearchActivities;
    private ComponentName mCurrentGlobalSearchActivity = null;
    private ComponentName mWebSearchActivity = null;

    public static String GOOGLE_SEARCH_COMPONENT_NAME =
            "com.android.googlesearch/.GoogleSearch";
    public static String ENHANCED_GOOGLE_SEARCH_COMPONENT_NAME =
            "com.google.android.providers.enhancedgooglesearch/.Launcher";

    // Cache the package manager instance
    final private IPackageManager mPm;
    // User for which this Searchables caches information
    private int mUserId;

    /**
     *
     * @param context Context to use for looking up activities etc.
     */
    public Searchables (Context context, int userId) {
        mContext = context;
        mUserId = userId;
        mPm = AppGlobals.getPackageManager();
    }

    /**
     * Look up, or construct, based on the activity.
     *
     * The activities fall into three cases, based on meta-data found in
     * the manifest entry:
     * <ol>
     * <li>The activity itself implements search.  This is indicated by the
     * presence of a "android.app.searchable" meta-data attribute.
     * The value is a reference to an XML file containing search information.</li>
     * <li>A related activity implements search.  This is indicated by the
     * presence of a "android.app.default_searchable" meta-data attribute.
     * The value is a string naming the activity implementing search.  In this
     * case the factory will "redirect" and return the searchable data.</li>
     * <li>No searchability data is provided.  We return null here and other
     * code will insert the "default" (e.g. contacts) search.
     *
     * TODO: cache the result in the map, and check the map first.
     * TODO: it might make sense to implement the searchable reference as
     * an application meta-data entry.  This way we don't have to pepper each
     * and every activity.
     * TODO: can we skip the constructor step if it's a non-searchable?
     * TODO: does it make sense to plug the default into a slot here for
     * automatic return?  Probably not, but it's one way to do it.
     *
     * @param activity The name of the current activity, or null if the
     * activity does not define any explicit searchable metadata.
     */
    public SearchableInfo getSearchableInfo(ComponentName activity) {
        // Step 1.  Is the result already hashed?  (case 1)
        SearchableInfo result;
        synchronized (this) {
            result = mSearchablesMap.get(activity);
            if (result != null) {
                final PackageManagerInternal pm =
                        LocalServices.getService(PackageManagerInternal.class);
                if (pm.canAccessComponent(Binder.getCallingUid(), result.getSearchActivity(),
                        UserHandle.getCallingUserId())) {
                    return result;
                }
                return null;
            }
        }

        // Step 2.  See if the current activity references a searchable.
        // Note:  Conceptually, this could be a while(true) loop, but there's
        // no point in implementing reference chaining here and risking a loop.
        // References must point directly to searchable activities.

        ActivityInfo ai = null;
        try {
            ai = mPm.getActivityInfo(activity, PackageManager.GET_META_DATA, mUserId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting activity info " + re);
            return null;
        }
        String refActivityName = null;

        // First look for activity-specific reference
        Bundle md = ai.metaData;
        if (md != null) {
            refActivityName = md.getString(MD_LABEL_DEFAULT_SEARCHABLE);
        }
        // If not found, try for app-wide reference
        if (refActivityName == null) {
            md = ai.applicationInfo.metaData;
            if (md != null) {
                refActivityName = md.getString(MD_LABEL_DEFAULT_SEARCHABLE);
            }
        }

        // Irrespective of source, if a reference was found, follow it.
        if (refActivityName != null)
        {
            // This value is deprecated, return null
            if (refActivityName.equals(MD_SEARCHABLE_SYSTEM_SEARCH)) {
                return null;
            }
            String pkg = activity.getPackageName();
            ComponentName referredActivity;
            if (refActivityName.charAt(0) == '.') {
                referredActivity = new ComponentName(pkg, pkg + refActivityName);
            } else {
                referredActivity = new ComponentName(pkg, refActivityName);
            }

            // Now try the referred activity, and if found, cache
            // it against the original name so we can skip the check
            synchronized (this) {
                result = mSearchablesMap.get(referredActivity);
                if (result != null) {
                    mSearchablesMap.put(activity, result);
                }
            }
            if (result != null) {
                final PackageManagerInternal pm =
                        LocalServices.getService(PackageManagerInternal.class);
                if (pm.canAccessComponent(Binder.getCallingUid(), result.getSearchActivity(),
                        UserHandle.getCallingUserId())) {
                    return result;
                }
                return null;
            }
        }

        // Step 3.  None found. Return null.
        return null;

    }

    /**
     * Builds an entire list (suitable for display) of
     * activities that are searchable, by iterating the entire set of
     * ACTION_SEARCH & ACTION_WEB_SEARCH intents.
     *
     * Also clears the hash of all activities -> searches which will
     * refill as the user clicks "search".
     *
     * This should only be done at startup and again if we know that the
     * list has changed.
     *
     * TODO: every activity that provides a ACTION_SEARCH intent should
     * also provide searchability meta-data.  There are a bunch of checks here
     * that, if data is not found, silently skip to the next activity.  This
     * won't help a developer trying to figure out why their activity isn't
     * showing up in the list, but an exception here is too rough.  I would
     * like to find a better notification mechanism.
     *
     * TODO: sort the list somehow?  UI choice.
     */
    public void updateSearchableList() {
        // These will become the new values at the end of the method
        HashMap<ComponentName, SearchableInfo> newSearchablesMap
                                = new HashMap<ComponentName, SearchableInfo>();
        ArrayList<SearchableInfo> newSearchablesList
                                = new ArrayList<SearchableInfo>();
        ArrayList<SearchableInfo> newSearchablesInGlobalSearchList
                                = new ArrayList<SearchableInfo>();

        // Use intent resolver to generate list of ACTION_SEARCH & ACTION_WEB_SEARCH receivers.
        List<ResolveInfo> searchList;
        final Intent intent = new Intent(Intent.ACTION_SEARCH);

        long ident = Binder.clearCallingIdentity();
        try {
            searchList = queryIntentActivities(intent,
                    PackageManager.GET_META_DATA | PackageManager.MATCH_DEBUG_TRIAGED_MISSING);

            List<ResolveInfo> webSearchInfoList;
            final Intent webSearchIntent = new Intent(Intent.ACTION_WEB_SEARCH);
            webSearchInfoList = queryIntentActivities(webSearchIntent,
                    PackageManager.GET_META_DATA | PackageManager.MATCH_DEBUG_TRIAGED_MISSING);

            // analyze each one, generate a Searchables record, and record
            if (searchList != null || webSearchInfoList != null) {
                int search_count = (searchList == null ? 0 : searchList.size());
                int web_search_count = (webSearchInfoList == null ? 0 : webSearchInfoList.size());
                int count = search_count + web_search_count;
                for (int ii = 0; ii < count; ii++) {
                    // for each component, try to find metadata
                    ResolveInfo info = (ii < search_count)
                            ? searchList.get(ii)
                            : webSearchInfoList.get(ii - search_count);
                    ActivityInfo ai = info.activityInfo;
                    // Check first to avoid duplicate entries.
                    if (newSearchablesMap.get(new ComponentName(ai.packageName, ai.name)) == null) {
                        SearchableInfo searchable = SearchableInfo.getActivityMetaData(mContext, ai,
                                mUserId);
                        if (searchable != null) {
                            newSearchablesList.add(searchable);
                            newSearchablesMap.put(searchable.getSearchActivity(), searchable);
                            if (searchable.shouldIncludeInGlobalSearch()) {
                                newSearchablesInGlobalSearchList.add(searchable);
                            }
                        }
                    }
                }
            }

            List<ResolveInfo> newGlobalSearchActivities = findGlobalSearchActivities();

            // Find the global search activity
            ComponentName newGlobalSearchActivity = findGlobalSearchActivity(
                    newGlobalSearchActivities);

            // Find the web search activity
            ComponentName newWebSearchActivity = findWebSearchActivity(newGlobalSearchActivity);

            // Store a consistent set of new values
            synchronized (this) {
                mSearchablesMap = newSearchablesMap;
                mSearchablesList = newSearchablesList;
                mSearchablesInGlobalSearchList = newSearchablesInGlobalSearchList;
                mGlobalSearchActivities = newGlobalSearchActivities;
                mCurrentGlobalSearchActivity = newGlobalSearchActivity;
                mWebSearchActivity = newWebSearchActivity;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Returns a sorted list of installed search providers as per
     * the following heuristics:
     *
     * (a) System apps are given priority over non system apps.
     * (b) Among system apps and non system apps, the relative ordering
     * is defined by their declared priority.
     */
    private List<ResolveInfo> findGlobalSearchActivities() {
        // Step 1 : Query the package manager for a list
        // of activities that can handle the GLOBAL_SEARCH intent.
        Intent intent = new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH);
        List<ResolveInfo> activities = queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_DEBUG_TRIAGED_MISSING);
        if (activities != null && !activities.isEmpty()) {
            // Step 2: Rank matching activities according to our heuristics.
            Collections.sort(activities, GLOBAL_SEARCH_RANKER);
        }

        return activities;
    }

    /**
     * Finds the global search activity.
     */
    private ComponentName findGlobalSearchActivity(List<ResolveInfo> installed) {
        // Fetch the global search provider from the system settings,
        // and if it's still installed, return it.
        final String searchProviderSetting = getGlobalSearchProviderSetting();
        if (!TextUtils.isEmpty(searchProviderSetting)) {
            final ComponentName globalSearchComponent = ComponentName.unflattenFromString(
                    searchProviderSetting);
            if (globalSearchComponent != null && isInstalled(globalSearchComponent)) {
                return globalSearchComponent;
            }
        }

        return getDefaultGlobalSearchProvider(installed);
    }

    /**
     * Checks whether the global search provider with a given
     * component name is installed on the system or not. This deals with
     * cases such as the removal of an installed provider.
     */
    private boolean isInstalled(ComponentName globalSearch) {
        Intent intent = new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH);
        intent.setComponent(globalSearch);

        List<ResolveInfo> activities = queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (activities != null && !activities.isEmpty()) {
            return true;
        }

        return false;
    }

    private static final Comparator<ResolveInfo> GLOBAL_SEARCH_RANKER =
            new Comparator<ResolveInfo>() {
        @Override
        public int compare(ResolveInfo lhs, ResolveInfo rhs) {
            if (lhs == rhs) {
                return 0;
            }
            boolean lhsSystem = isSystemApp(lhs);
            boolean rhsSystem = isSystemApp(rhs);

            if (lhsSystem && !rhsSystem) {
                return -1;
            } else if (rhsSystem && !lhsSystem) {
                return 1;
            } else {
                // Either both system engines, or both non system
                // engines.
                //
                // Note, this isn't a typo. Higher priority numbers imply
                // higher priority, but are "lower" in the sort order.
                return rhs.priority - lhs.priority;
            }
        }
    };

    /**
     * @return true iff. the resolve info corresponds to a system application.
     */
    private static final boolean isSystemApp(ResolveInfo res) {
        return (res.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /**
     * Returns the highest ranked search provider as per the
     * ranking defined in {@link #getGlobalSearchActivities()}.
     */
    private ComponentName getDefaultGlobalSearchProvider(List<ResolveInfo> providerList) {
        if (providerList != null && !providerList.isEmpty()) {
            ActivityInfo ai = providerList.get(0).activityInfo;
            return new ComponentName(ai.packageName, ai.name);
        }

        Log.w(LOG_TAG, "No global search activity found");
        return null;
    }

    private String getGlobalSearchProviderSetting() {
        return Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.SEARCH_GLOBAL_SEARCH_ACTIVITY);
    }

    /**
     * Finds the web search activity.
     *
     * Only looks in the package of the global search activity.
     */
    private ComponentName findWebSearchActivity(ComponentName globalSearchActivity) {
        if (globalSearchActivity == null) {
            return null;
        }
        Intent intent = new Intent(Intent.ACTION_WEB_SEARCH);
        intent.setPackage(globalSearchActivity.getPackageName());
        List<ResolveInfo> activities =
                queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

        if (activities != null && !activities.isEmpty()) {
            ActivityInfo ai = activities.get(0).activityInfo;
            // TODO: do some sanity checks here?
            return new ComponentName(ai.packageName, ai.name);
        }
        Log.w(LOG_TAG, "No web search activity found");
        return null;
    }

    private List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
        List<ResolveInfo> activities = null;
        try {
            activities =
                    mPm.queryIntentActivities(intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    flags | PackageManager.MATCH_INSTANT, mUserId).getList();
        } catch (RemoteException re) {
            // Local call
        }
        return activities;
    }

    /**
     * Returns the list of searchable activities.
     */
    public synchronized ArrayList<SearchableInfo> getSearchablesList() {
        return createFilterdSearchableInfoList(mSearchablesList);
    }

    /**
     * Returns a list of the searchable activities that can be included in global search.
     */
    public synchronized ArrayList<SearchableInfo> getSearchablesInGlobalSearchList() {
        return createFilterdSearchableInfoList(mSearchablesInGlobalSearchList);
    }

    /**
     * Returns a list of activities that handle the global search intent.
     */
    public synchronized ArrayList<ResolveInfo> getGlobalSearchActivities() {
        return createFilterdResolveInfoList(mGlobalSearchActivities);
    }

    private ArrayList<SearchableInfo> createFilterdSearchableInfoList(List<SearchableInfo> list) {
        if (list == null) {
            return null;
        }
        final ArrayList<SearchableInfo> resultList = new ArrayList<>(list.size());
        final PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getCallingUserId();
        for (SearchableInfo info : list) {
            if (pm.canAccessComponent(callingUid, info.getSearchActivity(), callingUserId)) {
                resultList.add(info);
            }
        }
        return resultList;
    }

    private ArrayList<ResolveInfo> createFilterdResolveInfoList(List<ResolveInfo> list) {
        if (list == null) {
            return null;
        }
        final ArrayList<ResolveInfo> resultList = new ArrayList<>(list.size());
        final PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getCallingUserId();
        for (ResolveInfo info : list) {
            if (pm.canAccessComponent(
                    callingUid, info.activityInfo.getComponentName(), callingUserId)) {
                resultList.add(info);
            }
        }
        return resultList;
    }

    /**
     * Gets the name of the global search activity.
     */
    public synchronized ComponentName getGlobalSearchActivity() {
        final PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getCallingUserId();
        if (mCurrentGlobalSearchActivity != null
                && pm.canAccessComponent(callingUid, mCurrentGlobalSearchActivity, callingUserId)) {
            return mCurrentGlobalSearchActivity;
        }
        return null;
    }

    /**
     * Gets the name of the web search activity.
     */
    public synchronized ComponentName getWebSearchActivity() {
        final PackageManagerInternal pm = LocalServices.getService(PackageManagerInternal.class);
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getCallingUserId();
        if (mWebSearchActivity != null
                && pm.canAccessComponent(callingUid, mWebSearchActivity, callingUserId)) {
            return mWebSearchActivity;
        }
        return null;
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Searchable authorities:");
        synchronized (this) {
            if (mSearchablesList != null) {
                for (SearchableInfo info: mSearchablesList) {
                    pw.print("  "); pw.println(info.getSuggestAuthority());
                }
            }
        }
    }
}
