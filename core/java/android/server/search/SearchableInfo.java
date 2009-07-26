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
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.view.inputmethod.EditorInfo;

import java.io.IOException;
import java.util.HashMap;

public final class SearchableInfo implements Parcelable {

    // general debugging support
    private static final boolean DBG = false;
    private static final String LOG_TAG = "SearchableInfo";

    // static strings used for XML lookups.
    // TODO how should these be documented for the developer, in a more structured way than 
    // the current long wordy javadoc in SearchManager.java ?
    private static final String MD_LABEL_SEARCHABLE = "android.app.searchable";
    private static final String MD_XML_ELEMENT_SEARCHABLE = "searchable";
    private static final String MD_XML_ELEMENT_SEARCHABLE_ACTION_KEY = "actionkey";
    
    // flags in the searchMode attribute
    private static final int SEARCH_MODE_BADGE_LABEL = 0x04;
    private static final int SEARCH_MODE_BADGE_ICON = 0x08;
    private static final int SEARCH_MODE_QUERY_REWRITE_FROM_DATA = 0x10;
    private static final int SEARCH_MODE_QUERY_REWRITE_FROM_TEXT = 0x20;
    
    // true member variables - what we know about the searchability
    private final int mLabelId;
    private final ComponentName mSearchActivity;
    private final int mHintId;
    private final int mSearchMode;
    private final int mIconId;
    private final int mSearchButtonText;
    private final int mSearchInputType;
    private final int mSearchImeOptions;
    private final boolean mIncludeInGlobalSearch;
    private final boolean mQueryAfterZeroResults;
    private final String mSettingsDescription;
    private final String mSuggestAuthority;
    private final String mSuggestPath;
    private final String mSuggestSelection;
    private final String mSuggestIntentAction;
    private final String mSuggestIntentData;
    private final int mSuggestThreshold;
    // Maps key codes to action key information. auto-boxing is not so bad here,
    // since keycodes for the hard keys are < 127. For such values, Integer.valueOf()
    // uses shared Integer objects.
    // This is not final, to allow lazy initialization.
    private HashMap<Integer,ActionKeyInfo> mActionKeys = null;
    private final String mSuggestProviderPackage;
    
    // Flag values for Searchable_voiceSearchMode
    private static int VOICE_SEARCH_SHOW_BUTTON = 1;
    private static int VOICE_SEARCH_LAUNCH_WEB_SEARCH = 2;
    private static int VOICE_SEARCH_LAUNCH_RECOGNIZER = 4;
    private final int mVoiceSearchMode;
    private final int mVoiceLanguageModeId;       // voiceLanguageModel
    private final int mVoicePromptTextId;         // voicePromptText
    private final int mVoiceLanguageId;           // voiceLanguage
    private final int mVoiceMaxResults;           // voiceMaxResults

    
    /**
     * Retrieve the authority for obtaining search suggestions.
     * 
     * @return Returns a string containing the suggestions authority.
     */
    public String getSuggestAuthority() {
        return mSuggestAuthority;
    }

    /**
     * Gets the component name of the searchable activity.
     */
    public ComponentName getSearchActivity() {
        return mSearchActivity;
    }

    /**
     * Checks whether the badge should be a text label.
     */
    public boolean useBadgeLabel() {
        return 0 != (mSearchMode & SEARCH_MODE_BADGE_LABEL);
    }

    /**
     * Checks whether the badge should be an icon.
     */
    public boolean useBadgeIcon() {
        return (0 != (mSearchMode & SEARCH_MODE_BADGE_ICON)) && (mIconId != 0);
    }

    /**
     * Checks whether the text in the query field should come from the suggestion intent data.
     */
    public boolean shouldRewriteQueryFromData() {
        return 0 != (mSearchMode & SEARCH_MODE_QUERY_REWRITE_FROM_DATA);
    }

    /**
     * Checks whether the text in the query field should come from the suggestion title.
     */
    public boolean shouldRewriteQueryFromText() {
        return 0 != (mSearchMode & SEARCH_MODE_QUERY_REWRITE_FROM_TEXT);
    }
    
