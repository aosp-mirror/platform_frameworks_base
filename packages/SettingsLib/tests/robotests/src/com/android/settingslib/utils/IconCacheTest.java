/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.utils;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class IconCacheTest {
    private Icon mIcon;
    private Context mContext;
    private IconCache mIconCache;

    @Before
    public void setUp() {
        mContext = spy(RuntimeEnvironment.application);
        mIcon = mock(Icon.class);
        Drawable drawable = mock(Drawable.class);
        doReturn(drawable).when(mIcon).loadDrawable(mContext);
        mIconCache = new IconCache(mContext);
    }

    @Test
    public void testGetIcon_iconisNull() {
        assertThat(mIconCache.getIcon(null)).isNull();
    }

    @Test
    public void testGetIcon_iconAlreadyLoaded() {
        mIconCache.getIcon(mIcon);
        verify(mIcon, times(1)).loadDrawable(mContext);
        mIconCache.getIcon(mIcon);
        verify(mIcon, times(1)).loadDrawable(mContext);
    }

    @Test
    public void testGetIcon_iconLoadedFirstTime() {
        mIconCache.getIcon(mIcon);
        assertTrue(mIconCache.mMap.containsKey(mIcon));
    }
}
