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

import static android.content.Intent.ACTION_UID_REMOVED;
import static android.content.Intent.EXTRA_UID;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.NetworkPolicy.LIMIT_DISABLED;
import static android.net.NetworkPolicy.WARNING_DISABLED;
import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_METERED;
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
import static org.easymock.EasyMock.anyInt;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.aryEq;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;

import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.IProcessObserver;
import android.app.Notification;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
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
import android.os.IPowerManager;
import android.os.MessageQueue.IdleHandler;
import android.os.UserHandle;
import android.test.AndroidTestCase;
import android.test.mock.MockPackageManager;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;
import android.text.format.Time;
import android.util.TrustedTime;

import com.android.server.net.NetworkPolicyManagerService;
import com.google.common.util.concurrent.AbstractFuture;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Handler;

import libcore.io.IoUtils;

/**
 * Tests for {@link NetworkPolicyManagerService}.
 */
@LargeTest
public class NetworkPolicyManagerServiceTest extends AndroidTestCase {
    private static final String TAG = "NetworkPolicyManagerServiceTest";

    private static final long TEST_START = 1194220800000L;
    private static final String TEST_IFACE = "test0";
    private static final String TEST_SSID = "AndroidAP";

    private static NetworkTemplate sTemplateWifi = NetworkTemplate.buildTemplateWifi(TEST_SSID);

    private BroadcastInterceptingContext mServiceContext;
    private File mPolicyDir;

    private IActivityManager mActivityManager;
    private IPowerManager mPowerManager;
    private INetworkStatsService mStatsService;
    private INetworkManagementService mNetworkManager;
    private INetworkPolicyListener mPolicyListener;
    private TrustedTime mTime;
    private IConnectivityManager mConnManager;
    private INotificationManager mNotifManager;

    private NetworkPolicyManagerService mService;
    private IProcessObserver mProcessObserver;
    private INetworkManagementEventObserver mNetworkObserver;

    private Binder mStubBinder = new Binder();

    private long mStartTime;
    private long mElapsedRealtime;

    private static final int USER_ID = 0;

    private static final int APP_ID_A = android.os.Process.FIRST_APPLICATION_UID + 800;
    private static final int APP_ID_B = android.os.Process.FIRST_APPLICATION_UID + 801;

    private static final int UID_A = UserHandle.getUid(USER_ID, APP_ID_A);
    private static final int UID_B = UserHandle.getUid(USER_ID, APP_ID_B);

    private static final int PID_1 = 400;
    private static final int PID_2 = 401;
    private static final int PID_3 = 402;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        setCurrentTimeMillis(TEST_START);

        // intercept various broadcasts, and pretend that uids have packages
        mServiceContext = new BroadcastInterceptingContext(getContext()) {
            @Override
            public PackageManager getPackageManager() {
                return new MockPackageManager() {
                    @Override
                    public String[] getPackagesForUid(int uid) {
                        return new String[] { "com.example" };
                    }

                    @Override
                    public PackageInfo getPackageInfo(String packageName, int flags) {
                        final PackageInfo info = new PackageInfo();
                        final Signature signature;
                        if ("android".equals(packageName)) {
                            signature = new Signature("F00D");
                        } else {
                            signature = new Signature("DEAD");
                        }
                        info.signatures = new Signature[] { signature };
                        return info;
                    }

                };
            }

            @Override
            public void startActivity(Intent intent) {
                // ignored
            }
        };

        mPolicyDir = getContext().getFilesDir();
        if (mPolicyDir.exists()) {
            IoUtils.deleteContents(mPolicyDir);
        }

        mActivityManager = createMock(IActivityManager.class);
        mPowerManager = createMock(IPowerManager.class);
        mStatsService = createMock(INetworkStatsService.class);
        mNetworkManager = createMock(INetworkManagementService.class);
        mPolicyListener = createMock(INetworkPolicyListener.class);
        mTime = createMock(TrustedTime.class);
        mConnManager = createMock(IConnectivityManager.class);
        mNotifManager = createMock(INotificationManager.class);

