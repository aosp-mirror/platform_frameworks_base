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
import static android.net.NetworkCapabilities.LINK_BANDWIDTH_UNSPECIFIED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.RouteInfo.RTN_UNREACHABLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo.DetailedState;
import android.net.RouteInfo;
import android.net.UidRange;
import android.net.VpnService;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.internal.net.VpnConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Tests for {@link Vpn}.
 *
 * Build, install and run with:
 *  runtest frameworks-net -c com.android.server.connectivity.VpnTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VpnTest {
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
    @Mock private ConnectivityManager mConnectivityManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        setMockedPackages(mPackages);

        when(mContext.getPackageName()).thenReturn(Vpn.class.getPackage().getName());
        when(mContext.getSystemService(eq(Context.USER_SERVICE))).thenReturn(mUserManager);
        when(mContext.getSystemService(eq(Context.APP_OPS_SERVICE))).thenReturn(mAppOps);
        when(mContext.getSystemService(eq(Context.NOTIFICATION_SERVICE)))
                .thenReturn(mNotificationManager);
        when(mContext.getSystemService(eq(Context.CONNECTIVITY_SERVICE)))
                .thenReturn(mConnectivityManager);
        when(mContext.getString(R.string.config_customVpnAlwaysOnDisconnectedDialogComponent))
                .thenReturn(Resources.getSystem().getString(
                        R.string.config_customVpnAlwaysOnDisconnectedDialogComponent));

        // Used by {@link Notification.Builder}
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.targetSdkVersion = VERSION_CODES.CUR_DEVELOPMENT;
        when(mContext.getApplicationInfo()).thenReturn(applicationInfo);
        when(mPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);

        doNothing().when(mNetService).registerObserver(any());
    }

    @Test
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

    @Test
    public void testManagedProfilesAreNotAddedToVpn() {
        setMockedUsers(primaryUser, managedProfileA);

        final Vpn vpn = createVpn(primaryUser.id);
        final Set<UidRange> ranges = vpn.createUserAndRestrictedProfilesRanges(primaryUser.id,
                null, null);

        assertEquals(new ArraySet<>(Arrays.asList(new UidRange[] {
            UidRange.createForUser(primaryUser.id)
        })), ranges);
    }

    @Test
    public void testAddUserToVpnOnlyAddsOneUser() {
        setMockedUsers(primaryUser, restrictedProfileA, managedProfileA);

        final Vpn vpn = createVpn(primaryUser.id);
        final Set<UidRange> ranges = new ArraySet<>();
        vpn.addUserToRanges(ranges, primaryUser.id, null, null);

        assertEquals(new ArraySet<>(Arrays.asList(new UidRange[] {
            UidRange.createForUser(primaryUser.id)
        })), ranges);
    }

    @Test
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

    @Test
    public void testGetAlwaysAndOnGetLockDown() throws Exception {
        final Vpn vpn = createVpn(primaryUser.id);

        // Default state.
        assertFalse(vpn.getAlwaysOn());
        assertFalse(vpn.getLockdown());

        // Set always-on without lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], false, Collections.emptyList()));
        assertTrue(vpn.getAlwaysOn());
        assertFalse(vpn.getLockdown());

        // Set always-on with lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], true, Collections.emptyList()));
        assertTrue(vpn.getAlwaysOn());
        assertTrue(vpn.getLockdown());

        // Remove always-on configuration.
        assertTrue(vpn.setAlwaysOnPackage(null, false, Collections.emptyList()));
        assertFalse(vpn.getAlwaysOn());
        assertFalse(vpn.getLockdown());
    }

    @Test
    public void testLockdownChangingPackage() throws Exception {
        final Vpn vpn = createVpn(primaryUser.id);
        final UidRange user = UidRange.createForUser(primaryUser.id);

        // Default state.
        assertUnblocked(vpn, user.start + PKG_UIDS[0], user.start + PKG_UIDS[1], user.start + PKG_UIDS[2], user.start + PKG_UIDS[3]);

        // Set always-on without lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], false, null));
        assertUnblocked(vpn, user.start + PKG_UIDS[0], user.start + PKG_UIDS[1], user.start + PKG_UIDS[2], user.start + PKG_UIDS[3]);

        // Set always-on with lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], true, null));
        verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(new UidRange[] {
            new UidRange(user.start, user.start + PKG_UIDS[1] - 1),
            new UidRange(user.start + PKG_UIDS[1] + 1, user.stop)
        }));
        assertBlocked(vpn, user.start + PKG_UIDS[0], user.start + PKG_UIDS[2], user.start + PKG_UIDS[3]);
        assertUnblocked(vpn, user.start + PKG_UIDS[1]);

        // Switch to another app.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[3], true, null));
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

    @Test
    public void testLockdownWhitelist() throws Exception {
        final Vpn vpn = createVpn(primaryUser.id);
        final UidRange user = UidRange.createForUser(primaryUser.id);

        // Set always-on with lockdown and whitelist app PKGS[2] from lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], true, Collections.singletonList(PKGS[2])));
        verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(new UidRange[] {
                new UidRange(user.start, user.start + PKG_UIDS[1] - 1),
                new UidRange(user.start + PKG_UIDS[2] + 1, user.stop)
        }));
        assertBlocked(vpn, user.start + PKG_UIDS[0], user.start + PKG_UIDS[3]);
        assertUnblocked(vpn, user.start + PKG_UIDS[1], user.start + PKG_UIDS[2]);

        // Change whitelisted app to PKGS[3].
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], true, Collections.singletonList(PKGS[3])));
        verify(mNetService).setAllowOnlyVpnForUids(eq(false), aryEq(new UidRange[] {
                new UidRange(user.start + PKG_UIDS[2] + 1, user.stop)
        }));
        verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(new UidRange[] {
                new UidRange(user.start + PKG_UIDS[1] + 1, user.start + PKG_UIDS[3] - 1),
                new UidRange(user.start + PKG_UIDS[3] + 1, user.stop)
        }));
        assertBlocked(vpn, user.start + PKG_UIDS[0], user.start + PKG_UIDS[2]);
        assertUnblocked(vpn, user.start + PKG_UIDS[1], user.start + PKG_UIDS[3]);

        // Change the VPN app.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[0], true, Collections.singletonList(PKGS[3])));
        verify(mNetService).setAllowOnlyVpnForUids(eq(false), aryEq(new UidRange[] {
                new UidRange(user.start, user.start + PKG_UIDS[1] - 1),
                new UidRange(user.start + PKG_UIDS[1] + 1, user.start + PKG_UIDS[3] - 1)
        }));
        verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(new UidRange[] {
                new UidRange(user.start, user.start + PKG_UIDS[0] - 1),
                new UidRange(user.start + PKG_UIDS[0] + 1, user.start + PKG_UIDS[3] - 1)
        }));
        assertBlocked(vpn, user.start + PKG_UIDS[1], user.start + PKG_UIDS[2]);
        assertUnblocked(vpn, user.start + PKG_UIDS[0], user.start + PKG_UIDS[3]);

        // Remove the whitelist.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[0], true, null));
        verify(mNetService).setAllowOnlyVpnForUids(eq(false), aryEq(new UidRange[] {
                new UidRange(user.start + PKG_UIDS[0] + 1, user.start + PKG_UIDS[3] - 1),
                new UidRange(user.start + PKG_UIDS[3] + 1, user.stop)
        }));
        verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(new UidRange[] {
                new UidRange(user.start + PKG_UIDS[0] + 1, user.stop),
        }));
        assertBlocked(vpn, user.start + PKG_UIDS[1], user.start + PKG_UIDS[2],
                user.start + PKG_UIDS[3]);
        assertUnblocked(vpn, user.start + PKG_UIDS[0]);

        // Add the whitelist.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[0], true, Collections.singletonList(PKGS[1])));
        verify(mNetService).setAllowOnlyVpnForUids(eq(false), aryEq(new UidRange[] {
                new UidRange(user.start + PKG_UIDS[0] + 1, user.stop)
        }));
        verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(new UidRange[] {
                new UidRange(user.start + PKG_UIDS[0] + 1, user.start + PKG_UIDS[1] - 1),
                new UidRange(user.start + PKG_UIDS[1] + 1, user.stop)
        }));
        assertBlocked(vpn, user.start + PKG_UIDS[2], user.start + PKG_UIDS[3]);
        assertUnblocked(vpn, user.start + PKG_UIDS[0], user.start + PKG_UIDS[1]);

        // Try whitelisting a package with a comma, should be rejected.
        assertFalse(vpn.setAlwaysOnPackage(PKGS[0], true, Collections.singletonList("a.b,c.d")));

        // Pass a non-existent packages in the whitelist, they (and only they) should be ignored.
        // Whitelisted package should change from PGKS[1] to PKGS[2].
        assertTrue(vpn.setAlwaysOnPackage(PKGS[0], true,
                Arrays.asList("com.foo.app", PKGS[2], "com.bar.app")));
        verify(mNetService).setAllowOnlyVpnForUids(eq(false), aryEq(new UidRange[]{
                new UidRange(user.start + PKG_UIDS[0] + 1, user.start + PKG_UIDS[1] - 1),
                new UidRange(user.start + PKG_UIDS[1] + 1, user.stop)
        }));
        verify(mNetService).setAllowOnlyVpnForUids(eq(true), aryEq(new UidRange[]{
                new UidRange(user.start + PKG_UIDS[0] + 1, user.start + PKG_UIDS[2] - 1),
                new UidRange(user.start + PKG_UIDS[2] + 1, user.stop)
        }));
    }

    @Test
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
        assertTrue(vpn.setAlwaysOnPackage(PKGS[3], true, null));
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

    @Test
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

    @Test
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

    @Test
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
        metaData.putBoolean(VpnService.SERVICE_META_DATA_SUPPORTS_ALWAYS_ON, false);
        svcInfo.metaData = metaData;
        assertFalse(vpn.isAlwaysOnPackageSupported(PKGS[0]));
    }

    @Test
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
        vpn.setAlwaysOnPackage(PKGS[0], false, null);
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
        vpn.setAlwaysOnPackage(null, false, null);
        order.verify(mNotificationManager).cancelAsUser(anyString(), anyInt(), eq(userHandle));
    }

    @Test
    public void testCapabilities() {
        final Vpn vpn = createVpn(primaryUser.id);
        setMockedUsers(primaryUser);

        final Network mobile = new Network(1);
        final Network wifi = new Network(2);

        final Map<Network, NetworkCapabilities> networks = new HashMap<>();
        networks.put(
                mobile,
                new NetworkCapabilities()
                        .addTransportType(TRANSPORT_CELLULAR)
                        .addCapability(NET_CAPABILITY_INTERNET)
                        .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                        .setLinkDownstreamBandwidthKbps(10));
        networks.put(
                wifi,
                new NetworkCapabilities()
                        .addTransportType(TRANSPORT_WIFI)
                        .addCapability(NET_CAPABILITY_INTERNET)
                        .addCapability(NET_CAPABILITY_NOT_METERED)
                        .addCapability(NET_CAPABILITY_NOT_ROAMING)
                        .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                        .setLinkUpstreamBandwidthKbps(20));
        setMockedNetworks(networks);

        final NetworkCapabilities caps = new NetworkCapabilities();

        Vpn.applyUnderlyingCapabilities(
                mConnectivityManager, new Network[] {}, caps, false /* isAlwaysMetered */);
        assertTrue(caps.hasTransport(TRANSPORT_VPN));
        assertFalse(caps.hasTransport(TRANSPORT_CELLULAR));
        assertFalse(caps.hasTransport(TRANSPORT_WIFI));
        assertEquals(LINK_BANDWIDTH_UNSPECIFIED, caps.getLinkDownstreamBandwidthKbps());
        assertEquals(LINK_BANDWIDTH_UNSPECIFIED, caps.getLinkUpstreamBandwidthKbps());
        assertFalse(caps.hasCapability(NET_CAPABILITY_NOT_METERED));
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_ROAMING));
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_CONGESTED));

        Vpn.applyUnderlyingCapabilities(
                mConnectivityManager,
                new Network[] {mobile},
                caps,
                false /* isAlwaysMetered */);
        assertTrue(caps.hasTransport(TRANSPORT_VPN));
        assertTrue(caps.hasTransport(TRANSPORT_CELLULAR));
        assertFalse(caps.hasTransport(TRANSPORT_WIFI));
        assertEquals(10, caps.getLinkDownstreamBandwidthKbps());
        assertEquals(LINK_BANDWIDTH_UNSPECIFIED, caps.getLinkUpstreamBandwidthKbps());
        assertFalse(caps.hasCapability(NET_CAPABILITY_NOT_METERED));
        assertFalse(caps.hasCapability(NET_CAPABILITY_NOT_ROAMING));
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_CONGESTED));

        Vpn.applyUnderlyingCapabilities(
                mConnectivityManager, new Network[] {wifi}, caps, false /* isAlwaysMetered */);
        assertTrue(caps.hasTransport(TRANSPORT_VPN));
        assertFalse(caps.hasTransport(TRANSPORT_CELLULAR));
        assertTrue(caps.hasTransport(TRANSPORT_WIFI));
        assertEquals(LINK_BANDWIDTH_UNSPECIFIED, caps.getLinkDownstreamBandwidthKbps());
        assertEquals(20, caps.getLinkUpstreamBandwidthKbps());
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_METERED));
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_ROAMING));
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_CONGESTED));

        Vpn.applyUnderlyingCapabilities(
                mConnectivityManager, new Network[] {wifi}, caps, true /* isAlwaysMetered */);
        assertTrue(caps.hasTransport(TRANSPORT_VPN));
        assertFalse(caps.hasTransport(TRANSPORT_CELLULAR));
        assertTrue(caps.hasTransport(TRANSPORT_WIFI));
        assertEquals(LINK_BANDWIDTH_UNSPECIFIED, caps.getLinkDownstreamBandwidthKbps());
        assertEquals(20, caps.getLinkUpstreamBandwidthKbps());
        assertFalse(caps.hasCapability(NET_CAPABILITY_NOT_METERED));
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_ROAMING));
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_CONGESTED));

        Vpn.applyUnderlyingCapabilities(
                mConnectivityManager,
                new Network[] {mobile, wifi},
                caps,
                false /* isAlwaysMetered */);
        assertTrue(caps.hasTransport(TRANSPORT_VPN));
        assertTrue(caps.hasTransport(TRANSPORT_CELLULAR));
        assertTrue(caps.hasTransport(TRANSPORT_WIFI));
        assertEquals(10, caps.getLinkDownstreamBandwidthKbps());
        assertEquals(20, caps.getLinkUpstreamBandwidthKbps());
        assertFalse(caps.hasCapability(NET_CAPABILITY_NOT_METERED));
        assertFalse(caps.hasCapability(NET_CAPABILITY_NOT_ROAMING));
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_CONGESTED));
    }

    /**
     * Mock some methods of vpn object.
     */
    private Vpn createVpn(@UserIdInt int userId) {
        return new Vpn(Looper.myLooper(), mContext, mNetService, userId, mSystemServices);
    }

    private static void assertBlocked(Vpn vpn, int... uids) {
        for (int uid : uids) {
            final boolean blocked = vpn.getLockdown() && vpn.isBlockingUid(uid);
            assertTrue("Uid " + uid + " should be blocked", blocked);
        }
    }

    private static void assertUnblocked(Vpn vpn, int... uids) {
        for (int uid : uids) {
            final boolean blocked = vpn.getLockdown() && vpn.isBlockingUid(uid);
            assertFalse("Uid " + uid + " should not be blocked", blocked);
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
                Integer appId = packages.get(appName);
                if (appId == null) throw new PackageManager.NameNotFoundException(appName);
                return UserHandle.getUid(userId, appId);
            }).when(mPackageManager).getPackageUidAsUser(anyString(), anyInt());
        } catch (Exception e) {
        }
    }

    private void setMockedNetworks(final Map<Network, NetworkCapabilities> networks) {
        doAnswer(invocation -> {
            final Network network = (Network) invocation.getArguments()[0];
            return networks.get(network);
        }).when(mConnectivityManager).getNetworkCapabilities(any());
    }

    // Need multiple copies of this, but Java's Stream objects can't be reused or
    // duplicated.
    private Stream<String> publicIpV4Routes() {
        return Stream.of(
                "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4",
                "32.0.0.0/3", "64.0.0.0/2", "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/6",
                "172.0.0.0/12", "172.32.0.0/11", "172.64.0.0/10", "172.128.0.0/9",
                "173.0.0.0/8", "174.0.0.0/7", "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11",
                "192.160.0.0/13", "192.169.0.0/16", "192.170.0.0/15", "192.172.0.0/14",
                "192.176.0.0/12", "192.192.0.0/10", "193.0.0.0/8", "194.0.0.0/7",
                "196.0.0.0/6", "200.0.0.0/5", "208.0.0.0/4");
    }

    private Stream<String> publicIpV6Routes() {
        return Stream.of(
                "::/1", "8000::/2", "c000::/3", "e000::/4", "f000::/5", "f800::/6",
                "fe00::/8", "2605:ef80:e:af1d::/64");
    }

    @Test
    public void testProvidesRoutesToMostDestinations() {
        final LinkProperties lp = new LinkProperties();

        // Default route provides routes to all IPv4 destinations.
        lp.addRoute(new RouteInfo(new IpPrefix("0.0.0.0/0")));
        assertTrue(Vpn.providesRoutesToMostDestinations(lp));

        // Empty LP provides routes to no destination
        lp.clear();
        assertFalse(Vpn.providesRoutesToMostDestinations(lp));

        // All IPv4 routes except for local networks. This is the case most relevant
        // to this function. It provides routes to almost the entire space.
        // (clone the stream so that we can reuse it later)
        publicIpV4Routes().forEach(s -> lp.addRoute(new RouteInfo(new IpPrefix(s))));
        assertTrue(Vpn.providesRoutesToMostDestinations(lp));

        // Removing a 16-bit prefix, which is 65536 addresses. This is still enough to
        // provide routes to "most" destinations.
        lp.removeRoute(new RouteInfo(new IpPrefix("192.169.0.0/16")));
        assertTrue(Vpn.providesRoutesToMostDestinations(lp));

        // Remove the /2 route, which represent a quarter of the available routing space.
        // This LP does not provides routes to "most" destinations any more.
        lp.removeRoute(new RouteInfo(new IpPrefix("64.0.0.0/2")));
        assertFalse(Vpn.providesRoutesToMostDestinations(lp));

        lp.clear();
        publicIpV6Routes().forEach(s -> lp.addRoute(new RouteInfo(new IpPrefix(s))));
        assertTrue(Vpn.providesRoutesToMostDestinations(lp));

        lp.removeRoute(new RouteInfo(new IpPrefix("::/1")));
        assertFalse(Vpn.providesRoutesToMostDestinations(lp));

        // V6 does not provide sufficient coverage but v4 does
        publicIpV4Routes().forEach(s -> lp.addRoute(new RouteInfo(new IpPrefix(s))));
        assertTrue(Vpn.providesRoutesToMostDestinations(lp));

        // V4 still does
        lp.removeRoute(new RouteInfo(new IpPrefix("192.169.0.0/16")));
        assertTrue(Vpn.providesRoutesToMostDestinations(lp));

        // V4 does not any more
        lp.removeRoute(new RouteInfo(new IpPrefix("64.0.0.0/2")));
        assertFalse(Vpn.providesRoutesToMostDestinations(lp));

        // V4 does not, but V6 has sufficient coverage again
        lp.addRoute(new RouteInfo(new IpPrefix("::/1")));
        assertTrue(Vpn.providesRoutesToMostDestinations(lp));

        lp.clear();
        // V4-unreachable route should not be treated as sufficient coverage
        lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), RTN_UNREACHABLE));
        assertFalse(Vpn.providesRoutesToMostDestinations(lp));

        lp.clear();
        // V6-unreachable route should not be treated as sufficient coverage
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), RTN_UNREACHABLE));
        assertFalse(Vpn.providesRoutesToMostDestinations(lp));
    }

    @Test
    public void testDoesNotLockUpWithTooManyRoutes() {
        final LinkProperties lp = new LinkProperties();
        final byte[] ad = new byte[4];
        // Actually evaluating this many routes under 1500ms is impossible on
        // current hardware and for some time, as the algorithm is O(n²).
        // Make sure the system has a safeguard against this and does not
        // lock up.
        final int MAX_ROUTES = 4000;
        final long MAX_ALLOWED_TIME_MS = 1500;
        for (int i = 0; i < MAX_ROUTES; ++i) {
            ad[0] = (byte)((i >> 24) & 0xFF);
            ad[1] = (byte)((i >> 16) & 0xFF);
            ad[2] = (byte)((i >> 8) & 0xFF);
            ad[3] = (byte)(i & 0xFF);
            try {
                lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.getByAddress(ad), 32)));
            } catch (UnknownHostException e) {
                // UnknownHostException is only thrown for an address of illegal length,
                // which can't happen in the case above.
            }
        }
        final long start = SystemClock.currentThreadTimeMillis();
        assertTrue(Vpn.providesRoutesToMostDestinations(lp));
        final long end = SystemClock.currentThreadTimeMillis();
        assertTrue(end - start < MAX_ALLOWED_TIME_MS);
    }
}
