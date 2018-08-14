/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.settingslib.suggestions;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.util.Xml;
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

    // If defined, only display this optional step if the current user is of that type.
    private static final String META_DATA_REQUIRE_USER_TYPE =
            "com.android.settings.require_user_type";

    // If defined, only display this optional step if a connection is available.
    private static final String META_DATA_IS_CONNECTION_REQUIRED =
            "com.android.settings.require_connection";

    // The valid values that setup wizard recognizes for differentiating user types.
    private static final String META_DATA_PRIMARY_USER_TYPE_VALUE = "primary";
    private static final String META_DATA_ADMIN_USER_TYPE_VALUE = "admin";
    private static final String META_DATA_GUEST_USER_TYPE_VALUE = "guest";
    private static final String META_DATA_RESTRICTED_USER_TYPE_VALUE = "restricted";

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
    public static final String SETUP_TIME = "_setup_time";
    private static final String IS_DISMISSED = "_is_dismissed";

    // Default dismiss control for smart suggestions.
    private static final String DEFAULT_SMART_DISMISS_CONTROL = "0";

    private final Context mContext;
    private final List<SuggestionCategory> mSuggestionList;
    private final ArrayMap<Pair<String, String>, Tile> mAddCache = new ArrayMap<>();
    private final SharedPreferences mSharedPrefs;
    private final String mDefaultDismissControl;

    public SuggestionParser(Context context, SharedPreferences sharedPrefs, int orderXml,
            String defaultDismissControl) {
        this(
                context,
                sharedPrefs,
                (List<SuggestionCategory>) new SuggestionOrderInflater(context).parse(orderXml),
                defaultDismissControl);
    }

    public SuggestionParser(Context context, SharedPreferences sharedPrefs, int orderXml) {
        this(context, sharedPrefs, orderXml, DEFAULT_SMART_DISMISS_CONTROL);
    }

    @VisibleForTesting
    public SuggestionParser(
            Context context,
            SharedPreferences sharedPrefs,
            List<SuggestionCategory> suggestionList,
            String defaultDismissControl) {
        mContext = context;
        mSuggestionList = suggestionList;
        mSharedPrefs = sharedPrefs;
        mDefaultDismissControl = defaultDismissControl;
    }

    public SuggestionList getSuggestions(boolean isSmartSuggestionEnabled) {
        final SuggestionList suggestionList = new SuggestionList();
        final int N = mSuggestionList.size();
        for (int i = 0; i < N; i++) {
            final SuggestionCategory category = mSuggestionList.get(i);
            if (category.exclusive && !isExclusiveCategoryExpired(category)) {
                // If suggestions from an exclusive category are present, parsing is stopped
                // and only suggestions from that category are displayed. Note that subsequent
                // exclusive categories are also ignored.
                final List<Tile> exclusiveSuggestions = new ArrayList<>();

                // Read suggestion and force isSmartSuggestion to be false so the rule defined
                // from each suggestion itself is used.
                readSuggestions(category, exclusiveSuggestions, false /* isSmartSuggestion */);
                if (!exclusiveSuggestions.isEmpty()) {
                    final SuggestionList exclusiveList = new SuggestionList();
                    exclusiveList.addSuggestions(category, exclusiveSuggestions);
                    return exclusiveList;
                }
            } else {
                // Either the category is not exclusive, or the exclusiveness expired so we should
                // treat it as a normal category.
                final List<Tile> suggestions = new ArrayList<>();
                readSuggestions(category, suggestions, isSmartSuggestionEnabled);
                suggestionList.addSuggestions(category, suggestions);
            }
        }
        return suggestionList;
    }

    /**
     * Dismisses a suggestion, returns true if the suggestion has no more dismisses left and should
     * be disabled.
     */
    public boolean dismissSuggestion(Tile suggestion) {
        final String keyBase = suggestion.intent.getComponent().flattenToShortString();
        mSharedPrefs.edit()
                .putBoolean(keyBase + IS_DISMISSED, true)
                .commit();
        return true;
    }

    @VisibleForTesting
    public void filterSuggestions(
            List<Tile> suggestions, int countBefore, boolean isSmartSuggestionEnabled) {
        for (int i = countBefore; i < suggestions.size(); i++) {
            if (!isAvailable(suggestions.get(i)) ||
                    !isSupported(suggestions.get(i)) ||
                    !satisifesRequiredUserType(suggestions.get(i)) ||
                    !satisfiesRequiredAccount(suggestions.get(i)) ||
                    !satisfiesConnectivity(suggestions.get(i)) ||
                    isDismissed(suggestions.get(i), isSmartSuggestionEnabled)) {
                suggestions.remove(i--);
            }
        }
    }

    @VisibleForTesting
    void readSuggestions(
            SuggestionCategory category, List<Tile> suggestions, boolean isSmartSuggestionEnabled) {
        int countBefore = suggestions.size();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category.category);
        if (category.pkg != null) {
            intent.setPackage(category.pkg);
        }
        TileUtils.getTilesForIntent(mContext, new UserHandle(UserHandle.myUserId()), intent,
                mAddCache, null, suggestions, true, false, false, true /* shouldUpdateTiles */);
        filterSuggestions(suggestions, countBefore, isSmartSuggestionEnabled);
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
        final String featuresRequired = suggestion.metaData.getString(META_DATA_REQUIRE_FEATURE);
        if (featuresRequired != null) {
            for (String feature : featuresRequired.split(",")) {
                if (TextUtils.isEmpty(feature)) {
                    Log.w(TAG, "Found empty substring when parsing required features: "
                            + featuresRequired);
                } else if (!mContext.getPackageManager().hasSystemFeature(feature)) {
                    Log.i(TAG, suggestion.title + " requires unavailable feature " + feature);
                    return false;
                }
            }
        }
        return true;
    }

    @RequiresPermission(Manifest.permission.MANAGE_USERS)
    private boolean satisifesRequiredUserType(Tile suggestion) {
        final String requiredUser = suggestion.metaData.getString(META_DATA_REQUIRE_USER_TYPE);
        if (requiredUser != null) {
            final UserManager userManager = mContext.getSystemService(UserManager.class);
            UserInfo userInfo = userManager.getUserInfo(UserHandle.myUserId());
            for (String userType : requiredUser.split("\\|")) {
                final boolean primaryUserCondtionMet = userInfo.isPrimary()
                        && META_DATA_PRIMARY_USER_TYPE_VALUE.equals(userType);
                final boolean adminUserConditionMet = userInfo.isAdmin()
                        && META_DATA_ADMIN_USER_TYPE_VALUE.equals(userType);
                final boolean guestUserCondtionMet = userInfo.isGuest()
                        && META_DATA_GUEST_USER_TYPE_VALUE.equals(userType);
                final boolean restrictedUserCondtionMet = userInfo.isRestricted()
                        && META_DATA_RESTRICTED_USER_TYPE_VALUE.equals(userType);
                if (primaryUserCondtionMet || adminUserConditionMet || guestUserCondtionMet
                        || restrictedUserCondtionMet) {
                    return true;
                }
            }
            Log.i(TAG, suggestion.title + " requires user type " + requiredUser);
            return false;
        }
        return true;
    }

    public boolean satisfiesRequiredAccount(Tile suggestion) {
        final String requiredAccountType = suggestion.metaData.getString(META_DATA_REQUIRE_ACCOUNT);
        if (requiredAccountType == null) {
            return true;
        }
        AccountManager accountManager = mContext.getSystemService(AccountManager.class);
        Account[] accounts = accountManager.getAccountsByType(requiredAccountType);
        boolean satisfiesRequiredAccount = accounts.length > 0;
        if (!satisfiesRequiredAccount) {
            Log.i(TAG, suggestion.title + " requires unavailable account type "
                    + requiredAccountType);
        }
        return satisfiesRequiredAccount;
    }

    public boolean isSupported(Tile suggestion) {
        final int isSupportedResource = suggestion.metaData.getInt(META_DATA_IS_SUPPORTED);
        try {
            if (suggestion.intent == null) {
                return false;
            }
            final Resources res = mContext.getPackageManager().getResourcesForActivity(
                    suggestion.intent.getComponent());
            boolean isSupported =
                    isSupportedResource != 0 ? res.getBoolean(isSupportedResource) : true;
            if (!isSupported) {
                Log.i(TAG, suggestion.title + " requires unsupported resource "
                        + isSupportedResource);
            }
            return isSupported;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Cannot find resources for " + suggestion.intent.getComponent());
            return false;
        } catch (Resources.NotFoundException e) {
            Log.w(TAG, "Cannot find resources for " + suggestion.intent.getComponent(), e);
            return false;
        }
    }

    private boolean satisfiesConnectivity(Tile suggestion) {
        final boolean isConnectionRequired =
                suggestion.metaData.getBoolean(META_DATA_IS_CONNECTION_REQUIRED);
        if (!isConnectionRequired) {
            return true;
        }
        ConnectivityManager cm =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        boolean satisfiesConnectivity = netInfo != null && netInfo.isConnectedOrConnecting();
        if (!satisfiesConnectivity) {
            Log.i(TAG, suggestion.title + " is missing required connection.");
        }
        return satisfiesConnectivity;
    }

    public boolean isCategoryDone(String category) {
        String name = Settings.Secure.COMPLETED_CATEGORY_PREFIX + category;
        return Settings.Secure.getInt(mContext.getContentResolver(), name, 0) != 0;
    }

    public void markCategoryDone(String category) {
        String name = Settings.Secure.COMPLETED_CATEGORY_PREFIX + category;
        Settings.Secure.putInt(mContext.getContentResolver(), name, 1);
    }

    /**
     * Whether or not the category's exclusiveness has expired.
     */
    private boolean isExclusiveCategoryExpired(SuggestionCategory category) {
        final String keySetupTime = category.category + SETUP_TIME;
        final long currentTime = System.currentTimeMillis();
        if (!mSharedPrefs.contains(keySetupTime)) {
            mSharedPrefs.edit()
                    .putLong(keySetupTime, currentTime)
                    .commit();
        }
        if (category.exclusiveExpireDaysInMillis < 0) {
            // negative means never expires
            return false;
        }
        final long setupTime = mSharedPrefs.getLong(keySetupTime, 0);
        final long elapsedTime = currentTime - setupTime;
        Log.d(TAG, "Day " + elapsedTime / DateUtils.DAY_IN_MILLIS + " for " + category.category);
        return elapsedTime > category.exclusiveExpireDaysInMillis;
    }

    @VisibleForTesting
    boolean isDismissed(Tile suggestion, boolean isSmartSuggestionEnabled) {
        String dismissControl = getDismissControl(suggestion, isSmartSuggestionEnabled);
        String keyBase = suggestion.intent.getComponent().flattenToShortString();
        if (!mSharedPrefs.contains(keyBase + SETUP_TIME)) {
            mSharedPrefs.edit()
                    .putLong(keyBase + SETUP_TIME, System.currentTimeMillis())
                    .commit();
        }
        // Check if it's already manually dismissed
        final boolean isDismissed = mSharedPrefs.getBoolean(keyBase + IS_DISMISSED, false);
        if (isDismissed) {
            return true;
        }
        if (dismissControl == null) {
            return false;
        }
        // Parse when suggestion should first appear. return true to artificially hide suggestion
        // before then.
        int firstAppearDay = parseDismissString(dismissControl);
        long firstAppearDayInMs = getEndTime(mSharedPrefs.getLong(keyBase + SETUP_TIME, 0),
                firstAppearDay);
        if (System.currentTimeMillis() >= firstAppearDayInMs) {
            // Dismiss timeout has passed, undismiss it.
            mSharedPrefs.edit()
                    .putBoolean(keyBase + IS_DISMISSED, false)
                    .commit();
            return false;
        }
        return true;
    }

    private long getEndTime(long startTime, int daysDelay) {
        long days = daysDelay * DateUtils.DAY_IN_MILLIS;
        return startTime + days;
    }

    /**
     * Parse the first int from a string formatted as "0,1,2..."
     * The value means suggestion should first appear on Day X.
     */
    private int parseDismissString(String dismissControl) {
        final String[] dismissStrs = dismissControl.split(",");
        return Integer.parseInt(dismissStrs[0]);
    }

    private String getDismissControl(Tile suggestion, boolean isSmartSuggestionEnabled) {
        if (isSmartSuggestionEnabled) {
            return mDefaultDismissControl;
        } else {
            return suggestion.metaData.getString(META_DATA_DISMISS_CONTROL);
        }
    }

    private static class SuggestionOrderInflater {
        private static final String TAG_LIST = "optional-steps";
        private static final String TAG_ITEM = "step";

        private static final String ATTR_CATEGORY = "category";
        private static final String ATTR_PACKAGE = "package";
        private static final String ATTR_MULTIPLE = "multiple";
        private static final String ATTR_EXCLUSIVE = "exclusive";
        private static final String ATTR_EXCLUSIVE_EXPIRE_DAYS = "exclusiveExpireDays";

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
                String exclusive = attrs.getAttributeValue(null, ATTR_EXCLUSIVE);
                category.exclusive =
                        !TextUtils.isEmpty(exclusive) && Boolean.parseBoolean(exclusive);
                String expireDaysAttr = attrs.getAttributeValue(null,
                        ATTR_EXCLUSIVE_EXPIRE_DAYS);
                long expireDays = !TextUtils.isEmpty(expireDaysAttr)
                        ? Integer.parseInt(expireDaysAttr)
                        : -1;
                category.exclusiveExpireDaysInMillis = DateUtils.DAY_IN_MILLIS * expireDays;
                return category;
            } else {
                throw new IllegalArgumentException("Unknown item " + name);
            }
        }
    }
}

