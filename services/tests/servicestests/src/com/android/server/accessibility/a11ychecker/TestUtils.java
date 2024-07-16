/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.accessibility.a11ychecker;

import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.mockito.Mockito;

public class TestUtils {
    static final String TEST_APP_PACKAGE_NAME = "com.example.app";
    static final int TEST_APP_VERSION_CODE = 12321;
    static final String TEST_ACTIVITY_NAME = "com.example.app.MainActivity";
    static final String TEST_A11Y_SERVICE_SOURCE_PACKAGE_NAME = "com.assistive.app";
    static final String TEST_A11Y_SERVICE_CLASS_NAME = "MyA11yService";
    static final int TEST_A11Y_SERVICE_SOURCE_VERSION_CODE = 333555;
    static final String TEST_WINDOW_TITLE = "Example window";

    static PackageManager getMockPackageManagerWithInstalledApps()
            throws PackageManager.NameNotFoundException {
        PackageManager mockPackageManager = Mockito.mock(PackageManager.class);
        ActivityInfo testActivityInfo = getTestActivityInfo();
        ComponentName testActivityComponentName = new ComponentName(TEST_APP_PACKAGE_NAME,
                TEST_ACTIVITY_NAME);

        when(mockPackageManager.getActivityInfo(testActivityComponentName, 0))
                .thenReturn(testActivityInfo);
        when(mockPackageManager.getPackageInfo(TEST_APP_PACKAGE_NAME, 0))
                .thenReturn(createPackageInfo(TEST_APP_PACKAGE_NAME, TEST_APP_VERSION_CODE,
                        testActivityInfo));
        when(mockPackageManager.getPackageInfo(TEST_A11Y_SERVICE_SOURCE_PACKAGE_NAME, 0))
                .thenReturn(createPackageInfo(TEST_A11Y_SERVICE_SOURCE_PACKAGE_NAME,
                        TEST_A11Y_SERVICE_SOURCE_VERSION_CODE, null));
        return mockPackageManager;
    }

    static ActivityInfo getTestActivityInfo() {
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = TEST_APP_PACKAGE_NAME;
        activityInfo.name = TEST_ACTIVITY_NAME;
        return activityInfo;
    }

    static PackageInfo createPackageInfo(String packageName, int versionCode,
            @Nullable ActivityInfo activityInfo) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.setLongVersionCode(versionCode);
        if (activityInfo != null) {
            packageInfo.activities = new ActivityInfo[]{activityInfo};
        }
        return packageInfo;

    }
}
