/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class DefaultAppInfoTest {

    @Mock
    private PackageItemInfo mPackageItemInfo;
    @Mock
    private ComponentName mComponentName;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private ApplicationInfo mApplicationInfo;
    @Mock
    private Drawable mIcon;

    private Context mContext;
    private DefaultAppInfo mInfo;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        MockitoAnnotations.initMocks(this);
        mContext = spy(RuntimeEnvironment.application);
        doReturn(mPackageManager).when(mContext).getPackageManager();
        when(mPackageManager.getApplicationInfoAsUser(anyString(), anyInt(),
                anyInt())).thenReturn(mApplicationInfo);
        when(mPackageManager.loadUnbadgedItemIcon(mPackageItemInfo, mApplicationInfo)).thenReturn(
                mIcon);
    }

    @Test
    public void initInfoWithActivityInfo_shouldLoadInfo() {
        mPackageItemInfo.packageName = "test";
        mInfo = new DefaultAppInfo(mContext, mPackageManager, 0 /* uid */, mPackageItemInfo);
        mInfo.loadLabel();
        Drawable icon = mInfo.loadIcon();

        assertThat(mInfo.getKey()).isEqualTo(mPackageItemInfo.packageName);
        assertThat(icon).isNotNull();
        verify(mPackageItemInfo).loadLabel(mPackageManager);
    }

    @Test
    public void initInfoWithComponent_shouldLoadInfo() {
        when(mComponentName.getPackageName()).thenReturn("com.android.settings");

        mInfo = new DefaultAppInfo(mContext, mPackageManager, 0 /* uid */, mComponentName);
        mInfo.getKey();

        verify(mComponentName).flattenToString();
    }
}
