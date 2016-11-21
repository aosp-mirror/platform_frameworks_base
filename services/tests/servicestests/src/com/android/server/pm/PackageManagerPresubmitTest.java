/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.pm;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArraySet;

import com.android.internal.os.RoSystemProperties;
import com.android.server.SystemConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static junit.framework.Assert.assertTrue;


/**
 * Presubmit tests for {@link PackageManager}.
 */
@RunWith(AndroidJUnit4.class)
public class PackageManagerPresubmitTest {

    private Context mContext;

    private PackageManager mPackageManager;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
        mPackageManager = mContext.getPackageManager();
    }

    /**
     * <p>This test ensures that all signature|privileged permissions are granted to core apps like
     * systemui/settings. If CONTROL_PRIVAPP_PERMISSIONS is set, the test also verifies that
     * granted permissions are whitelisted in {@link SystemConfig}
     */
    @Test
    @SmallTest
    @Presubmit
    public void testPrivAppPermissions() throws PackageManager.NameNotFoundException {
        String[] testPackages = {"com.android.settings", "com.android.shell",
                "com.android.systemui"};
        for (String testPackage : testPackages) {
            testPackagePrivAppPermission(testPackage);
        }
    }

    private void testPackagePrivAppPermission(String testPackage)
            throws PackageManager.NameNotFoundException {
        PackageInfo packageInfo = mPackageManager.getPackageInfo(testPackage,
                PackageManager.GET_PERMISSIONS);
        ArraySet<String> privAppPermissions = SystemConfig.getInstance()
                .getPrivAppPermissions(testPackage);
        for (int i = 0; i < packageInfo.requestedPermissions.length; i++) {
            String pName = packageInfo.requestedPermissions[i];
            int protectionLevel;
            boolean platformPermission;
            try {
                PermissionInfo permissionInfo = mPackageManager.getPermissionInfo(pName, 0);
                platformPermission = PackageManagerService.PLATFORM_PACKAGE_NAME.equals(
                        permissionInfo.packageName);
                protectionLevel = permissionInfo.protectionLevel;
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            if ((protectionLevel & PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0) {
                boolean granted = (packageInfo.requestedPermissionsFlags[i]
                        & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                assertTrue("Permission " + pName + " should be granted to " + testPackage, granted);
                // if CONTROL_PRIVAPP_PERMISSIONS enabled, platform permissions must be whitelisted
                // in SystemConfig
                if (platformPermission && RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS) {
                    assertTrue("Permission " + pName
                                    + " should be declared in the xml file for package "
                                    + testPackage,
                            privAppPermissions.contains(pName));
                }
            }
        }
    }
}
