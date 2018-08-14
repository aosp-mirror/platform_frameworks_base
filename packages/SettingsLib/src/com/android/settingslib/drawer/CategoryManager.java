/**
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.settingslib.drawer;

import android.content.ComponentName;
import android.content.Context;
import android.support.annotation.VisibleForTesting;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;

import com.android.settingslib.applications.InterestingConfigChanges;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static java.lang.String.CASE_INSENSITIVE_ORDER;

public class CategoryManager {

    private static final String TAG = "CategoryManager";

    private static CategoryManager sInstance;
    private final InterestingConfigChanges mInterestingConfigChanges;

    // Tile cache (key: <packageName, activityName>, value: tile)
    private final Map<Pair<String, String>, Tile> mTileByComponentCache;

    // Tile cache (key: category key, value: category)
    private final Map<String, DashboardCategory> mCategoryByKeyMap;

    private List<DashboardCategory> mCategories;
    private String mExtraAction;

    public static CategoryManager get(Context context) {
        return get(context, null);
    }

    public static CategoryManager get(Context context, String action) {
        if (sInstance == null) {
            sInstance = new CategoryManager(context, action);
        }
        return sInstance;
    }

    CategoryManager(Context context, String action) {
        mTileByComponentCache = new ArrayMap<>();
        mCategoryByKeyMap = new ArrayMap<>();
        mInterestingConfigChanges = new InterestingConfigChanges();
        mInterestingConfigChanges.applyNewConfig(context.getResources());
        mExtraAction = action;
    }

    public synchronized DashboardCategory getTilesByCategory(Context context, String categoryKey) {
        return getTilesByCategory(context, categoryKey, TileUtils.SETTING_PKG);
    }

    public synchronized DashboardCategory getTilesByCategory(Context context, String categoryKey,
            String settingPkg) {
        tryInitCategories(context, settingPkg);

        return mCategoryByKeyMap.get(categoryKey);
    }

    public synchronized List<DashboardCategory> getCategories(Context context) {
        return getCategories(context, TileUtils.SETTING_PKG);
    }

    public synchronized List<DashboardCategory> getCategories(Context context, String settingPkg) {
        tryInitCategories(context, settingPkg);
        return mCategories;
    }

    public synchronized void reloadAllCategories(Context context, String settingPkg) {
        final boolean forceClearCache = mInterestingConfigChanges.applyNewConfig(
                context.getResources());
        mCategories = null;
        tryInitCategories(context, forceClearCache, settingPkg);
    }

    public synchronized void updateCategoryFromBlacklist(Set<ComponentName> tileBlacklist) {
        if (mCategories == null) {
            Log.w(TAG, "Category is null, skipping blacklist update");
        }
        for (int i = 0; i < mCategories.size(); i++) {
            DashboardCategory category = mCategories.get(i);
            for (int j = 0; j < category.getTilesCount(); j++) {
                Tile tile = category.getTile(j);
                if (tileBlacklist.contains(tile.intent.getComponent())) {
                    category.removeTile(j--);
                }
            }
        }
    }

    private synchronized void tryInitCategories(Context context, String settingPkg) {
        // Keep cached tiles by default. The cache is only invalidated when InterestingConfigChange
        // happens.
        tryInitCategories(context, false /* forceClearCache */, settingPkg);
    }

    private synchronized void tryInitCategories(Context context, boolean forceClearCache,
            String settingPkg) {
        if (mCategories == null) {
            if (forceClearCache) {
                mTileByComponentCache.clear();
            }
            mCategoryByKeyMap.clear();
            mCategories = TileUtils.getCategories(context, mTileByComponentCache,
                    false /* categoryDefinedInManifest */, mExtraAction, settingPkg);
            for (DashboardCategory category : mCategories) {
                mCategoryByKeyMap.put(category.key, category);
            }
            backwardCompatCleanupForCategory(mTileByComponentCache, mCategoryByKeyMap);
            sortCategories(context, mCategoryByKeyMap);
            filterDuplicateTiles(mCategoryByKeyMap);
        }
    }

    @VisibleForTesting
    synchronized void backwardCompatCleanupForCategory(
            Map<Pair<String, String>, Tile> tileByComponentCache,
            Map<String, DashboardCategory> categoryByKeyMap) {
        // A package can use a) CategoryKey, b) old category keys, c) both.
        // Check if a package uses old category key only.
        // If yes, map them to new category key.

        // Build a package name -> tile map first.
        final Map<String, List<Tile>> packageToTileMap = new HashMap<>();
        for (Entry<Pair<String, String>, Tile> tileEntry : tileByComponentCache.entrySet()) {
            final String packageName = tileEntry.getKey().first;
            List<Tile> tiles = packageToTileMap.get(packageName);
            if (tiles == null) {
                tiles = new ArrayList<>();
                packageToTileMap.put(packageName, tiles);
            }
            tiles.add(tileEntry.getValue());
        }

        for (Entry<String, List<Tile>> entry : packageToTileMap.entrySet()) {
            final List<Tile> tiles = entry.getValue();
            // Loop map, find if all tiles from same package uses old key only.
            boolean useNewKey = false;
            boolean useOldKey = false;
            for (Tile tile : tiles) {
                if (CategoryKey.KEY_COMPAT_MAP.containsKey(tile.category)) {
                    useOldKey = true;
                } else {
                    useNewKey = true;
                    break;
                }
            }
            // Uses only old key, map them to new keys one by one.
            if (useOldKey && !useNewKey) {
                for (Tile tile : tiles) {
                    final String newCategoryKey = CategoryKey.KEY_COMPAT_MAP.get(tile.category);
                    tile.category = newCategoryKey;
                    // move tile to new category.
                    DashboardCategory newCategory = categoryByKeyMap.get(newCategoryKey);
                    if (newCategory == null) {
                        newCategory = new DashboardCategory();
                        categoryByKeyMap.put(newCategoryKey, newCategory);
                    }
                    newCategory.addTile(tile);
                }
            }
        }
    }

    /**
     * Sort the tiles injected from all apps such that if they have the same priority value,
     * they wil lbe sorted by package name.
     * <p/>
     * A list of tiles are considered sorted when their priority value decreases in a linear
     * scan.
     */
    @VisibleForTesting
    synchronized void sortCategories(Context context,
            Map<String, DashboardCategory> categoryByKeyMap) {
        for (Entry<String, DashboardCategory> categoryEntry : categoryByKeyMap.entrySet()) {
            categoryEntry.getValue().sortTiles(context.getPackageName());
        }
    }

    /**
     * Filter out duplicate tiles from category. Duplicate tiles are the ones pointing to the
     * same intent.
     */
    @VisibleForTesting
    synchronized void filterDuplicateTiles(Map<String, DashboardCategory> categoryByKeyMap) {
        for (Entry<String, DashboardCategory> categoryEntry : categoryByKeyMap.entrySet()) {
            final DashboardCategory category = categoryEntry.getValue();
            final int count = category.getTilesCount();
            final Set<ComponentName> components = new ArraySet<>();
            for (int i = count - 1; i >= 0; i--) {
                final Tile tile = category.getTile(i);
                if (tile.intent == null) {
                    continue;
                }
                final ComponentName tileComponent = tile.intent.getComponent();
                if (components.contains(tileComponent)) {
                    category.removeTile(i);
                } else {
                    components.add(tileComponent);
                }
            }
        }
    }

    /**
     * Sort priority value for tiles within a single {@code DashboardCategory}.
     *
     * @see #sortCategories(Context, Map)
     */
    private synchronized void sortCategoriesForExternalTiles(Context context,
            DashboardCategory dashboardCategory) {
        dashboardCategory.sortTiles(context.getPackageName());

    }
}
