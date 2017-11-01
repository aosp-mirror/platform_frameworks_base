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
import android.platform.test.annotations.GlobalPresubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArraySet;

import com.android.internal.os.RoSystemProperties;
import com.android.internal.util.ArrayUtils;
import com.android.server.SystemConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static android.content.pm.PackageManager.GET_PERMISSIONS;
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
     * <p>This test ensures that all signature|privileged permissions are granted to priv-apps.
     * If CONTROL_PRIVAPP_PERMISSIONS_ENFORCE is set, the test also verifies that
     * granted permissions are whitelisted in {@link SystemConfig}
     */
    @Test
    @SmallTest
    @GlobalPresubmit
    public void testPrivAppPermissions() throws PackageManager.NameNotFoundException {
        List<PackageInfo> installedPackages = mPackageManager
                .getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES | GET_PERMISSIONS);
        for (PackageInfo packageInfo : installedPackages) {
            if (!packageInfo.applicationInfo.isPrivilegedApp()
                    || PackageManagerService.PLATFORM_PACKAGE_NAME.equals(packageInfo.packageName)) {
                continue;
            }
            testPackagePrivAppPermission(packageInfo);
        }

    }

    private void testPackagePrivAppPermission(PackageInfo packageInfo)
            throws PackageManager.NameNotFoundException {
        String packageName = packageInfo.packageName;
        ArraySet<String> privAppPermissions = SystemConfig.getInstance()
                .getPrivAppPermissions(packageName);
        if (ArrayUtils.isEmpty(packageInfo.requestedPermissions)) {
            return;
        }
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
                // if privapp permissions are enforced, platform permissions must be whitelisted
                // in SystemConfig
                if (platformPermission && RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_ENFORCE) {
                    assertTrue("Permission " + pName + " should be declared in "
                                    + "privapp-permissions-<category>.xml file for package "
                                    + packageName,
                            privAppPermissions != null && privAppPermissions.contains(pName));
                }
                assertTrue("Permission " + pName + " should be granted to " + packageName, granted);
            }
        }
    }
}
