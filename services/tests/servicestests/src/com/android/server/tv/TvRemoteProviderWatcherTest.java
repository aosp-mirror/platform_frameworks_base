/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.tv;

import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Looper;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
public class TvRemoteProviderWatcherTest {
    private static final String TV_REMOTE_SERVICE_PACKAGE_NAME =
            "com.google.android.tv.remote.service";

    @Mock
    Context mMockContext;
    @Mock
    PackageManager mMockPackageManager;
    @Mock
    Resources mMockResources;

    private TvRemoteProviderWatcher mTvRemoteProviderWatcher;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockContext.getResources()).thenReturn(mMockResources);

        when(mMockResources.getString(com.android.internal.R.string.config_tvRemoteServicePackage))
                .thenReturn(TV_REMOTE_SERVICE_PACKAGE_NAME);


        when(mMockPackageManager.checkPermission(
                argThat(not(Manifest.permission.TV_VIRTUAL_REMOTE_CONTROLLER)),
                anyString())).thenReturn(
                PackageManager.PERMISSION_DENIED);

        when(mMockPackageManager.checkPermission(
                anyString(),
                argThat(not(TV_REMOTE_SERVICE_PACKAGE_NAME)))).thenReturn(
                PackageManager.PERMISSION_DENIED);

        when(mMockPackageManager.checkPermission(Manifest.permission.TV_VIRTUAL_REMOTE_CONTROLLER,
                TV_REMOTE_SERVICE_PACKAGE_NAME)).thenReturn(PackageManager.PERMISSION_GRANTED);

        mTvRemoteProviderWatcher = new TvRemoteProviderWatcher(mMockContext, new Object());
    }

    @Test
    public void acceptsValidCsvPackageName() {
        // Test intentionally includes empty spacing for a more complex test
        when(mMockResources.getString(com.android.internal.R.string.config_tvRemoteServicePackage))
            .thenReturn(",,foo,  " + TV_REMOTE_SERVICE_PACKAGE_NAME + ",bar, baz,,");
        assertTrue(mTvRemoteProviderWatcher.verifyServiceTrusted(createTvServiceInfo()));
    }

    @Test
    public void rejectsInvalidCsvPackageName() {
        // Checks include empty strings to validate that processing as well
        when(mMockResources.getString(com.android.internal.R.string.config_tvRemoteServicePackage))
            .thenReturn(",,foo,,  ,bar,   baz,,");
        assertFalse(mTvRemoteProviderWatcher.verifyServiceTrusted(createTvServiceInfo()));
    }

    @Test
    public void tvServiceIsTrusted() {
        assertTrue(mTvRemoteProviderWatcher.verifyServiceTrusted(createTvServiceInfo()));
    }

    @Test
    public void permissionIsRequired() {
        ServiceInfo serviceInfo = createTvServiceInfo();
        serviceInfo.permission = null;

        assertFalse(mTvRemoteProviderWatcher.verifyServiceTrusted(serviceInfo));
    }

    @Test
    public void permissionMustBeBindRemote() {
        ServiceInfo serviceInfo = createTvServiceInfo();
        serviceInfo.permission = Manifest.permission.BIND_TV_INPUT;
        assertFalse(mTvRemoteProviderWatcher.verifyServiceTrusted(serviceInfo));
    }

    @Test
    public void packageNameMustMatch() {
        ServiceInfo serviceInfo = createTvServiceInfo();
        serviceInfo.packageName = "some.random.package";
        assertFalse(mTvRemoteProviderWatcher.verifyServiceTrusted(serviceInfo));
    }

    @Test
    public void packageManagerPermissionIsRequired() {
        reset(mMockPackageManager);
        when(mMockPackageManager.checkPermission(anyString(), anyString())).thenReturn(
                PackageManager.PERMISSION_DENIED);

        assertFalse(mTvRemoteProviderWatcher.verifyServiceTrusted(createTvServiceInfo()));
    }

    @Test
    public void whitelistingPackageNameIsRequired() {
        reset(mMockResources);
        when(mMockResources.getString(anyInt())).thenReturn("");

        // Create a new watcher, as the resources are read in the constructor of the class
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        TvRemoteProviderWatcher watcher =
                new TvRemoteProviderWatcher(mMockContext, new Object());
        assertFalse(watcher.verifyServiceTrusted(createTvServiceInfo()));
    }

    private ServiceInfo createTvServiceInfo() {
        ServiceInfo serviceInfo = new ServiceInfo();

        serviceInfo.name = "ATV Remote Service";
        serviceInfo.packageName = TV_REMOTE_SERVICE_PACKAGE_NAME;
        serviceInfo.permission = Manifest.permission.BIND_TV_REMOTE_SERVICE;

        return serviceInfo;
    }
}
