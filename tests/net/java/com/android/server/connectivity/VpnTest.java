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
import static android.net.ConnectivityManager.NetworkCallback;
import static android.net.INetd.IF_STATE_DOWN;
import static android.net.INetd.IF_STATE_UP;
import static android.os.UserHandle.PER_USER_RANGE;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.Ikev2VpnProfile;
import android.net.InetAddresses;
import android.net.InterfaceConfigurationParcel;
import android.net.IpPrefix;
import android.net.IpSecManager;
import android.net.IpSecTunnelInterfaceResponse;
import android.net.LinkProperties;
import android.net.LocalSocket;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo.DetailedState;
import android.net.RouteInfo;
import android.net.UidRangeParcel;
import android.net.VpnManager;
import android.net.VpnService;
import android.net.VpnTransportInfo;
import android.net.ipsec.ike.IkeSessionCallback;
import android.net.ipsec.ike.exceptions.IkeProtocolException;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.INetworkManagementService;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.security.Credentials;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Range;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.server.IpSecService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalAnswers;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

    static final Network EGRESS_NETWORK = new Network(101);
    static final String EGRESS_IFACE = "wlan0";
    static final String TEST_VPN_PKG = "com.testvpn.vpn";
    private static final String TEST_VPN_SERVER = "1.2.3.4";
    private static final String TEST_VPN_IDENTITY = "identity";
    private static final byte[] TEST_VPN_PSK = "psk".getBytes();

    private static final Network TEST_NETWORK = new Network(Integer.MAX_VALUE);
    private static final String TEST_IFACE_NAME = "TEST_IFACE";
    private static final int TEST_TUNNEL_RESOURCE_ID = 0x2345;
    private static final long TEST_TIMEOUT_MS = 500L;

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
    private static final Range<Integer> PRI_USER_RANGE = uidRangeForUser(primaryUser.id);

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private Context mContext;
    @Mock private UserManager mUserManager;
    @Mock private PackageManager mPackageManager;
    @Mock private INetworkManagementService mNetService;
    @Mock private INetd mNetd;
    @Mock private AppOpsManager mAppOps;
    @Mock private NotificationManager mNotificationManager;
    @Mock private Vpn.SystemServices mSystemServices;
    @Mock private Vpn.Ikev2SessionCreator mIkev2SessionCreator;
    @Mock private ConnectivityManager mConnectivityManager;
    @Mock private IpSecService mIpSecService;
    @Mock private VpnProfileStore mVpnProfileStore;
    private final VpnProfile mVpnProfile;

    private IpSecManager mIpSecManager;

    public VpnTest() throws Exception {
        // Build an actual VPN profile that is capable of being converted to and from an
        // Ikev2VpnProfile
        final Ikev2VpnProfile.Builder builder =
                new Ikev2VpnProfile.Builder(TEST_VPN_SERVER, TEST_VPN_IDENTITY);
        builder.setAuthPsk(TEST_VPN_PSK);
        mVpnProfile = builder.build().toVpnProfile();
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mIpSecManager = new IpSecManager(mContext, mIpSecService);

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        setMockedPackages(mPackages);

        when(mContext.getPackageName()).thenReturn(TEST_VPN_PKG);
        when(mContext.getOpPackageName()).thenReturn(TEST_VPN_PKG);
        when(mContext.getSystemServiceName(UserManager.class))
                .thenReturn(Context.USER_SERVICE);
        when(mContext.getSystemService(eq(Context.USER_SERVICE))).thenReturn(mUserManager);
        when(mContext.getSystemService(eq(Context.APP_OPS_SERVICE))).thenReturn(mAppOps);
        when(mContext.getSystemServiceName(NotificationManager.class))
                .thenReturn(Context.NOTIFICATION_SERVICE);
        when(mContext.getSystemService(eq(Context.NOTIFICATION_SERVICE)))
                .thenReturn(mNotificationManager);
        when(mContext.getSystemService(eq(Context.CONNECTIVITY_SERVICE)))
                .thenReturn(mConnectivityManager);
        when(mContext.getSystemServiceName(eq(ConnectivityManager.class)))
                .thenReturn(Context.CONNECTIVITY_SERVICE);
        when(mContext.getSystemService(eq(Context.IPSEC_SERVICE))).thenReturn(mIpSecManager);
        when(mContext.getString(R.string.config_customVpnAlwaysOnDisconnectedDialogComponent))
                .thenReturn(Resources.getSystem().getString(
                        R.string.config_customVpnAlwaysOnDisconnectedDialogComponent));
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS))
                .thenReturn(true);

        // Used by {@link Notification.Builder}
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.targetSdkVersion = VERSION_CODES.CUR_DEVELOPMENT;
        when(mContext.getApplicationInfo()).thenReturn(applicationInfo);
        when(mPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);

        doNothing().when(mNetService).registerObserver(any());

        // Deny all appops by default.
        when(mAppOps.noteOpNoThrow(anyString(), anyInt(), anyString(), any(), any()))
                .thenReturn(AppOpsManager.MODE_IGNORED);

        // Setup IpSecService
        final IpSecTunnelInterfaceResponse tunnelResp =
                new IpSecTunnelInterfaceResponse(
                        IpSecManager.Status.OK, TEST_TUNNEL_RESOURCE_ID, TEST_IFACE_NAME);
        when(mIpSecService.createTunnelInterface(any(), any(), any(), any(), any()))
                .thenReturn(tunnelResp);
    }

    private Set<Range<Integer>> rangeSet(Range<Integer> ... ranges) {
        final Set<Range<Integer>> range = new ArraySet<>();
        for (Range<Integer> r : ranges) range.add(r);

        return range;
    }

    private static Range<Integer> uidRangeForUser(int userId) {
        return new Range<Integer>(userId * PER_USER_RANGE, (userId + 1) * PER_USER_RANGE - 1);
    }

    private Range<Integer> uidRange(int start, int stop) {
        return new Range<Integer>(start, stop);
    }

    @Test
    public void testRestrictedProfilesAreAddedToVpn() {
        setMockedUsers(primaryUser, secondaryUser, restrictedProfileA, restrictedProfileB);

        final Vpn vpn = createVpn(primaryUser.id);

        // Assume the user can have restricted profiles.
        doReturn(true).when(mUserManager).canHaveRestrictedProfile();
        final Set<Range<Integer>> ranges =
                vpn.createUserAndRestrictedProfilesRanges(primaryUser.id, null, null);

        assertEquals(rangeSet(PRI_USER_RANGE, uidRangeForUser(restrictedProfileA.id)), ranges);
    }

    @Test
    public void testManagedProfilesAreNotAddedToVpn() {
        setMockedUsers(primaryUser, managedProfileA);

        final Vpn vpn = createVpn(primaryUser.id);
        final Set<Range<Integer>> ranges = vpn.createUserAndRestrictedProfilesRanges(primaryUser.id,
                null, null);

        assertEquals(rangeSet(PRI_USER_RANGE), ranges);
    }

    @Test
    public void testAddUserToVpnOnlyAddsOneUser() {
        setMockedUsers(primaryUser, restrictedProfileA, managedProfileA);

        final Vpn vpn = createVpn(primaryUser.id);
        final Set<Range<Integer>> ranges = new ArraySet<>();
        vpn.addUserToRanges(ranges, primaryUser.id, null, null);

        assertEquals(rangeSet(PRI_USER_RANGE), ranges);
    }

    @Test
    public void testUidAllowAndDenylist() throws Exception {
        final Vpn vpn = createVpn(primaryUser.id);
        final Range<Integer> user = PRI_USER_RANGE;
        final int userStart = user.getLower();
        final int userStop = user.getUpper();
        final String[] packages = {PKGS[0], PKGS[1], PKGS[2]};

        // Allowed list
        final Set<Range<Integer>> allow = vpn.createUserAndRestrictedProfilesRanges(primaryUser.id,
                Arrays.asList(packages), null /* disallowedApplications */);
        assertEquals(rangeSet(
                uidRange(userStart + PKG_UIDS[0], userStart + PKG_UIDS[0]),
                uidRange(userStart + PKG_UIDS[1], userStart + PKG_UIDS[2])),
                allow);

        // Denied list
        final Set<Range<Integer>> disallow =
                vpn.createUserAndRestrictedProfilesRanges(primaryUser.id,
                        null /* allowedApplications */, Arrays.asList(packages));
        assertEquals(rangeSet(
                uidRange(userStart, userStart + PKG_UIDS[0] - 1),
                uidRange(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[1] - 1),
                /* Empty range between UIDS[1] and UIDS[2], should be excluded, */
                uidRange(userStart + PKG_UIDS[2] + 1, userStop)),
                disallow);
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
        final Range<Integer> user = PRI_USER_RANGE;
        final int userStart = user.getLower();
        final int userStop = user.getUpper();
        // Set always-on without lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], false, null));

        // Set always-on with lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], true, null));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1, userStop)
        }));

        // Switch to another app.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[3], true, null));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1, userStop)
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[3] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[3] + 1, userStop)
        }));
    }

    @Test
    public void testLockdownAllowlist() throws Exception {
        final Vpn vpn = createVpn(primaryUser.id);
        final Range<Integer> user = PRI_USER_RANGE;
        final int userStart = user.getLower();
        final int userStop = user.getUpper();
        // Set always-on with lockdown and allow app PKGS[2] from lockdown.
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[1], true, Collections.singletonList(PKGS[2])));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[]  {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[2] + 1, userStop)
        }));
        // Change allowed app list to PKGS[3].
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[1], true, Collections.singletonList(PKGS[3])));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[2] + 1, userStop)
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1, userStart + PKG_UIDS[3] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[3] + 1, userStop)
        }));

        // Change the VPN app.
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[0], true, Collections.singletonList(PKGS[3])));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1, userStart + PKG_UIDS[3] - 1)
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[0] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[3] - 1)
        }));

        // Remove the list of allowed packages.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[0], true, null));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[3] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[3] + 1, userStop)
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStop),
        }));

        // Add the list of allowed packages.
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[0], true, Collections.singletonList(PKGS[1])));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStop)
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1, userStop)
        }));

        // Try allowing a package with a comma, should be rejected.
        assertFalse(vpn.setAlwaysOnPackage(
                PKGS[0], true, Collections.singletonList("a.b,c.d")));

        // Pass a non-existent packages in the allowlist, they (and only they) should be ignored.
        // allowed package should change from PGKS[1] to PKGS[2].
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[0], true, Arrays.asList("com.foo.app", PKGS[2], "com.bar.app")));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1, userStop)
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[2] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[2] + 1, userStop)
        }));
    }

    @Test
    public void testLockdownRuleRepeatability() throws Exception {
        final Vpn vpn = createVpn(primaryUser.id);
        final UidRangeParcel[] primaryUserRangeParcel = new UidRangeParcel[] {
                new UidRangeParcel(PRI_USER_RANGE.getLower(), PRI_USER_RANGE.getUpper())};
        // Given legacy lockdown is already enabled,
        vpn.setLockdown(true);
        verify(mConnectivityManager, times(1)).setRequireVpnForUids(true,
                toRanges(primaryUserRangeParcel));

        // Enabling legacy lockdown twice should do nothing.
        vpn.setLockdown(true);
        verify(mConnectivityManager, times(1)).setRequireVpnForUids(anyBoolean(), any());

        // And disabling should remove the rules exactly once.
        vpn.setLockdown(false);
        verify(mConnectivityManager, times(1)).setRequireVpnForUids(false,
                toRanges(primaryUserRangeParcel));

        // Removing the lockdown again should have no effect.
        vpn.setLockdown(false);
        verify(mConnectivityManager, times(2)).setRequireVpnForUids(anyBoolean(), any());
    }

    private ArrayList<Range<Integer>> toRanges(UidRangeParcel[] ranges) {
        ArrayList<Range<Integer>> rangesArray = new ArrayList<>(ranges.length);
        for (int i = 0; i < ranges.length; i++) {
            rangesArray.add(new Range<>(ranges[i].start, ranges[i].stop));
        }
        return rangesArray;
    }

    @Test
    public void testLockdownRuleReversibility() throws Exception {
        final Vpn vpn = createVpn(primaryUser.id);
        final UidRangeParcel[] entireUser = {
            new UidRangeParcel(PRI_USER_RANGE.getLower(), PRI_USER_RANGE.getUpper())
        };
        final UidRangeParcel[] exceptPkg0 = {
            new UidRangeParcel(entireUser[0].start, entireUser[0].start + PKG_UIDS[0] - 1),
            new UidRangeParcel(entireUser[0].start + PKG_UIDS[0] + 1, entireUser[0].stop)
        };

        final InOrder order = inOrder(mConnectivityManager);

        // Given lockdown is enabled with no package (legacy VPN),
        vpn.setLockdown(true);
        order.verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(entireUser));

        // When a new VPN package is set the rules should change to cover that package.
        vpn.prepare(null, PKGS[0], VpnManager.TYPE_VPN_SERVICE);
        order.verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(entireUser));
        order.verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(exceptPkg0));

        // When that VPN package is unset, everything should be undone again in reverse.
        vpn.prepare(null, VpnConfig.LEGACY_VPN, VpnManager.TYPE_VPN_SERVICE);
        order.verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(exceptPkg0));
        order.verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(entireUser));
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
    public void testNotificationShownForAlwaysOnApp() throws Exception {
        final UserHandle userHandle = UserHandle.of(primaryUser.id);
        final Vpn vpn = createVpn(primaryUser.id);
        setMockedUsers(primaryUser);

        final InOrder order = inOrder(mNotificationManager);

        // Don't show a notification for regular disconnected states.
        vpn.updateState(DetailedState.DISCONNECTED, TAG);
        order.verify(mNotificationManager, atLeastOnce()).cancel(anyString(), anyInt());

        // Start showing a notification for disconnected once always-on.
        vpn.setAlwaysOnPackage(PKGS[0], false, null);
        order.verify(mNotificationManager).notify(anyString(), anyInt(), any());

        // Stop showing the notification once connected.
        vpn.updateState(DetailedState.CONNECTED, TAG);
        order.verify(mNotificationManager).cancel(anyString(), anyInt());

        // Show the notification if we disconnect again.
        vpn.updateState(DetailedState.DISCONNECTED, TAG);
        order.verify(mNotificationManager).notify(anyString(), anyInt(), any());

        // Notification should be cleared after unsetting always-on package.
        vpn.setAlwaysOnPackage(null, false, null);
        order.verify(mNotificationManager).cancel(anyString(), anyInt());
    }

    /**
     * The profile name should NOT change between releases for backwards compatibility
     *
     * <p>If this is changed between releases, the {@link Vpn#getVpnProfilePrivileged()} method MUST
     * be updated to ensure backward compatibility.
     */
    @Test
    public void testGetProfileNameForPackage() throws Exception {
        final Vpn vpn = createVpn(primaryUser.id);
        setMockedUsers(primaryUser);

        final String expected = Credentials.PLATFORM_VPN + primaryUser.id + "_" + TEST_VPN_PKG;
        assertEquals(expected, vpn.getProfileNameForPackage(TEST_VPN_PKG));
    }

    private Vpn createVpnAndSetupUidChecks(String... grantedOps) throws Exception {
        return createVpnAndSetupUidChecks(primaryUser, grantedOps);
    }

    private Vpn createVpnAndSetupUidChecks(UserInfo user, String... grantedOps) throws Exception {
        final Vpn vpn = createVpn(user.id);
        setMockedUsers(user);

        when(mPackageManager.getPackageUidAsUser(eq(TEST_VPN_PKG), anyInt()))
                .thenReturn(Process.myUid());

        for (final String opStr : grantedOps) {
            when(mAppOps.noteOpNoThrow(opStr, Process.myUid(), TEST_VPN_PKG,
                    null /* attributionTag */, null /* message */))
                    .thenReturn(AppOpsManager.MODE_ALLOWED);
        }

        return vpn;
    }

    private void checkProvisionVpnProfile(Vpn vpn, boolean expectedResult, String... checkedOps) {
        assertEquals(expectedResult, vpn.provisionVpnProfile(TEST_VPN_PKG, mVpnProfile));

        // The profile should always be stored, whether or not consent has been previously granted.
        verify(mVpnProfileStore)
                .put(
                        eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)),
                        eq(mVpnProfile.encode()));

        for (final String checkedOpStr : checkedOps) {
            verify(mAppOps).noteOpNoThrow(checkedOpStr, Process.myUid(), TEST_VPN_PKG,
                    null /* attributionTag */, null /* message */);
        }
    }

    @Test
    public void testProvisionVpnProfileNoIpsecTunnels() throws Exception {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS))
                .thenReturn(false);
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            checkProvisionVpnProfile(
                    vpn, true /* expectedResult */, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
            fail("Expected exception due to missing feature");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void testProvisionVpnProfilePreconsented() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        checkProvisionVpnProfile(
                vpn, true /* expectedResult */, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
    }

    @Test
    public void testProvisionVpnProfileNotPreconsented() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();

        // Expect that both the ACTIVATE_VPN and ACTIVATE_PLATFORM_VPN were tried, but the caller
        // had neither.
        checkProvisionVpnProfile(vpn, false /* expectedResult */,
                AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN, AppOpsManager.OPSTR_ACTIVATE_VPN);
    }

    @Test
    public void testProvisionVpnProfileVpnServicePreconsented() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_VPN);

        checkProvisionVpnProfile(vpn, true /* expectedResult */, AppOpsManager.OPSTR_ACTIVATE_VPN);
    }

    @Test
    public void testProvisionVpnProfileTooLarge() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        final VpnProfile bigProfile = new VpnProfile("");
        bigProfile.name = new String(new byte[Vpn.MAX_VPN_PROFILE_SIZE_BYTES + 1]);

        try {
            vpn.provisionVpnProfile(TEST_VPN_PKG, bigProfile);
            fail("Expected IAE due to profile size");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testProvisionVpnProfileRestrictedUser() throws Exception {
        final Vpn vpn =
                createVpnAndSetupUidChecks(
                        restrictedProfileA, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            vpn.provisionVpnProfile(TEST_VPN_PKG, mVpnProfile);
            fail("Expected SecurityException due to restricted user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testDeleteVpnProfile() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();

        vpn.deleteVpnProfile(TEST_VPN_PKG);

        verify(mVpnProfileStore)
                .remove(eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)));
    }

    @Test
    public void testDeleteVpnProfileRestrictedUser() throws Exception {
        final Vpn vpn =
                createVpnAndSetupUidChecks(
                        restrictedProfileA, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            vpn.deleteVpnProfile(TEST_VPN_PKG);
            fail("Expected SecurityException due to restricted user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testGetVpnProfilePrivileged() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();

        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(new VpnProfile("").encode());

        vpn.getVpnProfilePrivileged(TEST_VPN_PKG);

        verify(mVpnProfileStore).get(eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)));
    }

    @Test
    public void testStartVpnProfile() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());

        vpn.startVpnProfile(TEST_VPN_PKG);

        verify(mVpnProfileStore).get(eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)));
        verify(mAppOps)
                .noteOpNoThrow(
                        eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN),
                        eq(Process.myUid()),
                        eq(TEST_VPN_PKG),
                        eq(null) /* attributionTag */,
                        eq(null) /* message */);
    }

    @Test
    public void testStartVpnProfileVpnServicePreconsented() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_VPN);

        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());

        vpn.startVpnProfile(TEST_VPN_PKG);

        // Verify that the the ACTIVATE_VPN appop was checked, but no error was thrown.
        verify(mAppOps).noteOpNoThrow(AppOpsManager.OPSTR_ACTIVATE_VPN, Process.myUid(),
                TEST_VPN_PKG, null /* attributionTag */, null /* message */);
    }

    @Test
    public void testStartVpnProfileNotConsented() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();

        try {
            vpn.startVpnProfile(TEST_VPN_PKG);
            fail("Expected failure due to no user consent");
        } catch (SecurityException expected) {
        }

        // Verify both appops were checked.
        verify(mAppOps)
                .noteOpNoThrow(
                        eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN),
                        eq(Process.myUid()),
                        eq(TEST_VPN_PKG),
                        eq(null) /* attributionTag */,
                        eq(null) /* message */);
        verify(mAppOps).noteOpNoThrow(AppOpsManager.OPSTR_ACTIVATE_VPN, Process.myUid(),
                TEST_VPN_PKG, null /* attributionTag */, null /* message */);

        // Keystore should never have been accessed.
        verify(mVpnProfileStore, never()).get(any());
    }

    @Test
    public void testStartVpnProfileMissingProfile() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG))).thenReturn(null);

        try {
            vpn.startVpnProfile(TEST_VPN_PKG);
            fail("Expected failure due to missing profile");
        } catch (IllegalArgumentException expected) {
        }

        verify(mVpnProfileStore).get(vpn.getProfileNameForPackage(TEST_VPN_PKG));
        verify(mAppOps)
                .noteOpNoThrow(
                        eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN),
                        eq(Process.myUid()),
                        eq(TEST_VPN_PKG),
                        eq(null) /* attributionTag */,
                        eq(null) /* message */);
    }

    @Test
    public void testStartVpnProfileRestrictedUser() throws Exception {
        final Vpn vpn =
                createVpnAndSetupUidChecks(
                        restrictedProfileA, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            vpn.startVpnProfile(TEST_VPN_PKG);
            fail("Expected SecurityException due to restricted user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testStopVpnProfileRestrictedUser() throws Exception {
        final Vpn vpn =
                createVpnAndSetupUidChecks(
                        restrictedProfileA, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            vpn.stopVpnProfile(TEST_VPN_PKG);
            fail("Expected SecurityException due to restricted user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testSetPackageAuthorizationVpnService() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();

        assertTrue(vpn.setPackageAuthorization(TEST_VPN_PKG, VpnManager.TYPE_VPN_SERVICE));
        verify(mAppOps)
                .setMode(
                        eq(AppOpsManager.OPSTR_ACTIVATE_VPN),
                        eq(Process.myUid()),
                        eq(TEST_VPN_PKG),
                        eq(AppOpsManager.MODE_ALLOWED));
    }

    @Test
    public void testSetPackageAuthorizationPlatformVpn() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();

        assertTrue(vpn.setPackageAuthorization(TEST_VPN_PKG, VpnManager.TYPE_VPN_PLATFORM));
        verify(mAppOps)
                .setMode(
                        eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN),
                        eq(Process.myUid()),
                        eq(TEST_VPN_PKG),
                        eq(AppOpsManager.MODE_ALLOWED));
    }

    @Test
    public void testSetPackageAuthorizationRevokeAuthorization() throws Exception {
        final Vpn vpn = createVpnAndSetupUidChecks();

        assertTrue(vpn.setPackageAuthorization(TEST_VPN_PKG, VpnManager.TYPE_VPN_NONE));
        verify(mAppOps)
                .setMode(
                        eq(AppOpsManager.OPSTR_ACTIVATE_VPN),
                        eq(Process.myUid()),
                        eq(TEST_VPN_PKG),
                        eq(AppOpsManager.MODE_IGNORED));
        verify(mAppOps)
                .setMode(
                        eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN),
                        eq(Process.myUid()),
                        eq(TEST_VPN_PKG),
                        eq(AppOpsManager.MODE_IGNORED));
    }

    private NetworkCallback triggerOnAvailableAndGetCallback() throws Exception {
        final ArgumentCaptor<NetworkCallback> networkCallbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        verify(mConnectivityManager, timeout(TEST_TIMEOUT_MS))
                .requestNetwork(any(), networkCallbackCaptor.capture());

        // onAvailable() will trigger onDefaultNetworkChanged(), so NetdUtils#setInterfaceUp will be
        // invoked. Set the return value of INetd#interfaceGetCfg to prevent NullPointerException.
        final InterfaceConfigurationParcel config = new InterfaceConfigurationParcel();
        config.flags = new String[] {IF_STATE_DOWN};
        when(mNetd.interfaceGetCfg(anyString())).thenReturn(config);
        final NetworkCallback cb = networkCallbackCaptor.getValue();
        cb.onAvailable(TEST_NETWORK);
        return cb;
    }

    private void verifyInterfaceSetCfgWithFlags(String flag) throws Exception {
        // Add a timeout for waiting for interfaceSetCfg to be called.
        verify(mNetd, timeout(TEST_TIMEOUT_MS)).interfaceSetCfg(argThat(
                config -> Arrays.asList(config.flags).contains(flag)));
    }

    @Test
    public void testStartPlatformVpnAuthenticationFailed() throws Exception {
        final ArgumentCaptor<IkeSessionCallback> captor =
                ArgumentCaptor.forClass(IkeSessionCallback.class);
        final IkeProtocolException exception = mock(IkeProtocolException.class);
        when(exception.getErrorType())
                .thenReturn(IkeProtocolException.ERROR_TYPE_AUTHENTICATION_FAILED);

        final Vpn vpn = startLegacyVpn(createVpn(primaryUser.id), (mVpnProfile));
        final NetworkCallback cb = triggerOnAvailableAndGetCallback();

        verifyInterfaceSetCfgWithFlags(IF_STATE_UP);

        // Wait for createIkeSession() to be called before proceeding in order to ensure consistent
        // state
        verify(mIkev2SessionCreator, timeout(TEST_TIMEOUT_MS))
                .createIkeSession(any(), any(), any(), any(), captor.capture(), any());
        final IkeSessionCallback ikeCb = captor.getValue();
        ikeCb.onClosedExceptionally(exception);

        verify(mConnectivityManager, timeout(TEST_TIMEOUT_MS)).unregisterNetworkCallback(eq(cb));
        assertEquals(LegacyVpnInfo.STATE_FAILED, vpn.getLegacyVpnInfo().state);
    }

    @Test
    public void testStartPlatformVpnIllegalArgumentExceptionInSetup() throws Exception {
        when(mIkev2SessionCreator.createIkeSession(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException());
        final Vpn vpn = startLegacyVpn(createVpn(primaryUser.id), mVpnProfile);
        final NetworkCallback cb = triggerOnAvailableAndGetCallback();

        verifyInterfaceSetCfgWithFlags(IF_STATE_UP);

        // Wait for createIkeSession() to be called before proceeding in order to ensure consistent
        // state
        verify(mConnectivityManager, timeout(TEST_TIMEOUT_MS)).unregisterNetworkCallback(eq(cb));
        assertEquals(LegacyVpnInfo.STATE_FAILED, vpn.getLegacyVpnInfo().state);
    }

    private void setAndVerifyAlwaysOnPackage(Vpn vpn, int uid, boolean lockdownEnabled) {
        assertTrue(vpn.setAlwaysOnPackage(TEST_VPN_PKG, lockdownEnabled, null));

        verify(mVpnProfileStore).get(eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)));
        verify(mAppOps).setMode(
                eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN), eq(uid), eq(TEST_VPN_PKG),
                eq(AppOpsManager.MODE_ALLOWED));

        verify(mSystemServices).settingsSecurePutStringForUser(
                eq(Settings.Secure.ALWAYS_ON_VPN_APP), eq(TEST_VPN_PKG), eq(primaryUser.id));
        verify(mSystemServices).settingsSecurePutIntForUser(
                eq(Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN), eq(lockdownEnabled ? 1 : 0),
                eq(primaryUser.id));
        verify(mSystemServices).settingsSecurePutStringForUser(
                eq(Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN_WHITELIST), eq(""), eq(primaryUser.id));
    }

    @Test
    public void testSetAndStartAlwaysOnVpn() throws Exception {
        final Vpn vpn = createVpn(primaryUser.id);
        setMockedUsers(primaryUser);

        // UID checks must return a different UID; otherwise it'll be treated as already prepared.
        final int uid = Process.myUid() + 1;
        when(mPackageManager.getPackageUidAsUser(eq(TEST_VPN_PKG), anyInt()))
                .thenReturn(uid);
        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());

        setAndVerifyAlwaysOnPackage(vpn, uid, false);
        assertTrue(vpn.startAlwaysOnVpn());

        // TODO: Test the Ikev2VpnRunner started up properly. Relies on utility methods added in
        // a subsequent CL.
    }

    private Vpn startLegacyVpn(final Vpn vpn, final VpnProfile vpnProfile) throws Exception {
        setMockedUsers(primaryUser);

        // Dummy egress interface
        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(EGRESS_IFACE);

        final RouteInfo defaultRoute = new RouteInfo(new IpPrefix(Inet4Address.ANY, 0),
                        InetAddresses.parseNumericAddress("192.0.2.0"), EGRESS_IFACE);
        lp.addRoute(defaultRoute);

        vpn.startLegacyVpn(vpnProfile, EGRESS_NETWORK, lp);
        return vpn;
    }

    @Test
    public void testStartPlatformVpn() throws Exception {
        startLegacyVpn(createVpn(primaryUser.id), mVpnProfile);
        // TODO: Test the Ikev2VpnRunner started up properly. Relies on utility methods added in
        // a subsequent patch.
    }

    @Test
    public void testStartRacoonNumericAddress() throws Exception {
        startRacoon("1.2.3.4", "1.2.3.4");
    }

    @Test
    public void testStartRacoonHostname() throws Exception {
        startRacoon("hostname", "5.6.7.8"); // address returned by deps.resolve
    }

    private void assertTransportInfoMatches(NetworkCapabilities nc, int type) {
        assertNotNull(nc);
        VpnTransportInfo ti = (VpnTransportInfo) nc.getTransportInfo();
        assertNotNull(ti);
        assertEquals(type, ti.getType());
    }

    public void startRacoon(final String serverAddr, final String expectedAddr)
            throws Exception {
        final ConditionVariable legacyRunnerReady = new ConditionVariable();
        final VpnProfile profile = new VpnProfile("testProfile" /* key */);
        profile.type = VpnProfile.TYPE_L2TP_IPSEC_PSK;
        profile.name = "testProfileName";
        profile.username = "userName";
        profile.password = "thePassword";
        profile.server = serverAddr;
        profile.ipsecIdentifier = "id";
        profile.ipsecSecret = "secret";
        profile.l2tpSecret = "l2tpsecret";

        when(mConnectivityManager.getAllNetworks())
            .thenReturn(new Network[] { new Network(101) });

        when(mConnectivityManager.registerNetworkAgent(any(), any(), any(), any(),
                any(), any(), anyInt())).thenAnswer(invocation -> {
                    // The runner has registered an agent and is now ready.
                    legacyRunnerReady.open();
                    return new Network(102);
                });
        final Vpn vpn = startLegacyVpn(createVpn(primaryUser.id), profile);
        final TestDeps deps = (TestDeps) vpn.mDeps;
        try {
            // udppsk and 1701 are the values for TYPE_L2TP_IPSEC_PSK
            assertArrayEquals(
                    new String[] { EGRESS_IFACE, expectedAddr, "udppsk",
                            profile.ipsecIdentifier, profile.ipsecSecret, "1701" },
                    deps.racoonArgs.get(10, TimeUnit.SECONDS));
            // literal values are hardcoded in Vpn.java for mtpd args
            assertArrayEquals(
                    new String[] { EGRESS_IFACE, "l2tp", expectedAddr, "1701", profile.l2tpSecret,
                            "name", profile.username, "password", profile.password,
                            "linkname", "vpn", "refuse-eap", "nodefaultroute", "usepeerdns",
                            "idle", "1800", "mtu", "1270", "mru", "1270" },
                    deps.mtpdArgs.get(10, TimeUnit.SECONDS));

            // Now wait for the runner to be ready before testing for the route.
            ArgumentCaptor<LinkProperties> lpCaptor = ArgumentCaptor.forClass(LinkProperties.class);
            ArgumentCaptor<NetworkCapabilities> ncCaptor =
                    ArgumentCaptor.forClass(NetworkCapabilities.class);
            verify(mConnectivityManager, timeout(10_000)).registerNetworkAgent(any(), any(),
                    lpCaptor.capture(), ncCaptor.capture(), any(), any(), anyInt());

            // In this test the expected address is always v4 so /32.
            // Note that the interface needs to be specified because RouteInfo objects stored in
            // LinkProperties objects always acquire the LinkProperties' interface.
            final RouteInfo expectedRoute = new RouteInfo(new IpPrefix(expectedAddr + "/32"),
                    null, EGRESS_IFACE, RouteInfo.RTN_THROW);
            final List<RouteInfo> actualRoutes = lpCaptor.getValue().getRoutes();
            assertTrue("Expected throw route (" + expectedRoute + ") not found in " + actualRoutes,
                    actualRoutes.contains(expectedRoute));

            assertTransportInfoMatches(ncCaptor.getValue(), VpnManager.TYPE_VPN_LEGACY);
        } finally {
            // Now interrupt the thread, unblock the runner and clean up.
            vpn.mVpnRunner.exitVpnRunner();
            deps.getStateFile().delete(); // set to delete on exit, but this deletes it earlier
            vpn.mVpnRunner.join(10_000); // wait for up to 10s for the runner to die and cleanup
        }
    }

    private static final class TestDeps extends Vpn.Dependencies {
        public final CompletableFuture<String[]> racoonArgs = new CompletableFuture();
        public final CompletableFuture<String[]> mtpdArgs = new CompletableFuture();
        public final File mStateFile;

        private final HashMap<String, Boolean> mRunningServices = new HashMap<>();

        TestDeps() {
            try {
                mStateFile = File.createTempFile("vpnTest", ".tmp");
                mStateFile.deleteOnExit();
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean isCallerSystem() {
            return true;
        }

        @Override
        public void startService(final String serviceName) {
            mRunningServices.put(serviceName, true);
        }

        @Override
        public void stopService(final String serviceName) {
            mRunningServices.put(serviceName, false);
        }

        @Override
        public boolean isServiceRunning(final String serviceName) {
            return mRunningServices.getOrDefault(serviceName, false);
        }

        @Override
        public boolean isServiceStopped(final String serviceName) {
            return !isServiceRunning(serviceName);
        }

        @Override
        public File getStateFile() {
            return mStateFile;
        }

        @Override
        public PendingIntent getIntentForStatusPanel(Context context) {
            return null;
        }

        @Override
        public void sendArgumentsToDaemon(
                final String daemon, final LocalSocket socket, final String[] arguments,
                final Vpn.RetryScheduler interruptChecker) throws IOException {
            if ("racoon".equals(daemon)) {
                racoonArgs.complete(arguments);
            } else if ("mtpd".equals(daemon)) {
                writeStateFile(arguments);
                mtpdArgs.complete(arguments);
            } else {
                throw new UnsupportedOperationException("Unsupported daemon : " + daemon);
            }
        }

        private void writeStateFile(final String[] arguments) throws IOException {
            mStateFile.delete();
            mStateFile.createNewFile();
            mStateFile.deleteOnExit();
            final BufferedWriter writer = new BufferedWriter(
                    new FileWriter(mStateFile, false /* append */));
            writer.write(EGRESS_IFACE);
            writer.write("\n");
            // addresses
            writer.write("10.0.0.1/24\n");
            // routes
            writer.write("192.168.6.0/24\n");
            // dns servers
            writer.write("192.168.6.1\n");
            // search domains
            writer.write("vpn.searchdomains.com\n");
            // endpoint - intentionally empty
            writer.write("\n");
            writer.flush();
            writer.close();
        }

        @Override
        @NonNull
        public InetAddress resolve(final String endpoint) {
            try {
                // If a numeric IP address, return it.
                return InetAddress.parseNumericAddress(endpoint);
            } catch (IllegalArgumentException e) {
                // Otherwise, return some token IP to test for.
                return InetAddress.parseNumericAddress("5.6.7.8");
            }
        }

        @Override
        public boolean isInterfacePresent(final Vpn vpn, final String iface) {
            return true;
        }
    }

    /**
     * Mock some methods of vpn object.
     */
    private Vpn createVpn(@UserIdInt int userId) {
        final Context asUserContext = mock(Context.class, AdditionalAnswers.delegatesTo(mContext));
        doReturn(UserHandle.of(userId)).when(asUserContext).getUser();
        when(mContext.createContextAsUser(eq(UserHandle.of(userId)), anyInt()))
                .thenReturn(asUserContext);
        final TestLooper testLooper = new TestLooper();
        final Vpn vpn = new Vpn(testLooper.getLooper(), mContext, new TestDeps(), mNetService,
                mNetd, userId, mVpnProfileStore, mSystemServices, mIkev2SessionCreator);
        verify(mConnectivityManager, times(1)).registerNetworkProvider(argThat(
                provider -> provider.getName().contains("VpnNetworkProvider")
        ));
        return vpn;
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
            final ArrayList<UserInfo> result = new ArrayList<>(users.length);
            for (UserInfo ui : users) {
                if (ui.isEnabled() && !ui.partial) {
                    result.add(ui);
                }
            }
            return result;
        }).when(mUserManager).getAliveUsers();

        doAnswer(invocation -> {
            final int id = (int) invocation.getArguments()[0];
            return userMap.get(id);
        }).when(mUserManager).getUserInfo(anyInt());
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
}
