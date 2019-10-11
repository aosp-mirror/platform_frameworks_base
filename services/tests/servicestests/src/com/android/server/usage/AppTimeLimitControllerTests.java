/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.usage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.PendingIntent;
import android.app.usage.UsageStatsManagerInternal;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.UserHandle;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class AppTimeLimitControllerTests {

    private static final String PKG_SOC1 = "package.soc1";
    private static final String PKG_SOC2 = "package.soc2";
    private static final String PKG_GAME1 = "package.game1";
    private static final String PKG_GAME2 = "package.game2";
    private static final String PKG_PROD = "package.prod";

    private static final int UID = 10100;
    private static final int USER_ID = 10;
    private static final int OBS_ID1 = 1;
    private static final int OBS_ID2 = 2;
    private static final int OBS_ID3 = 3;
    private static final int OBS_ID4 = 4;
    private static final int OBS_ID5 = 5;
    private static final int OBS_ID6 = 6;
    private static final int OBS_ID7 = 7;
    private static final int OBS_ID8 = 8;
    private static final int OBS_ID9 = 9;
    private static final int OBS_ID10 = 10;
    private static final int OBS_ID11 = 11;

    private static final long TIME_30_MIN = 30 * 60_000L;
    private static final long TIME_10_MIN = 10 * 60_000L;
    private static final long TIME_1_MIN = 1 * 60_000L;

    private static final long MAX_OBSERVER_PER_UID = 10;
    private static final long MIN_TIME_LIMIT = 4_000L;

    private static final String[] GROUP1 = {
            PKG_SOC1, PKG_GAME1, PKG_PROD
    };

    private static final String[] GROUP_SOC = {
            PKG_SOC1, PKG_SOC2
    };

    private static final String[] GROUP_GAME = {
            PKG_GAME1, PKG_GAME2
    };

    private CountDownLatch mLimitReachedLatch = new CountDownLatch(1);
    private CountDownLatch mSessionEndLatch = new CountDownLatch(1);

    private AppTimeLimitController mController;

    private HandlerThread mThread;

    private long mUptimeMillis;

    AppTimeLimitController.TimeLimitCallbackListener mListener =
            new AppTimeLimitController.TimeLimitCallbackListener() {
                @Override
                public void onLimitReached(int observerId, int userId, long timeLimit,
                        long timeElapsed,
                        PendingIntent callbackIntent) {
                    mLimitReachedLatch.countDown();
                }

                @Override
                public void onSessionEnd(int observerId, int userId, long timeElapsed,
                        PendingIntent callbackIntent) {
                    mSessionEndLatch.countDown();
                }
            };

    class MyAppTimeLimitController extends AppTimeLimitController {
        MyAppTimeLimitController(AppTimeLimitController.TimeLimitCallbackListener listener,
                Looper looper) {
            super(listener, looper);
        }

        @Override
        protected long getUptimeMillis() {
            return mUptimeMillis;
        }

        @Override
        protected long getAppUsageObserverPerUidLimit() {
            return MAX_OBSERVER_PER_UID;
        }

        @Override
        protected long getUsageSessionObserverPerUidLimit() {
            return MAX_OBSERVER_PER_UID;
        }

        @Override
        protected long getAppUsageLimitObserverPerUidLimit() {
            return MAX_OBSERVER_PER_UID;
        }

        @Override
        protected long getMinTimeLimit() {
            return MIN_TIME_LIMIT;
        }
    }

    @Before
    public void setUp() {
        mThread = new HandlerThread("Test");
        mThread.start();
        mController = new MyAppTimeLimitController(mListener, mThread.getLooper());
    }

    @After
    public void tearDown() {
        mThread.quit();
    }

    /** Verify app usage observer is added */
    @Test
    public void testAppUsageObserver_AddObserver() {
        addAppUsageObserver(OBS_ID1, GROUP1, TIME_30_MIN);
        assertTrue("Observer wasn't added", hasAppUsageObserver(UID, OBS_ID1));
        addAppUsageObserver(OBS_ID2, GROUP_GAME, TIME_30_MIN);
        assertTrue("Observer wasn't added", hasAppUsageObserver(UID, OBS_ID2));
        assertTrue("Observer wasn't added", hasAppUsageObserver(UID, OBS_ID1));
    }

    /** Verify usage session observer is added */
    @Test
    public void testUsageSessionObserver_AddObserver() {
        addUsageSessionObserver(OBS_ID1, GROUP1, TIME_30_MIN, TIME_1_MIN);
        assertTrue("Observer wasn't added", hasUsageSessionObserver(UID, OBS_ID1));
        addUsageSessionObserver(OBS_ID2, GROUP_GAME, TIME_30_MIN, TIME_1_MIN);
        assertTrue("Observer wasn't added", hasUsageSessionObserver(UID, OBS_ID2));
    }

    /** Verify app usage limit observer is added */
    @Test
    public void testAppUsageLimitObserver_AddObserver() {
        addAppUsageLimitObserver(OBS_ID1, GROUP1, TIME_30_MIN, 0);
        assertTrue("Observer wasn't added", hasAppUsageLimitObserver(UID, OBS_ID1));
        addAppUsageLimitObserver(OBS_ID2, GROUP_GAME, TIME_30_MIN, 0);
        assertTrue("Observer wasn't added", hasAppUsageLimitObserver(UID, OBS_ID2));
        assertTrue("Observer wasn't added", hasAppUsageLimitObserver(UID, OBS_ID1));
    }

    /** Verify app usage observer is removed */
    @Test
    public void testAppUsageObserver_RemoveObserver() {
        addAppUsageObserver(OBS_ID1, GROUP1, TIME_30_MIN);
        assertTrue("Observer wasn't added", hasAppUsageObserver(UID, OBS_ID1));
        mController.removeAppUsageObserver(UID, OBS_ID1, USER_ID);
        assertFalse("Observer wasn't removed", hasAppUsageObserver(UID, OBS_ID1));
    }

    /** Verify usage session observer is removed */
    @Test
    public void testUsageSessionObserver_RemoveObserver() {
        addUsageSessionObserver(OBS_ID1, GROUP1, TIME_30_MIN, TIME_1_MIN);
        assertTrue("Observer wasn't added", hasUsageSessionObserver(UID, OBS_ID1));
        mController.removeUsageSessionObserver(UID, OBS_ID1, USER_ID);
        assertFalse("Observer wasn't removed", hasUsageSessionObserver(UID, OBS_ID1));
    }

    /** Verify app usage limit observer is removed */
    @Test
    public void testAppUsageLimitObserver_RemoveObserver() {
        addAppUsageLimitObserver(OBS_ID1, GROUP1, TIME_30_MIN, 0);
        assertTrue("Observer wasn't added", hasAppUsageLimitObserver(UID, OBS_ID1));
        mController.removeAppUsageLimitObserver(UID, OBS_ID1, USER_ID);
        assertFalse("Observer wasn't removed", hasAppUsageLimitObserver(UID, OBS_ID1));
    }

    /** Verify nothing happens when a nonexistent app usage observer is removed */
    @Test
    public void testAppUsageObserver_RemoveMissingObserver() {
        assertFalse("Observer should not exist", hasAppUsageObserver(UID, OBS_ID1));
        try {
            mController.removeAppUsageObserver(UID, OBS_ID1, USER_ID);
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            sw.write("Hit exception trying to remove nonexistent observer:\n");
            sw.write(e.toString());
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            sw.write("\nTest Failed!");
            fail(sw.toString());
        }
        assertFalse("Observer should not exist", hasAppUsageObserver(UID, OBS_ID1));
    }

    /** Verify nothing happens when a nonexistent usage session observer is removed */
    @Test
    public void testUsageSessionObserver_RemoveMissingObserver() {
        assertFalse("Observer should not exist", hasUsageSessionObserver(UID, OBS_ID1));
        try {
            mController.removeUsageSessionObserver(UID, OBS_ID1, USER_ID);
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            sw.write("Hit exception trying to remove nonexistent observer:");
            sw.write(e.toString());
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            sw.write("\nTest Failed!");
            fail(sw.toString());
        }
        assertFalse("Observer should not exist", hasUsageSessionObserver(UID, OBS_ID1));
    }

    /** Verify nothing happens when a nonexistent app usage limit observer is removed */
    @Test
    public void testAppUsageLimitObserver_RemoveMissingObserver() {
        assertFalse("Observer should not exist", hasAppUsageLimitObserver(UID, OBS_ID1));
        try {
            mController.removeAppUsageLimitObserver(UID, OBS_ID1, USER_ID);
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            sw.write("Hit exception trying to remove nonexistent observer:\n");
            sw.write(e.toString());
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            sw.write("\nTest Failed!");
            fail(sw.toString());
        }
        assertFalse("Observer should not exist", hasAppUsageLimitObserver(UID, OBS_ID1));
    }

    /** Re-adding an observer should result in only one copy */
    @Test
    public void testAppUsageObserver_ObserverReAdd() {
        addAppUsageObserver(OBS_ID1, GROUP1, TIME_30_MIN);
        assertTrue("Observer wasn't added", hasAppUsageObserver(UID, OBS_ID1));
        addAppUsageObserver(OBS_ID1, GROUP1, TIME_10_MIN);
        assertTrue("Observer wasn't added",
                mController.getAppUsageGroup(UID, OBS_ID1).getTimeLimitMs() == TIME_10_MIN);
        mController.removeAppUsageObserver(UID, OBS_ID1, USER_ID);
        assertFalse("Observer wasn't removed", hasAppUsageObserver(UID, OBS_ID1));
    }

    /** Re-adding an observer should result in only one copy */
    @Test
    public void testUsageSessionObserver_ObserverReAdd() {
        addUsageSessionObserver(OBS_ID1, GROUP1, TIME_30_MIN, TIME_1_MIN);
        assertTrue("Observer wasn't added", hasUsageSessionObserver(UID, OBS_ID1));
        addUsageSessionObserver(OBS_ID1, GROUP1, TIME_10_MIN, TIME_1_MIN);
        assertTrue("Observer wasn't added",
                mController.getSessionUsageGroup(UID, OBS_ID1).getTimeLimitMs() == TIME_10_MIN);
        mController.removeUsageSessionObserver(UID, OBS_ID1, USER_ID);
        assertFalse("Observer wasn't removed", hasUsageSessionObserver(UID, OBS_ID1));
    }

    /** Re-adding an observer should result in only one copy */
    @Test
    public void testAppUsageLimitObserver_ObserverReAdd() {
        addAppUsageLimitObserver(OBS_ID1, GROUP1, TIME_30_MIN, 0);
        assertTrue("Observer wasn't added", hasAppUsageLimitObserver(UID, OBS_ID1));
        addAppUsageLimitObserver(OBS_ID1, GROUP1, TIME_10_MIN, 0);
        assertTrue("Observer wasn't added",
                getAppUsageLimitObserver(UID, OBS_ID1).getTimeLimitMs() == TIME_10_MIN);
        mController.removeAppUsageLimitObserver(UID, OBS_ID1, USER_ID);
        assertFalse("Observer wasn't removed", hasAppUsageLimitObserver(UID, OBS_ID1));
    }

    /** Different type observers can be registered to the same observerId value */
    @Test
    public void testAllObservers_ExclusiveObserverIds() {
        addAppUsageObserver(OBS_ID1, GROUP1, TIME_10_MIN);
        addUsageSessionObserver(OBS_ID1, GROUP1, TIME_30_MIN, TIME_1_MIN);
        addAppUsageLimitObserver(OBS_ID1, GROUP1, TIME_10_MIN, 0);
        assertTrue("Observer wasn't added", hasAppUsageObserver(UID, OBS_ID1));
        assertTrue("Observer wasn't added", hasUsageSessionObserver(UID, OBS_ID1));
        assertTrue("Observer wasn't added", hasAppUsageLimitObserver(UID, OBS_ID1));

        AppTimeLimitController.UsageGroup appUsageGroup = mController.getAppUsageGroup(UID,
                OBS_ID1);
        AppTimeLimitController.UsageGroup sessionUsageGroup = mController.getSessionUsageGroup(UID,
                OBS_ID1);
        AppTimeLimitController.UsageGroup appUsageLimitGroup = getAppUsageLimitObserver(
                UID, OBS_ID1);

        // Verify data still intact
        assertEquals(TIME_10_MIN, appUsageGroup.getTimeLimitMs());
        assertEquals(TIME_30_MIN, sessionUsageGroup.getTimeLimitMs());
        assertEquals(TIME_10_MIN, appUsageLimitGroup.getTimeLimitMs());
    }

    /** Verify that usage across different apps within a group are added up */
    @Test
    public void testAppUsageObserver_Accumulation() throws Exception {
        setTime(0L);
        addAppUsageObserver(OBS_ID1, GROUP1, TIME_30_MIN);
        startUsage(PKG_SOC1);
        // Add 10 mins
        setTime(TIME_10_MIN);
        stopUsage(PKG_SOC1);

        AppTimeLimitController.UsageGroup group = mController.getAppUsageGroup(UID, OBS_ID1);

        long timeRemaining = group.getTimeLimitMs() - group.getUsageTimeMs();
        assertEquals(TIME_10_MIN * 2, timeRemaining);

        startUsage(PKG_SOC1);
        setTime(TIME_10_MIN * 2);
        stopUsage(PKG_SOC1);

        timeRemaining = group.getTimeLimitMs() - group.getUsageTimeMs();
        assertEquals(TIME_10_MIN, timeRemaining);

        setTime(TIME_30_MIN);

        assertFalse(mLimitReachedLatch.await(100L, TimeUnit.MILLISECONDS));

        // Add a different package in the group
        startUsage(PKG_GAME1);
        setTime(TIME_30_MIN + TIME_10_MIN);
        stopUsage(PKG_GAME1);

        assertEquals(0, group.getTimeLimitMs() - group.getUsageTimeMs());
        assertTrue(mLimitReachedLatch.await(100L, TimeUnit.MILLISECONDS));
    }

    /** Verify that usage across different apps within a group are added up */
    @Test
    public void testUsageSessionObserver_Accumulation() throws Exception {
        setTime(0L);
        addUsageSessionObserver(OBS_ID1, GROUP1, TIME_30_MIN, TIME_10_MIN);
        startUsage(PKG_SOC1);
        // Add 10 mins
        setTime(TIME_10_MIN);
        stopUsage(PKG_SOC1);

        AppTimeLimitController.UsageGroup group = mController.getSessionUsageGroup(UID, OBS_ID1);

        long timeRemaining = group.getTimeLimitMs() - group.getUsageTimeMs();
        assertEquals(TIME_10_MIN * 2, timeRemaining);

        startUsage(PKG_SOC1);
        setTime(TIME_10_MIN * 2);
        stopUsage(PKG_SOC1);

        timeRemaining = group.getTimeLimitMs() - group.getUsageTimeMs();
        assertEquals(TIME_10_MIN, timeRemaining);

        setTime(TIME_30_MIN);

        assertFalse(mLimitReachedLatch.await(100L, TimeUnit.MILLISECONDS));

        // Add a different package in the group
        startUsage(PKG_GAME1);
        setTime(TIME_30_MIN + TIME_10_MIN);
        stopUsage(PKG_GAME1);

        assertEquals(0, group.getTimeLimitMs() - group.getUsageTimeMs());
        assertTrue(mLimitReachedLatch.await(100L, TimeUnit.MILLISECONDS));
    }

    /** Verify that usage across different apps within a group are added up */
    @Test
    public void testAppUsageLimitObserver_Accumulation() throws Exception {
        setTime(0L);
        addAppUsageLimitObserver(OBS_ID1, GROUP1, TIME_30_MIN, 0);
        startUsage(PKG_SOC1);
        // Add 10 mins
        setTime(TIME_10_MIN);
        stopUsage(PKG_SOC1);

        AppTimeLimitController.UsageGroup group = getAppUsageLimitObserver(UID, OBS_ID1);

        long timeRemaining = group.getTimeLimitMs() - group.getUsageTimeMs();
        assertEquals(TIME_10_MIN * 2, timeRemaining);

        startUsage(PKG_SOC1);
        setTime(TIME_10_MIN * 2);
        stopUsage(PKG_SOC1);

        timeRemaining = group.getTimeLimitMs() - group.getUsageTimeMs();
        assertEquals(TIME_10_MIN, timeRemaining);

        setTime(TIME_30_MIN);

        assertFalse(mLimitReachedLatch.await(100L, TimeUnit.MILLISECONDS));

        // Add a different package in the group
        startUsage(PKG_GAME1);
        setTime(TIME_30_MIN + TIME_10_MIN);
        stopUsage(PKG_GAME1);

        assertEquals(0, group.getTimeLimitMs() - group.getUsageTimeMs());
        assertTrue(mLimitReachedLatch.await(100L, TimeUnit.MILLISECONDS));
    }

    /** Verify that time limit does not get triggered due to a different app */
    @Test
    public void testAppUsageObserver_TimeoutOtherApp() throws Exception {
        setTime(0L);
        addAppUsageObserver(OBS_ID1, GROUP1, 4_000L);
        startUsage(PKG_SOC2);
        assertFalse(mLimitReachedLatch.await(6_000L, TimeUnit.MILLISECONDS));
        setTime(6_000L);
        stopUsage(PKG_SOC2);
        assertFalse(mLimitReachedLatch.await(100L, TimeUnit.MILLISECONDS));
    }

    /** Verify that time limit does not get triggered due to a different app */
    @Test
    public void testUsageSessionObserver_TimeoutOtherApp() throws Exception {
        setTime(0L);
        addUsageSessionObserver(OBS_ID1, GROUP1, 4_000L, 1_000L);
        startUsage(PKG_SOC2);
        assertFalse(mLimitReachedLatch.await(6_000L, TimeUnit.MILLISECONDS));
        setTime(6_000L);
        stopUsage(PKG_SOC2);
        assertFalse(mLimitReachedLatch.await(100L, TimeUnit.MILLISECONDS));

    }

    /** Verify that time limit does not get triggered due to a different app */
    @Test
    public void testAppUsageLimitObserver_TimeoutOtherApp() throws Exception {
        setTime(0L);
        addAppUsageLimitObserver(OBS_ID1, GROUP1, 4_000L, 0);
        startUsage(PKG_SOC2);
        assertFalse(mLimitReachedLatch.await(6_000L, TimeUnit.MILLISECONDS));
        setTime(6_000L);
        stopUsage(PKG_SOC2);
        assertFalse(mLimitReachedLatch.await(100L, TimeUnit.MILLISECONDS));
    }

    /** Verify the timeout message is delivered at the right time */
    @Test
    public void testAppUsageObserver_Timeout() throws Exception {
        setTime(0L);
        addAppUsageObserver(OBS_ID1, GROUP1, 4_000L);
        startUsage(PKG_SOC1);
        setTime(6_000L);
        assertTrue(mLimitReachedLatch.await(6_000L, TimeUnit.MILLISECONDS));
        stopUsage(PKG_SOC1);
        // Verify that the observer was removed
        assertFalse(hasAppUsageObserver(UID, OBS_ID1));
    }

    /** Verify the timeout message is delivered at the right time */
    @Test
    public void testUsageSessionObserver_Timeout() throws Exception {
        setTime(0L);
        addUsageSessionObserver(OBS_ID1, GROUP1, 4_000L, 1_000L);
        startUsage(PKG_SOC1);
        setTime(6_000L);
        assertTrue(mLimitReachedLatch.await(6_000L, TimeUnit.MILLISECONDS));
        stopUsage(PKG_SOC1);
        // Usage has stopped, Session should end in a second. Verify session end occurs in a second
        // (+/- 100ms, which is hopefully not too slim a margin)
        assertFalse(mSessionEndLatch.await(900L, TimeUnit.MILLISECONDS));
        assertTrue(mSessionEndLatch.await(200L, TimeUnit.MILLISECONDS));
        // Verify that the observer was not removed
        assertTrue(hasUsageSessionObserver(UID, OBS_ID1));
    }

    /** Verify the timeout message is delivered at the right time */
    @Test
    public void testAppUsageLimitObserver_Timeout() throws Exception {
        setTime(0L);
        addAppUsageLimitObserver(OBS_ID1, GROUP1, 4_000L, 0);
        startUsage(PKG_SOC1);
        setTime(6_000L);
        assertTrue(mLimitReachedLatch.await(6_000L, TimeUnit.MILLISECONDS));
        stopUsage(PKG_SOC1);
        // Verify that the observer was not removed
        assertTrue(hasAppUsageLimitObserver(UID, OBS_ID1));
    }

    /** If an app was already running, make sure it is partially counted towards the time limit */
    @Test
    public void testAppUsageObserver_AlreadyRunning() throws Exception {
        setTime(TIME_10_MIN);
        startUsage(PKG_GAME1);
        setTime(TIME_30_MIN);
        addAppUsageObserver(OBS_ID2, GROUP_GAME, TIME_30_MIN);
        setTime(TIME_30_MIN + TIME_10_MIN);
        stopUsage(PKG_GAME1);
        assertFalse(mLimitReachedLatch.await(1_000L, TimeUnit.MILLISECONDS));

        startUsage(PKG_GAME2);
        setTime(TIME_30_MIN + TIME_30_MIN);
        stopUsage(PKG_GAME2);
        assertTrue(mLimitReachedLatch.await(1_000L, TimeUnit.MILLISECONDS));
        // Verify that the observer was removed
        assertFalse(hasAppUsageObserver(UID, OBS_ID2));
    }

    /** If an app was already running, make sure it is partially counted towards the time limit */
    @Test
    public void testUsageSessionObserver_AlreadyRunning() throws Exception {
        setTime(TIME_10_MIN);
        startUsage(PKG_GAME1);
        setTime(TIME_30_MIN);
        addUsageSessionObserver(OBS_ID2, GROUP_GAME, TIME_30_MIN, TIME_1_MIN);
        setTime(TIME_30_MIN + TIME_10_MIN);
        stopUsage(PKG_GAME1);
        assertFalse(mLimitReachedLatch.await(1_000L, TimeUnit.MILLISECONDS));

        startUsage(PKG_GAME2);
        setTime(TIME_30_MIN + TIME_30_MIN);
        stopUsage(PKG_GAME2);
        assertTrue(mLimitReachedLatch.await(1_000L, TimeUnit.MILLISECONDS));
        // Verify that the observer was removed
        assertTrue(hasUsageSessionObserver(UID, OBS_ID2));
    }

    /** If an app was already running, make sure it is partially counted towards the time limit */
    @Test
    public void testAppUsageLimitObserver_AlreadyRunning() throws Exception {
        setTime(TIME_10_MIN);
        startUsage(PKG_GAME1);
        setTime(TIME_30_MIN);
        addAppUsageLimitObserver(OBS_ID2, GROUP_GAME, TIME_30_MIN, 0);
        setTime(TIME_30_MIN + TIME_10_MIN);
        stopUsage(PKG_GAME1);
        assertFalse(mLimitReachedLatch.await(1_000L, TimeUnit.MILLISECONDS));

        startUsage(PKG_GAME2);
        setTime(TIME_30_MIN + TIME_30_MIN);
        stopUsage(PKG_GAME2);
        assertTrue(mLimitReachedLatch.await(1_000L, TimeUnit.MILLISECONDS));
        // Verify that the observer was not removed
        assertTrue(hasAppUsageLimitObserver(UID, OBS_ID2));
    }

    /** If watched app is already running, verify the timeout callback happens at the right time */
    @Test
    public void testAppUsageObserver_AlreadyRunningTimeout() throws Exception {
        setTime(0);
        startUsage(PKG_SOC1);
        setTime(TIME_10_MIN);
        // 10 second time limit
        addAppUsageObserver(OBS_ID1, GROUP_SOC, 10_000L);
        setTime(TIME_10_MIN + 5_000L);
        // Shouldn't call back in 6 seconds
        assertFalse(mLimitReachedLatch.await(6_000L, TimeUnit.MILLISECONDS));
        setTime(TIME_10_MIN + 10_000L);
        // Should call back by 11 seconds (6 earlier + 5 now)
        assertTrue(mLimitReachedLatch.await(5_000L, TimeUnit.MILLISECONDS));
        // Verify that the observer was removed
        assertFalse(hasAppUsageObserver(UID, OBS_ID1));
    }

    /** If watched app is already running, verify the timeout callback happens at the right time */
    @Test
    public void testUsageSessionObserver_AlreadyRunningTimeout() throws Exception {
        setTime(0);
        startUsage(PKG_SOC1);
        setTime(TIME_10_MIN);
        // 10 second time limit
        addUsageSessionObserver(OBS_ID1, GROUP_SOC, 10_000L, 1_000L);
        setTime(TIME_10_MIN + 5_000L);
        // Shouldn't call back in 6 seconds
        assertFalse(mLimitReachedLatch.await(6_000L, TimeUnit.MILLISECONDS));
        setTime(TIME_10_MIN + 10_000L);
        // Should call back by 11 seconds (6 earlier + 5 now)
        assertTrue(mLimitReachedLatch.await(5_000L, TimeUnit.MILLISECONDS));
        stopUsage(PKG_SOC1);
        // Usage has stopped, Session should end in a second. Verify session end occurs in a second
        // (+/- 100ms, which is hopefully not too slim a margin)
        assertFalse(mSessionEndLatch.await(900L, TimeUnit.MILLISECONDS));
        assertTrue(mSessionEndLatch.await(200L, TimeUnit.MILLISECONDS));
        // Verify that the observer was removed
        assertTrue(hasUsageSessionObserver(UID, OBS_ID1));
    }

    /** If watched app is already running, verify the timeout callback happens at the right time */
    @Test
    public void testAppUsageLimitObserver_AlreadyRunningTimeout() throws Exception {
        setTime(0);
        startUsage(PKG_SOC1);
        setTime(TIME_10_MIN);
        // 10 second time limit
        addAppUsageLimitObserver(OBS_ID1, GROUP_SOC, 10_000L, 0);
        setTime(TIME_10_MIN + 5_000L);
        // Shouldn't call back in 6 seconds
        assertFalse(mLimitReachedLatch.await(6_000L, TimeUnit.MILLISECONDS));
        setTime(TIME_10_MIN + 10_000L);
        // Should call back by 11 seconds (6 earlier + 5 now)
        assertTrue(mLimitReachedLatch.await(5_000L, TimeUnit.MILLISECONDS));
        // Verify that the observer was not removed
        assertTrue(hasAppUsageLimitObserver(UID, OBS_ID1));
    }

    /**
     * Verify that App Time Limit Controller will limit the number of observerIds for app usage
     * observers
     */
    @Test
    public void testAppUsageObserver_MaxObserverLimit() throws Exception {
        boolean receivedException = false;
        int ANOTHER_UID = UID + 1;
        addAppUsageObserver(OBS_ID1, GROUP1, TIME_30_MIN);
        addAppUsageObserver(OBS_ID2, GROUP1, TIME_30_MIN);
        addAppUsageObserver(OBS_ID3, GROUP1, TIME_30_MIN);
        addAppUsageObserver(OBS_ID4, GROUP1, TIME_30_MIN);
        addAppUsageObserver(OBS_ID5, GROUP1, TIME_30_MIN);
        addAppUsageObserver(OBS_ID6, GROUP1, TIME_30_MIN);
        addAppUsageObserver(OBS_ID7, GROUP1, TIME_30_MIN);
        addAppUsageObserver(OBS_ID8, GROUP1, TIME_30_MIN);
        addAppUsageObserver(OBS_ID9, GROUP1, TIME_30_MIN);
        addAppUsageObserver(OBS_ID10, GROUP1, TIME_30_MIN);
        // Readding an observer should not cause an IllegalStateException
        addAppUsageObserver(OBS_ID5, GROUP1, TIME_30_MIN);
        // Adding an observer for a different uid shouldn't cause an IllegalStateException
        mController.addAppUsageObserver(ANOTHER_UID, OBS_ID11, GROUP1, TIME_30_MIN, null, USER_ID);
        try {
            addAppUsageObserver(OBS_ID11, GROUP1, TIME_30_MIN);
        } catch (IllegalStateException ise) {
            receivedException = true;
        }
        assertTrue("Should have caused an IllegalStateException", receivedException);
    }

    /**
     * Verify that App Time Limit Controller will limit the number of observerIds for usage session
     * observers
     */
    @Test
    public void testUsageSessionObserver_MaxObserverLimit() throws Exception {
        addUsageSessionObserver(OBS_ID1, GROUP1, TIME_30_MIN, TIME_1_MIN);
        boolean receivedException = false;
        int ANOTHER_UID = UID + 1;
        addUsageSessionObserver(OBS_ID2, GROUP1, TIME_30_MIN, TIME_1_MIN);
        addUsageSessionObserver(OBS_ID3, GROUP1, TIME_30_MIN, TIME_1_MIN);
        addUsageSessionObserver(OBS_ID4, GROUP1, TIME_30_MIN, TIME_1_MIN);
        addUsageSessionObserver(OBS_ID5, GROUP1, TIME_30_MIN, TIME_1_MIN);
        addUsageSessionObserver(OBS_ID6, GROUP1, TIME_30_MIN, TIME_1_MIN);
        addUsageSessionObserver(OBS_ID7, GROUP1, TIME_30_MIN, TIME_1_MIN);
        addUsageSessionObserver(OBS_ID8, GROUP1, TIME_30_MIN, TIME_1_MIN);
        addUsageSessionObserver(OBS_ID9, GROUP1, TIME_30_MIN, TIME_1_MIN);
        addUsageSessionObserver(OBS_ID10, GROUP1, TIME_30_MIN, TIME_1_MIN);
        // Readding an observer should not cause an IllegalStateException
        addUsageSessionObserver(OBS_ID5, GROUP1, TIME_30_MIN, TIME_1_MIN);
        // Adding an observer for a different uid shouldn't cause an IllegalStateException
        mController.addUsageSessionObserver(ANOTHER_UID, OBS_ID11, GROUP1, TIME_30_MIN, TIME_1_MIN,
                null, null, USER_ID);
        try {
            addUsageSessionObserver(OBS_ID11, GROUP1, TIME_30_MIN, TIME_1_MIN);
        } catch (IllegalStateException ise) {
            receivedException = true;
        }
        assertTrue("Should have caused an IllegalStateException", receivedException);
    }

    /**
     * Verify that App Time Limit Controller will limit the number of observerIds for app usage
     * limit observers
     */
    @Test
    public void testAppUsageLimitObserver_MaxObserverLimit() throws Exception {
        boolean receivedException = false;
        int ANOTHER_UID = UID + 1;
        addAppUsageLimitObserver(OBS_ID1, GROUP1, TIME_30_MIN, 0);
        addAppUsageLimitObserver(OBS_ID2, GROUP1, TIME_30_MIN, 0);
        addAppUsageLimitObserver(OBS_ID3, GROUP1, TIME_30_MIN, 0);
        addAppUsageLimitObserver(OBS_ID4, GROUP1, TIME_30_MIN, 0);
        addAppUsageLimitObserver(OBS_ID5, GROUP1, TIME_30_MIN, 0);
        addAppUsageLimitObserver(OBS_ID6, GROUP1, TIME_30_MIN, 0);
        addAppUsageLimitObserver(OBS_ID7, GROUP1, TIME_30_MIN, 0);
        addAppUsageLimitObserver(OBS_ID8, GROUP1, TIME_30_MIN, 0);
        addAppUsageLimitObserver(OBS_ID9, GROUP1, TIME_30_MIN, 0);
        addAppUsageLimitObserver(OBS_ID10, GROUP1, TIME_30_MIN, 0);
        // Readding an observer should not cause an IllegalStateException
        addAppUsageLimitObserver(OBS_ID5, GROUP1, TIME_30_MIN, 0);
        // Adding an observer for a different uid shouldn't cause an IllegalStateException
        mController.addAppUsageLimitObserver(
                ANOTHER_UID, OBS_ID11, GROUP1, TIME_30_MIN, 0, null, USER_ID);
        try {
            addAppUsageLimitObserver(OBS_ID11, GROUP1, TIME_30_MIN, 0);
        } catch (IllegalStateException ise) {
            receivedException = true;
        }
        assertTrue("Should have caused an IllegalStateException", receivedException);
    }

    /** Verify that addAppUsageObserver minimum time limit is one minute */
    @Test
    public void testAppUsageObserver_MinimumTimeLimit() throws Exception {
        boolean receivedException = false;
        // adding an observer with a one minute time limit should not cause an exception
        addAppUsageObserver(OBS_ID1, GROUP1, MIN_TIME_LIMIT);
        try {
            addAppUsageObserver(OBS_ID1, GROUP1, MIN_TIME_LIMIT - 1);
        } catch (IllegalArgumentException iae) {
            receivedException = true;
        }
        assertTrue("Should have caused an IllegalArgumentException", receivedException);
    }

    /** Verify that addUsageSessionObserver minimum time limit is one minute */
    @Test
    public void testUsageSessionObserver_MinimumTimeLimit() throws Exception {
        boolean receivedException = false;
        // test also for session observers
        addUsageSessionObserver(OBS_ID10, GROUP1, MIN_TIME_LIMIT, TIME_1_MIN);
        try {
            addUsageSessionObserver(OBS_ID10, GROUP1, MIN_TIME_LIMIT - 1, TIME_1_MIN);
        } catch (IllegalArgumentException iae) {
            receivedException = true;
        }
        assertTrue("Should have caused an IllegalArgumentException", receivedException);
    }

    /** Verify that addAppUsageLimitObserver minimum time limit is one minute */
    @Test
    public void testAppUsageLimitObserver_MinimumTimeLimit() throws Exception {
        boolean receivedException = false;
        // adding an observer with a one minute time limit should not cause an exception
        addAppUsageLimitObserver(OBS_ID1, GROUP1, MIN_TIME_LIMIT, 0);
        try {
            addAppUsageLimitObserver(OBS_ID1, GROUP1, MIN_TIME_LIMIT - 1, 0);
        } catch (IllegalArgumentException iae) {
            receivedException = true;
        }
        assertTrue("Should have caused an IllegalArgumentException", receivedException);
    }

    /** Verify that concurrent usage from multiple apps in the same group will counted correctly */
    @Test
    public void testAppUsageObserver_ConcurrentUsage() throws Exception {
        setTime(0L);
        addAppUsageObserver(OBS_ID1, GROUP1, TIME_30_MIN);
        AppTimeLimitController.UsageGroup group = mController.getAppUsageGroup(UID, OBS_ID1);
        startUsage(PKG_SOC1);
        // Add 10 mins
        setTime(TIME_10_MIN);

        // Add a different package in the group will first package is still in use
        startUsage(PKG_GAME1);
        setTime(TIME_10_MIN * 2);
        // Stop first package usage
        stopUsage(PKG_SOC1);

        setTime(TIME_30_MIN);
        stopUsage(PKG_GAME1);

        assertEquals(TIME_30_MIN, group.getUsageTimeMs());
        assertTrue(mLimitReachedLatch.await(100L, TimeUnit.MILLISECONDS));
    }

    /** Verify that concurrent usage from multiple apps in the same group will counted correctly */
    @Test
    public void testUsageSessionObserver_ConcurrentUsage() throws Exception {
        setTime(0L);
        addUsageSessionObserver(OBS_ID1, GROUP1, TIME_30_MIN, TIME_1_MIN);
        AppTimeLimitController.UsageGroup group = mController.getSessionUsageGroup(UID, OBS_ID1);
        startUsage(PKG_SOC1);
        // Add 10 mins
        setTime(TIME_10_MIN);

        // Add a different package in the group will first package is still in use
        startUsage(PKG_GAME1);
        setTime(TIME_10_MIN * 2);
        // Stop first package usage
        stopUsage(PKG_SOC1);

        setTime(TIME_30_MIN);
        stopUsage(PKG_GAME1);

        assertEquals(TIME_30_MIN, group.getUsageTimeMs());
        assertTrue(mLimitReachedLatch.await(100L, TimeUnit.MILLISECONDS));
    }

    /** Verify that concurrent usage from multiple apps in the same group will counted correctly */
    @Test
    public void testAppUsageLimitObserver_ConcurrentUsage() throws Exception {
        setTime(0L);
        addAppUsageLimitObserver(OBS_ID1, GROUP1, TIME_30_MIN, 0);
        AppTimeLimitController.UsageGroup group = getAppUsageLimitObserver(UID, OBS_ID1);
        startUsage(PKG_SOC1);
        // Add 10 mins
        setTime(TIME_10_MIN);

        // Add a different package in the group will first package is still in use
        startUsage(PKG_GAME1);
        setTime(TIME_10_MIN * 2);
        // Stop first package usage
        stopUsage(PKG_SOC1);

        setTime(TIME_30_MIN);
        stopUsage(PKG_GAME1);

        assertEquals(TIME_30_MIN, group.getUsageTimeMs());
        assertTrue(mLimitReachedLatch.await(100L, TimeUnit.MILLISECONDS));
    }

    /** Verify that a session will continue if usage starts again within the session threshold */
    @Test
    public void testUsageSessionObserver_ContinueSession() throws Exception {
        setTime(0L);
        addUsageSessionObserver(OBS_ID1, GROUP1, 10_000L, 2_000L);
        startUsage(PKG_SOC1);
        setTime(6_000L);
        stopUsage(PKG_SOC1);
        // Wait momentarily, Session should not end
        assertFalse(mSessionEndLatch.await(1_000L, TimeUnit.MILLISECONDS));

        setTime(7_000L);
        startUsage(PKG_SOC1);
        setTime(10_500L);
        stopUsage(PKG_SOC1);
        // Total usage time has not reached the limit. Time limit callback should not fire yet
        assertFalse(mLimitReachedLatch.await(100L, TimeUnit.MILLISECONDS));

        setTime(10_600L);
        startUsage(PKG_SOC1);
        setTime(12_000L);
        assertTrue(mLimitReachedLatch.await(1_000L, TimeUnit.MILLISECONDS));
        stopUsage(PKG_SOC1);
        // Usage has stopped, Session should end in 2 seconds. Verify session end occurs
        // (+/- 100ms, which is hopefully not too slim a margin)
        assertFalse(mSessionEndLatch.await(1_900L, TimeUnit.MILLISECONDS));
        assertTrue(mSessionEndLatch.await(200L, TimeUnit.MILLISECONDS));
        // Verify that the observer was not removed
        assertTrue(hasUsageSessionObserver(UID, OBS_ID1));
    }

    /** Verify that a new session will start if next usage starts after the session threshold */
    @Test
    public void testUsageSessionObserver_NewSession() throws Exception {
        setTime(0L);
        addUsageSessionObserver(OBS_ID1, GROUP1, 10_000L, 1_000L);
        startUsage(PKG_SOC1);
        setTime(6_000L);
        stopUsage(PKG_SOC1);
        // Wait for longer than the session threshold. Session end callback should not be triggered
        // because the usage timelimit hasn't been triggered.
        assertFalse(mSessionEndLatch.await(1_500L, TimeUnit.MILLISECONDS));

        setTime(7_500L);
        // This should be the start of a new session
        startUsage(PKG_SOC1);
        setTime(16_000L);
        stopUsage(PKG_SOC1);
        // Total usage has exceed the timelimit, but current session time has not
        assertFalse(mLimitReachedLatch.await(100L, TimeUnit.MILLISECONDS));

        setTime(16_100L);
        startUsage(PKG_SOC1);
        setTime(18_000L);
        assertTrue(mLimitReachedLatch.await(2000L, TimeUnit.MILLISECONDS));
        stopUsage(PKG_SOC1);
        // Usage has stopped, Session should end in 2 seconds. Verify session end occurs
        // (+/- 100ms, which is hopefully not too slim a margin)
        assertFalse(mSessionEndLatch.await(900L, TimeUnit.MILLISECONDS));
        assertTrue(mSessionEndLatch.await(200L, TimeUnit.MILLISECONDS));
        // Verify that the observer was not removed
        assertTrue(hasUsageSessionObserver(UID, OBS_ID1));
    }

    /** Verify that the callbacks will be triggered for multiple sessions */
    @Test
    public void testUsageSessionObserver_RepeatSessions() throws Exception {
        setTime(0L);
        addUsageSessionObserver(OBS_ID1, GROUP1, 10_000L, 1_000L);
        startUsage(PKG_SOC1);
        setTime(9_000L);
        stopUsage(PKG_SOC1);
        // Stutter usage here, to reduce real world time needed trigger limit reached callback
        startUsage(PKG_SOC1);
        setTime(11_000L);
        assertTrue(mLimitReachedLatch.await(2_000L, TimeUnit.MILLISECONDS));
        stopUsage(PKG_SOC1);
        // Usage has stopped, Session should end in 1 seconds. Verify session end occurs
        // (+/- 100ms, which is hopefully not too slim a margin)
        assertFalse(mSessionEndLatch.await(900L, TimeUnit.MILLISECONDS));
        assertTrue(mSessionEndLatch.await(200L, TimeUnit.MILLISECONDS));

        // Rearm the countdown latches
        mLimitReachedLatch = new CountDownLatch(1);
        mSessionEndLatch = new CountDownLatch(1);

        // New session start
        setTime(20_000L);
        startUsage(PKG_SOC1);
        setTime(29_000L);
        stopUsage(PKG_SOC1);
        startUsage(PKG_SOC1);
        setTime(31_000L);
        assertTrue(mLimitReachedLatch.await(2_000L, TimeUnit.MILLISECONDS));
        stopUsage(PKG_SOC1);
        assertFalse(mSessionEndLatch.await(900L, TimeUnit.MILLISECONDS));
        assertTrue(mSessionEndLatch.await(200L, TimeUnit.MILLISECONDS));
        assertTrue(hasUsageSessionObserver(UID, OBS_ID1));
    }

    /** Verify the timeout message is delivered at the right time after past usage was reported */
    @Test
    public void testAppUsageObserver_PastUsage() throws Exception {
        setTime(10_000L);
        addAppUsageObserver(OBS_ID1, GROUP1, 6_000L);
        setTime(20_000L);
        startPastUsage(PKG_SOC1, 5_000);
        setTime(21_000L);
        assertTrue(mLimitReachedLatch.await(2_000L, TimeUnit.MILLISECONDS));
        stopUsage(PKG_SOC1);
        // Verify that the observer was removed
        assertFalse(hasAppUsageObserver(UID, OBS_ID1));
    }

    /**
     * Verify the timeout message is delivered at the right time after past usage was reported
     * that overlaps with already known usage
     */
    @Test
    public void testAppUsageObserver_PastUsageOverlap() throws Exception {
        setTime(0L);
        addAppUsageObserver(OBS_ID1, GROUP1, 20_000L);
        setTime(10_000L);
        startUsage(PKG_SOC1);
        setTime(20_000L);
        stopUsage(PKG_SOC1);
        setTime(25_000L);
        startPastUsage(PKG_SOC1, 9_000);
        setTime(26_000L);
        // the 4 seconds of overlapped usage should not be counted
        assertFalse(mLimitReachedLatch.await(2_000L, TimeUnit.MILLISECONDS));
        setTime(30_000L);
        assertTrue(mLimitReachedLatch.await(4_000L, TimeUnit.MILLISECONDS));
        stopUsage(PKG_SOC1);
        // Verify that the observer was removed
        assertFalse(hasAppUsageObserver(UID, OBS_ID1));
    }

    /** Verify app usage limit observer added correctly reports its total usage limit */
    @Test
    public void testAppUsageLimitObserver_GetTotalUsageLimit() {
        addAppUsageLimitObserver(OBS_ID1, GROUP1, TIME_30_MIN, 0);
        AppTimeLimitController.AppUsageLimitGroup group = getAppUsageLimitObserver(UID, OBS_ID1);
        assertNotNull("Observer wasn't added", group);
        assertEquals("Observer didn't correctly report total usage limit",
                TIME_30_MIN, group.getTotaUsageLimit());
    }

    /** Verify app usage limit observer added correctly reports its total usage limit */
    @Test
    public void testAppUsageLimitObserver_GetUsageRemaining() {
        setTime(0L);
        addAppUsageLimitObserver(OBS_ID1, GROUP1, TIME_30_MIN, 0);
        startUsage(PKG_SOC1);
        setTime(TIME_10_MIN);
        stopUsage(PKG_SOC1);
        AppTimeLimitController.AppUsageLimitGroup group = getAppUsageLimitObserver(UID, OBS_ID1);
        assertNotNull("Observer wasn't added", group);
        assertEquals("Observer didn't correctly report total usage limit",
                TIME_10_MIN * 2, group.getUsageRemaining());
    }

    /** Verify the app usage limit observer with the smallest usage limit remaining is returned
     *  when querying the getAppUsageLimit API.
     */
    @Test
    public void testAppUsageLimitObserver_GetAppUsageLimit() {
        addAppUsageLimitObserver(OBS_ID1, GROUP1, TIME_30_MIN, 0);
        addAppUsageLimitObserver(OBS_ID2, GROUP_SOC, TIME_10_MIN, 0);
        UsageStatsManagerInternal.AppUsageLimitData group = getAppUsageLimit(PKG_SOC1);
        assertEquals("Observer with the smallest usage limit remaining wasn't returned",
                TIME_10_MIN, group.getTotalUsageLimit());
    }

    /** Verify the app usage limit observer with the smallest usage limit remaining is returned
     *  when querying the getAppUsageLimit API.
     */
    @Test
    public void testAppUsageLimitObserver_GetAppUsageLimitUsed() {
        setTime(0L);
        addAppUsageLimitObserver(OBS_ID1, GROUP1, TIME_30_MIN, 0);
        addAppUsageLimitObserver(OBS_ID2, GROUP_SOC, TIME_10_MIN, 0);
        startUsage(PKG_GAME1);
        setTime(TIME_10_MIN * 2 + TIME_1_MIN);
        stopUsage(PKG_GAME1);
        // PKG_GAME1 is only in GROUP1 but since we're querying for PCK_SOC1 which is
        // in both groups, GROUP1 should be returned since it has a smaller time remaining
        UsageStatsManagerInternal.AppUsageLimitData group = getAppUsageLimit(PKG_SOC1);
        assertEquals("Observer with the smallest usage limit remaining wasn't returned",
                TIME_1_MIN * 9, group.getUsageRemaining());
    }

    /** Verify the app usage limit observer with the smallest usage limit remaining is returned
     *  when querying the getAppUsageLimit API.
     */
    @Test
    public void testAppUsageLimitObserver_GetAppUsageLimitAllUsed() {
        setTime(0L);
        addAppUsageLimitObserver(OBS_ID1, GROUP1, TIME_30_MIN, 0);
        addAppUsageLimitObserver(OBS_ID2, GROUP_SOC, TIME_10_MIN, 0);
        startUsage(PKG_SOC1);
        setTime(TIME_10_MIN);
        stopUsage(PKG_SOC1);
        // GROUP_SOC should be returned since it should be completely used up (0ms remaining)
        UsageStatsManagerInternal.AppUsageLimitData group = getAppUsageLimit(PKG_SOC1);
        assertEquals("Observer with the smallest usage limit remaining wasn't returned",
                0L, group.getUsageRemaining());
    }

    /** Verify that a limit of 0 is not allowed. */
    @Test
    public void testAppUsageLimitObserver_ZeroTimeLimitIsNotAllowed() {
        try {
            addAppUsageLimitObserver(OBS_ID1, GROUP1, 0, 0);
            fail("timeLimit of 0 should not be allowed.");
        } catch (IllegalArgumentException expected) {
            // Exception expected.
        }
    }

    /** Verify that timeUsed can be the same as timeLimit (for re-registering observers). */
    @Test
    public void testAppUsageLimitObserver_ZeroTimeRemainingIsAllowed() {
        addAppUsageLimitObserver(OBS_ID1, GROUP1, TIME_1_MIN, TIME_1_MIN);
        AppTimeLimitController.AppUsageLimitGroup group = getAppUsageLimitObserver(UID, OBS_ID1);
        assertNotNull("Observer wasn't added", group);
        assertEquals("Usage remaining was not 0.", 0, group.getUsageRemaining());
    }

    private void startUsage(String packageName) {
        mController.noteUsageStart(packageName, USER_ID);
    }

    private void startPastUsage(String packageName, int timeAgo) {
        mController.noteUsageStart(packageName, USER_ID, timeAgo);
    }

    private void stopUsage(String packageName) {
        mController.noteUsageStop(packageName, USER_ID);
    }

    private void addAppUsageObserver(int observerId, String[] packages, long timeLimit) {
        mController.addAppUsageObserver(UID, observerId, packages, timeLimit, null, USER_ID);
    }

    private void addUsageSessionObserver(int observerId, String[] packages, long timeLimit,
            long sessionThreshold) {
        mController.addUsageSessionObserver(UID, observerId, packages, timeLimit, sessionThreshold,
                null, null, USER_ID);
    }

    private void addAppUsageLimitObserver(int observerId, String[] packages, long timeLimit,
            long timeUsed) {
        mController.addAppUsageLimitObserver(UID, observerId, packages, timeLimit, timeUsed,
                null, USER_ID);
    }

    /** Is there still an app usage observer by that id */
    private boolean hasAppUsageObserver(int uid, int observerId) {
        return mController.getAppUsageGroup(uid, observerId) != null;
    }

    /** Is there still an usage session observer by that id */
    private boolean hasUsageSessionObserver(int uid, int observerId) {
        return mController.getSessionUsageGroup(uid, observerId) != null;
    }

    /** Is there still an app usage limit observer by that id */
    private boolean hasAppUsageLimitObserver(int uid, int observerId) {
        return mController.getAppUsageLimitGroup(uid, observerId) != null;
    }

    private AppTimeLimitController.AppUsageLimitGroup getAppUsageLimitObserver(
            int uid, int observerId) {
        return mController.getAppUsageLimitGroup(uid, observerId);
    }

    private UsageStatsManagerInternal.AppUsageLimitData getAppUsageLimit(String packageName) {
        return mController.getAppUsageLimit(packageName, UserHandle.of(USER_ID));
    }

    private void setTime(long time) {
        mUptimeMillis = time;
    }
}
