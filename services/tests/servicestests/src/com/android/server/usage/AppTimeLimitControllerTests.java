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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.PendingIntent;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@MediumTest
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

    private final CountDownLatch mCountDownLatch = new CountDownLatch(1);

    private AppTimeLimitController mController;

    private HandlerThread mThread;

    private long mUptimeMillis;

    AppTimeLimitController.OnLimitReachedListener mListener
            = new AppTimeLimitController.OnLimitReachedListener() {

        @Override
        public void onLimitReached(int observerId, int userId, long timeLimit, long timeElapsed,
                PendingIntent callbackIntent) {
            mCountDownLatch.countDown();
        }
    };

    class MyAppTimeLimitController extends AppTimeLimitController {
        MyAppTimeLimitController(AppTimeLimitController.OnLimitReachedListener listener,
                Looper looper) {
            super(listener, looper);
        }

        @Override
        protected long getUptimeMillis() {
            return mUptimeMillis;
        }

        @Override
        protected long getObserverPerUidLimit() {
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

    /** Verify observer is added */
    @Test
    public void testAddObserver() {
        addObserver(OBS_ID1, GROUP1, TIME_30_MIN);
        assertTrue("Observer wasn't added", hasObserver(OBS_ID1));
        addObserver(OBS_ID2, GROUP_GAME, TIME_30_MIN);
        assertTrue("Observer wasn't added", hasObserver(OBS_ID2));
        assertTrue("Observer wasn't added", hasObserver(OBS_ID1));
    }

    /** Verify observer is removed */
    @Test
    public void testRemoveObserver() {
        addObserver(OBS_ID1, GROUP1, TIME_30_MIN);
        assertTrue("Observer wasn't added", hasObserver(OBS_ID1));
        mController.removeObserver(UID, OBS_ID1, USER_ID);
        assertFalse("Observer wasn't removed", hasObserver(OBS_ID1));
    }

    /** Re-adding an observer should result in only one copy */
    @Test
    public void testObserverReAdd() {
        addObserver(OBS_ID1, GROUP1, TIME_30_MIN);
        assertTrue("Observer wasn't added", hasObserver(OBS_ID1));
        addObserver(OBS_ID1, GROUP1, TIME_10_MIN);
        assertTrue("Observer wasn't added",
                mController.getObserverGroup(OBS_ID1, USER_ID).timeLimit == TIME_10_MIN);
        mController.removeObserver(UID, OBS_ID1, USER_ID);
        assertFalse("Observer wasn't removed", hasObserver(OBS_ID1));
    }

    /** Verify that usage across different apps within a group are added up */
    @Test
    public void testAccumulation() throws Exception {
        setTime(0L);
        addObserver(OBS_ID1, GROUP1, TIME_30_MIN);
        moveToForeground(PKG_SOC1);
        // Add 10 mins
        setTime(TIME_10_MIN);
        moveToBackground(PKG_SOC1);

        long timeRemaining = mController.getObserverGroup(OBS_ID1, USER_ID).timeRemaining;
        assertEquals(TIME_10_MIN * 2, timeRemaining);

        moveToForeground(PKG_SOC1);
        setTime(TIME_10_MIN * 2);
        moveToBackground(PKG_SOC1);

        timeRemaining = mController.getObserverGroup(OBS_ID1, USER_ID).timeRemaining;
        assertEquals(TIME_10_MIN, timeRemaining);

        setTime(TIME_30_MIN);

        assertFalse(mCountDownLatch.await(100L, TimeUnit.MILLISECONDS));

        // Add a different package in the group
        moveToForeground(PKG_GAME1);
        setTime(TIME_30_MIN + TIME_10_MIN);
        moveToBackground(PKG_GAME1);

        assertEquals(0, mController.getObserverGroup(OBS_ID1, USER_ID).timeRemaining);
        assertTrue(mCountDownLatch.await(100L, TimeUnit.MILLISECONDS));
    }

    /** Verify that time limit does not get triggered due to a different app */
    @Test
    public void testTimeoutOtherApp() throws Exception {
        setTime(0L);
        addObserver(OBS_ID1, GROUP1, 4_000L);
        moveToForeground(PKG_SOC2);
        assertFalse(mCountDownLatch.await(6_000L, TimeUnit.MILLISECONDS));
        setTime(6_000L);
        moveToBackground(PKG_SOC2);
        assertFalse(mCountDownLatch.await(100L, TimeUnit.MILLISECONDS));
    }

    /** Verify the timeout message is delivered at the right time */
    @Test
    public void testTimeout() throws Exception {
        setTime(0L);
        addObserver(OBS_ID1, GROUP1, 4_000L);
        moveToForeground(PKG_SOC1);
        setTime(6_000L);
        assertTrue(mCountDownLatch.await(6_000L, TimeUnit.MILLISECONDS));
        moveToBackground(PKG_SOC1);
        // Verify that the observer was removed
        assertFalse(hasObserver(OBS_ID1));
    }

    /** If an app was already running, make sure it is partially counted towards the time limit */
    @Test
    public void testAlreadyRunning() throws Exception {
        setTime(TIME_10_MIN);
        moveToForeground(PKG_GAME1);
        setTime(TIME_30_MIN);
        addObserver(OBS_ID2, GROUP_GAME, TIME_30_MIN);
        setTime(TIME_30_MIN + TIME_10_MIN);
        moveToBackground(PKG_GAME1);
        assertFalse(mCountDownLatch.await(1000L, TimeUnit.MILLISECONDS));

        moveToForeground(PKG_GAME2);
        setTime(TIME_30_MIN + TIME_30_MIN);
        moveToBackground(PKG_GAME2);
        assertTrue(mCountDownLatch.await(1000L, TimeUnit.MILLISECONDS));
        // Verify that the observer was removed
        assertFalse(hasObserver(OBS_ID2));
    }

    /** If watched app is already running, verify the timeout callback happens at the right time */
    @Test
    public void testAlreadyRunningTimeout() throws Exception {
        setTime(0);
        moveToForeground(PKG_SOC1);
        setTime(TIME_10_MIN);
        // 10 second time limit
        addObserver(OBS_ID1, GROUP_SOC, 10_000L);
        setTime(TIME_10_MIN + 5_000L);
        // Shouldn't call back in 6 seconds
        assertFalse(mCountDownLatch.await(6_000L, TimeUnit.MILLISECONDS));
        setTime(TIME_10_MIN + 10_000L);
        // Should call back by 11 seconds (6 earlier + 5 now)
        assertTrue(mCountDownLatch.await(5_000L, TimeUnit.MILLISECONDS));
        // Verify that the observer was removed
        assertFalse(hasObserver(OBS_ID1));
    }

    /** Verify that App Time Limit Controller will limit the number of observerIds */
    @Test
    public void testMaxObserverLimit() throws Exception {
        boolean receivedException = false;
        int ANOTHER_UID = UID + 1;
        addObserver(OBS_ID1, GROUP1, TIME_30_MIN);
        addObserver(OBS_ID2, GROUP1, TIME_30_MIN);
        addObserver(OBS_ID3, GROUP1, TIME_30_MIN);
        addObserver(OBS_ID4, GROUP1, TIME_30_MIN);
        addObserver(OBS_ID5, GROUP1, TIME_30_MIN);
        addObserver(OBS_ID6, GROUP1, TIME_30_MIN);
        addObserver(OBS_ID7, GROUP1, TIME_30_MIN);
        addObserver(OBS_ID8, GROUP1, TIME_30_MIN);
        addObserver(OBS_ID9, GROUP1, TIME_30_MIN);
        addObserver(OBS_ID10, GROUP1, TIME_30_MIN);
        // Readding an observer should not cause an IllegalStateException
        addObserver(OBS_ID5, GROUP1, TIME_30_MIN);
        // Adding an observer for a different uid shouldn't cause an IllegalStateException
        mController.addObserver(ANOTHER_UID, OBS_ID11, GROUP1, TIME_30_MIN, null, USER_ID);
        try {
            addObserver(OBS_ID11, GROUP1, TIME_30_MIN);
        } catch (IllegalStateException ise) {
            receivedException = true;
        }
        assertTrue("Should have caused an IllegalStateException", receivedException);
    }

    /** Verify that addObserver minimum time limit is one minute */
    @Test
    public void testMinimumTimeLimit() throws Exception {
        boolean receivedException = false;
        // adding an observer with a one minute time limit should not cause an exception
        addObserver(OBS_ID1, GROUP1, MIN_TIME_LIMIT);
        try {
            addObserver(OBS_ID1, GROUP1, MIN_TIME_LIMIT - 1);
        } catch (IllegalArgumentException iae) {
            receivedException = true;
        }
        assertTrue("Should have caused an IllegalArgumentException", receivedException);
    }

    private void moveToForeground(String packageName) {
        mController.moveToForeground(packageName, "class", USER_ID);
    }

    private void moveToBackground(String packageName) {
        mController.moveToBackground(packageName, "class", USER_ID);
    }

    private void addObserver(int observerId, String[] packages, long timeLimit) {
        mController.addObserver(UID, observerId, packages, timeLimit, null, USER_ID);
    }

    /** Is there still an observer by that id */
    private boolean hasObserver(int observerId) {
        return mController.getObserverGroup(observerId, USER_ID) != null;
    }

    private void setTime(long time) {
        mUptimeMillis = time;
    }
}
