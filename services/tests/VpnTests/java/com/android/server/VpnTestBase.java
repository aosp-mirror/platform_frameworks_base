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

package com.android.server;

import static android.content.pm.UserInfo.FLAG_ADMIN;
import static android.content.pm.UserInfo.FLAG_MANAGED_PROFILE;
import static android.content.pm.UserInfo.FLAG_PRIMARY;
import static android.content.pm.UserInfo.FLAG_RESTRICTED;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArrayMap;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Common variables or methods shared between VpnTest and VpnManagerServiceTest. */
public class VpnTestBase {
    protected static final String TEST_VPN_PKG = "com.testvpn.vpn";
    /**
     * Names and UIDs for some fake packages. Important points:
     *  - UID is ordered increasing.
     *  - One pair of packages have consecutive UIDs.
     */
    protected static final String[] PKGS = {"com.example", "org.example", "net.example", "web.vpn"};
    protected static final int[] PKG_UIDS = {10066, 10077, 10078, 10400};
    // Mock packages
    protected static final Map<String, Integer> sPackages = new ArrayMap<>();
    static {
        for (int i = 0; i < PKGS.length; i++) {
            sPackages.put(PKGS[i], PKG_UIDS[i]);
        }
        sPackages.put(TEST_VPN_PKG, Process.myUid());
    }

    // Mock users
    protected static final int SYSTEM_USER_ID = 0;
    protected static final UserInfo SYSTEM_USER = new UserInfo(0, "system", UserInfo.FLAG_PRIMARY);
    protected static final UserInfo PRIMARY_USER = new UserInfo(27, "Primary",
            FLAG_ADMIN | FLAG_PRIMARY);
    protected static final UserInfo SECONDARY_USER = new UserInfo(15, "Secondary", FLAG_ADMIN);
    protected static final UserInfo RESTRICTED_PROFILE_A = new UserInfo(40, "RestrictedA",
            FLAG_RESTRICTED);
    protected static final UserInfo RESTRICTED_PROFILE_B = new UserInfo(42, "RestrictedB",
            FLAG_RESTRICTED);
    protected static final UserInfo MANAGED_PROFILE_A = new UserInfo(45, "ManagedA",
            FLAG_MANAGED_PROFILE);
    static {
        RESTRICTED_PROFILE_A.restrictedProfileParentId = PRIMARY_USER.id;
        RESTRICTED_PROFILE_B.restrictedProfileParentId = SECONDARY_USER.id;
        MANAGED_PROFILE_A.profileGroupId = PRIMARY_USER.id;
    }

    // Populate a fake packageName-to-UID mapping.
    protected void setMockedPackages(PackageManager mockPm, final Map<String, Integer> packages) {
        try {
            doAnswer(invocation -> {
                final String appName = (String) invocation.getArguments()[0];
                final int userId = (int) invocation.getArguments()[1];

                final Integer appId = packages.get(appName);
                if (appId == null) {
                    throw new PackageManager.NameNotFoundException(appName);
                }

                return UserHandle.getUid(userId, appId);
            }).when(mockPm).getPackageUidAsUser(anyString(), anyInt());
        } catch (Exception e) {
        }
    }

    protected List<Integer> toList(int[] arr) {
        return Arrays.stream(arr).boxed().collect(Collectors.toList());
    }
}
