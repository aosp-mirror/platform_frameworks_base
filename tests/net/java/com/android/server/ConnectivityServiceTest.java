/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static android.Manifest.permission.CHANGE_NETWORK_STATE;
import static android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.content.Intent.ACTION_USER_ADDED;
import static android.content.Intent.ACTION_USER_REMOVED;
import static android.content.Intent.ACTION_USER_UNLOCKED;
import static android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.EXTRA_NETWORK_INFO;
import static android.net.ConnectivityManager.EXTRA_NETWORK_TYPE;
import static android.net.ConnectivityManager.NETID_UNSET;
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_OFF;
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_OPPORTUNISTIC;
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_MOBILE_FOTA;
import static android.net.ConnectivityManager.TYPE_MOBILE_MMS;
import static android.net.ConnectivityManager.TYPE_MOBILE_SUPL;
import static android.net.ConnectivityManager.TYPE_VPN;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_PROBE_DNS;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_PROBE_FALLBACK;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_PROBE_HTTP;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_PROBE_HTTPS;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_PROBE_PRIVDNS;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_RESULT_PARTIAL;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_RESULT_VALID;
import static android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL;
import static android.net.NetworkCapabilities.NET_CAPABILITY_CBS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_EIMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_ENTERPRISE;
import static android.net.NetworkCapabilities.NET_CAPABILITY_FOREGROUND;
import static android.net.NetworkCapabilities.NET_CAPABILITY_FOTA;
import static android.net.NetworkCapabilities.NET_CAPABILITY_IA;
import static android.net.NetworkCapabilities.NET_CAPABILITY_IMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_MMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_OEM_PAID;
import static android.net.NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE;
import static android.net.NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY;
import static android.net.NetworkCapabilities.NET_CAPABILITY_RCS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_SUPL;
import static android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_WIFI_P2P;
import static android.net.NetworkCapabilities.NET_CAPABILITY_XCAP;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE;
import static android.net.NetworkPolicyManager.RULE_ALLOW_METERED;
import static android.net.NetworkPolicyManager.RULE_NONE;
import static android.net.NetworkPolicyManager.RULE_REJECT_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_METERED;
import static android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID;
import static android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK;
import static android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY;
import static android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY;
import static android.net.OemNetworkPreferences.OEM_NETWORK_PREFERENCE_UNINITIALIZED;
import static android.net.RouteInfo.RTN_UNREACHABLE;
import static android.os.Process.INVALID_UID;
import static android.system.OsConstants.IPPROTO_TCP;

import static com.android.server.ConnectivityServiceTestUtils.transportToLegacyType;
import static com.android.testutils.ConcurrentUtils.await;
import static com.android.testutils.ConcurrentUtils.durationOf;
import static com.android.testutils.ExceptionUtils.ignoreExceptions;
import static com.android.testutils.HandlerUtils.waitForIdleSerialExecutor;
import static com.android.testutils.MiscAsserts.assertContainsExactly;
import static com.android.testutils.MiscAsserts.assertEmpty;
import static com.android.testutils.MiscAsserts.assertLength;
import static com.android.testutils.MiscAsserts.assertRunsInAtMost;
import static com.android.testutils.MiscAsserts.assertThrows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.location.LocationManager;
import android.net.CaptivePortalData;
import android.net.ConnectionInfo;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.ConnectivityManager.PacketKeepalive;
import android.net.ConnectivityManager.PacketKeepaliveCallback;
import android.net.ConnectivityManager.TooManyRequestsException;
import android.net.ConnectivityThread;
import android.net.DataStallReportParcelable;
import android.net.EthernetManager;
import android.net.IConnectivityDiagnosticsCallback;
import android.net.IDnsResolver;
import android.net.INetd;
import android.net.INetworkMonitor;
import android.net.INetworkMonitorCallbacks;
import android.net.INetworkPolicyListener;
import android.net.INetworkStatsService;
import android.net.IOnSetOemNetworkPreferenceListener;
import android.net.IQosCallback;
import android.net.InetAddresses;
import android.net.InterfaceConfigurationParcel;
import android.net.IpPrefix;
import android.net.IpSecManager;
import android.net.IpSecManager.UdpEncapsulationSocket;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MatchAllNetworkSpecifier;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkPolicyManager;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.NetworkStack;
import android.net.NetworkStackClient;
import android.net.NetworkStateSnapshot;
import android.net.NetworkTestResultParcelable;
import android.net.OemNetworkPreferences;
import android.net.ProxyInfo;
import android.net.QosCallbackException;
import android.net.QosFilter;
import android.net.QosSession;
import android.net.ResolverParamsParcel;
import android.net.RouteInfo;
import android.net.RouteInfoParcel;
import android.net.SocketKeepalive;
import android.net.TransportInfo;
import android.net.UidRange;
import android.net.UidRangeParcel;
import android.net.UnderlyingNetworkInfo;
import android.net.Uri;
import android.net.VpnManager;
import android.net.VpnTransportInfo;
import android.net.metrics.IpConnectivityLog;
import android.net.shared.NetworkMonitorUtils;
import android.net.shared.PrivateDnsConfig;
import android.net.util.MultinetworkPolicyTracker;
import android.net.wifi.WifiInfo;
import android.os.BadParcelableException;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.SystemConfigManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.Credentials;
import android.security.KeyStore;
import android.system.Os;
import android.telephony.TelephonyManager;
import android.telephony.data.EpsBearerQosSessionAttributes;
import android.test.mock.MockContentResolver;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.IBatteryStats;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.WakeupMessage;
import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.net.module.util.ArrayTrackRecord;
import com.android.server.ConnectivityService.ConnectivityDiagnosticsCallbackInfo;
import com.android.server.connectivity.ConnectivityConstants;
import com.android.server.connectivity.MockableSystemProperties;
import com.android.server.connectivity.Nat464Xlat;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.connectivity.NetworkNotificationManager.NotificationType;
import com.android.server.connectivity.ProxyTracker;
import com.android.server.connectivity.QosCallbackTracker;
import com.android.server.connectivity.Vpn;
import com.android.server.net.NetworkPinner;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.testutils.ExceptionUtils;
import com.android.testutils.HandlerUtils;
import com.android.testutils.RecorderCallback.CallbackEntry;
import com.android.testutils.TestableNetworkCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.stubbing.Answer;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import kotlin.reflect.KClass;

/**
 * Tests for {@link ConnectivityService}.
 *
 * Build, install and run with:
 *  runtest frameworks-net -c com.android.server.ConnectivityServiceTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ConnectivityServiceTest {
    private static final String TAG = "ConnectivityServiceTest";

    private static final int TIMEOUT_MS = 500;
    private static final int TEST_LINGER_DELAY_MS = 400;
    private static final int TEST_NASCENT_DELAY_MS = 300;
    // Chosen to be less than the linger and nascent timeout. This ensures that we can distinguish
    // between a LOST callback that arrives immediately and a LOST callback that arrives after
    // the linger/nascent timeout. For this, our assertions should run fast enough to leave
    // less than (mService.mLingerDelayMs - TEST_CALLBACK_TIMEOUT_MS) between the time callbacks are
    // supposedly fired, and the time we call expectCallback.
    private static final int TEST_CALLBACK_TIMEOUT_MS = 250;
    // Chosen to be less than TEST_CALLBACK_TIMEOUT_MS. This ensures that requests have time to
    // complete before callbacks are verified.
    private static final int TEST_REQUEST_TIMEOUT_MS = 150;

    private static final int UNREASONABLY_LONG_ALARM_WAIT_MS = 1000;

    private static final long TIMESTAMP = 1234L;

    private static final int NET_ID = 110;
    private static final int OEM_PREF_ANY_NET_ID = -1;
    // Set a non-zero value to verify the flow to set tcp init rwnd value.
    private static final int TEST_TCP_INIT_RWND = 60;

    private static final String CLAT_PREFIX = "v4-";
    private static final String MOBILE_IFNAME = "test_rmnet_data0";
    private static final String WIFI_IFNAME = "test_wlan0";
    private static final String WIFI_WOL_IFNAME = "test_wlan_wol";
    private static final String VPN_IFNAME = "tun10042";
    private static final String TEST_PACKAGE_NAME = "com.android.test.package";
    private static final int TEST_PACKAGE_UID = 123;
    private static final String ALWAYS_ON_PACKAGE = "com.android.test.alwaysonvpn";

    private static final String INTERFACE_NAME = "interface";

    private static final String TEST_VENUE_URL_NA_PASSPOINT = "https://android.com/";
    private static final String TEST_VENUE_URL_NA_OTHER = "https://example.com/";
    private static final String TEST_TERMS_AND_CONDITIONS_URL_NA_PASSPOINT =
            "https://android.com/terms/";
    private static final String TEST_TERMS_AND_CONDITIONS_URL_NA_OTHER =
            "https://example.com/terms/";
    private static final String TEST_VENUE_URL_CAPPORT = "https://android.com/capport/";
    private static final String TEST_USER_PORTAL_API_URL_CAPPORT =
            "https://android.com/user/api/capport/";
    private static final String TEST_FRIENDLY_NAME = "Network friendly name";
    private static final String TEST_REDIRECT_URL = "http://example.com/firstPath";

    private MockContext mServiceContext;
    private HandlerThread mCsHandlerThread;
    private HandlerThread mVMSHandlerThread;
    private ConnectivityService.Dependencies mDeps;
    private ConnectivityService mService;
    private WrappedConnectivityManager mCm;
    private TestNetworkAgentWrapper mWiFiNetworkAgent;
    private TestNetworkAgentWrapper mCellNetworkAgent;
    private TestNetworkAgentWrapper mEthernetNetworkAgent;
    private MockVpn mMockVpn;
    private Context mContext;
    private INetworkPolicyListener mPolicyListener;
    private WrappedMultinetworkPolicyTracker mPolicyTracker;
    private HandlerThread mAlarmManagerThread;
    private TestNetIdManager mNetIdManager;
    private QosCallbackMockHelper mQosCallbackMockHelper;
    private QosCallbackTracker mQosCallbackTracker;
    private VpnManagerService mVpnManagerService;

    // State variables required to emulate NetworkPolicyManagerService behaviour.
    private int mUidRules = RULE_NONE;
    private boolean mRestrictBackground = false;

    @Mock DeviceIdleInternal mDeviceIdleInternal;
    @Mock INetworkManagementService mNetworkManagementService;
    @Mock INetworkStatsService mStatsService;
    @Mock IBatteryStats mBatteryStatsService;
    @Mock IDnsResolver mMockDnsResolver;
    @Mock INetd mMockNetd;
    @Mock NetworkStackClient mNetworkStack;
    @Mock PackageManager mPackageManager;
    @Mock UserManager mUserManager;
    @Mock NotificationManager mNotificationManager;
    @Mock AlarmManager mAlarmManager;
    @Mock IConnectivityDiagnosticsCallback mConnectivityDiagnosticsCallback;
    @Mock IBinder mIBinder;
    @Mock LocationManager mLocationManager;
    @Mock AppOpsManager mAppOpsManager;
    @Mock TelephonyManager mTelephonyManager;
    @Mock MockableSystemProperties mSystemProperties;
    @Mock EthernetManager mEthernetManager;
    @Mock NetworkPolicyManager mNetworkPolicyManager;
    @Mock KeyStore mKeyStore;
    @Mock SystemConfigManager mSystemConfigManager;

    private ArgumentCaptor<ResolverParamsParcel> mResolverParamsParcelCaptor =
            ArgumentCaptor.forClass(ResolverParamsParcel.class);

    // This class exists to test bindProcessToNetwork and getBoundNetworkForProcess. These methods
    // do not go through ConnectivityService but talk to netd directly, so they don't automatically
    // reflect the state of our test ConnectivityService.
    private class WrappedConnectivityManager extends ConnectivityManager {
        private Network mFakeBoundNetwork;

        public synchronized boolean bindProcessToNetwork(Network network) {
            mFakeBoundNetwork = network;
            return true;
        }

        public synchronized Network getBoundNetworkForProcess() {
            return mFakeBoundNetwork;
        }

        public WrappedConnectivityManager(Context context, ConnectivityService service) {
            super(context, service);
        }
    }

    private class MockContext extends BroadcastInterceptingContext {
        private final MockContentResolver mContentResolver;

        @Spy private Resources mResources;
        private final LinkedBlockingQueue<Intent> mStartedActivities = new LinkedBlockingQueue<>();

        // Map of permission name -> PermissionManager.Permission_{GRANTED|DENIED} constant
        private final HashMap<String, Integer> mMockedPermissions = new HashMap<>();

        MockContext(Context base, ContentProvider settingsProvider) {
            super(base);

            mResources = spy(base.getResources());
            when(mResources.getStringArray(com.android.internal.R.array.networkAttributes)).
                    thenReturn(new String[] {
                            "wifi,1,1,1,-1,true",
                            "mobile,0,0,0,-1,true",
                            "mobile_mms,2,0,2,60000,true",
                            "mobile_supl,3,0,2,60000,true",
                    });

            when(mResources.getStringArray(
                    com.android.internal.R.array.config_wakeonlan_supported_interfaces))
                    .thenReturn(new String[]{
                            WIFI_WOL_IFNAME,
                    });

            mContentResolver = new MockContentResolver();
            mContentResolver.addProvider(Settings.AUTHORITY, settingsProvider);
        }

        @Override
        public void startActivityAsUser(Intent intent, UserHandle handle) {
            mStartedActivities.offer(intent);
        }

        public Intent expectStartActivityIntent(int timeoutMs) {
            Intent intent = null;
            try {
                intent = mStartedActivities.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {}
            assertNotNull("Did not receive sign-in intent after " + timeoutMs + "ms", intent);
            return intent;
        }

        public void expectNoStartActivityIntent(int timeoutMs) {
            try {
                assertNull("Received unexpected Intent to start activity",
                        mStartedActivities.poll(timeoutMs, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {}
        }

        @Override
        public ComponentName startService(Intent service) {
            final String action = service.getAction();
            if (!VpnConfig.SERVICE_INTERFACE.equals(action)) {
                fail("Attempt to start unknown service, action=" + action);
            }
            return new ComponentName(service.getPackage(), "com.android.test.Service");
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.CONNECTIVITY_SERVICE.equals(name)) return mCm;
            if (Context.NOTIFICATION_SERVICE.equals(name)) return mNotificationManager;
            if (Context.USER_SERVICE.equals(name)) return mUserManager;
            if (Context.ALARM_SERVICE.equals(name)) return mAlarmManager;
            if (Context.LOCATION_SERVICE.equals(name)) return mLocationManager;
            if (Context.APP_OPS_SERVICE.equals(name)) return mAppOpsManager;
            if (Context.TELEPHONY_SERVICE.equals(name)) return mTelephonyManager;
            if (Context.ETHERNET_SERVICE.equals(name)) return mEthernetManager;
            if (Context.NETWORK_POLICY_SERVICE.equals(name)) return mNetworkPolicyManager;
            if (Context.SYSTEM_CONFIG_SERVICE.equals(name)) return mSystemConfigManager;
            return super.getSystemService(name);
        }

        @Override
        public Context createContextAsUser(UserHandle user, int flags) {
            final Context asUser = mock(Context.class, AdditionalAnswers.delegatesTo(this));
            doReturn(user).when(asUser).getUser();
            return asUser;
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }

        @Override
        public Resources getResources() {
            return mResources;
        }

        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        private int checkMockedPermission(String permission, Supplier<Integer> ifAbsent) {
            final Integer granted = mMockedPermissions.get(permission);
            return granted != null ? granted : ifAbsent.get();
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            return checkMockedPermission(
                    permission, () -> super.checkPermission(permission, pid, uid));
        }

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            return checkMockedPermission(
                    permission, () -> super.checkCallingOrSelfPermission(permission));
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            final Integer granted = mMockedPermissions.get(permission);
            if (granted == null) {
                super.enforceCallingOrSelfPermission(permission, message);
                return;
            }

            if (!granted.equals(PERMISSION_GRANTED)) {
                throw new SecurityException("[Test] permission denied: " + permission);
            }
        }

        /**
         * Mock checks for the specified permission, and have them behave as per {@code granted}.
         *
         * <p>Passing null reverts to default behavior, which does a real permission check on the
         * test package.
         * @param granted One of {@link PackageManager#PERMISSION_GRANTED} or
         *                {@link PackageManager#PERMISSION_DENIED}.
         */
        public void setPermission(String permission, Integer granted) {
            mMockedPermissions.put(permission, granted);
        }
    }

    private void waitForIdle() {
        HandlerUtils.waitForIdle(mCsHandlerThread, TIMEOUT_MS);
        waitForIdle(mCellNetworkAgent, TIMEOUT_MS);
        waitForIdle(mWiFiNetworkAgent, TIMEOUT_MS);
        waitForIdle(mEthernetNetworkAgent, TIMEOUT_MS);
        HandlerUtils.waitForIdle(mCsHandlerThread, TIMEOUT_MS);
        HandlerUtils.waitForIdle(ConnectivityThread.get(), TIMEOUT_MS);
    }

    private void waitForIdle(TestNetworkAgentWrapper agent, long timeoutMs) {
        if (agent == null) {
            return;
        }
        agent.waitForIdle(timeoutMs);
    }

    @Test
    public void testWaitForIdle() throws Exception {
        final int attempts = 50;  // Causes the test to take about 200ms on bullhead-eng.

        // Tests that waitForIdle returns immediately if the service is already idle.
        for (int i = 0; i < attempts; i++) {
            waitForIdle();
        }

        // Bring up a network that we can use to send messages to ConnectivityService.
        ExpectedBroadcast b = expectConnectivityAction(TYPE_WIFI, DetailedState.CONNECTED);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        b.expectBroadcast();
        Network n = mWiFiNetworkAgent.getNetwork();
        assertNotNull(n);

        // Tests that calling waitForIdle waits for messages to be processed.
        for (int i = 0; i < attempts; i++) {
            mWiFiNetworkAgent.setSignalStrength(i);
            waitForIdle();
            assertEquals(i, mCm.getNetworkCapabilities(n).getSignalStrength());
        }
    }

    // This test has an inherent race condition in it, and cannot be enabled for continuous testing
    // or presubmit tests. It is kept for manual runs and documentation purposes.
    @Ignore
    public void verifyThatNotWaitingForIdleCausesRaceConditions() throws Exception {
        // Bring up a network that we can use to send messages to ConnectivityService.
        ExpectedBroadcast b = expectConnectivityAction(TYPE_WIFI, DetailedState.CONNECTED);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        b.expectBroadcast();
        Network n = mWiFiNetworkAgent.getNetwork();
        assertNotNull(n);

        // Ensure that not calling waitForIdle causes a race condition.
        final int attempts = 50;  // Causes the test to take about 200ms on bullhead-eng.
        for (int i = 0; i < attempts; i++) {
            mWiFiNetworkAgent.setSignalStrength(i);
            if (i != mCm.getNetworkCapabilities(n).getSignalStrength()) {
                // We hit a race condition, as expected. Pass the test.
                return;
            }
        }

        // No race? There is a bug in this test.
        fail("expected race condition at least once in " + attempts + " attempts");
    }

    private class TestNetworkAgentWrapper extends NetworkAgentWrapper {
        private static final int VALIDATION_RESULT_INVALID = 0;

        private static final long DATA_STALL_TIMESTAMP = 10L;
        private static final int DATA_STALL_DETECTION_METHOD = 1;

        private INetworkMonitor mNetworkMonitor;
        private INetworkMonitorCallbacks mNmCallbacks;
        private int mNmValidationResult = VALIDATION_RESULT_INVALID;
        private int mProbesCompleted;
        private int mProbesSucceeded;
        private String mNmValidationRedirectUrl = null;
        private boolean mNmProvNotificationRequested = false;

        private final ConditionVariable mNetworkStatusReceived = new ConditionVariable();
        // Contains the redirectUrl from networkStatus(). Before reading, wait for
        // mNetworkStatusReceived.
        private String mRedirectUrl;

        TestNetworkAgentWrapper(int transport) throws Exception {
            this(transport, new LinkProperties(), null);
        }

        TestNetworkAgentWrapper(int transport, LinkProperties linkProperties)
                throws Exception {
            this(transport, linkProperties, null);
        }

        private TestNetworkAgentWrapper(int transport, LinkProperties linkProperties,
                NetworkCapabilities ncTemplate) throws Exception {
            super(transport, linkProperties, ncTemplate, mServiceContext);

            // Waits for the NetworkAgent to be registered, which includes the creation of the
            // NetworkMonitor.
            waitForIdle(TIMEOUT_MS);
            HandlerUtils.waitForIdle(mCsHandlerThread, TIMEOUT_MS);
            HandlerUtils.waitForIdle(ConnectivityThread.get(), TIMEOUT_MS);
        }

        @Override
        protected InstrumentedNetworkAgent makeNetworkAgent(LinkProperties linkProperties,
                final int type, final String typeName) throws Exception {
            mNetworkMonitor = mock(INetworkMonitor.class);

            final Answer validateAnswer = inv -> {
                new Thread(ignoreExceptions(this::onValidationRequested)).start();
                return null;
            };

            doAnswer(validateAnswer).when(mNetworkMonitor).notifyNetworkConnected(any(), any());
            doAnswer(validateAnswer).when(mNetworkMonitor).forceReevaluation(anyInt());

            final ArgumentCaptor<Network> nmNetworkCaptor = ArgumentCaptor.forClass(Network.class);
            final ArgumentCaptor<INetworkMonitorCallbacks> nmCbCaptor =
                    ArgumentCaptor.forClass(INetworkMonitorCallbacks.class);
            doNothing().when(mNetworkStack).makeNetworkMonitor(
                    nmNetworkCaptor.capture(),
                    any() /* name */,
                    nmCbCaptor.capture());

            final InstrumentedNetworkAgent na = new InstrumentedNetworkAgent(this, linkProperties,
                    type, typeName) {
                @Override
                public void networkStatus(int status, String redirectUrl) {
                    mRedirectUrl = redirectUrl;
                    mNetworkStatusReceived.open();
                }
            };

            assertEquals(na.getNetwork().netId, nmNetworkCaptor.getValue().netId);
            mNmCallbacks = nmCbCaptor.getValue();

            mNmCallbacks.onNetworkMonitorCreated(mNetworkMonitor);

            return na;
        }

        private void onValidationRequested() throws Exception {
            if (mNmProvNotificationRequested
                    && ((mNmValidationResult & NETWORK_VALIDATION_RESULT_VALID) != 0)) {
                mNmCallbacks.hideProvisioningNotification();
                mNmProvNotificationRequested = false;
            }

            mNmCallbacks.notifyProbeStatusChanged(mProbesCompleted, mProbesSucceeded);
            final NetworkTestResultParcelable p = new NetworkTestResultParcelable();
            p.result = mNmValidationResult;
            p.probesAttempted = mProbesCompleted;
            p.probesSucceeded = mProbesSucceeded;
            p.redirectUrl = mNmValidationRedirectUrl;
            p.timestampMillis = TIMESTAMP;
            mNmCallbacks.notifyNetworkTestedWithExtras(p);

            if (mNmValidationRedirectUrl != null) {
                mNmCallbacks.showProvisioningNotification(
                        "test_provisioning_notif_action", TEST_PACKAGE_NAME);
                mNmProvNotificationRequested = true;
            }
        }

        /**
         * Connect without adding any internet capability.
         */
        public void connectWithoutInternet() {
            super.connect();
        }

        /**
         * Transition this NetworkAgent to CONNECTED state with NET_CAPABILITY_INTERNET.
         * @param validated Indicate if network should pretend to be validated.
         */
        public void connect(boolean validated) {
            connect(validated, true, false /* isStrictMode */);
        }

        /**
         * Transition this NetworkAgent to CONNECTED state.
         * @param validated Indicate if network should pretend to be validated.
         * @param hasInternet Indicate if network should pretend to have NET_CAPABILITY_INTERNET.
         */
        public void connect(boolean validated, boolean hasInternet, boolean isStrictMode) {
            assertFalse(getNetworkCapabilities().hasCapability(NET_CAPABILITY_INTERNET));

            ConnectivityManager.NetworkCallback callback = null;
            final ConditionVariable validatedCv = new ConditionVariable();
            if (validated) {
                setNetworkValid(isStrictMode);
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(getNetworkCapabilities().getTransportTypes()[0])
                        .clearCapabilities()
                        .build();
                callback = new ConnectivityManager.NetworkCallback() {
                    public void onCapabilitiesChanged(Network network,
                            NetworkCapabilities networkCapabilities) {
                        if (network.equals(getNetwork()) &&
                                networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED)) {
                            validatedCv.open();
                        }
                    }
                };
                mCm.registerNetworkCallback(request, callback);
            }
            if (hasInternet) {
                addCapability(NET_CAPABILITY_INTERNET);
            }

            connectWithoutInternet();

            if (validated) {
                // Wait for network to validate.
                waitFor(validatedCv);
                setNetworkInvalid(isStrictMode);
            }

            if (callback != null) mCm.unregisterNetworkCallback(callback);
        }

        public void connectWithCaptivePortal(String redirectUrl, boolean isStrictMode) {
            setNetworkPortal(redirectUrl, isStrictMode);
            connect(false, true /* hasInternet */, isStrictMode);
        }

        public void connectWithPartialConnectivity() {
            setNetworkPartial();
            connect(false);
        }

        public void connectWithPartialValidConnectivity(boolean isStrictMode) {
            setNetworkPartialValid(isStrictMode);
            connect(false, true /* hasInternet */, isStrictMode);
        }

        void setNetworkValid(boolean isStrictMode) {
            mNmValidationResult = NETWORK_VALIDATION_RESULT_VALID;
            mNmValidationRedirectUrl = null;
            int probesSucceeded = NETWORK_VALIDATION_PROBE_DNS | NETWORK_VALIDATION_PROBE_HTTPS;
            if (isStrictMode) {
                probesSucceeded |= NETWORK_VALIDATION_PROBE_PRIVDNS;
            }
            // The probesCompleted equals to probesSucceeded for the case of valid network, so put
            // the same value into two different parameter of the method.
            setProbesStatus(probesSucceeded, probesSucceeded);
        }

        void setNetworkInvalid(boolean isStrictMode) {
            mNmValidationResult = VALIDATION_RESULT_INVALID;
            mNmValidationRedirectUrl = null;
            int probesCompleted = NETWORK_VALIDATION_PROBE_DNS | NETWORK_VALIDATION_PROBE_HTTPS
                    | NETWORK_VALIDATION_PROBE_HTTP;
            int probesSucceeded = 0;
            // If the isStrictMode is true, it means the network is invalid when NetworkMonitor
            // tried to validate the private DNS but failed.
            if (isStrictMode) {
                probesCompleted &= ~NETWORK_VALIDATION_PROBE_HTTP;
                probesSucceeded = probesCompleted;
                probesCompleted |= NETWORK_VALIDATION_PROBE_PRIVDNS;
            }
            setProbesStatus(probesCompleted, probesSucceeded);
        }

        void setNetworkPortal(String redirectUrl, boolean isStrictMode) {
            setNetworkInvalid(isStrictMode);
            mNmValidationRedirectUrl = redirectUrl;
            // Suppose the portal is found when NetworkMonitor probes NETWORK_VALIDATION_PROBE_HTTP
            // in the beginning, so the NETWORK_VALIDATION_PROBE_HTTPS hasn't probed yet.
            int probesCompleted = NETWORK_VALIDATION_PROBE_DNS | NETWORK_VALIDATION_PROBE_HTTP;
            int probesSucceeded = VALIDATION_RESULT_INVALID;
            if (isStrictMode) {
                probesCompleted |= NETWORK_VALIDATION_PROBE_PRIVDNS;
            }
            setProbesStatus(probesCompleted, probesSucceeded);
        }

        void setNetworkPartial() {
            mNmValidationResult = NETWORK_VALIDATION_RESULT_PARTIAL;
            mNmValidationRedirectUrl = null;
            int probesCompleted = NETWORK_VALIDATION_PROBE_DNS | NETWORK_VALIDATION_PROBE_HTTPS
                    | NETWORK_VALIDATION_PROBE_FALLBACK;
            int probesSucceeded = NETWORK_VALIDATION_PROBE_DNS | NETWORK_VALIDATION_PROBE_FALLBACK;
            setProbesStatus(probesCompleted, probesSucceeded);
        }

        void setNetworkPartialValid(boolean isStrictMode) {
            setNetworkPartial();
            mNmValidationResult |= NETWORK_VALIDATION_RESULT_VALID;
            int probesCompleted = NETWORK_VALIDATION_PROBE_DNS | NETWORK_VALIDATION_PROBE_HTTPS
                    | NETWORK_VALIDATION_PROBE_HTTP;
            int probesSucceeded = NETWORK_VALIDATION_PROBE_DNS | NETWORK_VALIDATION_PROBE_HTTP;
            // Suppose the partial network cannot pass the private DNS validation as well, so only
            // add NETWORK_VALIDATION_PROBE_DNS in probesCompleted but not probesSucceeded.
            if (isStrictMode) {
                probesCompleted |= NETWORK_VALIDATION_PROBE_PRIVDNS;
            }
            setProbesStatus(probesCompleted, probesSucceeded);
        }

        void setProbesStatus(int probesCompleted, int probesSucceeded) {
            mProbesCompleted = probesCompleted;
            mProbesSucceeded = probesSucceeded;
        }

        void notifyCapportApiDataChanged(CaptivePortalData data) {
            try {
                mNmCallbacks.notifyCaptivePortalDataChanged(data);
            } catch (RemoteException e) {
                throw new AssertionError("This cannot happen", e);
            }
        }

        public String waitForRedirectUrl() {
            assertTrue(mNetworkStatusReceived.block(TIMEOUT_MS));
            return mRedirectUrl;
        }

        public void expectDisconnected() {
            expectDisconnected(TIMEOUT_MS);
        }

        public void expectPreventReconnectReceived() {
            expectPreventReconnectReceived(TIMEOUT_MS);
        }

        void notifyDataStallSuspected() throws Exception {
            final DataStallReportParcelable p = new DataStallReportParcelable();
            p.detectionMethod = DATA_STALL_DETECTION_METHOD;
            p.timestampMillis = DATA_STALL_TIMESTAMP;
            mNmCallbacks.notifyDataStallSuspected(p);
        }
    }

    /**
     * A NetworkFactory that allows to wait until any in-flight NetworkRequest add or remove
     * operations have been processed and test for them.
     */
    private static class MockNetworkFactory extends NetworkFactory {
        private final ConditionVariable mNetworkStartedCV = new ConditionVariable();
        private final ConditionVariable mNetworkStoppedCV = new ConditionVariable();
        private final AtomicBoolean mNetworkStarted = new AtomicBoolean(false);

        static class RequestEntry {
            @NonNull
            public final NetworkRequest request;

            RequestEntry(@NonNull final NetworkRequest request) {
                this.request = request;
            }

            static final class Add extends RequestEntry {
                public final int factorySerialNumber;

                Add(@NonNull final NetworkRequest request, final int factorySerialNumber) {
                    super(request);
                    this.factorySerialNumber = factorySerialNumber;
                }
            }

            static final class Remove extends RequestEntry {
                Remove(@NonNull final NetworkRequest request) {
                    super(request);
                }
            }
        }

        // History of received requests adds and removes.
        private final ArrayTrackRecord<RequestEntry>.ReadHead mRequestHistory =
                new ArrayTrackRecord<RequestEntry>().newReadHead();

        private static <T> T failIfNull(@Nullable final T obj, @Nullable final String message) {
            if (null == obj) fail(null != message ? message : "Must not be null");
            return obj;
        }


        public RequestEntry.Add expectRequestAdd() {
            return failIfNull((RequestEntry.Add) mRequestHistory.poll(TIMEOUT_MS,
                    it -> it instanceof RequestEntry.Add), "Expected request add");
        }

        public void expectRequestAdds(final int count) {
            for (int i = count; i > 0; --i) {
                expectRequestAdd();
            }
        }

        public RequestEntry.Remove expectRequestRemove() {
            return failIfNull((RequestEntry.Remove) mRequestHistory.poll(TIMEOUT_MS,
                    it -> it instanceof RequestEntry.Remove), "Expected request remove");
        }

        public void expectRequestRemoves(final int count) {
            for (int i = count; i > 0; --i) {
                expectRequestRemove();
            }
        }

        // Used to collect the networks requests managed by this factory. This is a duplicate of
        // the internal information stored in the NetworkFactory (which is private).
        private SparseArray<NetworkRequest> mNetworkRequests = new SparseArray<>();
        private final HandlerThread mHandlerSendingRequests;

        public MockNetworkFactory(Looper looper, Context context, String logTag,
                NetworkCapabilities filter, HandlerThread threadSendingRequests) {
            super(looper, context, logTag, filter);
            mHandlerSendingRequests = threadSendingRequests;
        }

        public int getMyRequestCount() {
            return getRequestCount();
        }

        protected void startNetwork() {
            mNetworkStarted.set(true);
            mNetworkStartedCV.open();
        }

        protected void stopNetwork() {
            mNetworkStarted.set(false);
            mNetworkStoppedCV.open();
        }

        public boolean getMyStartRequested() {
            return mNetworkStarted.get();
        }

        public ConditionVariable getNetworkStartedCV() {
            mNetworkStartedCV.close();
            return mNetworkStartedCV;
        }

        public ConditionVariable getNetworkStoppedCV() {
            mNetworkStoppedCV.close();
            return mNetworkStoppedCV;
        }

        @Override
        protected void handleAddRequest(NetworkRequest request, int score,
                int factorySerialNumber) {
            mNetworkRequests.put(request.requestId, request);
            super.handleAddRequest(request, score, factorySerialNumber);
            mRequestHistory.add(new RequestEntry.Add(request, factorySerialNumber));
        }

        @Override
        protected void handleRemoveRequest(NetworkRequest request) {
            mNetworkRequests.remove(request.requestId);
            super.handleRemoveRequest(request);
            mRequestHistory.add(new RequestEntry.Remove(request));
        }

        public void assertRequestCountEquals(final int count) {
            assertEquals(count, getMyRequestCount());
        }

        @Override
        public void terminate() {
            super.terminate();
            // Make sure there are no remaining requests unaccounted for.
            HandlerUtils.waitForIdle(mHandlerSendingRequests, TIMEOUT_MS);
            assertNull(mRequestHistory.poll(0, r -> true));
        }

        // Trigger releasing the request as unfulfillable
        public void triggerUnfulfillable(NetworkRequest r) {
            super.releaseRequestAsUnfulfillableByAnyFactory(r);
        }
    }

    private Set<UidRange> uidRangesForUid(int uid) {
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        return ranges;
    }

    private static Looper startHandlerThreadAndReturnLooper() {
        final HandlerThread handlerThread = new HandlerThread("MockVpnThread");
        handlerThread.start();
        return handlerThread.getLooper();
    }

    private class MockVpn extends Vpn implements TestableNetworkCallback.HasNetwork {
        // Careful ! This is different from mNetworkAgent, because MockNetworkAgent does
        // not inherit from NetworkAgent.
        private TestNetworkAgentWrapper mMockNetworkAgent;
        private boolean mAgentRegistered = false;

        private int mVpnType = VpnManager.TYPE_VPN_SERVICE;
        private UnderlyingNetworkInfo mUnderlyingNetworkInfo;

        // These ConditionVariables allow tests to wait for LegacyVpnRunner to be stopped/started.
        // TODO: this scheme is ad-hoc and error-prone because it does not fail if, for example, the
        // test expects two starts in a row, or even if the production code calls start twice in a
        // row. find a better solution. Simply putting a method to create a LegacyVpnRunner into
        // Vpn.Dependencies doesn't work because LegacyVpnRunner is not a static class and has
        // extensive access into the internals of Vpn.
        private ConditionVariable mStartLegacyVpnCv = new ConditionVariable();
        private ConditionVariable mStopVpnRunnerCv = new ConditionVariable();

        public MockVpn(int userId) {
            super(startHandlerThreadAndReturnLooper(), mServiceContext,
                    new Dependencies() {
                        @Override
                        public boolean isCallerSystem() {
                            return true;
                        }

                        @Override
                        public DeviceIdleInternal getDeviceIdleInternal() {
                            return mDeviceIdleInternal;
                        }
                    },
                    mNetworkManagementService, mMockNetd, userId, mKeyStore);
        }

        public void setUids(Set<UidRange> uids) {
            mNetworkCapabilities.setUids(uids);
            if (mAgentRegistered) {
                mMockNetworkAgent.setNetworkCapabilities(mNetworkCapabilities, true);
            }
        }

        public void setVpnType(int vpnType) {
            mVpnType = vpnType;
        }

        @Override
        public Network getNetwork() {
            return (mMockNetworkAgent == null) ? null : mMockNetworkAgent.getNetwork();
        }

        @Override
        public int getNetId() {
            return (mMockNetworkAgent == null) ? NETID_UNSET : mMockNetworkAgent.getNetwork().netId;
        }

        @Override
        public int getActiveVpnType() {
            return mVpnType;
        }

        private LinkProperties makeLinkProperties() {
            final LinkProperties lp = new LinkProperties();
            lp.setInterfaceName(VPN_IFNAME);
            return lp;
        }

        private void registerAgent(boolean isAlwaysMetered, Set<UidRange> uids, LinkProperties lp)
                throws Exception {
            if (mAgentRegistered) throw new IllegalStateException("already registered");
            updateState(NetworkInfo.DetailedState.CONNECTING, "registerAgent");
            mConfig = new VpnConfig();
            setUids(uids);
            if (!isAlwaysMetered) mNetworkCapabilities.addCapability(NET_CAPABILITY_NOT_METERED);
            mInterface = VPN_IFNAME;
            mNetworkCapabilities.setTransportInfo(new VpnTransportInfo(getActiveVpnType()));
            mMockNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_VPN, lp,
                    mNetworkCapabilities);
            mMockNetworkAgent.waitForIdle(TIMEOUT_MS);

            verify(mMockNetd, times(1)).networkAddUidRanges(eq(mMockVpn.getNetId()),
                    eq(toUidRangeStableParcels(uids)));
            verify(mMockNetd, never())
                    .networkRemoveUidRanges(eq(mMockVpn.getNetId()), any());
            mAgentRegistered = true;
            updateState(NetworkInfo.DetailedState.CONNECTED, "registerAgent");
            mNetworkCapabilities.set(mMockNetworkAgent.getNetworkCapabilities());
            mNetworkAgent = mMockNetworkAgent.getNetworkAgent();
        }

        private void registerAgent(Set<UidRange> uids) throws Exception {
            registerAgent(false /* isAlwaysMetered */, uids, makeLinkProperties());
        }

        private void connect(boolean validated, boolean hasInternet, boolean isStrictMode) {
            mMockNetworkAgent.connect(validated, hasInternet, isStrictMode);
        }

        private void connect(boolean validated) {
            mMockNetworkAgent.connect(validated);
        }

        private TestNetworkAgentWrapper getAgent() {
            return mMockNetworkAgent;
        }

        public void establish(LinkProperties lp, int uid, Set<UidRange> ranges, boolean validated,
                boolean hasInternet, boolean isStrictMode) throws Exception {
            mNetworkCapabilities.setOwnerUid(uid);
            mNetworkCapabilities.setAdministratorUids(new int[]{uid});
            registerAgent(false, ranges, lp);
            connect(validated, hasInternet, isStrictMode);
            waitForIdle();
        }

        public void establish(LinkProperties lp, int uid, Set<UidRange> ranges) throws Exception {
            establish(lp, uid, ranges, true, true, false);
        }

        public void establishForMyUid(LinkProperties lp) throws Exception {
            final int uid = Process.myUid();
            establish(lp, uid, uidRangesForUid(uid), true, true, false);
        }

        public void establishForMyUid(boolean validated, boolean hasInternet, boolean isStrictMode)
                throws Exception {
            final int uid = Process.myUid();
            establish(makeLinkProperties(), uid, uidRangesForUid(uid), validated, hasInternet,
                    isStrictMode);
        }

        public void establishForMyUid() throws Exception {
            establishForMyUid(makeLinkProperties());
        }

        public void sendLinkProperties(LinkProperties lp) {
            mMockNetworkAgent.sendLinkProperties(lp);
        }

        public void disconnect() {
            if (mMockNetworkAgent != null) {
                mMockNetworkAgent.disconnect();
                updateState(NetworkInfo.DetailedState.DISCONNECTED, "disconnect");
            }
            mAgentRegistered = false;
            setUids(null);
            // Remove NET_CAPABILITY_INTERNET or MockNetworkAgent will refuse to connect later on.
            mNetworkCapabilities.removeCapability(NET_CAPABILITY_INTERNET);
            mInterface = null;
        }

        @Override
        public void startLegacyVpnRunner() {
            mStartLegacyVpnCv.open();
        }

        public void expectStartLegacyVpnRunner() {
            assertTrue("startLegacyVpnRunner not called after " + TIMEOUT_MS + " ms",
                    mStartLegacyVpnCv.block(TIMEOUT_MS));

            // startLegacyVpn calls stopVpnRunnerPrivileged, which will open mStopVpnRunnerCv, just
            // before calling startLegacyVpnRunner. Restore mStopVpnRunnerCv, so the test can expect
            // that the VpnRunner is stopped and immediately restarted by calling
            // expectStartLegacyVpnRunner() and expectStopVpnRunnerPrivileged() back-to-back.
            mStopVpnRunnerCv = new ConditionVariable();
        }

        @Override
        public void stopVpnRunnerPrivileged() {
            if (mVpnRunner != null) {
                super.stopVpnRunnerPrivileged();
                disconnect();
                mStartLegacyVpnCv = new ConditionVariable();
            }
            mVpnRunner = null;
            mStopVpnRunnerCv.open();
        }

        public void expectStopVpnRunnerPrivileged() {
            assertTrue("stopVpnRunnerPrivileged not called after " + TIMEOUT_MS + " ms",
                    mStopVpnRunnerCv.block(TIMEOUT_MS));
        }

        @Override
        public synchronized UnderlyingNetworkInfo getUnderlyingNetworkInfo() {
            if (mUnderlyingNetworkInfo != null) return mUnderlyingNetworkInfo;

            return super.getUnderlyingNetworkInfo();
        }

        private synchronized void setUnderlyingNetworkInfo(
                UnderlyingNetworkInfo underlyingNetworkInfo) {
            mUnderlyingNetworkInfo = underlyingNetworkInfo;
        }
    }

    private UidRangeParcel[] toUidRangeStableParcels(final @NonNull Set<UidRange> ranges) {
        return ranges.stream().map(
                r -> new UidRangeParcel(r.start, r.stop)).toArray(UidRangeParcel[]::new);
    }

    private VpnManagerService makeVpnManagerService() {
        final VpnManagerService.Dependencies deps = new VpnManagerService.Dependencies() {
            public int getCallingUid() {
                return mDeps.getCallingUid();
            }

            public HandlerThread makeHandlerThread() {
                return mVMSHandlerThread;
            }

            public KeyStore getKeyStore() {
                return mKeyStore;
            }

            public INetd getNetd() {
                return mMockNetd;
            }

            public INetworkManagementService getINetworkManagementService() {
                return mNetworkManagementService;
            }
        };
        return new VpnManagerService(mServiceContext, deps);
    }

    private void assertVpnTransportInfo(NetworkCapabilities nc, int type) {
        assertNotNull(nc);
        final TransportInfo ti = nc.getTransportInfo();
        assertTrue("VPN TransportInfo is not a VpnTransportInfo: " + ti,
                ti instanceof VpnTransportInfo);
        assertEquals(type, ((VpnTransportInfo) ti).type);

    }

    private void processBroadcastForVpn(Intent intent) {
        mServiceContext.sendBroadcast(intent);
        HandlerUtils.waitForIdle(mVMSHandlerThread, TIMEOUT_MS);
        waitForIdle();
    }

    private void mockVpn(int uid) {
        synchronized (mVpnManagerService.mVpns) {
            int userId = UserHandle.getUserId(uid);
            mMockVpn = new MockVpn(userId);
            // Every running user always has a Vpn in the mVpns array, even if no VPN is running.
            mVpnManagerService.mVpns.put(userId, mMockVpn);
        }
    }

    private void mockUidNetworkingBlocked() {
        doAnswer(i -> mContext.getSystemService(NetworkPolicyManager.class)
                .checkUidNetworkingBlocked(i.getArgument(0) /* uid */, mUidRules,
                        i.getArgument(1) /* metered */, mRestrictBackground)
        ).when(mNetworkPolicyManager).isUidNetworkingBlocked(anyInt(), anyBoolean());

        doAnswer(inv -> mContext.getSystemService(NetworkPolicyManager.class)
                .checkUidNetworkingBlocked(inv.getArgument(0) /* uid */,
                        inv.getArgument(1) /* uidRules */,
                        inv.getArgument(2) /* isNetworkMetered */,
                        inv.getArgument(3) /* isBackgroundRestricted */)
        ).when(mNetworkPolicyManager).checkUidNetworkingBlocked(
                anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }

    private void setUidRulesChanged(int uidRules) throws RemoteException {
        mUidRules = uidRules;
        mPolicyListener.onUidRulesChanged(Process.myUid(), mUidRules);
    }

    private void setRestrictBackgroundChanged(boolean restrictBackground) throws RemoteException {
        mRestrictBackground = restrictBackground;
        mPolicyListener.onRestrictBackgroundChanged(mRestrictBackground);
    }

    private Nat464Xlat getNat464Xlat(NetworkAgentWrapper mna) {
        return mService.getNetworkAgentInfoForNetwork(mna.getNetwork()).clatd;
    }

    private static class WrappedMultinetworkPolicyTracker extends MultinetworkPolicyTracker {
        volatile boolean mConfigRestrictsAvoidBadWifi;
        volatile int mConfigMeteredMultipathPreference;

        WrappedMultinetworkPolicyTracker(Context c, Handler h, Runnable r) {
            super(c, h, r);
        }

        @Override
        public boolean configRestrictsAvoidBadWifi() {
            return mConfigRestrictsAvoidBadWifi;
        }

        @Override
        public int configMeteredMultipathPreference() {
            return mConfigMeteredMultipathPreference;
        }
    }

    /**
     * Wait up to TIMEOUT_MS for {@code conditionVariable} to open.
     * Fails if TIMEOUT_MS goes by before {@code conditionVariable} opens.
     */
    static private void waitFor(ConditionVariable conditionVariable) {
        if (conditionVariable.block(TIMEOUT_MS)) {
            return;
        }
        fail("ConditionVariable was blocked for more than " + TIMEOUT_MS + "ms");
    }

    private void registerNetworkCallbackAsUid(NetworkRequest request, NetworkCallback callback,
            int uid) {
        when(mDeps.getCallingUid()).thenReturn(uid);
        try {
            mCm.registerNetworkCallback(request, callback);
            waitForIdle();
        } finally {
            returnRealCallingUid();
        }
    }

    private static final int PRIMARY_USER = 0;
    private static final int APP1_UID = UserHandle.getUid(PRIMARY_USER, 10100);
    private static final int APP2_UID = UserHandle.getUid(PRIMARY_USER, 10101);
    private static final int VPN_UID = UserHandle.getUid(PRIMARY_USER, 10043);
    private static final UserInfo PRIMARY_USER_INFO = new UserInfo(PRIMARY_USER, "",
            UserInfo.FLAG_PRIMARY);

    private static final int RESTRICTED_USER = 1;
    private static final UserInfo RESTRICTED_USER_INFO = new UserInfo(RESTRICTED_USER, "",
            UserInfo.FLAG_RESTRICTED);
    static {
        RESTRICTED_USER_INFO.restrictedProfileParentId = PRIMARY_USER;
    }

    @Before
    public void setUp() throws Exception {
        mNetIdManager = new TestNetIdManager();

        mContext = InstrumentationRegistry.getContext();

        MockitoAnnotations.initMocks(this);

        when(mUserManager.getAliveUsers()).thenReturn(Arrays.asList(PRIMARY_USER_INFO));
        when(mUserManager.getUserInfo(PRIMARY_USER)).thenReturn(PRIMARY_USER_INFO);
        // canHaveRestrictedProfile does not take a userId. It applies to the userId of the context
        // it was started from, i.e., PRIMARY_USER.
        when(mUserManager.canHaveRestrictedProfile()).thenReturn(true);
        when(mUserManager.getUserInfo(RESTRICTED_USER)).thenReturn(RESTRICTED_USER_INFO);

        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.targetSdkVersion = Build.VERSION_CODES.Q;
        when(mPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), any()))
                .thenReturn(applicationInfo);
        when(mSystemConfigManager.getSystemPermissionUids(anyString())).thenReturn(new int[0]);

        // InstrumentationTestRunner prepares a looper, but AndroidJUnitRunner does not.
        // http://b/25897652 .
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        mockDefaultPackages();

        FakeSettingsProvider.clearSettingsProvider();
        mServiceContext = new MockContext(InstrumentationRegistry.getContext(),
                new FakeSettingsProvider());
        mServiceContext.setUseRegisteredHandlers(true);
        LocalServices.removeServiceForTest(NetworkPolicyManagerInternal.class);
        LocalServices.addService(
                NetworkPolicyManagerInternal.class, mock(NetworkPolicyManagerInternal.class));

        mAlarmManagerThread = new HandlerThread("TestAlarmManager");
        mAlarmManagerThread.start();
        initAlarmManager(mAlarmManager, mAlarmManagerThread.getThreadHandler());

        mCsHandlerThread = new HandlerThread("TestConnectivityService");
        mVMSHandlerThread = new HandlerThread("TestVpnManagerService");
        mDeps = makeDependencies();
        returnRealCallingUid();
        mService = new ConnectivityService(mServiceContext,
                mStatsService,
                mMockDnsResolver,
                mock(IpConnectivityLog.class),
                mMockNetd,
                mDeps);
        mService.mLingerDelayMs = TEST_LINGER_DELAY_MS;
        mService.mNascentDelayMs = TEST_NASCENT_DELAY_MS;
        verify(mDeps).makeMultinetworkPolicyTracker(any(), any(), any());

        final ArgumentCaptor<INetworkPolicyListener> policyListenerCaptor =
                ArgumentCaptor.forClass(INetworkPolicyListener.class);
        verify(mNetworkPolicyManager).registerListener(policyListenerCaptor.capture());
        mPolicyListener = policyListenerCaptor.getValue();

        // Create local CM before sending system ready so that we can answer
        // getSystemService() correctly.
        mCm = new WrappedConnectivityManager(InstrumentationRegistry.getContext(), mService);
        mService.systemReadyInternal();
        mVpnManagerService = makeVpnManagerService();
        mVpnManagerService.systemReady();
        mockVpn(Process.myUid());
        mCm.bindProcessToNetwork(null);
        mQosCallbackTracker = mock(QosCallbackTracker.class);

        // Ensure that the default setting for Captive Portals is used for most tests
        setCaptivePortalMode(Settings.Global.CAPTIVE_PORTAL_MODE_PROMPT);
        setAlwaysOnNetworks(false);
        setPrivateDnsSettings(PRIVATE_DNS_MODE_OFF, "ignored.example.com");
    }

    private void returnRealCallingUid() {
        doAnswer((invocationOnMock) -> Binder.getCallingUid()).when(mDeps).getCallingUid();
    }

    private ConnectivityService.Dependencies makeDependencies() {
        doReturn(TEST_TCP_INIT_RWND).when(mSystemProperties)
                .getInt("net.tcp.default_init_rwnd", 0);
        doReturn(false).when(mSystemProperties).getBoolean("ro.radio.noril", false);
        doNothing().when(mSystemProperties).setTcpInitRwnd(anyInt());
        final ConnectivityService.Dependencies deps = mock(ConnectivityService.Dependencies.class);
        doReturn(mCsHandlerThread).when(deps).makeHandlerThread();
        doReturn(mNetIdManager).when(deps).makeNetIdManager();
        doReturn(mNetworkStack).when(deps).getNetworkStack();
        doReturn(mSystemProperties).when(deps).getSystemProperties();
        doReturn(mock(ProxyTracker.class)).when(deps).makeProxyTracker(any(), any());
        doReturn(true).when(deps).queryUserAccess(anyInt(), anyInt());
        doReturn(mBatteryStatsService).when(deps).getBatteryStatsService();
        doAnswer(inv -> {
            mPolicyTracker = new WrappedMultinetworkPolicyTracker(
                    inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            return mPolicyTracker;
        }).when(deps).makeMultinetworkPolicyTracker(any(), any(), any());

        return deps;
    }

    private static void initAlarmManager(final AlarmManager am, final Handler alarmHandler) {
        doAnswer(inv -> {
            final long when = inv.getArgument(1);
            final WakeupMessage wakeupMsg = inv.getArgument(3);
            final Handler handler = inv.getArgument(4);

            long delayMs = when - SystemClock.elapsedRealtime();
            if (delayMs < 0) delayMs = 0;
            if (delayMs > UNREASONABLY_LONG_ALARM_WAIT_MS) {
                fail("Attempting to send msg more than " + UNREASONABLY_LONG_ALARM_WAIT_MS
                        + "ms into the future: " + delayMs);
            }
            alarmHandler.postDelayed(() -> handler.post(wakeupMsg::onAlarm), wakeupMsg /* token */,
                    delayMs);

            return null;
        }).when(am).setExact(eq(AlarmManager.ELAPSED_REALTIME_WAKEUP), anyLong(), anyString(),
                any(WakeupMessage.class), any());

        doAnswer(inv -> {
            final WakeupMessage wakeupMsg = inv.getArgument(0);
            alarmHandler.removeCallbacksAndMessages(wakeupMsg /* token */);
            return null;
        }).when(am).cancel(any(WakeupMessage.class));
    }

    @After
    public void tearDown() throws Exception {
        setAlwaysOnNetworks(false);
        if (mCellNetworkAgent != null) {
            mCellNetworkAgent.disconnect();
            mCellNetworkAgent = null;
        }
        if (mWiFiNetworkAgent != null) {
            mWiFiNetworkAgent.disconnect();
            mWiFiNetworkAgent = null;
        }
        if (mEthernetNetworkAgent != null) {
            mEthernetNetworkAgent.disconnect();
            mEthernetNetworkAgent = null;
        }

        if (mQosCallbackMockHelper != null) {
            mQosCallbackMockHelper.tearDown();
            mQosCallbackMockHelper = null;
        }
        mMockVpn.disconnect();
        waitForIdle();

        FakeSettingsProvider.clearSettingsProvider();

        mCsHandlerThread.quitSafely();
        mAlarmManagerThread.quitSafely();
    }

    private void mockDefaultPackages() throws Exception {
        final String myPackageName = mContext.getPackageName();
        final PackageInfo myPackageInfo = mContext.getPackageManager().getPackageInfo(
                myPackageName, PackageManager.GET_PERMISSIONS);
        when(mPackageManager.getPackagesForUid(Binder.getCallingUid())).thenReturn(
                new String[] {myPackageName});
        when(mPackageManager.getPackageInfoAsUser(eq(myPackageName), anyInt(),
                eq(UserHandle.getCallingUserId()))).thenReturn(myPackageInfo);

        when(mPackageManager.getInstalledPackages(eq(GET_PERMISSIONS | MATCH_ANY_USER))).thenReturn(
                Arrays.asList(new PackageInfo[] {
                        buildPackageInfo(/* SYSTEM */ false, APP1_UID),
                        buildPackageInfo(/* SYSTEM */ false, APP2_UID),
                        buildPackageInfo(/* SYSTEM */ false, VPN_UID)
                }));

        // Create a fake always-on VPN package.
        final int userId = UserHandle.getCallingUserId();
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.targetSdkVersion = Build.VERSION_CODES.R;  // Always-on supported in N+.
        when(mPackageManager.getApplicationInfoAsUser(eq(ALWAYS_ON_PACKAGE), anyInt(),
                eq(userId))).thenReturn(applicationInfo);

        // Minimal mocking to keep Vpn#isAlwaysOnPackageSupported happy.
        ResolveInfo rInfo = new ResolveInfo();
        rInfo.serviceInfo = new ServiceInfo();
        rInfo.serviceInfo.metaData = new Bundle();
        final List<ResolveInfo> services = Arrays.asList(new ResolveInfo[]{rInfo});
        when(mPackageManager.queryIntentServicesAsUser(any(), eq(PackageManager.GET_META_DATA),
                eq(userId))).thenReturn(services);
        when(mPackageManager.getPackageUidAsUser(TEST_PACKAGE_NAME, userId))
                .thenReturn(Process.myUid());
        when(mPackageManager.getPackageUidAsUser(ALWAYS_ON_PACKAGE, userId))
                .thenReturn(VPN_UID);
    }

    private void verifyActiveNetwork(int transport) {
        // Test getActiveNetworkInfo()
        assertNotNull(mCm.getActiveNetworkInfo());
        assertEquals(transportToLegacyType(transport), mCm.getActiveNetworkInfo().getType());
        // Test getActiveNetwork()
        assertNotNull(mCm.getActiveNetwork());
        assertEquals(mCm.getActiveNetwork(), mCm.getActiveNetworkForUid(Process.myUid()));
        if (!NetworkCapabilities.isValidTransport(transport)) {
            throw new IllegalStateException("Unknown transport " + transport);
        }
        switch (transport) {
            case TRANSPORT_WIFI:
                assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
                break;
            case TRANSPORT_CELLULAR:
                assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
                break;
            case TRANSPORT_ETHERNET:
                assertEquals(mEthernetNetworkAgent.getNetwork(), mCm.getActiveNetwork());
                break;
            default:
                break;
        }
        // Test getNetworkInfo(Network)
        assertNotNull(mCm.getNetworkInfo(mCm.getActiveNetwork()));
        assertEquals(transportToLegacyType(transport),
                mCm.getNetworkInfo(mCm.getActiveNetwork()).getType());
        assertNotNull(mCm.getActiveNetworkInfoForUid(Process.myUid()));
        // Test getNetworkCapabilities(Network)
        assertNotNull(mCm.getNetworkCapabilities(mCm.getActiveNetwork()));
        assertTrue(mCm.getNetworkCapabilities(mCm.getActiveNetwork()).hasTransport(transport));
    }

    private void verifyNoNetwork() {
        waitForIdle();
        // Test getActiveNetworkInfo()
        assertNull(mCm.getActiveNetworkInfo());
        // Test getActiveNetwork()
        assertNull(mCm.getActiveNetwork());
        assertNull(mCm.getActiveNetworkForUid(Process.myUid()));
        // Test getAllNetworks()
        assertEmpty(mCm.getAllNetworks());
    }

    /**
     * Class to simplify expecting broadcasts using BroadcastInterceptingContext.
     * Ensures that the receiver is unregistered after the expected broadcast is received. This
     * cannot be done in the BroadcastReceiver itself because BroadcastInterceptingContext runs
     * the receivers' receive method while iterating over the list of receivers, and unregistering
     * the receiver during iteration throws ConcurrentModificationException.
     */
    private class ExpectedBroadcast extends CompletableFuture<Intent>  {
        private final BroadcastReceiver mReceiver;

        ExpectedBroadcast(BroadcastReceiver receiver) {
            mReceiver = receiver;
        }

        public Intent expectBroadcast(int timeoutMs) throws Exception {
            try {
                return get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                fail("Expected broadcast not received after " + timeoutMs + " ms");
                return null;
            } finally {
                mServiceContext.unregisterReceiver(mReceiver);
            }
        }

        public Intent expectBroadcast() throws Exception {
            return expectBroadcast(TIMEOUT_MS);
        }

        public void expectNoBroadcast(int timeoutMs) throws Exception {
            waitForIdle();
            try {
                final Intent intent = get(timeoutMs, TimeUnit.MILLISECONDS);
                fail("Unexpected broadcast: " + intent.getAction() + " " + intent.getExtras());
            } catch (TimeoutException expected) {
            } finally {
                mServiceContext.unregisterReceiver(mReceiver);
            }
        }
    }

    /** Expects that {@code count} CONNECTIVITY_ACTION broadcasts are received. */
    private ExpectedBroadcast registerConnectivityBroadcast(final int count) {
        return registerConnectivityBroadcastThat(count, intent -> true);
    }

    private ExpectedBroadcast registerConnectivityBroadcastThat(final int count,
            @NonNull final Predicate<Intent> filter) {
        final IntentFilter intentFilter = new IntentFilter(CONNECTIVITY_ACTION);
        // AtomicReference allows receiver to access expected even though it is constructed later.
        final AtomicReference<ExpectedBroadcast> expectedRef = new AtomicReference<>();
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            private int mRemaining = count;
            public void onReceive(Context context, Intent intent) {
                final int type = intent.getIntExtra(EXTRA_NETWORK_TYPE, -1);
                final NetworkInfo ni = intent.getParcelableExtra(EXTRA_NETWORK_INFO);
                Log.d(TAG, "Received CONNECTIVITY_ACTION type=" + type + " ni=" + ni);
                if (!filter.test(intent)) return;
                if (--mRemaining == 0) {
                    expectedRef.get().complete(intent);
                }
            }
        };
        final ExpectedBroadcast expected = new ExpectedBroadcast(receiver);
        expectedRef.set(expected);
        mServiceContext.registerReceiver(receiver, intentFilter);
        return expected;
    }

    private ExpectedBroadcast expectConnectivityAction(int type, NetworkInfo.DetailedState state) {
        return registerConnectivityBroadcastThat(1, intent ->
                type == intent.getIntExtra(EXTRA_NETWORK_TYPE, -1) && state.equals(
                        ((NetworkInfo) intent.getParcelableExtra(EXTRA_NETWORK_INFO))
                                .getDetailedState()));
    }

    @Test
    public void testNetworkTypes() {
        // Ensure that our mocks for the networkAttributes config variable work as expected. If they
        // don't, then tests that depend on CONNECTIVITY_ACTION broadcasts for these network types
        // will fail. Failing here is much easier to debug.
        assertTrue(mCm.isNetworkSupported(TYPE_WIFI));
        assertTrue(mCm.isNetworkSupported(TYPE_MOBILE));
        assertTrue(mCm.isNetworkSupported(TYPE_MOBILE_MMS));
        assertFalse(mCm.isNetworkSupported(TYPE_MOBILE_FOTA));

        // Check that TYPE_ETHERNET is supported. Unlike the asserts above, which only validate our
        // mocks, this assert exercises the ConnectivityService code path that ensures that
        // TYPE_ETHERNET is supported if the ethernet service is running.
        assertTrue(mCm.isNetworkSupported(TYPE_ETHERNET));
    }

    @Test
    public void testNetworkFeature() throws Exception {
        // Connect the cell agent and wait for the connected broadcast.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.addCapability(NET_CAPABILITY_SUPL);
        ExpectedBroadcast b = expectConnectivityAction(TYPE_MOBILE, DetailedState.CONNECTED);
        mCellNetworkAgent.connect(true);
        b.expectBroadcast();

        // Build legacy request for SUPL.
        final NetworkCapabilities legacyCaps = new NetworkCapabilities();
        legacyCaps.addTransportType(TRANSPORT_CELLULAR);
        legacyCaps.addCapability(NET_CAPABILITY_SUPL);
        final NetworkRequest legacyRequest = new NetworkRequest(legacyCaps, TYPE_MOBILE_SUPL,
                ConnectivityManager.REQUEST_ID_UNSET, NetworkRequest.Type.REQUEST);

        // File request, withdraw it and make sure no broadcast is sent
        b = registerConnectivityBroadcast(1);
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.requestNetwork(legacyRequest, callback);
        callback.expectCallback(CallbackEntry.AVAILABLE, mCellNetworkAgent);
        mCm.unregisterNetworkCallback(callback);
        b.expectNoBroadcast(800);  // 800ms long enough to at least flake if this is sent

        // Disconnect the network and expect mobile disconnected broadcast.
        b = expectConnectivityAction(TYPE_MOBILE, DetailedState.DISCONNECTED);
        mCellNetworkAgent.disconnect();
        b.expectBroadcast();
    }

    @Test
    public void testLingering() throws Exception {
        verifyNoNetwork();
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        assertNull(mCm.getActiveNetworkInfo());
        assertNull(mCm.getActiveNetwork());
        // Test bringing up validated cellular.
        ExpectedBroadcast b = expectConnectivityAction(TYPE_MOBILE, DetailedState.CONNECTED);
        mCellNetworkAgent.connect(true);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        assertLength(2, mCm.getAllNetworks());
        assertTrue(mCm.getAllNetworks()[0].equals(mCm.getActiveNetwork()) ||
                mCm.getAllNetworks()[1].equals(mCm.getActiveNetwork()));
        assertTrue(mCm.getAllNetworks()[0].equals(mWiFiNetworkAgent.getNetwork()) ||
                mCm.getAllNetworks()[1].equals(mWiFiNetworkAgent.getNetwork()));
        // Test bringing up validated WiFi.
        b = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.connect(true);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertLength(2, mCm.getAllNetworks());
        assertTrue(mCm.getAllNetworks()[0].equals(mCm.getActiveNetwork()) ||
                mCm.getAllNetworks()[1].equals(mCm.getActiveNetwork()));
        assertTrue(mCm.getAllNetworks()[0].equals(mCellNetworkAgent.getNetwork()) ||
                mCm.getAllNetworks()[1].equals(mCellNetworkAgent.getNetwork()));
        // Test cellular linger timeout.
        mCellNetworkAgent.expectDisconnected();
        waitForIdle();
        assertLength(1, mCm.getAllNetworks());
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertLength(1, mCm.getAllNetworks());
        assertEquals(mCm.getAllNetworks()[0], mCm.getActiveNetwork());
        // Test WiFi disconnect.
        b = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent.disconnect();
        b.expectBroadcast();
        verifyNoNetwork();
    }

    /**
     * Verify a newly created network will be inactive instead of torn down even if no one is
     * requesting.
     */
    @Test
    public void testNewNetworkInactive() throws Exception {
        // Create a callback that monitoring the testing network.
        final TestNetworkCallback listenCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(new NetworkRequest.Builder().build(), listenCallback);

        // 1. Create a network that is not requested by anyone, and does not satisfy any of the
        // default requests. Verify that the network will be inactive instead of torn down.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connectWithoutInternet();
        listenCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        listenCallback.assertNoCallback();

        // Verify that the network will be torn down after nascent expiry. A small period of time
        // is added in case of flakiness.
        final int nascentTimeoutMs =
                mService.mNascentDelayMs + mService.mNascentDelayMs / 4;
        listenCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent, nascentTimeoutMs);

        // 2. Create a network that is satisfied by a request comes later.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connectWithoutInternet();
        listenCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        final NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI).build();
        final TestNetworkCallback wifiCallback = new TestNetworkCallback();
        mCm.requestNetwork(wifiRequest, wifiCallback);
        wifiCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);

        // Verify that the network will be kept since the request is still satisfied. And is able
        // to get disconnected as usual if the request is released after the nascent timer expires.
        listenCallback.assertNoCallback(nascentTimeoutMs);
        mCm.unregisterNetworkCallback(wifiCallback);
        listenCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);

        // 3. Create a network that is satisfied by a request comes later.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connectWithoutInternet();
        listenCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        mCm.requestNetwork(wifiRequest, wifiCallback);
        wifiCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);

        // Verify that the network will still be torn down after the request gets removed.
        mCm.unregisterNetworkCallback(wifiCallback);
        listenCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);

        // There is no need to ensure that LOSING is never sent in the common case that the
        // network immediately satisfies a request that was already present, because it is already
        // verified anywhere whenever {@code TestNetworkCallback#expectAvailable*} is called.

        mCm.unregisterNetworkCallback(listenCallback);
    }

    /**
     * Verify a newly created network will be inactive and switch to background if only background
     * request is satisfied.
     */
    @Test
    public void testNewNetworkInactive_bgNetwork() throws Exception {
        // Create a callback that monitoring the wifi network.
        final TestNetworkCallback wifiListenCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI).build(), wifiListenCallback);

        // Create callbacks that can monitor background and foreground mobile networks.
        // This is done by granting using background networks permission before registration. Thus,
        // the service will not add {@code NET_CAPABILITY_FOREGROUND} by default.
        grantUsingBackgroundNetworksPermissionForUid(Binder.getCallingUid());
        final TestNetworkCallback bgMobileListenCallback = new TestNetworkCallback();
        final TestNetworkCallback fgMobileListenCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build(), bgMobileListenCallback);
        mCm.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_FOREGROUND).build(), fgMobileListenCallback);

        // Connect wifi, which satisfies default request.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        wifiListenCallback.expectAvailableThenValidatedCallbacks(mWiFiNetworkAgent);

        // Connect a cellular network, verify that satisfies only the background callback.
        setAlwaysOnNetworks(true);
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        bgMobileListenCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        fgMobileListenCallback.assertNoCallback();
        assertFalse(isForegroundNetwork(mCellNetworkAgent));

        mCellNetworkAgent.disconnect();
        bgMobileListenCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        fgMobileListenCallback.assertNoCallback();

        mCm.unregisterNetworkCallback(wifiListenCallback);
        mCm.unregisterNetworkCallback(bgMobileListenCallback);
        mCm.unregisterNetworkCallback(fgMobileListenCallback);
    }

    @Test
    public void testValidatedCellularOutscoresUnvalidatedWiFi() throws Exception {
        // Test bringing up unvalidated WiFi
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        ExpectedBroadcast b = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent.connect(false);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up unvalidated cellular
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false);
        waitForIdle();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test cellular disconnect.
        mCellNetworkAgent.disconnect();
        waitForIdle();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up validated cellular
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        b = registerConnectivityBroadcast(2);
        mCellNetworkAgent.connect(true);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test cellular disconnect.
        b = registerConnectivityBroadcast(2);
        mCellNetworkAgent.disconnect();
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi disconnect.
        b = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent.disconnect();
        b.expectBroadcast();
        verifyNoNetwork();
    }

    @Test
    public void testUnvalidatedWifiOutscoresUnvalidatedCellular() throws Exception {
        // Test bringing up unvalidated cellular.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        ExpectedBroadcast b = registerConnectivityBroadcast(1);
        mCellNetworkAgent.connect(false);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test bringing up unvalidated WiFi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        b = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.connect(false);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi disconnect.
        b = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.disconnect();
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test cellular disconnect.
        b = registerConnectivityBroadcast(1);
        mCellNetworkAgent.disconnect();
        b.expectBroadcast();
        verifyNoNetwork();
    }

    @Test
    public void testUnlingeringDoesNotValidate() throws Exception {
        // Test bringing up unvalidated WiFi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        ExpectedBroadcast b = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent.connect(false);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        // Test bringing up validated cellular.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        b = registerConnectivityBroadcast(2);
        mCellNetworkAgent.connect(true);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        // Test cellular disconnect.
        b = registerConnectivityBroadcast(2);
        mCellNetworkAgent.disconnect();
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Unlingering a network should not cause it to be marked as validated.
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
    }

    @Test
    public void testCellularOutscoresWeakWifi() throws Exception {
        // Test bringing up validated cellular.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        ExpectedBroadcast b = registerConnectivityBroadcast(1);
        mCellNetworkAgent.connect(true);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        b = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.connect(true);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi getting really weak.
        b = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.adjustScore(-11);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test WiFi restoring signal strength.
        b = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.adjustScore(11);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_WIFI);
    }

    @Test
    public void testReapingNetwork() throws Exception {
        // Test bringing up WiFi without NET_CAPABILITY_INTERNET.
        // Expect it to be torn down immediately because it satisfies no requests.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connectWithoutInternet();
        mWiFiNetworkAgent.expectDisconnected();
        // Test bringing up cellular without NET_CAPABILITY_INTERNET.
        // Expect it to be torn down immediately because it satisfies no requests.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mCellNetworkAgent.connectWithoutInternet();
        mCellNetworkAgent.expectDisconnected();
        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        final ExpectedBroadcast b = expectConnectivityAction(TYPE_WIFI, DetailedState.CONNECTED);
        mWiFiNetworkAgent.connect(true);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up unvalidated cellular.
        // Expect it to be torn down because it could never be the highest scoring network
        // satisfying the default request even if it validated.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false);
        mCellNetworkAgent.expectDisconnected();
        verifyActiveNetwork(TRANSPORT_WIFI);
        mWiFiNetworkAgent.disconnect();
        mWiFiNetworkAgent.expectDisconnected();
    }

    @Test
    public void testCellularFallback() throws Exception {
        // Test bringing up validated cellular.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        ExpectedBroadcast b = registerConnectivityBroadcast(1);
        mCellNetworkAgent.connect(true);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        b = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.connect(true);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Reevaluate WiFi (it'll instantly fail DNS).
        b = registerConnectivityBroadcast(2);
        assertTrue(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        mCm.reportBadNetwork(mWiFiNetworkAgent.getNetwork());
        // Should quickly fall back to Cellular.
        b.expectBroadcast();
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Reevaluate cellular (it'll instantly fail DNS).
        b = registerConnectivityBroadcast(2);
        assertTrue(mCm.getNetworkCapabilities(mCellNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        mCm.reportBadNetwork(mCellNetworkAgent.getNetwork());
        // Should quickly fall back to WiFi.
        b.expectBroadcast();
        assertFalse(mCm.getNetworkCapabilities(mCellNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        verifyActiveNetwork(TRANSPORT_WIFI);
    }

    @Test
    public void testWiFiFallback() throws Exception {
        // Test bringing up unvalidated WiFi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        ExpectedBroadcast b = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent.connect(false);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up validated cellular.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        b = registerConnectivityBroadcast(2);
        mCellNetworkAgent.connect(true);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Reevaluate cellular (it'll instantly fail DNS).
        b = registerConnectivityBroadcast(2);
        assertTrue(mCm.getNetworkCapabilities(mCellNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        mCm.reportBadNetwork(mCellNetworkAgent.getNetwork());
        // Should quickly fall back to WiFi.
        b.expectBroadcast();
        assertFalse(mCm.getNetworkCapabilities(mCellNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        verifyActiveNetwork(TRANSPORT_WIFI);
    }

    @Test
    public void testRequiresValidation() {
        assertTrue(NetworkMonitorUtils.isValidationRequired(
                mCm.getDefaultRequest().networkCapabilities));
    }

    /**
     * Utility NetworkCallback for testing. The caller must explicitly test for all the callbacks
     * this class receives, by calling expectCallback() exactly once each time a callback is
     * received. assertNoCallback may be called at any time.
     */
    private class TestNetworkCallback extends TestableNetworkCallback {
        TestNetworkCallback() {
            super(TEST_CALLBACK_TIMEOUT_MS);
        }

        @Override
        public void assertNoCallback() {
            // TODO: better support this use case in TestableNetworkCallback
            waitForIdle();
            assertNoCallback(0 /* timeout */);
        }

        @Override
        public <T extends CallbackEntry> T expectCallback(final KClass<T> type, final HasNetwork n,
                final long timeoutMs) {
            final T callback = super.expectCallback(type, n, timeoutMs);
            if (callback instanceof CallbackEntry.Losing) {
                // TODO : move this to the specific test(s) needing this rather than here.
                final CallbackEntry.Losing losing = (CallbackEntry.Losing) callback;
                final int maxMsToLive = losing.getMaxMsToLive();
                String msg = String.format(
                        "Invalid linger time value %d, must be between %d and %d",
                        maxMsToLive, 0, mService.mLingerDelayMs);
                assertTrue(msg, 0 <= maxMsToLive && maxMsToLive <= mService.mLingerDelayMs);
            }
            return callback;
        }
    }

    // Can't be part of TestNetworkCallback because "cannot be declared static; static methods can
    // only be declared in a static or top level type".
    static void assertNoCallbacks(TestNetworkCallback ... callbacks) {
        for (TestNetworkCallback c : callbacks) {
            c.assertNoCallback();
        }
    }

    static void expectOnLost(TestNetworkAgentWrapper network, TestNetworkCallback ... callbacks) {
        for (TestNetworkCallback c : callbacks) {
            c.expectCallback(CallbackEntry.LOST, network);
        }
    }

    static void expectAvailableCallbacksUnvalidatedWithSpecifier(TestNetworkAgentWrapper network,
            NetworkSpecifier specifier, TestNetworkCallback ... callbacks) {
        for (TestNetworkCallback c : callbacks) {
            c.expectCallback(CallbackEntry.AVAILABLE, network);
            c.expectCapabilitiesThat(network, (nc) ->
                    !nc.hasCapability(NET_CAPABILITY_VALIDATED)
                            && Objects.equals(specifier, nc.getNetworkSpecifier()));
            c.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, network);
            c.expectCallback(CallbackEntry.BLOCKED_STATUS, network);
        }
    }

    @Test
    public void testStateChangeNetworkCallbacks() throws Exception {
        final TestNetworkCallback genericNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback wifiNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest genericRequest = new NetworkRequest.Builder()
                .clearCapabilities().build();
        final NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI).build();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.registerNetworkCallback(genericRequest, genericNetworkCallback);
        mCm.registerNetworkCallback(wifiRequest, wifiNetworkCallback);
        mCm.registerNetworkCallback(cellRequest, cellNetworkCallback);

        // Test unvalidated networks
        ExpectedBroadcast b = registerConnectivityBroadcast(1);
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false);
        genericNetworkCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        cellNetworkCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        b.expectBroadcast();
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        // This should not trigger spurious onAvailable() callbacks, b/21762680.
        mCellNetworkAgent.adjustScore(-1);
        waitForIdle();
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        b = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        genericNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        wifiNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        b.expectBroadcast();
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        b = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.disconnect();
        genericNetworkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        wifiNetworkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        b.expectBroadcast();
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        b = registerConnectivityBroadcast(1);
        mCellNetworkAgent.disconnect();
        genericNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        b.expectBroadcast();
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        // Test validated networks
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        genericNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        // This should not trigger spurious onAvailable() callbacks, b/21762680.
        mCellNetworkAgent.adjustScore(-1);
        waitForIdle();
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        genericNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        genericNetworkCallback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        genericNetworkCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        wifiNetworkCallback.expectAvailableThenValidatedCallbacks(mWiFiNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        mWiFiNetworkAgent.disconnect();
        genericNetworkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        wifiNetworkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        mCellNetworkAgent.disconnect();
        genericNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);
    }

    private void doNetworkCallbacksSanitizationTest(boolean sanitized) throws Exception {
        final TestNetworkCallback callback = new TestNetworkCallback();
        final TestNetworkCallback defaultCallback = new TestNetworkCallback();
        final NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI).build();
        mCm.registerNetworkCallback(wifiRequest, callback);
        mCm.registerDefaultNetworkCallback(defaultCallback);

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);

        final LinkProperties newLp = new LinkProperties();
        final Uri capportUrl = Uri.parse("https://capport.example.com/api");
        final CaptivePortalData capportData = new CaptivePortalData.Builder()
                .setCaptive(true).build();

        final Uri expectedCapportUrl = sanitized ? null : capportUrl;
        newLp.setCaptivePortalApiUrl(capportUrl);
        mWiFiNetworkAgent.sendLinkProperties(newLp);
        callback.expectLinkPropertiesThat(mWiFiNetworkAgent, lp ->
                Objects.equals(expectedCapportUrl, lp.getCaptivePortalApiUrl()));
        defaultCallback.expectLinkPropertiesThat(mWiFiNetworkAgent, lp ->
                Objects.equals(expectedCapportUrl, lp.getCaptivePortalApiUrl()));

        final CaptivePortalData expectedCapportData = sanitized ? null : capportData;
        mWiFiNetworkAgent.notifyCapportApiDataChanged(capportData);
        callback.expectLinkPropertiesThat(mWiFiNetworkAgent, lp ->
                Objects.equals(expectedCapportData, lp.getCaptivePortalData()));
        defaultCallback.expectLinkPropertiesThat(mWiFiNetworkAgent, lp ->
                Objects.equals(expectedCapportData, lp.getCaptivePortalData()));

        final LinkProperties lp = mCm.getLinkProperties(mWiFiNetworkAgent.getNetwork());
        assertEquals(expectedCapportUrl, lp.getCaptivePortalApiUrl());
        assertEquals(expectedCapportData, lp.getCaptivePortalData());
    }

    @Test
    public void networkCallbacksSanitizationTest_Sanitize() throws Exception {
        mServiceContext.setPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
                PERMISSION_DENIED);
        mServiceContext.setPermission(Manifest.permission.NETWORK_SETTINGS,
                PERMISSION_DENIED);
        doNetworkCallbacksSanitizationTest(true /* sanitized */);
    }

    @Test
    public void networkCallbacksSanitizationTest_NoSanitize_NetworkStack() throws Exception {
        mServiceContext.setPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
                PERMISSION_GRANTED);
        mServiceContext.setPermission(Manifest.permission.NETWORK_SETTINGS, PERMISSION_DENIED);
        doNetworkCallbacksSanitizationTest(false /* sanitized */);
    }

    @Test
    public void networkCallbacksSanitizationTest_NoSanitize_Settings() throws Exception {
        mServiceContext.setPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
                PERMISSION_DENIED);
        mServiceContext.setPermission(Manifest.permission.NETWORK_SETTINGS, PERMISSION_GRANTED);
        doNetworkCallbacksSanitizationTest(false /* sanitized */);
    }

    @Test
    public void testOwnerUidCannotChange() throws Exception {
        final NetworkCapabilities ncTemplate = new NetworkCapabilities();
        final int originalOwnerUid = Process.myUid();
        ncTemplate.setOwnerUid(originalOwnerUid);

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI, new LinkProperties(),
                ncTemplate);
        mWiFiNetworkAgent.connect(false);
        waitForIdle();

        // Send ConnectivityService an update to the mWiFiNetworkAgent's capabilities that changes
        // the owner UID and an unrelated capability.
        NetworkCapabilities agentCapabilities = mWiFiNetworkAgent.getNetworkCapabilities();
        assertEquals(originalOwnerUid, agentCapabilities.getOwnerUid());
        agentCapabilities.setOwnerUid(42);
        assertFalse(agentCapabilities.hasCapability(NET_CAPABILITY_NOT_CONGESTED));
        agentCapabilities.addCapability(NET_CAPABILITY_NOT_CONGESTED);
        mWiFiNetworkAgent.setNetworkCapabilities(agentCapabilities, true);
        waitForIdle();

        // Owner UIDs are not visible without location permission.
        setupLocationPermissions(Build.VERSION_CODES.Q, true, AppOpsManager.OPSTR_FINE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);

        // Check that the capability change has been applied but the owner UID is not modified.
        NetworkCapabilities nc = mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork());
        assertEquals(originalOwnerUid, nc.getOwnerUid());
        assertTrue(nc.hasCapability(NET_CAPABILITY_NOT_CONGESTED));
    }

    @Test
    public void testMultipleLingering() throws Exception {
        // This test would be flaky with the default 120ms timer: that is short enough that
        // lingered networks are torn down before assertions can be run. We don't want to mock the
        // lingering timer to keep the WakeupMessage logic realistic: this has already proven useful
        // in detecting races.
        mService.mLingerDelayMs = 300;

        NetworkRequest request = new NetworkRequest.Builder()
                .clearCapabilities().addCapability(NET_CAPABILITY_NOT_METERED)
                .build();
        TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mEthernetNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_ETHERNET);

        mCellNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mEthernetNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);

        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        defaultCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        mWiFiNetworkAgent.connect(true);
        // We get AVAILABLE on wifi when wifi connects and satisfies our unmetered request.
        // We then get LOSING when wifi validates and cell is outscored.
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        // TODO: Investigate sending validated before losing.
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        mEthernetNetworkAgent.connect(true);
        callback.expectAvailableCallbacksUnvalidated(mEthernetNetworkAgent);
        // TODO: Investigate sending validated before losing.
        callback.expectCallback(CallbackEntry.LOSING, mWiFiNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mEthernetNetworkAgent);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mEthernetNetworkAgent);
        assertEquals(mEthernetNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        mEthernetNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mEthernetNetworkAgent);
        defaultCallback.expectCallback(CallbackEntry.LOST, mEthernetNetworkAgent);
        defaultCallback.expectAvailableCallbacksValidated(mWiFiNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        for (int i = 0; i < 4; i++) {
            TestNetworkAgentWrapper oldNetwork, newNetwork;
            if (i % 2 == 0) {
                mWiFiNetworkAgent.adjustScore(-15);
                oldNetwork = mWiFiNetworkAgent;
                newNetwork = mCellNetworkAgent;
            } else {
                mWiFiNetworkAgent.adjustScore(15);
                oldNetwork = mCellNetworkAgent;
                newNetwork = mWiFiNetworkAgent;

            }
            callback.expectCallback(CallbackEntry.LOSING, oldNetwork);
            // TODO: should we send an AVAILABLE callback to newNetwork, to indicate that it is no
            // longer lingering?
            defaultCallback.expectAvailableCallbacksValidated(newNetwork);
            assertEquals(newNetwork.getNetwork(), mCm.getActiveNetwork());
        }
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Verify that if a network no longer satisfies a request, we send LOST and not LOSING, even
        // if the network is still up.
        mWiFiNetworkAgent.removeCapability(NET_CAPABILITY_NOT_METERED);
        // We expect a notification about the capabilities change, and nothing else.
        defaultCallback.expectCapabilitiesWithout(NET_CAPABILITY_NOT_METERED, mWiFiNetworkAgent);
        defaultCallback.assertNoCallback();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Wifi no longer satisfies our listen, which is for an unmetered network.
        // But because its score is 55, it's still up (and the default network).
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Disconnect our test networks.
        mWiFiNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());
        mCellNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        waitForIdle();
        assertEquals(null, mCm.getActiveNetwork());

        mCm.unregisterNetworkCallback(callback);
        waitForIdle();

        // Check that a network is only lingered or torn down if it would not satisfy a request even
        // if it validated.
        request = new NetworkRequest.Builder().clearCapabilities().build();
        callback = new TestNetworkCallback();

        mCm.registerNetworkCallback(request, callback);

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false);   // Score: 10
        callback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring up wifi with a score of 20.
        // Cell stays up because it would satisfy the default request if it validated.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);   // Score: 20
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        defaultCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring up wifi with a score of 70.
        // Cell is lingered because it would not satisfy any request, even if it validated.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.adjustScore(50);
        mWiFiNetworkAgent.connect(false);   // Score: 70
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Tear down wifi.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        defaultCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring up wifi, then validate it. Previous versions would immediately tear down cell, but
        // it's arguably correct to linger it, since it was the default network before it validated.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        // TODO: Investigate sending validated before losing.
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        defaultCallback.expectAvailableThenValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        defaultCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        mCellNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        defaultCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        waitForIdle();
        assertEquals(null, mCm.getActiveNetwork());

        // If a network is lingering, and we add and remove a request from it, resume lingering.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        defaultCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        // TODO: Investigate sending validated before losing.
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        NetworkCallback noopCallback = new NetworkCallback();
        mCm.requestNetwork(cellRequest, noopCallback);
        // TODO: should this cause an AVAILABLE callback, to indicate that the network is no longer
        // lingering?
        mCm.unregisterNetworkCallback(noopCallback);
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);

        // Similar to the above: lingering can start even after the lingered request is removed.
        // Disconnect wifi and switch to cell.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        defaultCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Cell is now the default network. Pin it with a cell-specific request.
        noopCallback = new NetworkCallback();  // Can't reuse NetworkCallbacks. http://b/20701525
        mCm.requestNetwork(cellRequest, noopCallback);

        // Now connect wifi, and expect it to become the default network.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mWiFiNetworkAgent);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());
        // The default request is lingering on cell, but nothing happens to cell, and we send no
        // callbacks for it, because it's kept up by cellRequest.
        callback.assertNoCallback();
        // Now unregister cellRequest and expect cell to start lingering.
        mCm.unregisterNetworkCallback(noopCallback);
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);

        // Let linger run its course.
        callback.assertNoCallback();
        final int lingerTimeoutMs = mService.mLingerDelayMs + mService.mLingerDelayMs / 4;
        callback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent, lingerTimeoutMs);

        // Register a TRACK_DEFAULT request and check that it does not affect lingering.
        TestNetworkCallback trackDefaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(trackDefaultCallback);
        trackDefaultCallback.expectAvailableCallbacksValidated(mWiFiNetworkAgent);
        mEthernetNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_ETHERNET);
        mEthernetNetworkAgent.connect(true);
        callback.expectAvailableCallbacksUnvalidated(mEthernetNetworkAgent);
        callback.expectCallback(CallbackEntry.LOSING, mWiFiNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mEthernetNetworkAgent);
        trackDefaultCallback.expectAvailableDoubleValidatedCallbacks(mEthernetNetworkAgent);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mEthernetNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Let linger run its course.
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent, lingerTimeoutMs);

        // Clean up.
        mEthernetNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mEthernetNetworkAgent);
        defaultCallback.expectCallback(CallbackEntry.LOST, mEthernetNetworkAgent);
        trackDefaultCallback.expectCallback(CallbackEntry.LOST, mEthernetNetworkAgent);

        mCm.unregisterNetworkCallback(callback);
        mCm.unregisterNetworkCallback(defaultCallback);
        mCm.unregisterNetworkCallback(trackDefaultCallback);
    }

    private void grantUsingBackgroundNetworksPermissionForUid(final int uid) throws Exception {
        final String myPackageName = mContext.getPackageName();
        when(mPackageManager.getPackageInfo(eq(myPackageName), eq(GET_PERMISSIONS)))
                .thenReturn(buildPackageInfo(true, uid));
        mService.mPermissionMonitor.onPackageAdded(myPackageName, uid);
    }

    @Test
    public void testNetworkGoesIntoBackgroundAfterLinger() throws Exception {
        setAlwaysOnNetworks(true);
        grantUsingBackgroundNetworksPermissionForUid(Binder.getCallingUid());
        NetworkRequest request = new NetworkRequest.Builder()
                .clearCapabilities()
                .build();
        TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);

        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        defaultCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);

        // Wifi comes up and cell lingers.
        mWiFiNetworkAgent.connect(true);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);

        // File a request for cellular, then release it.
        NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        NetworkCallback noopCallback = new NetworkCallback();
        mCm.requestNetwork(cellRequest, noopCallback);
        mCm.unregisterNetworkCallback(noopCallback);
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);

        // Let linger run its course.
        callback.assertNoCallback();
        final int lingerTimeoutMs = TEST_LINGER_DELAY_MS + TEST_LINGER_DELAY_MS / 4;
        callback.expectCapabilitiesWithout(NET_CAPABILITY_FOREGROUND, mCellNetworkAgent,
                lingerTimeoutMs);

        // Clean up.
        mCm.unregisterNetworkCallback(defaultCallback);
        mCm.unregisterNetworkCallback(callback);
    }

    @Test
    public void testExplicitlySelected() throws Exception {
        NetworkRequest request = new NetworkRequest.Builder()
                .clearCapabilities().addCapability(NET_CAPABILITY_INTERNET)
                .build();
        TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        // Bring up validated cell.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);

        // Bring up unvalidated wifi with explicitlySelected=true.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.explicitlySelected(true, false);
        mWiFiNetworkAgent.connect(false);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);

        // Cell Remains the default.
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Lower wifi's score to below than cell, and check that it doesn't disconnect because
        // it's explicitly selected.
        mWiFiNetworkAgent.adjustScore(-40);
        mWiFiNetworkAgent.adjustScore(40);
        callback.assertNoCallback();

        // If the user chooses yes on the "No Internet access, stay connected?" dialog, we switch to
        // wifi even though it's unvalidated.
        mCm.setAcceptUnvalidated(mWiFiNetworkAgent.getNetwork(), true, false);
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Disconnect wifi, and then reconnect, again with explicitlySelected=true.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.explicitlySelected(true, false);
        mWiFiNetworkAgent.connect(false);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);

        // If the user chooses no on the "No Internet access, stay connected?" dialog, we ask the
        // network to disconnect.
        mCm.setAcceptUnvalidated(mWiFiNetworkAgent.getNetwork(), false, false);
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);

        // Reconnect, again with explicitlySelected=true, but this time validate.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.explicitlySelected(true, false);
        mWiFiNetworkAgent.connect(true);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // BUG: the network will no longer linger, even though it's validated and outscored.
        // TODO: fix this.
        mEthernetNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_ETHERNET);
        mEthernetNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mEthernetNetworkAgent);
        assertEquals(mEthernetNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        callback.assertNoCallback();

        // Disconnect wifi, and then reconnect as if the user had selected "yes, don't ask again"
        // (i.e., with explicitlySelected=true and acceptUnvalidated=true). Expect to switch to
        // wifi immediately.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.explicitlySelected(true, true);
        mWiFiNetworkAgent.connect(false);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCallback(CallbackEntry.LOSING, mEthernetNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        mEthernetNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mEthernetNetworkAgent);

        // Disconnect and reconnect with explicitlySelected=false and acceptUnvalidated=true.
        // Check that the network is not scored specially and that the device prefers cell data.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.explicitlySelected(false, true);
        mWiFiNetworkAgent.connect(false);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Clean up.
        mWiFiNetworkAgent.disconnect();
        mCellNetworkAgent.disconnect();

        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        callback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
    }

    private void tryNetworkFactoryRequests(int capability) throws Exception {
        // Verify NOT_RESTRICTED is set appropriately
        final NetworkCapabilities nc = new NetworkRequest.Builder().addCapability(capability)
                .build().networkCapabilities;
        if (capability == NET_CAPABILITY_CBS || capability == NET_CAPABILITY_DUN ||
                capability == NET_CAPABILITY_EIMS || capability == NET_CAPABILITY_FOTA ||
                capability == NET_CAPABILITY_IA || capability == NET_CAPABILITY_IMS ||
                capability == NET_CAPABILITY_RCS || capability == NET_CAPABILITY_XCAP
                || capability == NET_CAPABILITY_ENTERPRISE) {
            assertFalse(nc.hasCapability(NET_CAPABILITY_NOT_RESTRICTED));
        } else {
            assertTrue(nc.hasCapability(NET_CAPABILITY_NOT_RESTRICTED));
        }

        NetworkCapabilities filter = new NetworkCapabilities();
        filter.addCapability(capability);
        // Add NOT_VCN_MANAGED capability into filter unconditionally since some request will add
        // NOT_VCN_MANAGED automatically but not for NetworkCapabilities,
        // see {@code NetworkCapabilities#deduceNotVcnManagedCapability} for more details.
        filter.addCapability(NET_CAPABILITY_NOT_VCN_MANAGED);
        final HandlerThread handlerThread = new HandlerThread("testNetworkFactoryRequests");
        handlerThread.start();
        final MockNetworkFactory testFactory = new MockNetworkFactory(handlerThread.getLooper(),
                mServiceContext, "testFactory", filter, mCsHandlerThread);
        testFactory.setScoreFilter(40);
        ConditionVariable cv = testFactory.getNetworkStartedCV();
        testFactory.register();
        testFactory.expectRequestAdd();
        testFactory.assertRequestCountEquals(1);
        int expectedRequestCount = 1;
        NetworkCallback networkCallback = null;
        // For non-INTERNET capabilities we cannot rely on the default request being present, so
        // add one.
        if (capability != NET_CAPABILITY_INTERNET) {
            assertFalse(testFactory.getMyStartRequested());
            NetworkRequest request = new NetworkRequest.Builder().addCapability(capability).build();
            networkCallback = new NetworkCallback();
            mCm.requestNetwork(request, networkCallback);
            expectedRequestCount++;
            testFactory.expectRequestAdd();
        }
        waitFor(cv);
        testFactory.assertRequestCountEquals(expectedRequestCount);
        assertTrue(testFactory.getMyStartRequested());

        // Now bring in a higher scored network.
        TestNetworkAgentWrapper testAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        // Rather than create a validated network which complicates things by registering it's
        // own NetworkRequest during startup, just bump up the score to cancel out the
        // unvalidated penalty.
        testAgent.adjustScore(40);
        cv = testFactory.getNetworkStoppedCV();

        // When testAgent connects, ConnectivityService will re-send us all current requests with
        // the new score. There are expectedRequestCount such requests, and we must wait for all of
        // them.
        testAgent.connect(false);
        testAgent.addCapability(capability);
        waitFor(cv);
        testFactory.expectRequestAdds(expectedRequestCount);
        testFactory.assertRequestCountEquals(expectedRequestCount);
        assertFalse(testFactory.getMyStartRequested());

        // Bring in a bunch of requests.
        assertEquals(expectedRequestCount, testFactory.getMyRequestCount());
        ConnectivityManager.NetworkCallback[] networkCallbacks =
                new ConnectivityManager.NetworkCallback[10];
        for (int i = 0; i< networkCallbacks.length; i++) {
            networkCallbacks[i] = new ConnectivityManager.NetworkCallback();
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.addCapability(capability);
            mCm.requestNetwork(builder.build(), networkCallbacks[i]);
        }
        testFactory.expectRequestAdds(10);
        testFactory.assertRequestCountEquals(10 + expectedRequestCount);
        assertFalse(testFactory.getMyStartRequested());

        // Remove the requests.
        for (int i = 0; i < networkCallbacks.length; i++) {
            mCm.unregisterNetworkCallback(networkCallbacks[i]);
        }
        testFactory.expectRequestRemoves(10);
        testFactory.assertRequestCountEquals(expectedRequestCount);
        assertFalse(testFactory.getMyStartRequested());

        // Drop the higher scored network.
        cv = testFactory.getNetworkStartedCV();
        testAgent.disconnect();
        waitFor(cv);
        testFactory.expectRequestAdds(expectedRequestCount);
        testFactory.assertRequestCountEquals(expectedRequestCount);
        assertEquals(expectedRequestCount, testFactory.getMyRequestCount());
        assertTrue(testFactory.getMyStartRequested());

        testFactory.terminate();
        if (networkCallback != null) mCm.unregisterNetworkCallback(networkCallback);
        handlerThread.quit();
    }

    @Test
    public void testNetworkFactoryRequests() throws Exception {
        tryNetworkFactoryRequests(NET_CAPABILITY_MMS);
        tryNetworkFactoryRequests(NET_CAPABILITY_SUPL);
        tryNetworkFactoryRequests(NET_CAPABILITY_DUN);
        tryNetworkFactoryRequests(NET_CAPABILITY_FOTA);
        tryNetworkFactoryRequests(NET_CAPABILITY_IMS);
        tryNetworkFactoryRequests(NET_CAPABILITY_CBS);
        tryNetworkFactoryRequests(NET_CAPABILITY_WIFI_P2P);
        tryNetworkFactoryRequests(NET_CAPABILITY_IA);
        tryNetworkFactoryRequests(NET_CAPABILITY_RCS);
        tryNetworkFactoryRequests(NET_CAPABILITY_XCAP);
        tryNetworkFactoryRequests(NET_CAPABILITY_ENTERPRISE);
        tryNetworkFactoryRequests(NET_CAPABILITY_EIMS);
        tryNetworkFactoryRequests(NET_CAPABILITY_NOT_METERED);
        tryNetworkFactoryRequests(NET_CAPABILITY_INTERNET);
        tryNetworkFactoryRequests(NET_CAPABILITY_TRUSTED);
        tryNetworkFactoryRequests(NET_CAPABILITY_NOT_VPN);
        // Skipping VALIDATED and CAPTIVE_PORTAL as they're disallowed.
    }

    @Test
    public void testNetworkFactoryUnregister() throws Exception {
        final NetworkCapabilities filter = new NetworkCapabilities();
        filter.clearAll();

        final HandlerThread handlerThread = new HandlerThread("testNetworkFactoryRequests");
        handlerThread.start();

        // Checks that calling setScoreFilter on a NetworkFactory immediately before closing it
        // does not crash.
        for (int i = 0; i < 100; i++) {
            final MockNetworkFactory testFactory = new MockNetworkFactory(handlerThread.getLooper(),
                    mServiceContext, "testFactory", filter, mCsHandlerThread);
            // Register the factory and don't be surprised when the default request arrives.
            testFactory.register();
            testFactory.expectRequestAdd();

            testFactory.setScoreFilter(42);
            testFactory.terminate();

            if (i % 2 == 0) {
                try {
                    testFactory.register();
                    fail("Re-registering terminated NetworkFactory should throw");
                } catch (IllegalStateException expected) {
                }
            }
        }
        handlerThread.quit();
    }

    @Test
    public void testNoMutableNetworkRequests() throws Exception {
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                mContext, 0 /* requestCode */, new Intent("a"), FLAG_IMMUTABLE);
        NetworkRequest request1 = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_VALIDATED)
                .build();
        NetworkRequest request2 = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL)
                .build();

        Class<IllegalArgumentException> expected = IllegalArgumentException.class;
        assertThrows(expected, () -> mCm.requestNetwork(request1, new NetworkCallback()));
        assertThrows(expected, () -> mCm.requestNetwork(request1, pendingIntent));
        assertThrows(expected, () -> mCm.requestNetwork(request2, new NetworkCallback()));
        assertThrows(expected, () -> mCm.requestNetwork(request2, pendingIntent));
    }

    @Test
    public void testMMSonWiFi() throws Exception {
        // Test bringing up cellular without MMS NetworkRequest gets reaped
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.addCapability(NET_CAPABILITY_MMS);
        mCellNetworkAgent.connectWithoutInternet();
        mCellNetworkAgent.expectDisconnected();
        waitForIdle();
        assertEmpty(mCm.getAllNetworks());
        verifyNoNetwork();

        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        final ExpectedBroadcast b = expectConnectivityAction(TYPE_WIFI, DetailedState.CONNECTED);
        mWiFiNetworkAgent.connect(true);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_WIFI);

        // Register MMS NetworkRequest
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.requestNetwork(builder.build(), networkCallback);

        // Test bringing up unvalidated cellular with MMS
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.addCapability(NET_CAPABILITY_MMS);
        mCellNetworkAgent.connectWithoutInternet();
        networkCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        verifyActiveNetwork(TRANSPORT_WIFI);

        // Test releasing NetworkRequest disconnects cellular with MMS
        mCm.unregisterNetworkCallback(networkCallback);
        mCellNetworkAgent.expectDisconnected();
        verifyActiveNetwork(TRANSPORT_WIFI);
    }

    @Test
    public void testMMSonCell() throws Exception {
        // Test bringing up cellular without MMS
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        ExpectedBroadcast b = expectConnectivityAction(TYPE_MOBILE, DetailedState.CONNECTED);
        mCellNetworkAgent.connect(false);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_CELLULAR);

        // Register MMS NetworkRequest
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.requestNetwork(builder.build(), networkCallback);

        // Test bringing up MMS cellular network
        TestNetworkAgentWrapper
                mmsNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mmsNetworkAgent.addCapability(NET_CAPABILITY_MMS);
        mmsNetworkAgent.connectWithoutInternet();
        networkCallback.expectAvailableCallbacksUnvalidated(mmsNetworkAgent);
        verifyActiveNetwork(TRANSPORT_CELLULAR);

        // Test releasing MMS NetworkRequest does not disconnect main cellular NetworkAgent
        mCm.unregisterNetworkCallback(networkCallback);
        mmsNetworkAgent.expectDisconnected();
        verifyActiveNetwork(TRANSPORT_CELLULAR);
    }

    @Test
    public void testPartialConnectivity() throws Exception {
        // Register network callback.
        NetworkRequest request = new NetworkRequest.Builder()
                .clearCapabilities().addCapability(NET_CAPABILITY_INTERNET)
                .build();
        TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        // Bring up validated mobile data.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);

        // Bring up wifi with partial connectivity.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connectWithPartialConnectivity();
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_PARTIAL_CONNECTIVITY, mWiFiNetworkAgent);

        // Mobile data should be the default network.
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        callback.assertNoCallback();

        // With HTTPS probe disabled, NetworkMonitor should pass the network validation with http
        // probe.
        mWiFiNetworkAgent.setNetworkPartialValid(false /* isStrictMode */);
        // If the user chooses yes to use this partial connectivity wifi, switch the default
        // network to wifi and check if wifi becomes valid or not.
        mCm.setAcceptPartialConnectivity(mWiFiNetworkAgent.getNetwork(), true /* accept */,
                false /* always */);
        // If user accepts partial connectivity network,
        // NetworkMonitor#setAcceptPartialConnectivity() should be called too.
        waitForIdle();
        verify(mWiFiNetworkAgent.mNetworkMonitor, times(1)).setAcceptPartialConnectivity();

        // Need a trigger point to let NetworkMonitor tell ConnectivityService that network is
        // validated.
        mCm.reportNetworkConnectivity(mWiFiNetworkAgent.getNetwork(), true);
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        NetworkCapabilities nc = callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED,
                mWiFiNetworkAgent);
        assertTrue(nc.hasCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY));
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Disconnect and reconnect wifi with partial connectivity again.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connectWithPartialConnectivity();
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_PARTIAL_CONNECTIVITY, mWiFiNetworkAgent);

        // Mobile data should be the default network.
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // If the user chooses no, disconnect wifi immediately.
        mCm.setAcceptPartialConnectivity(mWiFiNetworkAgent.getNetwork(), false/* accept */,
                false /* always */);
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);

        // If user accepted partial connectivity before, and device reconnects to that network
        // again, but now the network has full connectivity. The network shouldn't contain
        // NET_CAPABILITY_PARTIAL_CONNECTIVITY.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        // acceptUnvalidated is also used as setting for accepting partial networks.
        mWiFiNetworkAgent.explicitlySelected(true /* explicitlySelected */,
                true /* acceptUnvalidated */);
        mWiFiNetworkAgent.connect(true);

        // If user accepted partial connectivity network before,
        // NetworkMonitor#setAcceptPartialConnectivity() will be called in
        // ConnectivityService#updateNetworkInfo().
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        verify(mWiFiNetworkAgent.mNetworkMonitor, times(1)).setAcceptPartialConnectivity();
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        nc = callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        assertFalse(nc.hasCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY));

        // Wifi should be the default network.
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);

        // The user accepted partial connectivity and selected "don't ask again". Now the user
        // reconnects to the partial connectivity network. Switch to wifi as soon as partial
        // connectivity is detected.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.explicitlySelected(true /* explicitlySelected */,
                true /* acceptUnvalidated */);
        mWiFiNetworkAgent.connectWithPartialConnectivity();
        // If user accepted partial connectivity network before,
        // NetworkMonitor#setAcceptPartialConnectivity() will be called in
        // ConnectivityService#updateNetworkInfo().
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        verify(mWiFiNetworkAgent.mNetworkMonitor, times(1)).setAcceptPartialConnectivity();
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        callback.expectCapabilitiesWith(NET_CAPABILITY_PARTIAL_CONNECTIVITY, mWiFiNetworkAgent);
        mWiFiNetworkAgent.setNetworkValid(false /* isStrictMode */);

        // Need a trigger point to let NetworkMonitor tell ConnectivityService that network is
        // validated.
        mCm.reportNetworkConnectivity(mWiFiNetworkAgent.getNetwork(), true);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);

        // If the user accepted partial connectivity, and the device auto-reconnects to the partial
        // connectivity network, it should contain both PARTIAL_CONNECTIVITY and VALIDATED.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.explicitlySelected(false /* explicitlySelected */,
                true /* acceptUnvalidated */);

        // NetworkMonitor will immediately (once the HTTPS probe fails...) report the network as
        // valid, because ConnectivityService calls setAcceptPartialConnectivity before it calls
        // notifyNetworkConnected.
        mWiFiNetworkAgent.connectWithPartialValidConnectivity(false /* isStrictMode */);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        verify(mWiFiNetworkAgent.mNetworkMonitor, times(1)).setAcceptPartialConnectivity();
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(
                NET_CAPABILITY_PARTIAL_CONNECTIVITY | NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
    }

    @Test
    public void testCaptivePortalOnPartialConnectivity() throws Exception {
        final TestNetworkCallback captivePortalCallback = new TestNetworkCallback();
        final NetworkRequest captivePortalRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build();
        mCm.registerNetworkCallback(captivePortalRequest, captivePortalCallback);

        final TestNetworkCallback validatedCallback = new TestNetworkCallback();
        final NetworkRequest validatedRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_VALIDATED).build();
        mCm.registerNetworkCallback(validatedRequest, validatedCallback);

        // Bring up a network with a captive portal.
        // Expect onAvailable callback of listen for NET_CAPABILITY_CAPTIVE_PORTAL.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        String redirectUrl = "http://android.com/path";
        mWiFiNetworkAgent.connectWithCaptivePortal(redirectUrl, false /* isStrictMode */);
        captivePortalCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.waitForRedirectUrl(), redirectUrl);

        // Check that startCaptivePortalApp sends the expected command to NetworkMonitor.
        mCm.startCaptivePortalApp(mWiFiNetworkAgent.getNetwork());
        verify(mWiFiNetworkAgent.mNetworkMonitor, timeout(TIMEOUT_MS).times(1))
                .launchCaptivePortalApp();

        // Report that the captive portal is dismissed with partial connectivity, and check that
        // callbacks are fired.
        mWiFiNetworkAgent.setNetworkPartial();
        mCm.reportNetworkConnectivity(mWiFiNetworkAgent.getNetwork(), true);
        waitForIdle();
        captivePortalCallback.expectCapabilitiesWith(NET_CAPABILITY_PARTIAL_CONNECTIVITY,
                mWiFiNetworkAgent);

        // Report partial connectivity is accepted.
        mWiFiNetworkAgent.setNetworkPartialValid(false /* isStrictMode */);
        mCm.setAcceptPartialConnectivity(mWiFiNetworkAgent.getNetwork(), true /* accept */,
                false /* always */);
        waitForIdle();
        mCm.reportNetworkConnectivity(mWiFiNetworkAgent.getNetwork(), true);
        captivePortalCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        validatedCallback.expectAvailableCallbacksValidated(mWiFiNetworkAgent);
        NetworkCapabilities nc =
                validatedCallback.expectCapabilitiesWith(NET_CAPABILITY_PARTIAL_CONNECTIVITY,
                mWiFiNetworkAgent);

        mCm.unregisterNetworkCallback(captivePortalCallback);
        mCm.unregisterNetworkCallback(validatedCallback);
    }

    @Test
    public void testCaptivePortal() throws Exception {
        final TestNetworkCallback captivePortalCallback = new TestNetworkCallback();
        final NetworkRequest captivePortalRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build();
        mCm.registerNetworkCallback(captivePortalRequest, captivePortalCallback);

        final TestNetworkCallback validatedCallback = new TestNetworkCallback();
        final NetworkRequest validatedRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_VALIDATED).build();
        mCm.registerNetworkCallback(validatedRequest, validatedCallback);

        // Bring up a network with a captive portal.
        // Expect onAvailable callback of listen for NET_CAPABILITY_CAPTIVE_PORTAL.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        String firstRedirectUrl = "http://example.com/firstPath";
        mWiFiNetworkAgent.connectWithCaptivePortal(firstRedirectUrl, false /* isStrictMode */);
        captivePortalCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.waitForRedirectUrl(), firstRedirectUrl);

        // Take down network.
        // Expect onLost callback.
        mWiFiNetworkAgent.disconnect();
        captivePortalCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);

        // Bring up a network with a captive portal.
        // Expect onAvailable callback of listen for NET_CAPABILITY_CAPTIVE_PORTAL.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        String secondRedirectUrl = "http://example.com/secondPath";
        mWiFiNetworkAgent.connectWithCaptivePortal(secondRedirectUrl, false /* isStrictMode */);
        captivePortalCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.waitForRedirectUrl(), secondRedirectUrl);

        // Make captive portal disappear then revalidate.
        // Expect onLost callback because network no longer provides NET_CAPABILITY_CAPTIVE_PORTAL.
        mWiFiNetworkAgent.setNetworkValid(false /* isStrictMode */);
        mCm.reportNetworkConnectivity(mWiFiNetworkAgent.getNetwork(), true);
        captivePortalCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);

        // Expect NET_CAPABILITY_VALIDATED onAvailable callback.
        validatedCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);

        // Break network connectivity.
        // Expect NET_CAPABILITY_VALIDATED onLost callback.
        mWiFiNetworkAgent.setNetworkInvalid(false /* isStrictMode */);
        mCm.reportNetworkConnectivity(mWiFiNetworkAgent.getNetwork(), false);
        validatedCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
    }

    @Test
    public void testCaptivePortalApp() throws Exception {
        final TestNetworkCallback captivePortalCallback = new TestNetworkCallback();
        final NetworkRequest captivePortalRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build();
        mCm.registerNetworkCallback(captivePortalRequest, captivePortalCallback);

        final TestNetworkCallback validatedCallback = new TestNetworkCallback();
        final NetworkRequest validatedRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_VALIDATED).build();
        mCm.registerNetworkCallback(validatedRequest, validatedCallback);

        // Bring up wifi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        validatedCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        Network wifiNetwork = mWiFiNetworkAgent.getNetwork();

        // Check that calling startCaptivePortalApp does nothing.
        final int fastTimeoutMs = 100;
        mCm.startCaptivePortalApp(wifiNetwork);
        waitForIdle();
        verify(mWiFiNetworkAgent.mNetworkMonitor, never()).launchCaptivePortalApp();
        mServiceContext.expectNoStartActivityIntent(fastTimeoutMs);

        // Turn into a captive portal.
        mWiFiNetworkAgent.setNetworkPortal("http://example.com", false /* isStrictMode */);
        mCm.reportNetworkConnectivity(wifiNetwork, false);
        captivePortalCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        validatedCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);

        // Check that startCaptivePortalApp sends the expected command to NetworkMonitor.
        mCm.startCaptivePortalApp(wifiNetwork);
        waitForIdle();
        verify(mWiFiNetworkAgent.mNetworkMonitor).launchCaptivePortalApp();

        // NetworkMonitor uses startCaptivePortal(Network, Bundle) (startCaptivePortalAppInternal)
        final Bundle testBundle = new Bundle();
        final String testKey = "testkey";
        final String testValue = "testvalue";
        testBundle.putString(testKey, testValue);
        mServiceContext.setPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
                PERMISSION_GRANTED);
        mCm.startCaptivePortalApp(wifiNetwork, testBundle);
        final Intent signInIntent = mServiceContext.expectStartActivityIntent(TIMEOUT_MS);
        assertEquals(ACTION_CAPTIVE_PORTAL_SIGN_IN, signInIntent.getAction());
        assertEquals(testValue, signInIntent.getStringExtra(testKey));

        // Report that the captive portal is dismissed, and check that callbacks are fired
        mWiFiNetworkAgent.setNetworkValid(false /* isStrictMode */);
        mWiFiNetworkAgent.mNetworkMonitor.forceReevaluation(Process.myUid());
        validatedCallback.expectAvailableCallbacksValidated(mWiFiNetworkAgent);
        captivePortalCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);

        mCm.unregisterNetworkCallback(validatedCallback);
        mCm.unregisterNetworkCallback(captivePortalCallback);
    }

    @Test
    public void testAvoidOrIgnoreCaptivePortals() throws Exception {
        final TestNetworkCallback captivePortalCallback = new TestNetworkCallback();
        final NetworkRequest captivePortalRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build();
        mCm.registerNetworkCallback(captivePortalRequest, captivePortalCallback);

        final TestNetworkCallback validatedCallback = new TestNetworkCallback();
        final NetworkRequest validatedRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_VALIDATED).build();
        mCm.registerNetworkCallback(validatedRequest, validatedCallback);

        setCaptivePortalMode(Settings.Global.CAPTIVE_PORTAL_MODE_AVOID);
        // Bring up a network with a captive portal.
        // Expect it to fail to connect and not result in any callbacks.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        String firstRedirectUrl = "http://example.com/firstPath";

        mWiFiNetworkAgent.connectWithCaptivePortal(firstRedirectUrl, false /* isStrictMode */);
        mWiFiNetworkAgent.expectDisconnected();
        mWiFiNetworkAgent.expectPreventReconnectReceived();

        assertNoCallbacks(captivePortalCallback, validatedCallback);
    }

    @Test
    public void testCaptivePortalApi() throws Exception {
        mServiceContext.setPermission(
                android.Manifest.permission.NETWORK_SETTINGS, PERMISSION_GRANTED);

        final TestNetworkCallback captivePortalCallback = new TestNetworkCallback();
        final NetworkRequest captivePortalRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build();
        mCm.registerNetworkCallback(captivePortalRequest, captivePortalCallback);

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        final String redirectUrl = "http://example.com/firstPath";

        mWiFiNetworkAgent.connectWithCaptivePortal(redirectUrl, false /* isStrictMode */);
        captivePortalCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);

        final CaptivePortalData testData = new CaptivePortalData.Builder()
                .setUserPortalUrl(Uri.parse(redirectUrl))
                .setBytesRemaining(12345L)
                .build();

        mWiFiNetworkAgent.notifyCapportApiDataChanged(testData);

        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> testData.equals(lp.getCaptivePortalData()));

        final LinkProperties newLps = new LinkProperties();
        newLps.setMtu(1234);
        mWiFiNetworkAgent.sendLinkProperties(newLps);
        // CaptivePortalData is not lost and unchanged when LPs are received from the NetworkAgent
        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> testData.equals(lp.getCaptivePortalData()) && lp.getMtu() == 1234);
    }

    private TestNetworkCallback setupNetworkCallbackAndConnectToWifi() throws Exception {
        // Grant NETWORK_SETTINGS permission to be able to receive LinkProperties change callbacks
        // with sensitive (captive portal) data
        mServiceContext.setPermission(
                android.Manifest.permission.NETWORK_SETTINGS, PERMISSION_GRANTED);

        final TestNetworkCallback captivePortalCallback = new TestNetworkCallback();
        final NetworkRequest captivePortalRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_CAPTIVE_PORTAL).build();
        mCm.registerNetworkCallback(captivePortalRequest, captivePortalCallback);

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);

        mWiFiNetworkAgent.connectWithCaptivePortal(TEST_REDIRECT_URL, false /* isStrictMode */);
        captivePortalCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        return captivePortalCallback;
    }

    private class CaptivePortalTestData {
        CaptivePortalTestData(CaptivePortalData naPasspointData, CaptivePortalData capportData,
                CaptivePortalData naOtherData, CaptivePortalData expectedMergedPasspointData,
                CaptivePortalData expectedMergedOtherData) {
            mNaPasspointData = naPasspointData;
            mCapportData = capportData;
            mNaOtherData = naOtherData;
            mExpectedMergedPasspointData = expectedMergedPasspointData;
            mExpectedMergedOtherData = expectedMergedOtherData;
        }

        public final CaptivePortalData mNaPasspointData;
        public final CaptivePortalData mCapportData;
        public final CaptivePortalData mNaOtherData;
        public final CaptivePortalData mExpectedMergedPasspointData;
        public final CaptivePortalData mExpectedMergedOtherData;

    }

    private CaptivePortalTestData setupCaptivePortalData() {
        final CaptivePortalData capportData = new CaptivePortalData.Builder()
                .setUserPortalUrl(Uri.parse(TEST_REDIRECT_URL))
                .setVenueInfoUrl(Uri.parse(TEST_VENUE_URL_CAPPORT))
                .setUserPortalUrl(Uri.parse(TEST_USER_PORTAL_API_URL_CAPPORT))
                .setExpiryTime(1000000L)
                .setBytesRemaining(12345L)
                .build();

        final CaptivePortalData naPasspointData = new CaptivePortalData.Builder()
                .setBytesRemaining(80802L)
                .setVenueInfoUrl(Uri.parse(TEST_VENUE_URL_NA_PASSPOINT),
                        CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_PASSPOINT)
                .setUserPortalUrl(Uri.parse(TEST_TERMS_AND_CONDITIONS_URL_NA_PASSPOINT),
                        CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_PASSPOINT)
                .setVenueFriendlyName(TEST_FRIENDLY_NAME).build();

        final CaptivePortalData naOtherData = new CaptivePortalData.Builder()
                .setBytesRemaining(80802L)
                .setVenueInfoUrl(Uri.parse(TEST_VENUE_URL_NA_OTHER),
                        CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_OTHER)
                .setUserPortalUrl(Uri.parse(TEST_TERMS_AND_CONDITIONS_URL_NA_OTHER),
                        CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_OTHER)
                .setVenueFriendlyName(TEST_FRIENDLY_NAME).build();

        final CaptivePortalData expectedMergedPasspointData = new CaptivePortalData.Builder()
                .setUserPortalUrl(Uri.parse(TEST_REDIRECT_URL))
                .setBytesRemaining(12345L)
                .setExpiryTime(1000000L)
                .setVenueInfoUrl(Uri.parse(TEST_VENUE_URL_NA_PASSPOINT),
                        CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_PASSPOINT)
                .setUserPortalUrl(Uri.parse(TEST_TERMS_AND_CONDITIONS_URL_NA_PASSPOINT),
                        CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_PASSPOINT)
                .setVenueFriendlyName(TEST_FRIENDLY_NAME).build();

        final CaptivePortalData expectedMergedOtherData = new CaptivePortalData.Builder()
                .setUserPortalUrl(Uri.parse(TEST_REDIRECT_URL))
                .setBytesRemaining(12345L)
                .setExpiryTime(1000000L)
                .setVenueInfoUrl(Uri.parse(TEST_VENUE_URL_CAPPORT))
                .setUserPortalUrl(Uri.parse(TEST_USER_PORTAL_API_URL_CAPPORT))
                .setVenueFriendlyName(TEST_FRIENDLY_NAME).build();
        return new CaptivePortalTestData(naPasspointData, capportData, naOtherData,
                expectedMergedPasspointData, expectedMergedOtherData);
    }

    @Test
    public void testMergeCaptivePortalApiWithFriendlyNameAndVenueUrl() throws Exception {
        final TestNetworkCallback captivePortalCallback = setupNetworkCallbackAndConnectToWifi();
        final CaptivePortalTestData captivePortalTestData = setupCaptivePortalData();

        // Baseline capport data
        mWiFiNetworkAgent.notifyCapportApiDataChanged(captivePortalTestData.mCapportData);

        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> captivePortalTestData.mCapportData.equals(lp.getCaptivePortalData()));

        // Venue URL, T&C URL and friendly name from Network agent with Passpoint source, confirm
        // that API data gets precedence on the bytes remaining.
        final LinkProperties linkProperties = new LinkProperties();
        linkProperties.setCaptivePortalData(captivePortalTestData.mNaPasspointData);
        mWiFiNetworkAgent.sendLinkProperties(linkProperties);

        // Make sure that the capport data is merged
        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> captivePortalTestData.mExpectedMergedPasspointData
                        .equals(lp.getCaptivePortalData()));

        // Now send this information from non-Passpoint source, confirm that Capport data takes
        // precedence
        linkProperties.setCaptivePortalData(captivePortalTestData.mNaOtherData);
        mWiFiNetworkAgent.sendLinkProperties(linkProperties);

        // Make sure that the capport data is merged
        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> captivePortalTestData.mExpectedMergedOtherData
                        .equals(lp.getCaptivePortalData()));

        // Create a new LP with no Network agent capport data
        final LinkProperties newLps = new LinkProperties();
        newLps.setMtu(1234);
        mWiFiNetworkAgent.sendLinkProperties(newLps);
        // CaptivePortalData is not lost and has the original values when LPs are received from the
        // NetworkAgent
        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> captivePortalTestData.mCapportData.equals(lp.getCaptivePortalData())
                        && lp.getMtu() == 1234);

        // Now send capport data only from the Network agent
        mWiFiNetworkAgent.notifyCapportApiDataChanged(null);
        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> lp.getCaptivePortalData() == null);

        newLps.setCaptivePortalData(captivePortalTestData.mNaPasspointData);
        mWiFiNetworkAgent.sendLinkProperties(newLps);

        // Make sure that only the network agent capport data is available
        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> captivePortalTestData.mNaPasspointData.equals(lp.getCaptivePortalData()));
    }

    @Test
    public void testMergeCaptivePortalDataFromNetworkAgentFirstThenCapport() throws Exception {
        final TestNetworkCallback captivePortalCallback = setupNetworkCallbackAndConnectToWifi();
        final CaptivePortalTestData captivePortalTestData = setupCaptivePortalData();

        // Venue URL and friendly name from Network agent, confirm that API data gets precedence
        // on the bytes remaining.
        final LinkProperties linkProperties = new LinkProperties();
        linkProperties.setCaptivePortalData(captivePortalTestData.mNaPasspointData);
        mWiFiNetworkAgent.sendLinkProperties(linkProperties);

        // Make sure that the data is saved correctly
        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> captivePortalTestData.mNaPasspointData.equals(lp.getCaptivePortalData()));

        // Expected merged data: Network agent data is preferred, and values that are not used by
        // it are merged from capport data
        mWiFiNetworkAgent.notifyCapportApiDataChanged(captivePortalTestData.mCapportData);

        // Make sure that the Capport data is merged correctly
        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> captivePortalTestData.mExpectedMergedPasspointData.equals(
                        lp.getCaptivePortalData()));

        // Now set the naData to null
        linkProperties.setCaptivePortalData(null);
        mWiFiNetworkAgent.sendLinkProperties(linkProperties);

        // Make sure that the Capport data is retained correctly
        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> captivePortalTestData.mCapportData.equals(lp.getCaptivePortalData()));
    }

    @Test
    public void testMergeCaptivePortalDataFromNetworkAgentOtherSourceFirstThenCapport()
            throws Exception {
        final TestNetworkCallback captivePortalCallback = setupNetworkCallbackAndConnectToWifi();
        final CaptivePortalTestData captivePortalTestData = setupCaptivePortalData();

        // Venue URL and friendly name from Network agent, confirm that API data gets precedence
        // on the bytes remaining.
        final LinkProperties linkProperties = new LinkProperties();
        linkProperties.setCaptivePortalData(captivePortalTestData.mNaOtherData);
        mWiFiNetworkAgent.sendLinkProperties(linkProperties);

        // Make sure that the data is saved correctly
        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> captivePortalTestData.mNaOtherData.equals(lp.getCaptivePortalData()));

        // Expected merged data: Network agent data is preferred, and values that are not used by
        // it are merged from capport data
        mWiFiNetworkAgent.notifyCapportApiDataChanged(captivePortalTestData.mCapportData);

        // Make sure that the Capport data is merged correctly
        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> captivePortalTestData.mExpectedMergedOtherData.equals(
                        lp.getCaptivePortalData()));
    }

    private NetworkRequest.Builder newWifiRequestBuilder() {
        return new NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI);
    }

    /**
     * Verify request matching behavior with network specifiers.
     *
     * This test does not check updating the specifier on a live network because the specifier is
     * immutable and this triggers a WTF in
     * {@link ConnectivityService#mixInCapabilities(NetworkAgentInfo, NetworkCapabilities)}.
     */
    @Test
    public void testNetworkSpecifier() throws Exception {
        // A NetworkSpecifier subclass that matches all networks but must not be visible to apps.
        class ConfidentialMatchAllNetworkSpecifier extends NetworkSpecifier implements
                Parcelable {
            @Override
            public boolean canBeSatisfiedBy(NetworkSpecifier other) {
                return true;
            }

            @Override
            public int describeContents() {
                return 0;
            }

            @Override
            public void writeToParcel(Parcel dest, int flags) {}

            @Override
            public NetworkSpecifier redact() {
                return null;
            }
        }

        // A network specifier that matches either another LocalNetworkSpecifier with the same
        // string or a ConfidentialMatchAllNetworkSpecifier, and can be passed to apps as is.
        class LocalStringNetworkSpecifier extends NetworkSpecifier implements Parcelable {
            private String mString;

            LocalStringNetworkSpecifier(String string) {
                mString = string;
            }

            @Override
            public boolean canBeSatisfiedBy(NetworkSpecifier other) {
                if (other instanceof LocalStringNetworkSpecifier) {
                    return TextUtils.equals(mString,
                            ((LocalStringNetworkSpecifier) other).mString);
                }
                if (other instanceof ConfidentialMatchAllNetworkSpecifier) return true;
                return false;
            }

            @Override
            public int describeContents() {
                return 0;
            }
            @Override
            public void writeToParcel(Parcel dest, int flags) {}
        }


        NetworkRequest rEmpty1 = newWifiRequestBuilder().build();
        NetworkRequest rEmpty2 = newWifiRequestBuilder().setNetworkSpecifier((String) null).build();
        NetworkRequest rEmpty3 = newWifiRequestBuilder().setNetworkSpecifier("").build();
        NetworkRequest rEmpty4 = newWifiRequestBuilder().setNetworkSpecifier(
            (NetworkSpecifier) null).build();
        NetworkRequest rFoo = newWifiRequestBuilder().setNetworkSpecifier(
                new LocalStringNetworkSpecifier("foo")).build();
        NetworkRequest rBar = newWifiRequestBuilder().setNetworkSpecifier(
                new LocalStringNetworkSpecifier("bar")).build();

        TestNetworkCallback cEmpty1 = new TestNetworkCallback();
        TestNetworkCallback cEmpty2 = new TestNetworkCallback();
        TestNetworkCallback cEmpty3 = new TestNetworkCallback();
        TestNetworkCallback cEmpty4 = new TestNetworkCallback();
        TestNetworkCallback cFoo = new TestNetworkCallback();
        TestNetworkCallback cBar = new TestNetworkCallback();
        TestNetworkCallback[] emptyCallbacks = new TestNetworkCallback[] {
                cEmpty1, cEmpty2, cEmpty3, cEmpty4 };

        mCm.registerNetworkCallback(rEmpty1, cEmpty1);
        mCm.registerNetworkCallback(rEmpty2, cEmpty2);
        mCm.registerNetworkCallback(rEmpty3, cEmpty3);
        mCm.registerNetworkCallback(rEmpty4, cEmpty4);
        mCm.registerNetworkCallback(rFoo, cFoo);
        mCm.registerNetworkCallback(rBar, cBar);

        LocalStringNetworkSpecifier nsFoo = new LocalStringNetworkSpecifier("foo");
        LocalStringNetworkSpecifier nsBar = new LocalStringNetworkSpecifier("bar");

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        expectAvailableCallbacksUnvalidatedWithSpecifier(mWiFiNetworkAgent, null /* specifier */,
                cEmpty1, cEmpty2, cEmpty3, cEmpty4);
        assertNoCallbacks(cFoo, cBar);

        mWiFiNetworkAgent.disconnect();
        expectOnLost(mWiFiNetworkAgent, cEmpty1, cEmpty2, cEmpty3, cEmpty4);

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.setNetworkSpecifier(nsFoo);
        mWiFiNetworkAgent.connect(false);
        expectAvailableCallbacksUnvalidatedWithSpecifier(mWiFiNetworkAgent, nsFoo,
                cEmpty1, cEmpty2, cEmpty3, cEmpty4, cFoo);
        cBar.assertNoCallback();
        assertEquals(nsFoo,
                mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).getNetworkSpecifier());
        assertNoCallbacks(cEmpty1, cEmpty2, cEmpty3, cEmpty4, cFoo);

        mWiFiNetworkAgent.disconnect();
        expectOnLost(mWiFiNetworkAgent, cEmpty1, cEmpty2, cEmpty3, cEmpty4, cFoo);

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.setNetworkSpecifier(nsBar);
        mWiFiNetworkAgent.connect(false);
        expectAvailableCallbacksUnvalidatedWithSpecifier(mWiFiNetworkAgent, nsBar,
                cEmpty1, cEmpty2, cEmpty3, cEmpty4, cBar);
        cFoo.assertNoCallback();
        assertEquals(nsBar,
                mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).getNetworkSpecifier());

        mWiFiNetworkAgent.disconnect();
        expectOnLost(mWiFiNetworkAgent, cEmpty1, cEmpty2, cEmpty3, cEmpty4, cBar);
        cFoo.assertNoCallback();

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.setNetworkSpecifier(new ConfidentialMatchAllNetworkSpecifier());
        mWiFiNetworkAgent.connect(false);
        expectAvailableCallbacksUnvalidatedWithSpecifier(mWiFiNetworkAgent, null /* specifier */,
                cEmpty1, cEmpty2, cEmpty3, cEmpty4, cFoo, cBar);
        assertNull(
                mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).getNetworkSpecifier());

        mWiFiNetworkAgent.disconnect();
        expectOnLost(mWiFiNetworkAgent, cEmpty1, cEmpty2, cEmpty3, cEmpty4, cFoo, cBar);
    }

    /**
     * @return the context's attribution tag
     */
    private String getAttributionTag() {
        return mContext.getAttributionTag();
    }

    @Test
    public void testInvalidNetworkSpecifier() {
        assertThrows(IllegalArgumentException.class, () -> {
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.setNetworkSpecifier(new MatchAllNetworkSpecifier());
        });

        assertThrows(IllegalArgumentException.class, () -> {
            NetworkCapabilities networkCapabilities = new NetworkCapabilities();
            networkCapabilities.addTransportType(TRANSPORT_WIFI)
                    .setNetworkSpecifier(new MatchAllNetworkSpecifier());
            mService.requestNetwork(networkCapabilities, NetworkRequest.Type.REQUEST.ordinal(),
                    null, 0, null, ConnectivityManager.TYPE_WIFI, mContext.getPackageName(),
                    getAttributionTag());
        });

        class NonParcelableSpecifier extends NetworkSpecifier {
            @Override
            public boolean canBeSatisfiedBy(NetworkSpecifier other) {
                return false;
            }
        };
        class ParcelableSpecifier extends NonParcelableSpecifier implements Parcelable {
            @Override public int describeContents() { return 0; }
            @Override public void writeToParcel(Parcel p, int flags) {}
        }

        final NetworkRequest.Builder builder =
                new NetworkRequest.Builder().addTransportType(TRANSPORT_ETHERNET);
        assertThrows(ClassCastException.class, () -> {
            builder.setNetworkSpecifier(new NonParcelableSpecifier());
            Parcel parcelW = Parcel.obtain();
            builder.build().writeToParcel(parcelW, 0);
        });

        final NetworkRequest nr =
                new NetworkRequest.Builder().addTransportType(TRANSPORT_ETHERNET)
                .setNetworkSpecifier(new ParcelableSpecifier())
                .build();
        assertNotNull(nr);

        assertThrows(BadParcelableException.class, () -> {
            Parcel parcelW = Parcel.obtain();
            nr.writeToParcel(parcelW, 0);
            byte[] bytes = parcelW.marshall();
            parcelW.recycle();

            Parcel parcelR = Parcel.obtain();
            parcelR.unmarshall(bytes, 0, bytes.length);
            parcelR.setDataPosition(0);
            NetworkRequest rereadNr = NetworkRequest.CREATOR.createFromParcel(parcelR);
        });
    }

    @Test
    public void testNetworkRequestUidSpoofSecurityException() throws Exception {
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        NetworkRequest networkRequest = newWifiRequestBuilder().build();
        TestNetworkCallback networkCallback = new TestNetworkCallback();
        doThrow(new SecurityException()).when(mAppOpsManager).checkPackage(anyInt(), anyString());
        assertThrows(SecurityException.class, () -> {
            mCm.requestNetwork(networkRequest, networkCallback);
        });
    }

    @Test
    public void testInvalidSignalStrength() {
        NetworkRequest r = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_INTERNET)
                .addTransportType(TRANSPORT_WIFI)
                .setSignalStrength(-75)
                .build();
        // Registering a NetworkCallback with signal strength but w/o NETWORK_SIGNAL_STRENGTH_WAKEUP
        // permission should get SecurityException.
        assertThrows(SecurityException.class, () ->
                mCm.registerNetworkCallback(r, new NetworkCallback()));

        assertThrows(SecurityException.class, () ->
                mCm.registerNetworkCallback(r, PendingIntent.getService(
                        mServiceContext, 0 /* requestCode */, new Intent(), FLAG_IMMUTABLE)));

        // Requesting a Network with signal strength should get IllegalArgumentException.
        assertThrows(IllegalArgumentException.class, () ->
                mCm.requestNetwork(r, new NetworkCallback()));

        assertThrows(IllegalArgumentException.class, () ->
                mCm.requestNetwork(r, PendingIntent.getService(
                        mServiceContext, 0 /* requestCode */, new Intent(), FLAG_IMMUTABLE)));
    }

    @Test
    public void testRegisterDefaultNetworkCallback() throws Exception {
        // NETWORK_SETTINGS is necessary to call registerSystemDefaultNetworkCallback.
        mServiceContext.setPermission(Manifest.permission.NETWORK_SETTINGS,
                PERMISSION_GRANTED);

        final TestNetworkCallback defaultNetworkCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultNetworkCallback);
        defaultNetworkCallback.assertNoCallback();

        final Handler handler = new Handler(ConnectivityThread.getInstanceLooper());
        final TestNetworkCallback systemDefaultCallback = new TestNetworkCallback();
        mCm.registerSystemDefaultNetworkCallback(systemDefaultCallback, handler);
        systemDefaultCallback.assertNoCallback();

        // Create a TRANSPORT_CELLULAR request to keep the mobile interface up
        // whenever Wi-Fi is up. Without this, the mobile network agent is
        // reaped before any other activity can take place.
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.requestNetwork(cellRequest, cellNetworkCallback);
        cellNetworkCallback.assertNoCallback();

        // Bring up cell and expect CALLBACK_AVAILABLE.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        defaultNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        systemDefaultCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());
        assertEquals(systemDefaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring up wifi and expect CALLBACK_AVAILABLE.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        cellNetworkCallback.assertNoCallback();
        defaultNetworkCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        systemDefaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());
        assertEquals(systemDefaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring down cell. Expect no default network callback, since it wasn't the default.
        mCellNetworkAgent.disconnect();
        cellNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        defaultNetworkCallback.assertNoCallback();
        systemDefaultCallback.assertNoCallback();
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());
        assertEquals(systemDefaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring up cell. Expect no default network callback, since it won't be the default.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        defaultNetworkCallback.assertNoCallback();
        systemDefaultCallback.assertNoCallback();
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());
        assertEquals(systemDefaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring down wifi. Expect the default network callback to notified of LOST wifi
        // followed by AVAILABLE cell.
        mWiFiNetworkAgent.disconnect();
        cellNetworkCallback.assertNoCallback();
        defaultNetworkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        defaultNetworkCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        systemDefaultCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        systemDefaultCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        mCellNetworkAgent.disconnect();
        cellNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        defaultNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        systemDefaultCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        waitForIdle();
        assertEquals(null, mCm.getActiveNetwork());

        mMockVpn.establishForMyUid();
        assertUidRangesUpdatedForMyUid(true);
        defaultNetworkCallback.expectAvailableThenValidatedCallbacks(mMockVpn);
        systemDefaultCallback.assertNoCallback();
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());
        assertEquals(null, systemDefaultCallback.getLastAvailableNetwork());

        mMockVpn.disconnect();
        defaultNetworkCallback.expectCallback(CallbackEntry.LOST, mMockVpn);
        systemDefaultCallback.assertNoCallback();
        waitForIdle();
        assertEquals(null, mCm.getActiveNetwork());
    }

    @Test
    public void testAdditionalStateCallbacks() throws Exception {
        // File a network request for mobile.
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.requestNetwork(cellRequest, cellNetworkCallback);

        // Bring up the mobile network.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);

        // We should get onAvailable(), onCapabilitiesChanged(), and
        // onLinkPropertiesChanged() in rapid succession. Additionally, we
        // should get onCapabilitiesChanged() when the mobile network validates.
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();

        // Update LinkProperties.
        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("foonet_data0");
        mCellNetworkAgent.sendLinkProperties(lp);
        // We should get onLinkPropertiesChanged().
        cellNetworkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED,
                mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();

        // Suspend the network.
        mCellNetworkAgent.suspend();
        cellNetworkCallback.expectCapabilitiesWithout(NET_CAPABILITY_NOT_SUSPENDED,
                mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackEntry.SUSPENDED, mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertEquals(NetworkInfo.State.SUSPENDED, mCm.getActiveNetworkInfo().getState());

        // Register a garden variety default network request.
        TestNetworkCallback dfltNetworkCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(dfltNetworkCallback);
        // We should get onAvailable(), onCapabilitiesChanged(), onLinkPropertiesChanged(),
        // as well as onNetworkSuspended() in rapid succession.
        dfltNetworkCallback.expectAvailableAndSuspendedCallbacks(mCellNetworkAgent, true);
        dfltNetworkCallback.assertNoCallback();
        mCm.unregisterNetworkCallback(dfltNetworkCallback);

        mCellNetworkAgent.resume();
        cellNetworkCallback.expectCapabilitiesWith(NET_CAPABILITY_NOT_SUSPENDED,
                mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackEntry.RESUMED, mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertEquals(NetworkInfo.State.CONNECTED, mCm.getActiveNetworkInfo().getState());

        dfltNetworkCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(dfltNetworkCallback);
        // This time onNetworkSuspended should not be called.
        dfltNetworkCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        dfltNetworkCallback.assertNoCallback();

        mCm.unregisterNetworkCallback(dfltNetworkCallback);
        mCm.unregisterNetworkCallback(cellNetworkCallback);
    }

    @Test
    public void testRegisterSystemDefaultCallbackRequiresNetworkSettings() throws Exception {
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false /* validated */);

        final Handler handler = new Handler(ConnectivityThread.getInstanceLooper());
        final TestNetworkCallback callback = new TestNetworkCallback();
        assertThrows(SecurityException.class,
                () -> mCm.registerSystemDefaultNetworkCallback(callback, handler));
        callback.assertNoCallback();

        mServiceContext.setPermission(Manifest.permission.NETWORK_SETTINGS,
                PERMISSION_GRANTED);
        mCm.registerSystemDefaultNetworkCallback(callback, handler);
        callback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        mCm.unregisterNetworkCallback(callback);
    }

    private void setCaptivePortalMode(int mode) {
        ContentResolver cr = mServiceContext.getContentResolver();
        Settings.Global.putInt(cr, Settings.Global.CAPTIVE_PORTAL_MODE, mode);
    }

    private void setAlwaysOnNetworks(boolean enable) {
        ContentResolver cr = mServiceContext.getContentResolver();
        Settings.Global.putInt(cr, Settings.Global.MOBILE_DATA_ALWAYS_ON, enable ? 1 : 0);
        mService.updateAlwaysOnNetworks();
        waitForIdle();
    }

    private void setPrivateDnsSettings(String mode, String specifier) {
        final ContentResolver cr = mServiceContext.getContentResolver();
        Settings.Global.putString(cr, Settings.Global.PRIVATE_DNS_MODE, mode);
        Settings.Global.putString(cr, Settings.Global.PRIVATE_DNS_SPECIFIER, specifier);
        mService.updatePrivateDnsSettings();
        waitForIdle();
    }

    private boolean isForegroundNetwork(TestNetworkAgentWrapper network) {
        NetworkCapabilities nc = mCm.getNetworkCapabilities(network.getNetwork());
        assertNotNull(nc);
        return nc.hasCapability(NET_CAPABILITY_FOREGROUND);
    }

    @Test
    public void testBackgroundNetworks() throws Exception {
        // Create a cellular background request.
        grantUsingBackgroundNetworksPermissionForUid(Binder.getCallingUid());
        final TestNetworkCallback cellBgCallback = new TestNetworkCallback();
        mCm.requestBackgroundNetwork(new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build(), null, cellBgCallback);

        // Make callbacks for monitoring.
        final NetworkRequest request = new NetworkRequest.Builder().build();
        final NetworkRequest fgRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_FOREGROUND).build();
        final TestNetworkCallback callback = new TestNetworkCallback();
        final TestNetworkCallback fgCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);
        mCm.registerNetworkCallback(fgRequest, fgCallback);

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        fgCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        assertTrue(isForegroundNetwork(mCellNetworkAgent));

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);

        // When wifi connects, cell lingers.
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        fgCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        fgCallback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        fgCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        assertTrue(isForegroundNetwork(mCellNetworkAgent));
        assertTrue(isForegroundNetwork(mWiFiNetworkAgent));

        // When lingering is complete, cell is still there but is now in the background.
        waitForIdle();
        int timeoutMs = TEST_LINGER_DELAY_MS + TEST_LINGER_DELAY_MS / 4;
        fgCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent, timeoutMs);
        // Expect a network capabilities update sans FOREGROUND.
        callback.expectCapabilitiesWithout(NET_CAPABILITY_FOREGROUND, mCellNetworkAgent);
        assertFalse(isForegroundNetwork(mCellNetworkAgent));
        assertTrue(isForegroundNetwork(mWiFiNetworkAgent));

        // File a cell request and check that cell comes into the foreground.
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        final TestNetworkCallback cellCallback = new TestNetworkCallback();
        mCm.requestNetwork(cellRequest, cellCallback);
        cellCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        fgCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        // Expect a network capabilities update with FOREGROUND, because the most recent
        // request causes its state to change.
        cellCallback.expectCapabilitiesWith(NET_CAPABILITY_FOREGROUND, mCellNetworkAgent);
        callback.expectCapabilitiesWith(NET_CAPABILITY_FOREGROUND, mCellNetworkAgent);
        assertTrue(isForegroundNetwork(mCellNetworkAgent));
        assertTrue(isForegroundNetwork(mWiFiNetworkAgent));

        // Release the request. The network immediately goes into the background, since it was not
        // lingering.
        mCm.unregisterNetworkCallback(cellCallback);
        fgCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        // Expect a network capabilities update sans FOREGROUND.
        callback.expectCapabilitiesWithout(NET_CAPABILITY_FOREGROUND, mCellNetworkAgent);
        assertFalse(isForegroundNetwork(mCellNetworkAgent));
        assertTrue(isForegroundNetwork(mWiFiNetworkAgent));

        // Disconnect wifi and check that cell is foreground again.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        fgCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        fgCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        assertTrue(isForegroundNetwork(mCellNetworkAgent));

        mCm.unregisterNetworkCallback(callback);
        mCm.unregisterNetworkCallback(fgCallback);
        mCm.unregisterNetworkCallback(cellBgCallback);
    }

    @Ignore // This test has instrinsic chances of spurious failures: ignore for continuous testing.
    public void benchmarkRequestRegistrationAndCallbackDispatch() throws Exception {
        // TODO: turn this unit test into a real benchmarking test.
        // Benchmarks connecting and switching performance in the presence of a large number of
        // NetworkRequests.
        // 1. File NUM_REQUESTS requests.
        // 2. Have a network connect. Wait for NUM_REQUESTS onAvailable callbacks to fire.
        // 3. Have a new network connect and outscore the previous. Wait for NUM_REQUESTS onLosing
        //    and NUM_REQUESTS onAvailable callbacks to fire.
        // See how long it took.
        final int NUM_REQUESTS = 90;
        final int REGISTER_TIME_LIMIT_MS = 200;
        final int CONNECT_TIME_LIMIT_MS = 60;
        final int SWITCH_TIME_LIMIT_MS = 60;
        final int UNREGISTER_TIME_LIMIT_MS = 20;

        final NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
        final NetworkCallback[] callbacks = new NetworkCallback[NUM_REQUESTS];
        final CountDownLatch availableLatch = new CountDownLatch(NUM_REQUESTS);
        final CountDownLatch losingLatch = new CountDownLatch(NUM_REQUESTS);

        for (int i = 0; i < NUM_REQUESTS; i++) {
            callbacks[i] = new NetworkCallback() {
                @Override public void onAvailable(Network n) { availableLatch.countDown(); }
                @Override public void onLosing(Network n, int t) { losingLatch.countDown(); }
            };
        }

        assertRunsInAtMost("Registering callbacks", REGISTER_TIME_LIMIT_MS, () -> {
            for (NetworkCallback cb : callbacks) {
                mCm.registerNetworkCallback(request, cb);
            }
        });

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        // Don't request that the network validate, because otherwise connect() will block until
        // the network gets NET_CAPABILITY_VALIDATED, after all the callbacks below have fired,
        // and we won't actually measure anything.
        mCellNetworkAgent.connect(false);

        long onAvailableDispatchingDuration = durationOf(() -> {
            await(availableLatch, 10 * CONNECT_TIME_LIMIT_MS);
        });
        Log.d(TAG, String.format("Dispatched %d of %d onAvailable callbacks in %dms",
                NUM_REQUESTS - availableLatch.getCount(), NUM_REQUESTS,
                onAvailableDispatchingDuration));
        assertTrue(String.format("Dispatching %d onAvailable callbacks in %dms, expected %dms",
                NUM_REQUESTS, onAvailableDispatchingDuration, CONNECT_TIME_LIMIT_MS),
                onAvailableDispatchingDuration <= CONNECT_TIME_LIMIT_MS);

        // Give wifi a high enough score that we'll linger cell when wifi comes up.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.adjustScore(40);
        mWiFiNetworkAgent.connect(false);

        long onLostDispatchingDuration = durationOf(() -> {
            await(losingLatch, 10 * SWITCH_TIME_LIMIT_MS);
        });
        Log.d(TAG, String.format("Dispatched %d of %d onLosing callbacks in %dms",
                NUM_REQUESTS - losingLatch.getCount(), NUM_REQUESTS, onLostDispatchingDuration));
        assertTrue(String.format("Dispatching %d onLosing callbacks in %dms, expected %dms",
                NUM_REQUESTS, onLostDispatchingDuration, SWITCH_TIME_LIMIT_MS),
                onLostDispatchingDuration <= SWITCH_TIME_LIMIT_MS);

        assertRunsInAtMost("Unregistering callbacks", UNREGISTER_TIME_LIMIT_MS, () -> {
            for (NetworkCallback cb : callbacks) {
                mCm.unregisterNetworkCallback(cb);
            }
        });
    }

    @Test
    public void testMobileDataAlwaysOn() throws Exception {
        grantUsingBackgroundNetworksPermissionForUid(Binder.getCallingUid());
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.registerNetworkCallback(cellRequest, cellNetworkCallback);

        final HandlerThread handlerThread = new HandlerThread("MobileDataAlwaysOnFactory");
        handlerThread.start();
        NetworkCapabilities filter = new NetworkCapabilities()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                .addCapability(NET_CAPABILITY_INTERNET);
        final MockNetworkFactory testFactory = new MockNetworkFactory(handlerThread.getLooper(),
                mServiceContext, "testFactory", filter, mCsHandlerThread);
        testFactory.setScoreFilter(40);

        // Register the factory and expect it to start looking for a network.
        testFactory.register();

        try {
            testFactory.expectRequestAdd();
            testFactory.assertRequestCountEquals(1);
            assertTrue(testFactory.getMyStartRequested());

            // Bring up wifi. The factory stops looking for a network.
            mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
            // Score 60 - 40 penalty for not validated yet, then 60 when it validates
            mWiFiNetworkAgent.connect(true);
            // Default request and mobile always on request
            testFactory.expectRequestAdds(2);
            assertFalse(testFactory.getMyStartRequested());

            // Turn on mobile data always on. The factory starts looking again.
            setAlwaysOnNetworks(true);
            testFactory.expectRequestAdd();
            testFactory.assertRequestCountEquals(2);

            assertTrue(testFactory.getMyStartRequested());

            // Bring up cell data and check that the factory stops looking.
            assertLength(1, mCm.getAllNetworks());
            mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
            mCellNetworkAgent.connect(true);
            cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
            testFactory.expectRequestAdds(2); // Unvalidated and validated
            testFactory.assertRequestCountEquals(2);
            // The cell network outscores the factory filter, so start is not requested.
            assertFalse(testFactory.getMyStartRequested());

            // Check that cell data stays up.
            waitForIdle();
            verifyActiveNetwork(TRANSPORT_WIFI);
            assertLength(2, mCm.getAllNetworks());

            // Turn off mobile data always on and expect the request to disappear...
            setAlwaysOnNetworks(false);
            testFactory.expectRequestRemove();

            // ...  and cell data to be torn down after nascent network timeout.
            cellNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent,
                    mService.mNascentDelayMs + TEST_CALLBACK_TIMEOUT_MS);
            waitForIdle();
            assertLength(1, mCm.getAllNetworks());
        } finally {
            testFactory.terminate();
            mCm.unregisterNetworkCallback(cellNetworkCallback);
            handlerThread.quit();
        }
    }

    @Test
    public void testAvoidBadWifiSetting() throws Exception {
        final ContentResolver cr = mServiceContext.getContentResolver();
        final String settingName = Settings.Global.NETWORK_AVOID_BAD_WIFI;

        mPolicyTracker.mConfigRestrictsAvoidBadWifi = false;
        String[] values = new String[] {null, "0", "1"};
        for (int i = 0; i < values.length; i++) {
            Settings.Global.putInt(cr, settingName, 1);
            mPolicyTracker.reevaluate();
            waitForIdle();
            String msg = String.format("config=false, setting=%s", values[i]);
            assertTrue(mService.avoidBadWifi());
            assertFalse(msg, mPolicyTracker.shouldNotifyWifiUnvalidated());
        }

        mPolicyTracker.mConfigRestrictsAvoidBadWifi = true;

        Settings.Global.putInt(cr, settingName, 0);
        mPolicyTracker.reevaluate();
        waitForIdle();
        assertFalse(mService.avoidBadWifi());
        assertFalse(mPolicyTracker.shouldNotifyWifiUnvalidated());

        Settings.Global.putInt(cr, settingName, 1);
        mPolicyTracker.reevaluate();
        waitForIdle();
        assertTrue(mService.avoidBadWifi());
        assertFalse(mPolicyTracker.shouldNotifyWifiUnvalidated());

        Settings.Global.putString(cr, settingName, null);
        mPolicyTracker.reevaluate();
        waitForIdle();
        assertFalse(mService.avoidBadWifi());
        assertTrue(mPolicyTracker.shouldNotifyWifiUnvalidated());
    }

    @Test
    public void testAvoidBadWifi() throws Exception {
        final ContentResolver cr = mServiceContext.getContentResolver();

        // Pretend we're on a carrier that restricts switching away from bad wifi.
        mPolicyTracker.mConfigRestrictsAvoidBadWifi = true;

        // File a request for cell to ensure it doesn't go down.
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.requestNetwork(cellRequest, cellNetworkCallback);

        TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        NetworkRequest validatedWifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NET_CAPABILITY_VALIDATED)
                .build();
        TestNetworkCallback validatedWifiCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(validatedWifiRequest, validatedWifiCallback);

        Settings.Global.putInt(cr, Settings.Global.NETWORK_AVOID_BAD_WIFI, 0);
        mPolicyTracker.reevaluate();

        // Bring up validated cell.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        defaultCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        Network cellNetwork = mCellNetworkAgent.getNetwork();

        // Bring up validated wifi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        validatedWifiCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        Network wifiNetwork = mWiFiNetworkAgent.getNetwork();

        // Fail validation on wifi.
        mWiFiNetworkAgent.setNetworkInvalid(false /* isStrictMode */);
        mCm.reportNetworkConnectivity(wifiNetwork, false);
        defaultCallback.expectCapabilitiesWithout(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        validatedWifiCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);

        // Because avoid bad wifi is off, we don't switch to cellular.
        defaultCallback.assertNoCallback();
        assertFalse(mCm.getNetworkCapabilities(wifiNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertTrue(mCm.getNetworkCapabilities(cellNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertEquals(mCm.getActiveNetwork(), wifiNetwork);

        // Simulate switching to a carrier that does not restrict avoiding bad wifi, and expect
        // that we switch back to cell.
        mPolicyTracker.mConfigRestrictsAvoidBadWifi = false;
        mPolicyTracker.reevaluate();
        defaultCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        assertEquals(mCm.getActiveNetwork(), cellNetwork);

        // Switch back to a restrictive carrier.
        mPolicyTracker.mConfigRestrictsAvoidBadWifi = true;
        mPolicyTracker.reevaluate();
        defaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mCm.getActiveNetwork(), wifiNetwork);

        // Simulate the user selecting "switch" on the dialog, and check that we switch to cell.
        mCm.setAvoidUnvalidated(wifiNetwork);
        defaultCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        assertFalse(mCm.getNetworkCapabilities(wifiNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertTrue(mCm.getNetworkCapabilities(cellNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertEquals(mCm.getActiveNetwork(), cellNetwork);

        // Disconnect and reconnect wifi to clear the one-time switch above.
        mWiFiNetworkAgent.disconnect();
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        validatedWifiCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        wifiNetwork = mWiFiNetworkAgent.getNetwork();

        // Fail validation on wifi and expect the dialog to appear.
        mWiFiNetworkAgent.setNetworkInvalid(false /* isStrictMode */);
        mCm.reportNetworkConnectivity(wifiNetwork, false);
        defaultCallback.expectCapabilitiesWithout(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        validatedWifiCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);

        // Simulate the user selecting "switch" and checking the don't ask again checkbox.
        Settings.Global.putInt(cr, Settings.Global.NETWORK_AVOID_BAD_WIFI, 1);
        mPolicyTracker.reevaluate();

        // We now switch to cell.
        defaultCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        assertFalse(mCm.getNetworkCapabilities(wifiNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertTrue(mCm.getNetworkCapabilities(cellNetwork).hasCapability(
                NET_CAPABILITY_VALIDATED));
        assertEquals(mCm.getActiveNetwork(), cellNetwork);

        // Simulate the user turning the cellular fallback setting off and then on.
        // We switch to wifi and then to cell.
        Settings.Global.putString(cr, Settings.Global.NETWORK_AVOID_BAD_WIFI, null);
        mPolicyTracker.reevaluate();
        defaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mCm.getActiveNetwork(), wifiNetwork);
        Settings.Global.putInt(cr, Settings.Global.NETWORK_AVOID_BAD_WIFI, 1);
        mPolicyTracker.reevaluate();
        defaultCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        assertEquals(mCm.getActiveNetwork(), cellNetwork);

        // If cell goes down, we switch to wifi.
        mCellNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        validatedWifiCallback.assertNoCallback();

        mCm.unregisterNetworkCallback(cellNetworkCallback);
        mCm.unregisterNetworkCallback(validatedWifiCallback);
        mCm.unregisterNetworkCallback(defaultCallback);
    }

    @Test
    public void testMeteredMultipathPreferenceSetting() throws Exception {
        final ContentResolver cr = mServiceContext.getContentResolver();
        final String settingName = Settings.Global.NETWORK_METERED_MULTIPATH_PREFERENCE;

        for (int config : Arrays.asList(0, 3, 2)) {
            for (String setting: Arrays.asList(null, "0", "2", "1")) {
                mPolicyTracker.mConfigMeteredMultipathPreference = config;
                Settings.Global.putString(cr, settingName, setting);
                mPolicyTracker.reevaluate();
                waitForIdle();

                final int expected = (setting != null) ? Integer.parseInt(setting) : config;
                String msg = String.format("config=%d, setting=%s", config, setting);
                assertEquals(msg, expected, mCm.getMultipathPreference(null));
            }
        }
    }

    /**
     * Validate that a satisfied network request does not trigger onUnavailable() once the
     * time-out period expires.
     */
    @Test
    public void testSatisfiedNetworkRequestDoesNotTriggerOnUnavailable() throws Exception {
        NetworkRequest nr = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.requestNetwork(nr, networkCallback, TEST_REQUEST_TIMEOUT_MS);

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        networkCallback.expectAvailableCallbacks(mWiFiNetworkAgent, false, false, false,
                TEST_CALLBACK_TIMEOUT_MS);

        // pass timeout and validate that UNAVAILABLE is not called
        networkCallback.assertNoCallback();
    }

    /**
     * Validate that a satisfied network request followed by a disconnected (lost) network does
     * not trigger onUnavailable() once the time-out period expires.
     */
    @Test
    public void testSatisfiedThenLostNetworkRequestDoesNotTriggerOnUnavailable() throws Exception {
        NetworkRequest nr = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.requestNetwork(nr, networkCallback, TEST_REQUEST_TIMEOUT_MS);

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        networkCallback.expectAvailableCallbacks(mWiFiNetworkAgent, false, false, false,
                TEST_CALLBACK_TIMEOUT_MS);
        mWiFiNetworkAgent.disconnect();
        networkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);

        // Validate that UNAVAILABLE is not called
        networkCallback.assertNoCallback();
    }

    /**
     * Validate that when a time-out is specified for a network request the onUnavailable()
     * callback is called when time-out expires. Then validate that if network request is
     * (somehow) satisfied - the callback isn't called later.
     */
    @Test
    public void testTimedoutNetworkRequest() throws Exception {
        NetworkRequest nr = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        final int timeoutMs = 10;
        mCm.requestNetwork(nr, networkCallback, timeoutMs);

        // pass timeout and validate that UNAVAILABLE is called
        networkCallback.expectCallback(CallbackEntry.UNAVAILABLE, (Network) null);

        // create a network satisfying request - validate that request not triggered
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        networkCallback.assertNoCallback();
    }

    /**
     * Validate that when a network request is unregistered (cancelled), no posterior event can
     * trigger the callback.
     */
    @Test
    public void testNoCallbackAfterUnregisteredNetworkRequest() throws Exception {
        NetworkRequest nr = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        final int timeoutMs = 10;

        mCm.requestNetwork(nr, networkCallback, timeoutMs);
        mCm.unregisterNetworkCallback(networkCallback);
        // Regardless of the timeout, unregistering the callback in ConnectivityManager ensures
        // that this callback will not be called.
        networkCallback.assertNoCallback();

        // create a network satisfying request - validate that request not triggered
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        networkCallback.assertNoCallback();
    }

    @Test
    public void testUnfulfillableNetworkRequest() throws Exception {
        runUnfulfillableNetworkRequest(false);
    }

    @Test
    public void testUnfulfillableNetworkRequestAfterUnregister() throws Exception {
        runUnfulfillableNetworkRequest(true);
    }

    /**
     * Validate the callback flow for a factory releasing a request as unfulfillable.
     */
    private void runUnfulfillableNetworkRequest(boolean preUnregister) throws Exception {
        NetworkRequest nr = new NetworkRequest.Builder().addTransportType(
                NetworkCapabilities.TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();

        final HandlerThread handlerThread = new HandlerThread("testUnfulfillableNetworkRequest");
        handlerThread.start();
        NetworkCapabilities filter = new NetworkCapabilities()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NET_CAPABILITY_INTERNET);
        final MockNetworkFactory testFactory = new MockNetworkFactory(handlerThread.getLooper(),
                mServiceContext, "testFactory", filter, mCsHandlerThread);
        testFactory.setScoreFilter(40);

        // Register the factory and expect it to receive the default request.
        testFactory.register();
        testFactory.expectRequestAdd();

        // Now file the test request and expect it.
        mCm.requestNetwork(nr, networkCallback);
        final NetworkRequest newRequest = testFactory.expectRequestAdd().request;

        if (preUnregister) {
            mCm.unregisterNetworkCallback(networkCallback);

            // Simulate the factory releasing the request as unfulfillable: no-op since
            // the callback has already been unregistered (but a test that no exceptions are
            // thrown).
            testFactory.triggerUnfulfillable(newRequest);
        } else {
            // Simulate the factory releasing the request as unfulfillable and expect onUnavailable!
            testFactory.triggerUnfulfillable(newRequest);

            networkCallback.expectCallback(CallbackEntry.UNAVAILABLE, (Network) null);

            // unregister network callback - a no-op (since already freed by the
            // on-unavailable), but should not fail or throw exceptions.
            mCm.unregisterNetworkCallback(networkCallback);
        }

        testFactory.expectRequestRemove();

        testFactory.terminate();
        handlerThread.quit();
    }

    private static class TestKeepaliveCallback extends PacketKeepaliveCallback {

        public enum CallbackType { ON_STARTED, ON_STOPPED, ON_ERROR }

        private class CallbackValue {
            public CallbackType callbackType;
            public int error;

            public CallbackValue(CallbackType type) {
                this.callbackType = type;
                this.error = PacketKeepalive.SUCCESS;
                assertTrue("onError callback must have error", type != CallbackType.ON_ERROR);
            }

            public CallbackValue(CallbackType type, int error) {
                this.callbackType = type;
                this.error = error;
                assertEquals("error can only be set for onError", type, CallbackType.ON_ERROR);
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof CallbackValue &&
                        this.callbackType == ((CallbackValue) o).callbackType &&
                        this.error == ((CallbackValue) o).error;
            }

            @Override
            public String toString() {
                return String.format("%s(%s, %d)", getClass().getSimpleName(), callbackType, error);
            }
        }

        private final LinkedBlockingQueue<CallbackValue> mCallbacks = new LinkedBlockingQueue<>();

        @Override
        public void onStarted() {
            mCallbacks.add(new CallbackValue(CallbackType.ON_STARTED));
        }

        @Override
        public void onStopped() {
            mCallbacks.add(new CallbackValue(CallbackType.ON_STOPPED));
        }

        @Override
        public void onError(int error) {
            mCallbacks.add(new CallbackValue(CallbackType.ON_ERROR, error));
        }

        private void expectCallback(CallbackValue callbackValue) throws InterruptedException {
            assertEquals(callbackValue, mCallbacks.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }

        public void expectStarted() throws Exception {
            expectCallback(new CallbackValue(CallbackType.ON_STARTED));
        }

        public void expectStopped() throws Exception {
            expectCallback(new CallbackValue(CallbackType.ON_STOPPED));
        }

        public void expectError(int error) throws Exception {
            expectCallback(new CallbackValue(CallbackType.ON_ERROR, error));
        }
    }

    private static class TestSocketKeepaliveCallback extends SocketKeepalive.Callback {

        public enum CallbackType { ON_STARTED, ON_STOPPED, ON_ERROR };

        private class CallbackValue {
            public CallbackType callbackType;
            public int error;

            CallbackValue(CallbackType type) {
                this.callbackType = type;
                this.error = SocketKeepalive.SUCCESS;
                assertTrue("onError callback must have error", type != CallbackType.ON_ERROR);
            }

            CallbackValue(CallbackType type, int error) {
                this.callbackType = type;
                this.error = error;
                assertEquals("error can only be set for onError", type, CallbackType.ON_ERROR);
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof CallbackValue
                        && this.callbackType == ((CallbackValue) o).callbackType
                        && this.error == ((CallbackValue) o).error;
            }

            @Override
            public String toString() {
                return String.format("%s(%s, %d)", getClass().getSimpleName(), callbackType,
                        error);
            }
        }

        private LinkedBlockingQueue<CallbackValue> mCallbacks = new LinkedBlockingQueue<>();
        private final Executor mExecutor;

        TestSocketKeepaliveCallback(@NonNull Executor executor) {
            mExecutor = executor;
        }

        @Override
        public void onStarted() {
            mCallbacks.add(new CallbackValue(CallbackType.ON_STARTED));
        }

        @Override
        public void onStopped() {
            mCallbacks.add(new CallbackValue(CallbackType.ON_STOPPED));
        }

        @Override
        public void onError(int error) {
            mCallbacks.add(new CallbackValue(CallbackType.ON_ERROR, error));
        }

        private void expectCallback(CallbackValue callbackValue) throws InterruptedException {
            assertEquals(callbackValue, mCallbacks.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        }

        public void expectStarted() throws InterruptedException {
            expectCallback(new CallbackValue(CallbackType.ON_STARTED));
        }

        public void expectStopped() throws InterruptedException {
            expectCallback(new CallbackValue(CallbackType.ON_STOPPED));
        }

        public void expectError(int error) throws InterruptedException {
            expectCallback(new CallbackValue(CallbackType.ON_ERROR, error));
        }

        public void assertNoCallback() {
            waitForIdleSerialExecutor(mExecutor, TIMEOUT_MS);
            CallbackValue cv = mCallbacks.peek();
            assertNull("Unexpected callback: " + cv, cv);
        }
    }

    private Network connectKeepaliveNetwork(LinkProperties lp) throws Exception {
        // Ensure the network is disconnected before anything else occurs
        if (mWiFiNetworkAgent != null) {
            assertNull(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()));
        }

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        ExpectedBroadcast b = expectConnectivityAction(TYPE_WIFI, DetailedState.CONNECTED);
        mWiFiNetworkAgent.connect(true);
        b.expectBroadcast();
        verifyActiveNetwork(TRANSPORT_WIFI);
        mWiFiNetworkAgent.sendLinkProperties(lp);
        waitForIdle();
        return mWiFiNetworkAgent.getNetwork();
    }

    @Test
    public void testPacketKeepalives() throws Exception {
        InetAddress myIPv4 = InetAddress.getByName("192.0.2.129");
        InetAddress notMyIPv4 = InetAddress.getByName("192.0.2.35");
        InetAddress myIPv6 = InetAddress.getByName("2001:db8::1");
        InetAddress dstIPv4 = InetAddress.getByName("8.8.8.8");
        InetAddress dstIPv6 = InetAddress.getByName("2001:4860:4860::8888");

        final int validKaInterval = 15;
        final int invalidKaInterval = 9;

        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("wlan12");
        lp.addLinkAddress(new LinkAddress(myIPv6, 64));
        lp.addLinkAddress(new LinkAddress(myIPv4, 25));
        lp.addRoute(new RouteInfo(InetAddress.getByName("fe80::1234")));
        lp.addRoute(new RouteInfo(InetAddress.getByName("192.0.2.254")));

        Network notMyNet = new Network(61234);
        Network myNet = connectKeepaliveNetwork(lp);

        TestKeepaliveCallback callback = new TestKeepaliveCallback();
        PacketKeepalive ka;

        // Attempt to start keepalives with invalid parameters and check for errors.
        ka = mCm.startNattKeepalive(notMyNet, validKaInterval, callback, myIPv4, 1234, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_NETWORK);

        ka = mCm.startNattKeepalive(myNet, invalidKaInterval, callback, myIPv4, 1234, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_INTERVAL);

        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 1234, dstIPv6);
        callback.expectError(PacketKeepalive.ERROR_INVALID_IP_ADDRESS);

        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv6, 1234, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_IP_ADDRESS);

        // NAT-T is only supported for IPv4.
        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv6, 1234, dstIPv6);
        callback.expectError(PacketKeepalive.ERROR_INVALID_IP_ADDRESS);

        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 123456, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_PORT);

        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 123456, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_INVALID_PORT);

        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 12345, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_HARDWARE_UNSUPPORTED);

        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 12345, dstIPv4);
        callback.expectError(PacketKeepalive.ERROR_HARDWARE_UNSUPPORTED);

        // Check that a started keepalive can be stopped.
        mWiFiNetworkAgent.setStartKeepaliveEvent(PacketKeepalive.SUCCESS);
        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 12345, dstIPv4);
        callback.expectStarted();
        mWiFiNetworkAgent.setStopKeepaliveEvent(PacketKeepalive.SUCCESS);
        ka.stop();
        callback.expectStopped();

        // Check that deleting the IP address stops the keepalive.
        LinkProperties bogusLp = new LinkProperties(lp);
        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 12345, dstIPv4);
        callback.expectStarted();
        bogusLp.removeLinkAddress(new LinkAddress(myIPv4, 25));
        bogusLp.addLinkAddress(new LinkAddress(notMyIPv4, 25));
        mWiFiNetworkAgent.sendLinkProperties(bogusLp);
        callback.expectError(PacketKeepalive.ERROR_INVALID_IP_ADDRESS);
        mWiFiNetworkAgent.sendLinkProperties(lp);

        // Check that a started keepalive is stopped correctly when the network disconnects.
        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 12345, dstIPv4);
        callback.expectStarted();
        mWiFiNetworkAgent.disconnect();
        mWiFiNetworkAgent.expectDisconnected();
        callback.expectError(PacketKeepalive.ERROR_INVALID_NETWORK);

        // ... and that stopping it after that has no adverse effects.
        waitForIdle();
        final Network myNetAlias = myNet;
        assertNull(mCm.getNetworkCapabilities(myNetAlias));
        ka.stop();

        // Reconnect.
        myNet = connectKeepaliveNetwork(lp);
        mWiFiNetworkAgent.setStartKeepaliveEvent(PacketKeepalive.SUCCESS);

        // Check that keepalive slots start from 1 and increment. The first one gets slot 1.
        mWiFiNetworkAgent.setExpectedKeepaliveSlot(1);
        ka = mCm.startNattKeepalive(myNet, validKaInterval, callback, myIPv4, 12345, dstIPv4);
        callback.expectStarted();

        // The second one gets slot 2.
        mWiFiNetworkAgent.setExpectedKeepaliveSlot(2);
        TestKeepaliveCallback callback2 = new TestKeepaliveCallback();
        PacketKeepalive ka2 = mCm.startNattKeepalive(
                myNet, validKaInterval, callback2, myIPv4, 6789, dstIPv4);
        callback2.expectStarted();

        // Now stop the first one and create a third. This also gets slot 1.
        ka.stop();
        callback.expectStopped();

        mWiFiNetworkAgent.setExpectedKeepaliveSlot(1);
        TestKeepaliveCallback callback3 = new TestKeepaliveCallback();
        PacketKeepalive ka3 = mCm.startNattKeepalive(
                myNet, validKaInterval, callback3, myIPv4, 9876, dstIPv4);
        callback3.expectStarted();

        ka2.stop();
        callback2.expectStopped();

        ka3.stop();
        callback3.expectStopped();
    }

    // Helper method to prepare the executor and run test
    private void runTestWithSerialExecutors(ExceptionUtils.ThrowingConsumer<Executor> functor)
            throws Exception {
        final ExecutorService executorSingleThread = Executors.newSingleThreadExecutor();
        final Executor executorInline = (Runnable r) -> r.run();
        functor.accept(executorSingleThread);
        executorSingleThread.shutdown();
        functor.accept(executorInline);
    }

    @Test
    public void testNattSocketKeepalives() throws Exception {
        runTestWithSerialExecutors(executor -> doTestNattSocketKeepalivesWithExecutor(executor));
        runTestWithSerialExecutors(executor -> doTestNattSocketKeepalivesFdWithExecutor(executor));
    }

    private void doTestNattSocketKeepalivesWithExecutor(Executor executor) throws Exception {
        // TODO: 1. Move this outside of ConnectivityServiceTest.
        //       2. Make test to verify that Nat-T keepalive socket is created by IpSecService.
        //       3. Mock ipsec service.
        final InetAddress myIPv4 = InetAddress.getByName("192.0.2.129");
        final InetAddress notMyIPv4 = InetAddress.getByName("192.0.2.35");
        final InetAddress myIPv6 = InetAddress.getByName("2001:db8::1");
        final InetAddress dstIPv4 = InetAddress.getByName("8.8.8.8");
        final InetAddress dstIPv6 = InetAddress.getByName("2001:4860:4860::8888");

        final int validKaInterval = 15;
        final int invalidKaInterval = 9;

        final IpSecManager mIpSec = (IpSecManager) mContext.getSystemService(Context.IPSEC_SERVICE);
        final UdpEncapsulationSocket testSocket = mIpSec.openUdpEncapsulationSocket();
        final int srcPort = testSocket.getPort();

        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("wlan12");
        lp.addLinkAddress(new LinkAddress(myIPv6, 64));
        lp.addLinkAddress(new LinkAddress(myIPv4, 25));
        lp.addRoute(new RouteInfo(InetAddress.getByName("fe80::1234")));
        lp.addRoute(new RouteInfo(InetAddress.getByName("192.0.2.254")));

        Network notMyNet = new Network(61234);
        Network myNet = connectKeepaliveNetwork(lp);

        TestSocketKeepaliveCallback callback = new TestSocketKeepaliveCallback(executor);

        // Attempt to start keepalives with invalid parameters and check for errors.
        // Invalid network.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                notMyNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_NETWORK);
        }

        // Invalid interval.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(invalidKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_INTERVAL);
        }

        // Invalid destination.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv6, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_IP_ADDRESS);
        }

        // Invalid source;
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv6, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_IP_ADDRESS);
        }

        // NAT-T is only supported for IPv4.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv6, dstIPv6, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_IP_ADDRESS);
        }

        // Basic check before testing started keepalive.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_UNSUPPORTED);
        }

        // Check that a started keepalive can be stopped.
        mWiFiNetworkAgent.setStartKeepaliveEvent(SocketKeepalive.SUCCESS);
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectStarted();
            mWiFiNetworkAgent.setStopKeepaliveEvent(SocketKeepalive.SUCCESS);
            ka.stop();
            callback.expectStopped();

            // Check that keepalive could be restarted.
            ka.start(validKaInterval);
            callback.expectStarted();
            ka.stop();
            callback.expectStopped();

            // Check that keepalive can be restarted without waiting for callback.
            ka.start(validKaInterval);
            callback.expectStarted();
            ka.stop();
            ka.start(validKaInterval);
            callback.expectStopped();
            callback.expectStarted();
            ka.stop();
            callback.expectStopped();
        }

        // Check that deleting the IP address stops the keepalive.
        LinkProperties bogusLp = new LinkProperties(lp);
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectStarted();
            bogusLp.removeLinkAddress(new LinkAddress(myIPv4, 25));
            bogusLp.addLinkAddress(new LinkAddress(notMyIPv4, 25));
            mWiFiNetworkAgent.sendLinkProperties(bogusLp);
            callback.expectError(SocketKeepalive.ERROR_INVALID_IP_ADDRESS);
            mWiFiNetworkAgent.sendLinkProperties(lp);
        }

        // Check that a started keepalive is stopped correctly when the network disconnects.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectStarted();
            mWiFiNetworkAgent.disconnect();
            mWiFiNetworkAgent.expectDisconnected();
            callback.expectError(SocketKeepalive.ERROR_INVALID_NETWORK);

            // ... and that stopping it after that has no adverse effects.
            waitForIdle();
            final Network myNetAlias = myNet;
            assertNull(mCm.getNetworkCapabilities(myNetAlias));
            ka.stop();
            callback.assertNoCallback();
        }

        // Reconnect.
        myNet = connectKeepaliveNetwork(lp);
        mWiFiNetworkAgent.setStartKeepaliveEvent(SocketKeepalive.SUCCESS);

        // Check that a stop followed by network disconnects does not result in crash.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectStarted();
            // Delay the response of keepalive events in networkAgent long enough to make sure
            // the follow-up network disconnection will be processed first.
            mWiFiNetworkAgent.setKeepaliveResponseDelay(3 * TIMEOUT_MS);
            ka.stop();

            // Make sure the stop has been processed. Wait for executor idle is needed to prevent
            // flaky since the actual stop call to the service is delegated to executor thread.
            waitForIdleSerialExecutor(executor, TIMEOUT_MS);
            waitForIdle();

            mWiFiNetworkAgent.disconnect();
            mWiFiNetworkAgent.expectDisconnected();
            callback.expectStopped();
            callback.assertNoCallback();
        }

        // Reconnect.
        waitForIdle();
        myNet = connectKeepaliveNetwork(lp);
        mWiFiNetworkAgent.setStartKeepaliveEvent(SocketKeepalive.SUCCESS);

        // Check that keepalive slots start from 1 and increment. The first one gets slot 1.
        mWiFiNetworkAgent.setExpectedKeepaliveSlot(1);
        int srcPort2 = 0;
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
                myNet, testSocket, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectStarted();

            // The second one gets slot 2.
            mWiFiNetworkAgent.setExpectedKeepaliveSlot(2);
            final UdpEncapsulationSocket testSocket2 = mIpSec.openUdpEncapsulationSocket();
            srcPort2 = testSocket2.getPort();
            TestSocketKeepaliveCallback callback2 = new TestSocketKeepaliveCallback(executor);
            try (SocketKeepalive ka2 = mCm.createSocketKeepalive(
                    myNet, testSocket2, myIPv4, dstIPv4, executor, callback2)) {
                ka2.start(validKaInterval);
                callback2.expectStarted();

                ka.stop();
                callback.expectStopped();

                ka2.stop();
                callback2.expectStopped();

                testSocket.close();
                testSocket2.close();
            }
        }

        // Check that there is no port leaked after all keepalives and sockets are closed.
        // TODO: enable this check after ensuring a valid free port. See b/129512753#comment7.
        // assertFalse(isUdpPortInUse(srcPort));
        // assertFalse(isUdpPortInUse(srcPort2));

        mWiFiNetworkAgent.disconnect();
        mWiFiNetworkAgent.expectDisconnected();
        mWiFiNetworkAgent = null;
    }

    @Test
    public void testTcpSocketKeepalives() throws Exception {
        runTestWithSerialExecutors(executor -> doTestTcpSocketKeepalivesWithExecutor(executor));
    }

    private void doTestTcpSocketKeepalivesWithExecutor(Executor executor) throws Exception {
        final int srcPortV4 = 12345;
        final int srcPortV6 = 23456;
        final InetAddress myIPv4 = InetAddress.getByName("127.0.0.1");
        final InetAddress myIPv6 = InetAddress.getByName("::1");

        final int validKaInterval = 15;

        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("wlan12");
        lp.addLinkAddress(new LinkAddress(myIPv6, 64));
        lp.addLinkAddress(new LinkAddress(myIPv4, 25));
        lp.addRoute(new RouteInfo(InetAddress.getByName("fe80::1234")));
        lp.addRoute(new RouteInfo(InetAddress.getByName("127.0.0.254")));

        final Network notMyNet = new Network(61234);
        final Network myNet = connectKeepaliveNetwork(lp);

        final Socket testSocketV4 = new Socket();
        final Socket testSocketV6 = new Socket();

        TestSocketKeepaliveCallback callback = new TestSocketKeepaliveCallback(executor);

        // Attempt to start Tcp keepalives with invalid parameters and check for errors.
        // Invalid network.
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
            notMyNet, testSocketV4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_NETWORK);
        }

        // Invalid Socket (socket is not bound with IPv4 address).
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
            myNet, testSocketV4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_SOCKET);
        }

        // Invalid Socket (socket is not bound with IPv6 address).
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
            myNet, testSocketV6, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_SOCKET);
        }

        // Bind the socket address
        testSocketV4.bind(new InetSocketAddress(myIPv4, srcPortV4));
        testSocketV6.bind(new InetSocketAddress(myIPv6, srcPortV6));

        // Invalid Socket (socket is bound with IPv4 address).
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
            myNet, testSocketV4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_SOCKET);
        }

        // Invalid Socket (socket is bound with IPv6 address).
        try (SocketKeepalive ka = mCm.createSocketKeepalive(
            myNet, testSocketV6, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectError(SocketKeepalive.ERROR_INVALID_SOCKET);
        }

        testSocketV4.close();
        testSocketV6.close();

        mWiFiNetworkAgent.disconnect();
        mWiFiNetworkAgent.expectDisconnected();
        mWiFiNetworkAgent = null;
    }

    private void doTestNattSocketKeepalivesFdWithExecutor(Executor executor) throws Exception {
        final InetAddress myIPv4 = InetAddress.getByName("192.0.2.129");
        final InetAddress anyIPv4 = InetAddress.getByName("0.0.0.0");
        final InetAddress dstIPv4 = InetAddress.getByName("8.8.8.8");
        final int validKaInterval = 15;

        // Prepare the target network.
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("wlan12");
        lp.addLinkAddress(new LinkAddress(myIPv4, 25));
        lp.addRoute(new RouteInfo(InetAddress.getByName("192.0.2.254")));
        Network myNet = connectKeepaliveNetwork(lp);
        mWiFiNetworkAgent.setStartKeepaliveEvent(SocketKeepalive.SUCCESS);
        mWiFiNetworkAgent.setStopKeepaliveEvent(SocketKeepalive.SUCCESS);

        TestSocketKeepaliveCallback callback = new TestSocketKeepaliveCallback(executor);

        // Prepare the target file descriptor, keep only one instance.
        final IpSecManager mIpSec = (IpSecManager) mContext.getSystemService(Context.IPSEC_SERVICE);
        final UdpEncapsulationSocket testSocket = mIpSec.openUdpEncapsulationSocket();
        final int srcPort = testSocket.getPort();
        final ParcelFileDescriptor testPfd =
                ParcelFileDescriptor.dup(testSocket.getFileDescriptor());
        testSocket.close();
        assertTrue(isUdpPortInUse(srcPort));

        // Start keepalive and explicit make the variable goes out of scope with try-with-resources
        // block.
        try (SocketKeepalive ka = mCm.createNattKeepalive(
                myNet, testPfd, myIPv4, dstIPv4, executor, callback)) {
            ka.start(validKaInterval);
            callback.expectStarted();
            ka.stop();
            callback.expectStopped();
        }

        // Check that the ParcelFileDescriptor is still valid after keepalive stopped,
        // ErrnoException with EBADF will be thrown if the socket is closed when checking local
        // address.
        assertTrue(isUdpPortInUse(srcPort));
        final InetSocketAddress sa =
                (InetSocketAddress) Os.getsockname(testPfd.getFileDescriptor());
        assertEquals(anyIPv4, sa.getAddress());

        testPfd.close();
        // TODO: enable this check after ensuring a valid free port. See b/129512753#comment7.
        // assertFalse(isUdpPortInUse(srcPort));

        mWiFiNetworkAgent.disconnect();
        mWiFiNetworkAgent.expectDisconnected();
        mWiFiNetworkAgent = null;
    }

    private static boolean isUdpPortInUse(int port) {
        try (DatagramSocket ignored = new DatagramSocket(port)) {
            return false;
        } catch (IOException alreadyInUse) {
            return true;
        }
    }

    @Test
    public void testGetCaptivePortalServerUrl() throws Exception {
        String url = mCm.getCaptivePortalServerUrl();
        assertEquals("http://connectivitycheck.gstatic.com/generate_204", url);
    }

    private static class TestNetworkPinner extends NetworkPinner {
        public static boolean awaitPin(int timeoutMs) throws InterruptedException {
            synchronized(sLock) {
                if (sNetwork == null) {
                    sLock.wait(timeoutMs);
                }
                return sNetwork != null;
            }
        }

        public static boolean awaitUnpin(int timeoutMs) throws InterruptedException {
            synchronized(sLock) {
                if (sNetwork != null) {
                    sLock.wait(timeoutMs);
                }
                return sNetwork == null;
            }
        }
    }

    private void assertPinnedToWifiWithCellDefault() {
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getBoundNetworkForProcess());
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
    }

    private void assertPinnedToWifiWithWifiDefault() {
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getBoundNetworkForProcess());
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
    }

    private void assertNotPinnedToWifi() {
        assertNull(mCm.getBoundNetworkForProcess());
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
    }

    @Test
    public void testNetworkPinner() throws Exception {
        NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .build();
        assertNull(mCm.getBoundNetworkForProcess());

        TestNetworkPinner.pin(mServiceContext, wifiRequest);
        assertNull(mCm.getBoundNetworkForProcess());

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);

        // When wi-fi connects, expect to be pinned.
        assertTrue(TestNetworkPinner.awaitPin(100));
        assertPinnedToWifiWithCellDefault();

        // Disconnect and expect the pin to drop.
        mWiFiNetworkAgent.disconnect();
        assertTrue(TestNetworkPinner.awaitUnpin(100));
        assertNotPinnedToWifi();

        // Reconnecting does not cause the pin to come back.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        assertFalse(TestNetworkPinner.awaitPin(100));
        assertNotPinnedToWifi();

        // Pinning while connected causes the pin to take effect immediately.
        TestNetworkPinner.pin(mServiceContext, wifiRequest);
        assertTrue(TestNetworkPinner.awaitPin(100));
        assertPinnedToWifiWithCellDefault();

        // Explicitly unpin and expect to use the default network again.
        TestNetworkPinner.unpin();
        assertNotPinnedToWifi();

        // Disconnect cell and wifi.
        ExpectedBroadcast b = registerConnectivityBroadcast(3);  // cell down, wifi up, wifi down.
        mCellNetworkAgent.disconnect();
        mWiFiNetworkAgent.disconnect();
        b.expectBroadcast();

        // Pinning takes effect even if the pinned network is the default when the pin is set...
        TestNetworkPinner.pin(mServiceContext, wifiRequest);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        assertTrue(TestNetworkPinner.awaitPin(100));
        assertPinnedToWifiWithWifiDefault();

        // ... and is maintained even when that network is no longer the default.
        b = registerConnectivityBroadcast(1);
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mCellNetworkAgent.connect(true);
        b.expectBroadcast();
        assertPinnedToWifiWithCellDefault();
    }

    @Test
    public void testNetworkCallbackMaximum() {
        final int MAX_REQUESTS = 100;
        final int CALLBACKS = 89;
        final int INTENTS = 11;
        assertEquals(MAX_REQUESTS, CALLBACKS + INTENTS);

        NetworkRequest networkRequest = new NetworkRequest.Builder().build();
        ArrayList<Object> registered = new ArrayList<>();

        int j = 0;
        while (j++ < CALLBACKS / 2) {
            NetworkCallback cb = new NetworkCallback();
            mCm.requestNetwork(networkRequest, cb);
            registered.add(cb);
        }
        while (j++ < CALLBACKS) {
            NetworkCallback cb = new NetworkCallback();
            mCm.registerNetworkCallback(networkRequest, cb);
            registered.add(cb);
        }
        j = 0;
        while (j++ < INTENTS / 2) {
            final PendingIntent pi = PendingIntent.getBroadcast(mContext, 0 /* requestCode */,
                    new Intent("a" + j), FLAG_IMMUTABLE);
            mCm.requestNetwork(networkRequest, pi);
            registered.add(pi);
        }
        while (j++ < INTENTS) {
            final PendingIntent pi = PendingIntent.getBroadcast(mContext, 0 /* requestCode */,
                    new Intent("b" + j), FLAG_IMMUTABLE);
            mCm.registerNetworkCallback(networkRequest, pi);
            registered.add(pi);
        }

        // Test that the limit is enforced when MAX_REQUESTS simultaneous requests are added.
        assertThrows(TooManyRequestsException.class, () ->
                mCm.requestNetwork(networkRequest, new NetworkCallback())
        );
        assertThrows(TooManyRequestsException.class, () ->
                mCm.registerNetworkCallback(networkRequest, new NetworkCallback())
        );
        assertThrows(TooManyRequestsException.class, () ->
                mCm.requestNetwork(networkRequest,
                        PendingIntent.getBroadcast(mContext, 0 /* requestCode */,
                                new Intent("c"), FLAG_IMMUTABLE))
        );
        assertThrows(TooManyRequestsException.class, () ->
                mCm.registerNetworkCallback(networkRequest,
                        PendingIntent.getBroadcast(mContext, 0 /* requestCode */,
                                new Intent("d"), FLAG_IMMUTABLE))
        );

        for (Object o : registered) {
            if (o instanceof NetworkCallback) {
                mCm.unregisterNetworkCallback((NetworkCallback)o);
            }
            if (o instanceof PendingIntent) {
                mCm.unregisterNetworkCallback((PendingIntent)o);
            }
        }
        waitForIdle();

        // Test that the limit is not hit when MAX_REQUESTS requests are added and removed.
        for (int i = 0; i < MAX_REQUESTS; i++) {
            NetworkCallback networkCallback = new NetworkCallback();
            mCm.requestNetwork(networkRequest, networkCallback);
            mCm.unregisterNetworkCallback(networkCallback);
        }
        waitForIdle();

        for (int i = 0; i < MAX_REQUESTS; i++) {
            NetworkCallback networkCallback = new NetworkCallback();
            mCm.registerNetworkCallback(networkRequest, networkCallback);
            mCm.unregisterNetworkCallback(networkCallback);
        }
        waitForIdle();

        for (int i = 0; i < MAX_REQUESTS; i++) {
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    mContext, 0 /* requestCode */, new Intent("e" + i), FLAG_IMMUTABLE);
            mCm.requestNetwork(networkRequest, pendingIntent);
            mCm.unregisterNetworkCallback(pendingIntent);
        }
        waitForIdle();

        for (int i = 0; i < MAX_REQUESTS; i++) {
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    mContext, 0 /* requestCode */, new Intent("f" + i), FLAG_IMMUTABLE);
            mCm.registerNetworkCallback(networkRequest, pendingIntent);
            mCm.unregisterNetworkCallback(pendingIntent);
        }
    }

    @Test
    public void testNetworkInfoOfTypeNone() throws Exception {
        ExpectedBroadcast b = registerConnectivityBroadcast(1);

        verifyNoNetwork();
        TestNetworkAgentWrapper wifiAware = new TestNetworkAgentWrapper(TRANSPORT_WIFI_AWARE);
        assertNull(mCm.getActiveNetworkInfo());

        Network[] allNetworks = mCm.getAllNetworks();
        assertLength(1, allNetworks);
        Network network = allNetworks[0];
        NetworkCapabilities capabilities = mCm.getNetworkCapabilities(network);
        assertTrue(capabilities.hasTransport(TRANSPORT_WIFI_AWARE));

        final NetworkRequest request =
                new NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI_AWARE).build();
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        // Bring up wifi aware network.
        wifiAware.connect(false, false, false /* isStrictMode */);
        callback.expectAvailableCallbacksUnvalidated(wifiAware);

        assertNull(mCm.getActiveNetworkInfo());
        assertNull(mCm.getActiveNetwork());
        // TODO: getAllNetworkInfo is dirty and returns a non-empty array right from the start
        // of this test. Fix it and uncomment the assert below.
        //assertEmpty(mCm.getAllNetworkInfo());

        // Disconnect wifi aware network.
        wifiAware.disconnect();
        callback.expectCallbackThat(TIMEOUT_MS, (info) -> info instanceof CallbackEntry.Lost);
        mCm.unregisterNetworkCallback(callback);

        verifyNoNetwork();
        b.expectNoBroadcast(10);
    }

    @Test
    public void testDeprecatedAndUnsupportedOperations() throws Exception {
        final int TYPE_NONE = ConnectivityManager.TYPE_NONE;
        assertNull(mCm.getNetworkInfo(TYPE_NONE));
        assertNull(mCm.getNetworkForType(TYPE_NONE));
        assertNull(mCm.getLinkProperties(TYPE_NONE));
        assertFalse(mCm.isNetworkSupported(TYPE_NONE));

        assertThrows(IllegalArgumentException.class,
                () -> mCm.networkCapabilitiesForType(TYPE_NONE));

        Class<UnsupportedOperationException> unsupported = UnsupportedOperationException.class;
        assertThrows(unsupported, () -> mCm.startUsingNetworkFeature(TYPE_WIFI, ""));
        assertThrows(unsupported, () -> mCm.stopUsingNetworkFeature(TYPE_WIFI, ""));
        // TODO: let test context have configuration application target sdk version
        // and test that pre-M requesting for TYPE_NONE sends back APN_REQUEST_FAILED
        assertThrows(unsupported, () -> mCm.startUsingNetworkFeature(TYPE_NONE, ""));
        assertThrows(unsupported, () -> mCm.stopUsingNetworkFeature(TYPE_NONE, ""));
        assertThrows(unsupported, () -> mCm.requestRouteToHostAddress(TYPE_NONE, null));
    }

    @Test
    public void testLinkPropertiesEnsuresDirectlyConnectedRoutes() throws Exception {
        final NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI).build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(networkRequest, networkCallback);

        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(WIFI_IFNAME);
        LinkAddress myIpv4Address = new LinkAddress("192.168.12.3/24");
        RouteInfo myIpv4DefaultRoute = new RouteInfo((IpPrefix) null,
                InetAddresses.parseNumericAddress("192.168.12.1"), lp.getInterfaceName());
        lp.addLinkAddress(myIpv4Address);
        lp.addRoute(myIpv4DefaultRoute);

        // Verify direct routes are added when network agent is first registered in
        // ConnectivityService.
        TestNetworkAgentWrapper networkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI, lp);
        networkAgent.connect(true);
        networkCallback.expectCallback(CallbackEntry.AVAILABLE, networkAgent);
        networkCallback.expectCallback(CallbackEntry.NETWORK_CAPS_UPDATED, networkAgent);
        CallbackEntry.LinkPropertiesChanged cbi =
                networkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED,
                networkAgent);
        networkCallback.expectCallback(CallbackEntry.BLOCKED_STATUS, networkAgent);
        networkCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, networkAgent);
        networkCallback.assertNoCallback();
        checkDirectlyConnectedRoutes(cbi.getLp(), Arrays.asList(myIpv4Address),
                Arrays.asList(myIpv4DefaultRoute));
        checkDirectlyConnectedRoutes(mCm.getLinkProperties(networkAgent.getNetwork()),
                Arrays.asList(myIpv4Address), Arrays.asList(myIpv4DefaultRoute));

        // Verify direct routes are added during subsequent link properties updates.
        LinkProperties newLp = new LinkProperties(lp);
        LinkAddress myIpv6Address1 = new LinkAddress("fe80::cafe/64");
        LinkAddress myIpv6Address2 = new LinkAddress("2001:db8::2/64");
        newLp.addLinkAddress(myIpv6Address1);
        newLp.addLinkAddress(myIpv6Address2);
        networkAgent.sendLinkProperties(newLp);
        cbi = networkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, networkAgent);
        networkCallback.assertNoCallback();
        checkDirectlyConnectedRoutes(cbi.getLp(),
                Arrays.asList(myIpv4Address, myIpv6Address1, myIpv6Address2),
                Arrays.asList(myIpv4DefaultRoute));
        mCm.unregisterNetworkCallback(networkCallback);
    }

    private <T> void assertSameElementsNoDuplicates(T[] expected, T[] actual) {
        // Easier to implement than a proper "assertSameElements" method that also correctly deals
        // with duplicates.
        final String msg = Arrays.toString(expected) + " != " + Arrays.toString(actual);
        assertEquals(msg, expected.length, actual.length);
        Set expectedSet = new ArraySet<>(Arrays.asList(expected));
        assertEquals("expected contains duplicates", expectedSet.size(), expected.length);
        // actual cannot have duplicates because it's the same length and has the same elements.
        Set actualSet = new ArraySet<>(Arrays.asList(actual));
        assertEquals(expectedSet, actualSet);
    }

    private void expectForceUpdateIfaces(Network[] networks, String defaultIface,
            Integer vpnUid, String vpnIfname, String[] underlyingIfaces) throws Exception {
        ArgumentCaptor<Network[]> networksCaptor = ArgumentCaptor.forClass(Network[].class);
        ArgumentCaptor<UnderlyingNetworkInfo[]> vpnInfosCaptor = ArgumentCaptor.forClass(
                UnderlyingNetworkInfo[].class);

        verify(mStatsService, atLeastOnce()).forceUpdateIfaces(networksCaptor.capture(),
                any(NetworkStateSnapshot[].class), eq(defaultIface), vpnInfosCaptor.capture());

        assertSameElementsNoDuplicates(networksCaptor.getValue(), networks);

        UnderlyingNetworkInfo[] infos = vpnInfosCaptor.getValue();
        if (vpnUid != null) {
            assertEquals("Should have exactly one VPN:", 1, infos.length);
            UnderlyingNetworkInfo info = infos[0];
            assertEquals("Unexpected VPN owner:", (int) vpnUid, info.ownerUid);
            assertEquals("Unexpected VPN interface:", vpnIfname, info.iface);
            assertSameElementsNoDuplicates(underlyingIfaces,
                    info.underlyingIfaces.toArray(new String[0]));
        } else {
            assertEquals(0, infos.length);
            return;
        }
    }

    private void expectForceUpdateIfaces(Network[] networks, String defaultIface) throws Exception {
        expectForceUpdateIfaces(networks, defaultIface, null, null, new String[0]);
    }

    @Test
    public void testStatsIfacesChanged() throws Exception {
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);

        final Network[] onlyCell = new Network[] {mCellNetworkAgent.getNetwork()};
        final Network[] onlyWifi = new Network[] {mWiFiNetworkAgent.getNetwork()};

        LinkProperties cellLp = new LinkProperties();
        cellLp.setInterfaceName(MOBILE_IFNAME);
        LinkProperties wifiLp = new LinkProperties();
        wifiLp.setInterfaceName(WIFI_IFNAME);

        // Simple connection should have updated ifaces
        mCellNetworkAgent.connect(false);
        mCellNetworkAgent.sendLinkProperties(cellLp);
        waitForIdle();
        expectForceUpdateIfaces(onlyCell, MOBILE_IFNAME);
        reset(mStatsService);

        // Default network switch should update ifaces.
        mWiFiNetworkAgent.connect(false);
        mWiFiNetworkAgent.sendLinkProperties(wifiLp);
        waitForIdle();
        assertEquals(wifiLp, mService.getActiveLinkProperties());
        expectForceUpdateIfaces(onlyWifi, WIFI_IFNAME);
        reset(mStatsService);

        // Disconnect should update ifaces.
        mWiFiNetworkAgent.disconnect();
        waitForIdle();
        expectForceUpdateIfaces(onlyCell, MOBILE_IFNAME);
        reset(mStatsService);

        // Metered change should update ifaces
        mCellNetworkAgent.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        waitForIdle();
        expectForceUpdateIfaces(onlyCell, MOBILE_IFNAME);
        reset(mStatsService);

        mCellNetworkAgent.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        waitForIdle();
        expectForceUpdateIfaces(onlyCell, MOBILE_IFNAME);
        reset(mStatsService);

        // Temp metered change shouldn't update ifaces
        mCellNetworkAgent.addCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED);
        waitForIdle();
        verify(mStatsService, never()).forceUpdateIfaces(eq(onlyCell), any(
                NetworkStateSnapshot[].class), eq(MOBILE_IFNAME), eq(new UnderlyingNetworkInfo[0]));
        reset(mStatsService);

        // Roaming change should update ifaces
        mCellNetworkAgent.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        waitForIdle();
        expectForceUpdateIfaces(onlyCell, MOBILE_IFNAME);
        reset(mStatsService);

        // Test VPNs.
        final LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(VPN_IFNAME);

        mMockVpn.establishForMyUid(lp);
        assertUidRangesUpdatedForMyUid(true);

        final Network[] cellAndVpn = new Network[] {
                mCellNetworkAgent.getNetwork(), mMockVpn.getNetwork()};

        // A VPN with default (null) underlying networks sets the underlying network's interfaces...
        expectForceUpdateIfaces(cellAndVpn, MOBILE_IFNAME, Process.myUid(), VPN_IFNAME,
                new String[]{MOBILE_IFNAME});

        // ...and updates them as the default network switches.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        mWiFiNetworkAgent.sendLinkProperties(wifiLp);
        final Network[] onlyNull = new Network[]{null};
        final Network[] wifiAndVpn = new Network[] {
                mWiFiNetworkAgent.getNetwork(), mMockVpn.getNetwork()};
        final Network[] cellAndWifi = new Network[] {
                mCellNetworkAgent.getNetwork(), mWiFiNetworkAgent.getNetwork()};
        final Network[] cellNullAndWifi = new Network[] {
                mCellNetworkAgent.getNetwork(), null, mWiFiNetworkAgent.getNetwork()};

        waitForIdle();
        assertEquals(wifiLp, mService.getActiveLinkProperties());
        expectForceUpdateIfaces(wifiAndVpn, WIFI_IFNAME, Process.myUid(), VPN_IFNAME,
                new String[]{WIFI_IFNAME});
        reset(mStatsService);

        // A VPN that sets its underlying networks passes the underlying interfaces, and influences
        // the default interface sent to NetworkStatsService by virtue of applying to the system
        // server UID (or, in this test, to the test's UID). This is the reason for sending
        // MOBILE_IFNAME even though the default network is wifi.
        // TODO: fix this to pass in the actual default network interface. Whether or not the VPN
        // applies to the system server UID should not have any bearing on network stats.
        mMockVpn.setUnderlyingNetworks(onlyCell);
        waitForIdle();
        expectForceUpdateIfaces(wifiAndVpn, MOBILE_IFNAME, Process.myUid(), VPN_IFNAME,
                new String[]{MOBILE_IFNAME});
        reset(mStatsService);

        mMockVpn.setUnderlyingNetworks(cellAndWifi);
        waitForIdle();
        expectForceUpdateIfaces(wifiAndVpn, MOBILE_IFNAME, Process.myUid(), VPN_IFNAME,
                new String[]{MOBILE_IFNAME, WIFI_IFNAME});
        reset(mStatsService);

        // Null underlying networks are ignored.
        mMockVpn.setUnderlyingNetworks(cellNullAndWifi);
        waitForIdle();
        expectForceUpdateIfaces(wifiAndVpn, MOBILE_IFNAME, Process.myUid(), VPN_IFNAME,
                new String[]{MOBILE_IFNAME, WIFI_IFNAME});
        reset(mStatsService);

        // If an underlying network disconnects, that interface should no longer be underlying.
        // This doesn't actually work because disconnectAndDestroyNetwork only notifies
        // NetworkStatsService before the underlying network is actually removed. So the underlying
        // network will only be removed if notifyIfacesChangedForNetworkStats is called again. This
        // could result in incorrect data usage measurements if the interface used by the
        // disconnected network is reused by a system component that does not register an agent for
        // it (e.g., tethering).
        mCellNetworkAgent.disconnect();
        waitForIdle();
        assertNull(mService.getLinkProperties(mCellNetworkAgent.getNetwork()));
        expectForceUpdateIfaces(wifiAndVpn, MOBILE_IFNAME, Process.myUid(), VPN_IFNAME,
                new String[]{MOBILE_IFNAME, WIFI_IFNAME});

        // Confirm that we never tell NetworkStatsService that cell is no longer the underlying
        // network for the VPN...
        verify(mStatsService, never()).forceUpdateIfaces(any(Network[].class),
                any(NetworkStateSnapshot[].class), any() /* anyString() doesn't match null */,
                argThat(infos -> infos[0].underlyingIfaces.size() == 1
                        && WIFI_IFNAME.equals(infos[0].underlyingIfaces.get(0))));
        verifyNoMoreInteractions(mStatsService);
        reset(mStatsService);

        // ... but if something else happens that causes notifyIfacesChangedForNetworkStats to be
        // called again, it does. For example, connect Ethernet, but with a low score, such that it
        // does not become the default network.
        mEthernetNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_ETHERNET);
        mEthernetNetworkAgent.adjustScore(-40);
        mEthernetNetworkAgent.connect(false);
        waitForIdle();
        verify(mStatsService).forceUpdateIfaces(any(Network[].class),
                any(NetworkStateSnapshot[].class), any() /* anyString() doesn't match null */,
                argThat(vpnInfos -> vpnInfos[0].underlyingIfaces.size() == 1
                        && WIFI_IFNAME.equals(vpnInfos[0].underlyingIfaces.get(0))));
        mEthernetNetworkAgent.disconnect();
        waitForIdle();
        reset(mStatsService);

        // When a VPN declares no underlying networks (i.e., no connectivity), getAllVpnInfo
        // does not return the VPN, so CS does not pass it to NetworkStatsService. This causes
        // NetworkStatsFactory#adjustForTunAnd464Xlat not to attempt any VPN data migration, which
        // is probably a performance improvement (though it's very unlikely that a VPN would declare
        // no underlying networks).
        // Also, for the same reason as above, the active interface passed in is null.
        mMockVpn.setUnderlyingNetworks(new Network[0]);
        waitForIdle();
        expectForceUpdateIfaces(wifiAndVpn, null);
        reset(mStatsService);

        // Specifying only a null underlying network is the same as no networks.
        mMockVpn.setUnderlyingNetworks(onlyNull);
        waitForIdle();
        expectForceUpdateIfaces(wifiAndVpn, null);
        reset(mStatsService);

        // Specifying networks that are all disconnected is the same as specifying no networks.
        mMockVpn.setUnderlyingNetworks(onlyCell);
        waitForIdle();
        expectForceUpdateIfaces(wifiAndVpn, null);
        reset(mStatsService);

        // Passing in null again means follow the default network again.
        mMockVpn.setUnderlyingNetworks(null);
        waitForIdle();
        expectForceUpdateIfaces(wifiAndVpn, WIFI_IFNAME, Process.myUid(), VPN_IFNAME,
                new String[]{WIFI_IFNAME});
        reset(mStatsService);
    }

    @Test
    public void testBasicDnsConfigurationPushed() throws Exception {
        setPrivateDnsSettings(PRIVATE_DNS_MODE_OPPORTUNISTIC, "ignored.example.com");

        // Clear any interactions that occur as a result of CS starting up.
        reset(mMockDnsResolver);

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        waitForIdle();
        verify(mMockDnsResolver, never()).setResolverConfiguration(any());
        verifyNoMoreInteractions(mMockDnsResolver);

        final LinkProperties cellLp = new LinkProperties();
        cellLp.setInterfaceName(MOBILE_IFNAME);
        // Add IPv4 and IPv6 default routes, because DNS-over-TLS code does
        // "is-reachable" testing in order to not program netd with unreachable
        // nameservers that it might try repeated to validate.
        cellLp.addLinkAddress(new LinkAddress("192.0.2.4/24"));
        cellLp.addRoute(new RouteInfo((IpPrefix) null, InetAddress.getByName("192.0.2.4"),
                MOBILE_IFNAME));
        cellLp.addLinkAddress(new LinkAddress("2001:db8:1::1/64"));
        cellLp.addRoute(new RouteInfo((IpPrefix) null, InetAddress.getByName("2001:db8:1::1"),
                MOBILE_IFNAME));
        mCellNetworkAgent.sendLinkProperties(cellLp);
        mCellNetworkAgent.connect(false);
        waitForIdle();

        verify(mMockDnsResolver, times(1)).createNetworkCache(
                eq(mCellNetworkAgent.getNetwork().netId));
        // CS tells dnsresolver about the empty DNS config for this network.
        verify(mMockDnsResolver, atLeastOnce()).setResolverConfiguration(any());
        reset(mMockDnsResolver);

        cellLp.addDnsServer(InetAddress.getByName("2001:db8::1"));
        mCellNetworkAgent.sendLinkProperties(cellLp);
        waitForIdle();
        verify(mMockDnsResolver, atLeastOnce()).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        ResolverParamsParcel resolvrParams = mResolverParamsParcelCaptor.getValue();
        assertEquals(1, resolvrParams.servers.length);
        assertTrue(ArrayUtils.contains(resolvrParams.servers, "2001:db8::1"));
        // Opportunistic mode.
        assertTrue(ArrayUtils.contains(resolvrParams.tlsServers, "2001:db8::1"));
        reset(mMockDnsResolver);

        cellLp.addDnsServer(InetAddress.getByName("192.0.2.1"));
        mCellNetworkAgent.sendLinkProperties(cellLp);
        waitForIdle();
        verify(mMockDnsResolver, atLeastOnce()).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        resolvrParams = mResolverParamsParcelCaptor.getValue();
        assertEquals(2, resolvrParams.servers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.servers,
                new String[]{"2001:db8::1", "192.0.2.1"}));
        // Opportunistic mode.
        assertEquals(2, resolvrParams.tlsServers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.tlsServers,
                new String[]{"2001:db8::1", "192.0.2.1"}));
        reset(mMockDnsResolver);

        final String TLS_SPECIFIER = "tls.example.com";
        final String TLS_SERVER6 = "2001:db8:53::53";
        final InetAddress[] TLS_IPS = new InetAddress[]{ InetAddress.getByName(TLS_SERVER6) };
        final String[] TLS_SERVERS = new String[]{ TLS_SERVER6 };
        mCellNetworkAgent.mNmCallbacks.notifyPrivateDnsConfigResolved(
                new PrivateDnsConfig(TLS_SPECIFIER, TLS_IPS).toParcel());

        waitForIdle();
        verify(mMockDnsResolver, atLeastOnce()).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        resolvrParams = mResolverParamsParcelCaptor.getValue();
        assertEquals(2, resolvrParams.servers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.servers,
                new String[]{"2001:db8::1", "192.0.2.1"}));
        reset(mMockDnsResolver);
    }

    @Test
    public void testDnsConfigurationTransTypesPushed() throws Exception {
        // Clear any interactions that occur as a result of CS starting up.
        reset(mMockDnsResolver);

        final NetworkRequest request = new NetworkRequest.Builder()
                .clearCapabilities().addCapability(NET_CAPABILITY_INTERNET)
                .build();
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        verify(mMockDnsResolver, times(1)).createNetworkCache(
                eq(mWiFiNetworkAgent.getNetwork().netId));
        verify(mMockDnsResolver, times(2)).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        final ResolverParamsParcel resolverParams = mResolverParamsParcelCaptor.getValue();
        assertContainsExactly(resolverParams.transportTypes, TRANSPORT_WIFI);
        reset(mMockDnsResolver);
    }

    @Test
    public void testPrivateDnsNotification() throws Exception {
        NetworkRequest request = new NetworkRequest.Builder()
                .clearCapabilities().addCapability(NET_CAPABILITY_INTERNET)
                .build();
        TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);
        // Bring up wifi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        // Private DNS resolution failed, checking if the notification will be shown or not.
        mWiFiNetworkAgent.setNetworkInvalid(true /* isStrictMode */);
        mWiFiNetworkAgent.mNetworkMonitor.forceReevaluation(Process.myUid());
        waitForIdle();
        // If network validation failed, NetworkMonitor will re-evaluate the network.
        // ConnectivityService should filter the redundant notification. This part is trying to
        // simulate that situation and check if ConnectivityService could filter that case.
        mWiFiNetworkAgent.mNetworkMonitor.forceReevaluation(Process.myUid());
        waitForIdle();
        verify(mNotificationManager, timeout(TIMEOUT_MS).times(1)).notify(anyString(),
                eq(NotificationType.PRIVATE_DNS_BROKEN.eventId), any());
        // If private DNS resolution successful, the PRIVATE_DNS_BROKEN notification shouldn't be
        // shown.
        mWiFiNetworkAgent.setNetworkValid(true /* isStrictMode */);
        mWiFiNetworkAgent.mNetworkMonitor.forceReevaluation(Process.myUid());
        waitForIdle();
        verify(mNotificationManager, timeout(TIMEOUT_MS).times(1)).cancel(anyString(),
                eq(NotificationType.PRIVATE_DNS_BROKEN.eventId));
        // If private DNS resolution failed again, the PRIVATE_DNS_BROKEN notification should be
        // shown again.
        mWiFiNetworkAgent.setNetworkInvalid(true /* isStrictMode */);
        mWiFiNetworkAgent.mNetworkMonitor.forceReevaluation(Process.myUid());
        waitForIdle();
        verify(mNotificationManager, timeout(TIMEOUT_MS).times(2)).notify(anyString(),
                eq(NotificationType.PRIVATE_DNS_BROKEN.eventId), any());
    }

    @Test
    public void testPrivateDnsSettingsChange() throws Exception {
        // Clear any interactions that occur as a result of CS starting up.
        reset(mMockDnsResolver);

        // The default on Android is opportunistic mode ("Automatic").
        setPrivateDnsSettings(PRIVATE_DNS_MODE_OPPORTUNISTIC, "ignored.example.com");

        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.requestNetwork(cellRequest, cellNetworkCallback);

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        waitForIdle();
        // CS tells netd about the empty DNS config for this network.
        verify(mMockDnsResolver, never()).setResolverConfiguration(any());
        verifyNoMoreInteractions(mMockDnsResolver);

        final LinkProperties cellLp = new LinkProperties();
        cellLp.setInterfaceName(MOBILE_IFNAME);
        // Add IPv4 and IPv6 default routes, because DNS-over-TLS code does
        // "is-reachable" testing in order to not program netd with unreachable
        // nameservers that it might try repeated to validate.
        cellLp.addLinkAddress(new LinkAddress("192.0.2.4/24"));
        cellLp.addRoute(new RouteInfo((IpPrefix) null, InetAddress.getByName("192.0.2.4"),
                MOBILE_IFNAME));
        cellLp.addLinkAddress(new LinkAddress("2001:db8:1::1/64"));
        cellLp.addRoute(new RouteInfo((IpPrefix) null, InetAddress.getByName("2001:db8:1::1"),
                MOBILE_IFNAME));
        cellLp.addDnsServer(InetAddress.getByName("2001:db8::1"));
        cellLp.addDnsServer(InetAddress.getByName("192.0.2.1"));

        mCellNetworkAgent.sendLinkProperties(cellLp);
        mCellNetworkAgent.connect(false);
        waitForIdle();
        verify(mMockDnsResolver, times(1)).createNetworkCache(
                eq(mCellNetworkAgent.getNetwork().netId));
        verify(mMockDnsResolver, atLeastOnce()).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        ResolverParamsParcel resolvrParams = mResolverParamsParcelCaptor.getValue();
        assertEquals(2, resolvrParams.tlsServers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.tlsServers,
                new String[] { "2001:db8::1", "192.0.2.1" }));
        // Opportunistic mode.
        assertEquals(2, resolvrParams.tlsServers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.tlsServers,
                new String[] { "2001:db8::1", "192.0.2.1" }));
        reset(mMockDnsResolver);
        cellNetworkCallback.expectCallback(CallbackEntry.AVAILABLE, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackEntry.NETWORK_CAPS_UPDATED,
                mCellNetworkAgent);
        CallbackEntry.LinkPropertiesChanged cbi = cellNetworkCallback.expectCallback(
                CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackEntry.BLOCKED_STATUS, mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertFalse(cbi.getLp().isPrivateDnsActive());
        assertNull(cbi.getLp().getPrivateDnsServerName());

        setPrivateDnsSettings(PRIVATE_DNS_MODE_OFF, "ignored.example.com");
        verify(mMockDnsResolver, times(1)).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        resolvrParams = mResolverParamsParcelCaptor.getValue();
        assertEquals(2, resolvrParams.servers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.servers,
                new String[] { "2001:db8::1", "192.0.2.1" }));
        reset(mMockDnsResolver);
        cellNetworkCallback.assertNoCallback();

        setPrivateDnsSettings(PRIVATE_DNS_MODE_OPPORTUNISTIC, "ignored.example.com");
        verify(mMockDnsResolver, atLeastOnce()).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        resolvrParams = mResolverParamsParcelCaptor.getValue();
        assertEquals(2, resolvrParams.servers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.servers,
                new String[] { "2001:db8::1", "192.0.2.1" }));
        assertEquals(2, resolvrParams.tlsServers.length);
        assertTrue(ArrayUtils.containsAll(resolvrParams.tlsServers,
                new String[] { "2001:db8::1", "192.0.2.1" }));
        reset(mMockDnsResolver);
        cellNetworkCallback.assertNoCallback();

        setPrivateDnsSettings(PRIVATE_DNS_MODE_PROVIDER_HOSTNAME, "strict.example.com");
        // Can't test dns configuration for strict mode without properly mocking
        // out the DNS lookups, but can test that LinkProperties is updated.
        cbi = cellNetworkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED,
                mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertTrue(cbi.getLp().isPrivateDnsActive());
        assertEquals("strict.example.com", cbi.getLp().getPrivateDnsServerName());
    }

    @Test
    public void testLinkPropertiesWithPrivateDnsValidationEvents() throws Exception {
        // The default on Android is opportunistic mode ("Automatic").
        setPrivateDnsSettings(PRIVATE_DNS_MODE_OPPORTUNISTIC, "ignored.example.com");

        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.requestNetwork(cellRequest, cellNetworkCallback);

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        waitForIdle();
        LinkProperties lp = new LinkProperties();
        mCellNetworkAgent.sendLinkProperties(lp);
        mCellNetworkAgent.connect(false);
        waitForIdle();
        cellNetworkCallback.expectCallback(CallbackEntry.AVAILABLE, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackEntry.NETWORK_CAPS_UPDATED,
                mCellNetworkAgent);
        CallbackEntry.LinkPropertiesChanged cbi = cellNetworkCallback.expectCallback(
                CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackEntry.BLOCKED_STATUS, mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertFalse(cbi.getLp().isPrivateDnsActive());
        assertNull(cbi.getLp().getPrivateDnsServerName());
        Set<InetAddress> dnsServers = new HashSet<>();
        checkDnsServers(cbi.getLp(), dnsServers);

        // Send a validation event for a server that is not part of the current
        // resolver config. The validation event should be ignored.
        mService.mNetdEventCallback.onPrivateDnsValidationEvent(
                mCellNetworkAgent.getNetwork().netId, "", "145.100.185.18", true);
        cellNetworkCallback.assertNoCallback();

        // Add a dns server to the LinkProperties.
        LinkProperties lp2 = new LinkProperties(lp);
        lp2.addDnsServer(InetAddress.getByName("145.100.185.16"));
        mCellNetworkAgent.sendLinkProperties(lp2);
        cbi = cellNetworkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED,
                mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertFalse(cbi.getLp().isPrivateDnsActive());
        assertNull(cbi.getLp().getPrivateDnsServerName());
        dnsServers.add(InetAddress.getByName("145.100.185.16"));
        checkDnsServers(cbi.getLp(), dnsServers);

        // Send a validation event containing a hostname that is not part of
        // the current resolver config. The validation event should be ignored.
        mService.mNetdEventCallback.onPrivateDnsValidationEvent(
                mCellNetworkAgent.getNetwork().netId, "145.100.185.16", "hostname", true);
        cellNetworkCallback.assertNoCallback();

        // Send a validation event where validation failed.
        mService.mNetdEventCallback.onPrivateDnsValidationEvent(
                mCellNetworkAgent.getNetwork().netId, "145.100.185.16", "", false);
        cellNetworkCallback.assertNoCallback();

        // Send a validation event where validation succeeded for a server in
        // the current resolver config. A LinkProperties callback with updated
        // private dns fields should be sent.
        mService.mNetdEventCallback.onPrivateDnsValidationEvent(
                mCellNetworkAgent.getNetwork().netId, "145.100.185.16", "", true);
        cbi = cellNetworkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED,
                mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertTrue(cbi.getLp().isPrivateDnsActive());
        assertNull(cbi.getLp().getPrivateDnsServerName());
        checkDnsServers(cbi.getLp(), dnsServers);

        // The private dns fields in LinkProperties should be preserved when
        // the network agent sends unrelated changes.
        LinkProperties lp3 = new LinkProperties(lp2);
        lp3.setMtu(1300);
        mCellNetworkAgent.sendLinkProperties(lp3);
        cbi = cellNetworkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED,
                mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertTrue(cbi.getLp().isPrivateDnsActive());
        assertNull(cbi.getLp().getPrivateDnsServerName());
        checkDnsServers(cbi.getLp(), dnsServers);
        assertEquals(1300, cbi.getLp().getMtu());

        // Removing the only validated server should affect the private dns
        // fields in LinkProperties.
        LinkProperties lp4 = new LinkProperties(lp3);
        lp4.removeDnsServer(InetAddress.getByName("145.100.185.16"));
        mCellNetworkAgent.sendLinkProperties(lp4);
        cbi = cellNetworkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED,
                mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        assertFalse(cbi.getLp().isPrivateDnsActive());
        assertNull(cbi.getLp().getPrivateDnsServerName());
        dnsServers.remove(InetAddress.getByName("145.100.185.16"));
        checkDnsServers(cbi.getLp(), dnsServers);
        assertEquals(1300, cbi.getLp().getMtu());
    }

    private void checkDirectlyConnectedRoutes(Object callbackObj,
            Collection<LinkAddress> linkAddresses, Collection<RouteInfo> otherRoutes) {
        assertTrue(callbackObj instanceof LinkProperties);
        LinkProperties lp = (LinkProperties) callbackObj;

        Set<RouteInfo> expectedRoutes = new ArraySet<>();
        expectedRoutes.addAll(otherRoutes);
        for (LinkAddress address : linkAddresses) {
            RouteInfo localRoute = new RouteInfo(address, null, lp.getInterfaceName());
            // Duplicates in linkAddresses are considered failures
            assertTrue(expectedRoutes.add(localRoute));
        }
        List<RouteInfo> observedRoutes = lp.getRoutes();
        assertEquals(expectedRoutes.size(), observedRoutes.size());
        assertTrue(observedRoutes.containsAll(expectedRoutes));
    }

    private static void checkDnsServers(Object callbackObj, Set<InetAddress> dnsServers) {
        assertTrue(callbackObj instanceof LinkProperties);
        LinkProperties lp = (LinkProperties) callbackObj;
        assertEquals(dnsServers.size(), lp.getDnsServers().size());
        assertTrue(lp.getDnsServers().containsAll(dnsServers));
    }

    @Test
    public void testApplyUnderlyingCapabilities() throws Exception {
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mCellNetworkAgent.connect(false /* validated */);
        mWiFiNetworkAgent.connect(false /* validated */);

        final NetworkCapabilities cellNc = new NetworkCapabilities()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET)
                .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                .setLinkDownstreamBandwidthKbps(10);
        final NetworkCapabilities wifiNc = new NetworkCapabilities()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NET_CAPABILITY_INTERNET)
                .addCapability(NET_CAPABILITY_NOT_METERED)
                .addCapability(NET_CAPABILITY_NOT_ROAMING)
                .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
                .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                .setLinkUpstreamBandwidthKbps(20);
        mCellNetworkAgent.setNetworkCapabilities(cellNc, true /* sendToConnectivityService */);
        mWiFiNetworkAgent.setNetworkCapabilities(wifiNc, true /* sendToConnectivityService */);
        waitForIdle();

        final Network mobile = mCellNetworkAgent.getNetwork();
        final Network wifi = mWiFiNetworkAgent.getNetwork();

        final NetworkCapabilities initialCaps = new NetworkCapabilities();
        initialCaps.addTransportType(TRANSPORT_VPN);
        initialCaps.addCapability(NET_CAPABILITY_INTERNET);
        initialCaps.removeCapability(NET_CAPABILITY_NOT_VPN);

        final NetworkCapabilities withNoUnderlying = new NetworkCapabilities();
        withNoUnderlying.addCapability(NET_CAPABILITY_INTERNET);
        withNoUnderlying.addCapability(NET_CAPABILITY_NOT_CONGESTED);
        withNoUnderlying.addCapability(NET_CAPABILITY_NOT_ROAMING);
        withNoUnderlying.addCapability(NET_CAPABILITY_NOT_SUSPENDED);
        withNoUnderlying.addTransportType(TRANSPORT_VPN);
        withNoUnderlying.removeCapability(NET_CAPABILITY_NOT_VPN);

        final NetworkCapabilities withMobileUnderlying = new NetworkCapabilities(withNoUnderlying);
        withMobileUnderlying.addTransportType(TRANSPORT_CELLULAR);
        withMobileUnderlying.removeCapability(NET_CAPABILITY_NOT_ROAMING);
        withMobileUnderlying.removeCapability(NET_CAPABILITY_NOT_SUSPENDED);
        withMobileUnderlying.setLinkDownstreamBandwidthKbps(10);

        final NetworkCapabilities withWifiUnderlying = new NetworkCapabilities(withNoUnderlying);
        withWifiUnderlying.addTransportType(TRANSPORT_WIFI);
        withWifiUnderlying.addCapability(NET_CAPABILITY_NOT_METERED);
        withWifiUnderlying.setLinkUpstreamBandwidthKbps(20);

        final NetworkCapabilities withWifiAndMobileUnderlying =
                new NetworkCapabilities(withNoUnderlying);
        withWifiAndMobileUnderlying.addTransportType(TRANSPORT_CELLULAR);
        withWifiAndMobileUnderlying.addTransportType(TRANSPORT_WIFI);
        withWifiAndMobileUnderlying.removeCapability(NET_CAPABILITY_NOT_METERED);
        withWifiAndMobileUnderlying.removeCapability(NET_CAPABILITY_NOT_ROAMING);
        withWifiAndMobileUnderlying.setLinkDownstreamBandwidthKbps(10);
        withWifiAndMobileUnderlying.setLinkUpstreamBandwidthKbps(20);

        final NetworkCapabilities initialCapsNotMetered = new NetworkCapabilities(initialCaps);
        initialCapsNotMetered.addCapability(NET_CAPABILITY_NOT_METERED);

        NetworkCapabilities caps = new NetworkCapabilities(initialCaps);
        mService.applyUnderlyingCapabilities(new Network[]{}, initialCapsNotMetered, caps);
        assertEquals(withNoUnderlying, caps);

        caps = new NetworkCapabilities(initialCaps);
        mService.applyUnderlyingCapabilities(new Network[]{null}, initialCapsNotMetered, caps);
        assertEquals(withNoUnderlying, caps);

        caps = new NetworkCapabilities(initialCaps);
        mService.applyUnderlyingCapabilities(new Network[]{mobile}, initialCapsNotMetered, caps);
        assertEquals(withMobileUnderlying, caps);

        mService.applyUnderlyingCapabilities(new Network[]{wifi}, initialCapsNotMetered, caps);
        assertEquals(withWifiUnderlying, caps);

        withWifiUnderlying.removeCapability(NET_CAPABILITY_NOT_METERED);
        caps = new NetworkCapabilities(initialCaps);
        mService.applyUnderlyingCapabilities(new Network[]{wifi}, initialCaps, caps);
        assertEquals(withWifiUnderlying, caps);

        caps = new NetworkCapabilities(initialCaps);
        mService.applyUnderlyingCapabilities(new Network[]{mobile, wifi}, initialCaps, caps);
        assertEquals(withWifiAndMobileUnderlying, caps);

        withWifiUnderlying.addCapability(NET_CAPABILITY_NOT_METERED);
        caps = new NetworkCapabilities(initialCaps);
        mService.applyUnderlyingCapabilities(new Network[]{null, mobile, null, wifi},
                initialCapsNotMetered, caps);
        assertEquals(withWifiAndMobileUnderlying, caps);

        caps = new NetworkCapabilities(initialCaps);
        mService.applyUnderlyingCapabilities(new Network[]{null, mobile, null, wifi},
                initialCapsNotMetered, caps);
        assertEquals(withWifiAndMobileUnderlying, caps);

        mService.applyUnderlyingCapabilities(null, initialCapsNotMetered, caps);
        assertEquals(withWifiUnderlying, caps);
    }

    @Test
    public void testVpnConnectDisconnectUnderlyingNetwork() throws Exception {
        final TestNetworkCallback callback = new TestNetworkCallback();
        final NetworkRequest request = new NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_NOT_VPN).build();

        mCm.registerNetworkCallback(request, callback);

        // Bring up a VPN that specifies an underlying network that does not exist yet.
        // Note: it's sort of meaningless for a VPN app to declare a network that doesn't exist yet,
        // (and doing so is difficult without using reflection) but it's good to test that the code
        // behaves approximately correctly.
        mMockVpn.establishForMyUid(false, true, false);
        assertUidRangesUpdatedForMyUid(true);
        final Network wifiNetwork = new Network(mNetIdManager.peekNextNetId());
        mMockVpn.setUnderlyingNetworks(new Network[]{wifiNetwork});
        callback.expectAvailableCallbacksUnvalidated(mMockVpn);
        assertTrue(mCm.getNetworkCapabilities(mMockVpn.getNetwork())
                .hasTransport(TRANSPORT_VPN));
        assertFalse(mCm.getNetworkCapabilities(mMockVpn.getNetwork())
                .hasTransport(TRANSPORT_WIFI));

        // Make that underlying network connect, and expect to see its capabilities immediately
        // reflected in the VPN's capabilities.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        assertEquals(wifiNetwork, mWiFiNetworkAgent.getNetwork());
        mWiFiNetworkAgent.connect(false);
        // TODO: the callback for the VPN happens before any callbacks are called for the wifi
        // network that has just connected. There appear to be two issues here:
        // 1. The VPN code will accept an underlying network as soon as getNetworkCapabilities() for
        //    it returns non-null (which happens very early, during handleRegisterNetworkAgent).
        //    This is not correct because that that point the network is not connected and cannot
        //    pass any traffic.
        // 2. When a network connects, updateNetworkInfo propagates underlying network capabilities
        //    before rematching networks.
        // Given that this scenario can't really happen, this is probably fine for now.
        callback.expectCallback(CallbackEntry.NETWORK_CAPS_UPDATED, mMockVpn);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertTrue(mCm.getNetworkCapabilities(mMockVpn.getNetwork())
                .hasTransport(TRANSPORT_VPN));
        assertTrue(mCm.getNetworkCapabilities(mMockVpn.getNetwork())
                .hasTransport(TRANSPORT_WIFI));

        // Disconnect the network, and expect to see the VPN capabilities change accordingly.
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        callback.expectCapabilitiesThat(mMockVpn, (nc) ->
                nc.getTransportTypes().length == 1 && nc.hasTransport(TRANSPORT_VPN));

        mMockVpn.disconnect();
        mCm.unregisterNetworkCallback(callback);
    }

    private void assertGetNetworkInfoOfGetActiveNetworkIsConnected(boolean expectedConnectivity) {
        // What Chromium used to do before https://chromium-review.googlesource.com/2605304
        assertEquals("Unexpected result for getActiveNetworkInfo(getActiveNetwork())",
                expectedConnectivity, mCm.getNetworkInfo(mCm.getActiveNetwork()).isConnected());
    }

    @Test
    public void testVpnUnderlyingNetworkSuspended() throws Exception {
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(callback);

        // Connect a VPN.
        mMockVpn.establishForMyUid(false /* validated */, true /* hasInternet */,
                false /* isStrictMode */);
        callback.expectAvailableCallbacksUnvalidated(mMockVpn);

        // Connect cellular data.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false /* validated */);
        callback.expectCapabilitiesThat(mMockVpn,
                nc -> nc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED)
                        && nc.hasTransport(TRANSPORT_CELLULAR));
        callback.assertNoCallback();

        assertTrue(mCm.getNetworkCapabilities(mMockVpn.getNetwork())
                .hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        assertNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_VPN, DetailedState.CONNECTED);
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);
        assertGetNetworkInfoOfGetActiveNetworkIsConnected(true);

        // Suspend the cellular network and expect the VPN to be suspended.
        mCellNetworkAgent.suspend();
        callback.expectCapabilitiesThat(mMockVpn,
                nc -> !nc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED)
                        && nc.hasTransport(TRANSPORT_CELLULAR));
        callback.expectCallback(CallbackEntry.SUSPENDED, mMockVpn);
        callback.assertNoCallback();

        assertFalse(mCm.getNetworkCapabilities(mMockVpn.getNetwork())
                .hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        assertNetworkInfo(TYPE_MOBILE, DetailedState.SUSPENDED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_VPN, DetailedState.SUSPENDED);
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.SUSPENDED);
        // VPN's main underlying network is suspended, so no connectivity.
        assertGetNetworkInfoOfGetActiveNetworkIsConnected(false);

        // Switch to another network. The VPN should no longer be suspended.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false /* validated */);
        callback.expectCapabilitiesThat(mMockVpn,
                nc -> nc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED)
                        && nc.hasTransport(TRANSPORT_WIFI));
        callback.expectCallback(CallbackEntry.RESUMED, mMockVpn);
        callback.assertNoCallback();

        assertTrue(mCm.getNetworkCapabilities(mMockVpn.getNetwork())
                .hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        assertNetworkInfo(TYPE_MOBILE, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_VPN, DetailedState.CONNECTED);
        assertActiveNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);
        assertGetNetworkInfoOfGetActiveNetworkIsConnected(true);

        // Unsuspend cellular and then switch back to it. The VPN remains not suspended.
        mCellNetworkAgent.resume();
        callback.assertNoCallback();
        mWiFiNetworkAgent.disconnect();
        callback.expectCapabilitiesThat(mMockVpn,
                nc -> nc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED)
                        && nc.hasTransport(TRANSPORT_CELLULAR));
        // Spurious double callback?
        callback.expectCapabilitiesThat(mMockVpn,
                nc -> nc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED)
                        && nc.hasTransport(TRANSPORT_CELLULAR));
        callback.assertNoCallback();

        assertTrue(mCm.getNetworkCapabilities(mMockVpn.getNetwork())
                .hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        assertNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_VPN, DetailedState.CONNECTED);
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);
        assertGetNetworkInfoOfGetActiveNetworkIsConnected(true);

        // Suspend cellular and expect no connectivity.
        mCellNetworkAgent.suspend();
        callback.expectCapabilitiesThat(mMockVpn,
                nc -> !nc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED)
                        && nc.hasTransport(TRANSPORT_CELLULAR));
        callback.expectCallback(CallbackEntry.SUSPENDED, mMockVpn);
        callback.assertNoCallback();

        assertFalse(mCm.getNetworkCapabilities(mMockVpn.getNetwork())
                .hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        assertNetworkInfo(TYPE_MOBILE, DetailedState.SUSPENDED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_VPN, DetailedState.SUSPENDED);
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.SUSPENDED);
        assertGetNetworkInfoOfGetActiveNetworkIsConnected(false);

        // Resume cellular and expect that connectivity comes back.
        mCellNetworkAgent.resume();
        callback.expectCapabilitiesThat(mMockVpn,
                nc -> nc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED)
                        && nc.hasTransport(TRANSPORT_CELLULAR));
        callback.expectCallback(CallbackEntry.RESUMED, mMockVpn);
        callback.assertNoCallback();

        assertTrue(mCm.getNetworkCapabilities(mMockVpn.getNetwork())
                .hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        assertNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_VPN, DetailedState.CONNECTED);
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);
        assertGetNetworkInfoOfGetActiveNetworkIsConnected(true);
    }

    @Test
    public void testVpnNetworkActive() throws Exception {
        // NETWORK_SETTINGS is necessary to call registerSystemDefaultNetworkCallback.
        mServiceContext.setPermission(Manifest.permission.NETWORK_SETTINGS,
                PERMISSION_GRANTED);

        final int uid = Process.myUid();

        final TestNetworkCallback genericNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback genericNotVpnNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback wifiNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback vpnNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback defaultCallback = new TestNetworkCallback();
        final TestNetworkCallback systemDefaultCallback = new TestNetworkCallback();
        final NetworkRequest genericNotVpnRequest = new NetworkRequest.Builder().build();
        final NetworkRequest genericRequest = new NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_NOT_VPN).build();
        final NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI).build();
        final NetworkRequest vpnNetworkRequest = new NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_NOT_VPN)
                .addTransportType(TRANSPORT_VPN).build();
        mCm.registerNetworkCallback(genericRequest, genericNetworkCallback);
        mCm.registerNetworkCallback(genericNotVpnRequest, genericNotVpnNetworkCallback);
        mCm.registerNetworkCallback(wifiRequest, wifiNetworkCallback);
        mCm.registerNetworkCallback(vpnNetworkRequest, vpnNetworkCallback);
        mCm.registerDefaultNetworkCallback(defaultCallback);
        mCm.registerSystemDefaultNetworkCallback(systemDefaultCallback,
                new Handler(ConnectivityThread.getInstanceLooper()));
        defaultCallback.assertNoCallback();

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);

        genericNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        genericNotVpnNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        wifiNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        systemDefaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        vpnNetworkCallback.assertNoCallback();
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        final Set<UidRange> ranges = uidRangesForUid(uid);
        mMockVpn.registerAgent(ranges);
        mMockVpn.setUnderlyingNetworks(new Network[0]);

        // VPN networks do not satisfy the default request and are automatically validated
        // by NetworkMonitor
        assertFalse(NetworkMonitorUtils.isValidationRequired(
                mMockVpn.getAgent().getNetworkCapabilities()));
        mMockVpn.getAgent().setNetworkValid(false /* isStrictMode */);

        mMockVpn.connect(false);

        genericNetworkCallback.expectAvailableThenValidatedCallbacks(mMockVpn);
        genericNotVpnNetworkCallback.assertNoCallback();
        wifiNetworkCallback.assertNoCallback();
        vpnNetworkCallback.expectAvailableThenValidatedCallbacks(mMockVpn);
        defaultCallback.expectAvailableThenValidatedCallbacks(mMockVpn);
        systemDefaultCallback.assertNoCallback();
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());
        assertEquals(mWiFiNetworkAgent.getNetwork(),
                systemDefaultCallback.getLastAvailableNetwork());

        ranges.clear();
        mMockVpn.setUids(ranges);

        genericNetworkCallback.expectCallback(CallbackEntry.LOST, mMockVpn);
        genericNotVpnNetworkCallback.assertNoCallback();
        wifiNetworkCallback.assertNoCallback();
        vpnNetworkCallback.expectCallback(CallbackEntry.LOST, mMockVpn);

        // TODO : The default network callback should actually get a LOST call here (also see the
        // comment below for AVAILABLE). This is because ConnectivityService does not look at UID
        // ranges at all when determining whether a network should be rematched. In practice, VPNs
        // can't currently update their UIDs without disconnecting, so this does not matter too
        // much, but that is the reason the test here has to check for an update to the
        // capabilities instead of the expected LOST then AVAILABLE.
        defaultCallback.expectCallback(CallbackEntry.NETWORK_CAPS_UPDATED, mMockVpn);
        systemDefaultCallback.assertNoCallback();

        ranges.add(new UidRange(uid, uid));
        mMockVpn.setUids(ranges);

        genericNetworkCallback.expectAvailableCallbacksValidated(mMockVpn);
        genericNotVpnNetworkCallback.assertNoCallback();
        wifiNetworkCallback.assertNoCallback();
        vpnNetworkCallback.expectAvailableCallbacksValidated(mMockVpn);
        // TODO : Here like above, AVAILABLE would be correct, but because this can't actually
        // happen outside of the test, ConnectivityService does not rematch callbacks.
        defaultCallback.expectCallback(CallbackEntry.NETWORK_CAPS_UPDATED, mMockVpn);
        systemDefaultCallback.assertNoCallback();

        mWiFiNetworkAgent.disconnect();

        genericNetworkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        genericNotVpnNetworkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        wifiNetworkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        vpnNetworkCallback.assertNoCallback();
        defaultCallback.assertNoCallback();
        systemDefaultCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);

        mMockVpn.disconnect();

        genericNetworkCallback.expectCallback(CallbackEntry.LOST, mMockVpn);
        genericNotVpnNetworkCallback.assertNoCallback();
        wifiNetworkCallback.assertNoCallback();
        vpnNetworkCallback.expectCallback(CallbackEntry.LOST, mMockVpn);
        defaultCallback.expectCallback(CallbackEntry.LOST, mMockVpn);
        systemDefaultCallback.assertNoCallback();
        assertEquals(null, mCm.getActiveNetwork());

        mCm.unregisterNetworkCallback(genericNetworkCallback);
        mCm.unregisterNetworkCallback(wifiNetworkCallback);
        mCm.unregisterNetworkCallback(vpnNetworkCallback);
        mCm.unregisterNetworkCallback(defaultCallback);
        mCm.unregisterNetworkCallback(systemDefaultCallback);
    }

    @Test
    public void testVpnWithoutInternet() throws Exception {
        final int uid = Process.myUid();

        final TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);

        defaultCallback.expectAvailableThenValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        mMockVpn.establishForMyUid(true /* validated */, false /* hasInternet */,
                false /* isStrictMode */);
        assertUidRangesUpdatedForMyUid(true);

        defaultCallback.assertNoCallback();
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        mMockVpn.disconnect();
        defaultCallback.assertNoCallback();

        mCm.unregisterNetworkCallback(defaultCallback);
    }

    @Test
    public void testVpnWithInternet() throws Exception {
        final int uid = Process.myUid();

        final TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);

        defaultCallback.expectAvailableThenValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        mMockVpn.establishForMyUid(true /* validated */, true /* hasInternet */,
                false /* isStrictMode */);
        assertUidRangesUpdatedForMyUid(true);

        defaultCallback.expectAvailableThenValidatedCallbacks(mMockVpn);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        mMockVpn.disconnect();
        defaultCallback.expectCallback(CallbackEntry.LOST, mMockVpn);
        defaultCallback.expectAvailableCallbacksValidated(mWiFiNetworkAgent);

        mCm.unregisterNetworkCallback(defaultCallback);
    }

    @Test
    public void testVpnUnvalidated() throws Exception {
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(callback);

        // Bring up Ethernet.
        mEthernetNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_ETHERNET);
        mEthernetNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mEthernetNetworkAgent);
        callback.assertNoCallback();

        // Bring up a VPN that has the INTERNET capability, initially unvalidated.
        mMockVpn.establishForMyUid(false /* validated */, true /* hasInternet */,
                false /* isStrictMode */);
        assertUidRangesUpdatedForMyUid(true);

        // Even though the VPN is unvalidated, it becomes the default network for our app.
        callback.expectAvailableCallbacksUnvalidated(mMockVpn);
        callback.assertNoCallback();

        assertTrue(mMockVpn.getAgent().getScore() > mEthernetNetworkAgent.getScore());
        assertEquals(ConnectivityConstants.VPN_DEFAULT_SCORE, mMockVpn.getAgent().getScore());
        assertEquals(mMockVpn.getNetwork(), mCm.getActiveNetwork());

        NetworkCapabilities nc = mCm.getNetworkCapabilities(mMockVpn.getNetwork());
        assertFalse(nc.hasCapability(NET_CAPABILITY_VALIDATED));
        assertTrue(nc.hasCapability(NET_CAPABILITY_INTERNET));

        assertFalse(NetworkMonitorUtils.isValidationRequired(
                mMockVpn.getAgent().getNetworkCapabilities()));
        assertTrue(NetworkMonitorUtils.isPrivateDnsValidationRequired(
                mMockVpn.getAgent().getNetworkCapabilities()));

        // Pretend that the VPN network validates.
        mMockVpn.getAgent().setNetworkValid(false /* isStrictMode */);
        mMockVpn.getAgent().mNetworkMonitor.forceReevaluation(Process.myUid());
        // Expect to see the validated capability, but no other changes, because the VPN is already
        // the default network for the app.
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mMockVpn);
        callback.assertNoCallback();

        mMockVpn.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mMockVpn);
        callback.expectAvailableCallbacksValidated(mEthernetNetworkAgent);
    }

    @Test
    public void testVpnStartsWithUnderlyingCaps() throws Exception {
        final int uid = Process.myUid();

        final TestNetworkCallback vpnNetworkCallback = new TestNetworkCallback();
        final NetworkRequest vpnNetworkRequest = new NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_NOT_VPN)
                .addTransportType(TRANSPORT_VPN)
                .build();
        mCm.registerNetworkCallback(vpnNetworkRequest, vpnNetworkCallback);
        vpnNetworkCallback.assertNoCallback();

        // Connect cell. It will become the default network, and in the absence of setting
        // underlying networks explicitly it will become the sole underlying network for the vpn.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.addCapability(NET_CAPABILITY_NOT_SUSPENDED);
        mCellNetworkAgent.connect(true);

        mMockVpn.establishForMyUid(true /* validated */, false /* hasInternet */,
                false /* isStrictMode */);
        assertUidRangesUpdatedForMyUid(true);

        vpnNetworkCallback.expectAvailableCallbacks(mMockVpn.getNetwork(),
                false /* suspended */, false /* validated */, false /* blocked */, TIMEOUT_MS);
        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn.getNetwork(), TIMEOUT_MS,
                nc -> nc.hasCapability(NET_CAPABILITY_VALIDATED));

        final NetworkCapabilities nc = mCm.getNetworkCapabilities(mMockVpn.getNetwork());
        assertTrue(nc.hasTransport(TRANSPORT_VPN));
        assertTrue(nc.hasTransport(TRANSPORT_CELLULAR));
        assertFalse(nc.hasTransport(TRANSPORT_WIFI));
        assertTrue(nc.hasCapability(NET_CAPABILITY_VALIDATED));
        assertFalse(nc.hasCapability(NET_CAPABILITY_NOT_METERED));
        assertTrue(nc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED));

        assertVpnTransportInfo(nc, VpnManager.TYPE_VPN_SERVICE);
    }

    private void assertDefaultNetworkCapabilities(int userId, NetworkAgentWrapper... networks) {
        final NetworkCapabilities[] defaultCaps = mService.getDefaultNetworkCapabilitiesForUser(
                userId, "com.android.calling.package", "com.test");
        final String defaultCapsString = Arrays.toString(defaultCaps);
        assertEquals(defaultCapsString, defaultCaps.length, networks.length);
        final Set<NetworkCapabilities> defaultCapsSet = new ArraySet<>(defaultCaps);
        for (NetworkAgentWrapper network : networks) {
            final NetworkCapabilities nc = mCm.getNetworkCapabilities(network.getNetwork());
            final String msg = "Did not find " + nc + " in " + Arrays.toString(defaultCaps);
            assertTrue(msg, defaultCapsSet.contains(nc));
        }
    }

    @Test
    public void testVpnSetUnderlyingNetworks() throws Exception {
        final TestNetworkCallback vpnNetworkCallback = new TestNetworkCallback();
        final NetworkRequest vpnNetworkRequest = new NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_NOT_VPN)
                .addTransportType(TRANSPORT_VPN)
                .build();
        NetworkCapabilities nc;
        mCm.registerNetworkCallback(vpnNetworkRequest, vpnNetworkCallback);
        vpnNetworkCallback.assertNoCallback();

        mMockVpn.establishForMyUid(true /* validated */, false /* hasInternet */,
                false /* isStrictMode */);
        assertUidRangesUpdatedForMyUid(true);

        vpnNetworkCallback.expectAvailableThenValidatedCallbacks(mMockVpn);
        nc = mCm.getNetworkCapabilities(mMockVpn.getNetwork());
        assertTrue(nc.hasTransport(TRANSPORT_VPN));
        assertFalse(nc.hasTransport(TRANSPORT_CELLULAR));
        assertFalse(nc.hasTransport(TRANSPORT_WIFI));
        // For safety reasons a VPN without underlying networks is considered metered.
        assertFalse(nc.hasCapability(NET_CAPABILITY_NOT_METERED));
        // A VPN without underlying networks is not suspended.
        assertTrue(nc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        assertVpnTransportInfo(nc, VpnManager.TYPE_VPN_SERVICE);

        final int userId = UserHandle.getUserId(Process.myUid());
        assertDefaultNetworkCapabilities(userId /* no networks */);

        // Connect cell and use it as an underlying network.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.addCapability(NET_CAPABILITY_NOT_SUSPENDED);
        mCellNetworkAgent.connect(true);

        mMockVpn.setUnderlyingNetworks(
                new Network[] { mCellNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED)
                && caps.hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        assertDefaultNetworkCapabilities(userId, mCellNetworkAgent);

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_SUSPENDED);
        mWiFiNetworkAgent.connect(true);

        mMockVpn.setUnderlyingNetworks(
                new Network[] { mCellNetworkAgent.getNetwork(), mWiFiNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED)
                && caps.hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        assertDefaultNetworkCapabilities(userId, mCellNetworkAgent, mWiFiNetworkAgent);

        // Don't disconnect, but note the VPN is not using wifi any more.
        mMockVpn.setUnderlyingNetworks(
                new Network[] { mCellNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED)
                && caps.hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        // The return value of getDefaultNetworkCapabilitiesForUser always includes the default
        // network (wifi) as well as the underlying networks (cell).
        assertDefaultNetworkCapabilities(userId, mCellNetworkAgent, mWiFiNetworkAgent);

        // Remove NOT_SUSPENDED from the only network and observe VPN is now suspended.
        mCellNetworkAgent.removeCapability(NET_CAPABILITY_NOT_SUSPENDED);
        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED)
                && !caps.hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        vpnNetworkCallback.expectCallback(CallbackEntry.SUSPENDED, mMockVpn);

        // Add NOT_SUSPENDED again and observe VPN is no longer suspended.
        mCellNetworkAgent.addCapability(NET_CAPABILITY_NOT_SUSPENDED);
        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED)
                && caps.hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        vpnNetworkCallback.expectCallback(CallbackEntry.RESUMED, mMockVpn);

        // Use Wifi but not cell. Note the VPN is now unmetered and not suspended.
        mMockVpn.setUnderlyingNetworks(
                new Network[] { mWiFiNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && caps.hasCapability(NET_CAPABILITY_NOT_METERED)
                && caps.hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        assertDefaultNetworkCapabilities(userId, mWiFiNetworkAgent);

        // Use both again.
        mMockVpn.setUnderlyingNetworks(
                new Network[] { mCellNetworkAgent.getNetwork(), mWiFiNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED)
                && caps.hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        assertDefaultNetworkCapabilities(userId, mCellNetworkAgent, mWiFiNetworkAgent);

        // Cell is suspended again. As WiFi is not, this should not cause a callback.
        mCellNetworkAgent.removeCapability(NET_CAPABILITY_NOT_SUSPENDED);
        vpnNetworkCallback.assertNoCallback();

        // Stop using WiFi. The VPN is suspended again.
        mMockVpn.setUnderlyingNetworks(
                new Network[] { mCellNetworkAgent.getNetwork() });
        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED)
                && !caps.hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        vpnNetworkCallback.expectCallback(CallbackEntry.SUSPENDED, mMockVpn);
        assertDefaultNetworkCapabilities(userId, mCellNetworkAgent, mWiFiNetworkAgent);

        // Use both again.
        mMockVpn.setUnderlyingNetworks(
                new Network[] { mCellNetworkAgent.getNetwork(), mWiFiNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED)
                && caps.hasCapability(NET_CAPABILITY_NOT_SUSPENDED));
        vpnNetworkCallback.expectCallback(CallbackEntry.RESUMED, mMockVpn);
        assertDefaultNetworkCapabilities(userId, mCellNetworkAgent, mWiFiNetworkAgent);

        // Disconnect cell. Receive update without even removing the dead network from the
        // underlying networks – it's dead anyway. Not metered any more.
        mCellNetworkAgent.disconnect();
        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && caps.hasCapability(NET_CAPABILITY_NOT_METERED));
        assertDefaultNetworkCapabilities(userId, mWiFiNetworkAgent);

        // Disconnect wifi too. No underlying networks means this is now metered.
        mWiFiNetworkAgent.disconnect();
        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED));
        // When a network disconnects, the callbacks are fired before all state is updated, so for a
        // short time, synchronous calls will behave as if the network is still connected. Wait for
        // things to settle.
        waitForIdle();
        assertDefaultNetworkCapabilities(userId /* no networks */);

        mMockVpn.disconnect();
    }

    @Test
    public void testNullUnderlyingNetworks() throws Exception {
        final int uid = Process.myUid();

        final TestNetworkCallback vpnNetworkCallback = new TestNetworkCallback();
        final NetworkRequest vpnNetworkRequest = new NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_NOT_VPN)
                .addTransportType(TRANSPORT_VPN)
                .build();
        NetworkCapabilities nc;
        mCm.registerNetworkCallback(vpnNetworkRequest, vpnNetworkCallback);
        vpnNetworkCallback.assertNoCallback();

        mMockVpn.establishForMyUid(true /* validated */, false /* hasInternet */,
                false /* isStrictMode */);
        assertUidRangesUpdatedForMyUid(true);

        vpnNetworkCallback.expectAvailableThenValidatedCallbacks(mMockVpn);
        nc = mCm.getNetworkCapabilities(mMockVpn.getNetwork());
        assertTrue(nc.hasTransport(TRANSPORT_VPN));
        assertFalse(nc.hasTransport(TRANSPORT_CELLULAR));
        assertFalse(nc.hasTransport(TRANSPORT_WIFI));
        // By default, VPN is set to track default network (i.e. its underlying networks is null).
        // In case of no default network, VPN is considered metered.
        assertFalse(nc.hasCapability(NET_CAPABILITY_NOT_METERED));
        assertVpnTransportInfo(nc, VpnManager.TYPE_VPN_SERVICE);

        // Connect to Cell; Cell is the default network.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);

        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED));

        // Connect to WiFi; WiFi is the new default.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);

        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && caps.hasCapability(NET_CAPABILITY_NOT_METERED));

        // Disconnect Cell. The default network did not change, so there shouldn't be any changes in
        // the capabilities.
        mCellNetworkAgent.disconnect();

        // Disconnect wifi too. Now we have no default network.
        mWiFiNetworkAgent.disconnect();

        vpnNetworkCallback.expectCapabilitiesThat(mMockVpn,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED));

        mMockVpn.disconnect();
    }

    @Test
    public void testRestrictedProfileAffectsVpnUidRanges() throws Exception {
        // NETWORK_SETTINGS is necessary to see the UID ranges in NetworkCapabilities.
        mServiceContext.setPermission(Manifest.permission.NETWORK_SETTINGS,
                PERMISSION_GRANTED);

        final NetworkRequest request = new NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_NOT_VPN)
                .build();
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        // Bring up a VPN
        mMockVpn.establishForMyUid();
        assertUidRangesUpdatedForMyUid(true);
        callback.expectAvailableThenValidatedCallbacks(mMockVpn);
        callback.assertNoCallback();

        final int uid = Process.myUid();
        NetworkCapabilities nc = mCm.getNetworkCapabilities(mMockVpn.getNetwork());
        assertNotNull("nc=" + nc, nc.getUids());
        assertEquals(nc.getUids(), uidRangesForUid(uid));
        assertVpnTransportInfo(nc, VpnManager.TYPE_VPN_SERVICE);

        // Set an underlying network and expect to see the VPN transports change.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCapabilitiesThat(mMockVpn, (caps)
                -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_WIFI));
        callback.expectCapabilitiesThat(mWiFiNetworkAgent, (caps)
                -> caps.hasCapability(NET_CAPABILITY_VALIDATED));

        when(mPackageManager.getPackageUidAsUser(ALWAYS_ON_PACKAGE, RESTRICTED_USER))
                .thenReturn(UserHandle.getUid(RESTRICTED_USER, VPN_UID));

        final Intent addedIntent = new Intent(ACTION_USER_ADDED);
        addedIntent.putExtra(Intent.EXTRA_USER, UserHandle.of(RESTRICTED_USER));
        addedIntent.putExtra(Intent.EXTRA_USER_HANDLE, RESTRICTED_USER);

        // Send a USER_ADDED broadcast for it.
        processBroadcastForVpn(addedIntent);

        // Expect that the VPN UID ranges contain both |uid| and the UID range for the newly-added
        // restricted user.
        callback.expectCapabilitiesThat(mMockVpn, (caps)
                -> caps.getUids().size() == 2
                && caps.getUids().contains(new UidRange(uid, uid))
                && caps.getUids().contains(createUidRange(RESTRICTED_USER))
                && caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_WIFI));

        // Change the VPN's capabilities somehow (specifically, disconnect wifi).
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        callback.expectCapabilitiesThat(mMockVpn, (caps)
                -> caps.getUids().size() == 2
                && caps.getUids().contains(new UidRange(uid, uid))
                && caps.getUids().contains(createUidRange(RESTRICTED_USER))
                && caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_WIFI));

        // Send a USER_REMOVED broadcast and expect to lose the UID range for the restricted user.
        final Intent removedIntent = new Intent(ACTION_USER_REMOVED);
        removedIntent.putExtra(Intent.EXTRA_USER, UserHandle.of(RESTRICTED_USER));
        removedIntent.putExtra(Intent.EXTRA_USER_HANDLE, RESTRICTED_USER);
        processBroadcastForVpn(removedIntent);

        // Expect that the VPN gains the UID range for the restricted user, and that the capability
        // change made just before that (i.e., loss of TRANSPORT_WIFI) is preserved.
        callback.expectCapabilitiesThat(mMockVpn, (caps)
                -> caps.getUids().size() == 1
                && caps.getUids().contains(new UidRange(uid, uid))
                && caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_WIFI));
    }

    @Test
    public void testLockdownVpnWithRestrictedProfiles() throws Exception {
        // For ConnectivityService#setAlwaysOnVpnPackage.
        mServiceContext.setPermission(
                Manifest.permission.CONTROL_ALWAYS_ON_VPN, PERMISSION_GRANTED);
        // For call Vpn#setAlwaysOnPackage.
        mServiceContext.setPermission(
                Manifest.permission.CONTROL_VPN, PERMISSION_GRANTED);
        // Necessary to see the UID ranges in NetworkCapabilities.
        mServiceContext.setPermission(
                Manifest.permission.NETWORK_SETTINGS, PERMISSION_GRANTED);

        final NetworkRequest request = new NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_NOT_VPN)
                .build();
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        final int uid = Process.myUid();

        // Connect wifi and check that UIDs in the main and restricted profiles have network access.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true /* validated */);
        final int restrictedUid = UserHandle.getUid(RESTRICTED_USER, 42 /* appId */);
        assertNotNull(mCm.getActiveNetworkForUid(uid));
        assertNotNull(mCm.getActiveNetworkForUid(restrictedUid));

        // Enable always-on VPN lockdown. The main user loses network access because no VPN is up.
        final ArrayList<String> allowList = new ArrayList<>();
        mVpnManagerService.setAlwaysOnVpnPackage(PRIMARY_USER, ALWAYS_ON_PACKAGE,
                true /* lockdown */, allowList);
        waitForIdle();
        assertNull(mCm.getActiveNetworkForUid(uid));
        // This is arguably overspecified: a UID that is not running doesn't have an active network.
        // But it's useful to check that non-default users do not lose network access, and to prove
        // that the loss of connectivity below is indeed due to the restricted profile coming up.
        assertNotNull(mCm.getActiveNetworkForUid(restrictedUid));

        // Start the restricted profile, and check that the UID within it loses network access.
        when(mPackageManager.getPackageUidAsUser(ALWAYS_ON_PACKAGE, RESTRICTED_USER))
                .thenReturn(UserHandle.getUid(RESTRICTED_USER, VPN_UID));
        when(mUserManager.getAliveUsers()).thenReturn(Arrays.asList(PRIMARY_USER_INFO,
                RESTRICTED_USER_INFO));
        // TODO: check that VPN app within restricted profile still has access, etc.
        final Intent addedIntent = new Intent(ACTION_USER_ADDED);
        addedIntent.putExtra(Intent.EXTRA_USER, UserHandle.of(RESTRICTED_USER));
        addedIntent.putExtra(Intent.EXTRA_USER_HANDLE, RESTRICTED_USER);
        processBroadcastForVpn(addedIntent);
        assertNull(mCm.getActiveNetworkForUid(uid));
        assertNull(mCm.getActiveNetworkForUid(restrictedUid));

        // Stop the restricted profile, and check that the UID within it has network access again.
        when(mUserManager.getAliveUsers()).thenReturn(Arrays.asList(PRIMARY_USER_INFO));

        // Send a USER_REMOVED broadcast and expect to lose the UID range for the restricted user.
        final Intent removedIntent = new Intent(ACTION_USER_REMOVED);
        removedIntent.putExtra(Intent.EXTRA_USER, UserHandle.of(RESTRICTED_USER));
        removedIntent.putExtra(Intent.EXTRA_USER_HANDLE, RESTRICTED_USER);
        processBroadcastForVpn(removedIntent);
        assertNull(mCm.getActiveNetworkForUid(uid));
        assertNotNull(mCm.getActiveNetworkForUid(restrictedUid));

        mVpnManagerService.setAlwaysOnVpnPackage(PRIMARY_USER, null, false /* lockdown */,
                allowList);
        waitForIdle();
    }

    @Test
    public void testIsActiveNetworkMeteredOverWifi() throws Exception {
        // Returns true by default when no network is available.
        assertTrue(mCm.isActiveNetworkMetered());
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();

        assertFalse(mCm.isActiveNetworkMetered());
    }

    @Test
    public void testIsActiveNetworkMeteredOverCell() throws Exception {
        // Returns true by default when no network is available.
        assertTrue(mCm.isActiveNetworkMetered());
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.removeCapability(NET_CAPABILITY_NOT_METERED);
        mCellNetworkAgent.connect(true);
        waitForIdle();

        assertTrue(mCm.isActiveNetworkMetered());
    }

    @Test
    public void testIsActiveNetworkMeteredOverVpnTrackingPlatformDefault() throws Exception {
        // Returns true by default when no network is available.
        assertTrue(mCm.isActiveNetworkMetered());
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.removeCapability(NET_CAPABILITY_NOT_METERED);
        mCellNetworkAgent.connect(true);
        waitForIdle();
        assertTrue(mCm.isActiveNetworkMetered());

        // Connect VPN network. By default it is using current default network (Cell).
        mMockVpn.establishForMyUid();
        assertUidRangesUpdatedForMyUid(true);

        // Ensure VPN is now the active network.
        assertEquals(mMockVpn.getNetwork(), mCm.getActiveNetwork());

        // Expect VPN to be metered.
        assertTrue(mCm.isActiveNetworkMetered());

        // Connect WiFi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();
        // VPN should still be the active network.
        assertEquals(mMockVpn.getNetwork(), mCm.getActiveNetwork());

        // Expect VPN to be unmetered as it should now be using WiFi (new default).
        assertFalse(mCm.isActiveNetworkMetered());

        // Disconnecting Cell should not affect VPN's meteredness.
        mCellNetworkAgent.disconnect();
        waitForIdle();

        assertFalse(mCm.isActiveNetworkMetered());

        // Disconnect WiFi; Now there is no platform default network.
        mWiFiNetworkAgent.disconnect();
        waitForIdle();

        // VPN without any underlying networks is treated as metered.
        assertTrue(mCm.isActiveNetworkMetered());

        mMockVpn.disconnect();
    }

   @Test
   public void testIsActiveNetworkMeteredOverVpnSpecifyingUnderlyingNetworks() throws Exception {
        // Returns true by default when no network is available.
        assertTrue(mCm.isActiveNetworkMetered());
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.removeCapability(NET_CAPABILITY_NOT_METERED);
        mCellNetworkAgent.connect(true);
        waitForIdle();
        assertTrue(mCm.isActiveNetworkMetered());

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();
        assertFalse(mCm.isActiveNetworkMetered());

        // Connect VPN network.
        mMockVpn.establishForMyUid();
        assertUidRangesUpdatedForMyUid(true);

        // Ensure VPN is now the active network.
        assertEquals(mMockVpn.getNetwork(), mCm.getActiveNetwork());
        // VPN is using Cell
        mMockVpn.setUnderlyingNetworks(
                new Network[] { mCellNetworkAgent.getNetwork() });
        waitForIdle();

        // Expect VPN to be metered.
        assertTrue(mCm.isActiveNetworkMetered());

        // VPN is now using WiFi
        mMockVpn.setUnderlyingNetworks(
                new Network[] { mWiFiNetworkAgent.getNetwork() });
        waitForIdle();

        // Expect VPN to be unmetered
        assertFalse(mCm.isActiveNetworkMetered());

        // VPN is using Cell | WiFi.
        mMockVpn.setUnderlyingNetworks(
                new Network[] { mCellNetworkAgent.getNetwork(), mWiFiNetworkAgent.getNetwork() });
        waitForIdle();

        // Expect VPN to be metered.
        assertTrue(mCm.isActiveNetworkMetered());

        // VPN is using WiFi | Cell.
        mMockVpn.setUnderlyingNetworks(
                new Network[] { mWiFiNetworkAgent.getNetwork(), mCellNetworkAgent.getNetwork() });
        waitForIdle();

        // Order should not matter and VPN should still be metered.
        assertTrue(mCm.isActiveNetworkMetered());

        // VPN is not using any underlying networks.
        mMockVpn.setUnderlyingNetworks(new Network[0]);
        waitForIdle();

        // VPN without underlying networks is treated as metered.
        assertTrue(mCm.isActiveNetworkMetered());

        mMockVpn.disconnect();
    }

    @Test
    public void testIsActiveNetworkMeteredOverAlwaysMeteredVpn() throws Exception {
        // Returns true by default when no network is available.
        assertTrue(mCm.isActiveNetworkMetered());
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();
        assertFalse(mCm.isActiveNetworkMetered());

        // Connect VPN network.
        mMockVpn.registerAgent(true /* isAlwaysMetered */, uidRangesForUid(Process.myUid()),
                new LinkProperties());
        mMockVpn.connect(true);
        waitForIdle();
        assertEquals(mMockVpn.getNetwork(), mCm.getActiveNetwork());

        // VPN is tracking current platform default (WiFi).
        mMockVpn.setUnderlyingNetworks(null);
        waitForIdle();

        // Despite VPN using WiFi (which is unmetered), VPN itself is marked as always metered.
        assertTrue(mCm.isActiveNetworkMetered());


        // VPN explicitly declares WiFi as its underlying network.
        mMockVpn.setUnderlyingNetworks(
                new Network[] { mWiFiNetworkAgent.getNetwork() });
        waitForIdle();

        // Doesn't really matter whether VPN declares its underlying networks explicitly.
        assertTrue(mCm.isActiveNetworkMetered());

        // With WiFi lost, VPN is basically without any underlying networks. And in that case it is
        // anyways suppose to be metered.
        mWiFiNetworkAgent.disconnect();
        waitForIdle();

        assertTrue(mCm.isActiveNetworkMetered());

        mMockVpn.disconnect();
    }

    @Test
    public void testNetworkBlockedStatus() throws Exception {
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .build();
        mCm.registerNetworkCallback(cellRequest, cellNetworkCallback);
        mockUidNetworkingBlocked();

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);

        setUidRulesChanged(RULE_REJECT_ALL);
        cellNetworkCallback.expectBlockedStatusCallback(true, mCellNetworkAgent);
        assertNull(mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.BLOCKED);

        // ConnectivityService should cache it not to invoke the callback again.
        setUidRulesChanged(RULE_REJECT_METERED);
        cellNetworkCallback.assertNoCallback();

        setUidRulesChanged(RULE_NONE);
        cellNetworkCallback.expectBlockedStatusCallback(false, mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);

        setUidRulesChanged(RULE_REJECT_METERED);
        cellNetworkCallback.expectBlockedStatusCallback(true, mCellNetworkAgent);
        assertNull(mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.BLOCKED);

        // Restrict the network based on UID rule and NOT_METERED capability change.
        mCellNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        cellNetworkCallback.expectCapabilitiesWith(NET_CAPABILITY_NOT_METERED, mCellNetworkAgent);
        cellNetworkCallback.expectBlockedStatusCallback(false, mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);

        mCellNetworkAgent.removeCapability(NET_CAPABILITY_NOT_METERED);
        cellNetworkCallback.expectCapabilitiesWithout(NET_CAPABILITY_NOT_METERED,
                mCellNetworkAgent);
        cellNetworkCallback.expectBlockedStatusCallback(true, mCellNetworkAgent);
        assertNull(mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.BLOCKED);

        setUidRulesChanged(RULE_ALLOW_METERED);
        cellNetworkCallback.expectBlockedStatusCallback(false, mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);

        setUidRulesChanged(RULE_NONE);
        cellNetworkCallback.assertNoCallback();

        // Restrict background data. Networking is not blocked because the network is unmetered.
        setRestrictBackgroundChanged(true);
        cellNetworkCallback.expectBlockedStatusCallback(true, mCellNetworkAgent);
        assertNull(mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.BLOCKED);
        setRestrictBackgroundChanged(true);
        cellNetworkCallback.assertNoCallback();

        setUidRulesChanged(RULE_ALLOW_METERED);
        cellNetworkCallback.expectBlockedStatusCallback(false, mCellNetworkAgent);
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);

        setRestrictBackgroundChanged(false);
        cellNetworkCallback.assertNoCallback();
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);

        mCm.unregisterNetworkCallback(cellNetworkCallback);
    }

    @Test
    public void testNetworkBlockedStatusBeforeAndAfterConnect() throws Exception {
        final TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);
        mockUidNetworkingBlocked();

        // No Networkcallbacks invoked before any network is active.
        setUidRulesChanged(RULE_REJECT_ALL);
        setUidRulesChanged(RULE_NONE);
        setUidRulesChanged(RULE_REJECT_METERED);
        defaultCallback.assertNoCallback();

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        defaultCallback.expectAvailableCallbacksUnvalidatedAndBlocked(mCellNetworkAgent);
        defaultCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mCellNetworkAgent);

        // Allow to use the network after switching to NOT_METERED network.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);
        defaultCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);

        // Switch to METERED network. Restrict the use of the network.
        mWiFiNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksValidatedAndBlocked(mCellNetworkAgent);

        // Network becomes NOT_METERED.
        mCellNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        defaultCallback.expectCapabilitiesWith(NET_CAPABILITY_NOT_METERED, mCellNetworkAgent);
        defaultCallback.expectBlockedStatusCallback(false, mCellNetworkAgent);

        // Verify there's no Networkcallbacks invoked after data saver on/off.
        setRestrictBackgroundChanged(true);
        setRestrictBackgroundChanged(false);
        defaultCallback.assertNoCallback();

        mCellNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        defaultCallback.assertNoCallback();

        mCm.unregisterNetworkCallback(defaultCallback);
    }

    private void expectNetworkRejectNonSecureVpn(InOrder inOrder, boolean add,
            UidRangeParcel... expected) throws Exception {
        inOrder.verify(mMockNetd).networkRejectNonSecureVpn(eq(add), aryEq(expected));
    }

    private void checkNetworkInfo(NetworkInfo ni, int type, DetailedState state) {
        assertNotNull(ni);
        assertEquals(type, ni.getType());
        assertEquals(ConnectivityManager.getNetworkTypeName(type), state, ni.getDetailedState());
    }

    private void assertActiveNetworkInfo(int type, DetailedState state) {
        checkNetworkInfo(mCm.getActiveNetworkInfo(), type, state);
    }
    private void assertNetworkInfo(int type, DetailedState state) {
        checkNetworkInfo(mCm.getNetworkInfo(type), type, state);
    }

    // Checks that each of the |agents| receive a blocked status change callback with the specified
    // |blocked| value, in any order. This is needed because when an event affects multiple
    // networks, ConnectivityService does not guarantee the order in which callbacks are fired.
    private void assertBlockedCallbackInAnyOrder(TestNetworkCallback callback, boolean blocked,
            TestNetworkAgentWrapper... agents) {
        final List<Network> expectedNetworks = Arrays.asList(agents).stream()
                .map((agent) -> agent.getNetwork())
                .collect(Collectors.toList());

        // Expect exactly one blocked callback for each agent.
        for (int i = 0; i < agents.length; i++) {
            CallbackEntry e = callback.expectCallbackThat(TIMEOUT_MS, (c) ->
                    c instanceof CallbackEntry.BlockedStatus
                            && ((CallbackEntry.BlockedStatus) c).getBlocked() == blocked);
            Network network = e.getNetwork();
            assertTrue("Received unexpected blocked callback for network " + network,
                    expectedNetworks.remove(network));
        }
    }

    @Test
    public void testNetworkBlockedStatusAlwaysOnVpn() throws Exception {
        mServiceContext.setPermission(
                Manifest.permission.CONTROL_ALWAYS_ON_VPN, PERMISSION_GRANTED);
        mServiceContext.setPermission(
                Manifest.permission.CONTROL_VPN, PERMISSION_GRANTED);
        mServiceContext.setPermission(
                Manifest.permission.NETWORK_SETTINGS, PERMISSION_GRANTED);

        final TestNetworkCallback callback = new TestNetworkCallback();
        final NetworkRequest request = new NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_NOT_VPN)
                .build();
        mCm.registerNetworkCallback(request, callback);

        final TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        final TestNetworkCallback vpnUidCallback = new TestNetworkCallback();
        final NetworkRequest vpnUidRequest = new NetworkRequest.Builder().build();
        registerNetworkCallbackAsUid(vpnUidRequest, vpnUidCallback, VPN_UID);

        final int uid = Process.myUid();
        final int userId = UserHandle.getUserId(uid);
        final ArrayList<String> allowList = new ArrayList<>();
        mVpnManagerService.setAlwaysOnVpnPackage(userId, ALWAYS_ON_PACKAGE, true /* lockdown */,
                allowList);
        waitForIdle();

        UidRangeParcel firstHalf = new UidRangeParcel(1, VPN_UID - 1);
        UidRangeParcel secondHalf = new UidRangeParcel(VPN_UID + 1, 99999);
        InOrder inOrder = inOrder(mMockNetd);
        expectNetworkRejectNonSecureVpn(inOrder, true, firstHalf, secondHalf);

        // Connect a network when lockdown is active, expect to see it blocked.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false /* validated */);
        callback.expectAvailableCallbacksUnvalidatedAndBlocked(mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidatedAndBlocked(mWiFiNetworkAgent);
        vpnUidCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetworkForUid(VPN_UID));
        assertNull(mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_WIFI, DetailedState.BLOCKED);
        // Mobile is BLOCKED even though it's not actually connected.
        assertNetworkInfo(TYPE_MOBILE, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.BLOCKED);

        // Disable lockdown, expect to see the network unblocked.
        mVpnManagerService.setAlwaysOnVpnPackage(userId, null, false /* lockdown */, allowList);
        callback.expectBlockedStatusCallback(false, mWiFiNetworkAgent);
        defaultCallback.expectBlockedStatusCallback(false, mWiFiNetworkAgent);
        vpnUidCallback.assertNoCallback();
        expectNetworkRejectNonSecureVpn(inOrder, false, firstHalf, secondHalf);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetworkForUid(VPN_UID));
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);

        // Add our UID to the allowlist and re-enable lockdown, expect network is not blocked.
        allowList.add(TEST_PACKAGE_NAME);
        mVpnManagerService.setAlwaysOnVpnPackage(userId, ALWAYS_ON_PACKAGE, true /* lockdown */,
                allowList);
        callback.assertNoCallback();
        defaultCallback.assertNoCallback();
        vpnUidCallback.assertNoCallback();

        // The following requires that the UID of this test package is greater than VPN_UID. This
        // is always true in practice because a plain AOSP build with no apps installed has almost
        // 200 packages installed.
        final UidRangeParcel piece1 = new UidRangeParcel(1, VPN_UID - 1);
        final UidRangeParcel piece2 = new UidRangeParcel(VPN_UID + 1, uid - 1);
        final UidRangeParcel piece3 = new UidRangeParcel(uid + 1, 99999);
        expectNetworkRejectNonSecureVpn(inOrder, true, piece1, piece2, piece3);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetworkForUid(VPN_UID));
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);

        // Connect a new network, expect it to be unblocked.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false /* validated */);
        callback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        defaultCallback.assertNoCallback();
        vpnUidCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetworkForUid(VPN_UID));
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);
        // Cellular is DISCONNECTED because it's not the default and there are no requests for it.
        assertNetworkInfo(TYPE_MOBILE, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);

        // Disable lockdown, remove our UID from the allowlist, and re-enable lockdown.
        // Everything should now be blocked.
        mVpnManagerService.setAlwaysOnVpnPackage(userId, null, false /* lockdown */, allowList);
        waitForIdle();
        expectNetworkRejectNonSecureVpn(inOrder, false, piece1, piece2, piece3);
        allowList.clear();
        mVpnManagerService.setAlwaysOnVpnPackage(userId, ALWAYS_ON_PACKAGE, true /* lockdown */,
                allowList);
        waitForIdle();
        expectNetworkRejectNonSecureVpn(inOrder, true, firstHalf, secondHalf);
        defaultCallback.expectBlockedStatusCallback(true, mWiFiNetworkAgent);
        assertBlockedCallbackInAnyOrder(callback, true, mWiFiNetworkAgent, mCellNetworkAgent);
        vpnUidCallback.assertNoCallback();
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetworkForUid(VPN_UID));
        assertNull(mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_WIFI, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.BLOCKED);

        // Disable lockdown. Everything is unblocked.
        mVpnManagerService.setAlwaysOnVpnPackage(userId, null, false /* lockdown */, allowList);
        defaultCallback.expectBlockedStatusCallback(false, mWiFiNetworkAgent);
        assertBlockedCallbackInAnyOrder(callback, false, mWiFiNetworkAgent, mCellNetworkAgent);
        vpnUidCallback.assertNoCallback();
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetworkForUid(VPN_UID));
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);

        // Enable and disable an always-on VPN package without lockdown. Expect no changes.
        reset(mMockNetd);
        mVpnManagerService.setAlwaysOnVpnPackage(userId, ALWAYS_ON_PACKAGE, false /* lockdown */,
                allowList);
        inOrder.verify(mMockNetd, never()).networkRejectNonSecureVpn(anyBoolean(), any());
        callback.assertNoCallback();
        defaultCallback.assertNoCallback();
        vpnUidCallback.assertNoCallback();
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetworkForUid(VPN_UID));
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);

        mVpnManagerService.setAlwaysOnVpnPackage(userId, null, false /* lockdown */, allowList);
        inOrder.verify(mMockNetd, never()).networkRejectNonSecureVpn(anyBoolean(), any());
        callback.assertNoCallback();
        defaultCallback.assertNoCallback();
        vpnUidCallback.assertNoCallback();
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetworkForUid(VPN_UID));
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);

        // Enable lockdown and connect a VPN. The VPN is not blocked.
        mVpnManagerService.setAlwaysOnVpnPackage(userId, ALWAYS_ON_PACKAGE, true /* lockdown */,
                allowList);
        defaultCallback.expectBlockedStatusCallback(true, mWiFiNetworkAgent);
        assertBlockedCallbackInAnyOrder(callback, true, mWiFiNetworkAgent, mCellNetworkAgent);
        vpnUidCallback.assertNoCallback();
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetworkForUid(VPN_UID));
        assertNull(mCm.getActiveNetwork());
        assertActiveNetworkInfo(TYPE_WIFI, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.BLOCKED);

        mMockVpn.establishForMyUid();
        assertUidRangesUpdatedForMyUid(true);
        defaultCallback.expectAvailableThenValidatedCallbacks(mMockVpn);
        vpnUidCallback.assertNoCallback();  // vpnUidCallback has NOT_VPN capability.
        assertEquals(mMockVpn.getNetwork(), mCm.getActiveNetwork());
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetworkForUid(VPN_UID));
        assertActiveNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_VPN, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);

        mMockVpn.disconnect();
        defaultCallback.expectCallback(CallbackEntry.LOST, mMockVpn);
        defaultCallback.expectAvailableCallbacksUnvalidatedAndBlocked(mWiFiNetworkAgent);
        assertNull(mCm.getActiveNetwork());

        mCm.unregisterNetworkCallback(callback);
        mCm.unregisterNetworkCallback(defaultCallback);
        mCm.unregisterNetworkCallback(vpnUidCallback);
    }

    private void setupLegacyLockdownVpn() {
        final String profileName = "testVpnProfile";
        final byte[] profileTag = profileName.getBytes(StandardCharsets.UTF_8);
        when(mKeyStore.contains(Credentials.LOCKDOWN_VPN)).thenReturn(true);
        when(mKeyStore.get(Credentials.LOCKDOWN_VPN)).thenReturn(profileTag);

        final VpnProfile profile = new VpnProfile(profileName);
        profile.name = "My VPN";
        profile.server = "192.0.2.1";
        profile.dnsServers = "8.8.8.8";
        profile.type = VpnProfile.TYPE_IPSEC_XAUTH_PSK;
        final byte[] encodedProfile = profile.encode();
        when(mKeyStore.get(Credentials.VPN + profileName)).thenReturn(encodedProfile);
    }

    private void establishLegacyLockdownVpn(Network underlying) throws Exception {
        // The legacy lockdown VPN only supports userId 0, and must have an underlying network.
        assertNotNull(underlying);
        mMockVpn.setVpnType(VpnManager.TYPE_VPN_LEGACY);
        // The legacy lockdown VPN only supports userId 0.
        final Set<UidRange> ranges = Collections.singleton(createUidRange(PRIMARY_USER));
        mMockVpn.registerAgent(ranges);
        mMockVpn.setUnderlyingNetworks(new Network[]{underlying});
        mMockVpn.connect(true);
    }

    @Test
    public void testLegacyLockdownVpn() throws Exception {
        mServiceContext.setPermission(
                Manifest.permission.CONTROL_VPN, PERMISSION_GRANTED);
        // For LockdownVpnTracker to call registerSystemDefaultNetworkCallback.
        mServiceContext.setPermission(
                Manifest.permission.NETWORK_SETTINGS, PERMISSION_GRANTED);

        final NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        final TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

        final TestNetworkCallback systemDefaultCallback = new TestNetworkCallback();
        mCm.registerSystemDefaultNetworkCallback(systemDefaultCallback,
                new Handler(ConnectivityThread.getInstanceLooper()));

        // Pretend lockdown VPN was configured.
        setupLegacyLockdownVpn();

        // LockdownVpnTracker disables the Vpn teardown code and enables lockdown.
        // Check the VPN's state before it does so.
        assertTrue(mMockVpn.getEnableTeardown());
        assertFalse(mMockVpn.getLockdown());

        // Send a USER_UNLOCKED broadcast so CS starts LockdownVpnTracker.
        final int userId = UserHandle.getUserId(Process.myUid());
        final Intent addedIntent = new Intent(ACTION_USER_UNLOCKED);
        addedIntent.putExtra(Intent.EXTRA_USER, UserHandle.of(userId));
        addedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        processBroadcastForVpn(addedIntent);

        // Lockdown VPN disables teardown and enables lockdown.
        assertFalse(mMockVpn.getEnableTeardown());
        assertTrue(mMockVpn.getLockdown());

        // Bring up a network.
        // Expect nothing to happen because the network does not have an IPv4 default route: legacy
        // VPN only supports IPv4.
        final LinkProperties cellLp = new LinkProperties();
        cellLp.setInterfaceName("rmnet0");
        cellLp.addLinkAddress(new LinkAddress("2001:db8::1/64"));
        cellLp.addRoute(new RouteInfo(new IpPrefix("::/0"), null, "rmnet0"));
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR, cellLp);
        mCellNetworkAgent.connect(false /* validated */);
        callback.expectAvailableCallbacksUnvalidatedAndBlocked(mCellNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidatedAndBlocked(mCellNetworkAgent);
        systemDefaultCallback.expectAvailableCallbacksUnvalidatedAndBlocked(mCellNetworkAgent);
        waitForIdle();
        assertNull(mMockVpn.getAgent());

        // Add an IPv4 address. Ideally the VPN should start, but it doesn't because nothing calls
        // LockdownVpnTracker#handleStateChangedLocked. This is a bug.
        // TODO: consider fixing this.
        cellLp.addLinkAddress(new LinkAddress("192.0.2.2/25"));
        cellLp.addRoute(new RouteInfo(new IpPrefix("0.0.0.0/0"), null, "rmnet0"));
        mCellNetworkAgent.sendLinkProperties(cellLp);
        callback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent);
        defaultCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent);
        systemDefaultCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED,
                mCellNetworkAgent);
        waitForIdle();
        assertNull(mMockVpn.getAgent());

        // Disconnect, then try again with a network that supports IPv4 at connection time.
        // Expect lockdown VPN to come up.
        ExpectedBroadcast b1 = expectConnectivityAction(TYPE_MOBILE, DetailedState.DISCONNECTED);
        mCellNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        defaultCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        systemDefaultCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        b1.expectBroadcast();

        // When lockdown VPN is active, the NetworkInfo state in CONNECTIVITY_ACTION is overwritten
        // with the state of the VPN network. So expect a CONNECTING broadcast.
        b1 = expectConnectivityAction(TYPE_MOBILE, DetailedState.CONNECTING);
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR, cellLp);
        mCellNetworkAgent.connect(false /* validated */);
        callback.expectAvailableCallbacksUnvalidatedAndBlocked(mCellNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidatedAndBlocked(mCellNetworkAgent);
        systemDefaultCallback.expectAvailableCallbacksUnvalidatedAndBlocked(mCellNetworkAgent);
        b1.expectBroadcast();
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_VPN, DetailedState.BLOCKED);

        // TODO: it would be nice if we could simply rely on the production code here, and have
        // LockdownVpnTracker start the VPN, have the VPN code register its NetworkAgent with
        // ConnectivityService, etc. That would require duplicating a fair bit of code from the
        // Vpn tests around how to mock out LegacyVpnRunner. But even if we did that, this does not
        // work for at least two reasons:
        // 1. In this test, calling registerNetworkAgent does not actually result in an agent being
        //    registered. This is because nothing calls onNetworkMonitorCreated, which is what
        //    actually ends up causing handleRegisterNetworkAgent to be called. Code in this test
        //    that wants to register an agent must use TestNetworkAgentWrapper.
        // 2. Even if we exposed Vpn#agentConnect to the test, and made MockVpn#agentConnect call
        //    the TestNetworkAgentWrapper code, this would deadlock because the
        //    TestNetworkAgentWrapper code cannot be called on the handler thread since it calls
        //    waitForIdle().
        mMockVpn.expectStartLegacyVpnRunner();
        b1 = expectConnectivityAction(TYPE_VPN, DetailedState.CONNECTED);
        ExpectedBroadcast b2 = expectConnectivityAction(TYPE_MOBILE, DetailedState.CONNECTED);
        establishLegacyLockdownVpn(mCellNetworkAgent.getNetwork());
        callback.expectAvailableThenValidatedCallbacks(mMockVpn);
        defaultCallback.expectAvailableThenValidatedCallbacks(mMockVpn);
        systemDefaultCallback.assertNoCallback();
        NetworkCapabilities vpnNc = mCm.getNetworkCapabilities(mMockVpn.getNetwork());
        b1.expectBroadcast();
        b2.expectBroadcast();
        assertActiveNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_VPN, DetailedState.CONNECTED);
        assertTrue(vpnNc.hasTransport(TRANSPORT_VPN));
        assertTrue(vpnNc.hasTransport(TRANSPORT_CELLULAR));
        assertFalse(vpnNc.hasTransport(TRANSPORT_WIFI));
        assertFalse(vpnNc.hasCapability(NET_CAPABILITY_NOT_METERED));
        assertVpnTransportInfo(vpnNc, VpnManager.TYPE_VPN_LEGACY);

        // Switch default network from cell to wifi. Expect VPN to disconnect and reconnect.
        final LinkProperties wifiLp = new LinkProperties();
        wifiLp.setInterfaceName("wlan0");
        wifiLp.addLinkAddress(new LinkAddress("192.0.2.163/25"));
        wifiLp.addRoute(new RouteInfo(new IpPrefix("0.0.0.0/0"), null, "wlan0"));
        final NetworkCapabilities wifiNc = new NetworkCapabilities();
        wifiNc.addTransportType(TRANSPORT_WIFI);
        wifiNc.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI, wifiLp, wifiNc);

        b1 = expectConnectivityAction(TYPE_MOBILE, DetailedState.DISCONNECTED);
        // Wifi is CONNECTING because the VPN isn't up yet.
        b2 = expectConnectivityAction(TYPE_WIFI, DetailedState.CONNECTING);
        ExpectedBroadcast b3 = expectConnectivityAction(TYPE_VPN, DetailedState.DISCONNECTED);
        mWiFiNetworkAgent.connect(false /* validated */);
        b1.expectBroadcast();
        b2.expectBroadcast();
        b3.expectBroadcast();
        mMockVpn.expectStopVpnRunnerPrivileged();
        mMockVpn.expectStartLegacyVpnRunner();

        // TODO: why is wifi not blocked? Is it because when this callback is sent, the VPN is still
        // connected, so the network is not considered blocked by the lockdown UID ranges? But the
        // fact that a VPN is connected should only result in the VPN itself being unblocked, not
        // any other network. Bug in isUidBlockedByVpn?
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        callback.expectCallback(CallbackEntry.LOST, mMockVpn);
        defaultCallback.expectCallback(CallbackEntry.LOST, mMockVpn);
        defaultCallback.expectAvailableCallbacksUnvalidatedAndBlocked(mWiFiNetworkAgent);
        systemDefaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);

        // While the VPN is reconnecting on the new network, everything is blocked.
        assertActiveNetworkInfo(TYPE_WIFI, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.BLOCKED);
        assertNetworkInfo(TYPE_VPN, DetailedState.BLOCKED);

        // The VPN comes up again on wifi.
        b1 = expectConnectivityAction(TYPE_VPN, DetailedState.CONNECTED);
        b2 = expectConnectivityAction(TYPE_WIFI, DetailedState.CONNECTED);
        establishLegacyLockdownVpn(mWiFiNetworkAgent.getNetwork());
        callback.expectAvailableThenValidatedCallbacks(mMockVpn);
        defaultCallback.expectAvailableThenValidatedCallbacks(mMockVpn);
        systemDefaultCallback.assertNoCallback();
        b1.expectBroadcast();
        b2.expectBroadcast();
        assertActiveNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_VPN, DetailedState.CONNECTED);
        vpnNc = mCm.getNetworkCapabilities(mMockVpn.getNetwork());
        assertTrue(vpnNc.hasTransport(TRANSPORT_VPN));
        assertTrue(vpnNc.hasTransport(TRANSPORT_WIFI));
        assertFalse(vpnNc.hasTransport(TRANSPORT_CELLULAR));
        assertTrue(vpnNc.hasCapability(NET_CAPABILITY_NOT_METERED));

        // Disconnect cell. Nothing much happens since it's not the default network.
        mCellNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        defaultCallback.assertNoCallback();
        systemDefaultCallback.assertNoCallback();

        assertActiveNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_MOBILE, DetailedState.DISCONNECTED);
        assertNetworkInfo(TYPE_WIFI, DetailedState.CONNECTED);
        assertNetworkInfo(TYPE_VPN, DetailedState.CONNECTED);

        b1 = expectConnectivityAction(TYPE_WIFI, DetailedState.DISCONNECTED);
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        systemDefaultCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        b1.expectBroadcast();
        callback.expectCapabilitiesThat(mMockVpn, nc -> !nc.hasTransport(TRANSPORT_WIFI));
        b2 = expectConnectivityAction(TYPE_VPN, DetailedState.DISCONNECTED);
        mMockVpn.expectStopVpnRunnerPrivileged();
        callback.expectCallback(CallbackEntry.LOST, mMockVpn);
        b2.expectBroadcast();
    }

    /**
     * Test mutable and requestable network capabilities such as
     * {@link NetworkCapabilities#NET_CAPABILITY_TRUSTED} and
     * {@link NetworkCapabilities#NET_CAPABILITY_NOT_VCN_MANAGED}. Verify that the
     * {@code ConnectivityService} re-assign the networks accordingly.
     */
    @Test
    public final void testLoseMutableAndRequestableCaps() throws Exception {
        final int[] testCaps = new int [] {
                NET_CAPABILITY_TRUSTED,
                NET_CAPABILITY_NOT_VCN_MANAGED
        };
        for (final int testCap : testCaps) {
            // Create requests with and without the testing capability.
            final TestNetworkCallback callbackWithCap = new TestNetworkCallback();
            final TestNetworkCallback callbackWithoutCap = new TestNetworkCallback();
            mCm.requestNetwork(new NetworkRequest.Builder().addCapability(testCap).build(),
                    callbackWithCap);
            mCm.requestNetwork(new NetworkRequest.Builder().removeCapability(testCap).build(),
                    callbackWithoutCap);

            // Setup networks with testing capability and verify the default network changes.
            mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
            mCellNetworkAgent.addCapability(testCap);
            mCellNetworkAgent.connect(true);
            callbackWithCap.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
            callbackWithoutCap.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
            verify(mMockNetd).networkSetDefault(eq(mCellNetworkAgent.getNetwork().netId));
            reset(mMockNetd);

            mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
            mWiFiNetworkAgent.addCapability(testCap);
            mWiFiNetworkAgent.connect(true);
            callbackWithCap.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
            callbackWithoutCap.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
            verify(mMockNetd).networkSetDefault(eq(mWiFiNetworkAgent.getNetwork().netId));
            reset(mMockNetd);

            // Remove the testing capability on wifi, verify the callback and default network
            // changes back to cellular.
            mWiFiNetworkAgent.removeCapability(testCap);
            callbackWithCap.expectAvailableCallbacksValidated(mCellNetworkAgent);
            callbackWithoutCap.expectCapabilitiesWithout(testCap, mWiFiNetworkAgent);
            verify(mMockNetd).networkSetDefault(eq(mCellNetworkAgent.getNetwork().netId));
            reset(mMockNetd);

            mCellNetworkAgent.removeCapability(testCap);
            callbackWithCap.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
            callbackWithoutCap.assertNoCallback();
            verify(mMockNetd).networkClearDefault();

            mCm.unregisterNetworkCallback(callbackWithCap);
            mCm.unregisterNetworkCallback(callbackWithoutCap);
        }
    }

    @Test
    public final void testBatteryStatsNetworkType() throws Exception {
        final LinkProperties cellLp = new LinkProperties();
        cellLp.setInterfaceName("cell0");
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR, cellLp);
        mCellNetworkAgent.connect(true);
        waitForIdle();
        verify(mBatteryStatsService).noteNetworkInterfaceForTransports(cellLp.getInterfaceName(),
                new int[] { TRANSPORT_CELLULAR });
        reset(mBatteryStatsService);

        final LinkProperties wifiLp = new LinkProperties();
        wifiLp.setInterfaceName("wifi0");
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI, wifiLp);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();
        verify(mBatteryStatsService).noteNetworkInterfaceForTransports(wifiLp.getInterfaceName(),
                new int[] { TRANSPORT_WIFI });
        reset(mBatteryStatsService);

        mCellNetworkAgent.disconnect();
        mWiFiNetworkAgent.disconnect();

        cellLp.setInterfaceName("wifi0");
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR, cellLp);
        mCellNetworkAgent.connect(true);
        waitForIdle();
        verify(mBatteryStatsService).noteNetworkInterfaceForTransports(cellLp.getInterfaceName(),
                new int[] { TRANSPORT_CELLULAR });
        mCellNetworkAgent.disconnect();
    }

    /**
     * Make simulated InterfaceConfigParcel for Nat464Xlat to query clat lower layer info.
     */
    private InterfaceConfigurationParcel getClatInterfaceConfigParcel(LinkAddress la) {
        final InterfaceConfigurationParcel cfg = new InterfaceConfigurationParcel();
        cfg.hwAddr = "11:22:33:44:55:66";
        cfg.ipv4Addr = la.getAddress().getHostAddress();
        cfg.prefixLength = la.getPrefixLength();
        return cfg;
    }

    /**
     * Make expected stack link properties, copied from Nat464Xlat.
     */
    private LinkProperties makeClatLinkProperties(LinkAddress la) {
        LinkAddress clatAddress = la;
        LinkProperties stacked = new LinkProperties();
        stacked.setInterfaceName(CLAT_PREFIX + MOBILE_IFNAME);
        RouteInfo ipv4Default = new RouteInfo(
                new LinkAddress(Inet4Address.ANY, 0),
                clatAddress.getAddress(), CLAT_PREFIX + MOBILE_IFNAME);
        stacked.addRoute(ipv4Default);
        stacked.addLinkAddress(clatAddress);
        return stacked;
    }

    @Test
    public void testStackedLinkProperties() throws Exception {
        final LinkAddress myIpv4 = new LinkAddress("1.2.3.4/24");
        final LinkAddress myIpv6 = new LinkAddress("2001:db8:1::1/64");
        final String kNat64PrefixString = "2001:db8:64:64:64:64::";
        final IpPrefix kNat64Prefix = new IpPrefix(InetAddress.getByName(kNat64PrefixString), 96);
        final String kOtherNat64PrefixString = "64:ff9b::";
        final IpPrefix kOtherNat64Prefix = new IpPrefix(
                InetAddress.getByName(kOtherNat64PrefixString), 96);
        final RouteInfo defaultRoute = new RouteInfo((IpPrefix) null, myIpv6.getAddress(),
                                                     MOBILE_IFNAME);
        final RouteInfo ipv6Subnet = new RouteInfo(myIpv6, null, MOBILE_IFNAME);
        final RouteInfo ipv4Subnet = new RouteInfo(myIpv4, null, MOBILE_IFNAME);
        final RouteInfo stackedDefault = new RouteInfo((IpPrefix) null, myIpv4.getAddress(),
                                                       CLAT_PREFIX + MOBILE_IFNAME);

        final NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET)
                .build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(networkRequest, networkCallback);

        // Prepare ipv6 only link properties.
        final LinkProperties cellLp = new LinkProperties();
        cellLp.setInterfaceName(MOBILE_IFNAME);
        cellLp.addLinkAddress(myIpv6);
        cellLp.addRoute(defaultRoute);
        cellLp.addRoute(ipv6Subnet);
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR, cellLp);
        reset(mMockDnsResolver);
        reset(mMockNetd);
        reset(mBatteryStatsService);

        // Connect with ipv6 link properties. Expect prefix discovery to be started.
        mCellNetworkAgent.connect(true);
        final int cellNetId = mCellNetworkAgent.getNetwork().netId;
        waitForIdle();

        verify(mMockNetd, times(1)).networkCreatePhysical(eq(cellNetId), anyInt());
        assertRoutesAdded(cellNetId, ipv6Subnet, defaultRoute);
        verify(mMockDnsResolver, times(1)).createNetworkCache(eq(cellNetId));
        verify(mMockNetd, times(1)).networkAddInterface(cellNetId, MOBILE_IFNAME);
        verify(mBatteryStatsService).noteNetworkInterfaceForTransports(cellLp.getInterfaceName(),
                new int[] { TRANSPORT_CELLULAR });

        networkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        verify(mMockDnsResolver, times(1)).startPrefix64Discovery(cellNetId);

        // Switching default network updates TCP buffer sizes.
        verifyTcpBufferSizeChange(ConnectivityService.DEFAULT_TCP_BUFFER_SIZES);
        verify(mSystemProperties, times(1)).setTcpInitRwnd(eq(TEST_TCP_INIT_RWND));
        // Add an IPv4 address. Expect prefix discovery to be stopped. Netd doesn't tell us that
        // the NAT64 prefix was removed because one was never discovered.
        cellLp.addLinkAddress(myIpv4);
        mCellNetworkAgent.sendLinkProperties(cellLp);
        networkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent);
        assertRoutesAdded(cellNetId, ipv4Subnet);
        verify(mMockDnsResolver, times(1)).stopPrefix64Discovery(cellNetId);
        verify(mMockDnsResolver, atLeastOnce()).setResolverConfiguration(any());

        // Make sure BatteryStats was not told about any v4- interfaces, as none should have
        // come online yet.
        waitForIdle();
        verify(mBatteryStatsService, never()).noteNetworkInterfaceForTransports(startsWith("v4-"),
                any());

        verifyNoMoreInteractions(mMockNetd);
        verifyNoMoreInteractions(mMockDnsResolver);
        reset(mMockNetd);
        reset(mMockDnsResolver);
        when(mMockNetd.interfaceGetCfg(CLAT_PREFIX + MOBILE_IFNAME))
                .thenReturn(getClatInterfaceConfigParcel(myIpv4));

        // Remove IPv4 address. Expect prefix discovery to be started again.
        cellLp.removeLinkAddress(myIpv4);
        mCellNetworkAgent.sendLinkProperties(cellLp);
        networkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent);
        verify(mMockDnsResolver, times(1)).startPrefix64Discovery(cellNetId);
        assertRoutesRemoved(cellNetId, ipv4Subnet);

        // When NAT64 prefix discovery succeeds, LinkProperties are updated and clatd is started.
        Nat464Xlat clat = getNat464Xlat(mCellNetworkAgent);
        assertNull(mCm.getLinkProperties(mCellNetworkAgent.getNetwork()).getNat64Prefix());
        mService.mNetdEventCallback.onNat64PrefixEvent(cellNetId, true /* added */,
                kNat64PrefixString, 96);
        LinkProperties lpBeforeClat = networkCallback.expectCallback(
                CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent).getLp();
        assertEquals(0, lpBeforeClat.getStackedLinks().size());
        assertEquals(kNat64Prefix, lpBeforeClat.getNat64Prefix());
        verify(mMockNetd, times(1)).clatdStart(MOBILE_IFNAME, kNat64Prefix.toString());

        // Clat iface comes up. Expect stacked link to be added.
        clat.interfaceLinkStateChanged(CLAT_PREFIX + MOBILE_IFNAME, true);
        networkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent);
        List<LinkProperties> stackedLps = mCm.getLinkProperties(mCellNetworkAgent.getNetwork())
                .getStackedLinks();
        assertEquals(makeClatLinkProperties(myIpv4), stackedLps.get(0));
        assertRoutesAdded(cellNetId, stackedDefault);
        verify(mMockNetd, times(1)).networkAddInterface(cellNetId, CLAT_PREFIX + MOBILE_IFNAME);
        // Change trivial linkproperties and see if stacked link is preserved.
        cellLp.addDnsServer(InetAddress.getByName("8.8.8.8"));
        mCellNetworkAgent.sendLinkProperties(cellLp);
        networkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent);

        List<LinkProperties> stackedLpsAfterChange =
                mCm.getLinkProperties(mCellNetworkAgent.getNetwork()).getStackedLinks();
        assertNotEquals(stackedLpsAfterChange, Collections.EMPTY_LIST);
        assertEquals(makeClatLinkProperties(myIpv4), stackedLpsAfterChange.get(0));

        verify(mMockDnsResolver, times(1)).setResolverConfiguration(
                mResolverParamsParcelCaptor.capture());
        ResolverParamsParcel resolvrParams = mResolverParamsParcelCaptor.getValue();
        assertEquals(1, resolvrParams.servers.length);
        assertTrue(ArrayUtils.contains(resolvrParams.servers, "8.8.8.8"));

        for (final LinkProperties stackedLp : stackedLpsAfterChange) {
            verify(mBatteryStatsService).noteNetworkInterfaceForTransports(
                    stackedLp.getInterfaceName(), new int[] { TRANSPORT_CELLULAR });
        }
        reset(mMockNetd);
        when(mMockNetd.interfaceGetCfg(CLAT_PREFIX + MOBILE_IFNAME))
                .thenReturn(getClatInterfaceConfigParcel(myIpv4));
        // Change the NAT64 prefix without first removing it.
        // Expect clatd to be stopped and started with the new prefix.
        mService.mNetdEventCallback.onNat64PrefixEvent(cellNetId, true /* added */,
                kOtherNat64PrefixString, 96);
        networkCallback.expectLinkPropertiesThat(mCellNetworkAgent,
                (lp) -> lp.getStackedLinks().size() == 0);
        verify(mMockNetd, times(1)).clatdStop(MOBILE_IFNAME);
        assertRoutesRemoved(cellNetId, stackedDefault);
        verify(mMockNetd, times(1)).networkRemoveInterface(cellNetId, CLAT_PREFIX + MOBILE_IFNAME);

        verify(mMockNetd, times(1)).clatdStart(MOBILE_IFNAME, kOtherNat64Prefix.toString());
        networkCallback.expectLinkPropertiesThat(mCellNetworkAgent,
                (lp) -> lp.getNat64Prefix().equals(kOtherNat64Prefix));
        clat.interfaceLinkStateChanged(CLAT_PREFIX + MOBILE_IFNAME, true);
        networkCallback.expectLinkPropertiesThat(mCellNetworkAgent,
                (lp) -> lp.getStackedLinks().size() == 1);
        assertRoutesAdded(cellNetId, stackedDefault);
        verify(mMockNetd, times(1)).networkAddInterface(cellNetId, CLAT_PREFIX + MOBILE_IFNAME);
        reset(mMockNetd);

        // Add ipv4 address, expect that clatd and prefix discovery are stopped and stacked
        // linkproperties are cleaned up.
        cellLp.addLinkAddress(myIpv4);
        cellLp.addRoute(ipv4Subnet);
        mCellNetworkAgent.sendLinkProperties(cellLp);
        networkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent);
        assertRoutesAdded(cellNetId, ipv4Subnet);
        verify(mMockNetd, times(1)).clatdStop(MOBILE_IFNAME);
        verify(mMockDnsResolver, times(1)).stopPrefix64Discovery(cellNetId);

        // As soon as stop is called, the linkproperties lose the stacked interface.
        networkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent);
        LinkProperties actualLpAfterIpv4 = mCm.getLinkProperties(mCellNetworkAgent.getNetwork());
        LinkProperties expected = new LinkProperties(cellLp);
        expected.setNat64Prefix(kOtherNat64Prefix);
        assertEquals(expected, actualLpAfterIpv4);
        assertEquals(0, actualLpAfterIpv4.getStackedLinks().size());
        assertRoutesRemoved(cellNetId, stackedDefault);

        // The interface removed callback happens but has no effect after stop is called.
        clat.interfaceRemoved(CLAT_PREFIX + MOBILE_IFNAME);
        networkCallback.assertNoCallback();
        verify(mMockNetd, times(1)).networkRemoveInterface(cellNetId, CLAT_PREFIX + MOBILE_IFNAME);
        verifyNoMoreInteractions(mMockNetd);
        verifyNoMoreInteractions(mMockDnsResolver);
        reset(mMockNetd);
        reset(mMockDnsResolver);
        when(mMockNetd.interfaceGetCfg(CLAT_PREFIX + MOBILE_IFNAME))
                .thenReturn(getClatInterfaceConfigParcel(myIpv4));

        // Stopping prefix discovery causes netd to tell us that the NAT64 prefix is gone.
        mService.mNetdEventCallback.onNat64PrefixEvent(cellNetId, false /* added */,
                kOtherNat64PrefixString, 96);
        networkCallback.expectLinkPropertiesThat(mCellNetworkAgent,
                (lp) -> lp.getNat64Prefix() == null);

        // Remove IPv4 address and expect prefix discovery and clatd to be started again.
        cellLp.removeLinkAddress(myIpv4);
        cellLp.removeRoute(new RouteInfo(myIpv4, null, MOBILE_IFNAME));
        cellLp.removeDnsServer(InetAddress.getByName("8.8.8.8"));
        mCellNetworkAgent.sendLinkProperties(cellLp);
        networkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent);
        assertRoutesRemoved(cellNetId, ipv4Subnet);  // Directly-connected routes auto-added.
        verify(mMockDnsResolver, times(1)).startPrefix64Discovery(cellNetId);
        mService.mNetdEventCallback.onNat64PrefixEvent(cellNetId, true /* added */,
                kNat64PrefixString, 96);
        networkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent);
        verify(mMockNetd, times(1)).clatdStart(MOBILE_IFNAME, kNat64Prefix.toString());

        // Clat iface comes up. Expect stacked link to be added.
        clat.interfaceLinkStateChanged(CLAT_PREFIX + MOBILE_IFNAME, true);
        networkCallback.expectLinkPropertiesThat(mCellNetworkAgent,
                (lp) -> lp.getStackedLinks().size() == 1 && lp.getNat64Prefix() != null);
        assertRoutesAdded(cellNetId, stackedDefault);
        verify(mMockNetd, times(1)).networkAddInterface(cellNetId, CLAT_PREFIX + MOBILE_IFNAME);

        // NAT64 prefix is removed. Expect that clat is stopped.
        mService.mNetdEventCallback.onNat64PrefixEvent(cellNetId, false /* added */,
                kNat64PrefixString, 96);
        networkCallback.expectLinkPropertiesThat(mCellNetworkAgent,
                (lp) -> lp.getStackedLinks().size() == 0 && lp.getNat64Prefix() == null);
        assertRoutesRemoved(cellNetId, ipv4Subnet, stackedDefault);

        // Stop has no effect because clat is already stopped.
        verify(mMockNetd, times(1)).clatdStop(MOBILE_IFNAME);
        networkCallback.expectLinkPropertiesThat(mCellNetworkAgent,
                (lp) -> lp.getStackedLinks().size() == 0);
        verify(mMockNetd, times(1)).networkRemoveInterface(cellNetId, CLAT_PREFIX + MOBILE_IFNAME);
        verify(mMockNetd, times(1)).interfaceGetCfg(CLAT_PREFIX + MOBILE_IFNAME);
        verifyNoMoreInteractions(mMockNetd);
        // Clean up.
        mCellNetworkAgent.disconnect();
        networkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        networkCallback.assertNoCallback();
        mCm.unregisterNetworkCallback(networkCallback);
    }

    private void expectNat64PrefixChange(TestableNetworkCallback callback,
            TestNetworkAgentWrapper agent, IpPrefix prefix) {
        callback.expectLinkPropertiesThat(agent, x -> Objects.equals(x.getNat64Prefix(), prefix));
    }

    @Test
    public void testNat64PrefixMultipleSources() throws Exception {
        final String iface = "wlan0";
        final String pref64FromRaStr = "64:ff9b::";
        final String pref64FromDnsStr = "2001:db8:64::";
        final IpPrefix pref64FromRa = new IpPrefix(InetAddress.getByName(pref64FromRaStr), 96);
        final IpPrefix pref64FromDns = new IpPrefix(InetAddress.getByName(pref64FromDnsStr), 96);
        final IpPrefix newPref64FromRa = new IpPrefix("2001:db8:64:64:64:64::/96");

        final NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_INTERNET)
                .build();
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, callback);

        final LinkProperties baseLp = new LinkProperties();
        baseLp.setInterfaceName(iface);
        baseLp.addLinkAddress(new LinkAddress("2001:db8:1::1/64"));
        baseLp.addDnsServer(InetAddress.getByName("2001:4860:4860::6464"));

        reset(mMockNetd, mMockDnsResolver);
        InOrder inOrder = inOrder(mMockNetd, mMockDnsResolver);

        // If a network already has a NAT64 prefix on connect, clatd is started immediately and
        // prefix discovery is never started.
        LinkProperties lp = new LinkProperties(baseLp);
        lp.setNat64Prefix(pref64FromRa);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI, lp);
        mWiFiNetworkAgent.connect(false);
        final Network network = mWiFiNetworkAgent.getNetwork();
        int netId = network.getNetId();
        callback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        inOrder.verify(mMockNetd).clatdStart(iface, pref64FromRa.toString());
        inOrder.verify(mMockDnsResolver).setPrefix64(netId, pref64FromRa.toString());
        inOrder.verify(mMockDnsResolver, never()).startPrefix64Discovery(netId);
        callback.assertNoCallback();
        assertEquals(pref64FromRa, mCm.getLinkProperties(network).getNat64Prefix());

        // If the RA prefix is withdrawn, clatd is stopped and prefix discovery is started.
        lp.setNat64Prefix(null);
        mWiFiNetworkAgent.sendLinkProperties(lp);
        expectNat64PrefixChange(callback, mWiFiNetworkAgent, null);
        inOrder.verify(mMockNetd).clatdStop(iface);
        inOrder.verify(mMockDnsResolver).setPrefix64(netId, "");
        inOrder.verify(mMockDnsResolver).startPrefix64Discovery(netId);

        // If the RA prefix appears while DNS discovery is in progress, discovery is stopped and
        // clatd is started with the prefix from the RA.
        lp.setNat64Prefix(pref64FromRa);
        mWiFiNetworkAgent.sendLinkProperties(lp);
        expectNat64PrefixChange(callback, mWiFiNetworkAgent, pref64FromRa);
        inOrder.verify(mMockNetd).clatdStart(iface, pref64FromRa.toString());
        inOrder.verify(mMockDnsResolver).stopPrefix64Discovery(netId);
        inOrder.verify(mMockDnsResolver).setPrefix64(netId, pref64FromRa.toString());

        // Withdraw the RA prefix so we can test the case where an RA prefix appears after DNS
        // discovery has succeeded.
        lp.setNat64Prefix(null);
        mWiFiNetworkAgent.sendLinkProperties(lp);
        expectNat64PrefixChange(callback, mWiFiNetworkAgent, null);
        inOrder.verify(mMockNetd).clatdStop(iface);
        inOrder.verify(mMockDnsResolver).setPrefix64(netId, "");
        inOrder.verify(mMockDnsResolver).startPrefix64Discovery(netId);

        mService.mNetdEventCallback.onNat64PrefixEvent(netId, true /* added */,
                pref64FromDnsStr, 96);
        expectNat64PrefixChange(callback, mWiFiNetworkAgent, pref64FromDns);
        inOrder.verify(mMockNetd).clatdStart(iface, pref64FromDns.toString());

        // If an RA advertises the same prefix that was discovered by DNS, nothing happens: prefix
        // discovery is not stopped, and there are no callbacks.
        lp.setNat64Prefix(pref64FromDns);
        mWiFiNetworkAgent.sendLinkProperties(lp);
        callback.assertNoCallback();
        inOrder.verify(mMockNetd, never()).clatdStop(iface);
        inOrder.verify(mMockNetd, never()).clatdStart(eq(iface), anyString());
        inOrder.verify(mMockDnsResolver, never()).stopPrefix64Discovery(netId);
        inOrder.verify(mMockDnsResolver, never()).startPrefix64Discovery(netId);
        inOrder.verify(mMockDnsResolver, never()).setPrefix64(eq(netId), anyString());

        // If the RA is later withdrawn, nothing happens again.
        lp.setNat64Prefix(null);
        mWiFiNetworkAgent.sendLinkProperties(lp);
        callback.assertNoCallback();
        inOrder.verify(mMockNetd, never()).clatdStop(iface);
        inOrder.verify(mMockNetd, never()).clatdStart(eq(iface), anyString());
        inOrder.verify(mMockDnsResolver, never()).stopPrefix64Discovery(netId);
        inOrder.verify(mMockDnsResolver, never()).startPrefix64Discovery(netId);
        inOrder.verify(mMockDnsResolver, never()).setPrefix64(eq(netId), anyString());

        // If the RA prefix changes, clatd is restarted and prefix discovery is stopped.
        lp.setNat64Prefix(pref64FromRa);
        mWiFiNetworkAgent.sendLinkProperties(lp);
        expectNat64PrefixChange(callback, mWiFiNetworkAgent, pref64FromRa);
        inOrder.verify(mMockNetd).clatdStop(iface);
        inOrder.verify(mMockDnsResolver).stopPrefix64Discovery(netId);

        // Stopping prefix discovery results in a prefix removed notification.
        mService.mNetdEventCallback.onNat64PrefixEvent(netId, false /* added */,
                pref64FromDnsStr, 96);

        inOrder.verify(mMockNetd).clatdStart(iface, pref64FromRa.toString());
        inOrder.verify(mMockDnsResolver).setPrefix64(netId, pref64FromRa.toString());
        inOrder.verify(mMockDnsResolver, never()).startPrefix64Discovery(netId);

        // If the RA prefix changes, clatd is restarted and prefix discovery is not started.
        lp.setNat64Prefix(newPref64FromRa);
        mWiFiNetworkAgent.sendLinkProperties(lp);
        expectNat64PrefixChange(callback, mWiFiNetworkAgent, newPref64FromRa);
        inOrder.verify(mMockNetd).clatdStop(iface);
        inOrder.verify(mMockDnsResolver).setPrefix64(netId, "");
        inOrder.verify(mMockNetd).clatdStart(iface, newPref64FromRa.toString());
        inOrder.verify(mMockDnsResolver).setPrefix64(netId, newPref64FromRa.toString());
        inOrder.verify(mMockDnsResolver, never()).stopPrefix64Discovery(netId);
        inOrder.verify(mMockDnsResolver, never()).startPrefix64Discovery(netId);

        // If the RA prefix changes to the same value, nothing happens.
        lp.setNat64Prefix(newPref64FromRa);
        mWiFiNetworkAgent.sendLinkProperties(lp);
        callback.assertNoCallback();
        assertEquals(newPref64FromRa, mCm.getLinkProperties(network).getNat64Prefix());
        inOrder.verify(mMockNetd, never()).clatdStop(iface);
        inOrder.verify(mMockNetd, never()).clatdStart(eq(iface), anyString());
        inOrder.verify(mMockDnsResolver, never()).stopPrefix64Discovery(netId);
        inOrder.verify(mMockDnsResolver, never()).startPrefix64Discovery(netId);
        inOrder.verify(mMockDnsResolver, never()).setPrefix64(eq(netId), anyString());

        // The transition between no prefix and DNS prefix is tested in testStackedLinkProperties.

        // If the same prefix is learned first by DNS and then by RA, and clat is later stopped,
        // (e.g., because the network disconnects) setPrefix64(netid, "") is never called.
        lp.setNat64Prefix(null);
        mWiFiNetworkAgent.sendLinkProperties(lp);
        expectNat64PrefixChange(callback, mWiFiNetworkAgent, null);
        inOrder.verify(mMockNetd).clatdStop(iface);
        inOrder.verify(mMockDnsResolver).setPrefix64(netId, "");
        inOrder.verify(mMockDnsResolver).startPrefix64Discovery(netId);
        mService.mNetdEventCallback.onNat64PrefixEvent(netId, true /* added */,
                pref64FromDnsStr, 96);
        expectNat64PrefixChange(callback, mWiFiNetworkAgent, pref64FromDns);
        inOrder.verify(mMockNetd).clatdStart(iface, pref64FromDns.toString());
        inOrder.verify(mMockDnsResolver, never()).setPrefix64(eq(netId), any());

        lp.setNat64Prefix(pref64FromDns);
        mWiFiNetworkAgent.sendLinkProperties(lp);
        callback.assertNoCallback();
        inOrder.verify(mMockNetd, never()).clatdStop(iface);
        inOrder.verify(mMockNetd, never()).clatdStart(eq(iface), anyString());
        inOrder.verify(mMockDnsResolver, never()).stopPrefix64Discovery(netId);
        inOrder.verify(mMockDnsResolver, never()).startPrefix64Discovery(netId);
        inOrder.verify(mMockDnsResolver, never()).setPrefix64(eq(netId), anyString());

        // When tearing down a network, clat state is only updated after CALLBACK_LOST is fired, but
        // before CONNECTIVITY_ACTION is sent. Wait for CONNECTIVITY_ACTION before verifying that
        // clat has been stopped, or the test will be flaky.
        ExpectedBroadcast b = expectConnectivityAction(TYPE_WIFI, DetailedState.DISCONNECTED);
        mWiFiNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        b.expectBroadcast();

        inOrder.verify(mMockNetd).clatdStop(iface);
        inOrder.verify(mMockDnsResolver).stopPrefix64Discovery(netId);
        inOrder.verify(mMockDnsResolver, never()).setPrefix64(eq(netId), anyString());

        mCm.unregisterNetworkCallback(callback);
    }

    @Test
    public void testDataActivityTracking() throws Exception {
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        final NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_INTERNET)
                .build();
        mCm.registerNetworkCallback(networkRequest, networkCallback);

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        final LinkProperties cellLp = new LinkProperties();
        cellLp.setInterfaceName(MOBILE_IFNAME);
        mCellNetworkAgent.sendLinkProperties(cellLp);
        mCellNetworkAgent.connect(true);
        networkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        verify(mMockNetd, times(1)).idletimerAddInterface(eq(MOBILE_IFNAME), anyInt(),
                eq(Integer.toString(TRANSPORT_CELLULAR)));

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        final LinkProperties wifiLp = new LinkProperties();
        wifiLp.setInterfaceName(WIFI_IFNAME);
        mWiFiNetworkAgent.sendLinkProperties(wifiLp);

        // Network switch
        mWiFiNetworkAgent.connect(true);
        networkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        networkCallback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        networkCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        verify(mMockNetd, times(1)).idletimerAddInterface(eq(WIFI_IFNAME), anyInt(),
                eq(Integer.toString(TRANSPORT_WIFI)));
        verify(mMockNetd, times(1)).idletimerRemoveInterface(eq(MOBILE_IFNAME), anyInt(),
                eq(Integer.toString(TRANSPORT_CELLULAR)));

        // Disconnect wifi and switch back to cell
        reset(mMockNetd);
        mWiFiNetworkAgent.disconnect();
        networkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        assertNoCallbacks(networkCallback);
        verify(mMockNetd, times(1)).idletimerRemoveInterface(eq(WIFI_IFNAME), anyInt(),
                eq(Integer.toString(TRANSPORT_WIFI)));
        verify(mMockNetd, times(1)).idletimerAddInterface(eq(MOBILE_IFNAME), anyInt(),
                eq(Integer.toString(TRANSPORT_CELLULAR)));

        // reconnect wifi
        reset(mMockNetd);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        wifiLp.setInterfaceName(WIFI_IFNAME);
        mWiFiNetworkAgent.sendLinkProperties(wifiLp);
        mWiFiNetworkAgent.connect(true);
        networkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        networkCallback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        networkCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        verify(mMockNetd, times(1)).idletimerAddInterface(eq(WIFI_IFNAME), anyInt(),
                eq(Integer.toString(TRANSPORT_WIFI)));
        verify(mMockNetd, times(1)).idletimerRemoveInterface(eq(MOBILE_IFNAME), anyInt(),
                eq(Integer.toString(TRANSPORT_CELLULAR)));

        // Disconnect cell
        reset(mMockNetd);
        mCellNetworkAgent.disconnect();
        networkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        // LOST callback is triggered earlier than removing idle timer. Broadcast should also be
        // sent as network being switched. Ensure rule removal for cell will not be triggered
        // unexpectedly before network being removed.
        waitForIdle();
        verify(mMockNetd, times(0)).idletimerRemoveInterface(eq(MOBILE_IFNAME), anyInt(),
                eq(Integer.toString(TRANSPORT_CELLULAR)));
        verify(mMockNetd, times(1)).networkDestroy(eq(mCellNetworkAgent.getNetwork().netId));
        verify(mMockDnsResolver, times(1))
                .destroyNetworkCache(eq(mCellNetworkAgent.getNetwork().netId));

        // Disconnect wifi
        ExpectedBroadcast b = expectConnectivityAction(TYPE_WIFI, DetailedState.DISCONNECTED);
        mWiFiNetworkAgent.disconnect();
        b.expectBroadcast();
        verify(mMockNetd, times(1)).idletimerRemoveInterface(eq(WIFI_IFNAME), anyInt(),
                eq(Integer.toString(TRANSPORT_WIFI)));

        // Clean up
        mCm.unregisterNetworkCallback(networkCallback);
    }

    private void verifyTcpBufferSizeChange(String tcpBufferSizes) throws Exception {
        String[] values = tcpBufferSizes.split(",");
        String rmemValues = String.join(" ", values[0], values[1], values[2]);
        String wmemValues = String.join(" ", values[3], values[4], values[5]);
        verify(mMockNetd, atLeastOnce()).setTcpRWmemorySize(rmemValues, wmemValues);
        reset(mMockNetd);
    }

    @Test
    public void testTcpBufferReset() throws Exception {
        final String testTcpBufferSizes = "1,2,3,4,5,6";
        final NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET)
                .build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(networkRequest, networkCallback);

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        reset(mMockNetd);
        // Switching default network updates TCP buffer sizes.
        mCellNetworkAgent.connect(false);
        networkCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        verifyTcpBufferSizeChange(ConnectivityService.DEFAULT_TCP_BUFFER_SIZES);
        verify(mSystemProperties, times(1)).setTcpInitRwnd(eq(TEST_TCP_INIT_RWND));
        // Change link Properties should have updated tcp buffer size.
        LinkProperties lp = new LinkProperties();
        lp.setTcpBufferSizes(testTcpBufferSizes);
        mCellNetworkAgent.sendLinkProperties(lp);
        networkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent);
        verifyTcpBufferSizeChange(testTcpBufferSizes);
        verify(mSystemProperties, times(2)).setTcpInitRwnd(eq(TEST_TCP_INIT_RWND));
        // Clean up.
        mCellNetworkAgent.disconnect();
        networkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        networkCallback.assertNoCallback();
        mCm.unregisterNetworkCallback(networkCallback);
    }

    @Test
    public void testGetGlobalProxyForNetwork() throws Exception {
        final ProxyInfo testProxyInfo = ProxyInfo.buildDirectProxy("test", 8888);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        final Network wifiNetwork = mWiFiNetworkAgent.getNetwork();
        when(mService.mProxyTracker.getGlobalProxy()).thenReturn(testProxyInfo);
        assertEquals(testProxyInfo, mService.getProxyForNetwork(wifiNetwork));
    }

    @Test
    public void testGetProxyForActiveNetwork() throws Exception {
        final ProxyInfo testProxyInfo = ProxyInfo.buildDirectProxy("test", 8888);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();
        assertNull(mService.getProxyForNetwork(null));

        final LinkProperties testLinkProperties = new LinkProperties();
        testLinkProperties.setHttpProxy(testProxyInfo);

        mWiFiNetworkAgent.sendLinkProperties(testLinkProperties);
        waitForIdle();

        assertEquals(testProxyInfo, mService.getProxyForNetwork(null));
    }

    @Test
    public void testGetProxyForVPN() throws Exception {
        final ProxyInfo testProxyInfo = ProxyInfo.buildDirectProxy("test", 8888);

        // Set up a WiFi network with no proxy
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();
        assertNull(mService.getProxyForNetwork(null));

        // Connect a VPN network with a proxy.
        LinkProperties testLinkProperties = new LinkProperties();
        testLinkProperties.setHttpProxy(testProxyInfo);
        mMockVpn.establishForMyUid(testLinkProperties);
        assertUidRangesUpdatedForMyUid(true);

        // Test that the VPN network returns a proxy, and the WiFi does not.
        assertEquals(testProxyInfo, mService.getProxyForNetwork(mMockVpn.getNetwork()));
        assertEquals(testProxyInfo, mService.getProxyForNetwork(null));
        assertNull(mService.getProxyForNetwork(mWiFiNetworkAgent.getNetwork()));

        // Test that the VPN network returns no proxy when it is set to null.
        testLinkProperties.setHttpProxy(null);
        mMockVpn.sendLinkProperties(testLinkProperties);
        waitForIdle();
        assertNull(mService.getProxyForNetwork(mMockVpn.getNetwork()));
        assertNull(mService.getProxyForNetwork(null));

        // Set WiFi proxy and check that the vpn proxy is still null.
        testLinkProperties.setHttpProxy(testProxyInfo);
        mWiFiNetworkAgent.sendLinkProperties(testLinkProperties);
        waitForIdle();
        assertNull(mService.getProxyForNetwork(null));

        // Disconnect from VPN and check that the active network, which is now the WiFi, has the
        // correct proxy setting.
        mMockVpn.disconnect();
        waitForIdle();
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        assertEquals(testProxyInfo, mService.getProxyForNetwork(mWiFiNetworkAgent.getNetwork()));
        assertEquals(testProxyInfo, mService.getProxyForNetwork(null));
    }

    @Test
    public void testFullyRoutedVpnResultsInInterfaceFilteringRules() throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("tun0");
        lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), null));
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), RTN_UNREACHABLE));
        // The uid range needs to cover the test app so the network is visible to it.
        final Set<UidRange> vpnRange = Collections.singleton(createUidRange(PRIMARY_USER));
        mMockVpn.establish(lp, VPN_UID, vpnRange);
        assertVpnUidRangesUpdated(true, vpnRange, VPN_UID);

        // A connected VPN should have interface rules set up. There are two expected invocations,
        // one during the VPN initial connection, one during the VPN LinkProperties update.
        ArgumentCaptor<int[]> uidCaptor = ArgumentCaptor.forClass(int[].class);
        verify(mMockNetd, times(2)).firewallAddUidInterfaceRules(eq("tun0"), uidCaptor.capture());
        assertContainsExactly(uidCaptor.getAllValues().get(0), APP1_UID, APP2_UID);
        assertContainsExactly(uidCaptor.getAllValues().get(1), APP1_UID, APP2_UID);
        assertTrue(mService.mPermissionMonitor.getVpnUidRanges("tun0").equals(vpnRange));

        mMockVpn.disconnect();
        waitForIdle();

        // Disconnected VPN should have interface rules removed
        verify(mMockNetd).firewallRemoveUidInterfaceRules(uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP1_UID, APP2_UID);
        assertNull(mService.mPermissionMonitor.getVpnUidRanges("tun0"));
    }

    @Test
    public void testLegacyVpnDoesNotResultInInterfaceFilteringRule() throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("tun0");
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), null));
        lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), null));
        // The uid range needs to cover the test app so the network is visible to it.
        final Set<UidRange> vpnRange = Collections.singleton(createUidRange(PRIMARY_USER));
        mMockVpn.establish(lp, Process.SYSTEM_UID, vpnRange);
        assertVpnUidRangesUpdated(true, vpnRange, Process.SYSTEM_UID);

        // Legacy VPN should not have interface rules set up
        verify(mMockNetd, never()).firewallAddUidInterfaceRules(any(), any());
    }

    @Test
    public void testLocalIpv4OnlyVpnDoesNotResultInInterfaceFilteringRule()
            throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("tun0");
        lp.addRoute(new RouteInfo(new IpPrefix("192.0.2.0/24"), null, "tun0"));
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), RTN_UNREACHABLE));
        // The uid range needs to cover the test app so the network is visible to it.
        final Set<UidRange> vpnRange = Collections.singleton(createUidRange(PRIMARY_USER));
        mMockVpn.establish(lp, Process.SYSTEM_UID, vpnRange);
        assertVpnUidRangesUpdated(true, vpnRange, Process.SYSTEM_UID);

        // IPv6 unreachable route should not be misinterpreted as a default route
        verify(mMockNetd, never()).firewallAddUidInterfaceRules(any(), any());
    }

    @Test
    public void testVpnHandoverChangesInterfaceFilteringRule() throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("tun0");
        lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), null));
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), null));
        // The uid range needs to cover the test app so the network is visible to it.
        final Set<UidRange> vpnRange = Collections.singleton(createUidRange(PRIMARY_USER));
        mMockVpn.establish(lp, VPN_UID, vpnRange);
        assertVpnUidRangesUpdated(true, vpnRange, VPN_UID);

        // Connected VPN should have interface rules set up. There are two expected invocations,
        // one during VPN uid update, one during VPN LinkProperties update
        ArgumentCaptor<int[]> uidCaptor = ArgumentCaptor.forClass(int[].class);
        verify(mMockNetd, times(2)).firewallAddUidInterfaceRules(eq("tun0"), uidCaptor.capture());
        assertContainsExactly(uidCaptor.getAllValues().get(0), APP1_UID, APP2_UID);
        assertContainsExactly(uidCaptor.getAllValues().get(1), APP1_UID, APP2_UID);

        reset(mMockNetd);
        InOrder inOrder = inOrder(mMockNetd);
        lp.setInterfaceName("tun1");
        mMockVpn.sendLinkProperties(lp);
        waitForIdle();
        // VPN handover (switch to a new interface) should result in rules being updated (old rules
        // removed first, then new rules added)
        inOrder.verify(mMockNetd).firewallRemoveUidInterfaceRules(uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP1_UID, APP2_UID);
        inOrder.verify(mMockNetd).firewallAddUidInterfaceRules(eq("tun1"), uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP1_UID, APP2_UID);

        reset(mMockNetd);
        lp = new LinkProperties();
        lp.setInterfaceName("tun1");
        lp.addRoute(new RouteInfo(new IpPrefix("192.0.2.0/24"), null, "tun1"));
        mMockVpn.sendLinkProperties(lp);
        waitForIdle();
        // VPN not routing everything should no longer have interface filtering rules
        verify(mMockNetd).firewallRemoveUidInterfaceRules(uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP1_UID, APP2_UID);

        reset(mMockNetd);
        lp = new LinkProperties();
        lp.setInterfaceName("tun1");
        lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), RTN_UNREACHABLE));
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), null));
        mMockVpn.sendLinkProperties(lp);
        waitForIdle();
        // Back to routing all IPv6 traffic should have filtering rules
        verify(mMockNetd).firewallAddUidInterfaceRules(eq("tun1"), uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP1_UID, APP2_UID);
    }

    @Test
    public void testUidUpdateChangesInterfaceFilteringRule() throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("tun0");
        lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), RTN_UNREACHABLE));
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), null));
        // The uid range needs to cover the test app so the network is visible to it.
        final UidRange vpnRange = createUidRange(PRIMARY_USER);
        final Set<UidRange> vpnRanges = Collections.singleton(vpnRange);
        mMockVpn.establish(lp, VPN_UID, vpnRanges);
        assertVpnUidRangesUpdated(true, vpnRanges, VPN_UID);

        reset(mMockNetd);
        InOrder inOrder = inOrder(mMockNetd);

        // Update to new range which is old range minus APP1, i.e. only APP2
        final Set<UidRange> newRanges = new HashSet<>(Arrays.asList(
                new UidRange(vpnRange.start, APP1_UID - 1),
                new UidRange(APP1_UID + 1, vpnRange.stop)));
        mMockVpn.setUids(newRanges);
        waitForIdle();

        ArgumentCaptor<int[]> uidCaptor = ArgumentCaptor.forClass(int[].class);
        // Verify old rules are removed before new rules are added
        inOrder.verify(mMockNetd).firewallRemoveUidInterfaceRules(uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP1_UID, APP2_UID);
        inOrder.verify(mMockNetd).firewallAddUidInterfaceRules(eq("tun0"), uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP2_UID);
    }

    @Test
    public void testLinkPropertiesWithWakeOnLanForActiveNetwork() throws Exception {
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);

        LinkProperties wifiLp = new LinkProperties();
        wifiLp.setInterfaceName(WIFI_WOL_IFNAME);
        wifiLp.setWakeOnLanSupported(false);

        // Default network switch should update ifaces.
        mWiFiNetworkAgent.connect(false);
        mWiFiNetworkAgent.sendLinkProperties(wifiLp);
        waitForIdle();

        // ConnectivityService should have changed the WakeOnLanSupported to true
        wifiLp.setWakeOnLanSupported(true);
        assertEquals(wifiLp, mService.getActiveLinkProperties());
    }

    @Test
    public void testLegacyExtraInfoSentToNetworkMonitor() throws Exception {
        class TestNetworkAgent extends NetworkAgent {
            TestNetworkAgent(Context context, Looper looper, NetworkAgentConfig config) {
                super(context, looper, "MockAgent", new NetworkCapabilities(),
                        new LinkProperties(), 40 , config, null /* provider */);
            }
        }
        final NetworkAgent naNoExtraInfo = new TestNetworkAgent(
                mServiceContext, mCsHandlerThread.getLooper(), new NetworkAgentConfig());
        naNoExtraInfo.register();
        verify(mNetworkStack).makeNetworkMonitor(any(), isNull(String.class), any());
        naNoExtraInfo.unregister();

        reset(mNetworkStack);
        final NetworkAgentConfig config =
                new NetworkAgentConfig.Builder().setLegacyExtraInfo("legacyinfo").build();
        final NetworkAgent naExtraInfo = new TestNetworkAgent(
                mServiceContext, mCsHandlerThread.getLooper(), config);
        naExtraInfo.register();
        verify(mNetworkStack).makeNetworkMonitor(any(), eq("legacyinfo"), any());
        naExtraInfo.unregister();
    }

    // To avoid granting location permission bypass.
    private void denyAllLocationPrivilegedPermissions() {
        mServiceContext.setPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
                PERMISSION_DENIED);
        mServiceContext.setPermission(Manifest.permission.NETWORK_SETTINGS,
                PERMISSION_DENIED);
        mServiceContext.setPermission(Manifest.permission.NETWORK_STACK,
                PERMISSION_DENIED);
        mServiceContext.setPermission(Manifest.permission.NETWORK_SETUP_WIZARD,
                PERMISSION_DENIED);
    }

    private void setupLocationPermissions(
            int targetSdk, boolean locationToggle, String op, String perm) throws Exception {
        denyAllLocationPrivilegedPermissions();

        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.targetSdkVersion = targetSdk;
        when(mPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), any()))
                .thenReturn(applicationInfo);

        when(mLocationManager.isLocationEnabledForUser(any())).thenReturn(locationToggle);

        if (op != null) {
            when(mAppOpsManager.noteOp(eq(op), eq(Process.myUid()),
                    eq(mContext.getPackageName()), eq(getAttributionTag()), anyString()))
                .thenReturn(AppOpsManager.MODE_ALLOWED);
        }

        if (perm != null) {
            mServiceContext.setPermission(perm, PERMISSION_GRANTED);
        }
    }

    private int getOwnerUidNetCapsForCallerPermission(int ownerUid, int callerUid) {
        final NetworkCapabilities netCap = new NetworkCapabilities().setOwnerUid(ownerUid);

        return mService.createWithLocationInfoSanitizedIfNecessaryWhenParceled(
                netCap, callerUid, mContext.getPackageName(), getAttributionTag()).getOwnerUid();
    }

    private void verifyWifiInfoCopyNetCapsForCallerPermission(
            int callerUid, boolean shouldMakeCopyWithLocationSensitiveFieldsParcelable) {
        final WifiInfo wifiInfo = mock(WifiInfo.class);
        when(wifiInfo.hasLocationSensitiveFields()).thenReturn(true);
        final NetworkCapabilities netCap = new NetworkCapabilities().setTransportInfo(wifiInfo);

        mService.createWithLocationInfoSanitizedIfNecessaryWhenParceled(
                netCap, callerUid, mContext.getPackageName(), getAttributionTag());
        verify(wifiInfo).makeCopy(eq(shouldMakeCopyWithLocationSensitiveFieldsParcelable));
    }

    @Test
    public void testCreateForCallerWithLocationInfoSanitizedWithFineLocationAfterQ()
            throws Exception {
        setupLocationPermissions(Build.VERSION_CODES.Q, true, AppOpsManager.OPSTR_FINE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);

        final int myUid = Process.myUid();
        assertEquals(myUid, getOwnerUidNetCapsForCallerPermission(myUid, myUid));

        verifyWifiInfoCopyNetCapsForCallerPermission(myUid,
                true /* shouldMakeCopyWithLocationSensitiveFieldsParcelable */);
    }

    @Test
    public void testCreateForCallerWithLocationInfoSanitizedWithCoarseLocationPreQ()
            throws Exception {
        setupLocationPermissions(Build.VERSION_CODES.P, true, AppOpsManager.OPSTR_COARSE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);

        final int myUid = Process.myUid();
        assertEquals(myUid, getOwnerUidNetCapsForCallerPermission(myUid, myUid));

        verifyWifiInfoCopyNetCapsForCallerPermission(myUid,
                true /* shouldMakeCopyWithLocationSensitiveFieldsParcelable */);
    }

    @Test
    public void testCreateForCallerWithLocationInfoSanitizedLocationOff() throws Exception {
        // Test that even with fine location permission, and UIDs matching, the UID is sanitized.
        setupLocationPermissions(Build.VERSION_CODES.Q, false, AppOpsManager.OPSTR_FINE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);

        final int myUid = Process.myUid();
        assertEquals(Process.INVALID_UID, getOwnerUidNetCapsForCallerPermission(myUid, myUid));

        verifyWifiInfoCopyNetCapsForCallerPermission(myUid,
                false/* shouldMakeCopyWithLocationSensitiveFieldsParcelable */);
    }

    @Test
    public void testCreateForCallerWithLocationInfoSanitizedWrongUid() throws Exception {
        // Test that even with fine location permission, not being the owner leads to sanitization.
        setupLocationPermissions(Build.VERSION_CODES.Q, true, AppOpsManager.OPSTR_FINE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);

        final int myUid = Process.myUid();
        assertEquals(Process.INVALID_UID, getOwnerUidNetCapsForCallerPermission(myUid + 1, myUid));

        verifyWifiInfoCopyNetCapsForCallerPermission(myUid,
                true /* shouldMakeCopyWithLocationSensitiveFieldsParcelable */);
    }

    @Test
    public void testCreateForCallerWithLocationInfoSanitizedWithCoarseLocationAfterQ()
            throws Exception {
        // Test that not having fine location permission leads to sanitization.
        setupLocationPermissions(Build.VERSION_CODES.Q, true, AppOpsManager.OPSTR_COARSE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);

        // Test that without the location permission, the owner field is sanitized.
        final int myUid = Process.myUid();
        assertEquals(Process.INVALID_UID, getOwnerUidNetCapsForCallerPermission(myUid, myUid));

        verifyWifiInfoCopyNetCapsForCallerPermission(myUid,
                false/* shouldMakeCopyWithLocationSensitiveFieldsParcelable */);
    }

    @Test
    public void testCreateForCallerWithLocationInfoSanitizedWithoutLocationPermission()
            throws Exception {
        setupLocationPermissions(Build.VERSION_CODES.Q, true, null /* op */, null /* perm */);

        // Test that without the location permission, the owner field is sanitized.
        final int myUid = Process.myUid();
        assertEquals(Process.INVALID_UID, getOwnerUidNetCapsForCallerPermission(myUid, myUid));

        verifyWifiInfoCopyNetCapsForCallerPermission(myUid,
                false/* shouldMakeCopyWithLocationSensitiveFieldsParcelable */);
    }

    private void setupConnectionOwnerUid(int vpnOwnerUid, @VpnManager.VpnType int vpnType)
            throws Exception {
        final Set<UidRange> vpnRange = Collections.singleton(createUidRange(PRIMARY_USER));
        mMockVpn.setVpnType(vpnType);
        mMockVpn.establish(new LinkProperties(), vpnOwnerUid, vpnRange);
        assertVpnUidRangesUpdated(true, vpnRange, vpnOwnerUid);

        final UnderlyingNetworkInfo underlyingNetworkInfo =
                new UnderlyingNetworkInfo(vpnOwnerUid, VPN_IFNAME, new ArrayList<String>());
        mMockVpn.setUnderlyingNetworkInfo(underlyingNetworkInfo);
        when(mDeps.getConnectionOwnerUid(anyInt(), any(), any())).thenReturn(42);
    }

    private void setupConnectionOwnerUidAsVpnApp(int vpnOwnerUid, @VpnManager.VpnType int vpnType)
            throws Exception {
        setupConnectionOwnerUid(vpnOwnerUid, vpnType);

        // Test as VPN app
        mServiceContext.setPermission(android.Manifest.permission.NETWORK_STACK, PERMISSION_DENIED);
        mServiceContext.setPermission(
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK, PERMISSION_DENIED);
    }

    private ConnectionInfo getTestConnectionInfo() throws Exception {
        return new ConnectionInfo(
                IPPROTO_TCP,
                new InetSocketAddress(InetAddresses.parseNumericAddress("1.2.3.4"), 1234),
                new InetSocketAddress(InetAddresses.parseNumericAddress("2.3.4.5"), 2345));
    }

    @Test
    public void testGetConnectionOwnerUidPlatformVpn() throws Exception {
        final int myUid = Process.myUid();
        setupConnectionOwnerUidAsVpnApp(myUid, VpnManager.TYPE_VPN_PLATFORM);

        assertEquals(INVALID_UID, mService.getConnectionOwnerUid(getTestConnectionInfo()));
    }

    @Test
    public void testGetConnectionOwnerUidVpnServiceWrongUser() throws Exception {
        final int myUid = Process.myUid();
        setupConnectionOwnerUidAsVpnApp(myUid + 1, VpnManager.TYPE_VPN_SERVICE);

        assertEquals(INVALID_UID, mService.getConnectionOwnerUid(getTestConnectionInfo()));
    }

    @Test
    public void testGetConnectionOwnerUidVpnServiceDoesNotThrow() throws Exception {
        final int myUid = Process.myUid();
        setupConnectionOwnerUidAsVpnApp(myUid, VpnManager.TYPE_VPN_SERVICE);

        assertEquals(42, mService.getConnectionOwnerUid(getTestConnectionInfo()));
    }

    @Test
    public void testGetConnectionOwnerUidVpnServiceNetworkStackDoesNotThrow() throws Exception {
        final int myUid = Process.myUid();
        setupConnectionOwnerUid(myUid, VpnManager.TYPE_VPN_SERVICE);
        mServiceContext.setPermission(
                android.Manifest.permission.NETWORK_STACK, PERMISSION_GRANTED);

        assertEquals(42, mService.getConnectionOwnerUid(getTestConnectionInfo()));
    }

    @Test
    public void testGetConnectionOwnerUidVpnServiceMainlineNetworkStackDoesNotThrow()
            throws Exception {
        final int myUid = Process.myUid();
        setupConnectionOwnerUid(myUid, VpnManager.TYPE_VPN_SERVICE);
        mServiceContext.setPermission(
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK, PERMISSION_GRANTED);

        assertEquals(42, mService.getConnectionOwnerUid(getTestConnectionInfo()));
    }

    private static PackageInfo buildPackageInfo(boolean hasSystemPermission, int uid) {
        final PackageInfo packageInfo = new PackageInfo();
        if (hasSystemPermission) {
            packageInfo.requestedPermissions = new String[] {
                    CHANGE_NETWORK_STATE, CONNECTIVITY_USE_RESTRICTED_NETWORKS };
            packageInfo.requestedPermissionsFlags = new int[] {
                    REQUESTED_PERMISSION_GRANTED, REQUESTED_PERMISSION_GRANTED };
        } else {
            packageInfo.requestedPermissions = new String[0];
        }
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.privateFlags = 0;
        packageInfo.applicationInfo.uid = UserHandle.getUid(UserHandle.USER_SYSTEM,
                UserHandle.getAppId(uid));
        return packageInfo;
    }

    @Test
    public void testRegisterConnectivityDiagnosticsCallbackInvalidRequest() throws Exception {
        final NetworkRequest request =
                new NetworkRequest(
                        new NetworkCapabilities(), TYPE_ETHERNET, 0, NetworkRequest.Type.NONE);
        try {
            mService.registerConnectivityDiagnosticsCallback(
                    mConnectivityDiagnosticsCallback, request, mContext.getPackageName());
            fail("registerConnectivityDiagnosticsCallback should throw on invalid NetworkRequest");
        } catch (IllegalArgumentException expected) {
        }
    }

    private void assertRouteInfoParcelMatches(RouteInfo route, RouteInfoParcel parcel) {
        assertEquals(route.getDestination().toString(), parcel.destination);
        assertEquals(route.getInterface(), parcel.ifName);
        assertEquals(route.getMtu(), parcel.mtu);

        switch (route.getType()) {
            case RouteInfo.RTN_UNICAST:
                if (route.hasGateway()) {
                    assertEquals(route.getGateway().getHostAddress(), parcel.nextHop);
                } else {
                    assertEquals(INetd.NEXTHOP_NONE, parcel.nextHop);
                }
                break;
            case RouteInfo.RTN_UNREACHABLE:
                assertEquals(INetd.NEXTHOP_UNREACHABLE, parcel.nextHop);
                break;
            case RouteInfo.RTN_THROW:
                assertEquals(INetd.NEXTHOP_THROW, parcel.nextHop);
                break;
            default:
                assertEquals(INetd.NEXTHOP_NONE, parcel.nextHop);
                break;
        }
    }

    private void assertRoutesAdded(int netId, RouteInfo... routes) throws Exception {
        ArgumentCaptor<RouteInfoParcel> captor = ArgumentCaptor.forClass(RouteInfoParcel.class);
        verify(mMockNetd, times(routes.length)).networkAddRouteParcel(eq(netId), captor.capture());
        for (int i = 0; i < routes.length; i++) {
            assertRouteInfoParcelMatches(routes[i], captor.getAllValues().get(i));
        }
    }

    private void assertRoutesRemoved(int netId, RouteInfo... routes) throws Exception {
        ArgumentCaptor<RouteInfoParcel> captor = ArgumentCaptor.forClass(RouteInfoParcel.class);
        verify(mMockNetd, times(routes.length)).networkRemoveRouteParcel(eq(netId),
                captor.capture());
        for (int i = 0; i < routes.length; i++) {
            assertRouteInfoParcelMatches(routes[i], captor.getAllValues().get(i));
        }
    }

    @Test
    public void testRegisterUnregisterConnectivityDiagnosticsCallback() throws Exception {
        final NetworkRequest wifiRequest =
                new NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI).build();
        when(mConnectivityDiagnosticsCallback.asBinder()).thenReturn(mIBinder);

        mService.registerConnectivityDiagnosticsCallback(
                mConnectivityDiagnosticsCallback, wifiRequest, mContext.getPackageName());

        // Block until all other events are done processing.
        HandlerUtils.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        verify(mIBinder).linkToDeath(any(ConnectivityDiagnosticsCallbackInfo.class), anyInt());
        verify(mConnectivityDiagnosticsCallback).asBinder();
        assertTrue(mService.mConnectivityDiagnosticsCallbacks.containsKey(mIBinder));

        mService.unregisterConnectivityDiagnosticsCallback(mConnectivityDiagnosticsCallback);
        verify(mIBinder, timeout(TIMEOUT_MS))
                .unlinkToDeath(any(ConnectivityDiagnosticsCallbackInfo.class), anyInt());
        assertFalse(mService.mConnectivityDiagnosticsCallbacks.containsKey(mIBinder));
        verify(mConnectivityDiagnosticsCallback, atLeastOnce()).asBinder();
    }

    @Test
    public void testRegisterDuplicateConnectivityDiagnosticsCallback() throws Exception {
        final NetworkRequest wifiRequest =
                new NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI).build();
        when(mConnectivityDiagnosticsCallback.asBinder()).thenReturn(mIBinder);

        mService.registerConnectivityDiagnosticsCallback(
                mConnectivityDiagnosticsCallback, wifiRequest, mContext.getPackageName());

        // Block until all other events are done processing.
        HandlerUtils.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        verify(mIBinder).linkToDeath(any(ConnectivityDiagnosticsCallbackInfo.class), anyInt());
        verify(mConnectivityDiagnosticsCallback).asBinder();
        assertTrue(mService.mConnectivityDiagnosticsCallbacks.containsKey(mIBinder));

        // Register the same callback again
        mService.registerConnectivityDiagnosticsCallback(
                mConnectivityDiagnosticsCallback, wifiRequest, mContext.getPackageName());

        // Block until all other events are done processing.
        HandlerUtils.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        assertTrue(mService.mConnectivityDiagnosticsCallbacks.containsKey(mIBinder));
    }

    public NetworkAgentInfo fakeMobileNai(NetworkCapabilities nc) {
        final NetworkInfo info = new NetworkInfo(TYPE_MOBILE, TelephonyManager.NETWORK_TYPE_LTE,
                ConnectivityManager.getNetworkTypeName(TYPE_MOBILE),
                TelephonyManager.getNetworkTypeName(TelephonyManager.NETWORK_TYPE_LTE));
        return new NetworkAgentInfo(null, new Network(NET_ID), info, new LinkProperties(),
                nc, 0, mServiceContext, null, new NetworkAgentConfig(), mService, null, null, 0,
                INVALID_UID, mQosCallbackTracker);
    }

    @Test
    public void testCheckConnectivityDiagnosticsPermissionsNetworkStack() throws Exception {
        final NetworkAgentInfo naiWithoutUid = fakeMobileNai(new NetworkCapabilities());

        mServiceContext.setPermission(
                android.Manifest.permission.NETWORK_STACK, PERMISSION_GRANTED);
        assertTrue(
                "NetworkStack permission not applied",
                mService.checkConnectivityDiagnosticsPermissions(
                        Process.myPid(), Process.myUid(), naiWithoutUid,
                        mContext.getOpPackageName()));
    }

    @Test
    public void testCheckConnectivityDiagnosticsPermissionsWrongUidPackageName() throws Exception {
        final NetworkAgentInfo naiWithoutUid = fakeMobileNai(new NetworkCapabilities());

        mServiceContext.setPermission(android.Manifest.permission.NETWORK_STACK, PERMISSION_DENIED);

        assertFalse(
                "Mismatched uid/package name should not pass the location permission check",
                mService.checkConnectivityDiagnosticsPermissions(
                        Process.myPid() + 1, Process.myUid() + 1, naiWithoutUid,
                        mContext.getOpPackageName()));
    }

    @Test
    public void testCheckConnectivityDiagnosticsPermissionsNoLocationPermission() throws Exception {
        final NetworkAgentInfo naiWithoutUid = fakeMobileNai(new NetworkCapabilities());

        mServiceContext.setPermission(android.Manifest.permission.NETWORK_STACK, PERMISSION_DENIED);

        assertFalse(
                "ACCESS_FINE_LOCATION permission necessary for Connectivity Diagnostics",
                mService.checkConnectivityDiagnosticsPermissions(
                        Process.myPid(), Process.myUid(), naiWithoutUid,
                        mContext.getOpPackageName()));
    }

    @Test
    public void testCheckConnectivityDiagnosticsPermissionsActiveVpn() throws Exception {
        final NetworkAgentInfo naiWithoutUid = fakeMobileNai(new NetworkCapabilities());

        mMockVpn.establishForMyUid();
        assertUidRangesUpdatedForMyUid(true);

        // Wait for networks to connect and broadcasts to be sent before removing permissions.
        waitForIdle();
        setupLocationPermissions(Build.VERSION_CODES.Q, true, AppOpsManager.OPSTR_FINE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);

        assertTrue(mMockVpn.setUnderlyingNetworks(new Network[] {naiWithoutUid.network}));
        waitForIdle();
        assertTrue(
                "Active VPN permission not applied",
                mService.checkConnectivityDiagnosticsPermissions(
                        Process.myPid(), Process.myUid(), naiWithoutUid,
                        mContext.getOpPackageName()));

        assertTrue(mMockVpn.setUnderlyingNetworks(null));
        waitForIdle();
        assertFalse(
                "VPN shouldn't receive callback on non-underlying network",
                mService.checkConnectivityDiagnosticsPermissions(
                        Process.myPid(), Process.myUid(), naiWithoutUid,
                        mContext.getOpPackageName()));
    }

    @Test
    public void testCheckConnectivityDiagnosticsPermissionsNetworkAdministrator() throws Exception {
        final NetworkCapabilities nc = new NetworkCapabilities();
        nc.setAdministratorUids(new int[] {Process.myUid()});
        final NetworkAgentInfo naiWithUid = fakeMobileNai(nc);

        setupLocationPermissions(Build.VERSION_CODES.Q, true, AppOpsManager.OPSTR_FINE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);
        mServiceContext.setPermission(android.Manifest.permission.NETWORK_STACK, PERMISSION_DENIED);

        assertTrue(
                "NetworkCapabilities administrator uid permission not applied",
                mService.checkConnectivityDiagnosticsPermissions(
                        Process.myPid(), Process.myUid(), naiWithUid, mContext.getOpPackageName()));
    }

    @Test
    public void testCheckConnectivityDiagnosticsPermissionsFails() throws Exception {
        final NetworkCapabilities nc = new NetworkCapabilities();
        nc.setOwnerUid(Process.myUid());
        nc.setAdministratorUids(new int[] {Process.myUid()});
        final NetworkAgentInfo naiWithUid = fakeMobileNai(nc);

        setupLocationPermissions(Build.VERSION_CODES.Q, true, AppOpsManager.OPSTR_FINE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);
        mServiceContext.setPermission(android.Manifest.permission.NETWORK_STACK, PERMISSION_DENIED);

        // Use wrong pid and uid
        assertFalse(
                "Permissions allowed when they shouldn't be granted",
                mService.checkConnectivityDiagnosticsPermissions(
                        Process.myPid() + 1, Process.myUid() + 1, naiWithUid,
                        mContext.getOpPackageName()));
    }

    @Test
    public void testRegisterConnectivityDiagnosticsCallbackCallsOnConnectivityReport()
            throws Exception {
        // Set up the Network, which leads to a ConnectivityReport being cached for the network.
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(callback);
        final LinkProperties linkProperties = new LinkProperties();
        linkProperties.setInterfaceName(INTERFACE_NAME);
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR, linkProperties);
        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        callback.assertNoCallback();

        final NetworkRequest request = new NetworkRequest.Builder().build();
        when(mConnectivityDiagnosticsCallback.asBinder()).thenReturn(mIBinder);

        mServiceContext.setPermission(
                android.Manifest.permission.NETWORK_STACK, PERMISSION_GRANTED);

        mService.registerConnectivityDiagnosticsCallback(
                mConnectivityDiagnosticsCallback, request, mContext.getPackageName());

        // Block until all other events are done processing.
        HandlerUtils.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        verify(mConnectivityDiagnosticsCallback)
                .onConnectivityReportAvailable(argThat(report -> {
                    return INTERFACE_NAME.equals(report.getLinkProperties().getInterfaceName())
                            && report.getNetworkCapabilities().hasTransport(TRANSPORT_CELLULAR);
                }));
    }

    private void setUpConnectivityDiagnosticsCallback() throws Exception {
        final NetworkRequest request = new NetworkRequest.Builder().build();
        when(mConnectivityDiagnosticsCallback.asBinder()).thenReturn(mIBinder);

        mServiceContext.setPermission(
                android.Manifest.permission.NETWORK_STACK, PERMISSION_GRANTED);

        mService.registerConnectivityDiagnosticsCallback(
                mConnectivityDiagnosticsCallback, request, mContext.getPackageName());

        // Block until all other events are done processing.
        HandlerUtils.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        // Connect the cell agent verify that it notifies TestNetworkCallback that it is available
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(callback);
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        callback.assertNoCallback();
    }

    @Test
    public void testConnectivityDiagnosticsCallbackOnConnectivityReportAvailable()
            throws Exception {
        setUpConnectivityDiagnosticsCallback();

        // Block until all other events are done processing.
        HandlerUtils.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        // Verify onConnectivityReport fired
        verify(mConnectivityDiagnosticsCallback).onConnectivityReportAvailable(
                argThat(report -> {
                    final NetworkCapabilities nc = report.getNetworkCapabilities();
                    return nc.getUids() == null
                            && nc.getAdministratorUids().length == 0
                            && nc.getOwnerUid() == Process.INVALID_UID;
                }));
    }

    @Test
    public void testConnectivityDiagnosticsCallbackOnDataStallSuspected() throws Exception {
        setUpConnectivityDiagnosticsCallback();

        // Trigger notifyDataStallSuspected() on the INetworkMonitorCallbacks instance in the
        // cellular network agent
        mCellNetworkAgent.notifyDataStallSuspected();

        // Block until all other events are done processing.
        HandlerUtils.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        // Verify onDataStallSuspected fired
        verify(mConnectivityDiagnosticsCallback).onDataStallSuspected(
                argThat(report -> {
                    final NetworkCapabilities nc = report.getNetworkCapabilities();
                    return nc.getUids() == null
                            && nc.getAdministratorUids().length == 0
                            && nc.getOwnerUid() == Process.INVALID_UID;
                }));
    }

    @Test
    public void testConnectivityDiagnosticsCallbackOnConnectivityReported() throws Exception {
        setUpConnectivityDiagnosticsCallback();

        final Network n = mCellNetworkAgent.getNetwork();
        final boolean hasConnectivity = true;
        mService.reportNetworkConnectivity(n, hasConnectivity);

        // Block until all other events are done processing.
        HandlerUtils.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        // Verify onNetworkConnectivityReported fired
        verify(mConnectivityDiagnosticsCallback)
                .onNetworkConnectivityReported(eq(n), eq(hasConnectivity));

        final boolean noConnectivity = false;
        mService.reportNetworkConnectivity(n, noConnectivity);

        // Block until all other events are done processing.
        HandlerUtils.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        // Wait for onNetworkConnectivityReported to fire
        verify(mConnectivityDiagnosticsCallback)
                .onNetworkConnectivityReported(eq(n), eq(noConnectivity));
    }

    @Test
    public void testRouteAddDeleteUpdate() throws Exception {
        final NetworkRequest request = new NetworkRequest.Builder().build();
        final TestNetworkCallback networkCallback = new TestNetworkCallback();
        mCm.registerNetworkCallback(request, networkCallback);
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        reset(mMockNetd);
        mCellNetworkAgent.connect(false);
        networkCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        final int netId = mCellNetworkAgent.getNetwork().netId;

        final String iface = "rmnet_data0";
        final InetAddress gateway = InetAddress.getByName("fe80::5678");
        RouteInfo direct = RouteInfo.makeHostRoute(gateway, iface);
        RouteInfo rio1 = new RouteInfo(new IpPrefix("2001:db8:1::/48"), gateway, iface);
        RouteInfo rio2 = new RouteInfo(new IpPrefix("2001:db8:2::/48"), gateway, iface);
        RouteInfo defaultRoute = new RouteInfo((IpPrefix) null, gateway, iface);
        RouteInfo defaultWithMtu = new RouteInfo(null, gateway, iface, RouteInfo.RTN_UNICAST,
                                                 1280 /* mtu */);

        // Send LinkProperties and check that we ask netd to add routes.
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(iface);
        lp.addRoute(direct);
        lp.addRoute(rio1);
        lp.addRoute(defaultRoute);
        mCellNetworkAgent.sendLinkProperties(lp);
        networkCallback.expectLinkPropertiesThat(mCellNetworkAgent, x -> x.getRoutes().size() == 3);

        assertRoutesAdded(netId, direct, rio1, defaultRoute);
        reset(mMockNetd);

        // Send updated LinkProperties and check that we ask netd to add, remove, update routes.
        assertTrue(lp.getRoutes().contains(defaultRoute));
        lp.removeRoute(rio1);
        lp.addRoute(rio2);
        lp.addRoute(defaultWithMtu);
        // Ensure adding the same route with a different MTU replaces the previous route.
        assertFalse(lp.getRoutes().contains(defaultRoute));
        assertTrue(lp.getRoutes().contains(defaultWithMtu));

        mCellNetworkAgent.sendLinkProperties(lp);
        networkCallback.expectLinkPropertiesThat(mCellNetworkAgent,
                x -> x.getRoutes().contains(rio2));

        assertRoutesRemoved(netId, rio1);
        assertRoutesAdded(netId, rio2);

        ArgumentCaptor<RouteInfoParcel> captor = ArgumentCaptor.forClass(RouteInfoParcel.class);
        verify(mMockNetd).networkUpdateRouteParcel(eq(netId), captor.capture());
        assertRouteInfoParcelMatches(defaultWithMtu, captor.getValue());


        mCm.unregisterNetworkCallback(networkCallback);
    }

    @Test
    public void testDumpDoesNotCrash() {
        // Filing a couple requests prior to testing the dump.
        final TestNetworkCallback genericNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback wifiNetworkCallback = new TestNetworkCallback();
        final NetworkRequest genericRequest = new NetworkRequest.Builder()
                .clearCapabilities().build();
        final NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI).build();
        mCm.registerNetworkCallback(genericRequest, genericNetworkCallback);
        mCm.registerNetworkCallback(wifiRequest, wifiNetworkCallback);
        final StringWriter stringWriter = new StringWriter();

        mService.dump(new FileDescriptor(), new PrintWriter(stringWriter), new String[0]);

        assertFalse(stringWriter.toString().isEmpty());
    }

    @Test
    public void testRequestsSortedByIdSortsCorrectly() {
        final TestNetworkCallback genericNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback wifiNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest genericRequest = new NetworkRequest.Builder()
                .clearCapabilities().build();
        final NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI).build();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR).build();
        mCm.registerNetworkCallback(genericRequest, genericNetworkCallback);
        mCm.registerNetworkCallback(wifiRequest, wifiNetworkCallback);
        mCm.registerNetworkCallback(cellRequest, cellNetworkCallback);
        waitForIdle();

        final ConnectivityService.NetworkRequestInfo[] nriOutput = mService.requestsSortedById();

        assertTrue(nriOutput.length > 1);
        for (int i = 0; i < nriOutput.length - 1; i++) {
            final boolean isRequestIdInOrder =
                    nriOutput[i].mRequests.get(0).requestId
                            < nriOutput[i + 1].mRequests.get(0).requestId;
            assertTrue(isRequestIdInOrder);
        }
    }

    private void assertUidRangesUpdatedForMyUid(boolean add) throws Exception {
        final int uid = Process.myUid();
        assertVpnUidRangesUpdated(add, uidRangesForUid(uid), uid);
    }

    private void assertVpnUidRangesUpdated(boolean add, Set<UidRange> vpnRanges, int exemptUid)
            throws Exception {
        InOrder inOrder = inOrder(mMockNetd);
        ArgumentCaptor<int[]> exemptUidCaptor = ArgumentCaptor.forClass(int[].class);

        inOrder.verify(mMockNetd, times(1)).socketDestroy(eq(toUidRangeStableParcels(vpnRanges)),
                exemptUidCaptor.capture());
        assertContainsExactly(exemptUidCaptor.getValue(), Process.VPN_UID, exemptUid);

        if (add) {
            inOrder.verify(mMockNetd, times(1)).networkAddUidRanges(eq(mMockVpn.getNetId()),
                    eq(toUidRangeStableParcels(vpnRanges)));
        } else {
            inOrder.verify(mMockNetd, times(1)).networkRemoveUidRanges(eq(mMockVpn.getNetId()),
                    eq(toUidRangeStableParcels(vpnRanges)));
        }

        inOrder.verify(mMockNetd, times(1)).socketDestroy(eq(toUidRangeStableParcels(vpnRanges)),
                exemptUidCaptor.capture());
        assertContainsExactly(exemptUidCaptor.getValue(), Process.VPN_UID, exemptUid);
    }

    @Test
    public void testVpnUidRangesUpdate() throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("tun0");
        lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), null));
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), null));
        final UidRange vpnRange = createUidRange(PRIMARY_USER);
        Set<UidRange> vpnRanges = Collections.singleton(vpnRange);
        mMockVpn.establish(lp, VPN_UID, vpnRanges);
        assertVpnUidRangesUpdated(true, vpnRanges, VPN_UID);

        reset(mMockNetd);
        // Update to new range which is old range minus APP1, i.e. only APP2
        final Set<UidRange> newRanges = new HashSet<>(Arrays.asList(
                new UidRange(vpnRange.start, APP1_UID - 1),
                new UidRange(APP1_UID + 1, vpnRange.stop)));
        mMockVpn.setUids(newRanges);
        waitForIdle();

        assertVpnUidRangesUpdated(true, newRanges, VPN_UID);
        assertVpnUidRangesUpdated(false, vpnRanges, VPN_UID);
    }

    @Test
    public void testInvalidRequestTypes() {
        final int[] invalidReqTypeInts = new int[]{-1, NetworkRequest.Type.NONE.ordinal(),
                NetworkRequest.Type.LISTEN.ordinal(), NetworkRequest.Type.values().length};
        final NetworkCapabilities nc = new NetworkCapabilities().addTransportType(TRANSPORT_WIFI);

        for (int reqTypeInt : invalidReqTypeInts) {
            assertThrows("Expect throws for invalid request type " + reqTypeInt,
                    IllegalArgumentException.class,
                    () -> mService.requestNetwork(nc, reqTypeInt, null, 0, null,
                            ConnectivityManager.TYPE_NONE, mContext.getPackageName(),
                            getAttributionTag())
            );
        }
    }

    private class QosCallbackMockHelper {
        @NonNull public final QosFilter mFilter;
        @NonNull public final IQosCallback mCallback;
        @NonNull public final TestNetworkAgentWrapper mAgentWrapper;
        @NonNull private final List<IQosCallback> mCallbacks = new ArrayList();

        QosCallbackMockHelper() throws Exception {
            Log.d(TAG, "QosCallbackMockHelper: ");
            mFilter = mock(QosFilter.class);

            // Ensure the network is disconnected before anything else occurs
            assertNull(mCellNetworkAgent);

            mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
            mCellNetworkAgent.connect(true);

            verifyActiveNetwork(TRANSPORT_CELLULAR);
            waitForIdle();
            final Network network = mCellNetworkAgent.getNetwork();

            final Pair<IQosCallback, IBinder> pair = createQosCallback();
            mCallback = pair.first;

            when(mFilter.getNetwork()).thenReturn(network);
            when(mFilter.validate()).thenReturn(QosCallbackException.EX_TYPE_FILTER_NONE);
            mAgentWrapper = mCellNetworkAgent;
        }

        void registerQosCallback(@NonNull final QosFilter filter,
                @NonNull final IQosCallback callback) {
            mCallbacks.add(callback);
            final NetworkAgentInfo nai =
                    mService.getNetworkAgentInfoForNetwork(filter.getNetwork());
            mService.registerQosCallbackInternal(filter, callback, nai);
        }

        void tearDown() {
            for (int i = 0; i < mCallbacks.size(); i++) {
                mService.unregisterQosCallback(mCallbacks.get(i));
            }
        }
    }

    private Pair<IQosCallback, IBinder> createQosCallback() {
        final IQosCallback callback = mock(IQosCallback.class);
        final IBinder binder = mock(Binder.class);
        when(callback.asBinder()).thenReturn(binder);
        when(binder.isBinderAlive()).thenReturn(true);
        return new Pair<>(callback, binder);
    }


    @Test
    public void testQosCallbackRegistration() throws Exception {
        mQosCallbackMockHelper = new QosCallbackMockHelper();
        final NetworkAgentWrapper wrapper = mQosCallbackMockHelper.mAgentWrapper;

        when(mQosCallbackMockHelper.mFilter.validate())
                .thenReturn(QosCallbackException.EX_TYPE_FILTER_NONE);
        mQosCallbackMockHelper.registerQosCallback(
                mQosCallbackMockHelper.mFilter, mQosCallbackMockHelper.mCallback);

        final NetworkAgentWrapper.CallbackType.OnQosCallbackRegister cbRegister1 =
                (NetworkAgentWrapper.CallbackType.OnQosCallbackRegister)
                        wrapper.getCallbackHistory().poll(1000, x -> true);
        assertNotNull(cbRegister1);

        final int registerCallbackId = cbRegister1.mQosCallbackId;
        mService.unregisterQosCallback(mQosCallbackMockHelper.mCallback);
        final NetworkAgentWrapper.CallbackType.OnQosCallbackUnregister cbUnregister;
        cbUnregister = (NetworkAgentWrapper.CallbackType.OnQosCallbackUnregister)
                wrapper.getCallbackHistory().poll(1000, x -> true);
        assertNotNull(cbUnregister);
        assertEquals(registerCallbackId, cbUnregister.mQosCallbackId);
        assertNull(wrapper.getCallbackHistory().poll(200, x -> true));
    }

    @Test
    public void testQosCallbackNoRegistrationOnValidationError() throws Exception {
        mQosCallbackMockHelper = new QosCallbackMockHelper();

        when(mQosCallbackMockHelper.mFilter.validate())
                .thenReturn(QosCallbackException.EX_TYPE_FILTER_NETWORK_RELEASED);
        mQosCallbackMockHelper.registerQosCallback(
                mQosCallbackMockHelper.mFilter, mQosCallbackMockHelper.mCallback);
        waitForIdle();
        verify(mQosCallbackMockHelper.mCallback)
                .onError(eq(QosCallbackException.EX_TYPE_FILTER_NETWORK_RELEASED));
    }

    @Test
    public void testQosCallbackAvailableAndLost() throws Exception {
        mQosCallbackMockHelper = new QosCallbackMockHelper();
        final int sessionId = 10;
        final int qosCallbackId = 1;

        when(mQosCallbackMockHelper.mFilter.validate())
                .thenReturn(QosCallbackException.EX_TYPE_FILTER_NONE);
        mQosCallbackMockHelper.registerQosCallback(
                mQosCallbackMockHelper.mFilter, mQosCallbackMockHelper.mCallback);
        waitForIdle();

        final EpsBearerQosSessionAttributes attributes = new EpsBearerQosSessionAttributes(
                1, 2, 3, 4, 5, new ArrayList<>());
        mQosCallbackMockHelper.mAgentWrapper.getNetworkAgent()
                .sendQosSessionAvailable(qosCallbackId, sessionId, attributes);
        waitForIdle();

        verify(mQosCallbackMockHelper.mCallback).onQosEpsBearerSessionAvailable(argThat(session ->
                session.getSessionId() == sessionId
                        && session.getSessionType() == QosSession.TYPE_EPS_BEARER), eq(attributes));

        mQosCallbackMockHelper.mAgentWrapper.getNetworkAgent()
                .sendQosSessionLost(qosCallbackId, sessionId);
        waitForIdle();
        verify(mQosCallbackMockHelper.mCallback).onQosSessionLost(argThat(session ->
                session.getSessionId() == sessionId
                        && session.getSessionType() == QosSession.TYPE_EPS_BEARER));
    }

    @Test
    public void testQosCallbackTooManyRequests() throws Exception {
        mQosCallbackMockHelper = new QosCallbackMockHelper();

        when(mQosCallbackMockHelper.mFilter.validate())
                .thenReturn(QosCallbackException.EX_TYPE_FILTER_NONE);
        for (int i = 0; i < 100; i++) {
            final Pair<IQosCallback, IBinder> pair = createQosCallback();

            try {
                mQosCallbackMockHelper.registerQosCallback(
                        mQosCallbackMockHelper.mFilter, pair.first);
            } catch (ServiceSpecificException e) {
                assertEquals(e.errorCode, ConnectivityManager.Errors.TOO_MANY_REQUESTS);
                if (i < 50) {
                    fail("TOO_MANY_REQUESTS thrown too early, the count is " + i);
                }

                // As long as there is at least 50 requests, it is safe to assume it works.
                // Note: The count isn't being tested precisely against 100 because the counter
                // is shared with request network.
                return;
            }
        }
        fail("TOO_MANY_REQUESTS never thrown");
    }

    private void mockGetApplicationInfo(@NonNull final String packageName, @NonNull final int uid)
            throws Exception {
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = uid;
        when(mPackageManager.getApplicationInfo(eq(packageName), anyInt()))
                .thenReturn(applicationInfo);
    }

    private void mockHasSystemFeature(@NonNull final String featureName,
            @NonNull final boolean hasFeature) {
        when(mPackageManager.hasSystemFeature(eq(featureName)))
                .thenReturn(hasFeature);
    }

    private UidRange getNriFirstUidRange(
            @NonNull final ConnectivityService.NetworkRequestInfo nri) {
        return nri.mRequests.get(0).networkCapabilities.getUids().iterator().next();
    }

    private OemNetworkPreferences createDefaultOemNetworkPreferences(
            @OemNetworkPreferences.OemNetworkPreference final int preference)
            throws Exception {
        // Arrange PackageManager mocks
        mockGetApplicationInfo(TEST_PACKAGE_NAME, TEST_PACKAGE_UID);

        // Build OemNetworkPreferences object
        return new OemNetworkPreferences.Builder()
                .addNetworkPreference(TEST_PACKAGE_NAME, preference)
                .build();
    }

    @Test
    public void testOemNetworkRequestFactoryPreferenceUninitializedThrowsError()
            throws PackageManager.NameNotFoundException {
        @OemNetworkPreferences.OemNetworkPreference final int prefToTest =
                OEM_NETWORK_PREFERENCE_UNINITIALIZED;

        // Act on OemNetworkRequestFactory.createNrisFromOemNetworkPreferences()
        assertThrows(IllegalArgumentException.class,
                () -> mService.new OemNetworkRequestFactory()
                        .createNrisFromOemNetworkPreferences(
                                createDefaultOemNetworkPreferences(prefToTest)));
    }

    @Test
    public void testOemNetworkRequestFactoryPreferenceOemPaid()
            throws Exception {
        // Expectations
        final int expectedNumOfNris = 1;
        final int expectedNumOfRequests = 3;

        @OemNetworkPreferences.OemNetworkPreference final int prefToTest =
                OEM_NETWORK_PREFERENCE_OEM_PAID;

        // Act on OemNetworkRequestFactory.createNrisFromOemNetworkPreferences()
        final ArraySet<ConnectivityService.NetworkRequestInfo> nris =
                mService.new OemNetworkRequestFactory()
                        .createNrisFromOemNetworkPreferences(
                                createDefaultOemNetworkPreferences(prefToTest));

        final List<NetworkRequest> mRequests = nris.iterator().next().mRequests;
        assertEquals(expectedNumOfNris, nris.size());
        assertEquals(expectedNumOfRequests, mRequests.size());
        assertTrue(mRequests.get(0).isListen());
        assertTrue(mRequests.get(0).hasCapability(NET_CAPABILITY_NOT_METERED));
        assertTrue(mRequests.get(0).hasCapability(NET_CAPABILITY_VALIDATED));
        assertTrue(mRequests.get(1).isRequest());
        assertTrue(mRequests.get(1).hasCapability(NET_CAPABILITY_OEM_PAID));
        assertTrue(mRequests.get(2).isRequest());
        assertTrue(mService.getDefaultRequest().networkCapabilities.equalsNetCapabilities(
                mRequests.get(2).networkCapabilities));
    }

    @Test
    public void testOemNetworkRequestFactoryPreferenceOemPaidNoFallback()
            throws Exception {
        // Expectations
        final int expectedNumOfNris = 1;
        final int expectedNumOfRequests = 2;

        @OemNetworkPreferences.OemNetworkPreference final int prefToTest =
                OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK;

        // Act on OemNetworkRequestFactory.createNrisFromOemNetworkPreferences()
        final ArraySet<ConnectivityService.NetworkRequestInfo> nris =
                mService.new OemNetworkRequestFactory()
                        .createNrisFromOemNetworkPreferences(
                                createDefaultOemNetworkPreferences(prefToTest));

        final List<NetworkRequest> mRequests = nris.iterator().next().mRequests;
        assertEquals(expectedNumOfNris, nris.size());
        assertEquals(expectedNumOfRequests, mRequests.size());
        assertTrue(mRequests.get(0).isListen());
        assertTrue(mRequests.get(0).hasCapability(NET_CAPABILITY_NOT_METERED));
        assertTrue(mRequests.get(0).hasCapability(NET_CAPABILITY_VALIDATED));
        assertTrue(mRequests.get(1).isRequest());
        assertTrue(mRequests.get(1).hasCapability(NET_CAPABILITY_OEM_PAID));
    }

    @Test
    public void testOemNetworkRequestFactoryPreferenceOemPaidOnly()
            throws Exception {
        // Expectations
        final int expectedNumOfNris = 1;
        final int expectedNumOfRequests = 1;

        @OemNetworkPreferences.OemNetworkPreference final int prefToTest =
                OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY;

        // Act on OemNetworkRequestFactory.createNrisFromOemNetworkPreferences()
        final ArraySet<ConnectivityService.NetworkRequestInfo> nris =
                mService.new OemNetworkRequestFactory()
                        .createNrisFromOemNetworkPreferences(
                                createDefaultOemNetworkPreferences(prefToTest));

        final List<NetworkRequest> mRequests = nris.iterator().next().mRequests;
        assertEquals(expectedNumOfNris, nris.size());
        assertEquals(expectedNumOfRequests, mRequests.size());
        assertTrue(mRequests.get(0).isRequest());
        assertTrue(mRequests.get(0).hasCapability(NET_CAPABILITY_OEM_PAID));
    }

    @Test
    public void testOemNetworkRequestFactoryPreferenceOemPrivateOnly()
            throws Exception {
        // Expectations
        final int expectedNumOfNris = 1;
        final int expectedNumOfRequests = 1;

        @OemNetworkPreferences.OemNetworkPreference final int prefToTest =
                OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY;

        // Act on OemNetworkRequestFactory.createNrisFromOemNetworkPreferences()
        final ArraySet<ConnectivityService.NetworkRequestInfo> nris =
                mService.new OemNetworkRequestFactory()
                        .createNrisFromOemNetworkPreferences(
                                createDefaultOemNetworkPreferences(prefToTest));

        final List<NetworkRequest> mRequests = nris.iterator().next().mRequests;
        assertEquals(expectedNumOfNris, nris.size());
        assertEquals(expectedNumOfRequests, mRequests.size());
        assertTrue(mRequests.get(0).isRequest());
        assertTrue(mRequests.get(0).hasCapability(NET_CAPABILITY_OEM_PRIVATE));
        assertFalse(mRequests.get(0).hasCapability(NET_CAPABILITY_OEM_PAID));
    }

    @Test
    public void testOemNetworkRequestFactoryCreatesCorrectNumOfNris()
            throws Exception {
        // Expectations
        final int expectedNumOfNris = 2;

        // Arrange PackageManager mocks
        final String testPackageName2 = "com.google.apps.dialer";
        mockGetApplicationInfo(TEST_PACKAGE_NAME, TEST_PACKAGE_UID);
        mockGetApplicationInfo(testPackageName2, TEST_PACKAGE_UID);

        // Build OemNetworkPreferences object
        final int testOemPref = OEM_NETWORK_PREFERENCE_OEM_PAID;
        final int testOemPref2 = OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK;
        final OemNetworkPreferences pref = new OemNetworkPreferences.Builder()
                .addNetworkPreference(TEST_PACKAGE_NAME, testOemPref)
                .addNetworkPreference(testPackageName2, testOemPref2)
                .build();

        // Act on OemNetworkRequestFactory.createNrisFromOemNetworkPreferences()
        final ArraySet<ConnectivityService.NetworkRequestInfo> nris =
                mService.new OemNetworkRequestFactory().createNrisFromOemNetworkPreferences(pref);

        assertNotNull(nris);
        assertEquals(expectedNumOfNris, nris.size());
    }

    @Test
    public void testOemNetworkRequestFactoryCorrectlySetsUids()
            throws Exception {
        // Arrange PackageManager mocks
        final String testPackageName2 = "com.google.apps.dialer";
        final int testPackageNameUid2 = 456;
        mockGetApplicationInfo(TEST_PACKAGE_NAME, TEST_PACKAGE_UID);
        mockGetApplicationInfo(testPackageName2, testPackageNameUid2);

        // Build OemNetworkPreferences object
        final int testOemPref = OEM_NETWORK_PREFERENCE_OEM_PAID;
        final int testOemPref2 = OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK;
        final OemNetworkPreferences pref = new OemNetworkPreferences.Builder()
                .addNetworkPreference(TEST_PACKAGE_NAME, testOemPref)
                .addNetworkPreference(testPackageName2, testOemPref2)
                .build();

        // Act on OemNetworkRequestFactory.createNrisFromOemNetworkPreferences()
        final List<ConnectivityService.NetworkRequestInfo> nris =
                new ArrayList<>(
                        mService.new OemNetworkRequestFactory().createNrisFromOemNetworkPreferences(
                                pref));

        // Sort by uid to access nris by index
        nris.sort(Comparator.comparingInt(nri -> getNriFirstUidRange(nri).start));
        assertEquals(TEST_PACKAGE_UID, getNriFirstUidRange(nris.get(0)).start);
        assertEquals(TEST_PACKAGE_UID, getNriFirstUidRange(nris.get(0)).stop);
        assertEquals(testPackageNameUid2, getNriFirstUidRange(nris.get(1)).start);
        assertEquals(testPackageNameUid2, getNriFirstUidRange(nris.get(1)).stop);
    }

    @Test
    public void testOemNetworkRequestFactoryAddsPackagesToCorrectPreference()
            throws Exception {
        // Expectations
        final int expectedNumOfNris = 1;
        final int expectedNumOfAppUids = 2;

        // Arrange PackageManager mocks
        final String testPackageName2 = "com.google.apps.dialer";
        final int testPackageNameUid2 = 456;
        mockGetApplicationInfo(TEST_PACKAGE_NAME, TEST_PACKAGE_UID);
        mockGetApplicationInfo(testPackageName2, testPackageNameUid2);

        // Build OemNetworkPreferences object
        final int testOemPref = OEM_NETWORK_PREFERENCE_OEM_PAID;
        final OemNetworkPreferences pref = new OemNetworkPreferences.Builder()
                .addNetworkPreference(TEST_PACKAGE_NAME, testOemPref)
                .addNetworkPreference(testPackageName2, testOemPref)
                .build();

        // Act on OemNetworkRequestFactory.createNrisFromOemNetworkPreferences()
        final ArraySet<ConnectivityService.NetworkRequestInfo> nris =
                mService.new OemNetworkRequestFactory().createNrisFromOemNetworkPreferences(pref);

        assertEquals(expectedNumOfNris, nris.size());
        assertEquals(expectedNumOfAppUids,
                nris.iterator().next().mRequests.get(0).networkCapabilities.getUids().size());
    }

    @Test
    public void testSetOemNetworkPreferenceNullListenerAndPrefParamThrowsNpe() {
        mockHasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, true);

        // Act on ConnectivityService.setOemNetworkPreference()
        assertThrows(NullPointerException.class,
                () -> mService.setOemNetworkPreference(
                        null,
                        null));
    }

    @Test
    public void testSetOemNetworkPreferenceFailsForNonAutomotive()
            throws Exception {
        mockHasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, false);
        @OemNetworkPreferences.OemNetworkPreference final int networkPref =
                OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY;

        // Act on ConnectivityService.setOemNetworkPreference()
        assertThrows(UnsupportedOperationException.class,
                () -> mService.setOemNetworkPreference(
                        createDefaultOemNetworkPreferences(networkPref),
                        new TestOemListenerCallback()));
    }

    private void setOemNetworkPreferenceAgentConnected(final int transportType,
            final boolean connectAgent) throws Exception {
        switch(transportType) {
            // Corresponds to a metered cellular network. Will be used for the default network.
            case TRANSPORT_CELLULAR:
                if (!connectAgent) {
                    mCellNetworkAgent.disconnect();
                    break;
                }
                mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
                mCellNetworkAgent.removeCapability(NET_CAPABILITY_NOT_METERED);
                mCellNetworkAgent.connect(true);
                break;
            // Corresponds to a restricted ethernet network with OEM_PAID/OEM_PRIVATE.
            case TRANSPORT_ETHERNET:
                if (!connectAgent) {
                    stopOemManagedNetwork();
                    break;
                }
                startOemManagedNetwork(true);
                break;
            // Corresponds to unmetered Wi-Fi.
            case TRANSPORT_WIFI:
                if (!connectAgent) {
                    mWiFiNetworkAgent.disconnect();
                    break;
                }
                mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
                mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
                mWiFiNetworkAgent.connect(true);
                break;
            default:
                throw new AssertionError("Unsupported transport type passed in.");

        }
        waitForIdle();
    }

    private void startOemManagedNetwork(final boolean isOemPaid) throws Exception {
        mEthernetNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_ETHERNET);
        mEthernetNetworkAgent.addCapability(
                isOemPaid ? NET_CAPABILITY_OEM_PAID : NET_CAPABILITY_OEM_PRIVATE);
        mEthernetNetworkAgent.removeCapability(NET_CAPABILITY_NOT_RESTRICTED);
        mEthernetNetworkAgent.connect(true);
    }

    private void stopOemManagedNetwork() {
        mEthernetNetworkAgent.disconnect();
        waitForIdle();
    }

    private void verifyMultipleDefaultNetworksTracksCorrectly(
            final int expectedOemRequestsSize,
            @NonNull final Network expectedDefaultNetwork,
            @NonNull final Network expectedPerAppNetwork) {
        // The current test setup assumes two tracked default network requests; one for the default
        // network and the other for the OEM network preference being tested. This will be validated
        // each time to confirm it doesn't change under test.
        final int expectedDefaultNetworkRequestsSize = 2;
        assertEquals(expectedDefaultNetworkRequestsSize, mService.mDefaultNetworkRequests.size());
        for (final ConnectivityService.NetworkRequestInfo defaultRequest
                : mService.mDefaultNetworkRequests) {
            final Network defaultNetwork = defaultRequest.getSatisfier() == null
                    ? null : defaultRequest.getSatisfier().network();
            // If this is the default request.
            if (defaultRequest == mService.mDefaultRequest) {
                assertEquals(
                        expectedDefaultNetwork,
                        defaultNetwork);
                // Make sure this value doesn't change.
                assertEquals(1, defaultRequest.mRequests.size());
                continue;
            }
            assertEquals(expectedPerAppNetwork, defaultNetwork);
            assertEquals(expectedOemRequestsSize, defaultRequest.mRequests.size());
        }
    }

    private void setupMultipleDefaultNetworksForOemNetworkPreferenceNotCurrentUidTest(
            @OemNetworkPreferences.OemNetworkPreference final int networkPrefToSetup)
            throws Exception {
        final int testPackageNameUid = 123;
        final String testPackageName = "per.app.defaults.package";
        setupMultipleDefaultNetworksForOemNetworkPreferenceTest(
                networkPrefToSetup, testPackageNameUid, testPackageName);
    }

    private void setupMultipleDefaultNetworksForOemNetworkPreferenceCurrentUidTest(
            @OemNetworkPreferences.OemNetworkPreference final int networkPrefToSetup)
            throws Exception {
        final int testPackageNameUid = Process.myUid();
        final String testPackageName = "per.app.defaults.package";
        setupMultipleDefaultNetworksForOemNetworkPreferenceTest(
                networkPrefToSetup, testPackageNameUid, testPackageName);
    }

    private void setupMultipleDefaultNetworksForOemNetworkPreferenceTest(
            @OemNetworkPreferences.OemNetworkPreference final int networkPrefToSetup,
            final int testPackageUid, @NonNull final String testPackageName) throws Exception {
        // Only the default request should be included at start.
        assertEquals(1, mService.mDefaultNetworkRequests.size());

        final UidRangeParcel[] uidRanges =
                toUidRangeStableParcels(uidRangesForUid(testPackageUid));
        setupSetOemNetworkPreferenceForPreferenceTest(
                networkPrefToSetup, uidRanges, testPackageName);
    }

    private void setupSetOemNetworkPreferenceForPreferenceTest(
            @OemNetworkPreferences.OemNetworkPreference final int networkPrefToSetup,
            @NonNull final UidRangeParcel[] uidRanges,
            @NonNull final String testPackageName)
            throws Exception {
        mockHasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, true);

        // These tests work off a single UID therefore using 'start' is valid.
        mockGetApplicationInfo(testPackageName, uidRanges[0].start);

        // Build OemNetworkPreferences object
        final OemNetworkPreferences pref = new OemNetworkPreferences.Builder()
                .addNetworkPreference(testPackageName, networkPrefToSetup)
                .build();

        // Act on ConnectivityService.setOemNetworkPreference()
        final TestOemListenerCallback mOnSetOemNetworkPreferenceTestListener =
                new TestOemListenerCallback();
        mService.setOemNetworkPreference(pref, mOnSetOemNetworkPreferenceTestListener);

        // Verify call returned successfully
        mOnSetOemNetworkPreferenceTestListener.expectOnComplete();
    }

    private static class TestOemListenerCallback implements IOnSetOemNetworkPreferenceListener {
        final CompletableFuture<Object> mDone = new CompletableFuture<>();

        @Override
        public void onComplete() {
            mDone.complete(new Object());
        }

        void expectOnComplete() throws Exception {
            try {
                mDone.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                fail("Expected onComplete() not received after " + TIMEOUT_MS + " ms");
            }
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }

    @Test
    public void testMultiDefaultGetActiveNetworkIsCorrect() throws Exception {
        @OemNetworkPreferences.OemNetworkPreference final int networkPref =
                OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY;
        final int expectedOemPrefRequestSize = 1;

        // Setup the test process to use networkPref for their default network.
        setupMultipleDefaultNetworksForOemNetworkPreferenceCurrentUidTest(networkPref);

        // Bring up ethernet with OEM_PAID. This will satisfy NET_CAPABILITY_OEM_PAID.
        // The active network for the default should be null at this point as this is a retricted
        // network.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_ETHERNET, true);
        verifyMultipleDefaultNetworksTracksCorrectly(expectedOemPrefRequestSize,
                null,
                mEthernetNetworkAgent.getNetwork());

        // Verify that the active network is correct
        verifyActiveNetwork(TRANSPORT_ETHERNET);
    }

    @Test
    public void testMultiDefaultIsActiveNetworkMeteredIsCorrect() throws Exception {
        @OemNetworkPreferences.OemNetworkPreference final int networkPref =
                OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY;
        final int expectedOemPrefRequestSize = 1;

        // Setup the test process to use networkPref for their default network.
        setupMultipleDefaultNetworksForOemNetworkPreferenceCurrentUidTest(networkPref);

        // Returns true by default when no network is available.
        assertTrue(mCm.isActiveNetworkMetered());

        // Connect to an unmetered restricted network that will only be available to the OEM pref.
        mEthernetNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_ETHERNET);
        mEthernetNetworkAgent.addCapability(NET_CAPABILITY_OEM_PAID);
        mEthernetNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mEthernetNetworkAgent.removeCapability(NET_CAPABILITY_NOT_RESTRICTED);
        mEthernetNetworkAgent.connect(true);
        waitForIdle();

        verifyMultipleDefaultNetworksTracksCorrectly(expectedOemPrefRequestSize,
                null,
                mEthernetNetworkAgent.getNetwork());

        assertFalse(mCm.isActiveNetworkMetered());
    }

    @Test
    public void testPerAppDefaultRegisterDefaultNetworkCallback() throws Exception {
        @OemNetworkPreferences.OemNetworkPreference final int networkPref =
                OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY;
        final int expectedOemPrefRequestSize = 1;
        final TestNetworkCallback defaultNetworkCallback = new TestNetworkCallback();

        // Register the default network callback before the pref is already set. This means that
        // the policy will be applied to the callback on setOemNetworkPreference().
        mCm.registerDefaultNetworkCallback(defaultNetworkCallback);
        defaultNetworkCallback.assertNoCallback();

        // Setup the test process to use networkPref for their default network.
        setupMultipleDefaultNetworksForOemNetworkPreferenceCurrentUidTest(networkPref);

        // Bring up ethernet with OEM_PAID. This will satisfy NET_CAPABILITY_OEM_PAID.
        // The active nai for the default is null at this point as this is a restricted network.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_ETHERNET, true);
        verifyMultipleDefaultNetworksTracksCorrectly(expectedOemPrefRequestSize,
                null,
                mEthernetNetworkAgent.getNetwork());

        // At this point with a restricted network used, the available callback should trigger
        defaultNetworkCallback.expectAvailableThenValidatedCallbacks(mEthernetNetworkAgent);
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(),
                mEthernetNetworkAgent.getNetwork());

        // Now bring down the default network which should trigger a LOST callback.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_ETHERNET, false);

        // At this point, with no network is available, the lost callback should trigger
        defaultNetworkCallback.expectCallback(CallbackEntry.LOST, mEthernetNetworkAgent);

        // Confirm we can unregister without issues.
        mCm.unregisterNetworkCallback(defaultNetworkCallback);
    }

    @Test
    public void testPerAppDefaultRegisterDefaultNetworkCallbackAfterPrefSet() throws Exception {
        @OemNetworkPreferences.OemNetworkPreference final int networkPref =
                OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY;
        final int expectedOemPrefRequestSize = 1;
        final TestNetworkCallback defaultNetworkCallback = new TestNetworkCallback();

        // Setup the test process to use networkPref for their default network.
        setupMultipleDefaultNetworksForOemNetworkPreferenceCurrentUidTest(networkPref);

        // Register the default network callback after the pref is already set. This means that
        // the policy will be applied to the callback on requestNetwork().
        mCm.registerDefaultNetworkCallback(defaultNetworkCallback);
        defaultNetworkCallback.assertNoCallback();

        // Bring up ethernet with OEM_PAID. This will satisfy NET_CAPABILITY_OEM_PAID.
        // The active nai for the default is null at this point as this is a restricted network.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_ETHERNET, true);
        verifyMultipleDefaultNetworksTracksCorrectly(expectedOemPrefRequestSize,
                null,
                mEthernetNetworkAgent.getNetwork());

        // At this point with a restricted network used, the available callback should trigger
        defaultNetworkCallback.expectAvailableThenValidatedCallbacks(mEthernetNetworkAgent);
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(),
                mEthernetNetworkAgent.getNetwork());

        // Now bring down the default network which should trigger a LOST callback.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_ETHERNET, false);

        // At this point, with no network is available, the lost callback should trigger
        defaultNetworkCallback.expectCallback(CallbackEntry.LOST, mEthernetNetworkAgent);

        // Confirm we can unregister without issues.
        mCm.unregisterNetworkCallback(defaultNetworkCallback);
    }

    @Test
    public void testPerAppDefaultRegisterDefaultNetworkCallbackDoesNotFire() throws Exception {
        @OemNetworkPreferences.OemNetworkPreference final int networkPref =
                OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY;
        final int expectedOemPrefRequestSize = 1;
        final TestNetworkCallback defaultNetworkCallback = new TestNetworkCallback();
        final int userId = UserHandle.getUserId(Process.myUid());

        mCm.registerDefaultNetworkCallback(defaultNetworkCallback);
        defaultNetworkCallback.assertNoCallback();

        // Setup a process different than the test process to use the default network. This means
        // that the defaultNetworkCallback won't be tracked by the per-app policy.
        setupMultipleDefaultNetworksForOemNetworkPreferenceNotCurrentUidTest(networkPref);

        // Bring up ethernet with OEM_PAID. This will satisfy NET_CAPABILITY_OEM_PAID.
        // The active nai for the default is null at this point as this is a restricted network.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_ETHERNET, true);
        verifyMultipleDefaultNetworksTracksCorrectly(expectedOemPrefRequestSize,
                null,
                mEthernetNetworkAgent.getNetwork());

        // As this callback does not have access to the OEM_PAID network, it will not fire.
        defaultNetworkCallback.assertNoCallback();
        assertDefaultNetworkCapabilities(userId /* no networks */);

        // Bring up unrestricted cellular. This should now satisfy the default network.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_CELLULAR, true);
        verifyMultipleDefaultNetworksTracksCorrectly(expectedOemPrefRequestSize,
                mCellNetworkAgent.getNetwork(),
                mEthernetNetworkAgent.getNetwork());

        // At this point with an unrestricted network used, the available callback should trigger
        defaultNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(),
                mCellNetworkAgent.getNetwork());
        assertDefaultNetworkCapabilities(userId, mCellNetworkAgent);

        // Now bring down the per-app network.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_ETHERNET, false);

        // Since the callback didn't use the per-app network, no callback should fire.
        defaultNetworkCallback.assertNoCallback();

        // Now bring down the default network.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_CELLULAR, false);

        // As this callback was tracking the default, this should now trigger.
        defaultNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);

        // Confirm we can unregister without issues.
        mCm.unregisterNetworkCallback(defaultNetworkCallback);
    }

    private void verifySetOemNetworkPreferenceForPreference(
            @NonNull final UidRangeParcel[] uidRanges,
            final int addUidRangesNetId,
            final int addUidRangesTimes,
            final int removeUidRangesNetId,
            final int removeUidRangesTimes,
            final boolean shouldDestroyNetwork) throws RemoteException {
        final boolean useAnyIdForAdd = OEM_PREF_ANY_NET_ID == addUidRangesNetId;
        final boolean useAnyIdForRemove = OEM_PREF_ANY_NET_ID == removeUidRangesNetId;

        // Validate netd.
        verify(mMockNetd, times(addUidRangesTimes))
                .networkAddUidRanges(
                        (useAnyIdForAdd ? anyInt() : eq(addUidRangesNetId)), eq(uidRanges));
        verify(mMockNetd, times(removeUidRangesTimes))
                .networkRemoveUidRanges(
                        (useAnyIdForRemove ? anyInt() : eq(removeUidRangesNetId)), eq(uidRanges));
        if (shouldDestroyNetwork) {
            verify(mMockNetd, times(1))
                    .networkDestroy((useAnyIdForRemove ? anyInt() : eq(removeUidRangesNetId)));
        }
        reset(mMockNetd);
    }

    /**
     * Test the tracked default requests clear previous OEM requests on setOemNetworkPreference().
     * @throws Exception
     */
    @Test
    public void testSetOemNetworkPreferenceClearPreviousOemValues() throws Exception {
        @OemNetworkPreferences.OemNetworkPreference int networkPref =
                OEM_NETWORK_PREFERENCE_OEM_PAID;
        final int testPackageUid = 123;
        final String testPackageName = "com.google.apps.contacts";
        final UidRangeParcel[] uidRanges =
                toUidRangeStableParcels(uidRangesForUid(testPackageUid));

        // Validate the starting requests only includes the fallback request.
        assertEquals(1, mService.mDefaultNetworkRequests.size());

        // Add an OEM default network request to track.
        setupSetOemNetworkPreferenceForPreferenceTest(networkPref, uidRanges, testPackageName);

        // Two requests should exist, one for the fallback and one for the pref.
        assertEquals(2, mService.mDefaultNetworkRequests.size());

        networkPref = OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY;
        setupSetOemNetworkPreferenceForPreferenceTest(networkPref, uidRanges, testPackageName);

        // Two requests should still exist validating the previous per-app request was replaced.
        assertEquals(2, mService.mDefaultNetworkRequests.size());
    }

    /**
     * Test network priority for preference OEM_NETWORK_PREFERENCE_OEM_PAID following in order:
     * NET_CAPABILITY_NOT_METERED -> NET_CAPABILITY_OEM_PAID -> fallback
     * @throws Exception
     */
    @Test
    public void testMultilayerForPreferenceOemPaidEvaluatesCorrectly()
            throws Exception {
        @OemNetworkPreferences.OemNetworkPreference final int networkPref =
                OEM_NETWORK_PREFERENCE_OEM_PAID;

        // Arrange PackageManager mocks
        final int testPackageNameUid = 123;
        final UidRangeParcel[] uidRanges =
                toUidRangeStableParcels(uidRangesForUid(testPackageNameUid));
        setupSetOemNetworkPreferenceForPreferenceTest(networkPref, uidRanges, TEST_PACKAGE_NAME);

        // Verify the starting state. No networks should be connected.
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                false /* shouldDestroyNetwork */);

        // Test lowest to highest priority requests.
        // Bring up metered cellular. This will satisfy the fallback network.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_CELLULAR, true);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mCellNetworkAgent.getNetwork().netId, 1 /* times */,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                false /* shouldDestroyNetwork */);

        // Bring up ethernet with OEM_PAID. This will satisfy NET_CAPABILITY_OEM_PAID.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_ETHERNET, true);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mEthernetNetworkAgent.getNetwork().netId, 1 /* times */,
                mCellNetworkAgent.getNetwork().netId, 1 /* times */,
                false /* shouldDestroyNetwork */);

        // Bring up unmetered Wi-Fi. This will satisfy NET_CAPABILITY_NOT_METERED.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_WIFI, true);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mWiFiNetworkAgent.getNetwork().netId, 1 /* times */,
                mEthernetNetworkAgent.getNetwork().netId, 1 /* times */,
                false /* shouldDestroyNetwork */);

        // Disconnecting OEM_PAID should have no effect as it is lower in priority then unmetered.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_ETHERNET, false);
        // netd should not be called as default networks haven't changed.
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                false /* shouldDestroyNetwork */);

        // Disconnecting unmetered should put PANS on lowest priority fallback request.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_WIFI, false);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mCellNetworkAgent.getNetwork().netId, 1 /* times */,
                mWiFiNetworkAgent.getNetwork().netId, 0 /* times */,
                true /* shouldDestroyNetwork */);

        // Disconnecting the fallback network should result in no connectivity.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_CELLULAR, false);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                mCellNetworkAgent.getNetwork().netId, 0 /* times */,
                true /* shouldDestroyNetwork */);
    }

    /**
     * Test network priority for OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK following in order:
     * NET_CAPABILITY_NOT_METERED -> NET_CAPABILITY_OEM_PAID
     * @throws Exception
     */
    @Test
    public void testMultilayerForPreferenceOemPaidNoFallbackEvaluatesCorrectly()
            throws Exception {
        @OemNetworkPreferences.OemNetworkPreference final int networkPref =
                OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK;

        // Arrange PackageManager mocks
        final int testPackageNameUid = 123;
        final UidRangeParcel[] uidRanges =
                toUidRangeStableParcels(uidRangesForUid(testPackageNameUid));
        setupSetOemNetworkPreferenceForPreferenceTest(networkPref, uidRanges, TEST_PACKAGE_NAME);

        // Verify the starting state. This preference doesn't support using the fallback network
        // therefore should be on the disconnected network as it has no networks to connect to.
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mService.mNoServiceNetwork.network.getNetId(), 1 /* times */,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                false /* shouldDestroyNetwork */);

        // Test lowest to highest priority requests.
        // Bring up metered cellular. This will satisfy the fallback network.
        // This preference should not use this network as it doesn't support fallback usage.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_CELLULAR, true);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                false /* shouldDestroyNetwork */);

        // Bring up ethernet with OEM_PAID. This will satisfy NET_CAPABILITY_OEM_PAID.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_ETHERNET, true);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mEthernetNetworkAgent.getNetwork().netId, 1 /* times */,
                mService.mNoServiceNetwork.network.getNetId(), 1 /* times */,
                false /* shouldDestroyNetwork */);

        // Bring up unmetered Wi-Fi. This will satisfy NET_CAPABILITY_NOT_METERED.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_WIFI, true);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mWiFiNetworkAgent.getNetwork().netId, 1 /* times */,
                mEthernetNetworkAgent.getNetwork().netId, 1 /* times */,
                false /* shouldDestroyNetwork */);

        // Disconnecting unmetered should put PANS on OEM_PAID.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_WIFI, false);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mEthernetNetworkAgent.getNetwork().netId, 1 /* times */,
                mWiFiNetworkAgent.getNetwork().netId, 0 /* times */,
                true /* shouldDestroyNetwork */);

        // Disconnecting OEM_PAID should result in no connectivity.
        // OEM_PAID_NO_FALLBACK not supporting a fallback now uses the disconnected network.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_ETHERNET, false);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mService.mNoServiceNetwork.network.getNetId(), 1 /* times */,
                mEthernetNetworkAgent.getNetwork().netId, 0 /* times */,
                true /* shouldDestroyNetwork */);
    }

    /**
     * Test network priority for OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY following in order:
     * NET_CAPABILITY_OEM_PAID
     * This preference should only apply to OEM_PAID networks.
     * @throws Exception
     */
    @Test
    public void testMultilayerForPreferenceOemPaidOnlyEvaluatesCorrectly()
            throws Exception {
        @OemNetworkPreferences.OemNetworkPreference final int networkPref =
                OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY;

        // Arrange PackageManager mocks
        final int testPackageNameUid = 123;
        final UidRangeParcel[] uidRanges =
                toUidRangeStableParcels(uidRangesForUid(testPackageNameUid));
        setupSetOemNetworkPreferenceForPreferenceTest(networkPref, uidRanges, TEST_PACKAGE_NAME);

        // Verify the starting state. This preference doesn't support using the fallback network
        // therefore should be on the disconnected network as it has no networks to connect to.
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mService.mNoServiceNetwork.network.getNetId(), 1 /* times */,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                false /* shouldDestroyNetwork */);

        // Bring up metered cellular. This should not apply to this preference.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_CELLULAR, true);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                false /* shouldDestroyNetwork */);

        // Bring up unmetered Wi-Fi. This should not apply to this preference.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_WIFI, true);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                false /* shouldDestroyNetwork */);

        // Bring up ethernet with OEM_PAID. This will satisfy NET_CAPABILITY_OEM_PAID.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_ETHERNET, true);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mEthernetNetworkAgent.getNetwork().netId, 1 /* times */,
                mService.mNoServiceNetwork.network.getNetId(), 1 /* times */,
                false /* shouldDestroyNetwork */);

        // Disconnecting OEM_PAID should result in no connectivity.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_ETHERNET, false);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mService.mNoServiceNetwork.network.getNetId(), 1 /* times */,
                mEthernetNetworkAgent.getNetwork().netId, 0 /* times */,
                true /* shouldDestroyNetwork */);
    }

    /**
     * Test network priority for OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY following in order:
     * NET_CAPABILITY_OEM_PRIVATE
     * This preference should only apply to OEM_PRIVATE networks.
     * @throws Exception
     */
    @Test
    public void testMultilayerForPreferenceOemPrivateOnlyEvaluatesCorrectly()
            throws Exception {
        @OemNetworkPreferences.OemNetworkPreference final int networkPref =
                OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY;

        // Arrange PackageManager mocks
        final int testPackageNameUid = 123;
        final UidRangeParcel[] uidRanges =
                toUidRangeStableParcels(uidRangesForUid(testPackageNameUid));
        setupSetOemNetworkPreferenceForPreferenceTest(networkPref, uidRanges, TEST_PACKAGE_NAME);

        // Verify the starting state. This preference doesn't support using the fallback network
        // therefore should be on the disconnected network as it has no networks to connect to.
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mService.mNoServiceNetwork.network.getNetId(), 1 /* times */,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                false /* shouldDestroyNetwork */);

        // Bring up metered cellular. This should not apply to this preference.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_CELLULAR, true);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                false /* shouldDestroyNetwork */);

        // Bring up unmetered Wi-Fi. This should not apply to this preference.
        setOemNetworkPreferenceAgentConnected(TRANSPORT_WIFI, true);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                OEM_PREF_ANY_NET_ID, 0 /* times */,
                false /* shouldDestroyNetwork */);

        // Bring up ethernet with OEM_PRIVATE. This will satisfy NET_CAPABILITY_OEM_PRIVATE.
        startOemManagedNetwork(false);
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mEthernetNetworkAgent.getNetwork().netId, 1 /* times */,
                mService.mNoServiceNetwork.network.getNetId(), 1 /* times */,
                false /* shouldDestroyNetwork */);

        // Disconnecting OEM_PRIVATE should result in no connectivity.
        stopOemManagedNetwork();
        verifySetOemNetworkPreferenceForPreference(uidRanges,
                mService.mNoServiceNetwork.network.getNetId(), 1 /* times */,
                mEthernetNetworkAgent.getNetwork().netId, 0 /* times */,
                true /* shouldDestroyNetwork */);
    }

    private UidRange createUidRange(int userId) {
        return UidRange.createForUser(UserHandle.of(userId));
    }
}
