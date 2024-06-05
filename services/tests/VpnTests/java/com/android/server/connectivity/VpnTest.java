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

import static android.Manifest.permission.BIND_VPN_SERVICE;
import static android.Manifest.permission.CONTROL_VPN;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.ConnectivityDiagnosticsManager.ConnectivityDiagnosticsCallback;
import static android.net.ConnectivityDiagnosticsManager.DataStallReport;
import static android.net.ConnectivityManager.NetworkCallback;
import static android.net.INetd.IF_STATE_DOWN;
import static android.net.INetd.IF_STATE_UP;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.RouteInfo.RTN_UNREACHABLE;
import static android.net.VpnManager.TYPE_VPN_PLATFORM;
import static android.net.cts.util.IkeSessionTestUtils.CHILD_PARAMS;
import static android.net.cts.util.IkeSessionTestUtils.TEST_IDENTITY;
import static android.net.cts.util.IkeSessionTestUtils.TEST_KEEPALIVE_TIMEOUT_UNSET;
import static android.net.cts.util.IkeSessionTestUtils.getTestIkeSessionParams;
import static android.net.ipsec.ike.IkeSessionConfiguration.EXTENSION_TYPE_MOBIKE;
import static android.net.ipsec.ike.IkeSessionParams.ESP_ENCAP_TYPE_AUTO;
import static android.net.ipsec.ike.IkeSessionParams.ESP_ENCAP_TYPE_NONE;
import static android.net.ipsec.ike.IkeSessionParams.ESP_ENCAP_TYPE_UDP;
import static android.net.ipsec.ike.IkeSessionParams.ESP_IP_VERSION_AUTO;
import static android.net.ipsec.ike.IkeSessionParams.ESP_IP_VERSION_IPV4;
import static android.net.ipsec.ike.IkeSessionParams.ESP_IP_VERSION_IPV6;
import static android.os.UserHandle.PER_USER_RANGE;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_CONFIG_APPLIED_BOOL;
import static android.telephony.CarrierConfigManager.KEY_MIN_UDP_PORT_4500_NAT_TIMEOUT_SEC_INT;
import static android.telephony.CarrierConfigManager.KEY_PREFERRED_IKE_PROTOCOL_INT;

