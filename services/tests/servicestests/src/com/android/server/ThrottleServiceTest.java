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

import static android.net.NetworkStats.SET_DEFAULT;
import static android.net.NetworkStats.TAG_NONE;
import static android.net.NetworkStats.UID_ALL;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.reset;
import static org.easymock.EasyMock.verify;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.INetworkManagementEventObserver;
import android.net.NetworkStats;
import android.net.ThrottleManager;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.TrustedTime;

import java.util.concurrent.Future;

/**
 * Tests for {@link ThrottleService}.
 */
@LargeTest
public class ThrottleServiceTest extends AndroidTestCase {
    private static final String TAG = "ThrottleServiceTest";

    private static final long MB_IN_BYTES = 1024 * 1024;

    private static final int TEST_KBITPS = 222;
    private static final int TEST_RESET_DAY = 11;

    private static final String TEST_IFACE = "test0";

    private BroadcastInterceptingContext mWatchingContext;
    private INetworkManagementService mMockNMService;
    private TrustedTime mMockTime;

    private ThrottleService mThrottleService;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mWatchingContext = new BroadcastInterceptingContext(getContext());

        mMockNMService = createMock(INetworkManagementService.class);
        mMockTime = createMock(TrustedTime.class);

        mThrottleService = new ThrottleService(
                mWatchingContext, mMockNMService, mMockTime, TEST_IFACE);
    }

    @Override
    public void tearDown() throws Exception {
        mWatchingContext = null;
        mMockNMService = null;

        mThrottleService.shutdown();
        mThrottleService = null;

        clearThrottlePolicy();

        super.tearDown();
    }

    public void testNoPolicyNotThrottled() throws Exception {
        expectTimeCurrent();
        expectSystemReady();

        // provide stats without policy, verify not throttled
        expectGetInterfaceCounter(1 * MB_IN_BYTES, 2 * MB_IN_BYTES);
        expectSetInterfaceThrottle(-1, -1);

        replay(mMockTime, mMockNMService);
        systemReady();
        verify(mMockTime, mMockNMService);
    }

    public void testUnderLimitNotThrottled() throws Exception {
        setThrottlePolicy(200 * MB_IN_BYTES, TEST_KBITPS, TEST_RESET_DAY);

        expectTimeCurrent();
        expectSystemReady();

        // provide stats under limits, and verify not throttled
        expectGetInterfaceCounter(1 * MB_IN_BYTES, 2 * MB_IN_BYTES);
        expectSetInterfaceThrottle(-1, -1);

        replay(mMockTime, mMockNMService);
        systemReady();
        verify(mMockTime, mMockNMService);
    }

    public void testOverLimitThrottled() throws Exception {
        setThrottlePolicy(200 * MB_IN_BYTES, TEST_KBITPS, TEST_RESET_DAY);

        expectTimeCurrent();
        expectSystemReady();

        // provide stats over limits, and verify throttled
        expectGetInterfaceCounter(500 * MB_IN_BYTES, 600 * MB_IN_BYTES);
        expectSetInterfaceThrottle(TEST_KBITPS, TEST_KBITPS);

        replay(mMockTime, mMockNMService);
        systemReady();
        verify(mMockTime, mMockNMService);
    }

    public void testUnderThenOverLimitThrottled() throws Exception {
        setThrottlePolicy(201 * MB_IN_BYTES, TEST_KBITPS, TEST_RESET_DAY);

        expectTimeCurrent();
        expectSystemReady();

        // provide stats right under 201MB limit, verify not throttled
        expectGetInterfaceCounter(100 * MB_IN_BYTES, 100 * MB_IN_BYTES);
        expectSetInterfaceThrottle(-1, -1);

        replay(mMockTime, mMockNMService);
        systemReady();
        verify(mMockTime, mMockNMService);
        reset(mMockTime, mMockNMService);

        expectTimeCurrent();

        // adjust usage to bump over limit, verify throttle kicks in
        expectGetInterfaceCounter(105 * MB_IN_BYTES, 100 * MB_IN_BYTES);
        expectSetInterfaceThrottle(TEST_KBITPS, TEST_KBITPS);

        // and kick poll event which should throttle
        replay(mMockTime, mMockNMService);
        forceServicePoll();
        verify(mMockTime, mMockNMService);
    }

    public void testUpdatedPolicyThrottled() throws Exception {
        setThrottlePolicy(500 * MB_IN_BYTES, TEST_KBITPS, TEST_RESET_DAY);

        expectTimeCurrent();
        expectSystemReady();

        // provide stats under limit, verify not throttled
        expectGetInterfaceCounter(50 * MB_IN_BYTES, 50 * MB_IN_BYTES);
        expectSetInterfaceThrottle(-1, -1);

        replay(mMockTime, mMockNMService);
        systemReady();
        verify(mMockTime, mMockNMService);
        reset(mMockTime, mMockNMService);

        expectTimeCurrent();

        // provide same stats, but verify that modified policy will throttle
        expectGetInterfaceCounter(50 * MB_IN_BYTES, 50 * MB_IN_BYTES);
        expectSetInterfaceThrottle(TEST_KBITPS, TEST_KBITPS);

        replay(mMockTime, mMockNMService);

        // now adjust policy to bump usage over limit
        setThrottlePolicy(5 * MB_IN_BYTES, TEST_KBITPS, TEST_RESET_DAY);

        // and wait for policy updated broadcast
        mWatchingContext.nextBroadcastIntent(ThrottleManager.POLICY_CHANGED_ACTION).get();

        verify(mMockTime, mMockNMService);
    }

    public void testWithPolicyOverLimitThrottledAndRemovedAfterCycle() throws Exception {
        setThrottlePolicy(90 * MB_IN_BYTES, TEST_KBITPS, TEST_RESET_DAY);

        final long baseTime = System.currentTimeMillis();

        expectTime(baseTime);
        expectSystemReady();

        // provide stats over limit, verify throttle kicks in
        expectGetInterfaceCounter(50 * MB_IN_BYTES, 50 * MB_IN_BYTES);
        expectSetInterfaceThrottle(TEST_KBITPS, TEST_KBITPS);

        replay(mMockTime, mMockNMService);
        systemReady();
        verify(mMockTime, mMockNMService);
        reset(mMockTime, mMockNMService);

        // pretend that time has jumped forward two months
        expectTime(baseTime + DateUtils.WEEK_IN_MILLIS * 8);

        // provide slightly updated stats, but verify throttle is removed
        expectGetInterfaceCounter(60 * MB_IN_BYTES, 60 * MB_IN_BYTES);
        expectSetInterfaceThrottle(-1, -1);

        // and kick poll event which should throttle
        replay(mMockTime, mMockNMService);
        forceServiceReset();
        verify(mMockTime, mMockNMService);
    }

    @Suppress
    public void testReturnStats() throws Exception {
        final IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        final INetworkManagementService nmService = INetworkManagementService.Stub.asInterface(b);

        // test is currently no-op, just exercises stats apis
        Log.d(TAG, nmService.getNetworkStatsSummaryDev().toString());
        Log.d(TAG, nmService.getNetworkStatsSummaryXt().toString());
        Log.d(TAG, nmService.getNetworkStatsDetail().toString());
    }

    /**
     * Persist the given {@link ThrottleService} policy into {@link Settings}.
     */
    public void setThrottlePolicy(long thresholdBytes, int valueKbitps, int resetDay) {
        final ContentResolver resolver = getContext().getContentResolver();
        Settings.Secure.putLong(resolver, Settings.Secure.THROTTLE_THRESHOLD_BYTES, thresholdBytes);
        Settings.Secure.putInt(resolver, Settings.Secure.THROTTLE_VALUE_KBITSPS, valueKbitps);
        Settings.Secure.putInt(resolver, Settings.Secure.THROTTLE_RESET_DAY, resetDay);
    }

    /**
     * Clear any {@link ThrottleService} policy from {@link Settings}.
     */
    public void clearThrottlePolicy() {
        final ContentResolver resolver = getContext().getContentResolver();
        Settings.Secure.putString(resolver, Settings.Secure.THROTTLE_THRESHOLD_BYTES, null);
        Settings.Secure.putString(resolver, Settings.Secure.THROTTLE_VALUE_KBITSPS, null);
        Settings.Secure.putString(resolver, Settings.Secure.THROTTLE_RESET_DAY, null);
    }

    /**
     * Expect any {@link TrustedTime} mock calls, and respond with
     * {@link System#currentTimeMillis()}.
     */
    public void expectTimeCurrent() throws Exception {
        expectTime(System.currentTimeMillis());
    }

    /**
     * Expect any {@link TrustedTime} mock calls, and respond with the given
     * time in response to {@link TrustedTime#currentTimeMillis()}.
     */
    public void expectTime(long currentTime) throws Exception {
        expect(mMockTime.forceRefresh()).andReturn(false).anyTimes();
        expect(mMockTime.hasCache()).andReturn(true).anyTimes();
        expect(mMockTime.currentTimeMillis()).andReturn(currentTime).anyTimes();
        expect(mMockTime.getCacheAge()).andReturn(0L).anyTimes();
        expect(mMockTime.getCacheCertainty()).andReturn(0L).anyTimes();
    }

    /**
     * Expect {@link ThrottleService#systemReady()} generated calls, such as
     * connecting with {@link NetworkManagementService} mock.
     */
    public void expectSystemReady() throws Exception {
        mMockNMService.registerObserver(isA(INetworkManagementEventObserver.class));
        expectLastCall().atLeastOnce();
    }

    /**
     * Expect {@link NetworkManagementService#getNetworkStatsSummaryDev()} mock
     * calls, responding with the given counter values.
     */
    public void expectGetInterfaceCounter(long rx, long tx) throws Exception {
        // TODO: provide elapsedRealtime mock to match TimeAuthority
        final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 1);
        stats.addValues(TEST_IFACE, UID_ALL, SET_DEFAULT, TAG_NONE, rx, 0L, tx, 0L, 0);

        expect(mMockNMService.getNetworkStatsSummaryDev()).andReturn(stats).atLeastOnce();
    }

    /**
     * Expect {@link NetworkManagementService#setInterfaceThrottle} mock call
     * with the specified parameters.
     */
    public void expectSetInterfaceThrottle(int rx, int tx) throws Exception {
        mMockNMService.setInterfaceThrottle(isA(String.class), eq(rx), eq(tx));
        expectLastCall().atLeastOnce();
    }

    /**
     * Dispatch {@link ThrottleService#systemReady()} and block until finished.
     */
    public void systemReady() throws Exception {
        final Future<Intent> policyChanged = mWatchingContext.nextBroadcastIntent(
                ThrottleManager.POLICY_CHANGED_ACTION);
        final Future<Intent> pollAction = mWatchingContext.nextBroadcastIntent(
                ThrottleManager.THROTTLE_POLL_ACTION);

        mThrottleService.systemReady();

        // wait for everything to settle; for policy to update and for first poll
        policyChanged.get();
        pollAction.get();
    }

    /**
     * Dispatch {@link ThrottleService#dispatchPoll()} and block until finished.
     */
    public void forceServicePoll() throws Exception {
        // during systemReady() service already pushed a sticky broadcast, so we
        // need to skip the immediate and wait for the updated sticky.
        final Future<Intent> pollAction = mWatchingContext.nextBroadcastIntent(
                ThrottleManager.THROTTLE_POLL_ACTION);

        mThrottleService.dispatchPoll();

        pollAction.get();
    }

    /**
     * Dispatch {@link ThrottleService#dispatchReset()} and block until finished.
     */
    public void forceServiceReset() throws Exception {
        // during systemReady() service already pushed a sticky broadcast, so we
        // need to skip the immediate and wait for the updated sticky.
        final Future<Intent> pollAction = mWatchingContext.nextBroadcastIntent(
                ThrottleManager.THROTTLE_POLL_ACTION);

        mThrottleService.dispatchReset();

        pollAction.get();
    }
}
