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
 * limitations under the License.
 */

package com.android.server.connectivity;

import static android.content.pm.UserInfo.FLAG_ADMIN;
import static android.content.pm.UserInfo.FLAG_MANAGED_PROFILE;
import static android.content.pm.UserInfo.FLAG_PRIMARY;
import static android.content.pm.UserInfo.FLAG_RESTRICTED;
import static org.mockito.Mockito.*;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.UidRange;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link Vpn}.
 *
 * Build, install and run with:
 *  runtest --path src/com/android/server/connectivity/VpnTest.java
 */
public class VpnTest extends AndroidTestCase {
    private static final String TAG = "VpnTest";

    // Mock users
    static final UserInfo primaryUser = new UserInfo(27, "Primary", FLAG_ADMIN | FLAG_PRIMARY);
    static final UserInfo secondaryUser = new UserInfo(15, "Secondary", FLAG_ADMIN);
    static final UserInfo restrictedProfileA = new UserInfo(40, "RestrictedA", FLAG_RESTRICTED);
    static final UserInfo restrictedProfileB = new UserInfo(42, "RestrictedB", FLAG_RESTRICTED);
    static final UserInfo managedProfileA = new UserInfo(45, "ManagedA", FLAG_MANAGED_PROFILE);
    static {
        restrictedProfileA.restrictedProfileParentId = primaryUser.id;
        restrictedProfileB.restrictedProfileParentId = secondaryUser.id;
        managedProfileA.profileGroupId = primaryUser.id;
    }

    @Mock private Context mContext;
    @Mock private UserManager mUserManager;
    @Mock private PackageManager mPackageManager;
    @Mock private INetworkManagementService mNetService;

    @Override
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getSystemService(eq(Context.USER_SERVICE))).thenReturn(mUserManager);
        doNothing().when(mNetService).registerObserver(any());
    }

    @SmallTest
    public void testRestrictedProfilesAreAddedToVpn() {
        setMockedUsers(primaryUser, secondaryUser, restrictedProfileA, restrictedProfileB);

        final Vpn vpn = createVpn(primaryUser.id);
        final Set<UidRange> ranges = vpn.createUserAndRestrictedProfilesRanges(primaryUser.id,
                null, null);

        assertEquals(new ArraySet<>(Arrays.asList(new UidRange[] {
            UidRange.createForUser(primaryUser.id),
            UidRange.createForUser(restrictedProfileA.id)
        })), ranges);
    }

    @SmallTest
    public void testManagedProfilesAreNotAddedToVpn() {
        setMockedUsers(primaryUser, managedProfileA);

        final Vpn vpn = createVpn(primaryUser.id);
        final Set<UidRange> ranges = vpn.createUserAndRestrictedProfilesRanges(primaryUser.id,
                null, null);

        assertEquals(new ArraySet<>(Arrays.asList(new UidRange[] {
            UidRange.createForUser(primaryUser.id)
        })), ranges);
    }

    @SmallTest
    public void testAddUserToVpnOnlyAddsOneUser() {
        setMockedUsers(primaryUser, restrictedProfileA, managedProfileA);

        final Vpn vpn = createVpn(primaryUser.id);
        final Set<UidRange> ranges = new ArraySet<>();
        vpn.addUserToRanges(ranges, primaryUser.id, null, null);

        assertEquals(new ArraySet<>(Arrays.asList(new UidRange[] {
            UidRange.createForUser(primaryUser.id)
        })), ranges);
    }

    @SmallTest
    public void testUidWhiteAndBlacklist() throws Exception {
        final Map<String, Integer> packages = new ArrayMap<>();
        packages.put("com.example", 66);
        packages.put("org.example", 77);
        packages.put("net.example", 78);
        setMockedPackages(packages);

        final Vpn vpn = createVpn(primaryUser.id);
        final UidRange user = UidRange.createForUser(primaryUser.id);

        // Whitelist
        final Set<UidRange> allow = vpn.createUserAndRestrictedProfilesRanges(primaryUser.id,
                new ArrayList<String>(packages.keySet()), null);
        assertEquals(new ArraySet<>(Arrays.asList(new UidRange[] {
            new UidRange(user.start + 66, user.start + 66),
            new UidRange(user.start + 77, user.start + 78)
        })), allow);

        // Blacklist
        final Set<UidRange> disallow = vpn.createUserAndRestrictedProfilesRanges(primaryUser.id,
                null, new ArrayList<String>(packages.keySet()));
        assertEquals(new ArraySet<>(Arrays.asList(new UidRange[] {
            new UidRange(user.start, user.start + 65),
            new UidRange(user.start + 67, user.start + 76),
            new UidRange(user.start + 79, user.stop)
        })), disallow);
    }

    /**
     * @return A subclass of {@link Vpn} which is reliably:
     * <ul>
     *   <li>Associated with a specific user ID</li>
     *   <li>Not in always-on mode</li>
     * </ul>
     */
    private Vpn createVpn(@UserIdInt int userId) {
        return new Vpn(Looper.myLooper(), mContext, mNetService, userId);
    }

    /**
     * Populate {@link #mUserManager} with a list of fake users.
     */
    private void setMockedUsers(UserInfo... users) {
        final Map<Integer, UserInfo> userMap = new ArrayMap<>();
        for (UserInfo user : users) {
            userMap.put(user.id, user);
        }

        doAnswer(invocation -> {
            return new ArrayList(userMap.values());
        }).when(mUserManager).getUsers();

        doAnswer(invocation -> {
            final int id = (int) invocation.getArguments()[0];
            return userMap.get(id);
        }).when(mUserManager).getUserInfo(anyInt());

        doAnswer(invocation -> {
            final int id = (int) invocation.getArguments()[0];
            return (userMap.get(id).flags & UserInfo.FLAG_ADMIN) != 0;
        }).when(mUserManager).canHaveRestrictedProfile(anyInt());
    }

    /**
     * Populate {@link #mPackageManager} with a fake packageName-to-UID mapping.
     */
    private void setMockedPackages(final Map<String, Integer> packages) {
        try {
            doAnswer(invocation -> {
                final String appName = (String) invocation.getArguments()[0];
                final int userId = (int) invocation.getArguments()[1];
                return UserHandle.getUid(userId, packages.get(appName));
            }).when(mPackageManager).getPackageUidAsUser(anyString(), anyInt());
        } catch (Exception e) {
        }
    }
}