    /**
     * Gets the description to use for this source in system search settings, or null if
     * none has been specified.
     */
    public String getSettingsDescription() {
        return mSettingsDescription;
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
     * Gets the suggestion threshold for use with these suggestions. 
     * 
     * @return The value of the <code>searchSuggestThreshold</code> attribute, 
     *         or 0 if the attribute is not set.
     */
    public int getSuggestThreshold() {
        return mSuggestThreshold;
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
        return createActivityContext(context, mSearchActivity);
    }
    
    /**
     * Creates a context for another activity.
     */
    private static Context createActivityContext(Context context, ComponentName activity) {
        Context theirContext = null;
        try {
            theirContext = context.createPackageContext(activity.getPackageName(), 0);
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
     * Constructor
     * 
     * Given a ComponentName, get the searchability info
     * and build a local copy of it.  Use the factory, not this.
     * 
     * @param activityContext runtime context for the activity that the searchable info is about.
     * @param attr The attribute set we found in the XML file, contains the values that are used to
     * construct the object.
     * @param cName The component name of the searchable activity
     * @throws IllegalArgumentException if the searchability info is invalid or insufficient
     */
    private SearchableInfo(Context activityContext, AttributeSet attr, final ComponentName cName) {
        mSearchActivity = cName;
        
        TypedArray a = activityContext.obtainStyledAttributes(attr,
                com.android.internal.R.styleable.Searchable);
        mSearchMode = a.getInt(com.android.internal.R.styleable.Searchable_searchMode, 0);
        mLabelId = a.getResourceId(com.android.internal.R.styleable.Searchable_label, 0);
        mHintId = a.getResourceId(com.android.internal.R.styleable.Searchable_hint, 0);
        mIconId = a.getResourceId(com.android.internal.R.styleable.Searchable_icon, 0);
        mSearchButtonText = a.getResourceId(
                com.android.internal.R.styleable.Searchable_searchButtonText, 0);
        mSearchInputType = a.getInt(com.android.internal.R.styleable.Searchable_inputType, 
                InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_VARIATION_NORMAL);
        mSearchImeOptions = a.getInt(com.android.internal.R.styleable.Searchable_imeOptions, 
                EditorInfo.IME_ACTION_SEARCH);
        mIncludeInGlobalSearch = a.getBoolean(
                com.android.internal.R.styleable.Searchable_includeInGlobalSearch, false);
        mQueryAfterZeroResults = a.getBoolean(
                com.android.internal.R.styleable.Searchable_queryAfterZeroResults, false);

        mSettingsDescription = a.getString(
                com.android.internal.R.styleable.Searchable_searchSettingsDescription);
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
        mSuggestThreshold = a.getInt(
                com.android.internal.R.styleable.Searchable_searchSuggestThreshold, 0);

        mVoiceSearchMode = 
            a.getInt(com.android.internal.R.styleable.Searchable_voiceSearchMode, 0);
        // TODO this didn't work - came back zero from YouTube
        mVoiceLanguageModeId = 
            a.getResourceId(com.android.internal.R.styleable.Searchable_voiceLanguageModel, 0);
        mVoicePromptTextId = 
            a.getResourceId(com.android.internal.R.styleable.Searchable_voicePromptText, 0);
        mVoiceLanguageId = 
            a.getResourceId(com.android.internal.R.styleable.Searchable_voiceLanguage, 0);
        mVoiceMaxResults = 
            a.getInt(com.android.internal.R.styleable.Searchable_voiceMaxResults, 0);

        a.recycle();

        // get package info for suggestions provider (if any)
        String suggestProviderPackage = null;
        if (mSuggestAuthority != null) {
            PackageManager pm = activityContext.getPackageManager();
            ProviderInfo pi = pm.resolveContentProvider(mSuggestAuthority, 0);
            if (pi != null) {
                suggestProviderPackage = pi.packageName;
            }
        }
        mSuggestProviderPackage = suggestProviderPackage;

        // for now, implement some form of rules - minimal data
        if (mLabelId == 0) {
            throw new IllegalArgumentException("Search label must be a resource reference.");
        }
    }
    
    /**
     * Private class used to hold the "action key" configuration
     */
    public static class ActionKeyInfo implements Parcelable {
        
        private final int mKeyCode;
        private final String mQueryActionMsg;
        private final String mSuggestActionMsg;
        private final String mSuggestActionMsgColumn;
        
        /**
         * Create one object using attributeset as input data.
         * @param activityContext runtime context of the activity that the action key information
         *        is about.
         * @param attr The attribute set we found in the XML file, contains the values that are used to
         * construct the object.
         * @throws IllegalArgumentException if the action key configuration is invalid
         */
        public ActionKeyInfo(Context activityContext, AttributeSet attr) {
            TypedArray a = activityContext.obtainStyledAttributes(attr,
                    com.android.internal.R.styleable.SearchableActionKey);

            mKeyCode = a.getInt(
                    com.android.internal.R.styleable.SearchableActionKey_keycode, 0);
            mQueryActionMsg = a.getString(
                    com.android.internal.R.styleable.SearchableActionKey_queryActionMsg);
            mSuggestActionMsg = a.getString(
                    com.android.internal.R.styleable.SearchableActionKey_suggestActionMsg);
            mSuggestActionMsgColumn = a.getString(
                    com.android.internal.R.styleable.SearchableActionKey_suggestActionMsgColumn);
            a.recycle();

            // sanity check.
            if (mKeyCode == 0) {
                throw new IllegalArgumentException("No keycode.");
            } else if ((mQueryActionMsg == null) && 
                    (mSuggestActionMsg == null) && 
                    (mSuggestActionMsgColumn == null)) {
                throw new IllegalArgumentException("No message information.");
            }
        }

        /**
         * Instantiate a new ActionKeyInfo from the data in a Parcel that was
         * previously written with {@link #writeToParcel(Parcel, int)}.
         *
         * @param in The Parcel containing the previously written ActionKeyInfo,
         * positioned at the location in the buffer where it was written.
         */
        public ActionKeyInfo(Parcel in) {
            mKeyCode = in.readInt();
            mQueryActionMsg = in.readString();
            mSuggestActionMsg = in.readString();
            mSuggestActionMsgColumn = in.readString();
        }

        public int getKeyCode() {
            return mKeyCode;
        }

        public String getQueryActionMsg() {
            return mQueryActionMsg;
        }

        public String getSuggestActionMsg() {
            return mSuggestActionMsg;
        }

        public String getSuggestActionMsgColumn() {
            return mSuggestActionMsgColumn;
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
        if (mActionKeys == null) {
            return null;
        }
        return mActionKeys.get(keyCode);
    }

    private void addActionKey(ActionKeyInfo keyInfo) {
        if (mActionKeys == null) {
            mActionKeys = new HashMap<Integer,ActionKeyInfo>();
        }
        mActionKeys.put(keyInfo.getKeyCode(), keyInfo);
    }

    public static SearchableInfo getActivityMetaData(Context context, ActivityInfo activityInfo) {
        // for each component, try to find metadata
        XmlResourceParser xml = 
                activityInfo.loadXmlMetaData(context.getPackageManager(), MD_LABEL_SEARCHABLE);
        if (xml == null) {
            return null;
        }
        ComponentName cName = new ComponentName(activityInfo.packageName, activityInfo.name);
        
        SearchableInfo searchable = getActivityMetaData(context, xml, cName);
        xml.close();
        
        if (DBG) {
            if (searchable != null) {
                Log.d(LOG_TAG, "Checked " + activityInfo.name
                        + ",label=" + searchable.getLabelId()
                        + ",icon=" + searchable.getIconId()
                        + ",suggestAuthority=" + searchable.getSuggestAuthority()
                        + ",target=" + searchable.getSearchActivity().getClassName()
                        + ",global=" + searchable.shouldIncludeInGlobalSearch()
                        + ",settingsDescription=" + searchable.getSettingsDescription()
                        + ",threshold=" + searchable.getSuggestThreshold());
            } else {
                Log.d(LOG_TAG, "Checked " + activityInfo.name + ", no searchable meta-data");
            }
        }
        return searchable;
    }
    
    /**
     * Get the metadata for a given activity
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
        Context activityContext = createActivityContext(context, cName);
        
        // in order to use the attributes mechanism, we have to walk the parser
        // forward through the file until it's reading the tag of interest.
        try {
            int tagType = xml.next();
            while (tagType != XmlPullParser.END_DOCUMENT) {
                if (tagType == XmlPullParser.START_TAG) {
                    if (xml.getName().equals(MD_XML_ELEMENT_SEARCHABLE)) {
                        AttributeSet attr = Xml.asAttributeSet(xml);
                        if (attr != null) {
                            try {
                                result = new SearchableInfo(activityContext, attr, cName);
                            } catch (IllegalArgumentException ex) {
                                Log.w(LOG_TAG, "Invalid searchable metadata for " +
                                        cName.flattenToShortString() + ": " + ex.getMessage());
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
                            try {
                                result.addActionKey(new ActionKeyInfo(activityContext, attr));
                            } catch (IllegalArgumentException ex) {
                                Log.w(LOG_TAG, "Invalid action key for " +
                                        cName.flattenToShortString() + ": " + ex.getMessage());
                                return null;
                            }
                        }
                    }
                }
                tagType = xml.next();
            }
        } catch (XmlPullParserException e) {
            Log.w(LOG_TAG, "Reading searchable metadata for " + cName.flattenToShortString(), e);
            return null;
        } catch (IOException e) {
            Log.w(LOG_TAG, "Reading searchable metadata for " + cName.flattenToShortString(), e);
            return null;
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
     * @return true if android:voiceSearchMode="showVoiceSearchButton"
     */
    public boolean getVoiceSearchEnabled() {
        return 0 != (mVoiceSearchMode & VOICE_SEARCH_SHOW_BUTTON);
    }
    
    /**
     * @return true if android:voiceSearchMode="launchWebSearch"
     */
    public boolean getVoiceSearchLaunchWebSearch() {
        return 0 != (mVoiceSearchMode & VOICE_SEARCH_LAUNCH_WEB_SEARCH);
    }
    
    /**
     * @return true if android:voiceSearchMode="launchRecognizer"
     */
    public boolean getVoiceSearchLaunchRecognizer() {
        return 0 != (mVoiceSearchMode & VOICE_SEARCH_LAUNCH_RECOGNIZER);
    }
    
    /**
     * @return the resource Id of the language model string, if specified in the searchable
     * activity's metadata, or 0 if not specified.  
     */
    public int getVoiceLanguageModeId() {
        return mVoiceLanguageModeId;
    }
    
    /**
     * @return the resource Id of the voice prompt text string, if specified in the searchable
     * activity's metadata, or 0 if not specified.  
     */
    public int getVoicePromptTextId() {
        return mVoicePromptTextId;
    }
    
    /**
     * @return the resource Id of the spoken langauge, if specified in the searchable
     * activity's metadata, or 0 if not specified.  
     */
    public int getVoiceLanguageId() {
        return mVoiceLanguageId;
    }
    
    /**
     * @return the max results count, if specified in the searchable
     * activity's metadata, or 0 if not specified.  
     */
    public int getVoiceMaxResults() {
        return mVoiceMaxResults;
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
     * Return the input type as specified in the searchable attributes.  This will default to
     * InputType.TYPE_CLASS_TEXT if not specified (which is appropriate for free text input).
     * 
     * @return the input type
     */
    public int getInputType() {
        return mSearchInputType;
    }
    
    /**
     * Return the input method options specified in the searchable attributes.
     * This will default to EditorInfo.ACTION_SEARCH if not specified (which is
     * appropriate for a search box).
     * 
     * @return the input type
     */
    public int getImeOptions() {
        return mSearchImeOptions;
    }
    
    /**
     * Checks whether the searchable is exported.
     *
     * @return The value of the <code>exported</code> attribute,
     *         or <code>false</code> if the attribute is not set.
     */
    public boolean shouldIncludeInGlobalSearch() {
        return mIncludeInGlobalSearch;
    }

    /**
     * Checks whether this searchable activity should be invoked after a query returned zero
     * results.
     *
     * @return The value of the <code>queryAfterZeroResults</code> attribute,
     *         or <code>false</code> if the attribute is not set.
     */
    public boolean queryAfterZeroResults() {
        return mQueryAfterZeroResults;
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
        mSearchInputType = in.readInt();
        mSearchImeOptions = in.readInt();
        mIncludeInGlobalSearch = in.readInt() != 0;
        mQueryAfterZeroResults = in.readInt() != 0;
        
        mSettingsDescription = in.readString();
        mSuggestAuthority = in.readString();
        mSuggestPath = in.readString();
        mSuggestSelection = in.readString();
        mSuggestIntentAction = in.readString();
        mSuggestIntentData = in.readString();
        mSuggestThreshold = in.readInt();

        for (int count = in.readInt(); count > 0; count--) {
            addActionKey(new ActionKeyInfo(in));
        }

        mSuggestProviderPackage = in.readString();
        
        mVoiceSearchMode = in.readInt();
        mVoiceLanguageModeId = in.readInt();
        mVoicePromptTextId = in.readInt();
        mVoiceLanguageId = in.readInt();
        mVoiceMaxResults = in.readInt();
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
        dest.writeInt(mSearchInputType);
        dest.writeInt(mSearchImeOptions);
        dest.writeInt(mIncludeInGlobalSearch ? 1 : 0);
        dest.writeInt(mQueryAfterZeroResults ? 1 : 0);
        
        dest.writeString(mSettingsDescription);
        dest.writeString(mSuggestAuthority);
        dest.writeString(mSuggestPath);
        dest.writeString(mSuggestSelection);
        dest.writeString(mSuggestIntentAction);
        dest.writeString(mSuggestIntentData);
        dest.writeInt(mSuggestThreshold);

        if (mActionKeys == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(mActionKeys.size());
            for (ActionKeyInfo actionKey : mActionKeys.values()) {
                actionKey.writeToParcel(dest, flags);
            }
        }

        dest.writeString(mSuggestProviderPackage);

        dest.writeInt(mVoiceSearchMode);
        dest.writeInt(mVoiceLanguageModeId);
        dest.writeInt(mVoicePromptTextId);
        dest.writeInt(mVoiceLanguageId);
        dest.writeInt(mVoiceMaxResults);
    }
}
