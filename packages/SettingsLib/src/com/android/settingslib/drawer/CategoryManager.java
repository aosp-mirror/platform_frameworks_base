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
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.settingslib.applications.InterestingConfigChanges;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class CategoryManager {

    private static final String TAG = "CategoryManager";

    private static CategoryManager sInstance;
    private final InterestingConfigChanges mInterestingConfigChanges;

    // Tile cache (key: <packageName, activityName>, value: tile)
    private final Map<Pair<String, String>, Tile> mTileByComponentCache;

    // Tile cache (key: category key, value: category)
    private final Map<String, DashboardCategory> mCategoryByKeyMap;

    private List<DashboardCategory> mCategories;

    public static CategoryManager get(Context context) {
        if (sInstance == null) {
            sInstance = new CategoryManager(context);
        }
        return sInstance;
    }

    CategoryManager(Context context) {
        mTileByComponentCache = new ArrayMap<>();
        mCategoryByKeyMap = new ArrayMap<>();
        mInterestingConfigChanges = new InterestingConfigChanges();
        mInterestingConfigChanges.applyNewConfig(context.getResources());
    }

    public synchronized DashboardCategory getTilesByCategory(Context context, String categoryKey) {
        tryInitCategories(context);

        return mCategoryByKeyMap.get(categoryKey);
    }

    public synchronized List<DashboardCategory> getCategories(Context context) {
        tryInitCategories(context);
        return mCategories;
    }

    public synchronized void reloadAllCategories(Context context) {
        final boolean forceClearCache = mInterestingConfigChanges.applyNewConfig(
                context.getResources());
        mCategories = null;
        tryInitCategories(context, forceClearCache);
    }

    public synchronized void updateCategoryFromBlacklist(Set<ComponentName> tileBlacklist) {
        if (mCategories == null) {
            Log.w(TAG, "Category is null, skipping blacklist update");
        }
        for (int i = 0; i < mCategories.size(); i++) {
            DashboardCategory category = mCategories.get(i);
            for (int j = 0; j < category.tiles.size(); j++) {
                Tile tile = category.tiles.get(j);
                if (tileBlacklist.contains(tile.intent.getComponent())) {
                    category.tiles.remove(j--);
                }
            }
        }
    }

    private synchronized void tryInitCategories(Context context) {
        // Keep cached tiles by default. The cache is only invalidated when InterestingConfigChange
        // happens.
        tryInitCategories(context, false /* forceClearCache */);
    }

    private synchronized void tryInitCategories(Context context, boolean forceClearCache) {
        if (mCategories == null) {
            if (forceClearCache) {
                mTileByComponentCache.clear();
            }
            mCategoryByKeyMap.clear();
            mCategories = TileUtils.getCategories(context, mTileByComponentCache,
                    false /* categoryDefinedInManifest */);
            for (DashboardCategory category : mCategories) {
                mCategoryByKeyMap.put(category.key, category);
            }
        }
    }

}
