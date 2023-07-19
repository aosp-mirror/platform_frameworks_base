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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;

import android.graphics.drawable.Drawable;
import android.util.LruCache;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AppIconCacheManagerTest {

    private static final String APP_PACKAGE_NAME = "com.test.app";
    private static final String APP_PACKAGE_NAME2 = "com.test.app2";
    private static final String APP_PACKAGE_NAME3 = "com.test.app3";
    private static final int APP_UID = 9999;

    @Mock
    private Drawable mIcon;
    @Mock
    private Drawable mIcon2;
    @Mock
    private Drawable mIcon3;

    private AppIconCacheManager mAppIconCacheManager;

    private LruCache<String, Drawable> mMockLruCache = new LruCache<String, Drawable>(3) {
        @Override
        protected int sizeOf(String key, Drawable drawable) {
            return 1;
        }
    };

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mAppIconCacheManager = AppIconCacheManager.getInstance();
        doReturn(10).when(mIcon).getIntrinsicHeight();
        doReturn(10).when(mIcon).getIntrinsicWidth();
        doReturn(mIcon).when(mIcon).mutate();

        doReturn(10).when(mIcon2).getIntrinsicHeight();
        doReturn(10).when(mIcon2).getIntrinsicWidth();
        doReturn(mIcon2).when(mIcon2).mutate();

        doReturn(10).when(mIcon3).getIntrinsicHeight();
        doReturn(10).when(mIcon3).getIntrinsicWidth();
        doReturn(mIcon3).when(mIcon3).mutate();
    }

    @After
    public void tearDown() {
        AppIconCacheManager.release();
    }

    @Test
    public void get_invalidPackageOrUid_shouldReturnNull() {
        assertThat(mAppIconCacheManager.get(/* packageName= */ null, /* uid= */ -1)).isNull();
    }

    @Test
    public void put_invalidPackageOrUid_shouldNotCrash() {
        mAppIconCacheManager.put(/* packageName= */ null, /* uid= */ 0, mIcon);
        // no crash
    }

    @Test
    public void put_invalidIcon_shouldNotCacheIcon() {
        mAppIconCacheManager.put(APP_PACKAGE_NAME, APP_UID, /* drawable= */ null);

        assertThat(mAppIconCacheManager.get(APP_PACKAGE_NAME, APP_UID)).isNull();
    }

    @Test
    public void put_invalidIconSize_shouldNotCacheIcon() {
        doReturn(-1).when(mIcon).getIntrinsicHeight();
        doReturn(-1).when(mIcon).getIntrinsicWidth();

        mAppIconCacheManager.put(APP_PACKAGE_NAME, APP_UID, mIcon);

        assertThat(mAppIconCacheManager.get(APP_PACKAGE_NAME, APP_UID)).isNull();
    }

    @Test
    public void put_shouldCacheIcon() {
        mAppIconCacheManager.put(APP_PACKAGE_NAME, APP_UID, mIcon);

        assertThat(mAppIconCacheManager.get(APP_PACKAGE_NAME, APP_UID)).isEqualTo(mIcon);
    }

    @Test
    public void release_noInstance_shouldNotCrash() {
        mAppIconCacheManager = null;

        AppIconCacheManager.release();
        // no crash
    }

    @Test
    public void release_existInstance_shouldClearCache() {
        mAppIconCacheManager.put(APP_PACKAGE_NAME, APP_UID, mIcon);

        AppIconCacheManager.release();

        assertThat(mAppIconCacheManager.get(APP_PACKAGE_NAME, APP_UID)).isNull();
    }

    @Test
    public void trimMemory_levelSatisfied_shouldNotCacheIcon() {
        // We mock the maxSize is 3, and the size of each element is 1
        mAppIconCacheManager.mockLruCache(mMockLruCache);

        mAppIconCacheManager.put(APP_PACKAGE_NAME, APP_UID, mIcon);
        mAppIconCacheManager.put(APP_PACKAGE_NAME2, APP_UID, mIcon2);
        mAppIconCacheManager.put(APP_PACKAGE_NAME3, APP_UID, mIcon3);

        // Trim size to 0
        final int level = android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
        mAppIconCacheManager.trimMemory(level);

        assertThat(mAppIconCacheManager.get(APP_PACKAGE_NAME, APP_UID)).isNull();
        assertThat(mAppIconCacheManager.get(APP_PACKAGE_NAME2, APP_UID)).isNull();
        assertThat(mAppIconCacheManager.get(APP_PACKAGE_NAME3, APP_UID)).isNull();
    }

    @Test
    public void trimMemory_levelSatisfied_shouldCacheAtLeastHalf() {
        // We mock the maxSize is 3, and the size of each element is 1
        mAppIconCacheManager.mockLruCache(mMockLruCache);

        mAppIconCacheManager.put(APP_PACKAGE_NAME, APP_UID, mIcon);
        mAppIconCacheManager.put(APP_PACKAGE_NAME2, APP_UID, mIcon2);
        mAppIconCacheManager.put(APP_PACKAGE_NAME3, APP_UID, mIcon3);

        // Get the last item
        mAppIconCacheManager.get(APP_PACKAGE_NAME, APP_UID);

        // Trim size to int( 3 / 2 ) = 1
        final int level = android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
        mAppIconCacheManager.trimMemory(level);

        assertThat(mAppIconCacheManager.get(APP_PACKAGE_NAME, APP_UID)).isNotNull();
        assertThat(mAppIconCacheManager.get(APP_PACKAGE_NAME2, APP_UID)).isNull();
        assertThat(mAppIconCacheManager.get(APP_PACKAGE_NAME3, APP_UID)).isNull();
    }

}
