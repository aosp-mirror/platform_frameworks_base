/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.locksettings;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.service.resumeonreboot.ResumeOnRebootService;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

@SmallTest
@RunWith(JUnit4.class)
public class ResumeOnRebootServiceProviderTests {

    @Mock
    Context mMockContext;
    @Mock
    PackageManager mMockPackageManager;

    ResolveInfo mFakeResolvedInfo;
    ServiceInfo mFakeServiceInfo;
    @Captor
    ArgumentCaptor<Intent> mIntentArgumentCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockContext.getUserId()).thenReturn(0);

        mFakeServiceInfo = new ServiceInfo();
        mFakeServiceInfo.packageName = "fakePackageName";
        mFakeServiceInfo.name = "fakeName";

        mFakeResolvedInfo = new ResolveInfo();
        mFakeResolvedInfo.serviceInfo = mFakeServiceInfo;
    }

    @Test
    public void noServiceFound() throws Exception {
        when(mMockPackageManager.queryIntentServices(any(),
                eq(PackageManager.MATCH_SYSTEM_ONLY))).thenReturn(
                null);
        assertThat(new ResumeOnRebootServiceProvider(mMockContext,
                mMockPackageManager).getServiceConnection()).isNull();
    }

    @Test
    public void serviceNotGuardedWithPermission() throws Exception {
        ArrayList<ResolveInfo> resultList = new ArrayList<>();
        mFakeServiceInfo.permission = "";
        resultList.add(mFakeResolvedInfo);
        when(mMockPackageManager.queryIntentServices(any(), anyInt())).thenReturn(resultList);
        assertThat(new ResumeOnRebootServiceProvider(mMockContext,
                mMockPackageManager).getServiceConnection()).isNull();
    }

    @Test
    public void serviceResolved() throws Exception {
        ArrayList<ResolveInfo> resultList = new ArrayList<>();
        resultList.add(mFakeResolvedInfo);
        mFakeServiceInfo.permission = Manifest.permission.BIND_RESUME_ON_REBOOT_SERVICE;
        when(mMockPackageManager.queryIntentServices(any(), anyInt())).thenReturn(resultList);

        assertThat(new ResumeOnRebootServiceProvider(mMockContext,
                mMockPackageManager).getServiceConnection()).isNotNull();

        verify(mMockPackageManager).queryIntentServices(mIntentArgumentCaptor.capture(),
                eq(PackageManager.MATCH_SYSTEM_ONLY | PackageManager.GET_SERVICES));
        assertThat(mIntentArgumentCaptor.getValue().getAction()).isEqualTo(
                ResumeOnRebootService.SERVICE_INTERFACE);
    }
}