import static com.android.net.module.util.NetworkStackConstants.IPV6_MIN_MTU;
import static com.android.server.connectivity.Vpn.AUTOMATIC_KEEPALIVE_DELAY_SECONDS;
import static com.android.server.connectivity.Vpn.DEFAULT_LONG_LIVED_TCP_CONNS_EXPENSIVE_TIMEOUT_SEC;
import static com.android.server.connectivity.Vpn.DEFAULT_UDP_PORT_4500_NAT_TIMEOUT_SEC_INT;
import static com.android.server.connectivity.Vpn.PREFERRED_IKE_PROTOCOL_AUTO;
import static com.android.server.connectivity.Vpn.PREFERRED_IKE_PROTOCOL_IPV4_UDP;
import static com.android.server.connectivity.Vpn.PREFERRED_IKE_PROTOCOL_IPV6_ESP;
import static com.android.server.connectivity.Vpn.PREFERRED_IKE_PROTOCOL_IPV6_UDP;
import static com.android.testutils.HandlerUtils.waitForIdleSerialExecutor;
import static com.android.testutils.MiscAsserts.assertThrows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
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
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.net.ConnectivityDiagnosticsManager;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.Ikev2VpnProfile;
import android.net.InetAddresses;
import android.net.InterfaceConfigurationParcel;
import android.net.IpPrefix;
import android.net.IpSecConfig;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.IpSecTunnelInterfaceResponse;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo.DetailedState;
import android.net.RouteInfo;
import android.net.TelephonyNetworkSpecifier;
import android.net.UidRangeParcel;
import android.net.VpnManager;
import android.net.VpnProfileState;
import android.net.VpnService;
import android.net.VpnTransportInfo;
import android.net.ipsec.ike.ChildSessionCallback;
import android.net.ipsec.ike.ChildSessionConfiguration;
import android.net.ipsec.ike.IkeFqdnIdentification;
import android.net.ipsec.ike.IkeSessionCallback;
import android.net.ipsec.ike.IkeSessionConfiguration;
import android.net.ipsec.ike.IkeSessionConnectionInfo;
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.IkeTrafficSelector;
import android.net.ipsec.ike.IkeTunnelConnectionParams;
import android.net.ipsec.ike.exceptions.IkeException;
import android.net.ipsec.ike.exceptions.IkeNetworkLostException;
import android.net.ipsec.ike.exceptions.IkeNonProtocolException;
import android.net.ipsec.ike.exceptions.IkeProtocolException;
import android.net.ipsec.ike.exceptions.IkeTimeoutException;
import android.net.vcn.VcnTransportInfo;
import android.net.wifi.WifiInfo;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.INetworkManagementService;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.PowerWhitelistManager;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.security.Credentials;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Range;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.internal.util.HexDump;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.DeviceIdleInternal;
import com.android.server.IpSecService;
import com.android.server.VpnTestBase;
import com.android.server.vcn.util.PersistableBundleUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalAnswers;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.StringWriter;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests for {@link Vpn}.
 *
 * Build, install and run with:
 *  runtest frameworks-net -c com.android.server.connectivity.VpnTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VpnTest extends VpnTestBase {
    private static final String TAG = "VpnTest";

    static final Network EGRESS_NETWORK = new Network(101);
    static final String EGRESS_IFACE = "wlan0";
    private static final String TEST_VPN_CLIENT = "2.4.6.8";
    private static final String TEST_VPN_SERVER = "1.2.3.4";
    private static final String TEST_VPN_IDENTITY = "identity";
    private static final byte[] TEST_VPN_PSK = "psk".getBytes();

    private static final int IP4_PREFIX_LEN = 32;
    private static final int IP6_PREFIX_LEN = 64;
    private static final int MIN_PORT = 0;
    private static final int MAX_PORT = 65535;

    private static final InetAddress TEST_VPN_CLIENT_IP =
            InetAddresses.parseNumericAddress(TEST_VPN_CLIENT);
    private static final InetAddress TEST_VPN_SERVER_IP =
            InetAddresses.parseNumericAddress(TEST_VPN_SERVER);
    private static final InetAddress TEST_VPN_CLIENT_IP_2 =
            InetAddresses.parseNumericAddress("192.0.2.200");
    private static final InetAddress TEST_VPN_SERVER_IP_2 =
            InetAddresses.parseNumericAddress("192.0.2.201");
    private static final InetAddress TEST_VPN_INTERNAL_IP =
            InetAddresses.parseNumericAddress("198.51.100.10");
    private static final InetAddress TEST_VPN_INTERNAL_IP6 =
            InetAddresses.parseNumericAddress("2001:db8::1");
    private static final InetAddress TEST_VPN_INTERNAL_DNS =
            InetAddresses.parseNumericAddress("8.8.8.8");
    private static final InetAddress TEST_VPN_INTERNAL_DNS6 =
            InetAddresses.parseNumericAddress("2001:4860:4860::8888");

    private static final IkeTrafficSelector IN_TS =
            new IkeTrafficSelector(MIN_PORT, MAX_PORT, TEST_VPN_INTERNAL_IP, TEST_VPN_INTERNAL_IP);
    private static final IkeTrafficSelector IN_TS6 =
            new IkeTrafficSelector(
                    MIN_PORT, MAX_PORT, TEST_VPN_INTERNAL_IP6, TEST_VPN_INTERNAL_IP6);
    private static final IkeTrafficSelector OUT_TS =
            new IkeTrafficSelector(MIN_PORT, MAX_PORT,
                    InetAddresses.parseNumericAddress("0.0.0.0"),
                    InetAddresses.parseNumericAddress("255.255.255.255"));
    private static final IkeTrafficSelector OUT_TS6 =
            new IkeTrafficSelector(
                    MIN_PORT,
                    MAX_PORT,
                    InetAddresses.parseNumericAddress("::"),
                    InetAddresses.parseNumericAddress("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"));

    private static final Network TEST_NETWORK = new Network(Integer.MAX_VALUE);
    private static final Network TEST_NETWORK_2 = new Network(Integer.MAX_VALUE - 1);
    private static final String TEST_IFACE_NAME = "TEST_IFACE";
    private static final int TEST_TUNNEL_RESOURCE_ID = 0x2345;
    private static final long TEST_TIMEOUT_MS = 500L;
    private static final long TIMEOUT_CROSSTHREAD_MS = 20_000L;
    private static final String PRIMARY_USER_APP_EXCLUDE_KEY =
            "VPNAPPEXCLUDED_27_com.testvpn.vpn";
    static final String PKGS_BYTES = getPackageByteString(List.of(PKGS));
    private static final Range<Integer> PRIMARY_USER_RANGE = uidRangeForUser(PRIMARY_USER.id);
    private static final int TEST_KEEPALIVE_TIMER = 800;
    private static final int TEST_SUB_ID = 1234;
    private static final String TEST_MCCMNC = "12345";

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private Context mContext;
    @Mock private UserManager mUserManager;
    @Mock private PackageManager mPackageManager;
    @Mock private INetworkManagementService mNetService;
    @Mock private INetd mNetd;
    @Mock private AppOpsManager mAppOps;
    @Mock private NotificationManager mNotificationManager;
    @Mock private Vpn.SystemServices mSystemServices;
    @Mock private Vpn.IkeSessionWrapper mIkeSessionWrapper;
    @Mock private Vpn.Ikev2SessionCreator mIkev2SessionCreator;
    @Mock private Vpn.VpnNetworkAgentWrapper mMockNetworkAgent;
    @Mock private ConnectivityManager mConnectivityManager;
    @Mock private ConnectivityDiagnosticsManager mCdm;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private TelephonyManager mTmPerSub;
    @Mock private CarrierConfigManager mConfigManager;
    @Mock private SubscriptionManager mSubscriptionManager;
    @Mock private IpSecService mIpSecService;
    @Mock private VpnProfileStore mVpnProfileStore;
    private final TestExecutor mExecutor;
    @Mock DeviceIdleInternal mDeviceIdleInternal;
    private final VpnProfile mVpnProfile;

    @Captor private ArgumentCaptor<Collection<Range<Integer>>> mUidRangesCaptor;

    private IpSecManager mIpSecManager;
    private TestDeps mTestDeps;

    public static class TestExecutor extends ScheduledThreadPoolExecutor {
        public static final long REAL_DELAY = -1;

        // For the purposes of the test, run all scheduled tasks after 10ms to save
        // execution time, unless overridden by the specific test. Set to REAL_DELAY
        // to actually wait for the delay specified by the real call to schedule().
        public long delayMs = 10;
        // If this is true, execute() will call the runnable inline. This is useful because
        // super.execute() calls schedule(), which messes with checks that scheduled() is
        // called a given number of times.
        public boolean executeDirect = false;

        public TestExecutor() {
            super(1);
        }

        @Override
        public void execute(final Runnable command) {
            // See |executeDirect| for why this is necessary.
            if (executeDirect) {
                command.run();
            } else {
                super.execute(command);
            }
        }

        @Override
        public ScheduledFuture<?> schedule(final Runnable command, final long delay,
                TimeUnit unit) {
            if (0 == delay || delayMs == REAL_DELAY) {
                // super.execute() calls schedule() with 0, so use the real delay if it's 0.
                return super.schedule(command, delay, unit);
            } else {
                return super.schedule(command, delayMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    public VpnTest() throws Exception {
        // Build an actual VPN profile that is capable of being converted to and from an
        // Ikev2VpnProfile
        final Ikev2VpnProfile.Builder builder =
                new Ikev2VpnProfile.Builder(TEST_VPN_SERVER, TEST_VPN_IDENTITY);
        builder.setAuthPsk(TEST_VPN_PSK);
        builder.setBypassable(true /* isBypassable */);
        mExecutor = spy(new TestExecutor());
        mVpnProfile = builder.build().toVpnProfile();
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mIpSecManager = new IpSecManager(mContext, mIpSecService);
        mTestDeps = spy(new TestDeps());
        doReturn(IPV6_MIN_MTU)
                .when(mTestDeps)
                .calculateVpnMtu(any(), anyInt(), anyInt(), anyBoolean());
        doReturn(1500).when(mTestDeps).getJavaNetworkInterfaceMtu(any(), anyInt());

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        setMockedPackages(sPackages);

        when(mContext.getPackageName()).thenReturn(TEST_VPN_PKG);
        when(mContext.getOpPackageName()).thenReturn(TEST_VPN_PKG);
        mockService(UserManager.class, Context.USER_SERVICE, mUserManager);
        mockService(AppOpsManager.class, Context.APP_OPS_SERVICE, mAppOps);
        mockService(NotificationManager.class, Context.NOTIFICATION_SERVICE, mNotificationManager);
        mockService(ConnectivityManager.class, Context.CONNECTIVITY_SERVICE, mConnectivityManager);
        mockService(IpSecManager.class, Context.IPSEC_SERVICE, mIpSecManager);
        mockService(ConnectivityDiagnosticsManager.class, Context.CONNECTIVITY_DIAGNOSTICS_SERVICE,
                mCdm);
        mockService(TelephonyManager.class, Context.TELEPHONY_SERVICE, mTelephonyManager);
        mockService(CarrierConfigManager.class, Context.CARRIER_CONFIG_SERVICE, mConfigManager);
        mockService(SubscriptionManager.class, Context.TELEPHONY_SUBSCRIPTION_SERVICE,
                mSubscriptionManager);
        doReturn(mTmPerSub).when(mTelephonyManager).createForSubscriptionId(anyInt());
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
        doReturn(new LinkProperties()).when(mConnectivityManager).getLinkProperties(any());

        // The unit test should know what kind of permission it needs and set the permission by
        // itself, so set the default value of Context#checkCallingOrSelfPermission to
        // PERMISSION_DENIED.
        doReturn(PERMISSION_DENIED).when(mContext).checkCallingOrSelfPermission(any());

        // Set up mIkev2SessionCreator and mExecutor
        resetIkev2SessionCreator(mIkeSessionWrapper);
    }

    private void resetIkev2SessionCreator(Vpn.IkeSessionWrapper ikeSession) {
        reset(mIkev2SessionCreator);
        when(mIkev2SessionCreator.createIkeSession(any(), any(), any(), any(), any(), any()))
                .thenReturn(ikeSession);
    }

    private <T> void mockService(Class<T> clazz, String name, T service) {
        doReturn(service).when(mContext).getSystemService(name);
        doReturn(name).when(mContext).getSystemServiceName(clazz);
        if (mContext.getSystemService(clazz).getClass().equals(Object.class)) {
            // Test is using mockito-extended (mContext uses Answers.RETURNS_DEEP_STUBS and returned
            // a mock object on a final method)
            doCallRealMethod().when(mContext).getSystemService(clazz);
        }
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

    private static String getPackageByteString(List<String> packages) {
        try {
            return HexDump.toHexString(
                    PersistableBundleUtils.toDiskStableBytes(PersistableBundleUtils.fromList(
                            packages, PersistableBundleUtils.STRING_SERIALIZER)),
                        true /* upperCase */);
        } catch (IOException e) {
            return null;
        }
    }

    @Test
    public void testRestrictedProfilesAreAddedToVpn() {
        setMockedUsers(PRIMARY_USER, SECONDARY_USER, RESTRICTED_PROFILE_A, RESTRICTED_PROFILE_B);

        final Vpn vpn = createVpn(PRIMARY_USER.id);

        // Assume the user can have restricted profiles.
        doReturn(true).when(mUserManager).canHaveRestrictedProfile();
        final Set<Range<Integer>> ranges =
                vpn.createUserAndRestrictedProfilesRanges(PRIMARY_USER.id, null, null);

        assertEquals(rangeSet(PRIMARY_USER_RANGE, uidRangeForUser(RESTRICTED_PROFILE_A.id)),
                 ranges);
    }

    @Test
    public void testManagedProfilesAreNotAddedToVpn() {
        setMockedUsers(PRIMARY_USER, MANAGED_PROFILE_A);

        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final Set<Range<Integer>> ranges = vpn.createUserAndRestrictedProfilesRanges(
                PRIMARY_USER.id, null, null);

        assertEquals(rangeSet(PRIMARY_USER_RANGE), ranges);
    }

    @Test
    public void testAddUserToVpnOnlyAddsOneUser() {
        setMockedUsers(PRIMARY_USER, RESTRICTED_PROFILE_A, MANAGED_PROFILE_A);

        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final Set<Range<Integer>> ranges = new ArraySet<>();
        vpn.addUserToRanges(ranges, PRIMARY_USER.id, null, null);

        assertEquals(rangeSet(PRIMARY_USER_RANGE), ranges);
    }

    @Test
    public void testUidAllowAndDenylist() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final Range<Integer> user = PRIMARY_USER_RANGE;
        final int userStart = user.getLower();
        final int userStop = user.getUpper();
        final String[] packages = {PKGS[0], PKGS[1], PKGS[2]};

        // Allowed list
        final Set<Range<Integer>> allow = vpn.createUserAndRestrictedProfilesRanges(PRIMARY_USER.id,
                Arrays.asList(packages), null /* disallowedApplications */);
        assertEquals(rangeSet(
                uidRange(userStart + PKG_UIDS[0], userStart + PKG_UIDS[0]),
                uidRange(userStart + PKG_UIDS[1], userStart + PKG_UIDS[2]),
                uidRange(Process.toSdkSandboxUid(userStart + PKG_UIDS[0]),
                         Process.toSdkSandboxUid(userStart + PKG_UIDS[0])),
                uidRange(Process.toSdkSandboxUid(userStart + PKG_UIDS[1]),
                         Process.toSdkSandboxUid(userStart + PKG_UIDS[2]))),
                allow);

        // Denied list
        final Set<Range<Integer>> disallow =
                vpn.createUserAndRestrictedProfilesRanges(PRIMARY_USER.id,
                        null /* allowedApplications */, Arrays.asList(packages));
        assertEquals(rangeSet(
                uidRange(userStart, userStart + PKG_UIDS[0] - 1),
                uidRange(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[1] - 1),
                /* Empty range between UIDS[1] and UIDS[2], should be excluded, */
                uidRange(userStart + PKG_UIDS[2] + 1,
                         Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                uidRange(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1),
                         Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                uidRange(Process.toSdkSandboxUid(userStart + PKG_UIDS[2] + 1), userStop)),
                disallow);
    }

    private void verifyPowerSaveTempWhitelistApp(String packageName) {
        verify(mDeviceIdleInternal, timeout(TEST_TIMEOUT_MS)).addPowerSaveTempWhitelistApp(
                anyInt(), eq(packageName), anyLong(), anyInt(), eq(false),
                eq(PowerWhitelistManager.REASON_VPN), eq("VpnManager event"));
    }

    @Test
    public void testGetAlwaysAndOnGetLockDown() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);

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
    public void testAlwaysOnWithoutLockdown() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[1], false /* lockdown */, null /* lockdownAllowlist */));
        verify(mConnectivityManager, never()).setRequireVpnForUids(anyBoolean(), any());

        assertTrue(vpn.setAlwaysOnPackage(
                null /* packageName */, false /* lockdown */, null /* lockdownAllowlist */));
        verify(mConnectivityManager, never()).setRequireVpnForUids(anyBoolean(), any());
    }

    @Test
    public void testLockdownChangingPackage() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final Range<Integer> user = PRIMARY_USER_RANGE;
        final int userStart = user.getLower();
        final int userStop = user.getUpper();
        // Set always-on without lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], false, null));

        // Set always-on with lockdown.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], true, null));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[1] + 1), userStop)
        }));

        // Switch to another app.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[3], true, null));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[1] + 1), userStop)
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[3] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[3] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[3] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[3] + 1), userStop)
        }));
    }

    @Test
    public void testLockdownAllowlist() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final Range<Integer> user = PRIMARY_USER_RANGE;
        final int userStart = user.getLower();
        final int userStop = user.getUpper();
        // Set always-on with lockdown and allow app PKGS[2] from lockdown.
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[1], true, Collections.singletonList(PKGS[2])));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[]  {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[2] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1]) - 1),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[2] + 1), userStop)
        }));
        // Change allowed app list to PKGS[3].
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[1], true, Collections.singletonList(PKGS[3])));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[2] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[2] + 1), userStop)
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1, userStart + PKG_UIDS[3] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[3] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[1] + 1),
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[3] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[3] + 1), userStop)
        }));

        // Change the VPN app.
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[0], true, Collections.singletonList(PKGS[3])));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1, userStart + PKG_UIDS[3] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[3] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[1] + 1),
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[3] - 1))
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart, userStart + PKG_UIDS[0] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[3] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[3] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1),
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[3] - 1))
        }));

        // Remove the list of allowed packages.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[0], true, null));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[3] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[3] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1),
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[3] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[3] + 1), userStop)
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1), userStop),
        }));

        // Add the list of allowed packages.
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[0], true, Collections.singletonList(PKGS[1])));
        verify(mConnectivityManager).setRequireVpnForUids(false, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1), userStop),
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[1] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1),
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[1] + 1), userStop)
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
                new UidRangeParcel(userStart + PKG_UIDS[1] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1),
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[1] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[1] + 1), userStop)
        }));
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(new UidRangeParcel[] {
                new UidRangeParcel(userStart + PKG_UIDS[0] + 1, userStart + PKG_UIDS[2] - 1),
                new UidRangeParcel(userStart + PKG_UIDS[2] + 1,
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[0] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[0] + 1),
                                   Process.toSdkSandboxUid(userStart + PKG_UIDS[2] - 1)),
                new UidRangeParcel(Process.toSdkSandboxUid(userStart + PKG_UIDS[2] + 1), userStop)
        }));
    }

    @Test
    public void testLockdownSystemUser() throws Exception {
        final Vpn vpn = createVpn(SYSTEM_USER_ID);

        // Uid 0 is always excluded and PKG_UIDS[1] is the uid of the VPN.
        final List<Integer> excludedUids = new ArrayList<>(List.of(0, PKG_UIDS[1]));
        final List<Range<Integer>> ranges = makeVpnUidRange(SYSTEM_USER_ID, excludedUids);

        // Set always-on with lockdown.
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[1], true /* lockdown */, null /* lockdownAllowlist */));
        verify(mConnectivityManager).setRequireVpnForUids(true, ranges);

        // Disable always-on with lockdown.
        assertTrue(vpn.setAlwaysOnPackage(
                null /* packageName */, false /* lockdown */, null /* lockdownAllowlist */));
        verify(mConnectivityManager).setRequireVpnForUids(false, ranges);

        // Set always-on with lockdown and allow the app PKGS[2].
        excludedUids.add(PKG_UIDS[2]);
        final List<Range<Integer>> ranges2 = makeVpnUidRange(SYSTEM_USER_ID, excludedUids);
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[1], true /* lockdown */, Collections.singletonList(PKGS[2])));
        verify(mConnectivityManager).setRequireVpnForUids(true, ranges2);

        // Disable always-on with lockdown.
        assertTrue(vpn.setAlwaysOnPackage(
                null /* packageName */, false /* lockdown */, null /* lockdownAllowlist */));
        verify(mConnectivityManager).setRequireVpnForUids(false, ranges2);
    }

    @Test
    public void testLockdownRuleRepeatability() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final UidRangeParcel[] primaryUserRangeParcel = new UidRangeParcel[] {
                new UidRangeParcel(PRIMARY_USER_RANGE.getLower(), PRIMARY_USER_RANGE.getUpper())};
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
        doReturn(PERMISSION_GRANTED).when(mContext).checkCallingOrSelfPermission(CONTROL_VPN);
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final UidRangeParcel[] entireUser = {
            new UidRangeParcel(PRIMARY_USER_RANGE.getLower(), PRIMARY_USER_RANGE.getUpper())
        };
        final UidRangeParcel[] exceptPkg0 = {
            new UidRangeParcel(entireUser[0].start, entireUser[0].start + PKG_UIDS[0] - 1),
            new UidRangeParcel(entireUser[0].start + PKG_UIDS[0] + 1,
                               Process.toSdkSandboxUid(entireUser[0].start + PKG_UIDS[0] - 1)),
            new UidRangeParcel(Process.toSdkSandboxUid(entireUser[0].start + PKG_UIDS[0] + 1),
                               entireUser[0].stop),
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
    public void testOnUserAddedAndRemoved_restrictedUser() throws Exception {
        final InOrder order = inOrder(mMockNetworkAgent);
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final Set<Range<Integer>> initialRange = rangeSet(PRIMARY_USER_RANGE);
        // Note since mVpnProfile is a Ikev2VpnProfile, this starts an IkeV2VpnRunner.
        startLegacyVpn(vpn, mVpnProfile);
        // Set an initial Uid range and mock the network agent
        vpn.mNetworkCapabilities.setUids(initialRange);
        vpn.mNetworkAgent = mMockNetworkAgent;

        // Add the restricted user
        setMockedUsers(PRIMARY_USER, RESTRICTED_PROFILE_A);
        vpn.onUserAdded(RESTRICTED_PROFILE_A.id);
        // Expect restricted user range to be added to the NetworkCapabilities.
        final Set<Range<Integer>> expectRestrictedRange =
                rangeSet(PRIMARY_USER_RANGE, uidRangeForUser(RESTRICTED_PROFILE_A.id));
        assertEquals(expectRestrictedRange, vpn.mNetworkCapabilities.getUids());
        order.verify(mMockNetworkAgent).doSendNetworkCapabilities(
                argThat(nc -> expectRestrictedRange.equals(nc.getUids())));

        // Remove the restricted user
        vpn.onUserRemoved(RESTRICTED_PROFILE_A.id);
        // Expect restricted user range to be removed from the NetworkCapabilities.
        assertEquals(initialRange, vpn.mNetworkCapabilities.getUids());
        order.verify(mMockNetworkAgent).doSendNetworkCapabilities(
                argThat(nc -> initialRange.equals(nc.getUids())));
    }

    @Test
    public void testOnUserAddedAndRemoved_restrictedUserLockdown() throws Exception {
        final UidRangeParcel[] primaryUserRangeParcel = new UidRangeParcel[] {
                new UidRangeParcel(PRIMARY_USER_RANGE.getLower(), PRIMARY_USER_RANGE.getUpper())};
        final Range<Integer> restrictedUserRange = uidRangeForUser(RESTRICTED_PROFILE_A.id);
        final UidRangeParcel[] restrictedUserRangeParcel = new UidRangeParcel[] {
                new UidRangeParcel(restrictedUserRange.getLower(), restrictedUserRange.getUpper())};
        final Vpn vpn = createVpn(PRIMARY_USER.id);

        // Set lockdown calls setRequireVpnForUids
        vpn.setLockdown(true);
        verify(mConnectivityManager).setRequireVpnForUids(true, toRanges(primaryUserRangeParcel));

        // Add the restricted user
        doReturn(true).when(mUserManager).canHaveRestrictedProfile();
        setMockedUsers(PRIMARY_USER, RESTRICTED_PROFILE_A);
        vpn.onUserAdded(RESTRICTED_PROFILE_A.id);

        // Expect restricted user range to be added.
        verify(mConnectivityManager).setRequireVpnForUids(true,
                toRanges(restrictedUserRangeParcel));

        // Mark as partial indicates that the user is removed, mUserManager.getAliveUsers() does not
        // return the restricted user but it is still returned in mUserManager.getUserInfo().
        RESTRICTED_PROFILE_A.partial = true;
        // Remove the restricted user
        vpn.onUserRemoved(RESTRICTED_PROFILE_A.id);
        verify(mConnectivityManager).setRequireVpnForUids(false,
                toRanges(restrictedUserRangeParcel));
        // reset to avoid affecting other tests since RESTRICTED_PROFILE_A is static.
        RESTRICTED_PROFILE_A.partial = false;
    }

    @Test
    public void testOnUserAddedAndRemoved_restrictedUserAlwaysOn() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);

        // setAlwaysOnPackage() calls setRequireVpnForUids()
        assertTrue(vpn.setAlwaysOnPackage(
                PKGS[0], true /* lockdown */, null /* lockdownAllowlist */));
        final List<Integer> excludedUids = List.of(PKG_UIDS[0]);
        final List<Range<Integer>> primaryRanges =
                makeVpnUidRange(PRIMARY_USER.id, excludedUids);
        verify(mConnectivityManager).setRequireVpnForUids(true, primaryRanges);

        // Add the restricted user
        doReturn(true).when(mUserManager).canHaveRestrictedProfile();
        setMockedUsers(PRIMARY_USER, RESTRICTED_PROFILE_A);
        vpn.onUserAdded(RESTRICTED_PROFILE_A.id);

        final List<Range<Integer>> restrictedRanges =
                makeVpnUidRange(RESTRICTED_PROFILE_A.id, excludedUids);
        // Expect restricted user range to be added.
        verify(mConnectivityManager).setRequireVpnForUids(true, restrictedRanges);

        // Mark as partial indicates that the user is removed, mUserManager.getAliveUsers() does not
        // return the restricted user but it is still returned in mUserManager.getUserInfo().
        RESTRICTED_PROFILE_A.partial = true;
        // Remove the restricted user
        vpn.onUserRemoved(RESTRICTED_PROFILE_A.id);
        verify(mConnectivityManager).setRequireVpnForUids(false, restrictedRanges);

        // reset to avoid affecting other tests since RESTRICTED_PROFILE_A is static.
        RESTRICTED_PROFILE_A.partial = false;
    }

    @Test
    public void testPrepare_throwSecurityExceptionWhenGivenPackageDoesNotBelongToTheCaller()
            throws Exception {
        mTestDeps.mIgnoreCallingUidChecks = false;
        final Vpn vpn = createVpn();
        assertThrows(SecurityException.class,
                () -> vpn.prepare("com.not.vpn.owner", null, VpnManager.TYPE_VPN_SERVICE));
        assertThrows(SecurityException.class,
                () -> vpn.prepare(null, "com.not.vpn.owner", VpnManager.TYPE_VPN_SERVICE));
        assertThrows(SecurityException.class,
                () -> vpn.prepare("com.not.vpn.owner1", "com.not.vpn.owner2",
                        VpnManager.TYPE_VPN_SERVICE));
    }

    @Test
    public void testPrepare_bothOldPackageAndNewPackageAreNull() throws Exception {
        final Vpn vpn = createVpn();
        assertTrue(vpn.prepare(null, null, VpnManager.TYPE_VPN_SERVICE));

    }

    @Test
    public void testPrepare_legacyVpnWithoutControlVpn()
            throws Exception {
        doThrow(new SecurityException("no CONTROL_VPN")).when(mContext)
                .enforceCallingOrSelfPermission(eq(CONTROL_VPN), any());
        final Vpn vpn = createVpn();
        assertThrows(SecurityException.class,
                () -> vpn.prepare(null, VpnConfig.LEGACY_VPN, VpnManager.TYPE_VPN_SERVICE));

        // CONTROL_VPN can be held by the caller or another system server process - both are
        // allowed. Just checking for `enforceCallingPermission` may not be sufficient.
        verify(mContext, never()).enforceCallingPermission(eq(CONTROL_VPN), any());
    }

    @Test
    public void testPrepare_legacyVpnWithControlVpn()
            throws Exception {
        doNothing().when(mContext).enforceCallingOrSelfPermission(eq(CONTROL_VPN), any());
        final Vpn vpn = createVpn();
        assertTrue(vpn.prepare(null, VpnConfig.LEGACY_VPN, VpnManager.TYPE_VPN_SERVICE));

        // CONTROL_VPN can be held by the caller or another system server process - both are
        // allowed. Just checking for `enforceCallingPermission` may not be sufficient.
        verify(mContext, never()).enforceCallingPermission(eq(CONTROL_VPN), any());
    }

    @Test
    public void testIsAlwaysOnPackageSupported() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);

        ApplicationInfo appInfo = new ApplicationInfo();
        when(mPackageManager.getApplicationInfoAsUser(eq(PKGS[0]), anyInt(), eq(PRIMARY_USER.id)))
                .thenReturn(appInfo);

        ServiceInfo svcInfo = new ServiceInfo();
        ResolveInfo resInfo = new ResolveInfo();
        resInfo.serviceInfo = svcInfo;
        when(mPackageManager.queryIntentServicesAsUser(any(), eq(PackageManager.GET_META_DATA),
                eq(PRIMARY_USER.id)))
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
        final UserHandle userHandle = UserHandle.of(PRIMARY_USER.id);
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        setMockedUsers(PRIMARY_USER);

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
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        setMockedUsers(PRIMARY_USER);

        final String expected = Credentials.PLATFORM_VPN + PRIMARY_USER.id + "_" + TEST_VPN_PKG;
        assertEquals(expected, vpn.getProfileNameForPackage(TEST_VPN_PKG));
    }

    private Vpn createVpn(String... grantedOps) throws Exception {
        return createVpn(PRIMARY_USER, grantedOps);
    }

    private Vpn createVpn(UserInfo user, String... grantedOps) throws Exception {
        final Vpn vpn = createVpn(user.id);
        setMockedUsers(user);

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
        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            checkProvisionVpnProfile(
                    vpn, true /* expectedResult */, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
            fail("Expected exception due to missing feature");
        } catch (UnsupportedOperationException expected) {
        }
    }

    private String startVpnForVerifyAppExclusionList(Vpn vpn) throws Exception {
        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());
        when(mVpnProfileStore.get(PRIMARY_USER_APP_EXCLUDE_KEY))
                .thenReturn(HexDump.hexStringToByteArray(PKGS_BYTES));
        final String sessionKey = vpn.startVpnProfile(TEST_VPN_PKG);
        final Set<Range<Integer>> uidRanges = vpn.createUserAndRestrictedProfilesRanges(
                PRIMARY_USER.id, null /* allowedApplications */, Arrays.asList(PKGS));
        verify(mConnectivityManager).setVpnDefaultForUids(eq(sessionKey), eq(uidRanges));
        clearInvocations(mConnectivityManager);
        verify(mVpnProfileStore).get(eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)));
        vpn.mNetworkAgent = mMockNetworkAgent;

        return sessionKey;
    }

    private Vpn prepareVpnForVerifyAppExclusionList() throws Exception {
        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
        startVpnForVerifyAppExclusionList(vpn);

        return vpn;
    }

    @Test
    public void testSetAndGetAppExclusionList() throws Exception {
        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
        final String sessionKey = startVpnForVerifyAppExclusionList(vpn);
        verify(mVpnProfileStore, never()).put(eq(PRIMARY_USER_APP_EXCLUDE_KEY), any());
        vpn.setAppExclusionList(TEST_VPN_PKG, Arrays.asList(PKGS));
        verify(mVpnProfileStore)
                .put(eq(PRIMARY_USER_APP_EXCLUDE_KEY),
                     eq(HexDump.hexStringToByteArray(PKGS_BYTES)));
        final Set<Range<Integer>> uidRanges = vpn.createUserAndRestrictedProfilesRanges(
                PRIMARY_USER.id, null /* allowedApplications */, Arrays.asList(PKGS));
        verify(mConnectivityManager).setVpnDefaultForUids(eq(sessionKey), eq(uidRanges));
        assertEquals(uidRanges, vpn.mNetworkCapabilities.getUids());
        assertEquals(Arrays.asList(PKGS), vpn.getAppExclusionList(TEST_VPN_PKG));
    }

    @Test
    public void testRefreshPlatformVpnAppExclusionList_updatesExcludedUids() throws Exception {
        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
        final String sessionKey = startVpnForVerifyAppExclusionList(vpn);
        vpn.setAppExclusionList(TEST_VPN_PKG, Arrays.asList(PKGS));
        final Set<Range<Integer>> uidRanges = vpn.createUserAndRestrictedProfilesRanges(
                PRIMARY_USER.id, null /* allowedApplications */, Arrays.asList(PKGS));
        verify(mConnectivityManager).setVpnDefaultForUids(eq(sessionKey), eq(uidRanges));
        verify(mMockNetworkAgent).doSendNetworkCapabilities(any());
        assertEquals(Arrays.asList(PKGS), vpn.getAppExclusionList(TEST_VPN_PKG));

        reset(mMockNetworkAgent);

        // Remove one of the package
        List<Integer> newExcludedUids = toList(PKG_UIDS);
        newExcludedUids.remove((Integer) PKG_UIDS[0]);
        Set<Range<Integer>> newUidRanges = makeVpnUidRangeSet(PRIMARY_USER.id, newExcludedUids);
        sPackages.remove(PKGS[0]);
        vpn.refreshPlatformVpnAppExclusionList();

        // List in keystore is not changed, but UID for the removed packages is no longer exempted.
        assertEquals(Arrays.asList(PKGS), vpn.getAppExclusionList(TEST_VPN_PKG));
        assertEquals(newUidRanges, vpn.mNetworkCapabilities.getUids());
        ArgumentCaptor<NetworkCapabilities> ncCaptor =
                ArgumentCaptor.forClass(NetworkCapabilities.class);
        verify(mMockNetworkAgent).doSendNetworkCapabilities(ncCaptor.capture());
        assertEquals(newUidRanges, ncCaptor.getValue().getUids());
        verify(mConnectivityManager).setVpnDefaultForUids(eq(sessionKey), eq(newUidRanges));

        reset(mMockNetworkAgent);

        // Add the package back
        newExcludedUids.add(PKG_UIDS[0]);
        newUidRanges = makeVpnUidRangeSet(PRIMARY_USER.id, newExcludedUids);
        sPackages.put(PKGS[0], PKG_UIDS[0]);
        vpn.refreshPlatformVpnAppExclusionList();

        // List in keystore is not changed and the uid list should be updated in the net cap.
        assertEquals(Arrays.asList(PKGS), vpn.getAppExclusionList(TEST_VPN_PKG));
        assertEquals(newUidRanges, vpn.mNetworkCapabilities.getUids());
        verify(mMockNetworkAgent).doSendNetworkCapabilities(ncCaptor.capture());
        assertEquals(newUidRanges, ncCaptor.getValue().getUids());

        // The uidRange is the same as the original setAppExclusionList so this is the second call
        verify(mConnectivityManager, times(2))
                .setVpnDefaultForUids(eq(sessionKey), eq(newUidRanges));
    }

    private List<Range<Integer>> makeVpnUidRange(int userId, List<Integer> excludedAppIdList) {
        final SortedSet<Integer> list = new TreeSet<>();

        final int userBase = userId * UserHandle.PER_USER_RANGE;
        for (int appId : excludedAppIdList) {
            final int uid = UserHandle.getUid(userId, appId);
            list.add(uid);
            if (Process.isApplicationUid(uid)) {
                list.add(Process.toSdkSandboxUid(uid)); // Add Sdk Sandbox UID
            }
        }

        final int minUid = userBase;
        final int maxUid = userBase + UserHandle.PER_USER_RANGE - 1;
        final List<Range<Integer>> ranges = new ArrayList<>();

        // Iterate the list to create the ranges between each uid.
        int start = minUid;
        for (int uid : list) {
            if (uid == start) {
                start++;
            } else {
                ranges.add(new Range<>(start, uid - 1));
                start = uid + 1;
            }
        }

        // Create the range between last uid and max uid.
        if (start <= maxUid) {
            ranges.add(new Range<>(start, maxUid));
        }

        return ranges;
    }

    private Set<Range<Integer>> makeVpnUidRangeSet(int userId, List<Integer> excludedAppIdList) {
        return new ArraySet<>(makeVpnUidRange(userId, excludedAppIdList));
    }

    @Test
    public void testSetAndGetAppExclusionListRestrictedUser() throws Exception {
        final Vpn vpn = prepareVpnForVerifyAppExclusionList();

        // Mock it to restricted profile
        when(mUserManager.getUserInfo(anyInt())).thenReturn(RESTRICTED_PROFILE_A);

        // Restricted users cannot configure VPNs
        assertThrows(SecurityException.class,
                () -> vpn.setAppExclusionList(TEST_VPN_PKG, new ArrayList<>()));

        assertEquals(Arrays.asList(PKGS), vpn.getAppExclusionList(TEST_VPN_PKG));
    }

    @Test
    public void testProvisionVpnProfilePreconsented() throws Exception {
        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        checkProvisionVpnProfile(
                vpn, true /* expectedResult */, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
    }

    @Test
    public void testProvisionVpnProfileNotPreconsented() throws Exception {
        final Vpn vpn = createVpn();

        // Expect that both the ACTIVATE_VPN and ACTIVATE_PLATFORM_VPN were tried, but the caller
        // had neither.
        checkProvisionVpnProfile(vpn, false /* expectedResult */,
                AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN, AppOpsManager.OPSTR_ACTIVATE_VPN);
    }

    @Test
    public void testProvisionVpnProfileVpnServicePreconsented() throws Exception {
        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_VPN);

        checkProvisionVpnProfile(vpn, true /* expectedResult */, AppOpsManager.OPSTR_ACTIVATE_VPN);
    }

    @Test
    public void testProvisionVpnProfileTooLarge() throws Exception {
        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

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
                createVpn(
                        RESTRICTED_PROFILE_A, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            vpn.provisionVpnProfile(TEST_VPN_PKG, mVpnProfile);
            fail("Expected SecurityException due to restricted user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testDeleteVpnProfile() throws Exception {
        final Vpn vpn = createVpn();

        vpn.deleteVpnProfile(TEST_VPN_PKG);

        verify(mVpnProfileStore)
                .remove(eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)));
    }

    @Test
    public void testDeleteVpnProfileRestrictedUser() throws Exception {
        final Vpn vpn =
                createVpn(
                        RESTRICTED_PROFILE_A, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            vpn.deleteVpnProfile(TEST_VPN_PKG);
            fail("Expected SecurityException due to restricted user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testGetVpnProfilePrivileged() throws Exception {
        final Vpn vpn = createVpn();

        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(new VpnProfile("").encode());

        vpn.getVpnProfilePrivileged(TEST_VPN_PKG);

        verify(mVpnProfileStore).get(eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)));
    }

    private void verifyPlatformVpnIsActivated(String packageName) {
        verify(mAppOps).noteOpNoThrow(
                eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN),
                eq(Process.myUid()),
                eq(packageName),
                eq(null) /* attributionTag */,
                eq(null) /* message */);
        verify(mAppOps).startOp(
                eq(AppOpsManager.OPSTR_ESTABLISH_VPN_MANAGER),
                eq(UserHandle.getUid(PRIMARY_USER.id, Process.myUid())),
                eq(packageName),
                eq(null) /* attributionTag */,
                eq(null) /* message */);
    }

    private void verifyPlatformVpnIsDeactivated(String packageName) {
        // Add a small delay to double confirm that finishOp is only called once.
        verify(mAppOps, after(100)).finishOp(
                eq(AppOpsManager.OPSTR_ESTABLISH_VPN_MANAGER),
                eq(UserHandle.getUid(PRIMARY_USER.id, Process.myUid())),
                eq(packageName),
                eq(null) /* attributionTag */);
    }

    @Test
    public void testStartVpnProfile() throws Exception {
        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());

        vpn.startVpnProfile(TEST_VPN_PKG);

        verify(mVpnProfileStore).get(eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)));
        verifyPlatformVpnIsActivated(TEST_VPN_PKG);
    }

    @Test
    public void testStartVpnProfileVpnServicePreconsented() throws Exception {
        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_VPN);

        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());

        vpn.startVpnProfile(TEST_VPN_PKG);

        // Verify that the ACTIVATE_VPN appop was checked, but no error was thrown.
        verify(mAppOps).noteOpNoThrow(AppOpsManager.OPSTR_ACTIVATE_VPN, Process.myUid(),
                TEST_VPN_PKG, null /* attributionTag */, null /* message */);
    }

    @Test
    public void testStartVpnProfileNotConsented() throws Exception {
        final Vpn vpn = createVpn();

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
        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

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
        final Vpn vpn = createVpn(RESTRICTED_PROFILE_A, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            vpn.startVpnProfile(TEST_VPN_PKG);
            fail("Expected SecurityException due to restricted user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testStopVpnProfileRestrictedUser() throws Exception {
        final Vpn vpn = createVpn(RESTRICTED_PROFILE_A, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);

        try {
            vpn.stopVpnProfile(TEST_VPN_PKG);
            fail("Expected SecurityException due to restricted user");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testStartOpAndFinishOpWillBeCalledWhenPlatformVpnIsOnAndOff() throws Exception {
        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());
        vpn.startVpnProfile(TEST_VPN_PKG);
        verifyPlatformVpnIsActivated(TEST_VPN_PKG);
        // Add a small delay to make sure that startOp is only called once.
        verify(mAppOps, after(100).times(1)).startOp(
                eq(AppOpsManager.OPSTR_ESTABLISH_VPN_MANAGER),
                eq(UserHandle.getUid(PRIMARY_USER.id, Process.myUid())),
                eq(TEST_VPN_PKG),
                eq(null) /* attributionTag */,
                eq(null) /* message */);
        // Check that the startOp is not called with OPSTR_ESTABLISH_VPN_SERVICE.
        verify(mAppOps, never()).startOp(
                eq(AppOpsManager.OPSTR_ESTABLISH_VPN_SERVICE),
                eq(UserHandle.getUid(PRIMARY_USER.id, Process.myUid())),
                eq(TEST_VPN_PKG),
                eq(null) /* attributionTag */,
                eq(null) /* message */);
        vpn.stopVpnProfile(TEST_VPN_PKG);
        verifyPlatformVpnIsDeactivated(TEST_VPN_PKG);
    }

    @Test
    public void testStartOpWithSeamlessHandover() throws Exception {
        // Create with SYSTEM_USER so that establish() will match the user ID when checking
        // against Binder.getCallerUid
        final Vpn vpn = createVpn(SYSTEM_USER, AppOpsManager.OPSTR_ACTIVATE_VPN);
        assertTrue(vpn.prepare(TEST_VPN_PKG, null, VpnManager.TYPE_VPN_SERVICE));
        final VpnConfig config = new VpnConfig();
        config.user = "VpnTest";
        config.addresses.add(new LinkAddress("192.0.2.2/32"));
        config.mtu = 1450;
        final ResolveInfo resolveInfo = new ResolveInfo();
        final ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.permission = BIND_VPN_SERVICE;
        resolveInfo.serviceInfo = serviceInfo;
        when(mPackageManager.resolveService(any(), anyInt())).thenReturn(resolveInfo);
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true);
        vpn.establish(config);
        verify(mAppOps, times(1)).startOp(
                eq(AppOpsManager.OPSTR_ESTABLISH_VPN_SERVICE),
                eq(Process.myUid()),
                eq(TEST_VPN_PKG),
                eq(null) /* attributionTag */,
                eq(null) /* message */);
        // Call establish() twice with the same config, it should match seamless handover case and
        // startOp() shouldn't be called again.
        vpn.establish(config);
        verify(mAppOps, times(1)).startOp(
                eq(AppOpsManager.OPSTR_ESTABLISH_VPN_SERVICE),
                eq(Process.myUid()),
                eq(TEST_VPN_PKG),
                eq(null) /* attributionTag */,
                eq(null) /* message */);
    }

    private void verifyVpnManagerEvent(String sessionKey, String category, int errorClass,
            int errorCode, String[] packageName, @NonNull VpnProfileState... profileState) {
        final Context userContext =
                mContext.createContextAsUser(UserHandle.of(PRIMARY_USER.id), 0 /* flags */);
        final ArgumentCaptor<Intent> intentArgumentCaptor = ArgumentCaptor.forClass(Intent.class);

        final int verifyTimes = profileState.length;
        verify(userContext, timeout(TEST_TIMEOUT_MS).times(verifyTimes))
                .startService(intentArgumentCaptor.capture());

        for (int i = 0; i < verifyTimes; i++) {
            final Intent intent = intentArgumentCaptor.getAllValues().get(i);
            assertEquals(packageName[i], intent.getPackage());
            assertEquals(sessionKey, intent.getStringExtra(VpnManager.EXTRA_SESSION_KEY));
            final Set<String> categories = intent.getCategories();
            assertTrue(categories.contains(category));
            assertEquals(1, categories.size());
            assertEquals(errorClass,
                    intent.getIntExtra(VpnManager.EXTRA_ERROR_CLASS, -1 /* defaultValue */));
            assertEquals(errorCode,
                    intent.getIntExtra(VpnManager.EXTRA_ERROR_CODE, -1 /* defaultValue */));
            // CATEGORY_EVENT_DEACTIVATED_BY_USER & CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED won't
            // send NetworkCapabilities & LinkProperties to VPN app.
            // For ERROR_CODE_NETWORK_LOST, the NetworkCapabilities & LinkProperties of underlying
            // network will be cleared. So the VPN app will receive null for those 2 extra values.
            if (category.equals(VpnManager.CATEGORY_EVENT_DEACTIVATED_BY_USER)
                    || category.equals(VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED)
                    || errorCode == VpnManager.ERROR_CODE_NETWORK_LOST) {
                assertNull(intent.getParcelableExtra(
                        VpnManager.EXTRA_UNDERLYING_NETWORK_CAPABILITIES));
                assertNull(intent.getParcelableExtra(VpnManager.EXTRA_UNDERLYING_LINK_PROPERTIES));
            } else {
                assertNotNull(intent.getParcelableExtra(
                        VpnManager.EXTRA_UNDERLYING_NETWORK_CAPABILITIES));
                assertNotNull(intent.getParcelableExtra(
                        VpnManager.EXTRA_UNDERLYING_LINK_PROPERTIES));
            }

            assertEquals(profileState[i], intent.getParcelableExtra(
                    VpnManager.EXTRA_VPN_PROFILE_STATE, VpnProfileState.class));
        }
        reset(userContext);
    }

    private void verifyDeactivatedByUser(String sessionKey, String[] packageName) {
        // CATEGORY_EVENT_DEACTIVATED_BY_USER is not an error event, so both of errorClass and
        // errorCode won't be set.
        verifyVpnManagerEvent(sessionKey, VpnManager.CATEGORY_EVENT_DEACTIVATED_BY_USER,
                -1 /* errorClass */, -1 /* errorCode */, packageName,
                // VPN NetworkAgnet does not switch to CONNECTED in the test, and the state is not
                // important here. Verify that the state as it is, i.e. CONNECTING state.
                new VpnProfileState(VpnProfileState.STATE_CONNECTING,
                        sessionKey, false /* alwaysOn */, false /* lockdown */));
    }

    private void verifyAlwaysOnStateChanged(String[] packageName, VpnProfileState... profileState) {
        verifyVpnManagerEvent(null /* sessionKey */,
                VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED, -1 /* errorClass */,
                -1 /* errorCode */, packageName, profileState);
    }

    @Test
    public void testVpnManagerEventForUserDeactivated() throws Exception {
        // For security reasons, Vpn#prepare() will check that oldPackage and newPackage are either
        // null or the package of the caller. This test will call Vpn#prepare() to pretend the old
        // VPN is replaced by a new one. But only Settings can change to some other packages, and
        // this is checked with CONTROL_VPN so simulate holding CONTROL_VPN in order to pass the
        // security checks.
        doReturn(PERMISSION_GRANTED).when(mContext).checkCallingOrSelfPermission(CONTROL_VPN);
        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());

        // Test the case that the user deactivates the vpn in vpn app.
        final String sessionKey1 = vpn.startVpnProfile(TEST_VPN_PKG);
        verifyPlatformVpnIsActivated(TEST_VPN_PKG);
        vpn.stopVpnProfile(TEST_VPN_PKG);
        verifyPlatformVpnIsDeactivated(TEST_VPN_PKG);
        verifyPowerSaveTempWhitelistApp(TEST_VPN_PKG);
        reset(mDeviceIdleInternal);
        verifyDeactivatedByUser(sessionKey1, new String[] {TEST_VPN_PKG});
        reset(mAppOps);

        // Test the case that the user chooses another vpn and the original one is replaced.
        final String sessionKey2 = vpn.startVpnProfile(TEST_VPN_PKG);
        verifyPlatformVpnIsActivated(TEST_VPN_PKG);
        vpn.prepare(TEST_VPN_PKG, "com.new.vpn" /* newPackage */, TYPE_VPN_PLATFORM);
        verifyPlatformVpnIsDeactivated(TEST_VPN_PKG);
        verifyPowerSaveTempWhitelistApp(TEST_VPN_PKG);
        reset(mDeviceIdleInternal);
        verifyDeactivatedByUser(sessionKey2, new String[] {TEST_VPN_PKG});
    }

    @Test
    public void testVpnManagerEventForAlwaysOnChanged() throws Exception {
        // Calling setAlwaysOnPackage() needs to hold CONTROL_VPN.
        doReturn(PERMISSION_GRANTED).when(mContext).checkCallingOrSelfPermission(CONTROL_VPN);
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        // Enable VPN always-on for PKGS[1].
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], false /* lockdown */,
                null /* lockdownAllowlist */));
        verifyPowerSaveTempWhitelistApp(PKGS[1]);
        reset(mDeviceIdleInternal);
        verifyAlwaysOnStateChanged(new String[] {PKGS[1]},
                new VpnProfileState(VpnProfileState.STATE_DISCONNECTED,
                        null /* sessionKey */, true /* alwaysOn */, false /* lockdown */));

        // Enable VPN lockdown for PKGS[1].
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], true /* lockdown */,
                null /* lockdownAllowlist */));
        verifyPowerSaveTempWhitelistApp(PKGS[1]);
        reset(mDeviceIdleInternal);
        verifyAlwaysOnStateChanged(new String[] {PKGS[1]},
                new VpnProfileState(VpnProfileState.STATE_DISCONNECTED,
                        null /* sessionKey */, true /* alwaysOn */, true /* lockdown */));

        // Disable VPN lockdown for PKGS[1].
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], false /* lockdown */,
                null /* lockdownAllowlist */));
        verifyPowerSaveTempWhitelistApp(PKGS[1]);
        reset(mDeviceIdleInternal);
        verifyAlwaysOnStateChanged(new String[] {PKGS[1]},
                new VpnProfileState(VpnProfileState.STATE_DISCONNECTED,
                        null /* sessionKey */, true /* alwaysOn */, false /* lockdown */));

        // Disable VPN always-on.
        assertTrue(vpn.setAlwaysOnPackage(null, false /* lockdown */,
                null /* lockdownAllowlist */));
        verifyPowerSaveTempWhitelistApp(PKGS[1]);
        reset(mDeviceIdleInternal);
        verifyAlwaysOnStateChanged(new String[] {PKGS[1]},
                new VpnProfileState(VpnProfileState.STATE_DISCONNECTED,
                        null /* sessionKey */, false /* alwaysOn */, false /* lockdown */));

        // Enable VPN always-on for PKGS[1] again.
        assertTrue(vpn.setAlwaysOnPackage(PKGS[1], false /* lockdown */,
                null /* lockdownAllowlist */));
        verifyPowerSaveTempWhitelistApp(PKGS[1]);
        reset(mDeviceIdleInternal);
        verifyAlwaysOnStateChanged(new String[] {PKGS[1]},
                new VpnProfileState(VpnProfileState.STATE_DISCONNECTED,
                        null /* sessionKey */, true /* alwaysOn */, false /* lockdown */));

        // Enable VPN always-on for PKGS[2].
        assertTrue(vpn.setAlwaysOnPackage(PKGS[2], false /* lockdown */,
                null /* lockdownAllowlist */));
        verifyPowerSaveTempWhitelistApp(PKGS[2]);
        reset(mDeviceIdleInternal);
        // PKGS[1] is replaced with PKGS[2].
        // Pass 2 VpnProfileState objects to verifyVpnManagerEvent(), the first one is sent to
        // PKGS[1] to notify PKGS[1] that the VPN always-on is disabled, the second one is sent to
        // PKGS[2] to notify PKGS[2] that the VPN always-on is enabled.
        verifyAlwaysOnStateChanged(new String[] {PKGS[1], PKGS[2]},
                new VpnProfileState(VpnProfileState.STATE_DISCONNECTED,
                        null /* sessionKey */, false /* alwaysOn */, false /* lockdown */),
                new VpnProfileState(VpnProfileState.STATE_DISCONNECTED,
                        null /* sessionKey */, true /* alwaysOn */, false /* lockdown */));
    }

    @Test
    public void testReconnectVpnManagerVpnWithAlwaysOnEnabled() throws Exception {
        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());
        vpn.startVpnProfile(TEST_VPN_PKG);
        verifyPlatformVpnIsActivated(TEST_VPN_PKG);

        // Enable VPN always-on for TEST_VPN_PKG.
        assertTrue(vpn.setAlwaysOnPackage(TEST_VPN_PKG, false /* lockdown */,
                null /* lockdownAllowlist */));

        // Reset to verify next startVpnProfile.
        reset(mAppOps);

        vpn.stopVpnProfile(TEST_VPN_PKG);

        // Reconnect the vpn with different package will cause exception.
        assertThrows(SecurityException.class, () -> vpn.startVpnProfile(PKGS[0]));

        // Reconnect the vpn again with the vpn always on package w/o exception.
        vpn.startVpnProfile(TEST_VPN_PKG);
        verifyPlatformVpnIsActivated(TEST_VPN_PKG);
    }

    @Test
    public void testLockdown_enableDisableWhileConnected() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */));

        final InOrder order = inOrder(mTestDeps);
        order.verify(mTestDeps, timeout(TIMEOUT_CROSSTHREAD_MS))
                .newNetworkAgent(any(), any(), any(), any(), any(), any(),
                        argThat(config -> config.allowBypass), any(), any());

        // Make VPN lockdown.
        assertTrue(vpnSnapShot.vpn.setAlwaysOnPackage(TEST_VPN_PKG, true /* lockdown */,
                null /* lockdownAllowlist */));

        order.verify(mTestDeps, timeout(TIMEOUT_CROSSTHREAD_MS))
                .newNetworkAgent(any(), any(), any(), any(), any(), any(),
                argThat(config -> !config.allowBypass), any(), any());

        // Disable lockdown.
        assertTrue(vpnSnapShot.vpn.setAlwaysOnPackage(TEST_VPN_PKG, false /* lockdown */,
                null /* lockdownAllowlist */));

        order.verify(mTestDeps, timeout(TIMEOUT_CROSSTHREAD_MS))
                .newNetworkAgent(any(), any(), any(), any(), any(), any(),
                        argThat(config -> config.allowBypass), any(), any());
    }

    @Test
    public void testSetPackageAuthorizationVpnService() throws Exception {
        final Vpn vpn = createVpn();

        assertTrue(vpn.setPackageAuthorization(TEST_VPN_PKG, VpnManager.TYPE_VPN_SERVICE));
        verify(mAppOps)
                .setMode(
                        eq(AppOpsManager.OPSTR_ACTIVATE_VPN),
                        eq(UserHandle.getUid(PRIMARY_USER.id, Process.myUid())),
                        eq(TEST_VPN_PKG),
                        eq(AppOpsManager.MODE_ALLOWED));
    }

    @Test
    public void testSetPackageAuthorizationPlatformVpn() throws Exception {
        final Vpn vpn = createVpn();

        assertTrue(vpn.setPackageAuthorization(TEST_VPN_PKG, TYPE_VPN_PLATFORM));
        verify(mAppOps)
                .setMode(
                        eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN),
                        eq(UserHandle.getUid(PRIMARY_USER.id, Process.myUid())),
                        eq(TEST_VPN_PKG),
                        eq(AppOpsManager.MODE_ALLOWED));
    }

    @Test
    public void testSetPackageAuthorizationRevokeAuthorization() throws Exception {
        final Vpn vpn = createVpn();

        assertTrue(vpn.setPackageAuthorization(TEST_VPN_PKG, VpnManager.TYPE_VPN_NONE));
        verify(mAppOps)
                .setMode(
                        eq(AppOpsManager.OPSTR_ACTIVATE_VPN),
                        eq(UserHandle.getUid(PRIMARY_USER.id, Process.myUid())),
                        eq(TEST_VPN_PKG),
                        eq(AppOpsManager.MODE_IGNORED));
        verify(mAppOps)
                .setMode(
                        eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN),
                        eq(UserHandle.getUid(PRIMARY_USER.id, Process.myUid())),
                        eq(TEST_VPN_PKG),
                        eq(AppOpsManager.MODE_IGNORED));
    }

    private NetworkCallback triggerOnAvailableAndGetCallback() throws Exception {
        return triggerOnAvailableAndGetCallback(new NetworkCapabilities.Builder().build());
    }

    private NetworkCallback triggerOnAvailableAndGetCallback(
            @NonNull final NetworkCapabilities caps) throws Exception {
        final ArgumentCaptor<NetworkCallback> networkCallbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        verify(mConnectivityManager, timeout(TEST_TIMEOUT_MS))
                .registerSystemDefaultNetworkCallback(networkCallbackCaptor.capture(), any());

        // onAvailable() will trigger onDefaultNetworkChanged(), so NetdUtils#setInterfaceUp will be
        // invoked. Set the return value of INetd#interfaceGetCfg to prevent NullPointerException.
        final InterfaceConfigurationParcel config = new InterfaceConfigurationParcel();
        config.flags = new String[] {IF_STATE_DOWN};
        when(mNetd.interfaceGetCfg(anyString())).thenReturn(config);
        final NetworkCallback cb = networkCallbackCaptor.getValue();
        cb.onAvailable(TEST_NETWORK);
        // Trigger onCapabilitiesChanged() and onLinkPropertiesChanged() so the test can verify that
        // if NetworkCapabilities and LinkProperties of underlying network will be sent/cleared or
        // not.
        // See verifyVpnManagerEvent().
        cb.onCapabilitiesChanged(TEST_NETWORK, caps);
        cb.onLinkPropertiesChanged(TEST_NETWORK, new LinkProperties());
        return cb;
    }

    private void verifyInterfaceSetCfgWithFlags(String flag) throws Exception {
        // Add a timeout for waiting for interfaceSetCfg to be called.
        verify(mNetd, timeout(TEST_TIMEOUT_MS)).interfaceSetCfg(argThat(
                config -> Arrays.asList(config.flags).contains(flag)));
    }

    private void doTestPlatformVpnWithException(IkeException exception,
            String category, int errorType, int errorCode) throws Exception {
        final ArgumentCaptor<IkeSessionCallback> captor =
                ArgumentCaptor.forClass(IkeSessionCallback.class);

        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(mVpnProfile.encode());

        doReturn(new NetworkCapabilities()).when(mConnectivityManager)
                .getRedactedNetworkCapabilitiesForPackage(any(), anyInt(), anyString());
        doReturn(new LinkProperties()).when(mConnectivityManager)
                .getRedactedLinkPropertiesForPackage(any(), anyInt(), anyString());

        final String sessionKey = vpn.startVpnProfile(TEST_VPN_PKG);
        final Set<Range<Integer>> uidRanges = rangeSet(PRIMARY_USER_RANGE);
        // This is triggered by Ikev2VpnRunner constructor.
        verify(mConnectivityManager, times(1)).setVpnDefaultForUids(eq(sessionKey), eq(uidRanges));
        final NetworkCallback cb = triggerOnAvailableAndGetCallback();

        verifyInterfaceSetCfgWithFlags(IF_STATE_UP);

        // Wait for createIkeSession() to be called before proceeding in order to ensure consistent
        // state
        verify(mIkev2SessionCreator, timeout(TEST_TIMEOUT_MS))
                .createIkeSession(any(), any(), any(), any(), captor.capture(), any());
        // This is triggered by Vpn#startOrMigrateIkeSession().
        verify(mConnectivityManager, times(2)).setVpnDefaultForUids(eq(sessionKey), eq(uidRanges));
        reset(mIkev2SessionCreator);
        // For network lost case, the process should be triggered by calling onLost(), which is the
        // same process with the real case.
        if (errorCode == VpnManager.ERROR_CODE_NETWORK_LOST) {
            cb.onLost(TEST_NETWORK);
            verify(mExecutor, atLeastOnce()).schedule(any(Runnable.class), anyLong(), any());
        } else {
            final IkeSessionCallback ikeCb = captor.getValue();
            mExecutor.execute(() -> ikeCb.onClosedWithException(exception));
        }

        verifyPowerSaveTempWhitelistApp(TEST_VPN_PKG);
        reset(mDeviceIdleInternal);
        verifyVpnManagerEvent(sessionKey, category, errorType, errorCode,
                // VPN NetworkAgnet does not switch to CONNECTED in the test, and the state is not
                // important here. Verify that the state as it is, i.e. CONNECTING state.
                new String[] {TEST_VPN_PKG}, new VpnProfileState(VpnProfileState.STATE_CONNECTING,
                        sessionKey, false /* alwaysOn */, false /* lockdown */));
        if (errorType == VpnManager.ERROR_CLASS_NOT_RECOVERABLE) {
            verify(mConnectivityManager).setVpnDefaultForUids(eq(sessionKey),
                    eq(Collections.EMPTY_LIST));
            verify(mConnectivityManager, timeout(TEST_TIMEOUT_MS))
                    .unregisterNetworkCallback(eq(cb));
        } else if (errorType == VpnManager.ERROR_CLASS_RECOVERABLE
                // Vpn won't retry when there is no usable underlying network.
                && errorCode != VpnManager.ERROR_CODE_NETWORK_LOST) {
            int retryIndex = 0;
            // First failure occurred above.
            final IkeSessionCallback retryCb = verifyRetryAndGetNewIkeCb(retryIndex++);
            // Trigger 2 more failures to let the retry delay increase to 5s.
            mExecutor.execute(() -> retryCb.onClosedWithException(exception));
            final IkeSessionCallback retryCb2 = verifyRetryAndGetNewIkeCb(retryIndex++);
            mExecutor.execute(() -> retryCb2.onClosedWithException(exception));
            final IkeSessionCallback retryCb3 = verifyRetryAndGetNewIkeCb(retryIndex++);

            // setVpnDefaultForUids may be called again but the uidRanges should not change.
            verify(mConnectivityManager, atLeast(2)).setVpnDefaultForUids(eq(sessionKey),
                    mUidRangesCaptor.capture());
            final List<Collection<Range<Integer>>> capturedUidRanges =
                    mUidRangesCaptor.getAllValues();
            for (int i = 2; i < capturedUidRanges.size(); i++) {
                // Assert equals no order.
                assertTrue(
                        "uid ranges should not be modified. Expected: " + uidRanges
                                + ", actual: " + capturedUidRanges.get(i),
                        capturedUidRanges.get(i).containsAll(uidRanges)
                                && capturedUidRanges.get(i).size() == uidRanges.size());
            }

            // A fourth failure will cause the retry delay to be greater than 5s.
            mExecutor.execute(() -> retryCb3.onClosedWithException(exception));
            verifyRetryAndGetNewIkeCb(retryIndex++);

            // The VPN network preference will be cleared when the retry delay is greater than 5s.
            verify(mConnectivityManager).setVpnDefaultForUids(eq(sessionKey),
                    eq(Collections.EMPTY_LIST));
        }
    }

    private IkeSessionCallback verifyRetryAndGetNewIkeCb(int retryIndex) {
        final ArgumentCaptor<IkeSessionCallback> ikeCbCaptor =
                ArgumentCaptor.forClass(IkeSessionCallback.class);

        // Verify retry is scheduled
        final long expectedDelayMs = mTestDeps.getNextRetryDelayMs(retryIndex);
        verify(mExecutor, timeout(TEST_TIMEOUT_MS)).schedule(any(Runnable.class),
                eq(expectedDelayMs), eq(TimeUnit.MILLISECONDS));

        verify(mIkev2SessionCreator, timeout(TEST_TIMEOUT_MS + expectedDelayMs))
                .createIkeSession(any(), any(), any(), any(), ikeCbCaptor.capture(), any());

        // Forget the mIkev2SessionCreator#createIkeSession call and mExecutor#schedule call
        // for the next retry verification
        resetIkev2SessionCreator(mIkeSessionWrapper);

        return ikeCbCaptor.getValue();
    }

    @Test
    public void testStartPlatformVpnAuthenticationFailed() throws Exception {
        final IkeProtocolException exception = mock(IkeProtocolException.class);
        final int errorCode = IkeProtocolException.ERROR_TYPE_AUTHENTICATION_FAILED;
        when(exception.getErrorType()).thenReturn(errorCode);
        doTestPlatformVpnWithException(exception,
                VpnManager.CATEGORY_EVENT_IKE_ERROR, VpnManager.ERROR_CLASS_NOT_RECOVERABLE,
                errorCode);
    }

    @Test
    public void testStartPlatformVpnFailedWithRecoverableError() throws Exception {
        final IkeProtocolException exception = mock(IkeProtocolException.class);
        final int errorCode = IkeProtocolException.ERROR_TYPE_TEMPORARY_FAILURE;
        when(exception.getErrorType()).thenReturn(errorCode);
        doTestPlatformVpnWithException(exception,
                VpnManager.CATEGORY_EVENT_IKE_ERROR, VpnManager.ERROR_CLASS_RECOVERABLE, errorCode);
    }

    @Test
    public void testStartPlatformVpnFailedWithUnknownHostException() throws Exception {
        final IkeNonProtocolException exception = mock(IkeNonProtocolException.class);
        final UnknownHostException unknownHostException = new UnknownHostException();
        final int errorCode = VpnManager.ERROR_CODE_NETWORK_UNKNOWN_HOST;
        when(exception.getCause()).thenReturn(unknownHostException);
        doTestPlatformVpnWithException(exception,
                VpnManager.CATEGORY_EVENT_NETWORK_ERROR, VpnManager.ERROR_CLASS_RECOVERABLE,
                errorCode);
    }

    @Test
    public void testStartPlatformVpnFailedWithIkeTimeoutException() throws Exception {
        final IkeNonProtocolException exception = mock(IkeNonProtocolException.class);
        final IkeTimeoutException ikeTimeoutException =
                new IkeTimeoutException("IkeTimeoutException");
        final int errorCode = VpnManager.ERROR_CODE_NETWORK_PROTOCOL_TIMEOUT;
        when(exception.getCause()).thenReturn(ikeTimeoutException);
        doTestPlatformVpnWithException(exception,
                VpnManager.CATEGORY_EVENT_NETWORK_ERROR, VpnManager.ERROR_CLASS_RECOVERABLE,
                errorCode);
    }

    @Test
    public void testStartPlatformVpnFailedWithIkeNetworkLostException() throws Exception {
        final IkeNetworkLostException exception = new IkeNetworkLostException(
                new Network(100));
        doTestPlatformVpnWithException(exception,
                VpnManager.CATEGORY_EVENT_NETWORK_ERROR, VpnManager.ERROR_CLASS_RECOVERABLE,
                VpnManager.ERROR_CODE_NETWORK_LOST);
    }

    @Test
    public void testStartPlatformVpnFailedWithIOException() throws Exception {
        final IkeNonProtocolException exception = mock(IkeNonProtocolException.class);
        final IOException ioException = new IOException();
        final int errorCode = VpnManager.ERROR_CODE_NETWORK_IO;
        when(exception.getCause()).thenReturn(ioException);
        doTestPlatformVpnWithException(exception,
                VpnManager.CATEGORY_EVENT_NETWORK_ERROR, VpnManager.ERROR_CLASS_RECOVERABLE,
                errorCode);
    }

    @Test
    public void testStartPlatformVpnIllegalArgumentExceptionInSetup() throws Exception {
        when(mIkev2SessionCreator.createIkeSession(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException());
        final Vpn vpn = startLegacyVpn(createVpn(PRIMARY_USER.id), mVpnProfile);
        final NetworkCallback cb = triggerOnAvailableAndGetCallback();

        verifyInterfaceSetCfgWithFlags(IF_STATE_UP);

        // Wait for createIkeSession() to be called before proceeding in order to ensure consistent
        // state
        verify(mConnectivityManager, timeout(TEST_TIMEOUT_MS)).unregisterNetworkCallback(eq(cb));
        assertEquals(LegacyVpnInfo.STATE_FAILED, vpn.getLegacyVpnInfo().state);
    }

    @Test
    public void testVpnManagerEventWillNotBeSentToSettingsVpn() throws Exception {
        startLegacyVpn(createVpn(PRIMARY_USER.id), mVpnProfile);
        triggerOnAvailableAndGetCallback();

        verifyInterfaceSetCfgWithFlags(IF_STATE_UP);

        final IkeNonProtocolException exception = mock(IkeNonProtocolException.class);
        final IkeTimeoutException ikeTimeoutException =
                new IkeTimeoutException("IkeTimeoutException");
        when(exception.getCause()).thenReturn(ikeTimeoutException);

        final ArgumentCaptor<IkeSessionCallback> captor =
                ArgumentCaptor.forClass(IkeSessionCallback.class);
        verify(mIkev2SessionCreator, timeout(TEST_TIMEOUT_MS))
                .createIkeSession(any(), any(), any(), any(), captor.capture(), any());
        final IkeSessionCallback ikeCb = captor.getValue();
        ikeCb.onClosedWithException(exception);

        final Context userContext =
                mContext.createContextAsUser(UserHandle.of(PRIMARY_USER.id), 0 /* flags */);
        verify(userContext, never()).startService(any());
    }

    private void setAndVerifyAlwaysOnPackage(Vpn vpn, int uid, boolean lockdownEnabled) {
        assertTrue(vpn.setAlwaysOnPackage(TEST_VPN_PKG, lockdownEnabled, null));

        verify(mVpnProfileStore).get(eq(vpn.getProfileNameForPackage(TEST_VPN_PKG)));
        verify(mAppOps).setMode(
                eq(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN), eq(uid), eq(TEST_VPN_PKG),
                eq(AppOpsManager.MODE_ALLOWED));

        verify(mSystemServices).settingsSecurePutStringForUser(
                eq(Settings.Secure.ALWAYS_ON_VPN_APP), eq(TEST_VPN_PKG), eq(PRIMARY_USER.id));
        verify(mSystemServices).settingsSecurePutIntForUser(
                eq(Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN), eq(lockdownEnabled ? 1 : 0),
                eq(PRIMARY_USER.id));
        verify(mSystemServices).settingsSecurePutStringForUser(
                eq(Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN_WHITELIST), eq(""), eq(PRIMARY_USER.id));
    }

    @Test
    public void testSetAndStartAlwaysOnVpn() throws Exception {
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        setMockedUsers(PRIMARY_USER);

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
        setMockedUsers(PRIMARY_USER);
        vpn.startLegacyVpn(vpnProfile);
        return vpn;
    }

    private IkeSessionConnectionInfo createIkeConnectInfo() {
        return new IkeSessionConnectionInfo(TEST_VPN_CLIENT_IP, TEST_VPN_SERVER_IP, TEST_NETWORK);
    }

    private IkeSessionConnectionInfo createIkeConnectInfo_2() {
        return new IkeSessionConnectionInfo(
                TEST_VPN_CLIENT_IP_2, TEST_VPN_SERVER_IP_2, TEST_NETWORK_2);
    }

    private IkeSessionConfiguration createIkeConfig(
            IkeSessionConnectionInfo ikeConnectInfo, boolean isMobikeEnabled) {
        final IkeSessionConfiguration.Builder builder =
                new IkeSessionConfiguration.Builder(ikeConnectInfo);

        if (isMobikeEnabled) {
            builder.addIkeExtension(EXTENSION_TYPE_MOBIKE);
        }

        return builder.build();
    }

    private ChildSessionConfiguration createChildConfig() {
        return new ChildSessionConfiguration.Builder(
                        Arrays.asList(IN_TS, IN_TS6), Arrays.asList(OUT_TS, OUT_TS6))
                .addInternalAddress(new LinkAddress(TEST_VPN_INTERNAL_IP, IP4_PREFIX_LEN))
                .addInternalAddress(new LinkAddress(TEST_VPN_INTERNAL_IP6, IP6_PREFIX_LEN))
                .addInternalDnsServer(TEST_VPN_INTERNAL_DNS)
                .addInternalDnsServer(TEST_VPN_INTERNAL_DNS6)
                .build();
    }

    private IpSecTransform createIpSecTransform() {
        return new IpSecTransform(mContext, new IpSecConfig());
    }

    private void verifyApplyTunnelModeTransforms(int expectedTimes) throws Exception {
        verify(mIpSecService, times(expectedTimes)).applyTunnelModeTransform(
                eq(TEST_TUNNEL_RESOURCE_ID), eq(IpSecManager.DIRECTION_IN),
                anyInt(), anyString());
        verify(mIpSecService, times(expectedTimes)).applyTunnelModeTransform(
                eq(TEST_TUNNEL_RESOURCE_ID), eq(IpSecManager.DIRECTION_OUT),
                anyInt(), anyString());
    }

    private Pair<IkeSessionCallback, ChildSessionCallback> verifyCreateIkeAndCaptureCbs()
            throws Exception {
        final ArgumentCaptor<IkeSessionCallback> ikeCbCaptor =
                ArgumentCaptor.forClass(IkeSessionCallback.class);
        final ArgumentCaptor<ChildSessionCallback> childCbCaptor =
                ArgumentCaptor.forClass(ChildSessionCallback.class);

        verify(mIkev2SessionCreator, timeout(TEST_TIMEOUT_MS)).createIkeSession(
                any(), any(), any(), any(), ikeCbCaptor.capture(), childCbCaptor.capture());

        return new Pair<>(ikeCbCaptor.getValue(), childCbCaptor.getValue());
    }

    private static class PlatformVpnSnapshot {
        public final Vpn vpn;
        public final NetworkCallback nwCb;
        public final IkeSessionCallback ikeCb;
        public final ChildSessionCallback childCb;

        PlatformVpnSnapshot(Vpn vpn, NetworkCallback nwCb,
                IkeSessionCallback ikeCb, ChildSessionCallback childCb) {
            this.vpn = vpn;
            this.nwCb = nwCb;
            this.ikeCb = ikeCb;
            this.childCb = childCb;
        }
    }

    private PlatformVpnSnapshot verifySetupPlatformVpn(IkeSessionConfiguration ikeConfig)
            throws Exception {
        return verifySetupPlatformVpn(ikeConfig, true);
    }

    private PlatformVpnSnapshot verifySetupPlatformVpn(
            IkeSessionConfiguration ikeConfig, boolean mtuSupportsIpv6) throws Exception {
        return verifySetupPlatformVpn(mVpnProfile, ikeConfig, mtuSupportsIpv6);
    }

    private PlatformVpnSnapshot verifySetupPlatformVpn(VpnProfile vpnProfile,
            IkeSessionConfiguration ikeConfig, boolean mtuSupportsIpv6) throws Exception {
        return verifySetupPlatformVpn(vpnProfile, ikeConfig,
                new NetworkCapabilities.Builder().build() /* underlying network caps */,
                mtuSupportsIpv6, false /* areLongLivedTcpConnectionsExpensive */);
    }

    private PlatformVpnSnapshot verifySetupPlatformVpn(VpnProfile vpnProfile,
            IkeSessionConfiguration ikeConfig,
            @NonNull final NetworkCapabilities underlyingNetworkCaps,
            boolean mtuSupportsIpv6,
            boolean areLongLivedTcpConnectionsExpensive) throws Exception {
        if (!mtuSupportsIpv6) {
            doReturn(IPV6_MIN_MTU - 1).when(mTestDeps).calculateVpnMtu(any(), anyInt(), anyInt(),
                    anyBoolean());
        }

        doReturn(mMockNetworkAgent).when(mTestDeps)
                .newNetworkAgent(
                        any(), any(), anyString(), any(), any(), any(), any(), any(), any());
        doReturn(TEST_NETWORK).when(mMockNetworkAgent).getNetwork();

        final Vpn vpn = createVpn(AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN);
        when(mVpnProfileStore.get(vpn.getProfileNameForPackage(TEST_VPN_PKG)))
                .thenReturn(vpnProfile.encode());

        final String sessionKey = vpn.startVpnProfile(TEST_VPN_PKG);
        final Set<Range<Integer>> uidRanges = Collections.singleton(PRIMARY_USER_RANGE);
        verify(mConnectivityManager).setVpnDefaultForUids(eq(sessionKey), eq(uidRanges));
        final NetworkCallback nwCb = triggerOnAvailableAndGetCallback(underlyingNetworkCaps);
        // There are 4 interactions with the executor.
        // - Network available
        // - LP change
        // - NC change
        // - schedule() calls in scheduleStartIkeSession()
        // The first 3 calls are triggered from Executor.execute(). The execute() will also call to
        // schedule() with 0 delay. Verify the exact interaction here so that it won't cause flakes
        // in the follow-up flow.
        verify(mExecutor, timeout(TEST_TIMEOUT_MS).times(4))
                .schedule(any(Runnable.class), anyLong(), any());
        reset(mExecutor);

        // Mock the setup procedure by firing callbacks
        final Pair<IkeSessionCallback, ChildSessionCallback> cbPair =
                verifyCreateIkeAndCaptureCbs();
        final IkeSessionCallback ikeCb = cbPair.first;
        final ChildSessionCallback childCb = cbPair.second;

        ikeCb.onOpened(ikeConfig);
        childCb.onIpSecTransformCreated(createIpSecTransform(), IpSecManager.DIRECTION_IN);
        childCb.onIpSecTransformCreated(createIpSecTransform(), IpSecManager.DIRECTION_OUT);
        childCb.onOpened(createChildConfig());

        // Verification VPN setup
        verifyApplyTunnelModeTransforms(1);

        ArgumentCaptor<LinkProperties> lpCaptor = ArgumentCaptor.forClass(LinkProperties.class);
        ArgumentCaptor<NetworkCapabilities> ncCaptor =
                ArgumentCaptor.forClass(NetworkCapabilities.class);
        ArgumentCaptor<NetworkAgentConfig> nacCaptor =
                ArgumentCaptor.forClass(NetworkAgentConfig.class);
        verify(mTestDeps).newNetworkAgent(
                any(), any(), anyString(), ncCaptor.capture(), lpCaptor.capture(),
                any(), nacCaptor.capture(), any(), any());
        verify(mIkeSessionWrapper).setUnderpinnedNetwork(TEST_NETWORK);
        // Check LinkProperties
        final LinkProperties lp = lpCaptor.getValue();
        final List<RouteInfo> expectedRoutes =
                new ArrayList<>(
                        Arrays.asList(
                                new RouteInfo(
                                        new IpPrefix(Inet4Address.ANY, 0),
                                        null /* gateway */,
                                        TEST_IFACE_NAME,
                                        RouteInfo.RTN_UNICAST)));
        final List<LinkAddress> expectedAddresses =
                new ArrayList<>(
                        Arrays.asList(new LinkAddress(TEST_VPN_INTERNAL_IP, IP4_PREFIX_LEN)));
        final List<InetAddress> expectedDns = new ArrayList<>(Arrays.asList(TEST_VPN_INTERNAL_DNS));

        if (mtuSupportsIpv6) {
            expectedRoutes.add(
                    new RouteInfo(
                            new IpPrefix(Inet6Address.ANY, 0),
                            null /* gateway */,
                            TEST_IFACE_NAME,
                            RouteInfo.RTN_UNICAST));
            expectedAddresses.add(new LinkAddress(TEST_VPN_INTERNAL_IP6, IP6_PREFIX_LEN));
            expectedDns.add(TEST_VPN_INTERNAL_DNS6);
        } else {
            expectedRoutes.add(
                    new RouteInfo(
                            new IpPrefix(Inet6Address.ANY, 0),
                            null /* gateway */,
                            TEST_IFACE_NAME,
                            RTN_UNREACHABLE));
        }

        assertEquals(expectedRoutes, lp.getRoutes());
        assertEquals(expectedAddresses, lp.getLinkAddresses());
        assertEquals(expectedDns, lp.getDnsServers());

        // Check NetworkCapabilities
        assertEquals(Arrays.asList(TEST_NETWORK), ncCaptor.getValue().getUnderlyingNetworks());

        // Check if allowBypass is set or not.
        assertTrue(nacCaptor.getValue().isBypassableVpn());
        // Check if extra info for VPN is set.
        assertTrue(nacCaptor.getValue().getLegacyExtraInfo().contains(TEST_VPN_PKG));
        final VpnTransportInfo info = (VpnTransportInfo) ncCaptor.getValue().getTransportInfo();
        assertTrue(info.isBypassable());
        assertEquals(areLongLivedTcpConnectionsExpensive,
                info.areLongLivedTcpConnectionsExpensive());
        return new PlatformVpnSnapshot(vpn, nwCb, ikeCb, childCb);
    }

    @Test
    public void testStartPlatformVpn() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */));
        vpnSnapShot.vpn.mVpnRunner.exitVpnRunner();
        verify(mConnectivityManager).setVpnDefaultForUids(anyString(), eq(Collections.EMPTY_LIST));
    }

    @Test
    public void testMigrateIkeSession_FromIkeTunnConnParams_AutoTimerNoTimer() throws Exception {
        doTestMigrateIkeSession_FromIkeTunnConnParams(
                false /* isAutomaticIpVersionSelectionEnabled */,
                true /* isAutomaticNattKeepaliveTimerEnabled */,
                TEST_KEEPALIVE_TIMEOUT_UNSET /* keepaliveInProfile */,
                ESP_IP_VERSION_AUTO /* ipVersionInProfile */,
                ESP_ENCAP_TYPE_AUTO /* encapTypeInProfile */);
    }

    @Test
    public void testMigrateIkeSession_FromIkeTunnConnParams_AutoTimerTimerSet() throws Exception {
        doTestMigrateIkeSession_FromIkeTunnConnParams(
                false /* isAutomaticIpVersionSelectionEnabled */,
                true /* isAutomaticNattKeepaliveTimerEnabled */,
                TEST_KEEPALIVE_TIMER /* keepaliveInProfile */,
                ESP_IP_VERSION_AUTO /* ipVersionInProfile */,
                ESP_ENCAP_TYPE_AUTO /* encapTypeInProfile */);
    }

    @Test
    public void testMigrateIkeSession_FromIkeTunnConnParams_AutoIp() throws Exception {
        doTestMigrateIkeSession_FromIkeTunnConnParams(
                true /* isAutomaticIpVersionSelectionEnabled */,
                false /* isAutomaticNattKeepaliveTimerEnabled */,
                TEST_KEEPALIVE_TIMEOUT_UNSET /* keepaliveInProfile */,
                ESP_IP_VERSION_AUTO /* ipVersionInProfile */,
                ESP_ENCAP_TYPE_AUTO /* encapTypeInProfile */);
    }

    @Test
    public void testMigrateIkeSession_FromIkeTunnConnParams_AssignedIpProtocol() throws Exception {
        doTestMigrateIkeSession_FromIkeTunnConnParams(
                false /* isAutomaticIpVersionSelectionEnabled */,
                false /* isAutomaticNattKeepaliveTimerEnabled */,
                TEST_KEEPALIVE_TIMEOUT_UNSET /* keepaliveInProfile */,
                ESP_IP_VERSION_IPV4 /* ipVersionInProfile */,
                ESP_ENCAP_TYPE_UDP /* encapTypeInProfile */);
    }

    @Test
    public void testMigrateIkeSession_FromNotIkeTunnConnParams_AutoTimer() throws Exception {
        doTestMigrateIkeSession_FromNotIkeTunnConnParams(
                false /* isAutomaticIpVersionSelectionEnabled */,
                true /* isAutomaticNattKeepaliveTimerEnabled */);
    }

    @Test
    public void testMigrateIkeSession_FromNotIkeTunnConnParams_AutoIp() throws Exception {
        doTestMigrateIkeSession_FromNotIkeTunnConnParams(
                true /* isAutomaticIpVersionSelectionEnabled */,
                false /* isAutomaticNattKeepaliveTimerEnabled */);
    }

    private void doTestMigrateIkeSession_FromNotIkeTunnConnParams(
            boolean isAutomaticIpVersionSelectionEnabled,
            boolean isAutomaticNattKeepaliveTimerEnabled) throws Exception {
        final Ikev2VpnProfile ikeProfile =
                new Ikev2VpnProfile.Builder(TEST_VPN_SERVER, TEST_VPN_IDENTITY)
                        .setAuthPsk(TEST_VPN_PSK)
                        .setBypassable(true /* isBypassable */)
                        .setAutomaticNattKeepaliveTimerEnabled(isAutomaticNattKeepaliveTimerEnabled)
                        .setAutomaticIpVersionSelectionEnabled(isAutomaticIpVersionSelectionEnabled)
                        .build();

        final int expectedKeepalive = isAutomaticNattKeepaliveTimerEnabled
                ? AUTOMATIC_KEEPALIVE_DELAY_SECONDS
                : DEFAULT_UDP_PORT_4500_NAT_TIMEOUT_SEC_INT;
        doTestMigrateIkeSession(ikeProfile.toVpnProfile(),
                expectedKeepalive,
                ESP_IP_VERSION_AUTO /* expectedIpVersion */,
                ESP_ENCAP_TYPE_AUTO /* expectedEncapType */,
                new NetworkCapabilities.Builder().build());
    }

    private Ikev2VpnProfile makeIkeV2VpnProfile(
            boolean isAutomaticIpVersionSelectionEnabled,
            boolean isAutomaticNattKeepaliveTimerEnabled,
            int keepaliveInProfile,
            int ipVersionInProfile,
            int encapTypeInProfile) {
        // TODO: Update helper function in IkeSessionTestUtils to support building IkeSessionParams
        // with IP version and encap type when mainline-prod branch support these two APIs.
        final IkeSessionParams params = getTestIkeSessionParams(true /* testIpv6 */,
                new IkeFqdnIdentification(TEST_IDENTITY), keepaliveInProfile);
        final IkeSessionParams ikeSessionParams = new IkeSessionParams.Builder(params)
                .setIpVersion(ipVersionInProfile)
                .setEncapType(encapTypeInProfile)
                .build();

        final IkeTunnelConnectionParams tunnelParams =
                new IkeTunnelConnectionParams(ikeSessionParams, CHILD_PARAMS);
        return new Ikev2VpnProfile.Builder(tunnelParams)
                .setBypassable(true)
                .setAutomaticNattKeepaliveTimerEnabled(isAutomaticNattKeepaliveTimerEnabled)
                .setAutomaticIpVersionSelectionEnabled(isAutomaticIpVersionSelectionEnabled)
                .build();
    }

    private void doTestMigrateIkeSession_FromIkeTunnConnParams(
            boolean isAutomaticIpVersionSelectionEnabled,
            boolean isAutomaticNattKeepaliveTimerEnabled,
            int keepaliveInProfile,
            int ipVersionInProfile,
            int encapTypeInProfile) throws Exception {
        doTestMigrateIkeSession_FromIkeTunnConnParams(isAutomaticIpVersionSelectionEnabled,
                isAutomaticNattKeepaliveTimerEnabled, keepaliveInProfile, ipVersionInProfile,
                encapTypeInProfile, new NetworkCapabilities.Builder().build());
    }

    private void doTestMigrateIkeSession_FromIkeTunnConnParams(
            boolean isAutomaticIpVersionSelectionEnabled,
            boolean isAutomaticNattKeepaliveTimerEnabled,
            int keepaliveInProfile,
            int ipVersionInProfile,
            int encapTypeInProfile,
            @NonNull final NetworkCapabilities nc) throws Exception {
        final Ikev2VpnProfile ikeProfile = makeIkeV2VpnProfile(
                isAutomaticIpVersionSelectionEnabled,
                isAutomaticNattKeepaliveTimerEnabled,
                keepaliveInProfile,
                ipVersionInProfile,
                encapTypeInProfile);

        final IkeSessionParams ikeSessionParams =
                ikeProfile.getIkeTunnelConnectionParams().getIkeSessionParams();
        final int expectedKeepalive = isAutomaticNattKeepaliveTimerEnabled
                ? AUTOMATIC_KEEPALIVE_DELAY_SECONDS
                : ikeSessionParams.getNattKeepAliveDelaySeconds();
        final int expectedIpVersion = isAutomaticIpVersionSelectionEnabled
                ? ESP_IP_VERSION_AUTO
                : ikeSessionParams.getIpVersion();
        final int expectedEncapType = isAutomaticIpVersionSelectionEnabled
                ? ESP_ENCAP_TYPE_AUTO
                : ikeSessionParams.getEncapType();
        doTestMigrateIkeSession(ikeProfile.toVpnProfile(), expectedKeepalive,
                expectedIpVersion, expectedEncapType, nc);
    }

    @Test
    public void doTestMigrateIkeSession_Vcn() throws Exception {
        final int expectedKeepalive = 2097; // Any unlikely number will do
        final NetworkCapabilities vcnNc = new NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .setTransportInfo(new VcnTransportInfo(TEST_SUB_ID, expectedKeepalive))
                .build();
        final Ikev2VpnProfile ikev2VpnProfile = makeIkeV2VpnProfile(
                true /* isAutomaticIpVersionSelectionEnabled */,
                true /* isAutomaticNattKeepaliveTimerEnabled */,
                234 /* keepaliveInProfile */, // Should be ignored, any value will do
                ESP_IP_VERSION_IPV4, // Should be ignored
                ESP_ENCAP_TYPE_UDP // Should be ignored
        );
        doTestMigrateIkeSession(
                ikev2VpnProfile.toVpnProfile(),
                expectedKeepalive,
                ESP_IP_VERSION_AUTO /* expectedIpVersion */,
                ESP_ENCAP_TYPE_AUTO /* expectedEncapType */,
                vcnNc);
    }

    private void doTestMigrateIkeSession(
            @NonNull final VpnProfile profile,
            final int expectedKeepalive,
            final int expectedIpVersion,
            final int expectedEncapType,
            @NonNull final NetworkCapabilities caps) throws Exception {
        final PlatformVpnSnapshot vpnSnapShot =
                verifySetupPlatformVpn(profile,
                        createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */),
                        caps /* underlying network capabilities */,
                        false /* mtuSupportsIpv6 */,
                        expectedKeepalive < DEFAULT_LONG_LIVED_TCP_CONNS_EXPENSIVE_TIMEOUT_SEC);
        // Simulate a new network coming up
        vpnSnapShot.nwCb.onAvailable(TEST_NETWORK_2);
        verify(mIkeSessionWrapper, never()).setNetwork(any(), anyInt(), anyInt(), anyInt());

        vpnSnapShot.nwCb.onCapabilitiesChanged(TEST_NETWORK_2, caps);
        // Verify MOBIKE is triggered
        verify(mIkeSessionWrapper, timeout(TEST_TIMEOUT_MS)).setNetwork(TEST_NETWORK_2,
                expectedIpVersion, expectedEncapType, expectedKeepalive);

        vpnSnapShot.vpn.mVpnRunner.exitVpnRunner();
    }

    @Test
    public void testLinkPropertiesUpdateTriggerReevaluation() throws Exception {
        final boolean hasV6 = true;

        mockCarrierConfig(TEST_SUB_ID, TelephonyManager.SIM_STATE_LOADED, TEST_KEEPALIVE_TIMER,
                PREFERRED_IKE_PROTOCOL_IPV6_ESP);
        final IkeSessionParams params = getTestIkeSessionParams(hasV6,
                new IkeFqdnIdentification(TEST_IDENTITY), TEST_KEEPALIVE_TIMER);
        final IkeTunnelConnectionParams tunnelParams =
                new IkeTunnelConnectionParams(params, CHILD_PARAMS);
        final Ikev2VpnProfile ikeProfile = new Ikev2VpnProfile.Builder(tunnelParams)
                .setBypassable(true)
                .setAutomaticNattKeepaliveTimerEnabled(false)
                .setAutomaticIpVersionSelectionEnabled(true)
                .build();
        final PlatformVpnSnapshot vpnSnapShot =
                verifySetupPlatformVpn(ikeProfile.toVpnProfile(),
                        createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */),
                        new NetworkCapabilities.Builder().build() /* underlying network caps */,
                        hasV6 /* mtuSupportsIpv6 */,
                        false /* areLongLivedTcpConnectionsExpensive */);
        reset(mExecutor);

        // Simulate a new network coming up
        final LinkProperties lp = new LinkProperties();
        lp.addLinkAddress(new LinkAddress("192.0.2.2/32"));

        // Have the executor use the real delay to make sure schedule() was called only
        // once for all calls. Also, arrange for execute() not to call schedule() to avoid
        // messing with the checks for schedule().
        mExecutor.delayMs = TestExecutor.REAL_DELAY;
        mExecutor.executeDirect = true;
        vpnSnapShot.nwCb.onAvailable(TEST_NETWORK_2);
        vpnSnapShot.nwCb.onCapabilitiesChanged(
                TEST_NETWORK_2, new NetworkCapabilities.Builder().build());
        vpnSnapShot.nwCb.onLinkPropertiesChanged(TEST_NETWORK_2, new LinkProperties(lp));
        verify(mExecutor).schedule(any(Runnable.class), longThat(it -> it > 0), any());
        reset(mExecutor);

        final InOrder order = inOrder(mIkeSessionWrapper);

        // Verify the network is started
        order.verify(mIkeSessionWrapper, timeout(TIMEOUT_CROSSTHREAD_MS)).setNetwork(TEST_NETWORK_2,
                ESP_IP_VERSION_AUTO, ESP_ENCAP_TYPE_AUTO, TEST_KEEPALIVE_TIMER);

        // Send the same properties, check that no migration is scheduled
        vpnSnapShot.nwCb.onLinkPropertiesChanged(TEST_NETWORK_2, new LinkProperties(lp));
        verify(mExecutor, never()).schedule(any(Runnable.class), anyLong(), any());

        // Add v6 address, verify MOBIKE is triggered
        lp.addLinkAddress(new LinkAddress("2001:db8::1/64"));
        vpnSnapShot.nwCb.onLinkPropertiesChanged(TEST_NETWORK_2, new LinkProperties(lp));
        order.verify(mIkeSessionWrapper, timeout(TIMEOUT_CROSSTHREAD_MS)).setNetwork(TEST_NETWORK_2,
                ESP_IP_VERSION_AUTO, ESP_ENCAP_TYPE_AUTO, TEST_KEEPALIVE_TIMER);

        // Add another v4 address, verify MOBIKE is triggered
        final LinkProperties stacked = new LinkProperties();
        stacked.setInterfaceName("v4-" + lp.getInterfaceName());
        stacked.addLinkAddress(new LinkAddress("192.168.0.1/32"));
        lp.addStackedLink(stacked);
        vpnSnapShot.nwCb.onLinkPropertiesChanged(TEST_NETWORK_2, new LinkProperties(lp));
        order.verify(mIkeSessionWrapper, timeout(TIMEOUT_CROSSTHREAD_MS)).setNetwork(TEST_NETWORK_2,
                ESP_IP_VERSION_AUTO, ESP_ENCAP_TYPE_AUTO, TEST_KEEPALIVE_TIMER);

        vpnSnapShot.vpn.mVpnRunner.exitVpnRunner();
    }

    private void mockCarrierConfig(int subId, int simStatus, int keepaliveTimer, int ikeProtocol) {
        final SubscriptionInfo subscriptionInfo = mock(SubscriptionInfo.class);
        doReturn(subId).when(subscriptionInfo).getSubscriptionId();
        doReturn(List.of(subscriptionInfo)).when(mSubscriptionManager)
                .getActiveSubscriptionInfoList();

        doReturn(simStatus).when(mTmPerSub).getSimApplicationState();
        doReturn(TEST_MCCMNC).when(mTmPerSub).getSimOperator(subId);

        final PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putInt(KEY_MIN_UDP_PORT_4500_NAT_TIMEOUT_SEC_INT, keepaliveTimer);
        persistableBundle.putInt(KEY_PREFERRED_IKE_PROTOCOL_INT, ikeProtocol);
        // For CarrierConfigManager.isConfigForIdentifiedCarrier check
        persistableBundle.putBoolean(KEY_CARRIER_CONFIG_APPLIED_BOOL, true);
        doReturn(persistableBundle).when(mConfigManager).getConfigForSubId(subId);
    }

    private CarrierConfigManager.CarrierConfigChangeListener getCarrierConfigListener() {
        final ArgumentCaptor<CarrierConfigManager.CarrierConfigChangeListener> listenerCaptor =
                ArgumentCaptor.forClass(CarrierConfigManager.CarrierConfigChangeListener.class);

        verify(mConfigManager).registerCarrierConfigChangeListener(any(), listenerCaptor.capture());

        return listenerCaptor.getValue();
    }

    @Test
    public void testNattKeepaliveTimerFromCarrierConfig_noSubId() throws Exception {
        doTestReadCarrierConfig(new NetworkCapabilities(),
                TelephonyManager.SIM_STATE_LOADED,
                PREFERRED_IKE_PROTOCOL_IPV4_UDP,
                AUTOMATIC_KEEPALIVE_DELAY_SECONDS /* expectedKeepaliveTimer */,
                ESP_IP_VERSION_AUTO /* expectedIpVersion */,
                ESP_ENCAP_TYPE_AUTO /* expectedEncapType */,
                false /* expectedReadFromCarrierConfig*/,
                true /* areLongLivedTcpConnectionsExpensive */);
    }

    @Test
    public void testNattKeepaliveTimerFromCarrierConfig_simAbsent() throws Exception {
        doTestReadCarrierConfig(new NetworkCapabilities.Builder().build(),
                TelephonyManager.SIM_STATE_ABSENT,
                PREFERRED_IKE_PROTOCOL_IPV4_UDP,
                AUTOMATIC_KEEPALIVE_DELAY_SECONDS /* expectedKeepaliveTimer */,
                ESP_IP_VERSION_AUTO /* expectedIpVersion */,
                ESP_ENCAP_TYPE_AUTO /* expectedEncapType */,
                false /* expectedReadFromCarrierConfig*/,
                true /* areLongLivedTcpConnectionsExpensive */);
    }

    @Test
    public void testNattKeepaliveTimerFromCarrierConfig() throws Exception {
        doTestReadCarrierConfig(createTestCellNc(),
                TelephonyManager.SIM_STATE_LOADED,
                PREFERRED_IKE_PROTOCOL_AUTO,
                TEST_KEEPALIVE_TIMER /* expectedKeepaliveTimer */,
                ESP_IP_VERSION_AUTO /* expectedIpVersion */,
                ESP_ENCAP_TYPE_AUTO /* expectedEncapType */,
                true /* expectedReadFromCarrierConfig*/,
                false /* areLongLivedTcpConnectionsExpensive */);
    }

    @Test
    public void testNattKeepaliveTimerFromCarrierConfig_NotCell() throws Exception {
        final NetworkCapabilities nc = new NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .setTransportInfo(new WifiInfo.Builder().build())
                .build();
        doTestReadCarrierConfig(nc,
                TelephonyManager.SIM_STATE_LOADED,
                PREFERRED_IKE_PROTOCOL_IPV4_UDP,
                AUTOMATIC_KEEPALIVE_DELAY_SECONDS /* expectedKeepaliveTimer */,
                ESP_IP_VERSION_AUTO /* expectedIpVersion */,
                ESP_ENCAP_TYPE_AUTO /* expectedEncapType */,
                false /* expectedReadFromCarrierConfig*/,
                true /* areLongLivedTcpConnectionsExpensive */);
    }

    @Test
    public void testPreferredIpProtocolFromCarrierConfig_v4UDP() throws Exception {
        doTestReadCarrierConfig(createTestCellNc(),
                TelephonyManager.SIM_STATE_LOADED,
                PREFERRED_IKE_PROTOCOL_IPV4_UDP,
                TEST_KEEPALIVE_TIMER /* expectedKeepaliveTimer */,
                ESP_IP_VERSION_IPV4 /* expectedIpVersion */,
                ESP_ENCAP_TYPE_UDP /* expectedEncapType */,
                true /* expectedReadFromCarrierConfig*/,
                false /* areLongLivedTcpConnectionsExpensive */);
    }

    @Test
    public void testPreferredIpProtocolFromCarrierConfig_v6ESP() throws Exception {
        doTestReadCarrierConfig(createTestCellNc(),
                TelephonyManager.SIM_STATE_LOADED,
                PREFERRED_IKE_PROTOCOL_IPV6_ESP,
                TEST_KEEPALIVE_TIMER /* expectedKeepaliveTimer */,
                ESP_IP_VERSION_IPV6 /* expectedIpVersion */,
                ESP_ENCAP_TYPE_NONE /* expectedEncapType */,
                true /* expectedReadFromCarrierConfig*/,
                false /* areLongLivedTcpConnectionsExpensive */);
    }

    @Test
    public void testPreferredIpProtocolFromCarrierConfig_v6UDP() throws Exception {
        doTestReadCarrierConfig(createTestCellNc(),
                TelephonyManager.SIM_STATE_LOADED,
                PREFERRED_IKE_PROTOCOL_IPV6_UDP,
                TEST_KEEPALIVE_TIMER /* expectedKeepaliveTimer */,
                ESP_IP_VERSION_IPV6 /* expectedIpVersion */,
                ESP_ENCAP_TYPE_UDP /* expectedEncapType */,
                true /* expectedReadFromCarrierConfig*/,
                false /* areLongLivedTcpConnectionsExpensive */);
    }

    private NetworkCapabilities createTestCellNc() {
        return new NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .setNetworkSpecifier(new TelephonyNetworkSpecifier.Builder()
                        .setSubscriptionId(TEST_SUB_ID)
                        .build())
                .build();
    }

    private void doTestReadCarrierConfig(NetworkCapabilities nc, int simState, int preferredIpProto,
            int expectedKeepaliveTimer, int expectedIpVersion, int expectedEncapType,
            boolean expectedReadFromCarrierConfig,
            boolean areLongLivedTcpConnectionsExpensive)
            throws Exception {
        final Ikev2VpnProfile ikeProfile =
                new Ikev2VpnProfile.Builder(TEST_VPN_SERVER, TEST_VPN_IDENTITY)
                        .setAuthPsk(TEST_VPN_PSK)
                        .setBypassable(true /* isBypassable */)
                        .setAutomaticNattKeepaliveTimerEnabled(true)
                        .setAutomaticIpVersionSelectionEnabled(true)
                        .build();

        final PlatformVpnSnapshot vpnSnapShot =
                verifySetupPlatformVpn(ikeProfile.toVpnProfile(),
                        createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */),
                        new NetworkCapabilities.Builder().build() /* underlying network caps */,
                        false /* mtuSupportsIpv6 */,
                        true /* areLongLivedTcpConnectionsExpensive */);

        final CarrierConfigManager.CarrierConfigChangeListener listener =
                getCarrierConfigListener();

        // Simulate a new network coming up
        vpnSnapShot.nwCb.onAvailable(TEST_NETWORK_2);
        // Migration will not be started until receiving network capabilities change.
        verify(mIkeSessionWrapper, never()).setNetwork(any(), anyInt(), anyInt(), anyInt());

        reset(mIkeSessionWrapper);
        mockCarrierConfig(TEST_SUB_ID, simState, TEST_KEEPALIVE_TIMER, preferredIpProto);
        vpnSnapShot.nwCb.onCapabilitiesChanged(TEST_NETWORK_2, nc);
        verify(mIkeSessionWrapper, timeout(TEST_TIMEOUT_MS)).setNetwork(TEST_NETWORK_2,
                expectedIpVersion, expectedEncapType, expectedKeepaliveTimer);
        if (expectedReadFromCarrierConfig) {
            final ArgumentCaptor<NetworkCapabilities> ncCaptor =
                    ArgumentCaptor.forClass(NetworkCapabilities.class);
            verify(mMockNetworkAgent, timeout(TEST_TIMEOUT_MS))
                    .doSendNetworkCapabilities(ncCaptor.capture());

            final VpnTransportInfo info =
                    (VpnTransportInfo) ncCaptor.getValue().getTransportInfo();
            assertEquals(areLongLivedTcpConnectionsExpensive,
                    info.areLongLivedTcpConnectionsExpensive());
        } else {
            verify(mMockNetworkAgent, never()).doSendNetworkCapabilities(any());
        }

        reset(mExecutor);
        reset(mIkeSessionWrapper);
        reset(mMockNetworkAgent);

        // Trigger carrier config change
        listener.onCarrierConfigChanged(1 /* logicalSlotIndex */, TEST_SUB_ID,
                -1 /* carrierId */, -1 /* specificCarrierId */);
        verify(mIkeSessionWrapper).setNetwork(TEST_NETWORK_2,
                expectedIpVersion, expectedEncapType, expectedKeepaliveTimer);
        // Expect no NetworkCapabilities change.
        // Call to doSendNetworkCapabilities() will not be triggered.
        verify(mMockNetworkAgent, never()).doSendNetworkCapabilities(any());
    }

    @Test
    public void testStartPlatformVpn_mtuDoesNotSupportIpv6() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot =
                verifySetupPlatformVpn(
                        createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */),
                        false /* mtuSupportsIpv6 */);
        vpnSnapShot.vpn.mVpnRunner.exitVpnRunner();
    }

    @Test
    public void testStartPlatformVpn_underlyingNetworkNotChange() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */));
        // Trigger update on the same network should not cause underlying network change in NC of
        // the VPN network
        vpnSnapShot.nwCb.onAvailable(TEST_NETWORK);
        vpnSnapShot.nwCb.onCapabilitiesChanged(TEST_NETWORK,
                new NetworkCapabilities.Builder()
                        .setSubscriptionIds(Set.of(TEST_SUB_ID))
                        .build());
        // Verify setNetwork() called but no underlying network update
        verify(mIkeSessionWrapper, timeout(TEST_TIMEOUT_MS)).setNetwork(eq(TEST_NETWORK),
                eq(ESP_IP_VERSION_AUTO) /* ipVersion */,
                eq(ESP_ENCAP_TYPE_AUTO) /* encapType */,
                eq(DEFAULT_UDP_PORT_4500_NAT_TIMEOUT_SEC_INT) /* keepaliveDelay */);
        verify(mMockNetworkAgent, never())
                .doSetUnderlyingNetworks(any());

        vpnSnapShot.nwCb.onAvailable(TEST_NETWORK_2);
        vpnSnapShot.nwCb.onCapabilitiesChanged(TEST_NETWORK_2,
                new NetworkCapabilities.Builder().build());

        // A new network should trigger both setNetwork() and a underlying network update.
        verify(mIkeSessionWrapper, timeout(TEST_TIMEOUT_MS)).setNetwork(eq(TEST_NETWORK_2),
                eq(ESP_IP_VERSION_AUTO) /* ipVersion */,
                eq(ESP_ENCAP_TYPE_AUTO) /* encapType */,
                eq(DEFAULT_UDP_PORT_4500_NAT_TIMEOUT_SEC_INT) /* keepaliveDelay */);
        verify(mMockNetworkAgent).doSetUnderlyingNetworks(
                Collections.singletonList(TEST_NETWORK_2));

        vpnSnapShot.vpn.mVpnRunner.exitVpnRunner();
    }

    @Test
    public void testStartPlatformVpnMobility_mobikeEnabled() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */));

        // Set new MTU on a different network
        final int newMtu = IPV6_MIN_MTU + 1;
        doReturn(newMtu).when(mTestDeps).calculateVpnMtu(any(), anyInt(), anyInt(), anyBoolean());

        // Mock network loss and verify a cleanup task is scheduled
        vpnSnapShot.nwCb.onLost(TEST_NETWORK);
        verify(mExecutor, atLeastOnce()).schedule(any(Runnable.class), anyLong(), any());

        // Mock new network comes up and the cleanup task is cancelled
        vpnSnapShot.nwCb.onAvailable(TEST_NETWORK_2);
        verify(mIkeSessionWrapper, never()).setNetwork(any(), anyInt(), anyInt(), anyInt());

        vpnSnapShot.nwCb.onCapabilitiesChanged(TEST_NETWORK_2,
                new NetworkCapabilities.Builder().build());
        // Verify MOBIKE is triggered
        verify(mIkeSessionWrapper, timeout(TEST_TIMEOUT_MS)).setNetwork(eq(TEST_NETWORK_2),
                eq(ESP_IP_VERSION_AUTO) /* ipVersion */,
                eq(ESP_ENCAP_TYPE_AUTO) /* encapType */,
                eq(DEFAULT_UDP_PORT_4500_NAT_TIMEOUT_SEC_INT) /* keepaliveDelay */);
        // Verify mNetworkCapabilities is updated
        assertEquals(
                Collections.singletonList(TEST_NETWORK_2),
                vpnSnapShot.vpn.mNetworkCapabilities.getUnderlyingNetworks());
        verify(mMockNetworkAgent)
                .doSetUnderlyingNetworks(Collections.singletonList(TEST_NETWORK_2));

        // Mock the MOBIKE procedure
        vpnSnapShot.ikeCb.onIkeSessionConnectionInfoChanged(createIkeConnectInfo_2());
        vpnSnapShot.childCb.onIpSecTransformsMigrated(
                createIpSecTransform(), createIpSecTransform());

        verify(mIpSecService).setNetworkForTunnelInterface(
                eq(TEST_TUNNEL_RESOURCE_ID), eq(TEST_NETWORK_2), anyString());

        // Expect 2 times: one for initial setup and one for MOBIKE
        verifyApplyTunnelModeTransforms(2);

        // Verify mNetworkAgent is updated
        verify(mMockNetworkAgent).doSendLinkProperties(argThat(lp -> lp.getMtu() == newMtu));
        verify(mMockNetworkAgent, never()).unregister();
        // No further doSetUnderlyingNetworks interaction. The interaction count should stay one.
        verify(mMockNetworkAgent, times(1)).doSetUnderlyingNetworks(any());
        vpnSnapShot.vpn.mVpnRunner.exitVpnRunner();
    }

    @Test
    public void testStartPlatformVpnMobility_mobikeEnabledMtuDoesNotSupportIpv6() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot =
                verifySetupPlatformVpn(
                        createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */));

        // Set MTU below 1280
        final int newMtu = IPV6_MIN_MTU - 1;
        doReturn(newMtu).when(mTestDeps).calculateVpnMtu(any(), anyInt(), anyInt(), anyBoolean());

        // Mock new network available & MOBIKE procedures
        vpnSnapShot.nwCb.onAvailable(TEST_NETWORK_2);
        vpnSnapShot.nwCb.onCapabilitiesChanged(TEST_NETWORK_2,
                new NetworkCapabilities.Builder().build());
        // Verify mNetworkCapabilities is updated
        verify(mMockNetworkAgent, timeout(TEST_TIMEOUT_MS))
                .doSetUnderlyingNetworks(Collections.singletonList(TEST_NETWORK_2));
        assertEquals(
                Collections.singletonList(TEST_NETWORK_2),
                vpnSnapShot.vpn.mNetworkCapabilities.getUnderlyingNetworks());

        vpnSnapShot.ikeCb.onIkeSessionConnectionInfoChanged(createIkeConnectInfo_2());
        vpnSnapShot.childCb.onIpSecTransformsMigrated(
                createIpSecTransform(), createIpSecTransform());

        // Verify removal of IPv6 addresses and routes triggers a network agent restart
        final ArgumentCaptor<LinkProperties> lpCaptor =
                ArgumentCaptor.forClass(LinkProperties.class);
        verify(mTestDeps, times(2))
                .newNetworkAgent(any(), any(), anyString(), any(), lpCaptor.capture(), any(), any(),
                        any(), any());
        verify(mMockNetworkAgent).unregister();
        // mMockNetworkAgent is an old NetworkAgent, so it won't update LinkProperties after
        // unregistering.
        verify(mMockNetworkAgent, never()).doSendLinkProperties(any());

        final LinkProperties lp = lpCaptor.getValue();

        for (LinkAddress addr : lp.getLinkAddresses()) {
            if (addr.isIpv6()) {
                fail("IPv6 address found on VPN with MTU < IPv6 minimum MTU");
            }
        }

        for (InetAddress dnsAddr : lp.getDnsServers()) {
            if (dnsAddr instanceof Inet6Address) {
                fail("IPv6 DNS server found on VPN with MTU < IPv6 minimum MTU");
            }
        }

        for (RouteInfo routeInfo : lp.getRoutes()) {
            if (routeInfo.getDestinationLinkAddress().isIpv6()
                    && !routeInfo.isIPv6UnreachableDefault()) {
                fail("IPv6 route found on VPN with MTU < IPv6 minimum MTU");
            }
        }

        assertEquals(newMtu, lp.getMtu());

        vpnSnapShot.vpn.mVpnRunner.exitVpnRunner();
    }

    @Test
    public void testStartPlatformVpnReestablishes_mobikeDisabled() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), false /* isMobikeEnabled */));

        // Forget the first IKE creation to be prepared to capture callbacks of the second
        // IKE session
        resetIkev2SessionCreator(mock(Vpn.IkeSessionWrapper.class));

        // Mock network switch
        vpnSnapShot.nwCb.onLost(TEST_NETWORK);
        vpnSnapShot.nwCb.onAvailable(TEST_NETWORK_2);
        // The old IKE Session will not be killed until receiving network capabilities change.
        verify(mIkeSessionWrapper, never()).kill();

        vpnSnapShot.nwCb.onCapabilitiesChanged(
                TEST_NETWORK_2, new NetworkCapabilities.Builder().build());
        // Verify the old IKE Session is killed
        verify(mIkeSessionWrapper, timeout(TEST_TIMEOUT_MS)).kill();

        // Capture callbacks of the new IKE Session
        final Pair<IkeSessionCallback, ChildSessionCallback> cbPair =
                verifyCreateIkeAndCaptureCbs();
        final IkeSessionCallback ikeCb = cbPair.first;
        final ChildSessionCallback childCb = cbPair.second;

        // Mock the IKE Session setup
        ikeCb.onOpened(createIkeConfig(createIkeConnectInfo_2(), false /* isMobikeEnabled */));

        childCb.onIpSecTransformCreated(createIpSecTransform(), IpSecManager.DIRECTION_IN);
        childCb.onIpSecTransformCreated(createIpSecTransform(), IpSecManager.DIRECTION_OUT);
        childCb.onOpened(createChildConfig());

        // Expect 2 times since there have been two Session setups
        verifyApplyTunnelModeTransforms(2);

        // Verify mNetworkCapabilities and mNetworkAgent are updated
        assertEquals(
                Collections.singletonList(TEST_NETWORK_2),
                vpnSnapShot.vpn.mNetworkCapabilities.getUnderlyingNetworks());
        verify(mMockNetworkAgent)
                .doSetUnderlyingNetworks(Collections.singletonList(TEST_NETWORK_2));

        vpnSnapShot.vpn.mVpnRunner.exitVpnRunner();
    }

    private String getDump(@NonNull final Vpn vpn) {
        final StringWriter sw = new StringWriter();
        final IndentingPrintWriter writer = new IndentingPrintWriter(sw, "");
        vpn.dump(writer);
        writer.flush();
        return sw.toString();
    }

    private int countMatches(@NonNull final Pattern regexp, @NonNull final String string) {
        final Matcher m = regexp.matcher(string);
        int i = 0;
        while (m.find()) ++i;
        return i;
    }

    @Test
    public void testNCEventChanges() throws Exception {
        final NetworkCapabilities.Builder ncBuilder = new NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET)
                .addCapability(NET_CAPABILITY_NOT_RESTRICTED)
                .setLinkDownstreamBandwidthKbps(1000)
                .setLinkUpstreamBandwidthKbps(500);

        final Ikev2VpnProfile ikeProfile =
                new Ikev2VpnProfile.Builder(TEST_VPN_SERVER, TEST_VPN_IDENTITY)
                        .setAuthPsk(TEST_VPN_PSK)
                        .setBypassable(true /* isBypassable */)
                        .setAutomaticNattKeepaliveTimerEnabled(true)
                        .setAutomaticIpVersionSelectionEnabled(true)
                        .build();

        final PlatformVpnSnapshot vpnSnapShot =
                verifySetupPlatformVpn(ikeProfile.toVpnProfile(),
                        createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */),
                        ncBuilder.build(), false /* mtuSupportsIpv6 */,
                        true /* areLongLivedTcpConnectionsExpensive */);

        // Calls to onCapabilitiesChanged will be thrown to the executor for execution ; by
        // default this will incur a 10ms delay before it's executed, messing with the timing
        // of the log and having the checks for counts in equals() below flake.
        mExecutor.executeDirect = true;

        // First nc changed triggered by verifySetupPlatformVpn
        final Pattern pattern = Pattern.compile("Cap changed from", Pattern.MULTILINE);
        final String stage1 = getDump(vpnSnapShot.vpn);
        assertEquals(1, countMatches(pattern, stage1));

        vpnSnapShot.nwCb.onCapabilitiesChanged(TEST_NETWORK, ncBuilder.build());
        final String stage2 = getDump(vpnSnapShot.vpn);
        // Was the same caps, there should still be only 1 match
        assertEquals(1, countMatches(pattern, stage2));

        ncBuilder.setLinkDownstreamBandwidthKbps(1200)
                .setLinkUpstreamBandwidthKbps(300);
        vpnSnapShot.nwCb.onCapabilitiesChanged(TEST_NETWORK, ncBuilder.build());
        final String stage3 = getDump(vpnSnapShot.vpn);
        // Was not an important change, should not be logged, still only 1 match
        assertEquals(1, countMatches(pattern, stage3));

        ncBuilder.addCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED);
        vpnSnapShot.nwCb.onCapabilitiesChanged(TEST_NETWORK, ncBuilder.build());
        final String stage4 = getDump(vpnSnapShot.vpn);
        // Change to caps is important, should cause a new match
        assertEquals(2, countMatches(pattern, stage4));

        ncBuilder.removeCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED);
        ncBuilder.setLinkDownstreamBandwidthKbps(600);
        vpnSnapShot.nwCb.onCapabilitiesChanged(TEST_NETWORK, ncBuilder.build());
        final String stage5 = getDump(vpnSnapShot.vpn);
        // Change to caps is important, should cause a new match even with the unimportant change
        assertEquals(3, countMatches(pattern, stage5));
    }
    // TODO : beef up event logs tests

    private void verifyHandlingNetworkLoss(PlatformVpnSnapshot vpnSnapShot) throws Exception {
        // Forget the #sendLinkProperties during first setup.
        reset(mMockNetworkAgent);

        // Mock network loss
        vpnSnapShot.nwCb.onLost(TEST_NETWORK);

        // Mock the grace period expires
        verify(mExecutor, atLeastOnce()).schedule(any(Runnable.class), anyLong(), any());

        final ArgumentCaptor<LinkProperties> lpCaptor =
                ArgumentCaptor.forClass(LinkProperties.class);
        verify(mMockNetworkAgent, timeout(TEST_TIMEOUT_MS))
                .doSendLinkProperties(lpCaptor.capture());
        final LinkProperties lp = lpCaptor.getValue();

        assertNull(lp.getInterfaceName());
        final List<RouteInfo> expectedRoutes = Arrays.asList(
                new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), null /* gateway */,
                        null /* iface */, RTN_UNREACHABLE),
                new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), null /* gateway */,
                        null /* iface */, RTN_UNREACHABLE));
        assertEquals(expectedRoutes, lp.getRoutes());

        verify(mMockNetworkAgent, timeout(TEST_TIMEOUT_MS)).unregister();
    }

    @Test
    public void testStartPlatformVpnHandlesNetworkLoss_mobikeEnabled() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */));
        verifyHandlingNetworkLoss(vpnSnapShot);
    }

    @Test
    public void testStartPlatformVpnHandlesNetworkLoss_mobikeDisabled() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), false /* isMobikeEnabled */));
        verifyHandlingNetworkLoss(vpnSnapShot);
    }

    private ConnectivityDiagnosticsCallback getConnectivityDiagCallback() {
        final ArgumentCaptor<ConnectivityDiagnosticsCallback> cdcCaptor =
                ArgumentCaptor.forClass(ConnectivityDiagnosticsCallback.class);
        verify(mCdm).registerConnectivityDiagnosticsCallback(
                any(), any(), cdcCaptor.capture());
        return cdcCaptor.getValue();
    }

    private DataStallReport createDataStallReport() {
        return new DataStallReport(TEST_NETWORK, 1234 /* reportTimestamp */,
                1 /* detectionMethod */, new LinkProperties(), new NetworkCapabilities(),
                new PersistableBundle());
    }

    private void verifyMobikeTriggered(List<Network> expected, int retryIndex) {
        // Verify retry is scheduled
        final long expectedDelayMs = mTestDeps.getValidationFailRecoveryMs(retryIndex);
        final ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
        verify(mExecutor, times(retryIndex + 1)).schedule(
                any(Runnable.class), delayCaptor.capture(), eq(TimeUnit.MILLISECONDS));
        final List<Long> delays = delayCaptor.getAllValues();
        assertEquals(expectedDelayMs, (long) delays.get(delays.size() - 1));

        final ArgumentCaptor<Network> networkCaptor = ArgumentCaptor.forClass(Network.class);
        verify(mIkeSessionWrapper, timeout(TEST_TIMEOUT_MS + expectedDelayMs))
                .setNetwork(networkCaptor.capture(), anyInt() /* ipVersion */,
                        anyInt() /* encapType */, anyInt() /* keepaliveDelay */);
        assertEquals(expected, Collections.singletonList(networkCaptor.getValue()));
    }

    @Test
    public void testDataStallInIkev2VpnMobikeDisabled() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), false /* isMobikeEnabled */));

        doReturn(TEST_NETWORK).when(mMockNetworkAgent).getNetwork();
        ((Vpn.IkeV2VpnRunner) vpnSnapShot.vpn.mVpnRunner).onValidationStatus(
                NetworkAgent.VALIDATION_STATUS_NOT_VALID);

        // Should not trigger MOBIKE if MOBIKE is not enabled
        verify(mIkeSessionWrapper, never()).setNetwork(any() /* network */,
                anyInt() /* ipVersion */, anyInt() /* encapType */, anyInt() /* keepaliveDelay */);
    }

    @Test
    public void testDataStallInIkev2VpnRecoveredByMobike() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */));

        doReturn(TEST_NETWORK).when(mMockNetworkAgent).getNetwork();
        ((Vpn.IkeV2VpnRunner) vpnSnapShot.vpn.mVpnRunner).onValidationStatus(
                NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        // Verify MOBIKE is triggered
        verifyMobikeTriggered(vpnSnapShot.vpn.mNetworkCapabilities.getUnderlyingNetworks(),
                0 /* retryIndex */);
        // Validation failure on VPN network should trigger a re-evaluation request for the
        // underlying network.
        verify(mConnectivityManager).reportNetworkConnectivity(TEST_NETWORK, false);

        reset(mIkev2SessionCreator);
        reset(mExecutor);

        // Send validation status update.
        // Recovered and get network validated. It should not trigger the ike session reset.
        ((Vpn.IkeV2VpnRunner) vpnSnapShot.vpn.mVpnRunner).onValidationStatus(
                NetworkAgent.VALIDATION_STATUS_VALID);
        // Verify that the retry count is reset. The mValidationFailRetryCount will not be reset
        // until the executor finishes the execute() call, so wait until the all tasks are executed.
        waitForIdleSerialExecutor(mExecutor, TEST_TIMEOUT_MS);
        assertEquals(0,
                ((Vpn.IkeV2VpnRunner) vpnSnapShot.vpn.mVpnRunner).mValidationFailRetryCount);
        verify(mIkev2SessionCreator, never()).createIkeSession(
                any(), any(), any(), any(), any(), any());

        reset(mIkeSessionWrapper);
        reset(mExecutor);

        // Another validation fail should trigger another reportNetworkConnectivity
        ((Vpn.IkeV2VpnRunner) vpnSnapShot.vpn.mVpnRunner).onValidationStatus(
                NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        verifyMobikeTriggered(vpnSnapShot.vpn.mNetworkCapabilities.getUnderlyingNetworks(),
                0 /* retryIndex */);
        verify(mConnectivityManager, times(2)).reportNetworkConnectivity(TEST_NETWORK, false);
    }

    @Test
    public void testDataStallInIkev2VpnNotRecoveredByMobike() throws Exception {
        final PlatformVpnSnapshot vpnSnapShot = verifySetupPlatformVpn(
                createIkeConfig(createIkeConnectInfo(), true /* isMobikeEnabled */));

        int retry = 0;
        doReturn(TEST_NETWORK).when(mMockNetworkAgent).getNetwork();
        ((Vpn.IkeV2VpnRunner) vpnSnapShot.vpn.mVpnRunner).onValidationStatus(
                NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        verifyMobikeTriggered(vpnSnapShot.vpn.mNetworkCapabilities.getUnderlyingNetworks(),
                retry++);
        // Validation failure on VPN network should trigger a re-evaluation request for the
        // underlying network.
        verify(mConnectivityManager).reportNetworkConnectivity(TEST_NETWORK, false);
        reset(mIkev2SessionCreator);

        // Second validation status update.
        ((Vpn.IkeV2VpnRunner) vpnSnapShot.vpn.mVpnRunner).onValidationStatus(
                NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        verifyMobikeTriggered(vpnSnapShot.vpn.mNetworkCapabilities.getUnderlyingNetworks(),
                retry++);
        // Call to reportNetworkConnectivity should only happen once. No further interaction.
        verify(mConnectivityManager, times(1)).reportNetworkConnectivity(TEST_NETWORK, false);

        // Use real delay to verify reset session will not be performed if there is an existing
        // recovery for resetting the session.
        mExecutor.delayMs = TestExecutor.REAL_DELAY;
        mExecutor.executeDirect = true;
        // Send validation status update should result in ike session reset.
        ((Vpn.IkeV2VpnRunner) vpnSnapShot.vpn.mVpnRunner).onValidationStatus(
                NetworkAgent.VALIDATION_STATUS_NOT_VALID);

        // Verify session reset is scheduled
        long expectedDelay = mTestDeps.getValidationFailRecoveryMs(retry++);
        final ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
        verify(mExecutor, times(retry)).schedule(any(Runnable.class), delayCaptor.capture(),
                eq(TimeUnit.MILLISECONDS));
        final List<Long> delays = delayCaptor.getAllValues();
        assertEquals(expectedDelay, (long) delays.get(delays.size() - 1));
        // Call to reportNetworkConnectivity should only happen once. No further interaction.
        verify(mConnectivityManager, times(1)).reportNetworkConnectivity(TEST_NETWORK, false);

        // Another invalid status reported should not trigger other scheduled recovery.
        expectedDelay = mTestDeps.getValidationFailRecoveryMs(retry++);
        ((Vpn.IkeV2VpnRunner) vpnSnapShot.vpn.mVpnRunner).onValidationStatus(
                NetworkAgent.VALIDATION_STATUS_NOT_VALID);
        verify(mExecutor, never()).schedule(
                any(Runnable.class), eq(expectedDelay), eq(TimeUnit.MILLISECONDS));

        // Verify that session being reset
        verify(mIkev2SessionCreator, timeout(TEST_TIMEOUT_MS + expectedDelay))
                .createIkeSession(any(), any(), any(), any(), any(), any());
        // Call to reportNetworkConnectivity should only happen once. No further interaction.
        verify(mConnectivityManager, times(1)).reportNetworkConnectivity(TEST_NETWORK, false);
    }

    @Test
    public void testStartLegacyVpnType() throws Exception {
        setMockedUsers(PRIMARY_USER);
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final VpnProfile profile = new VpnProfile("testProfile" /* key */);

        profile.type = VpnProfile.TYPE_PPTP;
        assertThrows(UnsupportedOperationException.class, () -> startLegacyVpn(vpn, profile));
        profile.type = VpnProfile.TYPE_L2TP_IPSEC_PSK;
        assertThrows(UnsupportedOperationException.class, () -> startLegacyVpn(vpn, profile));
    }

    @Test
    public void testStartLegacyVpnModifyProfile_TypePSK() throws Exception {
        setMockedUsers(PRIMARY_USER);
        final Vpn vpn = createVpn(PRIMARY_USER.id);
        final Ikev2VpnProfile ikev2VpnProfile =
                new Ikev2VpnProfile.Builder(TEST_VPN_SERVER, TEST_VPN_IDENTITY)
                        .setAuthPsk(TEST_VPN_PSK)
                        .build();
        final VpnProfile profile = ikev2VpnProfile.toVpnProfile();

        startLegacyVpn(vpn, profile);
        assertEquals(profile, ikev2VpnProfile.toVpnProfile());
    }

    // Make it public and un-final so as to spy it
    public class TestDeps extends Vpn.Dependencies {
        TestDeps() {}

        @Override
        public boolean isCallerSystem() {
            return true;
        }

        @Override
        public PendingIntent getIntentForStatusPanel(Context context) {
            return null;
        }

        @Override
        public ParcelFileDescriptor adoptFd(Vpn vpn, int mtu) {
            return new ParcelFileDescriptor(new FileDescriptor());
        }

        @Override
        public int jniCreate(Vpn vpn, int mtu) {
            // Pick a random positive number as fd to return.
            return 345;
        }

        @Override
        public String jniGetName(Vpn vpn, int fd) {
            return TEST_IFACE_NAME;
        }

        @Override
        public int jniSetAddresses(Vpn vpn, String interfaze, String addresses) {
            if (addresses == null) return 0;
            // Return the number of addresses.
            return addresses.split(" ").length;
        }

        @Override
        public void setBlocking(FileDescriptor fd, boolean blocking) {}

        @Override
        public DeviceIdleInternal getDeviceIdleInternal() {
            return mDeviceIdleInternal;
        }

        @Override
        public long getValidationFailRecoveryMs(int retryCount) {
            // Simply return retryCount as the delay seconds for retrying.
            return retryCount * 100L;
        }

        @Override
        public ScheduledThreadPoolExecutor newScheduledThreadPoolExecutor() {
            return mExecutor;
        }

        public boolean mIgnoreCallingUidChecks = true;
        @Override
        public void verifyCallingUidAndPackage(Context context, String packageName, int userId) {
            if (!mIgnoreCallingUidChecks) {
                super.verifyCallingUidAndPackage(context, packageName, userId);
            }
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
        final Vpn vpn = new Vpn(testLooper.getLooper(), mContext, mTestDeps, mNetService,
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
}
