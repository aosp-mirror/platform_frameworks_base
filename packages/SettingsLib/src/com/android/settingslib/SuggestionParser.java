/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.settingslib;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.util.Xml;
import android.provider.Settings;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.view.InflateException;
import com.android.settingslib.drawer.Tile;
import com.android.settingslib.drawer.TileUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SuggestionParser {

    private static final String TAG = "SuggestionParser";

    // If defined, only returns this suggestion if the feature is supported.
    public static final String META_DATA_REQUIRE_FEATURE = "com.android.settings.require_feature";

    // If defined, only display this optional step if an account of that type exists.
    private static final String META_DATA_REQUIRE_ACCOUNT = "com.android.settings.require_account";

    // If defined and not true, do not should optional step.
    private static final String META_DATA_IS_SUPPORTED = "com.android.settings.is_supported";

    /**
     * Allows suggestions to appear after a certain number of days, and to re-appear if dismissed.
     * For instance:
     * 0,10
     * Will appear immediately, but if the user removes it, it will come back after 10 days.
     *
     * Another example:
     * 10,30
     * Will only show up after 10 days, and then again after 30.
     */
    public static final String META_DATA_DISMISS_CONTROL = "com.android.settings.dismiss";

    // Shared prefs keys for storing dismissed state.
    // Index into current dismissed state.
    private static final String DISMISS_INDEX = "_dismiss_index";
    private static final String SETUP_TIME = "_setup_time";
    private static final String IS_DISMISSED = "_is_dismissed";

    private static final long MILLIS_IN_DAY = 24 * 60 * 60 * 1000;

    private final Context mContext;
    private final List<SuggestionCategory> mSuggestionList;
    private final ArrayMap<Pair<String, String>, Tile> addCache = new ArrayMap<>();
    private final SharedPreferences mSharedPrefs;

    public SuggestionParser(Context context, SharedPreferences sharedPrefs, int orderXml) {
        mContext = context;
        mSuggestionList = (List<SuggestionCategory>) new SuggestionOrderInflater(mContext)
                .parse(orderXml);
        mSharedPrefs = sharedPrefs;
    }

    public List<Tile> getSuggestions() {
        List<Tile> suggestions = new ArrayList<>();
        final int N = mSuggestionList.size();
        for (int i = 0; i < N; i++) {
            readSuggestions(mSuggestionList.get(i), suggestions);
        }
        return suggestions;
    }

    /**
     * Dismisses a suggestion, returns true if the suggestion has no more dismisses left and should
     * be disabled.
     */
    public boolean dismissSuggestion(Tile suggestion) {
        String keyBase = suggestion.intent.getComponent().flattenToShortString();
        int index = mSharedPrefs.getInt(keyBase + DISMISS_INDEX, 0);
        String dismissControl = suggestion.metaData.getString(META_DATA_DISMISS_CONTROL);
        if (dismissControl == null || parseDismissString(dismissControl).length == index) {
            return true;
        }
        mSharedPrefs.edit()
                .putBoolean(keyBase + IS_DISMISSED, true)
                .commit();
        return false;
    }

    private void readSuggestions(SuggestionCategory category, List<Tile> suggestions) {
        int countBefore = suggestions.size();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category.category);
        if (category.pkg != null) {
            intent.setPackage(category.pkg);
        }
        TileUtils.getTilesForIntent(mContext, new UserHandle(UserHandle.myUserId()), intent,
                addCache, null, suggestions, true, false);
        for (int i = countBefore; i < suggestions.size(); i++) {
            if (!isAvailable(suggestions.get(i)) ||
                    !isSupported(suggestions.get(i)) ||
                    !satisfiesRequiredAccount(suggestions.get(i)) ||
                    isDismissed(suggestions.get(i))) {
                suggestions.remove(i--);
            }
        }
        if (!category.multiple && suggestions.size() > (countBefore + 1)) {
            // If there are too many, remove them all and only re-add the one with the highest
            // priority.
            Tile item = suggestions.remove(suggestions.size() - 1);
            while (suggestions.size() > countBefore) {
                Tile last = suggestions.remove(suggestions.size() - 1);
                if (last.priority > item.priority) {
                    item = last;
                }
            }
            // If category is marked as done, do not add any item.
            if (!isCategoryDone(category.category)) {
                suggestions.add(item);
            }
        }
    }

    private boolean isAvailable(Tile suggestion) {
        String featureRequired = suggestion.metaData.getString(META_DATA_REQUIRE_FEATURE);
        if (featureRequired != null) {
            return mContext.getPackageManager().hasSystemFeature(featureRequired);
        }
        return true;
    }

    public boolean satisfiesRequiredAccount(Tile suggestion) {
        String requiredAccountType = suggestion.metaData.getString(META_DATA_REQUIRE_ACCOUNT);
        if (requiredAccountType == null) {
            return true;
        }
        AccountManager accountManager = AccountManager.get(mContext);
        Account[] accounts = accountManager.getAccountsByType(requiredAccountType);
        return accounts.length > 0;
    }

    public boolean isSupported(Tile suggestion) {
        int isSupportedResource = suggestion.metaData.getInt(META_DATA_IS_SUPPORTED);
        try {
            if (suggestion.intent == null) {
                return false;
            }
            final Resources res = mContext.getPackageManager().getResourcesForActivity(
                    suggestion.intent.getComponent());
            return isSupportedResource != 0 ? res.getBoolean(isSupportedResource) : true;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Cannot find resources for " + suggestion.intent.getComponent());
            return false;
        }
    }

    public boolean isCategoryDone(String category) {
        String name = Settings.Secure.COMPLETED_CATEGORY_PREFIX + category;
        return Settings.Secure.getInt(mContext.getContentResolver(), name, 0) != 0;
    }

    public void markCategoryDone(String category) {
        String name = Settings.Secure.COMPLETED_CATEGORY_PREFIX + category;
        Settings.Secure.putInt(mContext.getContentResolver(), name, 1);
    }

    private boolean isDismissed(Tile suggestion) {
        Object dismissObj = suggestion.metaData.get(META_DATA_DISMISS_CONTROL);
        if (dismissObj == null) {
            return false;
        }
        String dismissControl = String.valueOf(dismissObj);
        String keyBase = suggestion.intent.getComponent().flattenToShortString();
        if (!mSharedPrefs.contains(keyBase + SETUP_TIME)) {
            mSharedPrefs.edit()
                    .putLong(keyBase + SETUP_TIME, System.currentTimeMillis())
                    .commit();
        }
        // Default to dismissed, so that we can have suggestions that only first appear after
        // some number of days.
        if (!mSharedPrefs.getBoolean(keyBase + IS_DISMISSED, true)) {
            return false;
        }
        int index = mSharedPrefs.getInt(keyBase + DISMISS_INDEX, 0);
        int currentDismiss = parseDismissString(dismissControl)[index];
        long time = getEndTime(mSharedPrefs.getLong(keyBase + SETUP_TIME, 0), currentDismiss);
        if (System.currentTimeMillis() >= time) {
            // Dismiss timeout has passed, undismiss it.
            mSharedPrefs.edit()
                    .putBoolean(keyBase + IS_DISMISSED, false)
                    .putInt(keyBase + DISMISS_INDEX, index + 1)
                    .commit();
            return false;
        }
        return true;
    }

    private long getEndTime(long startTime, int daysDelay) {
        long days = daysDelay * MILLIS_IN_DAY;
        return startTime + days;
    }

    private int[] parseDismissString(String dismissControl) {
        String[] dismissStrs = dismissControl.split(",");
        int[] dismisses = new int[dismissStrs.length];
        for (int i = 0; i < dismissStrs.length; i++) {
            dismisses[i] = Integer.parseInt(dismissStrs[i]);
        }
        return dismisses;
    }

    private static class SuggestionCategory {
        public String category;
        public String pkg;
        public boolean multiple;
    }

    private static class SuggestionOrderInflater {
        private static final String TAG_LIST = "optional-steps";
        private static final String TAG_ITEM = "step";

        private static final String ATTR_CATEGORY = "category";
        private static final String ATTR_PACKAGE = "package";
        private static final String ATTR_MULTIPLE = "multiple";

        private final Context mContext;

        public SuggestionOrderInflater(Context context) {
            mContext = context;
        }

        public Object parse(int resource) {
            XmlPullParser parser = mContext.getResources().getXml(resource);
            final AttributeSet attrs = Xml.asAttributeSet(parser);
            try {
                // Look for the root node.
                int type;
                do {
                    type = parser.next();
                } while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT);

                if (type != XmlPullParser.START_TAG) {
                    throw new InflateException(parser.getPositionDescription()
                            + ": No start tag found!");
                }

                // Temp is the root that was found in the xml
                Object xmlRoot = onCreateItem(parser.getName(), attrs);

                // Inflate all children under temp
                rParse(parser, xmlRoot, attrs);
                return xmlRoot;
            } catch (XmlPullParserException | IOException e) {
                Log.w(TAG, "Problem parser resource " + resource, e);
                return null;
            }
        }

        /**
         * Recursive method used to descend down the xml hierarchy and instantiate
         * items, instantiate their children.
         */
        private void rParse(XmlPullParser parser, Object parent, final AttributeSet attrs)
                throws XmlPullParserException, IOException {
            final int depth = parser.getDepth();

            int type;
            while (((type = parser.next()) != XmlPullParser.END_TAG ||
                    parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }

                final String name = parser.getName();

                Object item = onCreateItem(name, attrs);
                onAddChildItem(parent, item);
                rParse(parser, item, attrs);
            }
        }

        protected void onAddChildItem(Object parent, Object child) {
            if (parent instanceof List<?> && child instanceof SuggestionCategory) {
                ((List<SuggestionCategory>) parent).add((SuggestionCategory) child);
            } else {
                throw new IllegalArgumentException("Parent was not a list");
            }
        }

        protected Object onCreateItem(String name, AttributeSet attrs) {
            if (name.equals(TAG_LIST)) {
                return new ArrayList<SuggestionCategory>();
            } else if (name.equals(TAG_ITEM)) {
                SuggestionCategory category = new SuggestionCategory();
                category.category = attrs.getAttributeValue(null, ATTR_CATEGORY);
                category.pkg = attrs.getAttributeValue(null, ATTR_PACKAGE);
                String multiple = attrs.getAttributeValue(null, ATTR_MULTIPLE);
                category.multiple = !TextUtils.isEmpty(multiple) && Boolean.parseBoolean(multiple);
                return category;
            } else {
                throw new IllegalArgumentException("Unknown item " + name);
            }
        }
    }
}

