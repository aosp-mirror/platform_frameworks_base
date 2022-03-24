/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.policy;

import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.KEYCODE_BACK;
import static android.view.KeyEvent.KEYCODE_POWER;
import static android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
import static android.view.KeyEvent.KEYCODE_VOLUME_UP;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test class for {@link KeyCombinationManager}.
 *
 * Build/Install/Run:
 *  atest KeyCombinationTests
 */

@SmallTest
public class KeyCombinationTests {
    private KeyCombinationManager mKeyCombinationManager;

    private final CountDownLatch mAction1Triggered = new CountDownLatch(1);
    private final CountDownLatch mAction2Triggered = new CountDownLatch(1);
    private final CountDownLatch mAction3Triggered = new CountDownLatch(1);

    private boolean mPreCondition = true;
    private static final long SCHEDULE_TIME = 300;
    private Handler mHandler;

    @Before
    public void setUp() {
        mHandler = new Handler(Looper.getMainLooper());
        mKeyCombinationManager = new KeyCombinationManager(mHandler);
        initKeyCombinationRules();
    }

    private void initKeyCombinationRules() {
        // Rule 1 : power + volume_down trigger action immediately.
        mKeyCombinationManager.addRule(
                new KeyCombinationManager.TwoKeysCombinationRule(KEYCODE_VOLUME_DOWN,
                        KEYCODE_POWER) {
                    @Override
                    void execute() {
                        mAction1Triggered.countDown();
                    }

                    @Override
                    void cancel() {
                    }
                });

        // Rule 2 : volume_up + volume_down with condition.
        mKeyCombinationManager.addRule(
                new KeyCombinationManager.TwoKeysCombinationRule(KEYCODE_VOLUME_DOWN,
                        KEYCODE_VOLUME_UP) {
                    @Override
                    boolean preCondition() {
                        return mPreCondition;
                    }

                    @Override
                    void execute() {
                        mAction2Triggered.countDown();
                    }

                    @Override
                    void cancel() {
                    }

                    @Override
                    long getKeyInterceptDelayMs() {
                        return 0;
                    }
                });

        // Rule 3 : power + volume_up schedule and trigger action after timeout.
        mKeyCombinationManager.addRule(
                new KeyCombinationManager.TwoKeysCombinationRule(KEYCODE_VOLUME_UP, KEYCODE_POWER) {
                    final Runnable mAction = new Runnable() {
                        @Override
                        public void run() {
                            mAction3Triggered.countDown();
                        }
                    };

                    @Override
                    void execute() {
                        mHandler.postDelayed(mAction, SCHEDULE_TIME);
                    }

                    @Override
                    void cancel() {
                        mHandler.removeCallbacks(mAction);
                    }
                });
    }

    private void pressKeys(long firstKeyTime, int firstKeyCode, long secondKeyTime,
            int secondKeyCode) {
        pressKeys(firstKeyTime, firstKeyCode, secondKeyTime, secondKeyCode, 0);
    }

