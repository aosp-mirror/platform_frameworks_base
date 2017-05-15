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
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.UidRange;
import android.net.VpnService;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.net.VpnConfig;

import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Tests for {@link Vpn}.
 *
 * Build, install and run with:
 *  runtest --path java/com/android/server/connectivity/VpnTest.java
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

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private Context mContext;
    @Mock private UserManager mUserManager;
    @Mock private PackageManager mPackageManager;
    @Mock private INetworkManagementService mNetService;
    @Mock private AppOpsManager mAppOps;
    @Mock private NotificationManager mNotificationManager;
    @Mock private Vpn.SystemServices mSystemServices;

    @Override
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        setMockedPackages(mPackages);

        when(mContext.getPackageName()).thenReturn(Vpn.class.getPackage().getName());
        when(mContext.getSystemService(eq(Context.USER_SERVICE))).thenReturn(mUserManager);
        when(mContext.getSystemService(eq(Context.APP_OPS_SERVICE))).thenReturn(mAppOps);
        when(mContext.getSystemService(eq(Context.NOTIFICATION_SERVICE)))
                .thenReturn(mNotificationManager);

        // Used by {@link Notification.Builder}
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.targetSdkVersion = VERSION_CODES.CUR_DEVELOPMENT;
        when(mContext.getApplicationInfo()).thenReturn(applicationInfo);

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
        final Vpn vpn = createVpn(primaryUser.id);
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
        final Vpn vpn = createVpn(primaryUser.id);
        final UidRange user = UidRange.createForUser(primaryUser.id);

        // Default state.
        assertUnblocked(vpn, user.start + PKG_UIDS[0], user.start + PKG_UIDS[1], user.start + PKG_UIDS[2], user.start + PKG_UIDS[3]);

        // Set always-on without lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], false));
        assertUnblocked(vpn, user.start + PKG_UIDS[0], user.start + PKG_UIDS[1], user.start + PKG_UIDS[2], user.start + PKG_UIDS[3]);

        // Set always-on with lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], true));
        verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(new UidRange[] {
            new UidRange(user.start, user.start + PKG_UIDS[1] - 1),
            new UidRange(user.start + PKG_UIDS[1] + 1, user.stop)
        }));
        assertBlocked(vpn, user.start + PKG_UIDS[0], user.start + PKG_UIDS[2], user.start + PKG_UIDS[3]);
        assertUnblocked(vpn, user.start + PKG_UIDS[1]);

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
        assertBlocked(vpn, user.start + PKG_UIDS[0], user.start + PKG_UIDS[1], user.start + PKG_UIDS[2]);
        assertUnblocked(vpn, user.start + PKG_UIDS[3]);
    }

    @SmallTest
    public void testLockdownAddingAProfile() throws Exception {
        final Vpn vpn = createVpn(primaryUser.id);
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
        assertUnblocked(vpn, profile.start + PKG_UIDS[0]);

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

    @SmallTest
    public void testLockdownRuleRepeatability() throws Exception {
        final Vpn vpn = createVpn(primaryUser.id);

        // Given legacy lockdown is already enabled,
        vpn.setLockdown(true);
        verify(mNetService, times(1)).setAllowOnlyVpnForUids(
                eq(true), aryEq(new UidRange[] {UidRange.createForUser(primaryUser.id)}));

        // Enabling legacy lockdown twice should do nothing.
        vpn.setLockdown(true);
        verify(mNetService, times(1)).setAllowOnlyVpnForUids(anyBoolean(), any(UidRange[].class));

        // And disabling should remove the rules exactly once.
        vpn.setLockdown(false);
        verify(mNetService, times(1)).setAllowOnlyVpnForUids(
                eq(false), aryEq(new UidRange[] {UidRange.createForUser(primaryUser.id)}));

        // Removing the lockdown again should have no effect.
        vpn.setLockdown(false);
        verify(mNetService, times(2)).setAllowOnlyVpnForUids(anyBoolean(), any(UidRange[].class));
    }

    @SmallTest
    public void testLockdownRuleReversibility() throws Exception {
        final Vpn vpn = createVpn(primaryUser.id);

        final UidRange[] entireUser = {
            UidRange.createForUser(primaryUser.id)
        };
        final UidRange[] exceptPkg0 = {
            new UidRange(entireUser[0].start, entireUser[0].start + PKG_UIDS[0] - 1),
            new UidRange(entireUser[0].start + PKG_UIDS[0] + 1, entireUser[0].stop)
        };

        final InOrder order = inOrder(mNetService);

        // Given lockdown is enabled with no package (legacy VPN),
        vpn.setLockdown(true);
        order.verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(entireUser));

        // When a new VPN package is set the rules should change to cover that package.
        vpn.prepare(null, PKGS[0]);
        order.verify(mNetService).setAllowOnlyVpnForUids(eq(false), aryEq(entireUser));
        order.verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(exceptPkg0));

        // When that VPN package is unset, everything should be undone again in reverse.
        vpn.prepare(null, VpnConfig.LEGACY_VPN);
        order.verify(mNetService).setAllowOnlyVpnForUids(eq(false), aryEq(exceptPkg0));
        order.verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(entireUser));
    }

    @SmallTest
    public void testIsAlwaysOnPackageSupported() throws Exception {
        final Vpn vpn = createVpn(primaryUser.id);

        ApplicationInfo appInfo = new ApplicationInfo();
        when(mPackageManager.getApplicationInfoAsUser(eq(PKGS[0]), anyInt(), eq(primaryUser.id)))
                .thenReturn(appInfo);

        ServiceInfo svcInfo = new ServiceInfo();
        ResolveInfo resInfo = new ResolveInfo();
        resInfo.serviceInfo = svcInfo;
        when(mPackageManager.queryIntentServicesAsUser(any(), eq(PackageManager.GET_META_DATA),
                eq(primaryUser.id)))
                .thenReturn(Collections.singletonList(resInfo));

        // null package name should return false
        assertFalse(vpn.isAlwaysOnPackageSupported(null));

        // Pre-N apps are not supported
        appInfo.targetSdkVersion = VERSION_CODES.M;
        assertFalse(vpn.isAlwaysOnPackageSupported(PKGS[0]));

        // N+ apps are supported by default
        appInfo.targetSdkVersion = VERSION_CODES.N;
        assertTrue(vpn.isAlwaysOnPackageSupported(PKGS[0]));

        // Apps that opt out explicitly are not supported
        appInfo.targetSdkVersion = VERSION_CODES.CUR_DEVELOPMENT;
        Bundle metaData = new Bundle();
        metaData.putBoolean(VpnService.METADATA_SUPPORTS_ALWAYS_ON, false);
        svcInfo.metaData = metaData;
        assertFalse(vpn.isAlwaysOnPackageSupported(PKGS[0]));
    }

    @SmallTest
    public void testNotificationShownForAlwaysOnApp() {
        final UserHandle userHandle = UserHandle.of(primaryUser.id);
        final Vpn vpn = createVpn(primaryUser.id);
        setMockedUsers(primaryUser);

        final InOrder order = inOrder(mNotificationManager);

        // Don't show a notification for regular disconnected states.
        vpn.updateState(DetailedState.DISCONNECTED, TAG);
        order.verify(mNotificationManager, atLeastOnce())
                .cancelAsUser(anyString(), anyInt(), eq(userHandle));

        // Start showing a notification for disconnected once always-on.
        vpn.setAlwaysOnPackage(PKGS[0], false);
        order.verify(mNotificationManager)
                .notifyAsUser(anyString(), anyInt(), any(), eq(userHandle));

        // Stop showing the notification once connected.
        vpn.updateState(DetailedState.CONNECTED, TAG);
        order.verify(mNotificationManager).cancelAsUser(anyString(), anyInt(), eq(userHandle));

        // Show the notification if we disconnect again.
        vpn.updateState(DetailedState.DISCONNECTED, TAG);
        order.verify(mNotificationManager)
                .notifyAsUser(anyString(), anyInt(), any(), eq(userHandle));

        // Notification should be cleared after unsetting always-on package.
        vpn.setAlwaysOnPackage(null, false);
        order.verify(mNotificationManager).cancelAsUser(anyString(), anyInt(), eq(userHandle));
    }

    /**
     * Mock some methods of vpn object.
     */
    private Vpn createVpn(@UserIdInt int userId) {
        return new Vpn(Looper.myLooper(), mContext, mNetService, userId, mSystemServices);
    }

    private static void assertBlocked(Vpn vpn, int... uids) {
        for (int uid : uids) {
            assertTrue("Uid " + uid + " should be blocked", vpn.isBlockingUid(uid));
        }
    }

    private static void assertUnblocked(Vpn vpn, int... uids) {
        for (int uid : uids) {
            assertFalse("Uid " + uid + " should not be blocked", vpn.isBlockingUid(uid));
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
