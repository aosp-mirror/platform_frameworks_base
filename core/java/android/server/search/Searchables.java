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

package android.server.search;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * This class maintains the information about all searchable activities. 
 */
public class Searchables {

    // static strings used for XML lookups, etc.
    // TODO how should these be documented for the developer, in a more structured way than 
    // the current long wordy javadoc in SearchManager.java ?
    private static final String MD_LABEL_DEFAULT_SEARCHABLE = "android.app.default_searchable";
    private static final String MD_SEARCHABLE_SYSTEM_SEARCH = "*";
    
    private Context mContext;
    
    private HashMap<ComponentName, SearchableInfo> mSearchablesMap = null;
    private ArrayList<SearchableInfo> mSearchablesList = null;
    private ArrayList<SearchableInfo> mSearchablesInGlobalSearchList = null;
    private SearchableInfo mDefaultSearchable = null;
    
    /**
     * 
     * @param context Context to use for looking up activities etc.
     */
    public Searchables (Context context) {
        mContext = context;
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
            if (result != null) return result;
        }
        
        // Step 2.  See if the current activity references a searchable.
        // Note:  Conceptually, this could be a while(true) loop, but there's
        // no point in implementing reference chaining here and risking a loop.  
        // References must point directly to searchable activities.
       
        ActivityInfo ai = null;
        try {
            ai = mContext.getPackageManager().
                       getActivityInfo(activity, PackageManager.GET_META_DATA );
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
                // An app or activity can declare that we should simply launch 
                // "system default search" if search is invoked.
                if (refActivityName.equals(MD_SEARCHABLE_SYSTEM_SEARCH)) {
                    return getDefaultSearchable();
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
                        return result;
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            // case 3: no metadata
        }
 
        // Step 3.  None found. Return null.
        return null;
        
    }
    
    /**
     * Provides the system-default search activity, which you can use
     * whenever getSearchableInfo() returns null;
     * 
     * @return Returns the system-default search activity, null if never defined
     */
    public synchronized SearchableInfo getDefaultSearchable() {
        return mDefaultSearchable;
    }
    
    public synchronized boolean isDefaultSearchable(SearchableInfo searchable) {
        return searchable == mDefaultSearchable;
    }
   
    /**
     * Builds an entire list (suitable for display) of 
     * activities that are searchable, by iterating the entire set of 
     * ACTION_SEARCH intents.  
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
    public void buildSearchableList() {
        
        // These will become the new values at the end of the method
        HashMap<ComponentName, SearchableInfo> newSearchablesMap 
                                = new HashMap<ComponentName, SearchableInfo>();
        ArrayList<SearchableInfo> newSearchablesList
                                = new ArrayList<SearchableInfo>();
        ArrayList<SearchableInfo> newSearchablesInGlobalSearchList
                                = new ArrayList<SearchableInfo>();

        final PackageManager pm = mContext.getPackageManager();
        
        // use intent resolver to generate list of ACTION_SEARCH receivers
        List<ResolveInfo> infoList;
        final Intent intent = new Intent(Intent.ACTION_SEARCH);
        infoList = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA);
        
        // analyze each one, generate a Searchables record, and record
        if (infoList != null) {
            int count = infoList.size();
            for (int ii = 0; ii < count; ii++) {
                // for each component, try to find metadata
                ResolveInfo info = infoList.get(ii);
                ActivityInfo ai = info.activityInfo;
                SearchableInfo searchable = SearchableInfo.getActivityMetaData(mContext, ai);
                if (searchable != null) {
                    newSearchablesList.add(searchable);
                    newSearchablesMap.put(searchable.mSearchActivity, searchable);
                    if (searchable.shouldIncludeInGlobalSearch()) {
                        newSearchablesInGlobalSearchList.add(searchable);
                    }
                }
            }
        }
        
        // Find the global search provider
        Intent globalSearchIntent = new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH);
        ComponentName globalSearchActivity = globalSearchIntent.resolveActivity(pm);
        SearchableInfo newDefaultSearchable = newSearchablesMap.get(globalSearchActivity);

        // Store a consistent set of new values
        synchronized (this) {
            mSearchablesMap = newSearchablesMap;
            mSearchablesList = newSearchablesList;
            mSearchablesInGlobalSearchList = newSearchablesInGlobalSearchList;
            mDefaultSearchable = newDefaultSearchable;
        }
    }
    
    /**
     * Returns the list of searchable activities.
     */
    public synchronized ArrayList<SearchableInfo> getSearchablesList() {
        ArrayList<SearchableInfo> result = new ArrayList<SearchableInfo>(mSearchablesList);
        return result;
    }
    
    /**
     * Returns a list of the searchable activities that can be included in global search.
     */
    public synchronized ArrayList<SearchableInfo> getSearchablesInGlobalSearchList() {
        return new ArrayList<SearchableInfo>(mSearchablesInGlobalSearchList);
    }
}
