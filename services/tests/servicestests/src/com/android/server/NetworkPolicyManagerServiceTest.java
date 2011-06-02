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

import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_PAID_BACKGROUND;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_PAID;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;

import android.app.IActivityManager;
import android.app.IProcessObserver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.INetworkPolicyListener;
import android.net.INetworkStatsService;
import android.os.Binder;
import android.os.IPowerManager;
import android.test.AndroidTestCase;
import android.test.mock.MockPackageManager;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;

import com.android.server.net.NetworkPolicyManagerService;

import org.easymock.Capture;
import org.easymock.EasyMock;

import java.util.concurrent.Future;

/**
 * Tests for {@link NetworkPolicyManagerService}.
 */
@LargeTest
public class NetworkPolicyManagerServiceTest extends AndroidTestCase {
    private static final String TAG = "NetworkPolicyManagerServiceTest";

    private BroadcastInterceptingContext mServiceContext;

    private IActivityManager mActivityManager;
    private IPowerManager mPowerManager;
    private INetworkStatsService mStatsService;
    private INetworkPolicyListener mPolicyListener;

    private NetworkPolicyManagerService mService;
    private IProcessObserver mProcessObserver;

    private Binder mStubBinder = new Binder();

    private static final int UID_A = 800;
    private static final int UID_B = 801;

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

        mActivityManager = createMock(IActivityManager.class);
        mPowerManager = createMock(IPowerManager.class);
        mStatsService = createMock(INetworkStatsService.class);
        mPolicyListener = createMock(INetworkPolicyListener.class);

        mService = new NetworkPolicyManagerService(
                mServiceContext, mActivityManager, mPowerManager, mStatsService);

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

        replay();
        mService.systemReady();
        verifyAndReset();

        mProcessObserver = processObserver.getValue();

    }

    @Override
    public void tearDown() throws Exception {
        mServiceContext = null;

        mActivityManager = null;
        mPowerManager = null;
        mStatsService = null;
        mPolicyListener = null;

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

        mService.setUidPolicy(UID_A, POLICY_REJECT_PAID_BACKGROUND);

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
        mService.setUidPolicy(UID_A, POLICY_REJECT_PAID_BACKGROUND);
        verifyAndReset();

        // now turn screen off and verify REJECT rule
        expect(mPowerManager.isScreenOn()).andReturn(false).atLeastOnce();
        expectRulesChanged(UID_A, RULE_REJECT_PAID);
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
        expectRulesChanged(UID_A, RULE_REJECT_PAID);
        replay();
        mService.setUidPolicy(UID_A, POLICY_REJECT_PAID_BACKGROUND);
        verifyAndReset();

        // POLICY_REJECT should RULE_ALLOW in foreground
        expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mProcessObserver.onForegroundActivitiesChanged(PID_1, UID_A, true);
        verifyAndReset();

        // POLICY_REJECT should RULE_REJECT in background
        expectRulesChanged(UID_A, RULE_REJECT_PAID);
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
        expectRulesChanged(UID_A, RULE_REJECT_PAID);
        replay();
        mService.setUidPolicy(UID_A, POLICY_REJECT_PAID_BACKGROUND);
        verifyAndReset();

        // removing POLICY_REJECT should return us to RULE_ALLOW
        expectRulesChanged(UID_A, RULE_ALLOW_ALL);
        replay();
        mService.setUidPolicy(UID_A, POLICY_NONE);
        verifyAndReset();
    }

    private void expectRulesChanged(int uid, int policy) throws Exception {
        mPolicyListener.onRulesChanged(eq(uid), eq(policy));
        expectLastCall().atLeastOnce();
    }

    private void replay() {
        EasyMock.replay(mActivityManager, mPowerManager, mStatsService, mPolicyListener);
    }

    private void verifyAndReset() {
        EasyMock.verify(mActivityManager, mPowerManager, mStatsService, mPolicyListener);
        EasyMock.reset(mActivityManager, mPowerManager, mStatsService, mPolicyListener);
    }
}
