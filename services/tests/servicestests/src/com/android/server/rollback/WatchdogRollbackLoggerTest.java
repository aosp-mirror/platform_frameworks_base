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
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

import java.util.List;

@RunWith(JUnit4.class)
public class WatchdogRollbackLoggerTest {

    private static final VersionedPackage sTestPackageV1 = new VersionedPackage("test.package", 1);
    private Context mMockContext = mock(Context.class);
    private PackageManager mMockPm;
    private ApplicationInfo mApplicationInfo;
    private PackageInfo mPackageInfo;

    private static final String LOGGING_PARENT_KEY = "android.content.pm.LOGGING_PARENT";
    private static final String LOGGING_PARENT_VALUE = "logging.parent";
    private static final int PACKAGE_INFO_FLAGS = PackageManager.MATCH_APEX
            | PackageManager.GET_META_DATA;
    private static final List<String> sFailingPackages =
            List.of("package1", "package2", "package3");

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
     * Ensures that null is returned if the application info has no metadata.
     */
    @Test
    public void testLogPackageHasNoMetadata() throws Exception {
        when(mMockPm.getPackageInfo(anyString(), anyInt())).thenReturn(mPackageInfo);
        VersionedPackage logPackage = WatchdogRollbackLogger.getLogPackage(mMockContext,
                sTestPackageV1);
        assertThat(logPackage).isNull();
        verify(mMockPm, times(1)).getPackageInfo(
                sTestPackageV1.getPackageName(), PACKAGE_INFO_FLAGS);
    }

    /**
     * Ensures that null is returned if the application info does not contain a logging
     * parent key.
     */
    @Test
    public void testLogPackageParentKeyIsNull() throws Exception {
        when(mMockPm.getPackageInfo(anyString(), anyInt())).thenReturn(mPackageInfo);
        Bundle bundle = new Bundle();
        bundle.putString(LOGGING_PARENT_KEY, null);
        mApplicationInfo.metaData = bundle;
        mPackageInfo.applicationInfo = mApplicationInfo;
        VersionedPackage logPackage = WatchdogRollbackLogger.getLogPackage(mMockContext,
                sTestPackageV1);
        assertThat(logPackage).isNull();
        verify(mMockPm, times(1)).getPackageInfo(
                sTestPackageV1.getPackageName(), PACKAGE_INFO_FLAGS);
    }

    /**
     * Ensures that the logging parent is returned as the logging package, if it exists.
     */
    @Test
    public void testLogPackageHasParentKey() throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString(LOGGING_PARENT_KEY, LOGGING_PARENT_VALUE);
        mApplicationInfo.metaData = bundle;
        mPackageInfo.applicationInfo = mApplicationInfo;
        mPackageInfo.setLongVersionCode(12345L);
        when(mMockPm.getPackageInfo(anyString(), anyInt())).thenReturn(mPackageInfo);
        VersionedPackage logPackage = WatchdogRollbackLogger.getLogPackage(mMockContext,
                sTestPackageV1);
        VersionedPackage expectedLogPackage = new VersionedPackage(LOGGING_PARENT_VALUE, 12345);
        assertThat(logPackage).isEqualTo(expectedLogPackage);
        verify(mMockPm, times(1)).getPackageInfo(
                sTestPackageV1.getPackageName(), PACKAGE_INFO_FLAGS);

    }

    /**
     * Ensures that null is returned if Package Manager does not know about the logging parent.
     */
    @Test
    public void testLogPackageNameNotFound() throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString(LOGGING_PARENT_KEY, LOGGING_PARENT_VALUE);
        mApplicationInfo.metaData = bundle;
        mPackageInfo.applicationInfo = mApplicationInfo;
        when(mMockPm.getPackageInfo(anyString(), anyInt())).thenReturn(mPackageInfo);
        when(mMockPm.getPackageInfo(same(LOGGING_PARENT_VALUE), anyInt())).thenThrow(
                new PackageManager.NameNotFoundException());
        VersionedPackage logPackage = WatchdogRollbackLogger.getLogPackage(mMockContext,
                sTestPackageV1);
        assertThat(logPackage).isNull();
        verify(mMockPm, times(1)).getPackageInfo(
                sTestPackageV1.getPackageName(), PACKAGE_INFO_FLAGS);
    }
}