        mService = new NetworkPolicyManagerService(mServiceContext, mActivityManager, mPowerManager,
                mStatsService, mNetworkManager, mTime, mPolicyDir, true);
        mService.bindConnectivityManager(mConnManager);
        mService.bindNotificationManager(mNotifManager);

        // RemoteCallbackList needs a binder to use as key
        expect(mPolicyListener.asBinder()).andReturn(mStubBinder).atLeastOnce();
        replay();
        mService.registerListener(mPolicyListener);
        verifyAndReset();

        // catch IProcessObserver during systemReady()
        final Capture<IProcessObserver> processObserver = new Capture<IProcessObserver>();
        mActivityManager.registerProcessObserver(capture(processObserver));
        expectLastCall().atLeastOnce();

        // catch INetworkManagementEventObserver during systemReady()
        final Capture<INetworkManagementEventObserver> networkObserver = new Capture<
                INetworkManagementEventObserver>();
        mNetworkManager.registerObserver(capture(networkObserver));
        expectLastCall().atLeastOnce();

        // expect to answer screen status during systemReady()
        expect(mPowerManager.isScreenOn()).andReturn(true).atLeastOnce();
        expect(mNetworkManager.isBandwidthControlEnabled()).andReturn(true).atLeastOnce();
        expectCurrentTime();

        replay();
        mService.systemReady();
        verifyAndReset();

