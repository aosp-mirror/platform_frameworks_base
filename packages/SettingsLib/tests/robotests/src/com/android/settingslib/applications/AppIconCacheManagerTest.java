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
    private static final int APP_UID = 9999;

    @Mock
    private Drawable mIcon;

    private AppIconCacheManager mAppIconCacheManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mAppIconCacheManager = AppIconCacheManager.getInstance();
        doReturn(10).when(mIcon).getIntrinsicHeight();
        doReturn(10).when(mIcon).getIntrinsicWidth();
        doReturn(mIcon).when(mIcon).mutate();
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
}
