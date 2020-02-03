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
import static android.net.NetworkCapabilities.NET_CAPABILITY_FOREGROUND;
import static android.net.NetworkCapabilities.NET_CAPABILITY_FOTA;
import static android.net.NetworkCapabilities.NET_CAPABILITY_IA;
import static android.net.NetworkCapabilities.NET_CAPABILITY_IMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_MMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
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
import static android.net.RouteInfo.RTN_UNREACHABLE;
import static android.system.OsConstants.IPPROTO_TCP;

import static com.android.server.ConnectivityServiceTestUtilsKt.transportToLegacyType;
import static com.android.testutils.ConcurrentUtilsKt.await;
import static com.android.testutils.ConcurrentUtilsKt.durationOf;
import static com.android.testutils.ExceptionUtils.ignoreExceptions;
import static com.android.testutils.HandlerUtilsKt.waitForIdleSerialExecutor;
import static com.android.testutils.MiscAssertsKt.assertContainsExactly;
import static com.android.testutils.MiscAssertsKt.assertEmpty;
import static com.android.testutils.MiscAssertsKt.assertLength;
import static com.android.testutils.MiscAssertsKt.assertRunsInAtMost;
import static com.android.testutils.MiscAssertsKt.assertThrows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
import android.net.IConnectivityDiagnosticsCallback;
import android.net.IDnsResolver;
import android.net.IIpConnectivityMetrics;
import android.net.INetd;
import android.net.INetworkMonitor;
import android.net.INetworkMonitorCallbacks;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.InetAddresses;
import android.net.InterfaceConfiguration;
import android.net.IpPrefix;
import android.net.IpSecManager;
import android.net.IpSecManager.UdpEncapsulationSocket;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.MatchAllNetworkSpecifier;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.NetworkStack;
import android.net.NetworkStackClient;
import android.net.NetworkState;
import android.net.NetworkUtils;
import android.net.ProxyInfo;
import android.net.ResolverParamsParcel;
import android.net.RouteInfo;
import android.net.SocketKeepalive;
import android.net.UidRange;
import android.net.Uri;
import android.net.VpnManager;
import android.net.metrics.IpConnectivityLog;
import android.net.shared.NetworkMonitorUtils;
import android.net.shared.PrivateDnsConfig;
import android.net.util.MultinetworkPolicyTracker;
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
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.KeyStore;
import android.system.Os;
import android.test.mock.MockContentResolver;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.IBatteryStats;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnInfo;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.WakeupMessage;
import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.ConnectivityService.ConnectivityDiagnosticsCallbackInfo;
import com.android.server.connectivity.ConnectivityConstants;
import com.android.server.connectivity.DefaultNetworkMetrics;
import com.android.server.connectivity.IpConnectivityMetrics;
import com.android.server.connectivity.MockableSystemProperties;
import com.android.server.connectivity.Nat464Xlat;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.connectivity.NetworkNotificationManager.NotificationType;
import com.android.server.connectivity.ProxyTracker;
import com.android.server.connectivity.Vpn;
import com.android.server.net.NetworkPinner;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.testutils.ExceptionUtils;
import com.android.testutils.HandlerUtilsKt;
import com.android.testutils.RecorderCallback.CallbackEntry;
import com.android.testutils.TestableNetworkCallback;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
    private static final int TEST_LINGER_DELAY_MS = 300;
    // Chosen to be less than the linger timeout. This ensures that we can distinguish between a
    // LOST callback that arrives immediately and a LOST callback that arrives after the linger
    // timeout. For this, our assertions should run fast enough to leave less than
    // (mService.mLingerDelayMs - TEST_CALLBACK_TIMEOUT_MS) between the time callbacks are
    // supposedly fired, and the time we call expectCallback.
    private static final int TEST_CALLBACK_TIMEOUT_MS = 250;
    // Chosen to be less than TEST_CALLBACK_TIMEOUT_MS. This ensures that requests have time to
    // complete before callbacks are verified.
    private static final int TEST_REQUEST_TIMEOUT_MS = 150;

    private static final int UNREASONABLY_LONG_ALARM_WAIT_MS = 1000;

    private static final long TIMESTAMP = 1234L;

    private static final String CLAT_PREFIX = "v4-";
    private static final String MOBILE_IFNAME = "test_rmnet_data0";
    private static final String WIFI_IFNAME = "test_wlan0";
    private static final String WIFI_WOL_IFNAME = "test_wlan_wol";
    private static final String TEST_PACKAGE_NAME = "com.android.test.package";
    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    private MockContext mServiceContext;
    private HandlerThread mCsHandlerThread;
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

    @Mock IIpConnectivityMetrics mIpConnectivityMetrics;
    @Mock IpConnectivityMetrics.Logger mMetricsService;
    @Mock DefaultNetworkMetrics mDefaultNetworkMetrics;
    @Mock INetworkManagementService mNetworkManagementService;
    @Mock INetworkStatsService mStatsService;
    @Mock IBatteryStats mBatteryStatsService;
    @Mock INetworkPolicyManager mNpm;
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
        // Contains all registered receivers since this object was created. Useful to clear
        // them when needed, as BroadcastInterceptingContext does not provide this facility.
        private final List<BroadcastReceiver> mRegisteredReceivers = new ArrayList<>();

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
        public Object getSystemService(String name) {
            if (Context.CONNECTIVITY_SERVICE.equals(name)) return mCm;
            if (Context.NOTIFICATION_SERVICE.equals(name)) return mNotificationManager;
            if (Context.NETWORK_STACK_SERVICE.equals(name)) return mNetworkStack;
            if (Context.USER_SERVICE.equals(name)) return mUserManager;
            if (Context.ALARM_SERVICE.equals(name)) return mAlarmManager;
            if (Context.LOCATION_SERVICE.equals(name)) return mLocationManager;
            if (Context.APP_OPS_SERVICE.equals(name)) return mAppOpsManager;
            return super.getSystemService(name);
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

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            mRegisteredReceivers.add(receiver);
            return super.registerReceiver(receiver, filter);
        }

        public void clearRegisteredReceivers() {
            // super.unregisterReceiver is a no-op for receivers that are not registered (because
            // they haven't been registered or because they have already been unregistered).
            // For the same reason, don't bother clearing mRegisteredReceivers.
            for (final BroadcastReceiver rcv : mRegisteredReceivers) unregisterReceiver(rcv);
        }
    }

    private void waitForIdle() {
        HandlerUtilsKt.waitForIdle(mCsHandlerThread, TIMEOUT_MS);
        waitForIdle(mCellNetworkAgent, TIMEOUT_MS);
        waitForIdle(mWiFiNetworkAgent, TIMEOUT_MS);
        waitForIdle(mEthernetNetworkAgent, TIMEOUT_MS);
        HandlerUtilsKt.waitForIdle(mCsHandlerThread, TIMEOUT_MS);
        HandlerUtilsKt.waitForIdle(ConnectivityThread.get(), TIMEOUT_MS);
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
        ConditionVariable cv = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
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
        ConditionVariable cv = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
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
        private static final int VALIDATION_RESULT_BASE = NETWORK_VALIDATION_PROBE_DNS
                | NETWORK_VALIDATION_PROBE_HTTP
                | NETWORK_VALIDATION_PROBE_HTTPS;
        private static final int VALIDATION_RESULT_VALID = VALIDATION_RESULT_BASE
                | NETWORK_VALIDATION_RESULT_VALID;
        private static final int VALIDATION_RESULT_PARTIAL = VALIDATION_RESULT_BASE
                | NETWORK_VALIDATION_PROBE_FALLBACK
                | NETWORK_VALIDATION_RESULT_PARTIAL;
        private static final int VALIDATION_RESULT_INVALID = 0;

        private static final long DATA_STALL_TIMESTAMP = 10L;
        private static final int DATA_STALL_DETECTION_METHOD = 1;

        private INetworkMonitor mNetworkMonitor;
        private INetworkMonitorCallbacks mNmCallbacks;
        private int mNmValidationResult = VALIDATION_RESULT_BASE;
        private int mProbesCompleted;
        private int mProbesSucceeded;
        private String mNmValidationRedirectUrl = null;
        private PersistableBundle mValidationExtras = PersistableBundle.EMPTY;
        private PersistableBundle mDataStallExtras = PersistableBundle.EMPTY;
        private boolean mNmProvNotificationRequested = false;

        private final ConditionVariable mNetworkStatusReceived = new ConditionVariable();
        // Contains the redirectUrl from networkStatus(). Before reading, wait for
        // mNetworkStatusReceived.
        private String mRedirectUrl;

        TestNetworkAgentWrapper(int transport) throws Exception {
            this(transport, new LinkProperties());
        }

        TestNetworkAgentWrapper(int transport, LinkProperties linkProperties)
                throws Exception {
            super(transport, linkProperties, mServiceContext);

            // Waits for the NetworkAgent to be registered, which includes the creation of the
            // NetworkMonitor.
            waitForIdle(TIMEOUT_MS);
            HandlerUtilsKt.waitForIdle(mCsHandlerThread, TIMEOUT_MS);
            HandlerUtilsKt.waitForIdle(ConnectivityThread.get(), TIMEOUT_MS);
        }

        @Override
        protected InstrumentedNetworkAgent makeNetworkAgent(LinkProperties linkProperties)
                throws Exception {
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

            final InstrumentedNetworkAgent na = new InstrumentedNetworkAgent(this, linkProperties) {
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
            mNmCallbacks.notifyNetworkTestedWithExtras(
                    mNmValidationResult, mNmValidationRedirectUrl, TIMESTAMP, mValidationExtras);

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
            mNmValidationResult = VALIDATION_RESULT_VALID;
            mNmValidationRedirectUrl = null;
            int probesSucceeded = VALIDATION_RESULT_BASE & ~NETWORK_VALIDATION_PROBE_HTTP;
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
            int probesCompleted = VALIDATION_RESULT_BASE;
            int probesSucceeded = VALIDATION_RESULT_INVALID;
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
            int probesCompleted = VALIDATION_RESULT_BASE & ~NETWORK_VALIDATION_PROBE_HTTPS;
            int probesSucceeded = VALIDATION_RESULT_INVALID;
            if (isStrictMode) {
                probesCompleted |= NETWORK_VALIDATION_PROBE_PRIVDNS;
            }
            setProbesStatus(probesCompleted, probesSucceeded);
        }

        void setNetworkPartial() {
            mNmValidationResult = VALIDATION_RESULT_PARTIAL;
            mNmValidationRedirectUrl = null;
            int probesCompleted = VALIDATION_RESULT_BASE;
            int probesSucceeded = VALIDATION_RESULT_BASE & ~NETWORK_VALIDATION_PROBE_HTTPS;
            setProbesStatus(probesCompleted, probesSucceeded);
        }

        void setNetworkPartialValid(boolean isStrictMode) {
            setNetworkPartial();
            mNmValidationResult |= VALIDATION_RESULT_VALID;
            int probesCompleted = VALIDATION_RESULT_BASE;
            int probesSucceeded = VALIDATION_RESULT_BASE & ~NETWORK_VALIDATION_PROBE_HTTPS;
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

        void notifyCaptivePortalDataChanged(CaptivePortalData data) {
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
            mNmCallbacks.notifyDataStallSuspected(
                    DATA_STALL_TIMESTAMP, DATA_STALL_DETECTION_METHOD, mDataStallExtras);
        }
    }

    /**
     * A NetworkFactory that allows tests to wait until any in-flight NetworkRequest add or remove
     * operations have been processed. Before ConnectivityService can add or remove any requests,
     * the factory must be told to expect those operations by calling expectAddRequestsWithScores or
     * expectRemoveRequests.
     */
    private static class MockNetworkFactory extends NetworkFactory {
        private final ConditionVariable mNetworkStartedCV = new ConditionVariable();
        private final ConditionVariable mNetworkStoppedCV = new ConditionVariable();
        private final AtomicBoolean mNetworkStarted = new AtomicBoolean(false);

        // Used to expect that requests be removed or added on a separate thread, without sleeping.
        // Callers can call either expectAddRequestsWithScores() or expectRemoveRequests() exactly
        // once, then cause some other thread to add or remove requests, then call
        // waitForRequests().
        // It is not possible to wait for both add and remove requests. When adding, the queue
        // contains the expected score. When removing, the value is unused, all matters is the
        // number of objects in the queue.
        private final LinkedBlockingQueue<Integer> mExpectations;

        // Whether we are currently expecting requests to be added or removed. Valid only if
        // mExpectations is non-empty.
        private boolean mExpectingAdditions;

        // Used to collect the networks requests managed by this factory. This is a duplicate of
        // the internal information stored in the NetworkFactory (which is private).
        private SparseArray<NetworkRequest> mNetworkRequests = new SparseArray<>();

        public MockNetworkFactory(Looper looper, Context context, String logTag,
                NetworkCapabilities filter) {
            super(looper, context, logTag, filter);
            mExpectations = new LinkedBlockingQueue<>();
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
            synchronized (mExpectations) {
                final Integer expectedScore = mExpectations.poll(); // null if the queue is empty

                assertNotNull("Added more requests than expected (" + request + " score : "
                        + score + ")", expectedScore);
                // If we're expecting anything, we must be expecting additions.
                if (!mExpectingAdditions) {
                    fail("Can't add requests while expecting requests to be removed");
                }
                if (expectedScore != score) {
                    fail("Expected score was " + expectedScore + " but actual was " + score
                            + " in added request");
                }

                // Add the request.
                mNetworkRequests.put(request.requestId, request);
                super.handleAddRequest(request, score, factorySerialNumber);
                mExpectations.notify();
            }
        }

        @Override
        protected void handleRemoveRequest(NetworkRequest request) {
            synchronized (mExpectations) {
                final Integer expectedScore = mExpectations.poll(); // null if the queue is empty

                assertTrue("Removed more requests than expected", expectedScore != null);
                // If we're expecting anything, we must be expecting removals.
                if (mExpectingAdditions) {
                    fail("Can't remove requests while expecting requests to be added");
                }

                // Remove the request.
                mNetworkRequests.remove(request.requestId);
                super.handleRemoveRequest(request);
                mExpectations.notify();
            }
        }

        // Trigger releasing the request as unfulfillable
        public void triggerUnfulfillable(NetworkRequest r) {
            super.releaseRequestAsUnfulfillableByAnyFactory(r);
        }

        private void assertNoExpectations() {
            if (mExpectations.size() != 0) {
                fail("Can't add expectation, " + mExpectations.size() + " already pending");
            }
        }

        // Expects that requests with the specified scores will be added.
        public void expectAddRequestsWithScores(final int... scores) {
            assertNoExpectations();
            mExpectingAdditions = true;
            for (int score : scores) {
                mExpectations.add(score);
            }
        }

        // Expects that count requests will be removed.
        public void expectRemoveRequests(final int count) {
            assertNoExpectations();
            mExpectingAdditions = false;
            for (int i = 0; i < count; ++i) {
                mExpectations.add(0); // For removals the score is ignored so any value will do.
            }
        }

        // Waits for the expected request additions or removals to happen within a timeout.
        public void waitForRequests() throws InterruptedException {
            final long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
            synchronized (mExpectations) {
                while (mExpectations.size() > 0 && SystemClock.elapsedRealtime() < deadline) {
                    mExpectations.wait(deadline - SystemClock.elapsedRealtime());
                }
            }
            final long count = mExpectations.size();
            final String msg = count + " requests still not " +
                    (mExpectingAdditions ? "added" : "removed") +
                    " after " + TIMEOUT_MS + " ms";
            assertEquals(msg, 0, count);
        }

        public SparseArray<NetworkRequest> waitForNetworkRequests(final int count)
                throws InterruptedException {
            waitForRequests();
            assertEquals(count, getMyRequestCount());
            return mNetworkRequests;
        }
    }

    private static Looper startHandlerThreadAndReturnLooper() {
        final HandlerThread handlerThread = new HandlerThread("MockVpnThread");
        handlerThread.start();
        return handlerThread.getLooper();
    }

    private class MockVpn extends Vpn {
        // TODO : the interactions between this mock and the mock network agent are too
        // hard to get right at this moment, because it's unclear in which case which
        // target needs to get a method call or both, and in what order. It's because
        // MockNetworkAgent wants to manage its own NetworkCapabilities, but the Vpn
        // parent class of MockVpn agent wants that responsibility.
        // That being said inside the test it should be possible to make the interactions
        // harder to get wrong with precise speccing, judicious comments, helper methods
        // and a few sprinkled assertions.

        private boolean mConnected = false;
        // Careful ! This is different from mNetworkAgent, because MockNetworkAgent does
        // not inherit from NetworkAgent.
        private TestNetworkAgentWrapper mMockNetworkAgent;
        private int mVpnType = VpnManager.TYPE_VPN_SERVICE;

        private VpnInfo mVpnInfo;

        public MockVpn(int userId) {
            super(startHandlerThreadAndReturnLooper(), mServiceContext, mNetworkManagementService,
                    userId, mock(KeyStore.class));
        }

        public void setNetworkAgent(TestNetworkAgentWrapper agent) {
            agent.waitForIdle(TIMEOUT_MS);
            mMockNetworkAgent = agent;
            mNetworkAgent = agent.getNetworkAgent();
            mNetworkCapabilities.set(agent.getNetworkCapabilities());
        }

        public void setUids(Set<UidRange> uids) {
            mNetworkCapabilities.setUids(uids);
            updateCapabilities(null /* defaultNetwork */);
        }

        public void setVpnType(int vpnType) {
            mVpnType = vpnType;
        }

        @Override
        public int getNetId() {
            if (mMockNetworkAgent == null) {
                return NETID_UNSET;
            }
            return mMockNetworkAgent.getNetwork().netId;
        }

        @Override
        public boolean appliesToUid(int uid) {
            return mConnected;  // Trickery to simplify testing.
        }

        @Override
        protected boolean isCallerEstablishedOwnerLocked() {
            return mConnected;  // Similar trickery
        }

        @Override
        public int getActiveAppVpnType() {
            return mVpnType;
        }

        private void connect(boolean isAlwaysMetered) {
            mNetworkCapabilities.set(mMockNetworkAgent.getNetworkCapabilities());
            mConnected = true;
            mConfig = new VpnConfig();
            mConfig.isMetered = isAlwaysMetered;
        }

        public void connectAsAlwaysMetered() {
            connect(true /* isAlwaysMetered */);
        }

        public void connect() {
            connect(false /* isAlwaysMetered */);
        }

        @Override
        public NetworkCapabilities updateCapabilities(Network defaultNetwork) {
            if (!mConnected) return null;
            super.updateCapabilities(defaultNetwork);
            // Because super.updateCapabilities will update the capabilities of the agent but
            // not the mock agent, the mock agent needs to know about them.
            copyCapabilitiesToNetworkAgent();
            return new NetworkCapabilities(mNetworkCapabilities);
        }

        private void copyCapabilitiesToNetworkAgent() {
            if (null != mMockNetworkAgent) {
                mMockNetworkAgent.setNetworkCapabilities(mNetworkCapabilities,
                        false /* sendToConnectivityService */);
            }
        }

        public void disconnect() {
            mConnected = false;
            mConfig = null;
        }

        @Override
        public synchronized VpnInfo getVpnInfo() {
            if (mVpnInfo != null) return mVpnInfo;

            return super.getVpnInfo();
        }

        private void setVpnInfo(VpnInfo vpnInfo) {
            mVpnInfo = vpnInfo;
        }
    }

    private void mockVpn(int uid) {
        synchronized (mService.mVpns) {
            int userId = UserHandle.getUserId(uid);
            mMockVpn = new MockVpn(userId);
            // This has no effect unless the VPN is actually connected, because things like
            // getActiveNetworkForUidInternal call getNetworkAgentInfoForNetId on the VPN
            // netId, and check if that network is actually connected.
            mService.mVpns.put(userId, mMockVpn);
        }
    }

    private void setUidRulesChanged(int uidRules) throws RemoteException {
        mPolicyListener.onUidRulesChanged(Process.myUid(), uidRules);
    }

    private void setRestrictBackgroundChanged(boolean restrictBackground) throws RemoteException {
        mPolicyListener.onRestrictBackgroundChanged(restrictBackground);
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

    private static final int VPN_USER = 0;
    private static final int APP1_UID = UserHandle.getUid(VPN_USER, 10100);
    private static final int APP2_UID = UserHandle.getUid(VPN_USER, 10101);
    private static final int VPN_UID = UserHandle.getUid(VPN_USER, 10043);

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();

        MockitoAnnotations.initMocks(this);
        when(mMetricsService.defaultNetworkMetrics()).thenReturn(mDefaultNetworkMetrics);

        when(mUserManager.getUsers(eq(true))).thenReturn(
                Arrays.asList(new UserInfo[] {
                        new UserInfo(VPN_USER, "", 0),
                }));
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.targetSdkVersion = Build.VERSION_CODES.Q;
        when(mPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), any()))
                .thenReturn(applicationInfo);

        // InstrumentationTestRunner prepares a looper, but AndroidJUnitRunner does not.
        // http://b/25897652 .
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        mockDefaultPackages();

        FakeSettingsProvider.clearSettingsProvider();
        mServiceContext = new MockContext(InstrumentationRegistry.getContext(),
                new FakeSettingsProvider());
        LocalServices.removeServiceForTest(NetworkPolicyManagerInternal.class);
        LocalServices.addService(
                NetworkPolicyManagerInternal.class, mock(NetworkPolicyManagerInternal.class));

        mAlarmManagerThread = new HandlerThread("TestAlarmManager");
        mAlarmManagerThread.start();
        initAlarmManager(mAlarmManager, mAlarmManagerThread.getThreadHandler());

        mCsHandlerThread = new HandlerThread("TestConnectivityService");
        final ConnectivityService.Dependencies deps = makeDependencies();
        mService = new ConnectivityService(mServiceContext,
                mNetworkManagementService,
                mStatsService,
                mNpm,
                mMockDnsResolver,
                mock(IpConnectivityLog.class),
                mMockNetd,
                deps);
        mService.mLingerDelayMs = TEST_LINGER_DELAY_MS;
        verify(deps).makeMultinetworkPolicyTracker(any(), any(), any());

        final ArgumentCaptor<INetworkPolicyListener> policyListenerCaptor =
                ArgumentCaptor.forClass(INetworkPolicyListener.class);
        verify(mNpm).registerListener(policyListenerCaptor.capture());
        mPolicyListener = policyListenerCaptor.getValue();

        // Create local CM before sending system ready so that we can answer
        // getSystemService() correctly.
        mCm = new WrappedConnectivityManager(InstrumentationRegistry.getContext(), mService);
        mService.systemReady();
        mockVpn(Process.myUid());
        mCm.bindProcessToNetwork(null);

        // Ensure that the default setting for Captive Portals is used for most tests
        setCaptivePortalMode(Settings.Global.CAPTIVE_PORTAL_MODE_PROMPT);
        setAlwaysOnNetworks(false);
        setPrivateDnsSettings(PRIVATE_DNS_MODE_OFF, "ignored.example.com");
    }

    private ConnectivityService.Dependencies makeDependencies() {
        final MockableSystemProperties systemProperties = spy(new MockableSystemProperties());
        when(systemProperties.getInt("net.tcp.default_init_rwnd", 0)).thenReturn(0);
        when(systemProperties.getBoolean("ro.radio.noril", false)).thenReturn(false);

        final ConnectivityService.Dependencies deps = mock(ConnectivityService.Dependencies.class);
        doReturn(mCsHandlerThread).when(deps).makeHandlerThread();
        doReturn(new TestNetIdManager()).when(deps).makeNetIdManager();
        doReturn(mNetworkStack).when(deps).getNetworkStack();
        doReturn(systemProperties).when(deps).getSystemProperties();
        doReturn(mock(ProxyTracker.class)).when(deps).makeProxyTracker(any(), any());
        doReturn(mMetricsService).when(deps).getMetricsLogger();
        doReturn(true).when(deps).queryUserAccess(anyInt(), anyInt());
        doReturn(mIpConnectivityMetrics).when(deps).getIpConnectivityMetrics();
        doReturn(mBatteryStatsService).when(deps).getBatteryStatsService();
        doReturn(true).when(deps).hasService(Context.ETHERNET_SERVICE);
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
        FakeSettingsProvider.clearSettingsProvider();

        mCsHandlerThread.quitSafely();
        mAlarmManagerThread.quitSafely();
    }

    private void mockDefaultPackages() throws Exception {
        final String testPackageName = mContext.getPackageName();
        final PackageInfo testPackageInfo = mContext.getPackageManager().getPackageInfo(
                testPackageName, PackageManager.GET_PERMISSIONS);
        when(mPackageManager.getPackagesForUid(Binder.getCallingUid())).thenReturn(
                new String[] {testPackageName});
        when(mPackageManager.getPackageInfoAsUser(eq(testPackageName), anyInt(),
                eq(UserHandle.getCallingUserId()))).thenReturn(testPackageInfo);

        when(mPackageManager.getInstalledPackages(eq(GET_PERMISSIONS | MATCH_ANY_USER))).thenReturn(
                Arrays.asList(new PackageInfo[] {
                        buildPackageInfo(/* SYSTEM */ false, APP1_UID),
                        buildPackageInfo(/* SYSTEM */ false, APP2_UID),
                        buildPackageInfo(/* SYSTEM */ false, VPN_UID)
                }));
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
                assertEquals(mCm.getActiveNetwork(), mWiFiNetworkAgent.getNetwork());
                break;
            case TRANSPORT_CELLULAR:
                assertEquals(mCm.getActiveNetwork(), mCellNetworkAgent.getNetwork());
                break;
            default:
                break;
        }
        // Test getNetworkInfo(Network)
        assertNotNull(mCm.getNetworkInfo(mCm.getActiveNetwork()));
        assertEquals(transportToLegacyType(transport),
                mCm.getNetworkInfo(mCm.getActiveNetwork()).getType());
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
     * Return a ConditionVariable that opens when {@code count} numbers of CONNECTIVITY_ACTION
     * broadcasts are received.
     */
    private ConditionVariable registerConnectivityBroadcast(final int count) {
        return registerConnectivityBroadcastThat(count, intent -> true);
    }

    private ConditionVariable registerConnectivityBroadcastThat(final int count,
            @NonNull final Predicate<Intent> filter) {
        final ConditionVariable cv = new ConditionVariable();
        final IntentFilter intentFilter = new IntentFilter(CONNECTIVITY_ACTION);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
                    private int remaining = count;
                    public void onReceive(Context context, Intent intent) {
                        if (!filter.test(intent)) return;
                        if (--remaining == 0) {
                            cv.open();
                            mServiceContext.unregisterReceiver(this);
                        }
                    }
                };
        mServiceContext.registerReceiver(receiver, intentFilter);
        return cv;
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
        final ConditionVariable cv1 = registerConnectivityBroadcastThat(1,
                intent -> intent.getIntExtra(EXTRA_NETWORK_TYPE, -1) == TYPE_MOBILE);
        mCellNetworkAgent.connect(true);
        waitFor(cv1);

        // Build legacy request for SUPL.
        final NetworkCapabilities legacyCaps = new NetworkCapabilities();
        legacyCaps.addTransportType(TRANSPORT_CELLULAR);
        legacyCaps.addCapability(NET_CAPABILITY_SUPL);
        final NetworkRequest legacyRequest = new NetworkRequest(legacyCaps, TYPE_MOBILE_SUPL,
                ConnectivityManager.REQUEST_ID_UNSET, NetworkRequest.Type.REQUEST);

        // File request, withdraw it and make sure no broadcast is sent
        final ConditionVariable cv2 = registerConnectivityBroadcast(1);
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.requestNetwork(legacyRequest, callback);
        callback.expectCallback(CallbackEntry.AVAILABLE, mCellNetworkAgent);
        mCm.unregisterNetworkCallback(callback);
        assertFalse(cv2.block(800)); // 800ms long enough to at least flake if this is sent
        // As the broadcast did not fire, the receiver was not unregistered. Do this now.
        mServiceContext.clearRegisteredReceivers();

        // Disconnect the network and expect mobile disconnected broadcast. Use a small hack to
        // check that has been sent.
        final AtomicBoolean vanillaAction = new AtomicBoolean(false);
        final ConditionVariable cv3 = registerConnectivityBroadcastThat(1, intent -> {
            if (intent.getAction().equals(CONNECTIVITY_ACTION)) {
                vanillaAction.set(true);
            }
            return !((NetworkInfo) intent.getExtra(EXTRA_NETWORK_INFO, -1)).isConnected();
        });
        mCellNetworkAgent.disconnect();
        waitFor(cv3);
        assertTrue(vanillaAction.get());
    }

    @Test
    public void testLingering() throws Exception {
        verifyNoNetwork();
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        assertNull(mCm.getActiveNetworkInfo());
        assertNull(mCm.getActiveNetwork());
        // Test bringing up validated cellular.
        ConditionVariable cv = registerConnectivityBroadcast(1);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        assertLength(2, mCm.getAllNetworks());
        assertTrue(mCm.getAllNetworks()[0].equals(mCm.getActiveNetwork()) ||
                mCm.getAllNetworks()[1].equals(mCm.getActiveNetwork()));
        assertTrue(mCm.getAllNetworks()[0].equals(mWiFiNetworkAgent.getNetwork()) ||
                mCm.getAllNetworks()[1].equals(mWiFiNetworkAgent.getNetwork()));
        // Test bringing up validated WiFi.
        cv = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
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
        cv = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
        verifyNoNetwork();
    }

    @Test
    public void testValidatedCellularOutscoresUnvalidatedWiFi() throws Exception {
        // Test bringing up unvalidated WiFi
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        ConditionVariable cv = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
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
        cv = registerConnectivityBroadcast(2);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test cellular disconnect.
        cv = registerConnectivityBroadcast(2);
        mCellNetworkAgent.disconnect();
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi disconnect.
        cv = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
        verifyNoNetwork();
    }

    @Test
    public void testUnvalidatedWifiOutscoresUnvalidatedCellular() throws Exception {
        // Test bringing up unvalidated cellular.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        ConditionVariable cv = registerConnectivityBroadcast(1);
        mCellNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test bringing up unvalidated WiFi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        cv = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi disconnect.
        cv = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test cellular disconnect.
        cv = registerConnectivityBroadcast(1);
        mCellNetworkAgent.disconnect();
        waitFor(cv);
        verifyNoNetwork();
    }

    @Test
    public void testUnlingeringDoesNotValidate() throws Exception {
        // Test bringing up unvalidated WiFi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        ConditionVariable cv = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        // Test bringing up validated cellular.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        cv = registerConnectivityBroadcast(2);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        // Test cellular disconnect.
        cv = registerConnectivityBroadcast(2);
        mCellNetworkAgent.disconnect();
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Unlingering a network should not cause it to be marked as validated.
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
    }

    @Test
    public void testCellularOutscoresWeakWifi() throws Exception {
        // Test bringing up validated cellular.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        ConditionVariable cv = registerConnectivityBroadcast(1);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        cv = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test WiFi getting really weak.
        cv = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.adjustScore(-11);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test WiFi restoring signal strength.
        cv = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.adjustScore(11);
        waitFor(cv);
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
        final ConditionVariable cv = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
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
        ConditionVariable cv = registerConnectivityBroadcast(1);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Test bringing up validated WiFi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        cv = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Reevaluate WiFi (it'll instantly fail DNS).
        cv = registerConnectivityBroadcast(2);
        assertTrue(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        mCm.reportBadNetwork(mWiFiNetworkAgent.getNetwork());
        // Should quickly fall back to Cellular.
        waitFor(cv);
        assertFalse(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Reevaluate cellular (it'll instantly fail DNS).
        cv = registerConnectivityBroadcast(2);
        assertTrue(mCm.getNetworkCapabilities(mCellNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        mCm.reportBadNetwork(mCellNetworkAgent.getNetwork());
        // Should quickly fall back to WiFi.
        waitFor(cv);
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
        ConditionVariable cv = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent.connect(false);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        // Test bringing up validated cellular.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        cv = registerConnectivityBroadcast(2);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_CELLULAR);
        // Reevaluate cellular (it'll instantly fail DNS).
        cv = registerConnectivityBroadcast(2);
        assertTrue(mCm.getNetworkCapabilities(mCellNetworkAgent.getNetwork()).hasCapability(
                NET_CAPABILITY_VALIDATED));
        mCm.reportBadNetwork(mCellNetworkAgent.getNetwork());
        // Should quickly fall back to WiFi.
        waitFor(cv);
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
        ConditionVariable cv = registerConnectivityBroadcast(1);
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(false);
        genericNetworkCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        cellNetworkCallback.expectAvailableCallbacksUnvalidated(mCellNetworkAgent);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        waitFor(cv);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        // This should not trigger spurious onAvailable() callbacks, b/21762680.
        mCellNetworkAgent.adjustScore(-1);
        waitForIdle();
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);
        assertEquals(mCellNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        cv = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        genericNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        wifiNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertEquals(mWiFiNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        waitFor(cv);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        cv = registerConnectivityBroadcast(2);
        mWiFiNetworkAgent.disconnect();
        genericNetworkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        wifiNetworkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        cellNetworkCallback.assertNoCallback();
        waitFor(cv);
        assertNoCallbacks(genericNetworkCallback, wifiNetworkCallback, cellNetworkCallback);

        cv = registerConnectivityBroadcast(1);
        mCellNetworkAgent.disconnect();
        genericNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        cellNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        waitFor(cv);
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
        mWiFiNetworkAgent.notifyCaptivePortalDataChanged(capportData);
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
        final String testPackageName = mContext.getPackageName();
        when(mPackageManager.getPackageInfo(eq(testPackageName), eq(GET_PERMISSIONS)))
                .thenReturn(buildPackageInfo(true, uid));
        mService.mPermissionMonitor.onPackageAdded(testPackageName, uid);
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

    private int[] makeIntArray(final int size, final int value) {
        final int[] array = new int[size];
        Arrays.fill(array, value);
        return array;
    }

    private void tryNetworkFactoryRequests(int capability) throws Exception {
        // Verify NOT_RESTRICTED is set appropriately
        final NetworkCapabilities nc = new NetworkRequest.Builder().addCapability(capability)
                .build().networkCapabilities;
        if (capability == NET_CAPABILITY_CBS || capability == NET_CAPABILITY_DUN ||
                capability == NET_CAPABILITY_EIMS || capability == NET_CAPABILITY_FOTA ||
                capability == NET_CAPABILITY_IA || capability == NET_CAPABILITY_IMS ||
                capability == NET_CAPABILITY_RCS || capability == NET_CAPABILITY_XCAP) {
            assertFalse(nc.hasCapability(NET_CAPABILITY_NOT_RESTRICTED));
        } else {
            assertTrue(nc.hasCapability(NET_CAPABILITY_NOT_RESTRICTED));
        }

        NetworkCapabilities filter = new NetworkCapabilities();
        filter.addCapability(capability);
        final HandlerThread handlerThread = new HandlerThread("testNetworkFactoryRequests");
        handlerThread.start();
        final MockNetworkFactory testFactory = new MockNetworkFactory(handlerThread.getLooper(),
                mServiceContext, "testFactory", filter);
        testFactory.setScoreFilter(40);
        ConditionVariable cv = testFactory.getNetworkStartedCV();
        testFactory.expectAddRequestsWithScores(0);
        testFactory.register();
        testFactory.waitForNetworkRequests(1);
        int expectedRequestCount = 1;
        NetworkCallback networkCallback = null;
        // For non-INTERNET capabilities we cannot rely on the default request being present, so
        // add one.
        if (capability != NET_CAPABILITY_INTERNET) {
            assertFalse(testFactory.getMyStartRequested());
            NetworkRequest request = new NetworkRequest.Builder().addCapability(capability).build();
            networkCallback = new NetworkCallback();
            testFactory.expectAddRequestsWithScores(0);  // New request
            mCm.requestNetwork(request, networkCallback);
            expectedRequestCount++;
            testFactory.waitForNetworkRequests(expectedRequestCount);
        }
        waitFor(cv);
        assertEquals(expectedRequestCount, testFactory.getMyRequestCount());
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
        testFactory.expectAddRequestsWithScores(makeIntArray(expectedRequestCount, 50));
        testAgent.connect(false);
        testAgent.addCapability(capability);
        waitFor(cv);
        testFactory.waitForNetworkRequests(expectedRequestCount);
        assertFalse(testFactory.getMyStartRequested());

        // Bring in a bunch of requests.
        testFactory.expectAddRequestsWithScores(makeIntArray(10, 50));
        assertEquals(expectedRequestCount, testFactory.getMyRequestCount());
        ConnectivityManager.NetworkCallback[] networkCallbacks =
                new ConnectivityManager.NetworkCallback[10];
        for (int i = 0; i< networkCallbacks.length; i++) {
            networkCallbacks[i] = new ConnectivityManager.NetworkCallback();
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.addCapability(capability);
            mCm.requestNetwork(builder.build(), networkCallbacks[i]);
        }
        testFactory.waitForNetworkRequests(10 + expectedRequestCount);
        assertFalse(testFactory.getMyStartRequested());

        // Remove the requests.
        testFactory.expectRemoveRequests(10);
        for (int i = 0; i < networkCallbacks.length; i++) {
            mCm.unregisterNetworkCallback(networkCallbacks[i]);
        }
        testFactory.waitForNetworkRequests(expectedRequestCount);
        assertFalse(testFactory.getMyStartRequested());

        // Drop the higher scored network.
        cv = testFactory.getNetworkStartedCV();
        // With the default network disconnecting, the requests are sent with score 0 to factories.
        testFactory.expectAddRequestsWithScores(makeIntArray(expectedRequestCount, 0));
        testAgent.disconnect();
        waitFor(cv);
        testFactory.waitForNetworkRequests(expectedRequestCount);
        assertEquals(expectedRequestCount, testFactory.getMyRequestCount());
        assertTrue(testFactory.getMyStartRequested());

        testFactory.unregister();
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
        tryNetworkFactoryRequests(NET_CAPABILITY_EIMS);
        tryNetworkFactoryRequests(NET_CAPABILITY_NOT_METERED);
        tryNetworkFactoryRequests(NET_CAPABILITY_INTERNET);
        tryNetworkFactoryRequests(NET_CAPABILITY_TRUSTED);
        tryNetworkFactoryRequests(NET_CAPABILITY_NOT_VPN);
        // Skipping VALIDATED and CAPTIVE_PORTAL as they're disallowed.
    }

    @Test
    public void testNoMutableNetworkRequests() throws Exception {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, new Intent("a"), 0);
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
        final ConditionVariable cv = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
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
        ConditionVariable cv = registerConnectivityBroadcast(1);
        mCellNetworkAgent.connect(false);
        waitFor(cv);
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
        // Expect no notification to be shown when captive portal disappears by itself
        verify(mNotificationManager, never()).notifyAsUser(
                anyString(), eq(NotificationType.LOGGED_IN.eventId), any(), any());

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
        verify(mNotificationManager, times(1)).notifyAsUser(anyString(),
                eq(NotificationType.LOGGED_IN.eventId), any(), eq(UserHandle.ALL));

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

        mWiFiNetworkAgent.notifyCaptivePortalDataChanged(testData);

        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> testData.equals(lp.getCaptivePortalData()));

        final LinkProperties newLps = new LinkProperties();
        newLps.setMtu(1234);
        mWiFiNetworkAgent.sendLinkProperties(newLps);
        // CaptivePortalData is not lost and unchanged when LPs are received from the NetworkAgent
        captivePortalCallback.expectLinkPropertiesThat(mWiFiNetworkAgent,
                lp -> testData.equals(lp.getCaptivePortalData()) && lp.getMtu() == 1234);
    }

    private NetworkRequest.Builder newWifiRequestBuilder() {
        return new NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI);
    }

    /**
     * Verify request matching behavior with network specifiers.
     *
     * Note: this test is somewhat problematic since it involves removing capabilities from
     * agents - i.e. agents rejecting requests which they previously accepted. This is flagged
     * as a WTF bug in
     * {@link ConnectivityService#mixInCapabilities(NetworkAgentInfo, NetworkCapabilities)} but
     * does work.
     */
    @Test
    public void testNetworkSpecifier() throws Exception {
        // A NetworkSpecifier subclass that matches all networks but must not be visible to apps.
        class ConfidentialMatchAllNetworkSpecifier extends NetworkSpecifier implements
                Parcelable {
            @Override
            public boolean satisfiedBy(NetworkSpecifier other) {
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
            public boolean satisfiedBy(NetworkSpecifier other) {
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
        cEmpty1.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        cEmpty2.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        cEmpty3.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        cEmpty4.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        assertNoCallbacks(cFoo, cBar);

        mWiFiNetworkAgent.setNetworkSpecifier(nsFoo);
        cFoo.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        for (TestNetworkCallback c: emptyCallbacks) {
            c.expectCapabilitiesThat(mWiFiNetworkAgent,
                    (caps) -> caps.getNetworkSpecifier().equals(nsFoo));
        }
        cFoo.expectCapabilitiesThat(mWiFiNetworkAgent,
                (caps) -> caps.getNetworkSpecifier().equals(nsFoo));
        assertEquals(nsFoo,
                mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).getNetworkSpecifier());
        cFoo.assertNoCallback();

        mWiFiNetworkAgent.setNetworkSpecifier(nsBar);
        cFoo.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        cBar.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        for (TestNetworkCallback c: emptyCallbacks) {
            c.expectCapabilitiesThat(mWiFiNetworkAgent,
                    (caps) -> caps.getNetworkSpecifier().equals(nsBar));
        }
        cBar.expectCapabilitiesThat(mWiFiNetworkAgent,
                (caps) -> caps.getNetworkSpecifier().equals(nsBar));
        assertEquals(nsBar,
                mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).getNetworkSpecifier());
        cBar.assertNoCallback();

        mWiFiNetworkAgent.setNetworkSpecifier(new ConfidentialMatchAllNetworkSpecifier());
        cFoo.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        for (TestNetworkCallback c : emptyCallbacks) {
            c.expectCapabilitiesThat(mWiFiNetworkAgent,
                    (caps) -> caps.getNetworkSpecifier() == null);
        }
        cFoo.expectCapabilitiesThat(mWiFiNetworkAgent,
                (caps) -> caps.getNetworkSpecifier() == null);
        cBar.expectCapabilitiesThat(mWiFiNetworkAgent,
                (caps) -> caps.getNetworkSpecifier() == null);
        assertNull(
                mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()).getNetworkSpecifier());
        cFoo.assertNoCallback();
        cBar.assertNoCallback();

        mWiFiNetworkAgent.setNetworkSpecifier(null);
        cFoo.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        cBar.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        for (TestNetworkCallback c: emptyCallbacks) {
            c.expectCallback(CallbackEntry.NETWORK_CAPS_UPDATED, mWiFiNetworkAgent);
        }

        assertNoCallbacks(cEmpty1, cEmpty2, cEmpty3, cEmpty4, cFoo, cBar);
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
            mService.requestNetwork(networkCapabilities, null, 0, null,
                    ConnectivityManager.TYPE_WIFI, mContext.getPackageName());
        });

        class NonParcelableSpecifier extends NetworkSpecifier {
            public boolean satisfiedBy(NetworkSpecifier other) { return false; }
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
                        mServiceContext, 0, new Intent(), 0)));

        // Requesting a Network with signal strength should get IllegalArgumentException.
        assertThrows(IllegalArgumentException.class, () ->
                mCm.requestNetwork(r, new NetworkCallback()));

        assertThrows(IllegalArgumentException.class, () ->
                mCm.requestNetwork(r, PendingIntent.getService(
                        mServiceContext, 0, new Intent(), 0)));
    }

    @Test
    public void testRegisterDefaultNetworkCallback() throws Exception {
        final TestNetworkCallback defaultNetworkCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultNetworkCallback);
        defaultNetworkCallback.assertNoCallback();

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
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring up wifi and expect CALLBACK_AVAILABLE.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        cellNetworkCallback.assertNoCallback();
        defaultNetworkCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring down cell. Expect no default network callback, since it wasn't the default.
        mCellNetworkAgent.disconnect();
        cellNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        defaultNetworkCallback.assertNoCallback();
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring up cell. Expect no default network callback, since it won't be the default.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        defaultNetworkCallback.assertNoCallback();
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        // Bring down wifi. Expect the default network callback to notified of LOST wifi
        // followed by AVAILABLE cell.
        mWiFiNetworkAgent.disconnect();
        cellNetworkCallback.assertNoCallback();
        defaultNetworkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        defaultNetworkCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        mCellNetworkAgent.disconnect();
        cellNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        defaultNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        waitForIdle();
        assertEquals(null, mCm.getActiveNetwork());

        final int uid = Process.myUid();
        final TestNetworkAgentWrapper
                vpnNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true);
        mMockVpn.connect();
        defaultNetworkCallback.expectAvailableThenValidatedCallbacks(vpnNetworkAgent);
        assertEquals(defaultNetworkCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        vpnNetworkAgent.disconnect();
        defaultNetworkCallback.expectCallback(CallbackEntry.LOST, vpnNetworkAgent);
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
        // Create a background request. We can't do this ourselves because ConnectivityService
        // doesn't have an API for it. So just turn on mobile data always on.
        setAlwaysOnNetworks(true);
        grantUsingBackgroundNetworksPermissionForUid(Binder.getCallingUid());
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
                .addCapability(NET_CAPABILITY_INTERNET);
        final MockNetworkFactory testFactory = new MockNetworkFactory(handlerThread.getLooper(),
                mServiceContext, "testFactory", filter);
        testFactory.setScoreFilter(40);

        // Register the factory and expect it to start looking for a network.
        testFactory.expectAddRequestsWithScores(0);  // Score 0 as the request is not served yet.
        testFactory.register();
        testFactory.waitForNetworkRequests(1);
        assertTrue(testFactory.getMyStartRequested());

        // Bring up wifi. The factory stops looking for a network.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        // Score 60 - 40 penalty for not validated yet, then 60 when it validates
        testFactory.expectAddRequestsWithScores(20, 60);
        mWiFiNetworkAgent.connect(true);
        testFactory.waitForRequests();
        assertFalse(testFactory.getMyStartRequested());

        ContentResolver cr = mServiceContext.getContentResolver();

        // Turn on mobile data always on. The factory starts looking again.
        testFactory.expectAddRequestsWithScores(0);  // Always on requests comes up with score 0
        setAlwaysOnNetworks(true);
        testFactory.waitForNetworkRequests(2);
        assertTrue(testFactory.getMyStartRequested());

        // Bring up cell data and check that the factory stops looking.
        assertLength(1, mCm.getAllNetworks());
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        testFactory.expectAddRequestsWithScores(10, 50);  // Unvalidated, then validated
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        testFactory.waitForNetworkRequests(2);
        assertFalse(testFactory.getMyStartRequested());  // Because the cell network outscores us.

        // Check that cell data stays up.
        waitForIdle();
        verifyActiveNetwork(TRANSPORT_WIFI);
        assertLength(2, mCm.getAllNetworks());

        // Turn off mobile data always on and expect the request to disappear...
        testFactory.expectRemoveRequests(1);
        setAlwaysOnNetworks(false);
        testFactory.waitForNetworkRequests(1);

        // ...  and cell data to be torn down.
        cellNetworkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        assertLength(1, mCm.getAllNetworks());

        testFactory.unregister();
        mCm.unregisterNetworkCallback(cellNetworkCallback);
        handlerThread.quit();
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
        networkCallback.expectCallback(CallbackEntry.UNAVAILABLE, null);

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
                mServiceContext, "testFactory", filter);
        testFactory.setScoreFilter(40);

        // Register the factory and expect it to receive the default request.
        testFactory.expectAddRequestsWithScores(0);
        testFactory.register();
        SparseArray<NetworkRequest> requests = testFactory.waitForNetworkRequests(1);

        assertEquals(1, requests.size()); // have 1 request at this point
        int origRequestId = requests.valueAt(0).requestId;

        // Now file the test request and expect it.
        testFactory.expectAddRequestsWithScores(0);
        mCm.requestNetwork(nr, networkCallback);
        requests = testFactory.waitForNetworkRequests(2); // have 2 requests at this point

        int newRequestId = 0;
        for (int i = 0; i < requests.size(); ++i) {
            if (requests.valueAt(i).requestId != origRequestId) {
                newRequestId = requests.valueAt(i).requestId;
                break;
            }
        }

        testFactory.expectRemoveRequests(1);
        if (preUnregister) {
            mCm.unregisterNetworkCallback(networkCallback);

            // Simulate the factory releasing the request as unfulfillable: no-op since
            // the callback has already been unregistered (but a test that no exceptions are
            // thrown).
            testFactory.triggerUnfulfillable(requests.get(newRequestId));
        } else {
            // Simulate the factory releasing the request as unfulfillable and expect onUnavailable!
            testFactory.triggerUnfulfillable(requests.get(newRequestId));

            networkCallback.expectCallback(CallbackEntry.UNAVAILABLE, null);
            testFactory.waitForRequests();

            // unregister network callback - a no-op (since already freed by the
            // on-unavailable), but should not fail or throw exceptions.
            mCm.unregisterNetworkCallback(networkCallback);
        }

        testFactory.unregister();
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
        // Ensure the network is disconnected before we do anything.
        if (mWiFiNetworkAgent != null) {
            assertNull(mCm.getNetworkCapabilities(mWiFiNetworkAgent.getNetwork()));
        }

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        ConditionVariable cv = registerConnectivityBroadcast(1);
        mWiFiNetworkAgent.connect(true);
        waitFor(cv);
        verifyActiveNetwork(TRANSPORT_WIFI);
        mWiFiNetworkAgent.sendLinkProperties(lp);
        waitForIdle();
        return mWiFiNetworkAgent.getNetwork();
    }

    @Test
    @FlakyTest(bugId = 140305589)
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

        // Sanity check before testing started keepalive.
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
        ConditionVariable cv = registerConnectivityBroadcast(3);  // cell down, wifi up, wifi down.
        mCellNetworkAgent.disconnect();
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);

        // Pinning takes effect even if the pinned network is the default when the pin is set...
        TestNetworkPinner.pin(mServiceContext, wifiRequest);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);
        assertTrue(TestNetworkPinner.awaitPin(100));
        assertPinnedToWifiWithWifiDefault();

        // ... and is maintained even when that network is no longer the default.
        cv = registerConnectivityBroadcast(1);
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mCellNetworkAgent.connect(true);
        waitFor(cv);
        assertPinnedToWifiWithCellDefault();
    }

    @Test
    public void testNetworkCallbackMaximum() {
        // We can only have 99 callbacks, because MultipathPolicyTracker is
        // already one of them.
        final int MAX_REQUESTS = 99;
        final int CALLBACKS = 89;
        final int INTENTS = 10;
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
            PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, new Intent("a" + j), 0);
            mCm.requestNetwork(networkRequest, pi);
            registered.add(pi);
        }
        while (j++ < INTENTS) {
            PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, new Intent("b" + j), 0);
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
                        PendingIntent.getBroadcast(mContext, 0, new Intent("c"), 0))
        );
        assertThrows(TooManyRequestsException.class, () ->
                mCm.registerNetworkCallback(networkRequest,
                        PendingIntent.getBroadcast(mContext, 0, new Intent("d"), 0))
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
            PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(mContext, 0, new Intent("e" + i), 0);
            mCm.requestNetwork(networkRequest, pendingIntent);
            mCm.unregisterNetworkCallback(pendingIntent);
        }
        waitForIdle();

        for (int i = 0; i < MAX_REQUESTS; i++) {
            PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(mContext, 0, new Intent("f" + i), 0);
            mCm.registerNetworkCallback(networkRequest, pendingIntent);
            mCm.unregisterNetworkCallback(pendingIntent);
        }
    }

    @Test
    public void testNetworkInfoOfTypeNone() throws Exception {
        ConditionVariable broadcastCV = registerConnectivityBroadcast(1);

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
        if (broadcastCV.block(10)) {
            fail("expected no broadcast, but got CONNECTIVITY_ACTION broadcast");
        }
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
                NetworkUtils.numericToInetAddress("192.168.12.1"), lp.getInterfaceName());
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

    @Test
    public void testStatsIfacesChanged() throws Exception {
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);

        Network[] onlyCell = new Network[] {mCellNetworkAgent.getNetwork()};
        Network[] onlyWifi = new Network[] {mWiFiNetworkAgent.getNetwork()};

        LinkProperties cellLp = new LinkProperties();
        cellLp.setInterfaceName(MOBILE_IFNAME);
        LinkProperties wifiLp = new LinkProperties();
        wifiLp.setInterfaceName(WIFI_IFNAME);

        // Simple connection should have updated ifaces
        mCellNetworkAgent.connect(false);
        mCellNetworkAgent.sendLinkProperties(cellLp);
        waitForIdle();
        verify(mStatsService, atLeastOnce())
                .forceUpdateIfaces(eq(onlyCell), any(NetworkState[].class), eq(MOBILE_IFNAME),
                        eq(new VpnInfo[0]));
        reset(mStatsService);

        // Default network switch should update ifaces.
        mWiFiNetworkAgent.connect(false);
        mWiFiNetworkAgent.sendLinkProperties(wifiLp);
        waitForIdle();
        assertEquals(wifiLp, mService.getActiveLinkProperties());
        verify(mStatsService, atLeastOnce())
                .forceUpdateIfaces(eq(onlyWifi), any(NetworkState[].class), eq(WIFI_IFNAME),
                        eq(new VpnInfo[0]));
        reset(mStatsService);

        // Disconnect should update ifaces.
        mWiFiNetworkAgent.disconnect();
        waitForIdle();
        verify(mStatsService, atLeastOnce())
                .forceUpdateIfaces(eq(onlyCell), any(NetworkState[].class),
                        eq(MOBILE_IFNAME), eq(new VpnInfo[0]));
        reset(mStatsService);

        // Metered change should update ifaces
        mCellNetworkAgent.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        waitForIdle();
        verify(mStatsService, atLeastOnce())
                .forceUpdateIfaces(eq(onlyCell), any(NetworkState[].class), eq(MOBILE_IFNAME),
                        eq(new VpnInfo[0]));
        reset(mStatsService);

        mCellNetworkAgent.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        waitForIdle();
        verify(mStatsService, atLeastOnce())
                .forceUpdateIfaces(eq(onlyCell), any(NetworkState[].class), eq(MOBILE_IFNAME),
                        eq(new VpnInfo[0]));
        reset(mStatsService);

        // Captive portal change shouldn't update ifaces
        mCellNetworkAgent.addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        waitForIdle();
        verify(mStatsService, never())
                .forceUpdateIfaces(eq(onlyCell), any(NetworkState[].class), eq(MOBILE_IFNAME),
                        eq(new VpnInfo[0]));
        reset(mStatsService);

        // Roaming change should update ifaces
        mCellNetworkAgent.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        waitForIdle();
        verify(mStatsService, atLeastOnce())
                .forceUpdateIfaces(eq(onlyCell), any(NetworkState[].class), eq(MOBILE_IFNAME),
                        eq(new VpnInfo[0]));
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
        verify(mNotificationManager, timeout(TIMEOUT_MS).times(1)).notifyAsUser(anyString(),
                eq(NotificationType.PRIVATE_DNS_BROKEN.eventId), any(), eq(UserHandle.ALL));
        // If private DNS resolution successful, the PRIVATE_DNS_BROKEN notification shouldn't be
        // shown.
        mWiFiNetworkAgent.setNetworkValid(true /* isStrictMode */);
        mWiFiNetworkAgent.mNetworkMonitor.forceReevaluation(Process.myUid());
        waitForIdle();
        verify(mNotificationManager, timeout(TIMEOUT_MS).times(1)).cancelAsUser(anyString(),
                eq(NotificationType.PRIVATE_DNS_BROKEN.eventId), eq(UserHandle.ALL));
        // If private DNS resolution failed again, the PRIVATE_DNS_BROKEN notification should be
        // shown again.
        mWiFiNetworkAgent.setNetworkInvalid(true /* isStrictMode */);
        mWiFiNetworkAgent.mNetworkMonitor.forceReevaluation(Process.myUid());
        waitForIdle();
        verify(mNotificationManager, timeout(TIMEOUT_MS).times(2)).notifyAsUser(anyString(),
                eq(NotificationType.PRIVATE_DNS_BROKEN.eventId), any(), eq(UserHandle.ALL));
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
    public void testVpnNetworkActive() throws Exception {
        final int uid = Process.myUid();

        final TestNetworkCallback genericNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback genericNotVpnNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback wifiNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback vpnNetworkCallback = new TestNetworkCallback();
        final TestNetworkCallback defaultCallback = new TestNetworkCallback();
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
        defaultCallback.assertNoCallback();

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(false);

        genericNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        genericNotVpnNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        wifiNetworkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        vpnNetworkCallback.assertNoCallback();
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        final TestNetworkAgentWrapper
                vpnNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        // VPN networks do not satisfy the default request and are automatically validated
        // by NetworkMonitor
        assertFalse(NetworkMonitorUtils.isValidationRequired(
                vpnNetworkAgent.getNetworkCapabilities()));
        vpnNetworkAgent.setNetworkValid(false /* isStrictMode */);

        vpnNetworkAgent.connect(false);
        mMockVpn.connect();
        mMockVpn.setUnderlyingNetworks(new Network[0]);

        genericNetworkCallback.expectAvailableCallbacksUnvalidated(vpnNetworkAgent);
        genericNotVpnNetworkCallback.assertNoCallback();
        wifiNetworkCallback.assertNoCallback();
        vpnNetworkCallback.expectAvailableCallbacksUnvalidated(vpnNetworkAgent);
        defaultCallback.expectAvailableCallbacksUnvalidated(vpnNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        genericNetworkCallback.expectCallback(CallbackEntry.NETWORK_CAPS_UPDATED, vpnNetworkAgent);
        genericNotVpnNetworkCallback.assertNoCallback();
        vpnNetworkCallback.expectCapabilitiesThat(vpnNetworkAgent, nc -> null == nc.getUids());
        defaultCallback.expectCallback(CallbackEntry.NETWORK_CAPS_UPDATED, vpnNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        ranges.clear();
        vpnNetworkAgent.setUids(ranges);

        genericNetworkCallback.expectCallback(CallbackEntry.LOST, vpnNetworkAgent);
        genericNotVpnNetworkCallback.assertNoCallback();
        wifiNetworkCallback.assertNoCallback();
        vpnNetworkCallback.expectCallback(CallbackEntry.LOST, vpnNetworkAgent);

        // TODO : The default network callback should actually get a LOST call here (also see the
        // comment below for AVAILABLE). This is because ConnectivityService does not look at UID
        // ranges at all when determining whether a network should be rematched. In practice, VPNs
        // can't currently update their UIDs without disconnecting, so this does not matter too
        // much, but that is the reason the test here has to check for an update to the
        // capabilities instead of the expected LOST then AVAILABLE.
        defaultCallback.expectCallback(CallbackEntry.NETWORK_CAPS_UPDATED, vpnNetworkAgent);

        ranges.add(new UidRange(uid, uid));
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.setUids(ranges);

        genericNetworkCallback.expectAvailableCallbacksValidated(vpnNetworkAgent);
        genericNotVpnNetworkCallback.assertNoCallback();
        wifiNetworkCallback.assertNoCallback();
        vpnNetworkCallback.expectAvailableCallbacksValidated(vpnNetworkAgent);
        // TODO : Here like above, AVAILABLE would be correct, but because this can't actually
        // happen outside of the test, ConnectivityService does not rematch callbacks.
        defaultCallback.expectCallback(CallbackEntry.NETWORK_CAPS_UPDATED, vpnNetworkAgent);

        mWiFiNetworkAgent.disconnect();

        genericNetworkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        genericNotVpnNetworkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        wifiNetworkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        vpnNetworkCallback.assertNoCallback();
        defaultCallback.assertNoCallback();

        vpnNetworkAgent.disconnect();

        genericNetworkCallback.expectCallback(CallbackEntry.LOST, vpnNetworkAgent);
        genericNotVpnNetworkCallback.assertNoCallback();
        wifiNetworkCallback.assertNoCallback();
        vpnNetworkCallback.expectCallback(CallbackEntry.LOST, vpnNetworkAgent);
        defaultCallback.expectCallback(CallbackEntry.LOST, vpnNetworkAgent);
        assertEquals(null, mCm.getActiveNetwork());

        mCm.unregisterNetworkCallback(genericNetworkCallback);
        mCm.unregisterNetworkCallback(wifiNetworkCallback);
        mCm.unregisterNetworkCallback(vpnNetworkCallback);
        mCm.unregisterNetworkCallback(defaultCallback);
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

        TestNetworkAgentWrapper
                vpnNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true /* validated */, false /* hasInternet */,
                false /* isStrictMode */);
        mMockVpn.connect();

        defaultCallback.assertNoCallback();
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        vpnNetworkAgent.disconnect();
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

        TestNetworkAgentWrapper
                vpnNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true /* validated */, true /* hasInternet */,
                false /* isStrictMode */);
        mMockVpn.connect();

        defaultCallback.expectAvailableThenValidatedCallbacks(vpnNetworkAgent);
        assertEquals(defaultCallback.getLastAvailableNetwork(), mCm.getActiveNetwork());

        vpnNetworkAgent.disconnect();
        defaultCallback.expectCallback(CallbackEntry.LOST, vpnNetworkAgent);
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
        final int uid = Process.myUid();
        final TestNetworkAgentWrapper
                vpnNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(false /* validated */, true /* hasInternet */,
                false /* isStrictMode */);
        mMockVpn.connect();

        // Even though the VPN is unvalidated, it becomes the default network for our app.
        callback.expectAvailableCallbacksUnvalidated(vpnNetworkAgent);
        // TODO: this looks like a spurious callback.
        callback.expectCallback(CallbackEntry.NETWORK_CAPS_UPDATED, vpnNetworkAgent);
        callback.assertNoCallback();

        assertTrue(vpnNetworkAgent.getScore() > mEthernetNetworkAgent.getScore());
        assertEquals(ConnectivityConstants.VPN_DEFAULT_SCORE, vpnNetworkAgent.getScore());
        assertEquals(vpnNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        NetworkCapabilities nc = mCm.getNetworkCapabilities(vpnNetworkAgent.getNetwork());
        assertFalse(nc.hasCapability(NET_CAPABILITY_VALIDATED));
        assertTrue(nc.hasCapability(NET_CAPABILITY_INTERNET));

        assertFalse(NetworkMonitorUtils.isValidationRequired(
                vpnNetworkAgent.getNetworkCapabilities()));
        assertTrue(NetworkMonitorUtils.isPrivateDnsValidationRequired(
                vpnNetworkAgent.getNetworkCapabilities()));

        // Pretend that the VPN network validates.
        vpnNetworkAgent.setNetworkValid(false /* isStrictMode */);
        vpnNetworkAgent.mNetworkMonitor.forceReevaluation(Process.myUid());
        // Expect to see the validated capability, but no other changes, because the VPN is already
        // the default network for the app.
        callback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, vpnNetworkAgent);
        callback.assertNoCallback();

        vpnNetworkAgent.disconnect();
        callback.expectCallback(CallbackEntry.LOST, vpnNetworkAgent);
        callback.expectAvailableCallbacksValidated(mEthernetNetworkAgent);
    }

    @Test
    public void testVpnSetUnderlyingNetworks() throws Exception {
        final int uid = Process.myUid();

        final TestNetworkCallback vpnNetworkCallback = new TestNetworkCallback();
        final NetworkRequest vpnNetworkRequest = new NetworkRequest.Builder()
                .removeCapability(NET_CAPABILITY_NOT_VPN)
                .addTransportType(TRANSPORT_VPN)
                .build();
        NetworkCapabilities nc;
        mCm.registerNetworkCallback(vpnNetworkRequest, vpnNetworkCallback);
        vpnNetworkCallback.assertNoCallback();

        final TestNetworkAgentWrapper
                vpnNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.connect();
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true /* validated */, false /* hasInternet */,
                false /* isStrictMode */);

        vpnNetworkCallback.expectAvailableThenValidatedCallbacks(vpnNetworkAgent);
        nc = mCm.getNetworkCapabilities(vpnNetworkAgent.getNetwork());
        assertTrue(nc.hasTransport(TRANSPORT_VPN));
        assertFalse(nc.hasTransport(TRANSPORT_CELLULAR));
        assertFalse(nc.hasTransport(TRANSPORT_WIFI));
        // For safety reasons a VPN without underlying networks is considered metered.
        assertFalse(nc.hasCapability(NET_CAPABILITY_NOT_METERED));

        // Connect cell and use it as an underlying network.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);

        mService.setUnderlyingNetworksForVpn(
                new Network[] { mCellNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesThat(vpnNetworkAgent,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED));

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);

        mService.setUnderlyingNetworksForVpn(
                new Network[] { mCellNetworkAgent.getNetwork(), mWiFiNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesThat(vpnNetworkAgent,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED));

        // Don't disconnect, but note the VPN is not using wifi any more.
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mCellNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesThat(vpnNetworkAgent,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED));

        // Use Wifi but not cell. Note the VPN is now unmetered.
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mWiFiNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesThat(vpnNetworkAgent,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && caps.hasCapability(NET_CAPABILITY_NOT_METERED));

        // Use both again.
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mCellNetworkAgent.getNetwork(), mWiFiNetworkAgent.getNetwork() });

        vpnNetworkCallback.expectCapabilitiesThat(vpnNetworkAgent,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED));

        // Disconnect cell. Receive update without even removing the dead network from the
        // underlying networks – it's dead anyway. Not metered any more.
        mCellNetworkAgent.disconnect();
        vpnNetworkCallback.expectCapabilitiesThat(vpnNetworkAgent,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && caps.hasCapability(NET_CAPABILITY_NOT_METERED));

        // Disconnect wifi too. No underlying networks means this is now metered.
        mWiFiNetworkAgent.disconnect();
        vpnNetworkCallback.expectCapabilitiesThat(vpnNetworkAgent,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED));

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

        final TestNetworkAgentWrapper
                vpnNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.connect();
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true /* validated */, false /* hasInternet */,
                false /* isStrictMode */);

        vpnNetworkCallback.expectAvailableThenValidatedCallbacks(vpnNetworkAgent);
        nc = mCm.getNetworkCapabilities(vpnNetworkAgent.getNetwork());
        assertTrue(nc.hasTransport(TRANSPORT_VPN));
        assertFalse(nc.hasTransport(TRANSPORT_CELLULAR));
        assertFalse(nc.hasTransport(TRANSPORT_WIFI));
        // By default, VPN is set to track default network (i.e. its underlying networks is null).
        // In case of no default network, VPN is considered metered.
        assertFalse(nc.hasCapability(NET_CAPABILITY_NOT_METERED));

        // Connect to Cell; Cell is the default network.
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);

        vpnNetworkCallback.expectCapabilitiesThat(vpnNetworkAgent,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED));

        // Connect to WiFi; WiFi is the new default.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);

        vpnNetworkCallback.expectCapabilitiesThat(vpnNetworkAgent,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && caps.hasTransport(TRANSPORT_WIFI)
                && caps.hasCapability(NET_CAPABILITY_NOT_METERED));

        // Disconnect Cell. The default network did not change, so there shouldn't be any changes in
        // the capabilities.
        mCellNetworkAgent.disconnect();

        // Disconnect wifi too. Now we have no default network.
        mWiFiNetworkAgent.disconnect();

        vpnNetworkCallback.expectCapabilitiesThat(vpnNetworkAgent,
                (caps) -> caps.hasTransport(TRANSPORT_VPN)
                && !caps.hasTransport(TRANSPORT_CELLULAR) && !caps.hasTransport(TRANSPORT_WIFI)
                && !caps.hasCapability(NET_CAPABILITY_NOT_METERED));

        mMockVpn.disconnect();
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
        TestNetworkAgentWrapper
                vpnNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        final int uid = Process.myUid();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true);
        mMockVpn.connect();
        waitForIdle();
        // Ensure VPN is now the active network.
        assertEquals(vpnNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // Expect VPN to be metered.
        assertTrue(mCm.isActiveNetworkMetered());

        // Connect WiFi.
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();
        // VPN should still be the active network.
        assertEquals(vpnNetworkAgent.getNetwork(), mCm.getActiveNetwork());

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

        vpnNetworkAgent.disconnect();
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
        TestNetworkAgentWrapper
                vpnNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        final int uid = Process.myUid();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true);
        mMockVpn.connect();
        waitForIdle();
        // Ensure VPN is now the active network.
        assertEquals(vpnNetworkAgent.getNetwork(), mCm.getActiveNetwork());
        // VPN is using Cell
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mCellNetworkAgent.getNetwork() });
        waitForIdle();

        // Expect VPN to be metered.
        assertTrue(mCm.isActiveNetworkMetered());

        // VPN is now using WiFi
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mWiFiNetworkAgent.getNetwork() });
        waitForIdle();

        // Expect VPN to be unmetered
        assertFalse(mCm.isActiveNetworkMetered());

        // VPN is using Cell | WiFi.
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mCellNetworkAgent.getNetwork(), mWiFiNetworkAgent.getNetwork() });
        waitForIdle();

        // Expect VPN to be metered.
        assertTrue(mCm.isActiveNetworkMetered());

        // VPN is using WiFi | Cell.
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mWiFiNetworkAgent.getNetwork(), mCellNetworkAgent.getNetwork() });
        waitForIdle();

        // Order should not matter and VPN should still be metered.
        assertTrue(mCm.isActiveNetworkMetered());

        // VPN is not using any underlying networks.
        mService.setUnderlyingNetworksForVpn(new Network[0]);
        waitForIdle();

        // VPN without underlying networks is treated as metered.
        assertTrue(mCm.isActiveNetworkMetered());

        vpnNetworkAgent.disconnect();
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
        TestNetworkAgentWrapper
                vpnNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        final int uid = Process.myUid();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.setUids(ranges);
        vpnNetworkAgent.connect(true);
        mMockVpn.connectAsAlwaysMetered();
        waitForIdle();
        assertEquals(vpnNetworkAgent.getNetwork(), mCm.getActiveNetwork());

        // VPN is tracking current platform default (WiFi).
        mService.setUnderlyingNetworksForVpn(null);
        waitForIdle();

        // Despite VPN using WiFi (which is unmetered), VPN itself is marked as always metered.
        assertTrue(mCm.isActiveNetworkMetered());

        // VPN explicitly declares WiFi as its underlying network.
        mService.setUnderlyingNetworksForVpn(
                new Network[] { mWiFiNetworkAgent.getNetwork() });
        waitForIdle();

        // Doesn't really matter whether VPN declares its underlying networks explicitly.
        assertTrue(mCm.isActiveNetworkMetered());

        // With WiFi lost, VPN is basically without any underlying networks. And in that case it is
        // anyways suppose to be metered.
        mWiFiNetworkAgent.disconnect();
        waitForIdle();

        assertTrue(mCm.isActiveNetworkMetered());

        vpnNetworkAgent.disconnect();
    }

    @Test
    public void testNetworkBlockedStatus() throws Exception {
        final TestNetworkCallback cellNetworkCallback = new TestNetworkCallback();
        final NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .build();
        mCm.registerNetworkCallback(cellRequest, cellNetworkCallback);

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        cellNetworkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);

        setUidRulesChanged(RULE_REJECT_ALL);
        cellNetworkCallback.expectBlockedStatusCallback(true, mCellNetworkAgent);

        // ConnectivityService should cache it not to invoke the callback again.
        setUidRulesChanged(RULE_REJECT_METERED);
        cellNetworkCallback.assertNoCallback();

        setUidRulesChanged(RULE_NONE);
        cellNetworkCallback.expectBlockedStatusCallback(false, mCellNetworkAgent);

        setUidRulesChanged(RULE_REJECT_METERED);
        cellNetworkCallback.expectBlockedStatusCallback(true, mCellNetworkAgent);

        // Restrict the network based on UID rule and NOT_METERED capability change.
        mCellNetworkAgent.addCapability(NET_CAPABILITY_NOT_METERED);
        cellNetworkCallback.expectCapabilitiesWith(NET_CAPABILITY_NOT_METERED, mCellNetworkAgent);
        cellNetworkCallback.expectBlockedStatusCallback(false, mCellNetworkAgent);
        mCellNetworkAgent.removeCapability(NET_CAPABILITY_NOT_METERED);
        cellNetworkCallback.expectCapabilitiesWithout(NET_CAPABILITY_NOT_METERED,
                mCellNetworkAgent);
        cellNetworkCallback.expectBlockedStatusCallback(true, mCellNetworkAgent);
        setUidRulesChanged(RULE_ALLOW_METERED);
        cellNetworkCallback.expectBlockedStatusCallback(false, mCellNetworkAgent);

        setUidRulesChanged(RULE_NONE);
        cellNetworkCallback.assertNoCallback();

        // Restrict the network based on BackgroundRestricted.
        setRestrictBackgroundChanged(true);
        cellNetworkCallback.expectBlockedStatusCallback(true, mCellNetworkAgent);
        setRestrictBackgroundChanged(true);
        cellNetworkCallback.assertNoCallback();
        setRestrictBackgroundChanged(false);
        cellNetworkCallback.expectBlockedStatusCallback(false, mCellNetworkAgent);
        cellNetworkCallback.assertNoCallback();

        mCm.unregisterNetworkCallback(cellNetworkCallback);
    }

    @Test
    public void testNetworkBlockedStatusBeforeAndAfterConnect() throws Exception {
        final TestNetworkCallback defaultCallback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(defaultCallback);

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

    @Test
    public final void testLoseTrusted() throws Exception {
        final NetworkRequest trustedRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_TRUSTED)
                .build();
        final TestNetworkCallback trustedCallback = new TestNetworkCallback();
        mCm.requestNetwork(trustedRequest, trustedCallback);

        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        trustedCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        verify(mNetworkManagementService).setDefaultNetId(eq(mCellNetworkAgent.getNetwork().netId));
        reset(mNetworkManagementService);

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        mWiFiNetworkAgent.connect(true);
        trustedCallback.expectAvailableDoubleValidatedCallbacks(mWiFiNetworkAgent);
        verify(mNetworkManagementService).setDefaultNetId(eq(mWiFiNetworkAgent.getNetwork().netId));
        reset(mNetworkManagementService);

        mWiFiNetworkAgent.removeCapability(NET_CAPABILITY_TRUSTED);
        trustedCallback.expectAvailableCallbacksValidated(mCellNetworkAgent);
        verify(mNetworkManagementService).setDefaultNetId(eq(mCellNetworkAgent.getNetwork().netId));
        reset(mNetworkManagementService);

        mCellNetworkAgent.removeCapability(NET_CAPABILITY_TRUSTED);
        trustedCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        verify(mNetworkManagementService).clearDefaultNetId();

        mCm.unregisterNetworkCallback(trustedCallback);
    }

    @Ignore // 40%+ flakiness : figure out why and re-enable.
    @Test
    public final void testBatteryStatsNetworkType() throws Exception {
        final LinkProperties cellLp = new LinkProperties();
        cellLp.setInterfaceName("cell0");
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR, cellLp);
        mCellNetworkAgent.connect(true);
        waitForIdle();
        verify(mBatteryStatsService).noteNetworkInterfaceType(cellLp.getInterfaceName(),
                TYPE_MOBILE);
        reset(mBatteryStatsService);

        final LinkProperties wifiLp = new LinkProperties();
        wifiLp.setInterfaceName("wifi0");
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI, wifiLp);
        mWiFiNetworkAgent.connect(true);
        waitForIdle();
        verify(mBatteryStatsService).noteNetworkInterfaceType(wifiLp.getInterfaceName(),
                TYPE_WIFI);
        reset(mBatteryStatsService);

        mCellNetworkAgent.disconnect();

        cellLp.setInterfaceName("wifi0");
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR, cellLp);
        mCellNetworkAgent.connect(true);
        waitForIdle();
        verify(mBatteryStatsService).noteNetworkInterfaceType(cellLp.getInterfaceName(),
                TYPE_MOBILE);
    }

    /**
     * Make simulated InterfaceConfig for Nat464Xlat to query clat lower layer info.
     */
    private InterfaceConfiguration getClatInterfaceConfig(LinkAddress la) {
        InterfaceConfiguration cfg = new InterfaceConfiguration();
        cfg.setHardwareAddress("11:22:33:44:55:66");
        cfg.setLinkAddress(la);
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
        reset(mNetworkManagementService);
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
        verify(mBatteryStatsService).noteNetworkInterfaceType(cellLp.getInterfaceName(),
                TYPE_MOBILE);

        networkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        verify(mMockDnsResolver, times(1)).startPrefix64Discovery(cellNetId);

        // Switching default network updates TCP buffer sizes.
        verifyTcpBufferSizeChange(ConnectivityService.DEFAULT_TCP_BUFFER_SIZES);

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
        verify(mBatteryStatsService, never()).noteNetworkInterfaceType(startsWith("v4-"), anyInt());

        verifyNoMoreInteractions(mMockNetd);
        verifyNoMoreInteractions(mMockDnsResolver);
        reset(mNetworkManagementService);
        reset(mMockNetd);
        reset(mMockDnsResolver);
        when(mNetworkManagementService.getInterfaceConfig(CLAT_PREFIX + MOBILE_IFNAME))
                .thenReturn(getClatInterfaceConfig(myIpv4));

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
            verify(mBatteryStatsService).noteNetworkInterfaceType(stackedLp.getInterfaceName(),
                    TYPE_MOBILE);
        }

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
        expected.setNat64Prefix(kNat64Prefix);
        assertEquals(expected, actualLpAfterIpv4);
        assertEquals(0, actualLpAfterIpv4.getStackedLinks().size());
        assertRoutesRemoved(cellNetId, stackedDefault);

        // The interface removed callback happens but has no effect after stop is called.
        clat.interfaceRemoved(CLAT_PREFIX + MOBILE_IFNAME);
        networkCallback.assertNoCallback();

        verifyNoMoreInteractions(mMockNetd);
        verifyNoMoreInteractions(mMockDnsResolver);
        reset(mNetworkManagementService);
        reset(mMockNetd);
        reset(mMockDnsResolver);
        when(mNetworkManagementService.getInterfaceConfig(CLAT_PREFIX + MOBILE_IFNAME))
                .thenReturn(getClatInterfaceConfig(myIpv4));

        // Stopping prefix discovery causes netd to tell us that the NAT64 prefix is gone.
        mService.mNetdEventCallback.onNat64PrefixEvent(cellNetId, false /* added */,
                kNat64PrefixString, 96);
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
        verifyNoMoreInteractions(mMockNetd);

        // Clean up.
        mCellNetworkAgent.disconnect();
        networkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        networkCallback.assertNoCallback();
        mCm.unregisterNetworkCallback(networkCallback);
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
        reset(mNetworkManagementService);
        mCellNetworkAgent.connect(true);
        networkCallback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        verify(mNetworkManagementService, times(1)).addIdleTimer(eq(MOBILE_IFNAME), anyInt(),
                eq(ConnectivityManager.TYPE_MOBILE));

        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        final LinkProperties wifiLp = new LinkProperties();
        wifiLp.setInterfaceName(WIFI_IFNAME);
        mWiFiNetworkAgent.sendLinkProperties(wifiLp);

        // Network switch
        reset(mNetworkManagementService);
        mWiFiNetworkAgent.connect(true);
        networkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        networkCallback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        networkCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);
        verify(mNetworkManagementService, times(1)).addIdleTimer(eq(WIFI_IFNAME), anyInt(),
                eq(ConnectivityManager.TYPE_WIFI));
        verify(mNetworkManagementService, times(1)).removeIdleTimer(eq(MOBILE_IFNAME));

        // Disconnect wifi and switch back to cell
        reset(mNetworkManagementService);
        mWiFiNetworkAgent.disconnect();
        networkCallback.expectCallback(CallbackEntry.LOST, mWiFiNetworkAgent);
        assertNoCallbacks(networkCallback);
        verify(mNetworkManagementService, times(1)).removeIdleTimer(eq(WIFI_IFNAME));
        verify(mNetworkManagementService, times(1)).addIdleTimer(eq(MOBILE_IFNAME), anyInt(),
                eq(ConnectivityManager.TYPE_MOBILE));

        // reconnect wifi
        mWiFiNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_WIFI);
        wifiLp.setInterfaceName(WIFI_IFNAME);
        mWiFiNetworkAgent.sendLinkProperties(wifiLp);
        mWiFiNetworkAgent.connect(true);
        networkCallback.expectAvailableCallbacksUnvalidated(mWiFiNetworkAgent);
        networkCallback.expectCallback(CallbackEntry.LOSING, mCellNetworkAgent);
        networkCallback.expectCapabilitiesWith(NET_CAPABILITY_VALIDATED, mWiFiNetworkAgent);

        // Disconnect cell
        reset(mNetworkManagementService);
        reset(mMockNetd);
        mCellNetworkAgent.disconnect();
        networkCallback.expectCallback(CallbackEntry.LOST, mCellNetworkAgent);
        // LOST callback is triggered earlier than removing idle timer. Broadcast should also be
        // sent as network being switched. Ensure rule removal for cell will not be triggered
        // unexpectedly before network being removed.
        waitForIdle();
        verify(mNetworkManagementService, times(0)).removeIdleTimer(eq(MOBILE_IFNAME));
        verify(mMockNetd, times(1)).networkDestroy(eq(mCellNetworkAgent.getNetwork().netId));
        verify(mMockDnsResolver, times(1))
                .destroyNetworkCache(eq(mCellNetworkAgent.getNetwork().netId));

        // Disconnect wifi
        ConditionVariable cv = registerConnectivityBroadcast(1);
        reset(mNetworkManagementService);
        mWiFiNetworkAgent.disconnect();
        waitFor(cv);
        verify(mNetworkManagementService, times(1)).removeIdleTimer(eq(WIFI_IFNAME));

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

        // Change link Properties should have updated tcp buffer size.
        LinkProperties lp = new LinkProperties();
        lp.setTcpBufferSizes(testTcpBufferSizes);
        mCellNetworkAgent.sendLinkProperties(lp);
        networkCallback.expectCallback(CallbackEntry.LINK_PROPERTIES_CHANGED, mCellNetworkAgent);
        verifyTcpBufferSizeChange(testTcpBufferSizes);

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

        // Set up a VPN network with a proxy
        final int uid = Process.myUid();
        final TestNetworkAgentWrapper
                vpnNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_VPN);
        final ArraySet<UidRange> ranges = new ArraySet<>();
        ranges.add(new UidRange(uid, uid));
        mMockVpn.setUids(ranges);
        LinkProperties testLinkProperties = new LinkProperties();
        testLinkProperties.setHttpProxy(testProxyInfo);
        vpnNetworkAgent.sendLinkProperties(testLinkProperties);
        waitForIdle();

        // Connect to VPN with proxy
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        vpnNetworkAgent.connect(true);
        mMockVpn.connect();
        waitForIdle();

        // Test that the VPN network returns a proxy, and the WiFi does not.
        assertEquals(testProxyInfo, mService.getProxyForNetwork(vpnNetworkAgent.getNetwork()));
        assertEquals(testProxyInfo, mService.getProxyForNetwork(null));
        assertNull(mService.getProxyForNetwork(mWiFiNetworkAgent.getNetwork()));

        // Test that the VPN network returns no proxy when it is set to null.
        testLinkProperties.setHttpProxy(null);
        vpnNetworkAgent.sendLinkProperties(testLinkProperties);
        waitForIdle();
        assertNull(mService.getProxyForNetwork(vpnNetworkAgent.getNetwork()));
        assertNull(mService.getProxyForNetwork(null));

        // Set WiFi proxy and check that the vpn proxy is still null.
        testLinkProperties.setHttpProxy(testProxyInfo);
        mWiFiNetworkAgent.sendLinkProperties(testLinkProperties);
        waitForIdle();
        assertNull(mService.getProxyForNetwork(null));

        // Disconnect from VPN and check that the active network, which is now the WiFi, has the
        // correct proxy setting.
        vpnNetworkAgent.disconnect();
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
        // The uid range needs to cover the test app so the network is visible to it.
        final Set<UidRange> vpnRange = Collections.singleton(UidRange.createForUser(VPN_USER));
        final TestNetworkAgentWrapper vpnNetworkAgent = establishVpn(lp, VPN_UID, vpnRange);

        // Connected VPN should have interface rules set up. There are two expected invocations,
        // one during VPN uid update, one during VPN LinkProperties update
        ArgumentCaptor<int[]> uidCaptor = ArgumentCaptor.forClass(int[].class);
        verify(mMockNetd, times(2)).firewallAddUidInterfaceRules(eq("tun0"), uidCaptor.capture());
        assertContainsExactly(uidCaptor.getAllValues().get(0), APP1_UID, APP2_UID);
        assertContainsExactly(uidCaptor.getAllValues().get(1), APP1_UID, APP2_UID);
        assertTrue(mService.mPermissionMonitor.getVpnUidRanges("tun0").equals(vpnRange));

        vpnNetworkAgent.disconnect();
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
        lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), null));
        // The uid range needs to cover the test app so the network is visible to it.
        final Set<UidRange> vpnRange = Collections.singleton(UidRange.createForUser(VPN_USER));
        final TestNetworkAgentWrapper vpnNetworkAgent = establishVpn(
                lp, Process.SYSTEM_UID, vpnRange);

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
        final Set<UidRange> vpnRange = Collections.singleton(UidRange.createForUser(VPN_USER));
        final TestNetworkAgentWrapper vpnNetworkAgent = establishVpn(
                lp, Process.SYSTEM_UID, vpnRange);

        // IPv6 unreachable route should not be misinterpreted as a default route
        verify(mMockNetd, never()).firewallAddUidInterfaceRules(any(), any());
    }

    @Test
    public void testVpnHandoverChangesInterfaceFilteringRule() throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("tun0");
        lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), null));
        // The uid range needs to cover the test app so the network is visible to it.
        final Set<UidRange> vpnRange = Collections.singleton(UidRange.createForUser(VPN_USER));
        final TestNetworkAgentWrapper vpnNetworkAgent = establishVpn(lp, VPN_UID, vpnRange);

        // Connected VPN should have interface rules set up. There are two expected invocations,
        // one during VPN uid update, one during VPN LinkProperties update
        ArgumentCaptor<int[]> uidCaptor = ArgumentCaptor.forClass(int[].class);
        verify(mMockNetd, times(2)).firewallAddUidInterfaceRules(eq("tun0"), uidCaptor.capture());
        assertContainsExactly(uidCaptor.getAllValues().get(0), APP1_UID, APP2_UID);
        assertContainsExactly(uidCaptor.getAllValues().get(1), APP1_UID, APP2_UID);

        reset(mMockNetd);
        InOrder inOrder = inOrder(mMockNetd);
        lp.setInterfaceName("tun1");
        vpnNetworkAgent.sendLinkProperties(lp);
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
        vpnNetworkAgent.sendLinkProperties(lp);
        waitForIdle();
        // VPN not routing everything should no longer have interface filtering rules
        verify(mMockNetd).firewallRemoveUidInterfaceRules(uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP1_UID, APP2_UID);

        reset(mMockNetd);
        lp = new LinkProperties();
        lp.setInterfaceName("tun1");
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), null));
        vpnNetworkAgent.sendLinkProperties(lp);
        waitForIdle();
        // Back to routing all IPv6 traffic should have filtering rules
        verify(mMockNetd).firewallAddUidInterfaceRules(eq("tun1"), uidCaptor.capture());
        assertContainsExactly(uidCaptor.getValue(), APP1_UID, APP2_UID);
    }

    @Test
    public void testUidUpdateChangesInterfaceFilteringRule() throws Exception {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName("tun0");
        lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), null));
        // The uid range needs to cover the test app so the network is visible to it.
        final UidRange vpnRange = UidRange.createForUser(VPN_USER);
        final TestNetworkAgentWrapper vpnNetworkAgent = establishVpn(lp, VPN_UID,
                Collections.singleton(vpnRange));

        reset(mMockNetd);
        InOrder inOrder = inOrder(mMockNetd);

        // Update to new range which is old range minus APP1, i.e. only APP2
        final Set<UidRange> newRanges = new HashSet<>(Arrays.asList(
                new UidRange(vpnRange.start, APP1_UID - 1),
                new UidRange(APP1_UID + 1, vpnRange.stop)));
        vpnNetworkAgent.setUids(newRanges);
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

    private void setupLocationPermissions(
            int targetSdk, boolean locationToggle, String op, String perm) throws Exception {
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.targetSdkVersion = targetSdk;
        when(mPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), any()))
                .thenReturn(applicationInfo);

        when(mLocationManager.isLocationEnabledForUser(any())).thenReturn(locationToggle);

        if (op != null) {
            when(mAppOpsManager.noteOp(eq(op), eq(Process.myUid()), eq(mContext.getPackageName())))
                .thenReturn(AppOpsManager.MODE_ALLOWED);
        }

        if (perm != null) {
            mServiceContext.setPermission(perm, PERMISSION_GRANTED);
        }
    }

    private int getOwnerUidNetCapsForCallerPermission(int ownerUid, int callerUid) {
        final NetworkCapabilities netCap = new NetworkCapabilities().setOwnerUid(ownerUid);

        return mService
                .maybeSanitizeLocationInfoForCaller(netCap, callerUid, mContext.getPackageName())
                .getOwnerUid();
    }

    @Test
    public void testMaybeSanitizeLocationInfoForCallerWithFineLocationAfterQ() throws Exception {
        setupLocationPermissions(Build.VERSION_CODES.Q, true, AppOpsManager.OPSTR_FINE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);

        final int myUid = Process.myUid();
        assertEquals(myUid, getOwnerUidNetCapsForCallerPermission(myUid, myUid));
    }

    @Test
    public void testMaybeSanitizeLocationInfoForCallerWithCoarseLocationPreQ() throws Exception {
        setupLocationPermissions(Build.VERSION_CODES.P, true, AppOpsManager.OPSTR_COARSE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);

        final int myUid = Process.myUid();
        assertEquals(myUid, getOwnerUidNetCapsForCallerPermission(myUid, myUid));
    }

    @Test
    public void testMaybeSanitizeLocationInfoForCallerLocationOff() throws Exception {
        // Test that even with fine location permission, and UIDs matching, the UID is sanitized.
        setupLocationPermissions(Build.VERSION_CODES.Q, false, AppOpsManager.OPSTR_FINE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);

        final int myUid = Process.myUid();
        assertEquals(Process.INVALID_UID, getOwnerUidNetCapsForCallerPermission(myUid, myUid));
    }

    @Test
    public void testMaybeSanitizeLocationInfoForCallerWrongUid() throws Exception {
        // Test that even with fine location permission, not being the owner leads to sanitization.
        setupLocationPermissions(Build.VERSION_CODES.Q, true, AppOpsManager.OPSTR_FINE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);

        final int myUid = Process.myUid();
        assertEquals(Process.INVALID_UID, getOwnerUidNetCapsForCallerPermission(myUid + 1, myUid));
    }

    @Test
    public void testMaybeSanitizeLocationInfoForCallerWithCoarseLocationAfterQ() throws Exception {
        // Test that not having fine location permission leads to sanitization.
        setupLocationPermissions(Build.VERSION_CODES.Q, true, AppOpsManager.OPSTR_COARSE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);

        // Test that without the location permission, the owner field is sanitized.
        final int myUid = Process.myUid();
        assertEquals(Process.INVALID_UID, getOwnerUidNetCapsForCallerPermission(myUid, myUid));
    }

    @Test
    public void testMaybeSanitizeLocationInfoForCallerWithoutLocationPermission() throws Exception {
        setupLocationPermissions(Build.VERSION_CODES.Q, true, null /* op */, null /* perm */);

        // Test that without the location permission, the owner field is sanitized.
        final int myUid = Process.myUid();
        assertEquals(Process.INVALID_UID, getOwnerUidNetCapsForCallerPermission(myUid, myUid));
    }

    private void setupConnectionOwnerUid(int vpnOwnerUid, @VpnManager.VpnType int vpnType)
            throws Exception {
        final Set<UidRange> vpnRange = Collections.singleton(UidRange.createForUser(VPN_USER));
        establishVpn(new LinkProperties(), vpnOwnerUid, vpnRange);
        mMockVpn.setVpnType(vpnType);

        final VpnInfo vpnInfo = new VpnInfo();
        vpnInfo.ownerUid = vpnOwnerUid;
        mMockVpn.setVpnInfo(vpnInfo);
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

        try {
            mService.getConnectionOwnerUid(getTestConnectionInfo());
            fail("Expected SecurityException for non-VpnService app");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testGetConnectionOwnerUidVpnServiceWrongUser() throws Exception {
        final int myUid = Process.myUid();
        setupConnectionOwnerUidAsVpnApp(myUid + 1, VpnManager.TYPE_VPN_SERVICE);

        try {
            mService.getConnectionOwnerUid(getTestConnectionInfo());
            fail("Expected SecurityException for non-VpnService app");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testGetConnectionOwnerUidVpnServiceDoesNotThrow() throws Exception {
        final int myUid = Process.myUid();
        setupConnectionOwnerUidAsVpnApp(myUid, VpnManager.TYPE_VPN_SERVICE);

        // TODO: Test the returned UID
        mService.getConnectionOwnerUid(getTestConnectionInfo());
    }

    @Test
    public void testGetConnectionOwnerUidVpnServiceNetworkStackDoesNotThrow() throws Exception {
        final int myUid = Process.myUid();
        setupConnectionOwnerUid(myUid, VpnManager.TYPE_VPN_SERVICE);
        mServiceContext.setPermission(
                android.Manifest.permission.NETWORK_STACK, PERMISSION_GRANTED);

        // TODO: Test the returned UID
        mService.getConnectionOwnerUid(getTestConnectionInfo());
    }

    @Test
    public void testGetConnectionOwnerUidVpnServiceMainlineNetworkStackDoesNotThrow()
            throws Exception {
        final int myUid = Process.myUid();
        setupConnectionOwnerUid(myUid, VpnManager.TYPE_VPN_SERVICE);
        mServiceContext.setPermission(
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK, PERMISSION_GRANTED);

        // TODO: Test the returned UID
        mService.getConnectionOwnerUid(getTestConnectionInfo());
    }

    private TestNetworkAgentWrapper establishVpn(
            LinkProperties lp, int ownerUid, Set<UidRange> vpnRange) throws Exception {
        final TestNetworkAgentWrapper
                vpnNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_VPN, lp);
        vpnNetworkAgent.getNetworkCapabilities().setOwnerUid(ownerUid);
        mMockVpn.setNetworkAgent(vpnNetworkAgent);
        mMockVpn.connect();
        mMockVpn.setUids(vpnRange);
        vpnNetworkAgent.connect(true);
        waitForIdle();
        return vpnNetworkAgent;
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

    private void assertRoutesAdded(int netId, RouteInfo... routes) throws Exception {
        InOrder inOrder = inOrder(mNetworkManagementService);
        for (int i = 0; i < routes.length; i++) {
            inOrder.verify(mNetworkManagementService).addRoute(eq(netId), eq(routes[i]));
        }
    }

    private void assertRoutesRemoved(int netId, RouteInfo... routes) throws Exception {
        InOrder inOrder = inOrder(mNetworkManagementService);
        for (int i = 0; i < routes.length; i++) {
            inOrder.verify(mNetworkManagementService).removeRoute(eq(netId), eq(routes[i]));
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
        HandlerUtilsKt.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        verify(mIBinder).linkToDeath(any(ConnectivityDiagnosticsCallbackInfo.class), anyInt());
        verify(mConnectivityDiagnosticsCallback).asBinder();
        assertTrue(
                mService.mConnectivityDiagnosticsCallbacks.containsKey(
                        mConnectivityDiagnosticsCallback));

        mService.unregisterConnectivityDiagnosticsCallback(mConnectivityDiagnosticsCallback);
        verify(mIBinder, timeout(TIMEOUT_MS))
                .unlinkToDeath(any(ConnectivityDiagnosticsCallbackInfo.class), anyInt());
        assertFalse(
                mService.mConnectivityDiagnosticsCallbacks.containsKey(
                        mConnectivityDiagnosticsCallback));
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
        HandlerUtilsKt.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        verify(mIBinder).linkToDeath(any(ConnectivityDiagnosticsCallbackInfo.class), anyInt());
        verify(mConnectivityDiagnosticsCallback).asBinder();
        assertTrue(
                mService.mConnectivityDiagnosticsCallbacks.containsKey(
                        mConnectivityDiagnosticsCallback));

        // Register the same callback again
        mService.registerConnectivityDiagnosticsCallback(
                mConnectivityDiagnosticsCallback, wifiRequest, mContext.getPackageName());

        // Block until all other events are done processing.
        HandlerUtilsKt.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        assertTrue(
                mService.mConnectivityDiagnosticsCallbacks.containsKey(
                        mConnectivityDiagnosticsCallback));
    }

    @Test
    public void testCheckConnectivityDiagnosticsPermissionsNetworkStack() throws Exception {
        final NetworkAgentInfo naiWithoutUid =
                new NetworkAgentInfo(
                        null, null, null, null, null, new NetworkCapabilities(), 0,
                        mServiceContext, null, null, mService, null, null, null, 0);

        mServiceContext.setPermission(
                android.Manifest.permission.NETWORK_STACK, PERMISSION_GRANTED);
        assertTrue(
                "NetworkStack permission not applied",
                mService.checkConnectivityDiagnosticsPermissions(
                        Process.myPid(), Process.myUid(), naiWithoutUid,
                        mContext.getOpPackageName()));
    }

    @Test
    public void testCheckConnectivityDiagnosticsPermissionsNoLocationPermission() throws Exception {
        final NetworkAgentInfo naiWithoutUid =
                new NetworkAgentInfo(
                        null, null, null, null, null, new NetworkCapabilities(), 0,
                        mServiceContext, null, null, mService, null, null, null, 0);

        mServiceContext.setPermission(android.Manifest.permission.NETWORK_STACK, PERMISSION_DENIED);

        assertFalse(
                "ACCESS_FINE_LOCATION permission necessary for Connectivity Diagnostics",
                mService.checkConnectivityDiagnosticsPermissions(
                        Process.myPid(), Process.myUid(), naiWithoutUid,
                        mContext.getOpPackageName()));
    }

    @Test
    public void testCheckConnectivityDiagnosticsPermissionsActiveVpn() throws Exception {
        final NetworkAgentInfo naiWithoutUid =
                new NetworkAgentInfo(
                        null, null, null, null, null, new NetworkCapabilities(), 0,
                        mServiceContext, null, null, mService, null, null, null, 0);

        setupLocationPermissions(Build.VERSION_CODES.Q, true, AppOpsManager.OPSTR_FINE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);
        mServiceContext.setPermission(android.Manifest.permission.NETWORK_STACK, PERMISSION_DENIED);

        // setUp() calls mockVpn() which adds a VPN with the Test Runner's uid. Configure it to be
        // active
        final VpnInfo info = new VpnInfo();
        info.ownerUid = Process.myUid();
        info.vpnIface = "interface";
        mMockVpn.setVpnInfo(info);
        assertTrue(
                "Active VPN permission not applied",
                mService.checkConnectivityDiagnosticsPermissions(
                        Process.myPid(), Process.myUid(), naiWithoutUid,
                        mContext.getOpPackageName()));
    }

    @Test
    public void testCheckConnectivityDiagnosticsPermissionsNetworkAdministrator() throws Exception {
        final NetworkCapabilities nc = new NetworkCapabilities();
        nc.setAdministratorUids(Arrays.asList(Process.myUid()));
        final NetworkAgentInfo naiWithUid =
                new NetworkAgentInfo(
                        null, null, null, null, null, nc, 0, mServiceContext, null, null,
                        mService, null, null, null, 0);

        setupLocationPermissions(Build.VERSION_CODES.Q, true, AppOpsManager.OPSTR_FINE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION);
        mServiceContext.setPermission(android.Manifest.permission.NETWORK_STACK, PERMISSION_DENIED);

        // Disconnect mock vpn so the uid check on NetworkAgentInfo is tested
        mMockVpn.disconnect();
        assertTrue(
                "NetworkCapabilities administrator uid permission not applied",
                mService.checkConnectivityDiagnosticsPermissions(
                        Process.myPid(), Process.myUid(), naiWithUid, mContext.getOpPackageName()));
    }

    @Test
    public void testCheckConnectivityDiagnosticsPermissionsFails() throws Exception {
        final NetworkCapabilities nc = new NetworkCapabilities();
        nc.setOwnerUid(Process.myUid());
        nc.setAdministratorUids(Arrays.asList(Process.myUid()));
        final NetworkAgentInfo naiWithUid =
                new NetworkAgentInfo(
                        null, null, null, null, null, nc, 0, mServiceContext, null, null,
                        mService, null, null, null, 0);

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

    private void setUpConnectivityDiagnosticsCallback() throws Exception {
        final NetworkRequest request = new NetworkRequest.Builder().build();
        when(mConnectivityDiagnosticsCallback.asBinder()).thenReturn(mIBinder);

        mServiceContext.setPermission(
                android.Manifest.permission.NETWORK_STACK, PERMISSION_GRANTED);

        mService.registerConnectivityDiagnosticsCallback(
                mConnectivityDiagnosticsCallback, request, mContext.getPackageName());

        // Block until all other events are done processing.
        HandlerUtilsKt.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        // Connect the cell agent verify that it notifies TestNetworkCallback that it is available
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerDefaultNetworkCallback(callback);
        mCellNetworkAgent = new TestNetworkAgentWrapper(TRANSPORT_CELLULAR);
        mCellNetworkAgent.connect(true);
        callback.expectAvailableThenValidatedCallbacks(mCellNetworkAgent);
        callback.assertNoCallback();
    }

    @Test
    public void testConnectivityDiagnosticsCallbackOnConnectivityReport() throws Exception {
        setUpConnectivityDiagnosticsCallback();

        // Block until all other events are done processing.
        HandlerUtilsKt.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        // Verify onConnectivityReport fired
        verify(mConnectivityDiagnosticsCallback).onConnectivityReport(
                argThat(report -> {
                    final NetworkCapabilities nc = report.getNetworkCapabilities();
                    return nc.getUids() == null
                            && nc.getAdministratorUids().isEmpty()
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
        HandlerUtilsKt.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        // Verify onDataStallSuspected fired
        verify(mConnectivityDiagnosticsCallback).onDataStallSuspected(
                argThat(report -> {
                    final NetworkCapabilities nc = report.getNetworkCapabilities();
                    return nc.getUids() == null
                            && nc.getAdministratorUids().isEmpty()
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
        HandlerUtilsKt.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        // Verify onNetworkConnectivityReported fired
        verify(mConnectivityDiagnosticsCallback)
                .onNetworkConnectivityReported(eq(n), eq(hasConnectivity));

        final boolean noConnectivity = false;
        mService.reportNetworkConnectivity(n, noConnectivity);

        // Block until all other events are done processing.
        HandlerUtilsKt.waitForIdle(mCsHandlerThread, TIMEOUT_MS);

        // Wait for onNetworkConnectivityReported to fire
        verify(mConnectivityDiagnosticsCallback)
                .onNetworkConnectivityReported(eq(n), eq(noConnectivity));
    }
}