        mProcessObserver = processObserver.getValue();
        mNetworkObserver = networkObserver.getValue();

    }

    @Override
    public void tearDown() throws Exception {
        for (File file : mPolicyDir.listFiles()) {
            file.delete();
        }

        mServiceContext = null;
        mPolicyDir = null;

        mActivityManager = null;
        mPowerManager = null;
        mStatsService = null;
        mPolicyListener = null;
        mTime = null;

        mService = null;
        mProcessObserver = null;

        super.tearDown();
    }

    @Suppress
    public void testPolicyChangeTriggersBroadcast() throws Exception {
        mService.setUidPolicy(APP_ID_A, POLICY_NONE);

        // change background policy and expect broadcast
        final Future<Intent> backgroundChanged = mServiceContext.nextBroadcastIntent(
                ConnectivityManager.ACTION_BACKGROUND_DATA_SETTING_CHANGED);

        mService.setUidPolicy(APP_ID_A, POLICY_REJECT_METERED_BACKGROUND);

        backgroundChanged.get();
    }

    public void testPidForegroundCombined() throws Exception {
        IdleFuture idle;

        // push all uid into background
        idle = expectIdle();
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, false);
        mProcessObserver.onForegroundActivitiesChanged(PID_2, UID_A, false);
        mProcessObserver.onForegroundActivitiesChanged(PID_3, UID_B, false);
        idle.get();
        assertFalse(mService.isUidForeground(UID_A));
        assertFalse(mService.isUidForeground(UID_B));

        // push one of the shared pids into foreground
        idle = expectIdle();
        mProcessObserver.onForegroundActivitiesChanged(PID_2, UID_A, true);
        idle.get();
        assertTrue(mService.isUidForeground(UID_A));
        assertFalse(mService.isUidForeground(UID_B));

        // and swap another uid into foreground
        idle = expectIdle();
        mProcessObserver.onForegroundActivitiesChanged(PID_2, UID_A, false);
        mProcessObserver.onForegroundActivitiesChanged(PID_3, UID_B, true);
        idle.get();
        assertFalse(mService.isUidForeground(UID_A));
        assertTrue(mService.isUidForeground(UID_B));

        // push both pid into foreground
        idle = expectIdle();
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, true);
        mProcessObserver.onForegroundActivitiesChanged(PID_2, UID_A, true);
        idle.get();
        assertTrue(mService.isUidForeground(UID_A));

        // pull one out, should still be foreground
        idle = expectIdle();
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, false);
        idle.get();
        assertTrue(mService.isUidForeground(UID_A));

        // pull final pid out, should now be background
        idle = expectIdle();
        mProcessObserver.onForegroundActivitiesChanged(PID_2, UID_A, false);
        idle.get();
        assertFalse(mService.isUidForeground(UID_A));
    }

    public void testScreenChangesRules() throws Exception {
        Future<Void> future;

        expectSetUidNetworkRules(UID_A, false);
        expectSetUidForeground(UID_A, true);
        future = expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, true);
        future.get();
        verifyAndReset();

        // push strict policy for foreground uid, verify ALLOW rule
        expectSetUidNetworkRules(UID_A, false);
        expectSetUidForeground(UID_A, true);
        future = expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mService.setUidPolicy(APP_ID_A, POLICY_REJECT_METERED_BACKGROUND);
        future.get();
        verifyAndReset();

        // now turn screen off and verify REJECT rule
        expect(mPowerManager.isScreenOn()).andReturn(false).atLeastOnce();
        expectSetUidNetworkRules(UID_A, true);
        expectSetUidForeground(UID_A, false);
        future = expectRulesChanged(UID_A, RULE_REJECT_METERED);
        replay();
        mServiceContext.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        future.get();
        verifyAndReset();

        // and turn screen back on, verify ALLOW rule restored
        expect(mPowerManager.isScreenOn()).andReturn(true).atLeastOnce();
        expectSetUidNetworkRules(UID_A, false);
        expectSetUidForeground(UID_A, true);
        future = expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mServiceContext.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        future.get();
        verifyAndReset();
    }

    public void testPolicyNone() throws Exception {
        Future<Void> future;

        expectSetUidNetworkRules(UID_A, false);
        expectSetUidForeground(UID_A, true);
        future = expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, true);
        future.get();
        verifyAndReset();

        // POLICY_NONE should RULE_ALLOW in foreground
        expectSetUidNetworkRules(UID_A, false);
        expectSetUidForeground(UID_A, true);
        future = expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mService.setUidPolicy(APP_ID_A, POLICY_NONE);
        future.get();
        verifyAndReset();

        // POLICY_NONE should RULE_ALLOW in background
        expectSetUidNetworkRules(UID_A, false);
        expectSetUidForeground(UID_A, false);
        future = expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, false);
        future.get();
        verifyAndReset();
    }

    public void testPolicyReject() throws Exception {
        Future<Void> future;

        // POLICY_REJECT should RULE_ALLOW in background
        expectSetUidNetworkRules(UID_A, true);
        expectSetUidForeground(UID_A, false);
        future = expectRulesChanged(UID_A, RULE_REJECT_METERED);
        replay();
        mService.setUidPolicy(APP_ID_A, POLICY_REJECT_METERED_BACKGROUND);
        future.get();
        verifyAndReset();

        // POLICY_REJECT should RULE_ALLOW in foreground
        expectSetUidNetworkRules(UID_A, false);
        expectSetUidForeground(UID_A, true);
        future = expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, true);
        future.get();
        verifyAndReset();

        // POLICY_REJECT should RULE_REJECT in background
        expectSetUidNetworkRules(UID_A, true);
        expectSetUidForeground(UID_A, false);
        future = expectRulesChanged(UID_A, RULE_REJECT_METERED);
        replay();
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, false);
        future.get();
        verifyAndReset();
    }

    public void testPolicyRejectAddRemove() throws Exception {
        Future<Void> future;

        // POLICY_NONE should have RULE_ALLOW in background
        expectSetUidNetworkRules(UID_A, false);
        expectSetUidForeground(UID_A, false);
        future = expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, false);
        mService.setUidPolicy(APP_ID_A, POLICY_NONE);
        future.get();
        verifyAndReset();

        // adding POLICY_REJECT should cause RULE_REJECT
        expectSetUidNetworkRules(UID_A, true);
        expectSetUidForeground(UID_A, false);
        future = expectRulesChanged(UID_A, RULE_REJECT_METERED);
        replay();
        mService.setUidPolicy(APP_ID_A, POLICY_REJECT_METERED_BACKGROUND);
        future.get();
        verifyAndReset();

        // removing POLICY_REJECT should return us to RULE_ALLOW
        expectSetUidNetworkRules(UID_A, false);
        expectSetUidForeground(UID_A, false);
        future = expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mService.setUidPolicy(APP_ID_A, POLICY_NONE);
        future.get();
        verifyAndReset();
    }

    public void testLastCycleBoundaryThisMonth() throws Exception {
        // assume cycle day of "5th", which should be in same month
        final long currentTime = parseTime("2007-11-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-11-05T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 5, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    public void testLastCycleBoundaryLastMonth() throws Exception {
        // assume cycle day of "20th", which should be in last month
        final long currentTime = parseTime("2007-11-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-10-20T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 20, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    public void testLastCycleBoundaryThisMonthFebruary() throws Exception {
        // assume cycle day of "30th" in february; should go to january
        final long currentTime = parseTime("2007-02-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-01-30T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 30, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

    public void testLastCycleBoundaryLastMonthFebruary() throws Exception {
        // assume cycle day of "30th" in february, which should clamp
        final long currentTime = parseTime("2007-03-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-02-28T23:59:59.000Z");

        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 30, TIMEZONE_UTC, 1024L, 1024L, false);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertTimeEquals(expectedCycle, actualCycle);
    }

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

    public void testNextCycleTimezoneAfterUtc() throws Exception {
        // US/Central is UTC-6
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 10, "US/Central", 1024L, 1024L, false);
        assertTimeEquals(parseTime("2012-01-10T06:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-01-05T00:00:00.000Z"), policy));
    }

    public void testNextCycleTimezoneBeforeUtc() throws Exception {
        // Israel is UTC+2
        final NetworkPolicy policy = new NetworkPolicy(
                sTemplateWifi, 10, "Israel", 1024L, 1024L, false);
        assertTimeEquals(parseTime("2012-01-09T22:00:00.000Z"),
                computeNextCycleBoundary(parseTime("2012-01-05T00:00:00.000Z"), policy));
    }

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

    public void testNetworkPolicyAppliedCycleLastMonth() throws Exception {
        NetworkState[] state = null;
        NetworkStats stats = null;
        Future<Void> future;

        final long TIME_FEB_15 = 1171497600000L;
        final long TIME_MAR_10 = 1173484800000L;
        final int CYCLE_DAY = 15;

        setCurrentTimeMillis(TIME_MAR_10);

        // first, pretend that wifi network comes online. no policy active,
        // which means we shouldn't push limit to interface.
        state = new NetworkState[] { buildWifi() };
        expect(mConnManager.getAllNetworkState()).andReturn(state).atLeastOnce();
        expectCurrentTime();
        expectClearNotifications();
        expectAdvisePersistThreshold();
        future = expectMeteredIfacesChanged();

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION_IMMEDIATE));
        future.get();
        verifyAndReset();

        // now change cycle to be on 15th, and test in early march, to verify we
        // pick cycle day in previous month.
        expect(mConnManager.getAllNetworkState()).andReturn(state).atLeastOnce();
        expectCurrentTime();

        // pretend that 512 bytes total have happened
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 256L, 2L, 256L, 2L);
        expect(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15, TIME_MAR_10))
                .andReturn(stats.getTotalBytes()).atLeastOnce();
        expectPolicyDataEnable(TYPE_WIFI, true);

        // TODO: consider making strongly ordered mock
        expectRemoveInterfaceQuota(TEST_IFACE);
        expectSetInterfaceQuota(TEST_IFACE, (2 * MB_IN_BYTES) - 512);

        expectClearNotifications();
        expectAdvisePersistThreshold();
        future = expectMeteredIfacesChanged(TEST_IFACE);

        replay();
        setNetworkPolicies(new NetworkPolicy(
                sTemplateWifi, CYCLE_DAY, TIMEZONE_UTC, 1 * MB_IN_BYTES, 2 * MB_IN_BYTES, false));
        future.get();
        verifyAndReset();
    }

    public void testUidRemovedPolicyCleared() throws Exception {
        Future<Void> future;

        // POLICY_REJECT should RULE_REJECT in background
        expectSetUidNetworkRules(UID_A, true);
        expectSetUidForeground(UID_A, false);
        future = expectRulesChanged(UID_A, RULE_REJECT_METERED);
        replay();
        mService.setUidPolicy(APP_ID_A, POLICY_REJECT_METERED_BACKGROUND);
        future.get();
        verifyAndReset();

        // uninstall should clear RULE_REJECT
        expectSetUidNetworkRules(UID_A, false);
        expectSetUidForeground(UID_A, false);
        future = expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        final Intent intent = new Intent(ACTION_UID_REMOVED);
        intent.putExtra(EXTRA_UID, UID_A);
        mServiceContext.sendBroadcast(intent);
        future.get();
        verifyAndReset();
    }

    public void testOverWarningLimitNotification() throws Exception {
        NetworkState[] state = null;
        NetworkStats stats = null;
        Future<Void> future;
        Future<String> tagFuture;

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
            expect(mConnManager.getAllNetworkState()).andReturn(state).atLeastOnce();
            expect(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15, currentTimeMillis()))
                    .andReturn(stats.getTotalBytes()).atLeastOnce();
            expectPolicyDataEnable(TYPE_WIFI, true);

            expectClearNotifications();
            expectAdvisePersistThreshold();
            future = expectMeteredIfacesChanged();

            replay();
            setNetworkPolicies(new NetworkPolicy(sTemplateWifi, CYCLE_DAY, TIMEZONE_UTC, 1
                    * MB_IN_BYTES, 2 * MB_IN_BYTES, false));
            future.get();
            verifyAndReset();
        }

        // bring up wifi network
        incrementCurrentTime(MINUTE_IN_MILLIS);
        state = new NetworkState[] { buildWifi() };
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 0L, 0L, 0L, 0L);

        {
            expectCurrentTime();
            expect(mConnManager.getAllNetworkState()).andReturn(state).atLeastOnce();
            expect(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15, currentTimeMillis()))
                    .andReturn(stats.getTotalBytes()).atLeastOnce();
            expectPolicyDataEnable(TYPE_WIFI, true);

            expectRemoveInterfaceQuota(TEST_IFACE);
            expectSetInterfaceQuota(TEST_IFACE, 2 * MB_IN_BYTES);

            expectClearNotifications();
            expectAdvisePersistThreshold();
            future = expectMeteredIfacesChanged(TEST_IFACE);

            replay();
            mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION_IMMEDIATE));
            future.get();
            verifyAndReset();
        }

        // go over warning, which should kick notification
        incrementCurrentTime(MINUTE_IN_MILLIS);
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 1536 * KB_IN_BYTES, 15L, 0L, 0L);

        {
            expectCurrentTime();
            expect(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15, currentTimeMillis()))
                    .andReturn(stats.getTotalBytes()).atLeastOnce();
            expectPolicyDataEnable(TYPE_WIFI, true);

            expectForceUpdate();
            expectClearNotifications();
            tagFuture = expectEnqueueNotification();

            replay();
            mNetworkObserver.limitReached(null, TEST_IFACE);
            assertNotificationType(TYPE_WARNING, tagFuture.get());
            verifyAndReset();
        }

        // go over limit, which should kick notification and dialog
        incrementCurrentTime(MINUTE_IN_MILLIS);
        stats = new NetworkStats(getElapsedRealtime(), 1)
                .addIfaceValues(TEST_IFACE, 5 * MB_IN_BYTES, 512L, 0L, 0L);

        {
            expectCurrentTime();
            expect(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15, currentTimeMillis()))
                    .andReturn(stats.getTotalBytes()).atLeastOnce();
            expectPolicyDataEnable(TYPE_WIFI, false);

            expectForceUpdate();
            expectClearNotifications();
            tagFuture = expectEnqueueNotification();

            replay();
            mNetworkObserver.limitReached(null, TEST_IFACE);
            assertNotificationType(TYPE_LIMIT, tagFuture.get());
            verifyAndReset();
        }

        // now snooze policy, which should remove quota
        incrementCurrentTime(MINUTE_IN_MILLIS);

        {
            expectCurrentTime();
            expect(mConnManager.getAllNetworkState()).andReturn(state).atLeastOnce();
            expect(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15, currentTimeMillis()))
                    .andReturn(stats.getTotalBytes()).atLeastOnce();
            expectPolicyDataEnable(TYPE_WIFI, true);

            // snoozed interface still has high quota so background data is
            // still restricted.
            expectRemoveInterfaceQuota(TEST_IFACE);
            expectSetInterfaceQuota(TEST_IFACE, Long.MAX_VALUE);
            expectAdvisePersistThreshold();
            expectMeteredIfacesChanged(TEST_IFACE);

            future = expectClearNotifications();
            tagFuture = expectEnqueueNotification();

            replay();
            mService.snoozeLimit(sTemplateWifi);
            assertNotificationType(TYPE_LIMIT_SNOOZED, tagFuture.get());
            future.get();
            verifyAndReset();
        }
    }

    public void testMeteredNetworkWithoutLimit() throws Exception {
        NetworkState[] state = null;
        NetworkStats stats = null;
        Future<Void> future;
        Future<String> tagFuture;

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
            expect(mConnManager.getAllNetworkState()).andReturn(state).atLeastOnce();
            expect(mStatsService.getNetworkTotalBytes(sTemplateWifi, TIME_FEB_15, currentTimeMillis()))
                    .andReturn(stats.getTotalBytes()).atLeastOnce();
            expectPolicyDataEnable(TYPE_WIFI, true);

            expectRemoveInterfaceQuota(TEST_IFACE);
            expectSetInterfaceQuota(TEST_IFACE, Long.MAX_VALUE);

            expectClearNotifications();
            expectAdvisePersistThreshold();
            future = expectMeteredIfacesChanged(TEST_IFACE);

            replay();
            setNetworkPolicies(new NetworkPolicy(
                    sTemplateWifi, CYCLE_DAY, TIMEZONE_UTC, WARNING_DISABLED, LIMIT_DISABLED,
                    true));
            future.get();
            verifyAndReset();
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
        return new NetworkState(info, prop, null, null, TEST_SSID);
    }

    private void expectCurrentTime() throws Exception {
        expect(mTime.forceRefresh()).andReturn(false).anyTimes();
        expect(mTime.hasCache()).andReturn(true).anyTimes();
        expect(mTime.currentTimeMillis()).andReturn(currentTimeMillis()).anyTimes();
        expect(mTime.getCacheAge()).andReturn(0L).anyTimes();
        expect(mTime.getCacheCertainty()).andReturn(0L).anyTimes();
    }

    private void expectForceUpdate() throws Exception {
        mStatsService.forceUpdate();
        expectLastCall().atLeastOnce();
    }

    private Future<Void> expectClearNotifications() throws Exception {
        final FutureAnswer future = new FutureAnswer();
        mNotifManager.cancelNotificationWithTag(
                isA(String.class), isA(String.class), anyInt(), anyInt());
        expectLastCall().andAnswer(future).anyTimes();
        return future;
    }

    private Future<String> expectEnqueueNotification() throws Exception {
        final FutureCapture<String> tag = new FutureCapture<String>();
        mNotifManager.enqueueNotificationWithTag(isA(String.class), capture(tag.capture), anyInt(),
                isA(Notification.class), isA(int[].class), UserHandle.myUserId());
        return tag;
    }

    private void expectSetInterfaceQuota(String iface, long quotaBytes) throws Exception {
        mNetworkManager.setInterfaceQuota(iface, quotaBytes);
        expectLastCall().atLeastOnce();
    }

    private void expectRemoveInterfaceQuota(String iface) throws Exception {
        mNetworkManager.removeInterfaceQuota(iface);
        expectLastCall().atLeastOnce();
    }

    private void expectSetInterfaceAlert(String iface, long alertBytes) throws Exception {
        mNetworkManager.setInterfaceAlert(iface, alertBytes);
        expectLastCall().atLeastOnce();
    }

    private void expectRemoveInterfaceAlert(String iface) throws Exception {
        mNetworkManager.removeInterfaceAlert(iface);
        expectLastCall().atLeastOnce();
    }

    private void expectSetUidNetworkRules(int uid, boolean rejectOnQuotaInterfaces)
            throws Exception {
        mNetworkManager.setUidNetworkRules(uid, rejectOnQuotaInterfaces);
        expectLastCall().atLeastOnce();
    }

    private void expectSetUidForeground(int uid, boolean uidForeground) throws Exception {
        mStatsService.setUidForeground(uid, uidForeground);
        expectLastCall().atLeastOnce();
    }

    private Future<Void> expectRulesChanged(int uid, int policy) throws Exception {
        final FutureAnswer future = new FutureAnswer();
        mPolicyListener.onUidRulesChanged(eq(uid), eq(policy));
        expectLastCall().andAnswer(future);
        return future;
    }

    private Future<Void> expectMeteredIfacesChanged(String... ifaces) throws Exception {
        final FutureAnswer future = new FutureAnswer();
        mPolicyListener.onMeteredIfacesChanged(aryEq(ifaces));
        expectLastCall().andAnswer(future);
        return future;
    }

    private Future<Void> expectPolicyDataEnable(int type, boolean enabled) throws Exception {
        final FutureAnswer future = new FutureAnswer();
        mConnManager.setPolicyDataEnable(type, enabled);
        expectLastCall().andAnswer(future);
        return future;
    }

    private void expectAdvisePersistThreshold() throws Exception {
        mStatsService.advisePersistThreshold(anyLong());
        expectLastCall().anyTimes();
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

    private static class FutureAnswer extends TestAbstractFuture<Void> implements IAnswer<Void> {
        @Override
        public Void answer() {
            set(null);
            return null;
        }
    }

    private static class FutureCapture<T> extends TestAbstractFuture<T> {
        public Capture<T> capture = new Capture<T>() {
            @Override
            public void setValue(T value) {
                super.setValue(value);
                set(value);
            }
        };
    }

    private static class IdleFuture extends AbstractFuture<Void> implements IdleHandler {
        @Override
        public Void get() throws InterruptedException, ExecutionException {
            try {
                return get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean queueIdle() {
            set(null);
            return false;
        }
    }

    /**
     * Wait until {@link #mService} internal {@link Handler} is idle.
     */
    private IdleFuture expectIdle() {
        final IdleFuture future = new IdleFuture();
        mService.addIdleHandler(future);
        return future;
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
        assertEquals(
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

    private void replay() {
        EasyMock.replay(mActivityManager, mPowerManager, mStatsService, mPolicyListener,
                mNetworkManager, mTime, mConnManager, mNotifManager);
    }

    private void verifyAndReset() {
        EasyMock.verify(mActivityManager, mPowerManager, mStatsService, mPolicyListener,
                mNetworkManager, mTime, mConnManager, mNotifManager);
        EasyMock.reset(mActivityManager, mPowerManager, mStatsService, mPolicyListener,
                mNetworkManager, mTime, mConnManager, mNotifManager);
    }
}
