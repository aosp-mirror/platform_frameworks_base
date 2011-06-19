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
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_METERED;
import static android.net.NetworkPolicyManager.computeLastCycleBoundary;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static android.net.NetworkTemplate.MATCH_WIFI;
import static org.easymock.EasyMock.anyInt;
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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
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
import android.os.IPowerManager;
import android.test.AndroidTestCase;
import android.test.mock.MockPackageManager;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;
import android.text.format.Time;
import android.util.TrustedTime;

import com.android.server.net.NetworkPolicyManagerService;

import org.easymock.Capture;
import org.easymock.EasyMock;

import java.io.File;
import java.util.concurrent.Future;

/**
 * Tests for {@link NetworkPolicyManagerService}.
 */
@LargeTest
public class NetworkPolicyManagerServiceTest extends AndroidTestCase {
    private static final String TAG = "NetworkPolicyManagerServiceTest";

    private static final long TEST_START = 1194220800000L;
    private static final String TEST_IFACE = "test0";

    private static NetworkTemplate sTemplateWifi = new NetworkTemplate(MATCH_WIFI, null);

    private BroadcastInterceptingContext mServiceContext;
    private File mPolicyDir;

    private IActivityManager mActivityManager;
    private IPowerManager mPowerManager;
    private INetworkStatsService mStatsService;
    private INetworkPolicyListener mPolicyListener;
    private TrustedTime mTime;
    private IConnectivityManager mConnManager;
    private INotificationManager mNotifManager;

    private NetworkPolicyManagerService mService;
    private IProcessObserver mProcessObserver;

    private Binder mStubBinder = new Binder();

    private static final int UID_A = android.os.Process.FIRST_APPLICATION_UID + 800;
    private static final int UID_B = android.os.Process.FIRST_APPLICATION_UID + 801;

    private static final int PID_1 = 400;
    private static final int PID_2 = 401;
    private static final int PID_3 = 402;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        // intercept various broadcasts, and pretend that uids have packages
        mServiceContext = new BroadcastInterceptingContext(getContext()) {
            @Override
            public PackageManager getPackageManager() {
                return new MockPackageManager() {
                    @Override
                    public String[] getPackagesForUid(int uid) {
                        return new String[] { "com.example" };
                    }
                };
            }
        };

        mPolicyDir = getContext().getFilesDir();

        mActivityManager = createMock(IActivityManager.class);
        mPowerManager = createMock(IPowerManager.class);
        mStatsService = createMock(INetworkStatsService.class);
        mPolicyListener = createMock(INetworkPolicyListener.class);
        mTime = createMock(TrustedTime.class);
        mConnManager = createMock(IConnectivityManager.class);
        mNotifManager = createMock(INotificationManager.class);

        mService = new NetworkPolicyManagerService(
                mServiceContext, mActivityManager, mPowerManager, mStatsService, mTime, mPolicyDir);
        mService.bindConnectivityManager(mConnManager);
        mService.bindNotificationManager(mNotifManager);

        // RemoteCallbackList needs a binder to use as key
        expect(mPolicyListener.asBinder()).andReturn(mStubBinder).atLeastOnce();
        replay();
        mService.registerListener(mPolicyListener);
        verifyAndReset();

        // catch the registered IProcessObserver during systemReady()
        final Capture<IProcessObserver> processObserver = new Capture<IProcessObserver>();
        mActivityManager.registerProcessObserver(capture(processObserver));
        expectLastCall().atLeastOnce();

        // expect to answer screen status during systemReady()
        expect(mPowerManager.isScreenOn()).andReturn(true).atLeastOnce();
        expectTime(System.currentTimeMillis());

        replay();
        mService.systemReady();
        verifyAndReset();

        mProcessObserver = processObserver.getValue();

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
        mService.setUidPolicy(UID_A, POLICY_NONE);

        // change background policy and expect broadcast
        final Future<Intent> backgroundChanged = mServiceContext.nextBroadcastIntent(
                ConnectivityManager.ACTION_BACKGROUND_DATA_SETTING_CHANGED);

