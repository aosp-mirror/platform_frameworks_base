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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public final class SearchableInfo implements Parcelable {

    // general debugging support
    final static String LOG_TAG = "SearchableInfo";
    
    // set this flag to 1 to prevent any apps from providing suggestions
    final static int DBG_INHIBIT_SUGGESTIONS = 0;

    // static strings used for XML lookups, etc.
    // TODO how should these be documented for the developer, in a more structured way than 
    // the current long wordy javadoc in SearchManager.java ?
    private static final String MD_LABEL_DEFAULT_SEARCHABLE = "android.app.default_searchable";
    private static final String MD_LABEL_SEARCHABLE = "android.app.searchable";
    private static final String MD_SEARCHABLE_SYSTEM_SEARCH = "*";
    private static final String MD_XML_ELEMENT_SEARCHABLE = "searchable";
    private static final String MD_XML_ELEMENT_SEARCHABLE_ACTION_KEY = "actionkey";

    // class maintenance and general shared data
    private static HashMap<ComponentName, SearchableInfo> sSearchablesMap = null;
    private static ArrayList<SearchableInfo> sSearchablesList = null;
    private static SearchableInfo sDefaultSearchable = null;
    
    // true member variables - what we know about the searchability
    // TO-DO replace public with getters
    public boolean mSearchable = false;
    private int mLabelId = 0;
    public ComponentName mSearchActivity = null;
    private int mHintId = 0;
    private int mSearchMode = 0;
    public boolean mBadgeLabel = false;
    public boolean mBadgeIcon = false;
    public boolean mQueryRewriteFromData = false;
    public boolean mQueryRewriteFromText = false;
    private int mIconId = 0;
    private int mSearchButtonText = 0;
    private String mSuggestAuthority = null;
    private String mSuggestPath = null;
    private String mSuggestSelection = null;
    private String mSuggestIntentAction = null;
    private String mSuggestIntentData = null;
    private ActionKeyInfo mActionKeyList = null;
    private String mSuggestProviderPackage = null;
    private Context mCacheActivityContext = null;   // use during setup only - don't hold memory!
    
    /**
     * Set the default searchable activity (when none is specified).
     */
    public static void setDefaultSearchable(Context context, 
                                            ComponentName activity) {
        synchronized (SearchableInfo.class) {
            SearchableInfo si = null;
            if (activity != null) {
                si = getSearchableInfo(context, activity);
                if (si != null) {
                    // move to front of list
                    sSearchablesList.remove(si);
                    sSearchablesList.add(0, si);
                }
            }
            sDefaultSearchable = si;
        }
    }
    
    /**
     * Provides the system-default search activity, which you can use
     * whenever getSearchableInfo() returns null;
     * 
     * @return Returns the system-default search activity, null if never defined
     */
    public static SearchableInfo getDefaultSearchable() {
        synchronized (SearchableInfo.class) {
            return sDefaultSearchable;
        }
    }
    
    /**
     * Retrieve the authority for obtaining search suggestions.
     * 
     * @return Returns a string containing the suggestions authority.
     */
    public String getSuggestAuthority() {
        return mSuggestAuthority;
    }
    
    /**
     * Retrieve the path for obtaining search suggestions.
     * 
     * @return Returns a string containing the suggestions path, or null if not provided.
     */
    public String getSuggestPath() {
        return mSuggestPath;
    }
    
    /**
     * Retrieve the selection pattern for obtaining search suggestions.  This must
     * include a single ? which will be used for the user-typed characters.
     * 
     * @return Returns a string containing the suggestions authority.
     */
    public String getSuggestSelection() {
        return mSuggestSelection;
    }
    
    /**
     * Retrieve the (optional) intent action for use with these suggestions.  This is
     * useful if all intents will have the same action (e.g. "android.intent.action.VIEW").
     * 
     * Can be overriden in any given suggestion via the AUTOSUGGEST_COLUMN_INTENT_ACTION column.
     * 
     * @return Returns a string containing the default intent action.
     */
    public String getSuggestIntentAction() {
        return mSuggestIntentAction;
    }
    
    /**
     * Retrieve the (optional) intent data for use with these suggestions.  This is
     * useful if all intents will have similar data URIs (e.g. "android.intent.action.VIEW"), 
     * but you'll likely need to provide a specific ID as well via the column
     * AUTOSUGGEST_COLUMN_INTENT_DATA_ID, which will be appended to the intent data URI.
     * 
     * Can be overriden in any given suggestion via the AUTOSUGGEST_COLUMN_INTENT_DATA column.
     * 
     * @return Returns a string containing the default intent data.
     */
    public String getSuggestIntentData() {
        return mSuggestIntentData;
    }
    
    /**
     * Get the context for the searchable activity.  
     * 
     * This is fairly expensive so do it on the original scan, or when an app is
     * selected, but don't hang on to the result forever.
     * 
     * @param context You need to supply a context to start with
     * @return Returns a context related to the searchable activity
     */
    public Context getActivityContext(Context context) {
        Context theirContext = null;
        try {
            theirContext = context.createPackageContext(mSearchActivity.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            // unexpected, but we deal with this by null-checking theirContext
        } catch (java.lang.SecurityException e) {
            // unexpected, but we deal with this by null-checking theirContext
        }
        
        return theirContext;
    }
    
    /**
     * Get the context for the suggestions provider.  
     * 
     * This is fairly expensive so do it on the original scan, or when an app is
     * selected, but don't hang on to the result forever.
     * 
     * @param context You need to supply a context to start with
     * @param activityContext If we can determine that the provider and the activity are the
     * same, we'll just return this one.
     * @return Returns a context related to the context provider
     */
    public Context getProviderContext(Context context, Context activityContext) {
        Context theirContext = null;
        if (mSearchActivity.getPackageName().equals(mSuggestProviderPackage)) {
            return activityContext;
        }
        if (mSuggestProviderPackage != null)
        try {
            theirContext = context.createPackageContext(mSuggestProviderPackage, 0);
        } catch (PackageManager.NameNotFoundException e) {
            // unexpected, but we deal with this by null-checking theirContext
        } catch (java.lang.SecurityException e) {
            // unexpected, but we deal with this by null-checking theirContext
        }
        
        return theirContext;
    }
    
    /**
     * Factory.  Look up, or construct, based on the activity.
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
    public static SearchableInfo getSearchableInfo(Context context, 
                                                   ComponentName activity) {
        // Step 1.  Is the result already hashed?  (case 1)
        SearchableInfo result;
        synchronized (SearchableInfo.class) {
            result = sSearchablesMap.get(activity);
            if (result != null) return result;
        }
        
        // Step 2.  See if the current activity references a searchable.
        // Note:  Conceptually, this could be a while(true) loop, but there's
        // no point in implementing reference chaining here and risking a loop.  
        // References must point directly to searchable activities.
       
        ActivityInfo ai = null;
        XmlPullParser xml = null;
        try {
            ai = context.getPackageManager().
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
                synchronized (SearchableInfo.class) {
                    result = sSearchablesMap.get(referredActivity);
                    if (result != null) {
                        sSearchablesMap.put(activity, result);
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
     * Super-factory.  Builds an entire list (suitable for display) of 
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
     * 
     * @param context a context we can use during this work
     */
    public static void buildSearchableList(Context context) {
        
        // create empty hash & list
        HashMap<ComponentName, SearchableInfo> newSearchablesMap 
                                = new HashMap<ComponentName, SearchableInfo>();
        ArrayList<SearchableInfo> newSearchablesList
                                = new ArrayList<SearchableInfo>();

        // use intent resolver to generate list of ACTION_SEARCH receivers
        final PackageManager pm = context.getPackageManager();
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
                XmlResourceParser xml = ai.loadXmlMetaData(context.getPackageManager(), 
                                                       MD_LABEL_SEARCHABLE);
                if (xml == null) {
                    continue;
                }
                ComponentName cName = new ComponentName(
                        info.activityInfo.packageName, 
                        info.activityInfo.name);
                
                SearchableInfo searchable = getActivityMetaData(context, xml, cName);
                xml.close();
                
                if (searchable != null) {
                    // no need to keep the context any longer.  setup time is over.
                    searchable.mCacheActivityContext  = null;
                    
                    newSearchablesList.add(searchable);
                    newSearchablesMap.put(cName, searchable);
                }
            }
        }
        
        // record the final values as a coherent pair
        synchronized (SearchableInfo.class) {
            sSearchablesList = newSearchablesList;
            sSearchablesMap = newSearchablesMap;
        }
    }
    
    /**
     * Constructor
     * 
     * Given a ComponentName, get the searchability info
     * and build a local copy of it.  Use the factory, not this.
     * 
     * @param context runtime context
     * @param attr The attribute set we found in the XML file, contains the values that are used to
     * construct the object.
     * @param cName The component name of the searchable activity
     */
    private SearchableInfo(Context context, AttributeSet attr, final ComponentName cName) {
        // initialize as an "unsearchable" object
        mSearchable = false;
        mSearchActivity = cName;
        
        // to access another activity's resources, I need its context.
        // BE SURE to release the cache sometime after construction - it's a large object to hold
        mCacheActivityContext = getActivityContext(context);
        if (mCacheActivityContext != null) {
            TypedArray a = mCacheActivityContext.obtainStyledAttributes(attr,
                    com.android.internal.R.styleable.Searchable);
            mSearchMode = a.getInt(com.android.internal.R.styleable.Searchable_searchMode, 0);
            mLabelId = a.getResourceId(com.android.internal.R.styleable.Searchable_label, 0);
            mHintId = a.getResourceId(com.android.internal.R.styleable.Searchable_hint, 0);
            mIconId = a.getResourceId(com.android.internal.R.styleable.Searchable_icon, 0);
            mSearchButtonText = a.getResourceId(
                    com.android.internal.R.styleable.Searchable_searchButtonText, 0);

            setSearchModeFlags();
            if (DBG_INHIBIT_SUGGESTIONS == 0) {
                mSuggestAuthority = a.getString(
                        com.android.internal.R.styleable.Searchable_searchSuggestAuthority);
                mSuggestPath = a.getString(
                        com.android.internal.R.styleable.Searchable_searchSuggestPath);
                mSuggestSelection = a.getString(
                        com.android.internal.R.styleable.Searchable_searchSuggestSelection);
                mSuggestIntentAction = a.getString(
                        com.android.internal.R.styleable.Searchable_searchSuggestIntentAction);
                mSuggestIntentData = a.getString(
                        com.android.internal.R.styleable.Searchable_searchSuggestIntentData);
            }
            a.recycle();

            // get package info for suggestions provider (if any)
            if (mSuggestAuthority != null) {
                ProviderInfo pi =
                    context.getPackageManager().resolveContentProvider(mSuggestAuthority,
                            0);
                if (pi != null) {
                    mSuggestProviderPackage = pi.packageName;
                }
            }
        }

        // for now, implement some form of rules - minimal data
        if (mLabelId != 0) {
            mSearchable = true;
        } else {
            // Provide some help for developers instead of just silently discarding
            Log.w(LOG_TAG, "Insufficient metadata to configure searchability for " + 
                    cName.flattenToShortString());
        }
    }

    /**
     * Convert searchmode to flags.
     */
    private void setSearchModeFlags() {
        // decompose searchMode attribute
        // TODO How do I reconcile these hardcoded values with the flag bits defined in
        // in attrs.xml?  e.g. android.R.id.filterMode = 0x010200a4 instead of just "1"
    /*  mFilterMode = (0 != (mSearchMode & 1));  */
    /*  mQuickStart = (0 != (mSearchMode & 2));  */
        mBadgeLabel = (0 != (mSearchMode & 4));
        mBadgeIcon = (0 != (mSearchMode & 8)) && (mIconId != 0);
        mQueryRewriteFromData = (0 != (mSearchMode & 0x10));
        mQueryRewriteFromText = (0 != (mSearchMode & 0x20));
    }
    
    /**
     * Private class used to hold the "action key" configuration
     */
    public class ActionKeyInfo implements Parcelable {
        
        public int mKeyCode = 0;
        public String mQueryActionMsg;
        public String mSuggestActionMsg;
        public String mSuggestActionMsgColumn;
        private ActionKeyInfo mNext;
        
        /**
         * Create one object using attributeset as input data.
         * @param context runtime context
         * @param attr The attribute set we found in the XML file, contains the values that are used to
         * construct the object.
         * @param next We'll build these up using a simple linked list (since there are usually
         * just zero or one).
         */
        public ActionKeyInfo(Context context, AttributeSet attr, ActionKeyInfo next) {
            TypedArray a = mCacheActivityContext.obtainStyledAttributes(attr,
                    com.android.internal.R.styleable.SearchableActionKey);

            mKeyCode = a.getInt(
                    com.android.internal.R.styleable.SearchableActionKey_keycode, 0);
            mQueryActionMsg = a.getString(
                    com.android.internal.R.styleable.SearchableActionKey_queryActionMsg);
            if (DBG_INHIBIT_SUGGESTIONS == 0) {
                mSuggestActionMsg = a.getString(
                        com.android.internal.R.styleable.SearchableActionKey_suggestActionMsg);
                mSuggestActionMsgColumn = a.getString(
                        com.android.internal.R.styleable.SearchableActionKey_suggestActionMsgColumn);
            }
            a.recycle();

            // initialize any other fields
            mNext = next;

            // sanity check.  must have at least one action message, or invalidate the object.
            if ((mQueryActionMsg == null) && 
                    (mSuggestActionMsg == null) && 
                    (mSuggestActionMsgColumn == null)) {
                mKeyCode = 0;
            }           
        }

        /**
         * Instantiate a new ActionKeyInfo from the data in a Parcel that was
         * previously written with {@link #writeToParcel(Parcel, int)}.
         *
         * @param in The Parcel containing the previously written ActionKeyInfo,
         * positioned at the location in the buffer where it was written.
         * @param next The value to place in mNext, creating a linked list
         */
        public ActionKeyInfo(Parcel in, ActionKeyInfo next) {
            mKeyCode = in.readInt();
            mQueryActionMsg = in.readString();
            mSuggestActionMsg = in.readString();
            mSuggestActionMsgColumn = in.readString();
            mNext = next;
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mKeyCode);
            dest.writeString(mQueryActionMsg);
            dest.writeString(mSuggestActionMsg);
            dest.writeString(mSuggestActionMsgColumn);
        }
    }
    
    /**
     * If any action keys were defined for this searchable activity, look up and return.
     * 
     * @param keyCode The key that was pressed
     * @return Returns the ActionKeyInfo record, or null if none defined
     */
    public ActionKeyInfo findActionKey(int keyCode) {
        ActionKeyInfo info = mActionKeyList;
        while (info != null) {
            if (info.mKeyCode == keyCode) {
                return info;
            }
            info = info.mNext;
        }
        return null;
    }
    
    /**
     * Get the metadata for a given activity
     * 
     * TODO: clean up where we return null vs. where we throw exceptions.
     * 
     * @param context runtime context
     * @param xml XML parser for reading attributes
     * @param cName The component name of the searchable activity
     * 
     * @result A completely constructed SearchableInfo, or null if insufficient XML data for it
     */
    private static SearchableInfo getActivityMetaData(Context context, XmlPullParser xml,
            final ComponentName cName)  {
        SearchableInfo result = null;
        
        // in order to use the attributes mechanism, we have to walk the parser
        // forward through the file until it's reading the tag of interest.
        try {
            int tagType = xml.next();
            while (tagType != XmlPullParser.END_DOCUMENT) {
                if (tagType == XmlPullParser.START_TAG) {
                    if (xml.getName().equals(MD_XML_ELEMENT_SEARCHABLE)) {
                        AttributeSet attr = Xml.asAttributeSet(xml);
                        if (attr != null) {
                            result = new SearchableInfo(context, attr, cName);
                            // if the constructor returned a bad object, exit now.
                            if (! result.mSearchable) {
                                return null;
                            }
                        }
                    } else if (xml.getName().equals(MD_XML_ELEMENT_SEARCHABLE_ACTION_KEY)) {
                        if (result == null) {
                            // Can't process an embedded element if we haven't seen the enclosing
                            return null;
                        }
                        AttributeSet attr = Xml.asAttributeSet(xml);
                        if (attr != null) {
                            ActionKeyInfo keyInfo = result.new ActionKeyInfo(context, attr, 
                                    result.mActionKeyList);
                            // only add to list if it is was useable
                            if (keyInfo.mKeyCode != 0) {
                                result.mActionKeyList = keyInfo;
                            }
                        }
                    }
                }
                tagType = xml.next();
            }
        } catch (XmlPullParserException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return result;
    }
    
    /**
     * Return the "label" (user-visible name) of this searchable context.  This must be 
     * accessed using the target (searchable) Activity's resources, not simply the context of the
     * caller.
     * 
     * @return Returns the resource Id
     */
    public int getLabelId() {
        return mLabelId;
    }
    
    /**
     * Return the resource Id of the hint text.  This must be 
     * accessed using the target (searchable) Activity's resources, not simply the context of the
     * caller.
     * 
     * @return Returns the resource Id, or 0 if not specified by this package.
     */
    public int getHintId() {
        return mHintId;
    }
    
    /**
     * Return the icon Id specified by the Searchable_icon meta-data entry.  This must be 
     * accessed using the target (searchable) Activity's resources, not simply the context of the
     * caller.
     * 
     * @return Returns the resource id.
     */
    public int getIconId() {
        return mIconId;
    }
    
    /**
     * Return the resource Id of replacement text for the "Search" button.
     * 
     * @return Returns the resource Id, or 0 if not specified by this package.
     */
    public int getSearchButtonText() {
        return mSearchButtonText;
    }
    
    /**
     * Return the list of searchable activities, for use in the drop-down.
     */
    public static ArrayList<SearchableInfo> getSearchablesList() {
        synchronized (SearchableInfo.class) {
            ArrayList<SearchableInfo> result = new ArrayList<SearchableInfo>(sSearchablesList);
            return result;
        }
    }
    
    /**
     * Support for parcelable and aidl operations.
     */
    public static final Parcelable.Creator<SearchableInfo> CREATOR
    = new Parcelable.Creator<SearchableInfo>() {
        public SearchableInfo createFromParcel(Parcel in) {
            return new SearchableInfo(in);
        }

        public SearchableInfo[] newArray(int size) {
            return new SearchableInfo[size];
        }
    };

    /**
     * Instantiate a new SearchableInfo from the data in a Parcel that was
     * previously written with {@link #writeToParcel(Parcel, int)}.
     *
     * @param in The Parcel containing the previously written SearchableInfo,
     * positioned at the location in the buffer where it was written.
     */
    public SearchableInfo(Parcel in) {
        mLabelId = in.readInt();
        mSearchActivity = ComponentName.readFromParcel(in);
        mHintId = in.readInt();
        mSearchMode = in.readInt();
        mIconId = in.readInt();
        mSearchButtonText = in.readInt();
        setSearchModeFlags();

        mSuggestAuthority = in.readString();
        mSuggestPath = in.readString();
        mSuggestSelection = in.readString();
        mSuggestIntentAction = in.readString();
        mSuggestIntentData = in.readString();

        mActionKeyList = null;
        int count = in.readInt();
        while (count-- > 0) {
            mActionKeyList = new ActionKeyInfo(in, mActionKeyList);
        }
        
        mSuggestProviderPackage = in.readString();
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mLabelId);
        mSearchActivity.writeToParcel(dest, flags);
        dest.writeInt(mHintId);
        dest.writeInt(mSearchMode);
        dest.writeInt(mIconId);
        dest.writeInt(mSearchButtonText);
        
        dest.writeString(mSuggestAuthority);
        dest.writeString(mSuggestPath);
        dest.writeString(mSuggestSelection);
        dest.writeString(mSuggestIntentAction);
        dest.writeString(mSuggestIntentData);

        // This is usually a very short linked list so we'll just pre-count it
        ActionKeyInfo nextKeyInfo = mActionKeyList;
        int count = 0;
        while (nextKeyInfo != null) {
            ++count;
            nextKeyInfo = nextKeyInfo.mNext;
        }
        dest.writeInt(count);
        // Now write count of 'em
        nextKeyInfo = mActionKeyList;
        while (count-- > 0) {
            nextKeyInfo.writeToParcel(dest, flags);
        }
        
        dest.writeString(mSuggestProviderPackage);
    }
}
