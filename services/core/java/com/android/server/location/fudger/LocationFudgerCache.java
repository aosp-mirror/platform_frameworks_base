/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.location.fudger;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.location.flags.Flags;
import android.location.provider.IS2CellIdsCallback;
import android.location.provider.IS2LevelCallback;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.location.geometry.S2CellIdUtils;
import com.android.server.location.provider.proxy.ProxyPopulationDensityProvider;

import java.util.Objects;

/**
 * A cache for returning the coarsening level to be used. The coarsening level depends on the user
 * location. If the cache contains the requested latitude/longitude, the s2 level of the cached
 * cell id is returned. If not, a default value is returned.
 * This class has a {@link ProxyPopulationDensityProvider} used to refresh the cache.
 * This cache exists because {@link ProxyPopulationDensityProvider} must be queried asynchronously,
 * whereas a synchronous answer is needed.
 * The cache is first-in, first-out, and has a fixed size. Cache entries are valid until evicted by
 * another value.
 */
@FlaggedApi(Flags.FLAG_POPULATION_DENSITY_PROVIDER)
public class LocationFudgerCache {

    // The maximum number of S2 cell ids stored in the cache.
    // Each cell id is a long, so the memory requirement is 8*MAX_CACHE_SIZE bytes.
    protected static final int MAX_CACHE_SIZE = 20;

    private final Object mLock = new Object();

    // mCache is a circular buffer of size MAX_CACHE_SIZE. The next position to be written to is
    // mPosInCache. Initially, the cache is filled with INVALID_CELL_IDs.
    @GuardedBy("mLock")
    private final long[] mCache = new long[MAX_CACHE_SIZE];

    @GuardedBy("mLock")
    private int mPosInCache = 0;

    @GuardedBy("mLock")
    private int mCacheSize = 0;

    // The S2 level to coarsen to, if the cache doesn't contain a better answer.
    // Updated concurrently by callbacks.
    @GuardedBy("mLock")
    private Integer mDefaultCoarseningLevel = null;

    // The provider that asynchronously provides what is stored in the cache.
    private final ProxyPopulationDensityProvider mPopulationDensityProvider;

    private static String sTAG = "LocationFudgerCache";

    public LocationFudgerCache(@NonNull ProxyPopulationDensityProvider provider) {
        mPopulationDensityProvider = Objects.requireNonNull(provider);

        asyncFetchDefaultCoarseningLevel();
    }

    /** Returns true if the cache has successfully received a default value from the provider. */
    public boolean hasDefaultValue() {
        synchronized (mLock) {
            return (mDefaultCoarseningLevel != null);
        }
    }

    /**
     * Returns the S2 level to which the provided location should be coarsened.
     * The answer comes from the cache if available, otherwise the default value is returned.
     */
    public int getCoarseningLevel(double latitudeDegrees, double longitudeDegrees) {
        // If we still haven't received the default level from the provider, try fetching it again.
        // The answer wouldn't come in time, but it will be used for the following queries.
        if (!hasDefaultValue()) {
            asyncFetchDefaultCoarseningLevel();
        }
        Long s2CellId = readCacheForLatLng(latitudeDegrees, longitudeDegrees);
        if (s2CellId == null) {
            // Asynchronously queries the density from the provider. The answer won't come in time,
            // but it will update the cache for the following queries.
            refreshCache(latitudeDegrees, longitudeDegrees);

            return getDefaultCoarseningLevel();
        }
        return S2CellIdUtils.getLevel(s2CellId);
    }

    /**
     * If the cache contains the current location, returns the corresponding S2 cell id.
     * Otherwise, returns null.
     */
    @Nullable
    private Long readCacheForLatLng(double latDegrees, double lngDegrees) {
        synchronized (mLock) {
            for (int i = 0; i < mCacheSize; i++) {
                if (S2CellIdUtils.containsLatLngDegrees(mCache[i], latDegrees, lngDegrees)) {
                    return mCache[i];
                }
            }
        }
        return null;
    }

    /** Adds the provided s2 cell id to the cache. This might evict other values from the cache. */
    public void addToCache(long s2CellId) {
        addToCache(new long[] {s2CellId});
    }

    /**
     * Adds the provided s2 cell ids to the cache. This might evict other values from the cache.
     * If more than MAX_CACHE_SIZE elements are provided, only the first elements are copied.
     * The first element of the input is added last into the FIFO cache, so it gets evicted last.
     */
    public void addToCache(long[] s2CellIds) {
        synchronized (mLock) {
            // Only copy up to MAX_CACHE_SIZE elements
            int end = Math.min(s2CellIds.length, MAX_CACHE_SIZE);
            mCacheSize = Math.min(mCacheSize + end, MAX_CACHE_SIZE);

            // Add in reverse so the first cell of s2CellIds is the last evicted
            for (int i = end - 1; i >= 0; i--) {
                mCache[mPosInCache] = s2CellIds[i];
                mPosInCache = (mPosInCache + 1) % MAX_CACHE_SIZE;
            }
        }
    }

    /**
     * Queries the population density provider for the default coarsening level (to be used if the
     * cache doesn't contain a better answer), and updates mDefaultCoarseningLevel with the answer.
     */
    private void asyncFetchDefaultCoarseningLevel() {
        IS2LevelCallback callback = new IS2LevelCallback.Stub() {
            @Override
            public void onResult(int s2level) {
                synchronized (mLock) {
                    mDefaultCoarseningLevel = Integer.valueOf(s2level);
                }
            }

            @Override
            public void onError() {
                Log.e(sTAG, "could not get default population density");
            }
        };
        mPopulationDensityProvider.getDefaultCoarseningLevel(callback);
    }

    /**
     *  Queries the population density provider and store the result in the cache.
     */
    private void refreshCache(double latitude, double longitude) {
        IS2CellIdsCallback callback = new IS2CellIdsCallback.Stub() {
            @Override
            public void onResult(long[] s2CellIds) {
                addToCache(s2CellIds);
            }

            @Override
            public void onError() {
                Log.e(sTAG, "could not get population density");
            }
        };
        mPopulationDensityProvider.getCoarsenedS2Cells(latitude, longitude, MAX_CACHE_SIZE - 1,
                callback);
    }

    /**
     * Returns the default S2 level to coarsen to. This should be used if the cache
     * does not provide a better answer.
     */
    private int getDefaultCoarseningLevel() {
        synchronized (mLock) {
            // The minimum valid level is 0.
            if (mDefaultCoarseningLevel == null) {
                return 0;
            }
            return mDefaultCoarseningLevel;
        }
    }
}