        mService.setUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND);

        backgroundChanged.get();
    }

    public void testPidForegroundCombined() throws Exception {
        // push all uid into background
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, false);
        mProcessObserver.onForegroundActivitiesChanged(PID_2, UID_A, false);
        mProcessObserver.onForegroundActivitiesChanged(PID_3, UID_B, false);
        assertFalse(mService.isUidForeground(UID_A));
        assertFalse(mService.isUidForeground(UID_B));

        // push one of the shared pids into foreground
        mProcessObserver.onForegroundActivitiesChanged(PID_2, UID_A, true);
        assertTrue(mService.isUidForeground(UID_A));
        assertFalse(mService.isUidForeground(UID_B));

        // and swap another uid into foreground
        mProcessObserver.onForegroundActivitiesChanged(PID_2, UID_A, false);
        mProcessObserver.onForegroundActivitiesChanged(PID_3, UID_B, true);
        assertFalse(mService.isUidForeground(UID_A));
        assertTrue(mService.isUidForeground(UID_B));

        // push both pid into foreground
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, true);
        mProcessObserver.onForegroundActivitiesChanged(PID_2, UID_A, true);
        assertTrue(mService.isUidForeground(UID_A));

        // pull one out, should still be foreground
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, false);
        assertTrue(mService.isUidForeground(UID_A));

        // pull final pid out, should now be background
        mProcessObserver.onForegroundActivitiesChanged(PID_2, UID_A, false);
        assertFalse(mService.isUidForeground(UID_A));
    }

    public void testScreenChangesRules() throws Exception {
        // push strict policy for foreground uid, verify ALLOW rule
        expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, true);
        mService.setUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND);
        verifyAndReset();

        // now turn screen off and verify REJECT rule
        expect(mPowerManager.isScreenOn()).andReturn(false).atLeastOnce();
        expectRulesChanged(UID_A, RULE_REJECT_METERED);
        replay();
        mServiceContext.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        verifyAndReset();

        // and turn screen back on, verify ALLOW rule restored
        expect(mPowerManager.isScreenOn()).andReturn(true).atLeastOnce();
        expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mServiceContext.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        verifyAndReset();
    }

    public void testPolicyNone() throws Exception {
        // POLICY_NONE should RULE_ALLOW in foreground
        expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mService.setUidPolicy(UID_A, POLICY_NONE);
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, true);
        verifyAndReset();

        // POLICY_NONE should RULE_ALLOW in background
        expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, false);
        verifyAndReset();
    }

    public void testPolicyReject() throws Exception {
        // POLICY_REJECT should RULE_ALLOW in background
        expectRulesChanged(UID_A, RULE_REJECT_METERED);
        replay();
        mService.setUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND);
        verifyAndReset();

        // POLICY_REJECT should RULE_ALLOW in foreground
        expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, true);
        verifyAndReset();

        // POLICY_REJECT should RULE_REJECT in background
        expectRulesChanged(UID_A, RULE_REJECT_METERED);
        replay();
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, false);
        verifyAndReset();
    }

    public void testPolicyRejectAddRemove() throws Exception {
        // POLICY_NONE should have RULE_ALLOW in background
        expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mService.setUidPolicy(UID_A, POLICY_NONE);
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, false);
        verifyAndReset();

        // adding POLICY_REJECT should cause RULE_REJECT
        expectRulesChanged(UID_A, RULE_REJECT_METERED);
        replay();
        mService.setUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND);
        verifyAndReset();

        // removing POLICY_REJECT should return us to RULE_ALLOW
        expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mService.setUidPolicy(UID_A, POLICY_NONE);
        verifyAndReset();
    }

    public void testLastCycleBoundaryThisMonth() throws Exception {
        // assume cycle day of "5th", which should be in same month
        final long currentTime = parseTime("2007-11-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-11-05T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(sTemplateWifi, 5, 1024L, 1024L);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertEquals(expectedCycle, actualCycle);
    }

    public void testLastCycleBoundaryLastMonth() throws Exception {
        // assume cycle day of "20th", which should be in last month
        final long currentTime = parseTime("2007-11-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-10-20T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(sTemplateWifi, 20, 1024L, 1024L);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertEquals(expectedCycle, actualCycle);
    }

    public void testLastCycleBoundaryThisMonthFebruary() throws Exception {
        // assume cycle day of "30th" in february; should go to january
        final long currentTime = parseTime("2007-02-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-01-30T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(sTemplateWifi, 30, 1024L, 1024L);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertEquals(expectedCycle, actualCycle);
    }

    public void testLastCycleBoundaryLastMonthFebruary() throws Exception {
        // assume cycle day of "30th" in february, which should clamp
        final long currentTime = parseTime("2007-03-14T00:00:00.000Z");
        final long expectedCycle = parseTime("2007-03-01T00:00:00.000Z");

        final NetworkPolicy policy = new NetworkPolicy(sTemplateWifi, 30, 1024L, 1024L);
        final long actualCycle = computeLastCycleBoundary(currentTime, policy);
        assertEquals(expectedCycle, actualCycle);
    }

    public void testNetworkPolicyAppliedCycleLastMonth() throws Exception {
        long elapsedRealtime = 0;
        NetworkState[] state = null;
        NetworkStats stats = null;

        final long TIME_FEB_15 = 1171497600000L;
        final long TIME_MAR_10 = 1173484800000L;
        final int CYCLE_DAY = 15;

        // first, pretend that wifi network comes online. no policy active,
        // which means we shouldn't push limit to interface.
        state = new NetworkState[] { buildWifi() };
        expect(mConnManager.getAllNetworkState()).andReturn(state).atLeastOnce();
        expectTime(TIME_MAR_10 + elapsedRealtime);
        expectMeteredIfacesChanged();

        replay();
        mServiceContext.sendBroadcast(new Intent(CONNECTIVITY_ACTION));
        verifyAndReset();

        // now change cycle to be on 15th, and test in early march, to verify we
        // pick cycle day in previous month.
        expect(mConnManager.getAllNetworkState()).andReturn(state).atLeastOnce();
        expectTime(TIME_MAR_10 + elapsedRealtime);

        // pretend that 512 bytes total have happened
        stats = new NetworkStats(elapsedRealtime, 1)
                .addEntry(TEST_IFACE, UID_ALL, TAG_NONE, 256L, 256L);
        expect(mStatsService.getSummaryForNetwork(sTemplateWifi, TIME_FEB_15, TIME_MAR_10))
                .andReturn(stats).atLeastOnce();

        // expect that quota remaining should be 1536 bytes
        // TODO: write up NetworkManagementService mock

        expectClearNotifications();
        expectMeteredIfacesChanged(TEST_IFACE);

        replay();
        setNetworkPolicies(new NetworkPolicy(sTemplateWifi, CYCLE_DAY, 1024L, 2048L));
        verifyAndReset();
    }

    public void testUidRemovedPolicyCleared() throws Exception {
        // POLICY_REJECT should RULE_REJECT in background
        expectRulesChanged(UID_A, RULE_REJECT_METERED);
        replay();
        mService.setUidPolicy(UID_A, POLICY_REJECT_METERED_BACKGROUND);
        verifyAndReset();

        // uninstall should clear RULE_REJECT
        expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        final Intent intent = new Intent(ACTION_UID_REMOVED);
        intent.putExtra(EXTRA_UID, UID_A);
        mServiceContext.sendBroadcast(intent);
        verifyAndReset();
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
        return new NetworkState(info, prop, null);
    }

    private void expectTime(long currentTime) throws Exception {
        expect(mTime.forceRefresh()).andReturn(false).anyTimes();
        expect(mTime.hasCache()).andReturn(true).anyTimes();
        expect(mTime.currentTimeMillis()).andReturn(currentTime).anyTimes();
        expect(mTime.getCacheAge()).andReturn(0L).anyTimes();
        expect(mTime.getCacheCertainty()).andReturn(0L).anyTimes();
    }

    private void expectClearNotifications() throws Exception {
        mNotifManager.cancelNotificationWithTag(isA(String.class), isA(String.class), anyInt());
        expectLastCall().anyTimes();
    }

    private void expectRulesChanged(int uid, int policy) throws Exception {
        mPolicyListener.onUidRulesChanged(eq(uid), eq(policy));
        expectLastCall().atLeastOnce();
    }

    private void expectMeteredIfacesChanged(String... ifaces) throws Exception {
        mPolicyListener.onMeteredIfacesChanged(aryEq(ifaces));
        expectLastCall().atLeastOnce();
    }

    private void replay() {
        EasyMock.replay(mActivityManager, mPowerManager, mStatsService, mPolicyListener, mTime,
                mConnManager, mNotifManager);
    }

    private void verifyAndReset() {
        EasyMock.verify(mActivityManager, mPowerManager, mStatsService, mPolicyListener, mTime,
                mConnManager, mNotifManager);
        EasyMock.reset(mActivityManager, mPowerManager, mStatsService, mPolicyListener, mTime,
                mConnManager, mNotifManager);
    }
}
