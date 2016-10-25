/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.NetworkPolicy.LIMIT_DISABLED;
import static android.net.NetworkPolicy.WARNING_DISABLED;
import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND;
import static android.net.NetworkPolicyManager.computeLastCycleBoundary;
import static android.net.NetworkPolicyManager.computeNextCycleBoundary;
import static android.net.TrafficStats.KB_IN_BYTES;
import static android.net.TrafficStats.MB_IN_BYTES;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.Time.TIMEZONE_UTC;

import static com.android.server.net.NetworkPolicyManagerService.TYPE_LIMIT;
import static com.android.server.net.NetworkPolicyManagerService.TYPE_LIMIT_SNOOZED;
import static com.android.server.net.NetworkPolicyManagerService.TYPE_WARNING;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.IUidObserver;
import android.app.Notification;
import android.app.usage.UsageStatsManagerInternal;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkPolicyListener;
import android.net.INetworkStatsService;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkPolicy;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.os.Binder;
import android.os.INetworkManagementService;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.text.format.Time;
import android.util.Log;
import android.util.TrustedTime;

import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.net.NetworkPolicyManagerService;

import libcore.io.IoUtils;

import com.google.common.util.concurrent.AbstractFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests for {@link NetworkPolicyManagerService}.
 */
@RunWith(AndroidJUnit4.class)
public class NetworkPolicyManagerServiceTest {
    private static final String TAG = "NetworkPolicyManagerServiceTest";

    private static final long TEST_START = 1194220800000L;
    private static final String TEST_IFACE = "test0";
    private static final String TEST_SSID = "AndroidAP";

    private static NetworkTemplate sTemplateWifi = NetworkTemplate.buildTemplateWifi(TEST_SSID);

    private BroadcastInterceptingContext mServiceContext;
    private File mPolicyDir;

    private @Mock IActivityManager mActivityManager;
    private @Mock INetworkStatsService mStatsService;
    private @Mock INetworkManagementService mNetworkManager;
    private @Mock TrustedTime mTime;
    private @Mock IConnectivityManager mConnManager;
    private @Mock INotificationManager mNotifManager;
    private @Mock PackageManager mPackageManager;

    private IUidObserver mUidObserver;
    private INetworkManagementEventObserver mNetworkObserver;

    private NetworkPolicyListenerAnswer mPolicyListener;
    private NetworkPolicyManagerService mService;

    private long mStartTime;
    private long mElapsedRealtime;

    private static final int USER_ID = 0;

    private static final int APP_ID_A = android.os.Process.FIRST_APPLICATION_UID + 800;
    private static final int APP_ID_B = android.os.Process.FIRST_APPLICATION_UID + 801;

    private static final int UID_A = UserHandle.getUid(USER_ID, APP_ID_A);
    private static final int UID_B = UserHandle.getUid(USER_ID, APP_ID_B);

    private static final String PKG_NAME_A = "name.is.A,pkg.A";

    @BeforeClass
    public static void registerLocalServices() {
        addLocalServiceMock(PowerManagerInternal.class);
        addLocalServiceMock(DeviceIdleController.LocalService.class);
        final UsageStatsManagerInternal usageStats =
                addLocalServiceMock(UsageStatsManagerInternal.class);
        when(usageStats.getIdleUidsForUser(anyInt())).thenReturn(new int[]{});
    }

