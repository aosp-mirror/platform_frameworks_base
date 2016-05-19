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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.ComponentCallbacks2;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.net.Uri;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.documentsui.ThumbnailCache.Result;
import com.android.documentsui.testing.Bitmaps;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ThumbnailCacheTest {

    private static final Uri URI_0 = Uri.parse("content://authority/document/0");
    private static final Uri URI_1 = Uri.parse("content://authority/document/1");

    private static final Point SMALL_SIZE = new Point(1, 1);
    private static final Point MID_SIZE = new Point(2, 2);
    private static final Point LARGE_SIZE = new Point(3, 3);

    private static final Bitmap SMALL_BITMAP = Bitmaps.createTestBitmap(1, 1);
    private static final Bitmap MIDSIZE_BITMAP = Bitmaps.createTestBitmap(2, 2);
    private static final Bitmap LARGE_BITMAP = Bitmaps.createTestBitmap(3, 3);

    private static final long LAST_MODIFIED = 100;

    private static final int CACHE_SIZE_LIMIT =
            MIDSIZE_BITMAP.getByteCount() + LARGE_BITMAP.getByteCount();

    private ThumbnailCache mCache;

    @Before
    public void setUp() {
        mCache = new ThumbnailCache(CACHE_SIZE_LIMIT);
    }

    @Test
    public void testMiss() {
        mCache.putThumbnail(URI_0, MID_SIZE, MIDSIZE_BITMAP, LAST_MODIFIED);

        Result result = mCache.getThumbnail(URI_1, MID_SIZE);

        assertMiss(result);
    }

    @Test
    public void testHit_Exact() {
        mCache.putThumbnail(URI_0, MID_SIZE, MIDSIZE_BITMAP, LAST_MODIFIED);

        Result result = mCache.getThumbnail(URI_0, MID_SIZE);

        assertHitExact(result);
        assertSame(MIDSIZE_BITMAP, result.getThumbnail());
    }

    @Test
    public void testHit_Smaller() {
        mCache.putThumbnail(URI_0, MID_SIZE, MIDSIZE_BITMAP, LAST_MODIFIED);

        Result result = mCache.getThumbnail(URI_0, LARGE_SIZE);

        assertHitSmaller(result);
        assertSame(MIDSIZE_BITMAP, result.getThumbnail());
    }

    @Test
    public void testHit_Larger() {
        mCache.putThumbnail(URI_0, MID_SIZE, MIDSIZE_BITMAP, LAST_MODIFIED);

        Result result = mCache.getThumbnail(URI_0, SMALL_SIZE);

        assertHitLarger(result);
        assertSame(MIDSIZE_BITMAP, result.getThumbnail());
    }

    @Test
    public void testHit_Larger_HasBothSize() {
        mCache.putThumbnail(URI_0, LARGE_SIZE, LARGE_BITMAP, LAST_MODIFIED);
        mCache.putThumbnail(URI_0, SMALL_SIZE, SMALL_BITMAP, LAST_MODIFIED);

        Result result = mCache.getThumbnail(URI_0, MID_SIZE);

        assertHitLarger(result);
        assertSame(LARGE_BITMAP, result.getThumbnail());
    }

    @Test
    public void testHit_Exact_MultiplePut() {
        mCache.putThumbnail(URI_0, MID_SIZE, MIDSIZE_BITMAP, LAST_MODIFIED);

        Bitmap localBitmap = Bitmaps.createTestBitmap(MID_SIZE.x, MID_SIZE.y);
        long localLastModified = LAST_MODIFIED + 100;
        mCache.putThumbnail(URI_0, MID_SIZE, localBitmap, localLastModified);

        Result result = mCache.getThumbnail(URI_0, MID_SIZE);

        assertHitExact(result);
        assertSame(localBitmap, result.getThumbnail());
    }

    @Test
    public void testHit_EqualLastModified() {
        mCache.putThumbnail(URI_0, MID_SIZE, MIDSIZE_BITMAP, LAST_MODIFIED);

        Result result = mCache.getThumbnail(URI_0, MID_SIZE);

        assertEquals(LAST_MODIFIED, result.getLastModified());
    }

    @Test
    public void testEvictOldest_SizeExceeded() {
        mCache.putThumbnail(URI_0, MID_SIZE, MIDSIZE_BITMAP, LAST_MODIFIED);
        mCache.putThumbnail(URI_1, SMALL_SIZE, SMALL_BITMAP, LAST_MODIFIED);
        mCache.putThumbnail(URI_1, LARGE_SIZE, LARGE_BITMAP, LAST_MODIFIED);

        Result result = mCache.getThumbnail(URI_0, MID_SIZE);

        assertMiss(result);
    }

    @Test
    public void testCacheShrink_OnTrimMemory_Moderate() {
        mCache.putThumbnail(URI_0, MID_SIZE, MIDSIZE_BITMAP, LAST_MODIFIED);
        mCache.putThumbnail(URI_0, SMALL_SIZE, SMALL_BITMAP, LAST_MODIFIED);
        mCache.putThumbnail(URI_0, LARGE_SIZE, LARGE_BITMAP, LAST_MODIFIED);

        mCache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE);

        Result result = mCache.getThumbnail(URI_0, MID_SIZE);
        assertMiss(result);
    }

    @Test
    public void testCacheShrink_OnTrimMemory_Background() {
        mCache.putThumbnail(URI_0, LARGE_SIZE, LARGE_BITMAP, LAST_MODIFIED);
        mCache.putThumbnail(URI_0, SMALL_SIZE, SMALL_BITMAP, LAST_MODIFIED);

        mCache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);

        // Math here (size of each pixel omitted):
        // Limit = midSize + largeSize = 2 * 2 + 3 * 3 = 13, so after all putThumbnail the cache is
        // full.
        //
        // HalfLimit = Limit / 2 = 5. After evicting largeSize bitmap, cache size decreases to 4,
        // which is smaller than 5. Then smallSize bitmap remains.
        Result result = mCache.getThumbnail(URI_0, MID_SIZE);
        assertHitSmaller(result);
        assertSame(SMALL_BITMAP, result.getThumbnail());
    }

    private static void assertMiss(Result result) {
        assertEquals(Result.CACHE_MISS, result.getStatus());
        assertFalse(result.isExactHit());
        assertFalse(result.isHit());
    }

    private static void assertHitExact(Result result) {
        assertEquals(Result.CACHE_HIT_EXACT, result.getStatus());
        assertTrue(result.isExactHit());
        assertTrue(result.isHit());
    }

    private static void assertHitSmaller(Result result) {
        assertEquals(Result.CACHE_HIT_SMALLER, result.getStatus());
        assertFalse(result.isExactHit());
        assertTrue(result.isHit());
    }

    private static void assertHitLarger(Result result) {
        assertEquals(Result.CACHE_HIT_LARGER, result.getStatus());
        assertFalse(result.isExactHit());
        assertTrue(result.isHit());
    }
}
