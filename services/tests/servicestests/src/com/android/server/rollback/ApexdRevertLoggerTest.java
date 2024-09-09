/*
 * Copyright (C) 2024 The Android Open Source Project
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


import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import android.os.Bundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

@RunWith(JUnit4.class)
public class ApexdRevertLoggerTest {

    private Context mMockContext = mock(Context.class);
    private PackageManager mMockPm;
    private PackageInfo mPackageInfo;

    private static final String LOGGING_PARENT_KEY = "android.content.pm.LOGGING_PARENT";
    private static final int PACKAGE_INFO_FLAGS = PackageManager.MATCH_APEX
            | PackageManager.GET_META_DATA;
    private static final List<String> sFailingPackages =
            List.of("package1", "package2", "package3");

    @Before
    public void setUp() {
        mMockPm = mock(PackageManager.class);
        when(mMockContext.getPackageManager()).thenReturn(mMockPm);
        PackageInstaller mockPi = mock(PackageInstaller.class);
        when(mMockPm.getPackageInstaller()).thenReturn(mockPi);
        PackageInstaller.SessionInfo mockSessionInfo = mock(PackageInstaller.SessionInfo.class);
        when(mockPi.getSessionInfo(anyInt())).thenReturn(mockSessionInfo);
        mPackageInfo = new PackageInfo();
    }

    /**
     * Ensures that we make the correct Package Manager calls in the case that the failing packages
     * are correctly configured with parent packages.
     */
    @Test
    public void testApexdLoggingCallsWithParents() throws Exception {
        for (String failingPackage: sFailingPackages) {
            PackageInfo packageInfo = new PackageInfo();
            ApplicationInfo applicationInfo = new ApplicationInfo();
            Bundle bundle = new Bundle();
            bundle.putString(LOGGING_PARENT_KEY, getParent(failingPackage));
            applicationInfo.metaData = bundle;
            packageInfo.applicationInfo = applicationInfo;
            when(mMockPm.getPackageInfo(same(failingPackage), anyInt())).thenReturn(packageInfo);
        }

        when(mMockPm.getPackageInfo(anyString(), eq(0))).thenReturn(mPackageInfo);
        ApexdRevertLogger.logApexdRevert(mMockContext, sFailingPackages, "test_process");
        for (String failingPackage: sFailingPackages) {
            verify(mMockPm, times(1)).getPackageInfo(failingPackage, PACKAGE_INFO_FLAGS);
            verify(mMockPm, times(1)).getPackageInfo(getParent(failingPackage), 0);
        }
    }

    /**
     * Ensures that we don't make any calls to parent packages in the case that packages are not
     * correctly configured with parent packages.
     */
    @Test
    public void testApexdLoggingCallsWithNoParents() throws Exception {
        for (String failingPackage: sFailingPackages) {
            PackageInfo packageInfo = new PackageInfo();
            packageInfo.applicationInfo = new ApplicationInfo();
            when(mMockPm.getPackageInfo(same(failingPackage), anyInt())).thenReturn(packageInfo);
        }
        when(mMockPm.getPackageInfo(anyString(), eq(0))).thenReturn(mPackageInfo);

        ApexdRevertLogger.logApexdRevert(mMockContext, sFailingPackages, "test_process");
        verify(mMockPm, times(sFailingPackages.size())).getPackageInfo(anyString(), anyInt());
        for (String failingPackage: sFailingPackages) {
            verify(mMockPm, times(1)).getPackageInfo(failingPackage, PACKAGE_INFO_FLAGS);
        }
    }

    private String getParent(String packageName) {
        return packageName + "-parent";
    }
}
