/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.applications;

import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.VisibleForTesting;

/**
 * Cache app icon for management.
 */
public class AppIconCacheManager {
    private static final String TAG = "AppIconCacheManager";
    private static final float CACHE_RATIO = 0.1f;
    private static final int MAX_CACHE_SIZE_IN_KB = getMaxCacheInKb();
    private static final String DELIMITER = ":";
    private static AppIconCacheManager sAppIconCacheManager;
    private LruCache<String, Drawable> mDrawableCache;

    private AppIconCacheManager() {
        mDrawableCache = new LruCache<String, Drawable>(MAX_CACHE_SIZE_IN_KB) {
            @Override
            protected int sizeOf(String key, Drawable drawable) {
                if (drawable instanceof BitmapDrawable) {
                    return ((BitmapDrawable) drawable).getBitmap().getByteCount() / 1024;
                }
                // Rough estimate each pixel will use 4 bytes by default.
                return drawable.getIntrinsicHeight() * drawable.getIntrinsicWidth() * 4 / 1024;
            }
        };
    }

    /**
     * Get an {@link AppIconCacheManager} instance.
     */
    public static synchronized AppIconCacheManager getInstance() {
        if (sAppIconCacheManager == null) {
            sAppIconCacheManager = new AppIconCacheManager();
        }
        return sAppIconCacheManager;
    }

    /**
     * Put app icon to cache
     *
     * @param packageName of icon
     * @param uid         of packageName
     * @param drawable    app icon
     */
    public void put(String packageName, int uid, Drawable drawable) {
        final String key = getKey(packageName, uid);
        if (key == null || drawable == null || drawable.getIntrinsicHeight() < 0
                || drawable.getIntrinsicWidth() < 0) {
            Log.w(TAG, "Invalid key or drawable.");
            return;
        }
        mDrawableCache.put(key, drawable);
    }

    /**
     * Get app icon from cache.
     *
     * @param packageName of icon
     * @param uid         of packageName
     * @return app icon
     */
    public Drawable get(String packageName, int uid) {
        final String key = getKey(packageName, uid);
        if (key == null) {
            Log.w(TAG, "Invalid key with package or uid.");
            return null;
        }
        final Drawable cachedDrawable = mDrawableCache.get(key);
        return cachedDrawable != null ? cachedDrawable.mutate() : null;
    }

    /**
     * Release cache.
     */
    public static void release() {
        if (sAppIconCacheManager != null) {
            sAppIconCacheManager.mDrawableCache.evictAll();
        }
    }

    private static String getKey(String packageName, int uid) {
        if (packageName == null || uid < 0) {
            return null;
        }
        return packageName + DELIMITER + UserHandle.getUserId(uid);
    }

    private static int getMaxCacheInKb() {
        return Math.round(CACHE_RATIO * Runtime.getRuntime().maxMemory() / 1024);
    }

    /**
     * Make LruCache testable, DO NOT call this method in production build.
     */
    @VisibleForTesting
    void mockLruCache(LruCache lruCache) {
        this.mDrawableCache = lruCache;
    }

    /**
     * Clears as much memory as possible.
     *
     * @see android.content.ComponentCallbacks2#onTrimMemory(int)
     */
    public void trimMemory(int level) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            // Time to clear everything
            if (sAppIconCacheManager != null) {
                sAppIconCacheManager.mDrawableCache.trimToSize(0);
            }
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            // tough time but still affordable, clear half of the cache
            if (sAppIconCacheManager != null) {
                final int maxSize = sAppIconCacheManager.mDrawableCache.maxSize();
                sAppIconCacheManager.mDrawableCache.trimToSize(maxSize / 2);
            }
        }
    }
}
