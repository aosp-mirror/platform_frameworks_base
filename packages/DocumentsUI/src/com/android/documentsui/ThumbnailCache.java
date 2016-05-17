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
 * limitations under the License.
 */

package com.android.documentsui;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.ComponentCallbacks2;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.net.Uri;
import android.util.LruCache;
import android.util.Pair;
import android.util.Pools;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Comparator;
import java.util.HashMap;
import java.util.TreeMap;

/**
 * An LRU cache that supports finding the thumbnail of the requested uri with a different size than
 * the requested one.
 */
public class ThumbnailCache {

    private static final SizeComparator SIZE_COMPARATOR = new SizeComparator();

    /**
     * A 2-dimensional index into {@link #mCache} entries. Pair<Uri, Point> is the key to
     * {@link #mCache}. TreeMap is used to search the closest size to a given size and a given uri.
     */
    private final HashMap<Uri, TreeMap<Point, Pair<Uri, Point>>> mSizeIndex;
    private final Cache mCache;

    /**
     * Creates a thumbnail LRU cache.
     *
     * @param maxCacheSizeInBytes the maximum size of thumbnails in bytes this cache can hold.
     */
    public ThumbnailCache(int maxCacheSizeInBytes) {
        mSizeIndex = new HashMap<>();
        mCache = new Cache(maxCacheSizeInBytes);
    }

    /**
     * Obtains thumbnail given a uri and a size.
     *
     * @param uri the uri of the thumbnail in need
     * @param size the desired size of the thumbnail
     * @return the thumbnail result
     */
    public Result getThumbnail(Uri uri, Point size) {
        Result result = Result.obtain(Result.CACHE_MISS, null, null);

        TreeMap<Point, Pair<Uri, Point>> sizeMap;
        sizeMap = mSizeIndex.get(uri);
        if (sizeMap == null || sizeMap.isEmpty()) {
            // There is not any thumbnail for this uri.
            return result;
        }

        // Look for thumbnail of the same size.
        Pair<Uri, Point> cacheKey = sizeMap.get(size);
        if (cacheKey != null) {
            Bitmap thumbnail = mCache.get(cacheKey);
            if (thumbnail != null) {
                result.mStatus = Result.CACHE_HIT_EXACT;
                result.mThumbnail = thumbnail;
                result.mSize = size;
                return result;
            }
        }

        // Look for thumbnail of bigger sizes.
        Point otherSize = sizeMap.higherKey(size);
        if (otherSize != null) {
            cacheKey = sizeMap.get(otherSize);

            if (cacheKey != null) {
                Bitmap thumbnail = mCache.get(cacheKey);
                if (thumbnail != null) {
                    result.mStatus = Result.CACHE_HIT_LARGER;
                    result.mThumbnail = thumbnail;
                    result.mSize = otherSize;
                    return result;
                }
            }
        }

        // Look for thumbnail of smaller sizes.
        otherSize = sizeMap.lowerKey(size);
        if (otherSize != null) {
            cacheKey = sizeMap.get(otherSize);

            if (cacheKey != null) {
                Bitmap thumbnail = mCache.get(cacheKey);
                if (thumbnail != null) {
                    result.mStatus = Result.CACHE_HIT_SMALLER;
                    result.mThumbnail = thumbnail;
                    result.mSize = otherSize;
                    return result;
                }
            }
        }

        // Cache miss.
        return result;
    }

    public void putThumbnail(Uri uri, Point size, Bitmap thumbnail) {
        Pair<Uri, Point> cacheKey = Pair.create(uri, size);

        TreeMap<Point, Pair<Uri, Point>> sizeMap;
        synchronized (mSizeIndex) {
            sizeMap = mSizeIndex.get(uri);
            if (sizeMap == null) {
                sizeMap = new TreeMap<>(SIZE_COMPARATOR);
                mSizeIndex.put(uri, sizeMap);
            }
        }

        mCache.put(cacheKey, thumbnail);
        synchronized (sizeMap) {
            sizeMap.put(size, cacheKey);
        }
    }

    public void onTrimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            synchronized (mSizeIndex) {
                mSizeIndex.clear();
            }
            mCache.evictAll();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            mCache.trimToSize(mCache.size() / 2);
        }
    }

    /**
     * A class that holds thumbnail and cache status.
     */
    public static final class Result {

        @Retention(RetentionPolicy.SOURCE)
        @IntDef({CACHE_MISS, CACHE_HIT_EXACT, CACHE_HIT_SMALLER, CACHE_HIT_LARGER})
        @interface Status {}

        /**
         * Indicates there is no thumbnail for the requested uri. The thumbnail will be null.
         */
        public static final int CACHE_MISS = 0;
        /**
         * Indicates the thumbnail matches the requested size and requested uri.
         */
        public static final int CACHE_HIT_EXACT = 1;
        /**
         * Indicates the thumbnail is in a smaller size than the requested one from the requested
         * uri.
         */
        public static final int CACHE_HIT_SMALLER = 2;
        /**
         * Indicates the thumbnail is in a larger size than the requested one from the requested
         * uri.
         */
        public static final int CACHE_HIT_LARGER = 3;

        private static final Pools.SimplePool<Result> sPool = new Pools.SimplePool<>(1);

        private @Status int mStatus;

        private @Nullable Bitmap mThumbnail;

        private @Nullable Point mSize;

        private static Result obtain(@Status int status, @Nullable Bitmap thumbnail,
                @Nullable Point size) {
            Result instance = sPool.acquire();
            instance = (instance != null ? instance : new Result());

            instance.mStatus = status;
            instance.mThumbnail = thumbnail;
            instance.mSize = size;

            return instance;
        }

        private Result() {
        }

        public void recycle() {
            mStatus = -1;
            mThumbnail = null;
            mSize = null;

            boolean released = sPool.release(this);
            // This assert is used to guarantee we won't generate too many instances that can't be
            // held in the pool, which indicates our pool size is too small.
            //
            // Right now one instance is enough because we expect all instances are only used in
            // main thread.
            assert (released);
        }

        public @Status int getStatus() {
            return mStatus;
        }

        public @Nullable Bitmap getThumbnail() {
            return mThumbnail;
        }

        public @Nullable Point getSize() {
            return mSize;
        }

        public boolean isHit() {
            return (mStatus != CACHE_MISS);
        }

        public boolean isExactHit() {
            return (mStatus == CACHE_HIT_EXACT);
        }
    }

    private static final class Cache extends LruCache<Pair<Uri, Point>, Bitmap> {
        private Cache(int maxSizeBytes) {
            super(maxSizeBytes);
        }

        @Override
        protected int sizeOf(Pair<Uri, Point> key, Bitmap value) {
            return value.getByteCount();
        }
    }

    private static final class SizeComparator implements Comparator<Point> {
        @Override
        public int compare(Point size0, Point size1) {
            // Assume all sizes are roughly square, so we only compare them in one dimension.
            return size0.x - size1.x;
        }
    }
}