    private void pressKeys(long firstKeyTime, int firstKeyCode, long secondKeyTime,
            int secondKeyCode, long pressTime) {
        final KeyEvent firstKeyDown = new KeyEvent(firstKeyTime, firstKeyTime, ACTION_DOWN,
                firstKeyCode, 0 /* repeat */, 0 /* metaState */);
        final KeyEvent secondKeyDown = new KeyEvent(secondKeyTime, secondKeyTime, ACTION_DOWN,
                secondKeyCode, 0 /* repeat */, 0 /* metaState */);

        mKeyCombinationManager.interceptKey(firstKeyDown, true);
        mKeyCombinationManager.interceptKey(secondKeyDown, true);

        // keep press down.
        try {
            Thread.sleep(pressTime);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        final KeyEvent firstKeyUp = new KeyEvent(firstKeyTime, firstKeyTime, ACTION_UP,
                firstKeyCode, 0 /* repeat */, 0 /* metaState */);
        final KeyEvent secondKeyUp = new KeyEvent(secondKeyTime, secondKeyTime, ACTION_UP,
                secondKeyCode, 0 /* repeat */, 0 /* metaState */);

        mKeyCombinationManager.interceptKey(firstKeyUp, true);
        mKeyCombinationManager.interceptKey(secondKeyUp, true);
    }

    @Test
    public void testTriggerRule() throws InterruptedException {
        final long eventTime = SystemClock.uptimeMillis();
        pressKeys(eventTime, KEYCODE_POWER, eventTime, KEYCODE_VOLUME_DOWN);
        assertTrue(mAction1Triggered.await(SCHEDULE_TIME, TimeUnit.MILLISECONDS));

        pressKeys(eventTime, KEYCODE_VOLUME_UP, eventTime, KEYCODE_VOLUME_DOWN);
        assertTrue(mAction2Triggered.await(SCHEDULE_TIME, TimeUnit.MILLISECONDS));

        pressKeys(eventTime, KEYCODE_POWER, eventTime, KEYCODE_VOLUME_UP, SCHEDULE_TIME + 50);
        assertTrue(mAction3Triggered.await(SCHEDULE_TIME, TimeUnit.MILLISECONDS));
    }

    /**
     *  Nothing should happen if there is no definition.
     */
    @Test
    public void testNotTrigger_NoRule() throws InterruptedException {
        final long eventTime = SystemClock.uptimeMillis();
        pressKeys(eventTime, KEYCODE_BACK, eventTime, KEYCODE_VOLUME_DOWN);
        assertFalse(mAction1Triggered.await(SCHEDULE_TIME, TimeUnit.MILLISECONDS));
        assertFalse(mAction2Triggered.await(SCHEDULE_TIME, TimeUnit.MILLISECONDS));
        assertFalse(mAction3Triggered.await(SCHEDULE_TIME, TimeUnit.MILLISECONDS));
    }

    /**
     *  Nothing should happen if the interval of press time is too long.
     */
    @Test
    public void testNotTrigger_Interval() throws InterruptedException {
        final long eventTime = SystemClock.uptimeMillis();
        final long earlyEventTime = eventTime - 200; // COMBINE_KEY_DELAY_MILLIS = 150;
        pressKeys(earlyEventTime, KEYCODE_POWER, eventTime, KEYCODE_VOLUME_DOWN);
        assertFalse(mAction1Triggered.await(SCHEDULE_TIME, TimeUnit.MILLISECONDS));
    }

    /**
     *  Nothing should happen if the condition is false.
     */
    @Test
    public void testNotTrigger_Condition() throws InterruptedException {
        final long eventTime = SystemClock.uptimeMillis();
        // we won't trigger action 2 because the condition is false.
        mPreCondition = false;
        pressKeys(eventTime, KEYCODE_VOLUME_UP, eventTime, KEYCODE_VOLUME_DOWN);
        assertFalse(mAction2Triggered.await(SCHEDULE_TIME, TimeUnit.MILLISECONDS));
    }

    /**
     *  Nothing should happen if the keys released too early.
     */
    @Test
    public void testNotTrigger_EarlyRelease() throws InterruptedException {
        final long eventTime = SystemClock.uptimeMillis();
        pressKeys(eventTime, KEYCODE_POWER, eventTime, KEYCODE_VOLUME_UP);
        assertFalse(mAction3Triggered.await(SCHEDULE_TIME, TimeUnit.MILLISECONDS));
    }

    /**
     *  The KeyInterceptTimeout should return the max timeout value.
     */
    @Test
    public void testKeyInterceptTimeout() {
        final long eventTime = SystemClock.uptimeMillis();
        final KeyEvent firstKeyDown = new KeyEvent(eventTime, eventTime, ACTION_DOWN,
                KEYCODE_VOLUME_UP, 0 /* repeat */, 0 /* metaState */);
        // Press KEYCODE_VOLUME_UP would activate rule2 and rule3,
        // and rule2's intercept delay is 0.
        mKeyCombinationManager.interceptKey(firstKeyDown, true);
        assertTrue(mKeyCombinationManager.getKeyInterceptTimeout(KEYCODE_VOLUME_UP) > eventTime);
    }
}