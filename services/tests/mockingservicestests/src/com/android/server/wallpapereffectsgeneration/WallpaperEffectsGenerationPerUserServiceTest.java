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

package com.android.server.wallpapereffectsgeneration;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AppGlobals;
import android.app.wallpapereffectsgeneration.CinematicEffectRequest;
import android.app.wallpapereffectsgeneration.ICinematicEffectListener;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@SuppressLint("GuardedBy")
public class WallpaperEffectsGenerationPerUserServiceTest {
    private static final int BIND_SERVICE_UID = 123;
    @Mock
    PackageManager mMockPackageManager;
    @Mock
    ICinematicEffectListener mListener;

    private Context mContext;
    private IPackageManager mIPackageManager;
    private WallpaperEffectsGenerationPerUserService mService;
    private ServiceInfo mServiceInfo;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = spy(ApplicationProvider.getApplicationContext());
        mIPackageManager = AppGlobals.getPackageManager();
        spyOn(mIPackageManager);
        when(mContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockPackageManager.checkPermission(any(), any()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        ApplicationInfo mockApplicationInfo = new ApplicationInfo();
        mockApplicationInfo.uid = BIND_SERVICE_UID;
        mServiceInfo = new ServiceInfo();
        mServiceInfo.permission = Manifest.permission.BIND_WALLPAPER_EFFECTS_GENERATION_SERVICE;
        mServiceInfo.applicationInfo = mockApplicationInfo;
        when(mIPackageManager.getServiceInfo(any(), anyLong(), anyInt()))
                .thenReturn(mServiceInfo);

        WallpaperEffectsGenerationManagerService managerService =
                new WallpaperEffectsGenerationManagerService(mContext);
        mService = new WallpaperEffectsGenerationPerUserService(
                managerService, new Object(), mContext.getUserId());
    }

    @Test
    public void testIsCallingUidAllowed_returnFalseWhenServiceNotBound() {
        assertNull(mService.getServiceInfo());
        assertFalse(mService.isCallingUidAllowed(BIND_SERVICE_UID));
    }

    @Test
    public void testIsCallingUidAllowed_uidSetCorrectlyAfterFirstCall() {
        CinematicEffectRequest request = new CinematicEffectRequest(
                "test-id", Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888));
        mService.onGenerateCinematicEffectLocked(request, mListener);
        assertNotNull(mService.getServiceInfo());
        assertTrue(mService.isCallingUidAllowed(BIND_SERVICE_UID));
    }
}
