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
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

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
    private static final String EXTRA_SETTINGS_ACTION =
            "com.android.settings.action.EXTRA_SETTINGS";

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
     * Name of the meta-data item that should be set in the AndroidManifest.xml
     * to specify the icon that should be displayed for the preference.
     */
    public static final String META_DATA_PREFERENCE_ICON = "com.android.settings.icon";

    /**
     * Name of the meta-data item that should be set in the AndroidManifest.xml
     * to specify the title that should be displayed for the preference.
     */
    public static final String META_DATA_PREFERENCE_TITLE = "com.android.settings.title";

    /**
     * Name of the meta-data item that should be set in the AndroidManifest.xml
     * to specify the summary text that should be displayed for the preference.
     */
    public static final String META_DATA_PREFERENCE_SUMMARY = "com.android.settings.summary";

    private static final String SETTING_PKG = "com.android.settings";

    public static List<DashboardCategory> getCategories(Context context,
            HashMap<Pair<String, String>, Tile> cache) {
        final long startTime = System.currentTimeMillis();
        boolean setup = Global.getInt(context.getContentResolver(), Global.DEVICE_PROVISIONED, 0)
                != 0;
        ArrayList<Tile> tiles = new ArrayList<>();
        UserManager userManager = UserManager.get(context);
        for (UserHandle user : userManager.getUserProfiles()) {
            // TODO: Needs much optimization, too many PM queries going on here.
            if (user.getIdentifier() == ActivityManager.getCurrentUser()) {
                // Only add Settings for this user.
                getTilesForAction(context, user, SETTINGS_ACTION, cache, null, tiles, true);
                getTilesForAction(context, user, OPERATOR_SETTINGS, cache,
                        OPERATOR_DEFAULT_CATEGORY, tiles, false);
                getTilesForAction(context, user, MANUFACTURER_SETTINGS, cache,
                        MANUFACTURER_DEFAULT_CATEGORY, tiles, false);
            }
            if (setup) {
                getTilesForAction(context, user, EXTRA_SETTINGS_ACTION, cache, null, tiles, false);
            }
        }
        HashMap<String, DashboardCategory> categoryMap = new HashMap<>();
        for (Tile tile : tiles) {
            DashboardCategory category = categoryMap.get(tile.category);
            if (category == null) {
                category = createCategory(context, tile.category);
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
            Collections.sort(category.tiles, TILE_COMPARATOR);
        }
        Collections.sort(categories, CATEGORY_COMPARATOR);
        if (DEBUG_TIMING) Log.d(LOG_TAG, "getCategories took "
                + (System.currentTimeMillis() - startTime) + " ms");
        return categories;
    }

    private static DashboardCategory createCategory(Context context, String categoryKey) {
        DashboardCategory category = new DashboardCategory();
        category.key = categoryKey;
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
            String defaultCategory, ArrayList<Tile> outTiles, boolean requireSettings) {
        Intent intent = new Intent(action);
        if (requireSettings) {
            intent.setPackage(SETTING_PKG);
        }
        getTilesForIntent(context, user, intent, addedCache, defaultCategory, outTiles,
                requireSettings, true);
    }

    public static void getTilesForIntent(Context context, UserHandle user, Intent intent,
            Map<Pair<String, String>, Tile> addedCache, String defaultCategory, List<Tile> outTiles,
            boolean usePriority, boolean checkCategory) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> results = pm.queryIntentActivitiesAsUser(intent,
                PackageManager.GET_META_DATA, user.getIdentifier());
        for (ResolveInfo resolved : results) {
            if (!resolved.system) {
                // Do not allow any app to add to settings, only system ones.
                continue;
            }
            ActivityInfo activityInfo = resolved.activityInfo;
            Bundle metaData = activityInfo.metaData;
            String categoryKey = defaultCategory;
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
                        pm);
                if (DEBUG) Log.d(LOG_TAG, "Adding tile " + tile.title);

                addedCache.put(key, tile);
            }
            if (!tile.userHandle.contains(user)) {
                tile.userHandle.add(user);
            }
            if (!outTiles.contains(tile)) {
                outTiles.add(tile);
            }
        }
    }

    private static DashboardCategory getCategory(List<DashboardCategory> target,
            String categoryKey) {
        for (DashboardCategory category : target) {
            if (categoryKey.equals(category.key)) {
                return category;
            }
        }
        return null;
    }

    private static boolean updateTileData(Context context, Tile tile,
            ActivityInfo activityInfo, ApplicationInfo applicationInfo, PackageManager pm) {
        if (applicationInfo.isSystemApp()) {
            int icon = 0;
            CharSequence title = null;
            String summary = null;

            // Get the activity's meta-data
            try {
                Resources res = pm.getResourcesForApplication(
                        applicationInfo.packageName);
                Bundle metaData = activityInfo.metaData;

                if (res != null && metaData != null) {
                    if (metaData.containsKey(META_DATA_PREFERENCE_ICON)) {
                        icon = metaData.getInt(META_DATA_PREFERENCE_ICON);
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
                }
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
                if (DEBUG) Log.d(LOG_TAG, "Couldn't find info", e);
            }

            // Set the preference title to the activity's label if no
            // meta-data is found
            if (TextUtils.isEmpty(title)) {
                title = activityInfo.loadLabel(pm).toString();
            }
            if (icon == 0) {
                icon = activityInfo.icon;
            }

            // Set icon, title and summary for the preference
            tile.icon = Icon.createWithResource(activityInfo.packageName, icon);
            tile.title = title;
            tile.summary = summary;
            // Replace the intent with this specific activity
            tile.intent = new Intent().setClassName(activityInfo.packageName,
                    activityInfo.name);

            return true;
        }

        return false;
    }

    public static final Comparator<Tile> TILE_COMPARATOR =
            new Comparator<Tile>() {
        @Override
        public int compare(Tile lhs, Tile rhs) {
            return rhs.priority - lhs.priority;
        }
    };

    private static final Comparator<DashboardCategory> CATEGORY_COMPARATOR =
            new Comparator<DashboardCategory>() {
        @Override
        public int compare(DashboardCategory lhs, DashboardCategory rhs) {
            return rhs.priority - lhs.priority;
        }
    };
}
