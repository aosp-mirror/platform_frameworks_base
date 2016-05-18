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
import static org.mockito.AdditionalMatchers.*;
import static org.mockito.Mockito.*;

import android.annotation.UserIdInt;
import android.app.AppOpsManager;
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

    /**
     * Names and UIDs for some fake packages. Important points:
     *  - UID is ordered increasing.
     *  - One pair of packages have consecutive UIDs.
     */
    static final String[] PKGS = {"com.example", "org.example", "net.example", "web.vpn"};
    static final int[] PKG_UIDS = {66, 77, 78, 400};

    // Mock packages
    static final Map<String, Integer> mPackages = new ArrayMap<>();
    static {
        for (int i = 0; i < PKGS.length; i++) {
            mPackages.put(PKGS[i], PKG_UIDS[i]);
        }
    }

    @Mock private Context mContext;
    @Mock private UserManager mUserManager;
    @Mock private PackageManager mPackageManager;
    @Mock private INetworkManagementService mNetService;
    @Mock private AppOpsManager mAppOps;

    @Override
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        setMockedPackages(mPackages);
        when(mContext.getSystemService(eq(Context.USER_SERVICE))).thenReturn(mUserManager);
        when(mContext.getSystemService(eq(Context.APP_OPS_SERVICE))).thenReturn(mAppOps);
        doNothing().when(mNetService).registerObserver(any());
    }

    @SmallTest
    public void testRestrictedProfilesAreAddedToVpn() {
        setMockedUsers(primaryUser, secondaryUser, restrictedProfileA, restrictedProfileB);

        final Vpn vpn = new MockVpn(primaryUser.id);
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

        final Vpn vpn = new MockVpn(primaryUser.id);
        final Set<UidRange> ranges = vpn.createUserAndRestrictedProfilesRanges(primaryUser.id,
                null, null);

        assertEquals(new ArraySet<>(Arrays.asList(new UidRange[] {
            UidRange.createForUser(primaryUser.id)
        })), ranges);
    }

    @SmallTest
    public void testAddUserToVpnOnlyAddsOneUser() {
        setMockedUsers(primaryUser, restrictedProfileA, managedProfileA);

        final Vpn vpn = new MockVpn(primaryUser.id);
        final Set<UidRange> ranges = new ArraySet<>();
        vpn.addUserToRanges(ranges, primaryUser.id, null, null);

        assertEquals(new ArraySet<>(Arrays.asList(new UidRange[] {
            UidRange.createForUser(primaryUser.id)
        })), ranges);
    }

    @SmallTest
    public void testUidWhiteAndBlacklist() throws Exception {
        final Vpn vpn = new MockVpn(primaryUser.id);
        final UidRange user = UidRange.createForUser(primaryUser.id);
        final String[] packages = {PKGS[0], PKGS[1], PKGS[2]};

        // Whitelist
        final Set<UidRange> allow = vpn.createUserAndRestrictedProfilesRanges(primaryUser.id,
                Arrays.asList(packages), null);
        assertEquals(new ArraySet<>(Arrays.asList(new UidRange[] {
            new UidRange(user.start + PKG_UIDS[0], user.start + PKG_UIDS[0]),
            new UidRange(user.start + PKG_UIDS[1], user.start + PKG_UIDS[2])
        })), allow);

        // Blacklist
        final Set<UidRange> disallow = vpn.createUserAndRestrictedProfilesRanges(primaryUser.id,
                null, Arrays.asList(packages));
        assertEquals(new ArraySet<>(Arrays.asList(new UidRange[] {
            new UidRange(user.start, user.start + PKG_UIDS[0] - 1),
            new UidRange(user.start + PKG_UIDS[0] + 1, user.start + PKG_UIDS[1] - 1),
            /* Empty range between UIDS[1] and UIDS[2], should be excluded, */
            new UidRange(user.start + PKG_UIDS[2] + 1, user.stop)
        })), disallow);
    }

    @SmallTest
    public void testLockdownChangingPackage() throws Exception {
        final MockVpn vpn = new MockVpn(primaryUser.id);
        final UidRange user = UidRange.createForUser(primaryUser.id);

        // Default state.
        vpn.assertUnblocked(user.start + PKG_UIDS[0], user.start + PKG_UIDS[1], user.start + PKG_UIDS[2], user.start + PKG_UIDS[3]);

        // Set always-on without lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], false));
        vpn.assertUnblocked(user.start + PKG_UIDS[0], user.start + PKG_UIDS[1], user.start + PKG_UIDS[2], user.start + PKG_UIDS[3]);

        // Set always-on with lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], true));
        verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(new UidRange[] {
            new UidRange(user.start, user.start + PKG_UIDS[1] - 1),
            new UidRange(user.start + PKG_UIDS[1] + 1, user.stop)
        }));
        vpn.assertBlocked(user.start + PKG_UIDS[0], user.start + PKG_UIDS[2], user.start + PKG_UIDS[3]);
        vpn.assertUnblocked(user.start + PKG_UIDS[1]);

        // Switch to another app.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[3], true));
        verify(mNetService).setAllowOnlyVpnForUids(eq(false), aryEq(new UidRange[] {
            new UidRange(user.start, user.start + PKG_UIDS[1] - 1),
            new UidRange(user.start + PKG_UIDS[1] + 1, user.stop)
        }));
        verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(new UidRange[] {
            new UidRange(user.start, user.start + PKG_UIDS[3] - 1),
            new UidRange(user.start + PKG_UIDS[3] + 1, user.stop)
        }));
        vpn.assertBlocked(user.start + PKG_UIDS[0], user.start + PKG_UIDS[1], user.start + PKG_UIDS[2]);
        vpn.assertUnblocked(user.start + PKG_UIDS[3]);
    }

    @SmallTest
    public void testLockdownAddingAProfile() throws Exception {
        final MockVpn vpn = new MockVpn(primaryUser.id);
        setMockedUsers(primaryUser);

        // Make a copy of the restricted profile, as we're going to mark it deleted halfway through.
        final UserInfo tempProfile = new UserInfo(restrictedProfileA.id, restrictedProfileA.name,
                restrictedProfileA.flags);
        tempProfile.restrictedProfileParentId = primaryUser.id;

        final UidRange user = UidRange.createForUser(primaryUser.id);
        final UidRange profile = UidRange.createForUser(tempProfile.id);

        // Set lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[3], true));
        verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(new UidRange[] {
            new UidRange(user.start, user.start + PKG_UIDS[3] - 1),
            new UidRange(user.start + PKG_UIDS[3] + 1, user.stop)
        }));

        // Verify restricted user isn't affected at first.
        vpn.assertUnblocked(profile.start + PKG_UIDS[0]);

        // Add the restricted user.
        setMockedUsers(primaryUser, tempProfile);
        vpn.onUserAdded(tempProfile.id);
        verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(new UidRange[] {
            new UidRange(profile.start, profile.start + PKG_UIDS[3] - 1),
            new UidRange(profile.start + PKG_UIDS[3] + 1, profile.stop)
        }));

        // Remove the restricted user.
        tempProfile.partial = true;
        vpn.onUserRemoved(tempProfile.id);
        verify(mNetService).setAllowOnlyVpnForUids(eq(false), aryEq(new UidRange[] {
            new UidRange(profile.start, profile.start + PKG_UIDS[3] - 1),
            new UidRange(profile.start + PKG_UIDS[3] + 1, profile.stop)
        }));
    }

    /**
     * A subclass of {@link Vpn} with some of the fields pre-mocked.
     */
    private class MockVpn extends Vpn {
        public MockVpn(@UserIdInt int userId) {
            super(Looper.myLooper(), mContext, mNetService, userId);
        }

        public void assertBlocked(int... uids) {
            for (int uid : uids) {
                assertTrue("Uid " + uid + " should be blocked", isBlockingUid(uid));
            }
        }

        public void assertUnblocked(int... uids) {
            for (int uid : uids) {
                assertFalse("Uid " + uid + " should not be blocked", isBlockingUid(uid));
            }
        }
    }

    /**
     * Populate {@link #mUserManager} with a list of fake users.
     */
    private void setMockedUsers(UserInfo... users) {
        final Map<Integer, UserInfo> userMap = new ArrayMap<>();
        for (UserInfo user : users) {
            userMap.put(user.id, user);
        }

        /**
         * @see UserManagerService#getUsers(boolean)
         */
        doAnswer(invocation -> {
            final boolean excludeDying = (boolean) invocation.getArguments()[0];
            final ArrayList<UserInfo> result = new ArrayList<>(users.length);
            for (UserInfo ui : users) {
                if (!excludeDying || (ui.isEnabled() && !ui.partial)) {
                    result.add(ui);
                }
            }
            return result;
        }).when(mUserManager).getUsers(anyBoolean());

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
