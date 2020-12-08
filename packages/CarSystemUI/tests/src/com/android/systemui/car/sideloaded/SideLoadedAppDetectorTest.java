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

package com.android.systemui.car.sideloaded;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableResources;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarSystemUiTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class SideLoadedAppDetectorTest extends SysuiTestCase {

    private static final String SAFE_VENDOR = "com.safe.vendor";
    private static final String UNSAFE_VENDOR = "com.unsafe.vendor";
    private static final String APP_PACKAGE_NAME = "com.test";
    private static final String APP_CLASS_NAME = ".TestClass";

    private SideLoadedAppDetector mSideLoadedAppDetector;

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private CarDeviceProvisionedController mCarDeviceProvisionedController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        TestableResources testableResources = mContext.getOrCreateTestableResources();
        String[] allowedAppInstallSources = new String[] {SAFE_VENDOR};
        testableResources.addOverride(R.array.config_allowedAppInstallSources,
                allowedAppInstallSources);

        mSideLoadedAppDetector = new SideLoadedAppDetector(testableResources.getResources(),
                mPackageManager,
                mCarDeviceProvisionedController);
    }

    @Test
    public void isSafe_systemApp_returnsTrue() throws Exception {
        ActivityManager.StackInfo stackInfo = new ActivityManager.StackInfo();
        stackInfo.topActivity = new ComponentName(APP_PACKAGE_NAME, APP_CLASS_NAME);

        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = APP_PACKAGE_NAME;
        applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;

        when(mPackageManager.getApplicationInfoAsUser(eq(APP_PACKAGE_NAME), anyInt(), any()))
                .thenReturn(applicationInfo);

        assertThat(mSideLoadedAppDetector.isSafe(stackInfo)).isTrue();
    }

    @Test
    public void isSafe_updatedSystemApp_returnsTrue() throws Exception {
        ActivityManager.StackInfo stackInfo = new ActivityManager.StackInfo();
        stackInfo.topActivity = new ComponentName(APP_PACKAGE_NAME, APP_CLASS_NAME);

        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = APP_PACKAGE_NAME;
        applicationInfo.flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;

        when(mPackageManager.getApplicationInfoAsUser(eq(APP_PACKAGE_NAME), anyInt(), any()))
                .thenReturn(applicationInfo);

        assertThat(mSideLoadedAppDetector.isSafe(stackInfo)).isTrue();
    }

    @Test
    public void isSafe_nonSystemApp_withSafeSource_returnsTrue() throws Exception {
        InstallSourceInfo sourceInfo = new InstallSourceInfo(SAFE_VENDOR,
                /* initiatingPackageSigningInfo= */null,
                /* originatingPackageName= */ null,
                /* installingPackageName= */ null);
        ActivityManager.StackInfo stackInfo = new ActivityManager.StackInfo();
        stackInfo.topActivity = new ComponentName(APP_PACKAGE_NAME, APP_CLASS_NAME);

        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = APP_PACKAGE_NAME;

        when(mPackageManager.getApplicationInfoAsUser(eq(APP_PACKAGE_NAME), anyInt(), any()))
                .thenReturn(applicationInfo);
        when(mPackageManager.getInstallSourceInfo(APP_PACKAGE_NAME)).thenReturn(sourceInfo);

        assertThat(mSideLoadedAppDetector.isSafe(stackInfo)).isTrue();
    }

    @Test
    public void isSafe_nonSystemApp_withUnsafeSource_returnsFalse() throws Exception {
        InstallSourceInfo sourceInfo = new InstallSourceInfo(UNSAFE_VENDOR,
                /* initiatingPackageSigningInfo= */null,
                /* originatingPackageName= */ null,
                /* installingPackageName= */ null);
        ActivityManager.StackInfo stackInfo = new ActivityManager.StackInfo();
        stackInfo.topActivity = new ComponentName(APP_PACKAGE_NAME, APP_CLASS_NAME);

        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = APP_PACKAGE_NAME;

        when(mPackageManager.getApplicationInfoAsUser(eq(APP_PACKAGE_NAME), anyInt(), any()))
                .thenReturn(applicationInfo);
        when(mPackageManager.getInstallSourceInfo(APP_PACKAGE_NAME)).thenReturn(sourceInfo);

        assertThat(mSideLoadedAppDetector.isSafe(stackInfo)).isFalse();
    }

    @Test
    public void isSafe_nonSystemApp_withoutSource_returnsFalse() throws Exception {
        InstallSourceInfo sourceInfo = new InstallSourceInfo(null,
                /* initiatingPackageSigningInfo= */null,
                /* originatingPackageName= */ null,
                /* installingPackageName= */ null);
        ActivityManager.StackInfo stackInfo = new ActivityManager.StackInfo();
        stackInfo.topActivity = new ComponentName(APP_PACKAGE_NAME, APP_CLASS_NAME);

        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = APP_PACKAGE_NAME;

        when(mPackageManager.getApplicationInfoAsUser(eq(APP_PACKAGE_NAME), anyInt(), any()))
                .thenReturn(applicationInfo);
        when(mPackageManager.getInstallSourceInfo(APP_PACKAGE_NAME)).thenReturn(sourceInfo);

        assertThat(mSideLoadedAppDetector.isSafe(stackInfo)).isFalse();
    }
}
