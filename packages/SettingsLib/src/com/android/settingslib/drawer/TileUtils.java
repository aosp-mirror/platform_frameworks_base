/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.settingslib.drawer;

import android.app.ActivityManager;
import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TileUtils {

    private static final boolean DEBUG = false;
    private static final boolean DEBUG_TIMING = false;

    private static final String LOG_TAG = "TileUtils";

    /**
     * Settings will search for system activities of this action and add them as a top level
     * settings tile using the following parameters.
     *
     * <p>A category must be specified in the meta-data for the activity named
     * {@link #EXTRA_CATEGORY_KEY}
     *
     * <p>The title may be defined by meta-data named {@link #META_DATA_PREFERENCE_TITLE}
     * otherwise the label for the activity will be used.
     *
     * <p>The icon may be defined by meta-data named {@link #META_DATA_PREFERENCE_ICON}
     * otherwise the icon for the activity will be used.
     *
     * <p>A summary my be defined by meta-data named {@link #META_DATA_PREFERENCE_SUMMARY}
     */
    public static final String EXTRA_SETTINGS_ACTION =
            "com.android.settings.action.EXTRA_SETTINGS";

    /**
     * @See {@link #EXTRA_SETTINGS_ACTION}.
     */
    private static final String IA_SETTINGS_ACTION =
            "com.android.settings.action.IA_SETTINGS";


    /**
     * Same as #EXTRA_SETTINGS_ACTION but used for the platform Settings activities.
     */
    private static final String SETTINGS_ACTION =
            "com.android.settings.action.SETTINGS";

    private static final String OPERATOR_SETTINGS =
            "com.android.settings.OPERATOR_APPLICATION_SETTING";

    private static final String OPERATOR_DEFAULT_CATEGORY =
            "com.android.settings.category.wireless";

    private static final String MANUFACTURER_SETTINGS =
            "com.android.settings.MANUFACTURER_APPLICATION_SETTING";

    private static final String MANUFACTURER_DEFAULT_CATEGORY =
            "com.android.settings.category.device";

    /**
     * The key used to get the category from metadata of activities of action
     * {@link #EXTRA_SETTINGS_ACTION}
     * The value must be one of:
     * <li>com.android.settings.category.wireless</li>
     * <li>com.android.settings.category.device</li>
     * <li>com.android.settings.category.personal</li>
     * <li>com.android.settings.category.system</li>
     */
    private static final String EXTRA_CATEGORY_KEY = "com.android.settings.category";

    /**
     * The key used to get the package name of the icon resource for the preference.
     */
    private static final String EXTRA_PREFERENCE_ICON_PACKAGE =
        "com.android.settings.icon_package";

    /**
     * Name of the meta-data item that should be set in the AndroidManifest.xml
     * to specify the key that should be used for the preference.
     */
    public static final String META_DATA_PREFERENCE_KEYHINT = "com.android.settings.keyhint";

    /**
     * Name of the meta-data item that should be set in the AndroidManifest.xml
     * to specify the icon that should be displayed for the preference.
     */
    public static final String META_DATA_PREFERENCE_ICON = "com.android.settings.icon";

    /**
     * Name of the meta-data item that should be set in the AndroidManifest.xml
     * to specify the icon background color. The value may or may not be used by Settings app.
     */
    public static final String META_DATA_PREFERENCE_ICON_BACKGROUND_HINT =
            "com.android.settings.bg.hint";

    /**
     * Name of the meta-data item that should be set in the AndroidManifest.xml
     * to specify the content provider providing the icon that should be displayed for
     * the preference.
     *
     * Icon provided by the content provider overrides any static icon.
     */
    public static final String META_DATA_PREFERENCE_ICON_URI = "com.android.settings.icon_uri";

    /**
     * Name of the meta-data item that should be set in the AndroidManifest.xml
     * to specify whether the icon is tintable. This should be a boolean value {@code true} or
     * {@code false}, set using {@code android:value}
     */
    public static final String META_DATA_PREFERENCE_ICON_TINTABLE =
            "com.android.settings.icon_tintable";

    /**
     * Name of the meta-data item that should be set in the AndroidManifest.xml
     * to specify the title that should be displayed for the preference.
     *
     * <p>Note: It is preferred to provide this value using {@code android:resource} with a string
     * resource for localization.
     */
    public static final String META_DATA_PREFERENCE_TITLE = "com.android.settings.title";

    /**
     * Name of the meta-data item that should be set in the AndroidManifest.xml
     * to specify the summary text that should be displayed for the preference.
     */
    public static final String META_DATA_PREFERENCE_SUMMARY = "com.android.settings.summary";

    /**
     * Name of the meta-data item that should be set in the AndroidManifest.xml
     * to specify the content provider providing the summary text that should be displayed for the
     * preference.
     *
     * Summary provided by the content provider overrides any static summary.
     */
    public static final String META_DATA_PREFERENCE_SUMMARY_URI =
            "com.android.settings.summary_uri";

    /**
     * Name of the meta-data item that should be set in the AndroidManifest.xml to specify the
     * custom view which should be displayed for the preference. The custom view will be inflated
     * as a remote view.
     *
     * This also can be used with {@link #META_DATA_PREFERENCE_SUMMARY_URI}, by setting the id
     * of the summary TextView to '@android:id/summary'.
     */
    public static final String META_DATA_PREFERENCE_CUSTOM_VIEW =
            "com.android.settings.custom_view";

    public static final String SETTING_PKG = "com.android.settings";

    /**
     * Build a list of DashboardCategory. Each category must be defined in manifest.
     * eg: .Settings$DeviceSettings
     * @deprecated
     */
    @Deprecated
    public static List<DashboardCategory> getCategories(Context context,
            Map<Pair<String, String>, Tile> cache) {
        return getCategories(context, cache, true /*categoryDefinedInManifest*/);
    }

    /**
     * Build a list of DashboardCategory.
     * @param categoryDefinedInManifest If true, an dummy activity must exists in manifest to
     * represent this category (eg: .Settings$DeviceSettings)
     */
    public static List<DashboardCategory> getCategories(Context context,
            Map<Pair<String, String>, Tile> cache, boolean categoryDefinedInManifest) {
        return getCategories(context, cache, categoryDefinedInManifest, null, SETTING_PKG);
    }

    /**
     * Build a list of DashboardCategory.
     * @param categoryDefinedInManifest If true, an dummy activity must exists in manifest to
     * represent this category (eg: .Settings$DeviceSettings)
     * @param extraAction additional intent filter action to be usetileutild to build the dashboard
     * categories
     */
    public static List<DashboardCategory> getCategories(Context context,
            Map<Pair<String, String>, Tile> cache, boolean categoryDefinedInManifest,
            String extraAction, String settingPkg) {
        final long startTime = System.currentTimeMillis();
        boolean setup = Global.getInt(context.getContentResolver(), Global.DEVICE_PROVISIONED, 0)
                != 0;
        ArrayList<Tile> tiles = new ArrayList<>();
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        for (UserHandle user : userManager.getUserProfiles()) {
            // TODO: Needs much optimization, too many PM queries going on here.
            if (user.getIdentifier() == ActivityManager.getCurrentUser()) {
                // Only add Settings for this user.
                getTilesForAction(context, user, SETTINGS_ACTION, cache, null, tiles, true,
                        settingPkg);
                getTilesForAction(context, user, OPERATOR_SETTINGS, cache,
                        OPERATOR_DEFAULT_CATEGORY, tiles, false, true, settingPkg);
                getTilesForAction(context, user, MANUFACTURER_SETTINGS, cache,
                        MANUFACTURER_DEFAULT_CATEGORY, tiles, false, true, settingPkg);
            }
            if (setup) {
                getTilesForAction(context, user, EXTRA_SETTINGS_ACTION, cache, null, tiles, false,
                        settingPkg);
                if (!categoryDefinedInManifest) {
                    getTilesForAction(context, user, IA_SETTINGS_ACTION, cache, null, tiles, false,
                            settingPkg);
                    if (extraAction != null) {
                        getTilesForAction(context, user, extraAction, cache, null, tiles, false,
                                settingPkg);
                    }
                }
            }
        }

        HashMap<String, DashboardCategory> categoryMap = new HashMap<>();
        for (Tile tile : tiles) {
            DashboardCategory category = categoryMap.get(tile.category);
            if (category == null) {
                category = createCategory(context, tile.category, categoryDefinedInManifest);
                if (category == null) {
                    Log.w(LOG_TAG, "Couldn't find category " + tile.category);
                    continue;
                }
                categoryMap.put(category.key, category);
            }
            category.addTile(tile);
        }
        ArrayList<DashboardCategory> categories = new ArrayList<>(categoryMap.values());
        for (DashboardCategory category : categories) {
            category.sortTiles();
        }
        Collections.sort(categories, CATEGORY_COMPARATOR);
        if (DEBUG_TIMING) Log.d(LOG_TAG, "getCategories took "
                + (System.currentTimeMillis() - startTime) + " ms");
        return categories;
    }

    /**
     * Create a new DashboardCategory from key.
     *
     * @param context Context to query intent
     * @param categoryKey The category key
     * @param categoryDefinedInManifest If true, an dummy activity must exists in manifest to
     * represent this category (eg: .Settings$DeviceSettings)
     */
    private static DashboardCategory createCategory(Context context, String categoryKey,
            boolean categoryDefinedInManifest) {
        DashboardCategory category = new DashboardCategory();
        category.key = categoryKey;
        if (!categoryDefinedInManifest) {
            return category;
        }
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> results = pm.queryIntentActivities(new Intent(categoryKey), 0);
        if (results.size() == 0) {
            return null;
        }
        for (ResolveInfo resolved : results) {
            if (!resolved.system) {
                // Do not allow any app to add to settings, only system ones.
                continue;
            }
            category.title = resolved.activityInfo.loadLabel(pm);
            category.priority = SETTING_PKG.equals(
                    resolved.activityInfo.applicationInfo.packageName) ? resolved.priority : 0;
            if (DEBUG) Log.d(LOG_TAG, "Adding category " + category.title);
        }

        return category;
    }

    private static void getTilesForAction(Context context,
            UserHandle user, String action, Map<Pair<String, String>, Tile> addedCache,
            String defaultCategory, ArrayList<Tile> outTiles, boolean requireSettings,
            String settingPkg) {
        getTilesForAction(context, user, action, addedCache, defaultCategory, outTiles,
                requireSettings, requireSettings, settingPkg);
    }

    private static void getTilesForAction(Context context,
            UserHandle user, String action, Map<Pair<String, String>, Tile> addedCache,
            String defaultCategory, ArrayList<Tile> outTiles, boolean requireSettings,
            boolean usePriority, String settingPkg) {
        Intent intent = new Intent(action);
        if (requireSettings) {
            intent.setPackage(settingPkg);
        }
        getTilesForIntent(context, user, intent, addedCache, defaultCategory, outTiles,
                usePriority, true, true);
    }

    public static void getTilesForIntent(
            Context context, UserHandle user, Intent intent,
            Map<Pair<String, String>, Tile> addedCache, String defaultCategory, List<Tile> outTiles,
            boolean usePriority, boolean checkCategory, boolean forceTintExternalIcon) {
        getTilesForIntent(context, user, intent, addedCache, defaultCategory, outTiles,
                usePriority, checkCategory, forceTintExternalIcon, false /* shouldUpdateTiles */);
    }

    public static void getTilesForIntent(
            Context context, UserHandle user, Intent intent,
            Map<Pair<String, String>, Tile> addedCache, String defaultCategory, List<Tile> outTiles,
            boolean usePriority, boolean checkCategory, boolean forceTintExternalIcon,
            boolean shouldUpdateTiles) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> results = pm.queryIntentActivitiesAsUser(intent,
                PackageManager.GET_META_DATA, user.getIdentifier());
        Map<String, IContentProvider> providerMap = new HashMap<>();
        for (ResolveInfo resolved : results) {
            if (!resolved.system) {
                // Do not allow any app to add to settings, only system ones.
                continue;
            }
            ActivityInfo activityInfo = resolved.activityInfo;
            Bundle metaData = activityInfo.metaData;
            String categoryKey = defaultCategory;

            // Load category
            if (checkCategory && ((metaData == null) || !metaData.containsKey(EXTRA_CATEGORY_KEY))
                    && categoryKey == null) {
                Log.w(LOG_TAG, "Found " + resolved.activityInfo.name + " for intent "
                        + intent + " missing metadata "
                        + (metaData == null ? "" : EXTRA_CATEGORY_KEY));
                continue;
            } else {
                categoryKey = metaData.getString(EXTRA_CATEGORY_KEY);
            }

            Pair<String, String> key = new Pair<String, String>(activityInfo.packageName,
                    activityInfo.name);
            Tile tile = addedCache.get(key);
            if (tile == null) {
                tile = new Tile();
                tile.intent = new Intent().setClassName(
                        activityInfo.packageName, activityInfo.name);
                tile.category = categoryKey;
                tile.priority = usePriority ? resolved.priority : 0;
                tile.metaData = activityInfo.metaData;
                updateTileData(context, tile, activityInfo, activityInfo.applicationInfo,
                        pm, providerMap, forceTintExternalIcon);
                if (DEBUG) Log.d(LOG_TAG, "Adding tile " + tile.title);
                addedCache.put(key, tile);
            } else if (shouldUpdateTiles) {
                updateSummaryAndTitle(context, providerMap, tile);
            }

            if (!tile.userHandle.contains(user)) {
                tile.userHandle.add(user);
            }
            if (!outTiles.contains(tile)) {
                outTiles.add(tile);
            }
        }
    }

    private static boolean updateTileData(Context context, Tile tile,
            ActivityInfo activityInfo, ApplicationInfo applicationInfo, PackageManager pm,
            Map<String, IContentProvider> providerMap, boolean forceTintExternalIcon) {
        if (applicationInfo.isSystemApp()) {
            boolean forceTintIcon = false;
            int icon = 0;
            Pair<String, Integer> iconFromUri = null;
            CharSequence title = null;
            String summary = null;
            String keyHint = null;
            boolean isIconTintable = false;

            // Get the activity's meta-data
            try {
                Resources res = pm.getResourcesForApplication(applicationInfo.packageName);
                Bundle metaData = activityInfo.metaData;

                if (forceTintExternalIcon
                        && !context.getPackageName().equals(applicationInfo.packageName)) {
                    isIconTintable = true;
                    forceTintIcon = true;
                }

                if (res != null && metaData != null) {
                    if (metaData.containsKey(META_DATA_PREFERENCE_ICON)) {
                        icon = metaData.getInt(META_DATA_PREFERENCE_ICON);
                    }
                    if (metaData.containsKey(META_DATA_PREFERENCE_ICON_TINTABLE)) {
                        if (forceTintIcon) {
                            Log.w(LOG_TAG, "Ignoring icon tintable for " + activityInfo);
                        } else {
                            isIconTintable =
                                    metaData.getBoolean(META_DATA_PREFERENCE_ICON_TINTABLE);
                        }
                    }
                    if (metaData.containsKey(META_DATA_PREFERENCE_TITLE)) {
                        if (metaData.get(META_DATA_PREFERENCE_TITLE) instanceof Integer) {
                            title = res.getString(metaData.getInt(META_DATA_PREFERENCE_TITLE));
                        } else {
                            title = metaData.getString(META_DATA_PREFERENCE_TITLE);
                        }
                    }
                    if (metaData.containsKey(META_DATA_PREFERENCE_SUMMARY)) {
                        if (metaData.get(META_DATA_PREFERENCE_SUMMARY) instanceof Integer) {
                            summary = res.getString(metaData.getInt(META_DATA_PREFERENCE_SUMMARY));
                        } else {
                            summary = metaData.getString(META_DATA_PREFERENCE_SUMMARY);
                        }
                    }
                    if (metaData.containsKey(META_DATA_PREFERENCE_KEYHINT)) {
                        if (metaData.get(META_DATA_PREFERENCE_KEYHINT) instanceof Integer) {
                            keyHint = res.getString(metaData.getInt(META_DATA_PREFERENCE_KEYHINT));
                        } else {
                            keyHint = metaData.getString(META_DATA_PREFERENCE_KEYHINT);
                        }
                    }
                    if (metaData.containsKey(META_DATA_PREFERENCE_CUSTOM_VIEW)) {
                        int layoutId = metaData.getInt(META_DATA_PREFERENCE_CUSTOM_VIEW);
                        tile.remoteViews = new RemoteViews(applicationInfo.packageName, layoutId);
                        updateSummaryAndTitle(context, providerMap, tile);
                    }
                }
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
                if (DEBUG) Log.d(LOG_TAG, "Couldn't find info", e);
            }

            // Set the preference title to the activity's label if no
            // meta-data is found
            if (TextUtils.isEmpty(title)) {
                title = activityInfo.loadLabel(pm).toString();
            }

            // Set the icon
            if (icon == 0) {
                // Only fallback to activityinfo.icon if metadata does not contain ICON_URI.
                // ICON_URI should be loaded in app UI when need the icon object.
                if (!tile.metaData.containsKey(META_DATA_PREFERENCE_ICON_URI)) {
                    icon = activityInfo.icon;
                }
            }
            if (icon != 0) {
                tile.icon = Icon.createWithResource(activityInfo.packageName, icon);
            }

            // Set title and summary for the preference
            tile.title = title;
            tile.summary = summary;
            // Replace the intent with this specific activity
            tile.intent = new Intent().setClassName(activityInfo.packageName,
                    activityInfo.name);
            // Suggest a key for this tile
            tile.key = keyHint;
            tile.isIconTintable = isIconTintable;

            return true;
        }

        return false;
    }

    private static void updateSummaryAndTitle(
            Context context, Map<String, IContentProvider> providerMap, Tile tile) {
        if (tile == null || tile.metaData == null
                || !tile.metaData.containsKey(META_DATA_PREFERENCE_SUMMARY_URI)) {
            return;
        }

        String uriString = tile.metaData.getString(META_DATA_PREFERENCE_SUMMARY_URI);
        Bundle bundle = getBundleFromUri(context, uriString, providerMap);
        String overrideSummary = getString(bundle, META_DATA_PREFERENCE_SUMMARY);
        String overrideTitle = getString(bundle, META_DATA_PREFERENCE_TITLE);
        if (overrideSummary != null) {
            tile.remoteViews.setTextViewText(android.R.id.summary, overrideSummary);
        }

        if (overrideTitle != null) {
            tile.remoteViews.setTextViewText(android.R.id.title, overrideTitle);
        }
    }

    /**
     * Gets the icon package name and resource id from content provider.
     * @param context context
     * @param packageName package name of the target activity
     * @param uriString URI for the content provider
     * @param providerMap Maps URI authorities to providers
     * @return package name and resource id of the icon specified
     */
    public static Pair<String, Integer> getIconFromUri(Context context, String packageName,
            String uriString, Map<String, IContentProvider> providerMap) {
        Bundle bundle = getBundleFromUri(context, uriString, providerMap);
        if (bundle == null) {
            return null;
        }
        String iconPackageName = bundle.getString(EXTRA_PREFERENCE_ICON_PACKAGE);
        if (TextUtils.isEmpty(iconPackageName)) {
            return null;
        }
        int resId = bundle.getInt(META_DATA_PREFERENCE_ICON, 0);
        if (resId == 0) {
            return null;
        }
        // Icon can either come from the target package or from the Settings app.
        if (iconPackageName.equals(packageName)
                || iconPackageName.equals(context.getPackageName())) {
            return Pair.create(iconPackageName, bundle.getInt(META_DATA_PREFERENCE_ICON, 0));
        }
        return null;
    }

    /**
     * Gets text associated with the input key from the content provider.
     * @param context context
     * @param uriString URI for the content provider
     * @param providerMap Maps URI authorities to providers
     * @param key Key mapping to the text in bundle returned by the content provider
     * @return Text associated with the key, if returned by the content provider
     */
    public static String getTextFromUri(Context context, String uriString,
            Map<String, IContentProvider> providerMap, String key) {
        Bundle bundle = getBundleFromUri(context, uriString, providerMap);
        return (bundle != null) ? bundle.getString(key) : null;
    }

    private static Bundle getBundleFromUri(Context context, String uriString,
            Map<String, IContentProvider> providerMap) {
        if (TextUtils.isEmpty(uriString)) {
            return null;
        }
        Uri uri = Uri.parse(uriString);
        String method = getMethodFromUri(uri);
        if (TextUtils.isEmpty(method)) {
            return null;
        }
        IContentProvider provider = getProviderFromUri(context, uri, providerMap);
        if (provider == null) {
            return null;
        }
        try {
            return provider.call(context.getPackageName(), method, uriString, null);
        } catch (RemoteException e) {
            return null;
        }
    }

    private static String getString(Bundle bundle, String key) {
        return bundle == null ? null : bundle.getString(key);
    }

    private static IContentProvider getProviderFromUri(Context context, Uri uri,
            Map<String, IContentProvider> providerMap) {
        if (uri == null) {
            return null;
        }
        String authority = uri.getAuthority();
        if (TextUtils.isEmpty(authority)) {
            return null;
        }
        if (!providerMap.containsKey(authority)) {
            providerMap.put(authority, context.getContentResolver().acquireUnstableProvider(uri));
        }
        return providerMap.get(authority);
    }

    /** Returns the first path segment of the uri if it exists as the method, otherwise null. */
    static String getMethodFromUri(Uri uri) {
        if (uri == null) {
            return null;
        }
        List<String> pathSegments = uri.getPathSegments();
        if ((pathSegments == null) || pathSegments.isEmpty()) {
            return null;
        }
        return pathSegments.get(0);
    }

    private static final Comparator<DashboardCategory> CATEGORY_COMPARATOR =
            new Comparator<DashboardCategory>() {
        @Override
        public int compare(DashboardCategory lhs, DashboardCategory rhs) {
            return rhs.priority - lhs.priority;
        }
    };
}