    @Before
    public void callSystemReady() throws Exception {
        MockitoAnnotations.initMocks(this);

        final Context context = InstrumentationRegistry.getContext();

        setCurrentTimeMillis(TEST_START);

        // intercept various broadcasts, and pretend that uids have packages
        mServiceContext = new BroadcastInterceptingContext(context) {
            @Override
            public PackageManager getPackageManager() {
                return mPackageManager;
            }

            @Override
            public void startActivity(Intent intent) {
                // ignored
            }
        };

        mPolicyDir = context.getFilesDir();
        if (mPolicyDir.exists()) {
            IoUtils.deleteContents(mPolicyDir);
        }

        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                mUidObserver = (IUidObserver) invocation.getArguments()[0];
                Log.d(TAG, "set mUidObserver to " + mUidObserver);
                return null;
            }
        }).when(mActivityManager).registerUidObserver(any(), anyInt());

        mService = new NetworkPolicyManagerService(mServiceContext, mActivityManager, mStatsService,
                mNetworkManager, mTime, mPolicyDir, true);
        mService.bindConnectivityManager(mConnManager);
        mService.bindNotificationManager(mNotifManager);
        mPolicyListener = new NetworkPolicyListenerAnswer(mService);

        // Sets some common expectations.
        when(mPackageManager.getPackageInfo(anyString(), anyInt())).thenAnswer(
                new Answer<PackageInfo>() {

                    @Override
                    public PackageInfo answer(InvocationOnMock invocation) throws Throwable {
                        final String packageName = (String) invocation.getArguments()[0];
                        final PackageInfo info = new PackageInfo();
                        final Signature signature;
                        if ("android".equals(packageName)) {
                            signature = new Signature("F00D");
                        } else {
                            signature = new Signature("DEAD");
                        }
                        info.signatures = new Signature[] {
                            signature
                        };
                        return info;
                    }
                });
        when(mPackageManager.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(new ApplicationInfo());
        when(mPackageManager.getPackagesForUid(UID_A)).thenReturn(new String[] {PKG_NAME_A});
        when(mNetworkManager.isBandwidthControlEnabled()).thenReturn(true);
        expectCurrentTime();

        // Prepare NPMS.
        mService.systemReady();

        // catch INetworkManagementEventObserver during systemReady()
        ArgumentCaptor<INetworkManagementEventObserver> networkObserver =
              ArgumentCaptor.forClass(INetworkManagementEventObserver.class);
        verify(mNetworkManager).registerObserver(networkObserver.capture());
        mNetworkObserver = networkObserver.getValue();
    }

    @After
    public void removeFiles() throws Exception {
        for (File file : mPolicyDir.listFiles()) {
            file.delete();
        }
    }

    @After
    public void unregisterLocalServices() throws Exception {
        // Registered by NetworkPolicyManagerService's constructor.
        LocalServices.removeServiceForTest(NetworkPolicyManagerInternal.class);
    }

    // NOTE: testPolicyChangeTriggersListener() and testUidForeground() are too superficial, they
    // don't check for side-effects (like calls to NetworkManagementService) neither cover all
    // different modes (Data Saver, Battery Saver, Doze, App idle, etc...).
    // These scenarios are extensively tested on CTS' HostsideRestrictBackgroundNetworkTests.

    @Test
    public void testPolicyChangeTriggersListener() throws Exception {
        mPolicyListener.expect().onRestrictBackgroundBlacklistChanged(anyInt(), anyBoolean());

        mService.setUidPolicy(APP_ID_A, POLICY_NONE);
        mService.setUidPolicy(APP_ID_A, POLICY_REJECT_METERED_BACKGROUND);

        mPolicyListener.waitAndVerify().onRestrictBackgroundBlacklistChanged(APP_ID_A, true);
    }

    @Test
    public void testUidForeground() throws Exception {
        // push all uids into background
        mUidObserver.onUidStateChanged(UID_A, ActivityManager.PROCESS_STATE_SERVICE);
        mUidObserver.onUidStateChanged(UID_B, ActivityManager.PROCESS_STATE_SERVICE);
        assertFalse(mService.isUidForeground(UID_A));
        assertFalse(mService.isUidForeground(UID_B));

        // push one of the uids into foreground
        mUidObserver.onUidStateChanged(UID_A, ActivityManager.PROCESS_STATE_TOP);
        assertTrue(mService.isUidForeground(UID_A));
        assertFalse(mService.isUidForeground(UID_B));

        // and swap another uid into foreground
        mUidObserver.onUidStateChanged(UID_A, ActivityManager.PROCESS_STATE_SERVICE);
        mUidObserver.onUidStateChanged(UID_B, ActivityManager.PROCESS_STATE_TOP);
        assertFalse(mService.isUidForeground(UID_A));
        assertTrue(mService.isUidForeground(UID_B));
    }

    @Test
    public void testLastCycleBoundaryThisMonth() throws Exception {
        // assume cycle day of "5th", which should be in same month
        final long currentTime = parseTime("2007-11-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-11-05T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 5, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testLastCycleBoundaryLastMonth() throws Exception {
        // assume cycle day of "20th", which should be in last month
        final long currentTime = parseTime("2007-11-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-10-20T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 20, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testLastCycleBoundaryThisMonthFebruary() throws Exception {
        // assume cycle day of "30th" in february; should go to january
        final long currentTime = parseTime("2007-02-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-01-30T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 30, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testLastCycleBoundaryLastMonthFebruary() throws Exception {
        // assume cycle day of "30th" in february, which should clamp
        final long currentTime = parseTime("2007-03-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-02-28T23:59:59.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 30, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testCycleBoundaryLeapYear() throws Exception {
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 29, TIMEZONE_UTC, 1024L, 1024L, false);

        assertTimeEquals(parseTime("2012-01-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-01-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2012-02-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-02-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2012-02-29T00:00:00.000Z"),
                computeLastCycleBoundary(parseTime("2012-03-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2012-03-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-03-14T00:00:00.000Z"), policy));

        assertTimeEquals(parseTime("2007-01-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2007-01-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2007-02-28T23:59:59.000Z"),
                computeNextCycleBoundary(parseTime("2007-02-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2007-02-28T23:59:59.000Z"),
                computeLastCycleBoundary(parseTime("2007-03-14T00:00:00.000Z"), policy));
        assertTimeEquals(parseTime("2007-03-29T00:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2007-03-14T00:00:00.000Z"), policy));
    }

    @Test
    public void testNextCycleTimezoneAfterUtc() throws Exception {
        // US/Central is UTC-6
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 10, "US/Central", 1024L, 1024L, false);
        assertTimeEquals(parseTime("2012-01-10T06:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-01-05T00:00:00.000Z"), policy));
    }

    @Test
    public void testNextCycleTimezoneBeforeUtc() throws Exception {
        // Israel is UTC+2
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 10, "Israel", 1024L, 1024L, false);
        assertTimeEquals(parseTime("2012-01-09T22:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-01-05T00:00:00.000Z"), policy));
    }

    @Test
    public void testNextCycleSane() throws Exception {
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 31, TIMEZONE_UTC, WARNING_DISABLED, LIMIT_DISABLED, false);
        final LinkedHashSet<Long> seen = new LinkedHashSet<Long>();

        // walk forwards, ensuring that cycle boundaries don't get stuck
        long currentCycle = computeNextCycleBoundary(parseTime("2011-08-01T00:00:00.000Z"), policy);
        for (int i = 0; i < 128; i++) {
            long nextCycle = computeNextCycleBoundary(currentCycle, policy);
            assertEqualsFuzzy(DAY_IN_MILLIS * 30, nextCycle - currentCycle, DAY_IN_MILLIS * 3);
            assertUnique(seen, nextCycle);
            currentCycle = nextCycle;
        }
    }

    @Test
    public void testLastCycleSane() throws Exception {
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 31, TIMEZONE_UTC, WARNING_DISABLED, LIMIT_DISABLED, false);
        final LinkedHashSet<Long> seen = new LinkedHashSet<Long>();

        // walk backwards, ensuring that cycle boundaries look sane
        long currentCycle = computeLastCycleBoundary(parseTime("2011-08-04T00:00:00.000Z"), policy);
        for (int i = 0; i < 128; i++) {
            long lastCycle = computeLastCycleBoundary(currentCycle, policy);
            assertEqualsFuzzy(DAY_IN_MILLIS * 30, currentCycle - lastCycle, DAY_IN_MILLIS * 3);
            assertUnique(seen, lastCycle);
            currentCycle = lastCycle;
        }
    }

    @Test
    public void testCycleTodayJanuary() throws Exception {
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 14, "US/Pacific", 1024L, 1024L, false);

        assertTimeEquals(parseTime("2013-01-14T00:00:00.000-08:00"),
                computeNextCycleBoundary(parseTime("2013-01-13T23:59:59.000-08:00"), policy));
        assertTimeEquals(parseTime("2013-02-14T00:00:00.000-08:00"),
                computeNextCycleBoundary(parseTime("2013-01-14T00:00:01.000-08:00"), policy));
        assertTimeEquals(parseTime("2013-02-14T00:00:00.000-08:00"),
                computeNextCycleBoundary(parseTime("2013-01-14T15:11:00.000-08:00"), policy));

        assertTimeEquals(parseTime("2012-12-14T00:00:00.000-08:00"),
                computeLastCycleBoundary(parseTime("2013-01-13T23:59:59.000-08:00"), policy));
        assertTimeEquals(parseTime("2013-01-14T00:00:00.000-08:00"),
                computeLastCycleBoundary(parseTime("2013-01-14T00:00:01.000-08:00"), policy));
        assertTimeEquals(parseTime("2013-01-14T00:00:00.000-08:00"),
                computeLastCycleBoundary(parseTime("2013-01-14T15:11:00.000-08:00"), policy));
    }

    @Test
    public void testLastCycleBoundaryDST() throws Exception {
        final long currentTime = parseTime("1989-01-02T07:30:00.000");
        final long expectedCycle = parseTime("1988-12-03T02:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 3, "America/Argentina/Buenos_Aires", 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testLastCycleBoundaryJanuaryDST() throws Exception {
        final long currentTime = parseTime("1989-01-26T21:00:00.000Z");
        final long expectedCycle = parseTime("1989-01-01T01:59:59.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 32, "America/Argentina/Buenos_Aires", 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    @Test
    public void testNetworkPolicyAppliedCycleLastMonth() throws Exception {
        NetworkState[] state = null;
        NetworkStats stats = null;

        final long TIME_FEB_15 = 1171497600000L;
        final long TIME_MAR_10 = 1173484800000L;
        final int CYCLE_DAY = 15;

        setCurrentTimeMillis(TIME_MAR_10);

        // first, pretend that wifi network comes online. no policy active,
        // which means we shouldn't push limit to interface.
        state = new NetworkState[] { buildWifi() };
        when(mConnManager.getAllNetworkState()).thenReturn(state);
        expectCurrentTime();

        mPolicyListener.expect().onMeteredIfacesChanged(any());
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
        mPolicyListener.waitAndVerify().onMeteredIfacesChanged(any());

        // now change cycle to be on 15th, and test in early march, to verify we
        // pick cycle day in previous month.
        when(mConnManager.getAllNetworkState()).thenReturn(state);
        expectCurrentTime();

        // pretend that 512 bytes total have happened
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 256L, 2L, 256L, 2L);
        when(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15, TIME_MAR_10))
                .thenReturn(stats.getTotalBytes());

        mPolicyListener.expect().onMeteredIfacesChanged(any());
        setNetworkPolicies(new NetworkPolicy(
                sTemplateWifi, CYCLE_DAY, TIMEZONE_UTC, 1 * MB_IN_BYTES, 2 * MB_IN_BYTES, false));
        mPolicyListener.waitAndVerify().onMeteredIfacesChanged(eq(new String[]{TEST_IFACE}));

        // TODO: consider making strongly ordered mock
        verifyPolicyDataEnable(TYPE_WIFI, true);
        verifyRemoveInterfaceQuota(TEST_IFACE);
        verifySetInterfaceQuota(TEST_IFACE, (2 * MB_IN_BYTES) - 512);
    }

    @Test
    public void testOverWarningLimitNotification() throws Exception {
        NetworkState[] state = null;
        NetworkStats stats = null;
        Future<String> tagFuture = null;

        final long TIME_FEB_15 = 1171497600000L;
        final long TIME_MAR_10 = 1173484800000L;
        final int CYCLE_DAY = 15;

        setCurrentTimeMillis(TIME_MAR_10);

        // assign wifi policy
        state = new NetworkState[] {};
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 0L, 0L, 0L, 0L);

        {
            expectCurrentTime();
            when(mConnManager.getAllNetworkState()).thenReturn(state);
            when(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15,
                    currentTimeMillis())).thenReturn(stats.getTotalBytes());

            mPolicyListener.expect().onMeteredIfacesChanged(any());
            setNetworkPolicies(new NetworkPolicy(sTemplateWifi, CYCLE_DAY, TIMEZONE_UTC, 1
                    * MB_IN_BYTES, 2 * MB_IN_BYTES, false));
            mPolicyListener.waitAndVerify().onMeteredIfacesChanged(any());
            verifyPolicyDataEnable(TYPE_WIFI, true);
        }

        // bring up wifi network
        incrementCurrentTime(MINUTE_IN_MILLIS);
        state = new NetworkState[] { buildWifi() };
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 0L, 0L, 0L, 0L);

        {
            expectCurrentTime();
            when(mConnManager.getAllNetworkState()).thenReturn(state);
            when(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15,
                    currentTimeMillis())).thenReturn(stats.getTotalBytes());

            mPolicyListener.expect().onMeteredIfacesChanged(any());
            mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
            mPolicyListener.waitAndVerify().onMeteredIfacesChanged(eq(new String[]{TEST_IFACE}));

            verifyPolicyDataEnable(TYPE_WIFI, true);
            verifyRemoveInterfaceQuota(TEST_IFACE);
            verifySetInterfaceQuota(TEST_IFACE, 2 * MB_IN_BYTES);
        }

        // go over warning, which should kick notification
        incrementCurrentTime(MINUTE_IN_MILLIS);
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 1536 * KB_IN_BYTES, 15L, 0L, 0L);

        {
            expectCurrentTime();
            when(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15,
                    currentTimeMillis())).thenReturn(stats.getTotalBytes());
            tagFuture = expectEnqueueNotification();

            mNetworkObserver.limitReached(null, TEST_IFACE);

            assertNotificationType(TYPE_WARNING, tagFuture.get());
            verifyPolicyDataEnable(TYPE_WIFI, true);

        }

        // go over limit, which should kick notification and dialog
        incrementCurrentTime(MINUTE_IN_MILLIS);
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 5 * MB_IN_BYTES, 512L, 0L, 0L);

        {
            expectCurrentTime();
            when(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15,
                    currentTimeMillis())).thenReturn(stats.getTotalBytes());
            tagFuture = expectEnqueueNotification();

            mNetworkObserver.limitReached(null, TEST_IFACE);

            assertNotificationType(TYPE_LIMIT, tagFuture.get());
            verifyPolicyDataEnable(TYPE_WIFI, false);
        }

        // now snooze policy, which should remove quota
        incrementCurrentTime(MINUTE_IN_MILLIS);

        {
            expectCurrentTime();
            when(mConnManager.getAllNetworkState()).thenReturn(state);
            when(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15,
                    currentTimeMillis())).thenReturn(stats.getTotalBytes());
            tagFuture = expectEnqueueNotification();

            mPolicyListener.expect().onMeteredIfacesChanged(any());
            mService.snoozeLimit(sTemplateWifi);
            mPolicyListener.waitAndVerify().onMeteredIfacesChanged(eq(new String[]{TEST_IFACE}));

            assertNotificationType(TYPE_LIMIT_SNOOZED, tagFuture.get());
            // snoozed interface still has high quota so background data is
            // still restricted.
            verifyRemoveInterfaceQuota(TEST_IFACE);
            verifySetInterfaceQuota(TEST_IFACE, Long.MAX_VALUE);
            verifyPolicyDataEnable(TYPE_WIFI, true);
        }
    }

    @Test
    public void testMeteredNetworkWithoutLimit() throws Exception {
        NetworkState[] state = null;
        NetworkStats stats = null;

        final long TIME_FEB_15 = 1171497600000L;
        final long TIME_MAR_10 = 1173484800000L;
        final int CYCLE_DAY = 15;

        setCurrentTimeMillis(TIME_MAR_10);

        // bring up wifi network with metered policy
        state = new NetworkState[] { buildWifi() };
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 0L, 0L, 0L, 0L);

        {
            expectCurrentTime();
            when(mConnManager.getAllNetworkState()).thenReturn(state);
            when(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15,
                    currentTimeMillis())).thenReturn(stats.getTotalBytes());

            mPolicyListener.expect().onMeteredIfacesChanged(any());
            setNetworkPolicies(new NetworkPolicy(
                    sTemplateWifi, CYCLE_DAY, TIMEZONE_UTC, WARNING_DISABLED, LIMIT_DISABLED,
                    true));
            mPolicyListener.waitAndVerify().onMeteredIfacesChanged(eq(new String[]{TEST_IFACE}));

            verifyPolicyDataEnable(TYPE_WIFI, true);
            verifyRemoveInterfaceQuota(TEST_IFACE);
            verifySetInterfaceQuota(TEST_IFACE, Long.MAX_VALUE);
        }
    }

    private static long parseTime(String time) {
        final Time result = new Time();
        result.parse3339(time);
        return result.toMillis(true);
    }

    private void setNetworkPolicies(NetworkPolicy... policies) {
        mService.setNetworkPolicies(policies);
    }

    private static NetworkState buildWifi() {
        final NetworkInfo info = new NetworkInfo(TYPE_WIFI, 0, null, null);
        info.setDetailedState(DetailedState.CONNECTED, null, null);
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFACE);
        return new NetworkState(info, prop, null, null, null, TEST_SSID);
    }

    private void expectCurrentTime() throws Exception {
        when(mTime.forceRefresh()).thenReturn(false);
        when(mTime.hasCache()).thenReturn(true);
        when(mTime.currentTimeMillis()).thenReturn(currentTimeMillis());
        when(mTime.getCacheAge()).thenReturn(0L);
        when(mTime.getCacheCertainty()).thenReturn(0L);
    }

    private Future<String> expectEnqueueNotification() throws Exception {
        final FutureAnswer<String> futureAnswer = new FutureAnswer<String>(2);
        doAnswer(futureAnswer).when(mNotifManager).enqueueNotificationWithTag(
                anyString(), anyString(), anyString() /* capture here (index 2)*/,
                anyInt(), isA(Notification.class), isA(int[].class), anyInt());
        return futureAnswer;
    }

    private void verifySetInterfaceQuota(String iface, long quotaBytes) throws Exception {
        verify(mNetworkManager, atLeastOnce()).setInterfaceQuota(iface, quotaBytes);
    }

    private void verifyRemoveInterfaceQuota(String iface) throws Exception {
        verify(mNetworkManager, atLeastOnce()).removeInterfaceQuota(iface);
    }

    private Future<Void> verifyPolicyDataEnable(int type, boolean enabled) throws Exception {
        // TODO: bring back this test
        return null;
    }

    private void verifyAdvisePersistThreshold() throws Exception {
        verify(mStatsService).advisePersistThreshold(anyLong());
    }

    private static class TestAbstractFuture<T> extends AbstractFuture<T> {
        @Override
        public T get() throws InterruptedException, ExecutionException {
            try {
                return get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class FutureAnswer<T> extends TestAbstractFuture<T> implements Answer<Void> {
        private final int index;

        FutureAnswer(int index) {
            this.index = index;
        }
        @Override
        public Void answer(InvocationOnMock invocation) throws Throwable {
            @SuppressWarnings("unchecked")
            T captured = (T) invocation.getArguments()[index];
            set(captured);
            return null;
        }
    }

    private static void assertTimeEquals(long expected, long actual) {
        if (expected != actual) {
            fail("expected " + formatTime(expected) + " but was actually " + formatTime(actual));
        }
    }

    private static String formatTime(long millis) {
        final Time time = new Time(Time.TIMEZONE_UTC);
        time.set(millis);
        return time.format3339(false);
    }

    private static void assertEqualsFuzzy(long expected, long actual, long fuzzy) {
        final long low = expected - fuzzy;
        final long high = expected + fuzzy;
        if (actual < low || actual > high) {
            fail("value " + actual + " is outside [" + low + "," + high + "]");
        }
    }

    private static void assertUnique(LinkedHashSet<Long> seen, Long value) {
        if (!seen.add(value)) {
            fail("found duplicate time " + value + " in series " + seen.toString());
        }
    }

    private static void assertNotificationType(int expected, String actualTag) {
        assertEquals("notification type mismatch for '" + actualTag +"'",
                Integer.toString(expected), actualTag.substring(actualTag.lastIndexOf(':') + 1));
    }

    private long getElapsedRealtime() {
        return mElapsedRealtime;
    }

    private void setCurrentTimeMillis(long currentTimeMillis) {
        mStartTime = currentTimeMillis;
        mElapsedRealtime = 0L;
    }

    private long currentTimeMillis() {
        return mStartTime + mElapsedRealtime;
    }

    private void incrementCurrentTime(long duration) {
        mElapsedRealtime += duration;
    }

    /**
     * Creates a mock and registers it to {@link LocalServices}.
     */
    private static <T> T addLocalServiceMock(Class<T> clazz) {
        final T mock = mock(clazz);
        LocalServices.addService(clazz, mock);
        return mock;
    }

    /**
     * Custom Mockito answer used to verify async {@link INetworkPolicyListener} calls.
     *
     * <p>Typical usage:
     * <pre><code>
     *    mPolicyListener.expect().someCallback(any());
     *    // do something on objects under test
     *    mPolicyListener.waitAndVerify().someCallback(eq(expectedValue));
     * </code></pre>
     */
    final class NetworkPolicyListenerAnswer implements Answer<Void> {
        private CountDownLatch latch;
        private final INetworkPolicyListener listener;

        NetworkPolicyListenerAnswer(NetworkPolicyManagerService service) {
            this.listener = mock(INetworkPolicyListener.class);
            // RemoteCallbackList needs a binder to use as key
            when(listener.asBinder()).thenReturn(new Binder());
            service.registerListener(listener);
        }

        @Override
        public Void answer(InvocationOnMock invocation) throws Throwable {
            Log.d(TAG,"counting down on answer: " + invocation);
            latch.countDown();
            return null;
        }

        INetworkPolicyListener expect() {
            assertNull("expect() called before waitAndVerify()", latch);
            latch = new CountDownLatch(1);
            return doAnswer(this).when(listener);
        }

        INetworkPolicyListener waitAndVerify() {
            assertNotNull("waitAndVerify() called before expect()", latch);
            try {
                assertTrue("callback not called in 5 seconds", latch.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                fail("Thread interrupted before callback called");
            } finally {
                latch = null;
            }
            return verify(listener, atLeastOnce());
        }
    }
}
