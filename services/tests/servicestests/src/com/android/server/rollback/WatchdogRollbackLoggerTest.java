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

package com.android.server.rollback;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Bundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class WatchdogRollbackLoggerTest {

    private static final VersionedPackage sTestPackageV1 = new VersionedPackage("test.package", 1);
    private Context mMockContext = mock(Context.class);
    private PackageManager mMockPm;
    private ApplicationInfo mApplicationInfo;
    private PackageInfo mPackageInfo;

    private static final String LOGGING_PARENT_KEY = "android.content.pm.LOGGING_PARENT";

    @Before
    public void setUp() {
        mApplicationInfo = new ApplicationInfo();
        mMockPm = mock(PackageManager.class);
        when(mMockContext.getPackageManager()).thenReturn(mMockPm);
        PackageInstaller mockPi = mock(PackageInstaller.class);
        when(mMockPm.getPackageInstaller()).thenReturn(mockPi);
        PackageInstaller.SessionInfo mockSessionInfo = mock(PackageInstaller.SessionInfo.class);
        when(mockPi.getSessionInfo(anyInt())).thenReturn(mockSessionInfo);
        mPackageInfo = new PackageInfo();
    }

    /**
     * Ensures that the original package is returned if the application info has no metadata.
     */
    @Test
    public void testLogPackageHasNoMetadata() throws Exception {
        when(mMockPm.getApplicationInfo(anyString(), anyInt())).thenReturn(mApplicationInfo);
        VersionedPackage logPackage = WatchdogRollbackLogger.getLogPackage(mMockContext,
                sTestPackageV1);
        assertThat(logPackage).isEqualTo(sTestPackageV1);
    }

    /**
     * Ensures the original package is returned if the application info does not contain a logging
     * parent key.
     */
    @Test
    public void testLogPackageParentKeyIsNull() throws Exception {
        when(mMockPm.getApplicationInfo(anyString(), anyInt())).thenReturn(mApplicationInfo);
        Bundle bundle = new Bundle();
        bundle.putString(LOGGING_PARENT_KEY, null);
        VersionedPackage logPackage = WatchdogRollbackLogger.getLogPackage(mMockContext,
                sTestPackageV1);
        assertThat(logPackage).isEqualTo(sTestPackageV1);
    }

    /**
     * Ensures that the logging parent is returned as the logging package, if it exists.
     */
    @Test
    public void testLogPackageHasParentKey() throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString(LOGGING_PARENT_KEY, "logging.parent");
        mApplicationInfo.metaData = bundle;
        mPackageInfo.setLongVersionCode(12345L);
        when(mMockPm.getApplicationInfo(anyString(), anyInt())).thenReturn(mApplicationInfo);
        when(mMockPm.getPackageInfo(anyString(), anyInt())).thenReturn(mPackageInfo);
        VersionedPackage logPackage = WatchdogRollbackLogger.getLogPackage(mMockContext,
                sTestPackageV1);
        VersionedPackage expectedLogPackage = new VersionedPackage("logging.parent", 12345);
        assertThat(logPackage).isEqualTo(expectedLogPackage);
    }

    /**
     * Ensures that the original package is returned if Package Manager does not know about the
     * logging parent.
     */
    @Test
    public void testLogPackageNameNotFound() throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString("android.content.pm.LOGGING_PARENT", "logging.parent");
        mApplicationInfo.metaData = bundle;
        when(mMockPm.getPackageInfo(anyString(), anyInt())).thenThrow(
                new PackageManager.NameNotFoundException());
        VersionedPackage logPackage = WatchdogRollbackLogger.getLogPackage(mMockContext,
                sTestPackageV1);
        assertThat(logPackage).isEqualTo(sTestPackageV1);
    }
}
