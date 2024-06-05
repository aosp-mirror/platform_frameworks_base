/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Test class for {@link SingleKeyGestureDetector}.
 *
 * Build/Install/Run:
 *  atest WmTests:SingleKeyGestureTests
 */
public class SingleKeyGestureTests {
    private SingleKeyGestureDetector mDetector;

    private int mMaxMultiPressCount = 3;
    private int mExpectedMultiPressCount = 2;

    private CountDownLatch mShortPressed = new CountDownLatch(1);
    private CountDownLatch mLongPressed = new CountDownLatch(1);
    private CountDownLatch mVeryLongPressed = new CountDownLatch(1);
    private CountDownLatch mMultiPressed = new CountDownLatch(1);
    private BlockingQueue<KeyUpData> mKeyUpQueue = new LinkedBlockingQueue<>();

    private final Instrumentation mInstrumentation = getInstrumentation();
    private final Context mContext = mInstrumentation.getTargetContext();
    private long mWaitTimeout;
    private long mLongPressTime;
    private long mVeryLongPressTime;

    // Allow press from non interactive mode.
    private boolean mAllowNonInteractiveForPress = true;
    private boolean mAllowNonInteractiveForLongPress = true;

    private boolean mLongPressOnPowerBehavior = true;
    private boolean mVeryLongPressOnPowerBehavior = true;
    private boolean mLongPressOnBackBehavior = false;

    @Before
    public void setUp() {
        mInstrumentation.runOnMainSync(
                () -> {
                    mDetector = SingleKeyGestureDetector.get(mContext, Looper.myLooper());
                    initSingleKeyGestureRules();
                });

        mWaitTimeout = SingleKeyGestureDetector.MULTI_PRESS_TIMEOUT + 50;
        mLongPressTime = SingleKeyGestureDetector.sDefaultLongPressTimeout + 50;
        mVeryLongPressTime = SingleKeyGestureDetector.sDefaultVeryLongPressTimeout + 50;
    }

    private void initSingleKeyGestureRules() {
        mDetector.addRule(
                new SingleKeyGestureDetector.SingleKeyRule(KEYCODE_POWER) {
                    @Override
                    boolean supportLongPress() {
                        return mLongPressOnPowerBehavior;
                    }

                    @Override
                    boolean supportVeryLongPress() {
                        return mVeryLongPressOnPowerBehavior;
                    }

                    @Override
                    int getMaxMultiPressCount() {
                        return mMaxMultiPressCount;
                    }

                    @Override
                    public void onPress(long downTime, int displayId) {
                        if (mDetector.beganFromNonInteractive() && !mAllowNonInteractiveForPress) {
                            return;
                        }
                        mShortPressed.countDown();
                    }

                    @Override
                    void onLongPress(long downTime) {
                        if (mDetector.beganFromNonInteractive()
                                && !mAllowNonInteractiveForLongPress) {
                            return;
                        }
                        mLongPressed.countDown();
                    }

                    @Override
                    void onVeryLongPress(long downTime) {
                        mVeryLongPressed.countDown();
                    }

                    @Override
                    void onMultiPress(long downTime, int count, int displayId) {
                        if (mDetector.beganFromNonInteractive() && !mAllowNonInteractiveForPress) {
                            return;
                        }
                        mMultiPressed.countDown();
                        assertTrue(mMaxMultiPressCount >= count);
                        assertEquals(mExpectedMultiPressCount, count);
                    }

                    @Override
                    void onKeyUp(long eventTime, int multiPressCount, int displayId) {
                        mKeyUpQueue.add(new KeyUpData(KEYCODE_POWER, multiPressCount));
                    }
                });

        mDetector.addRule(
                new SingleKeyGestureDetector.SingleKeyRule(KEYCODE_BACK) {
                    @Override
                    boolean supportLongPress() {
                        return mLongPressOnBackBehavior;
                    }

                    @Override
                    int getMaxMultiPressCount() {
                        return mMaxMultiPressCount;
                    }

                    @Override
                    public void onPress(long downTime, int displayId) {
                        if (mDetector.beganFromNonInteractive() && !mAllowNonInteractiveForPress) {
                            return;
                        }
                        mShortPressed.countDown();
                    }

                    @Override
                    void onMultiPress(long downTime, int count, int displayId) {
                        if (mDetector.beganFromNonInteractive() && !mAllowNonInteractiveForPress) {
                            return;
                        }
                        mMultiPressed.countDown();
                        assertTrue(mMaxMultiPressCount >= count);
                        assertEquals(mExpectedMultiPressCount, count);
                    }

                    @Override
                    void onKeyUp(long eventTime, int multiPressCount, int displayId) {
                        mKeyUpQueue.add(new KeyUpData(KEYCODE_BACK, multiPressCount));
                    }

                    @Override
                    void onLongPress(long downTime) {
                        mLongPressed.countDown();
                    }
                });
    }

    private static class KeyUpData {
        public final int keyCode;
        public final int pressCount;

        KeyUpData(int keyCode, int pressCount) {
            this.keyCode = keyCode;
            this.pressCount = pressCount;
        }
    }

    private void pressKey(int keyCode, long pressTime) {
        pressKey(keyCode, pressTime, true /* interactive */);
    }

    private void pressKey(int keyCode, long pressTime, boolean interactive) {
        pressKey(keyCode, pressTime, interactive, false /* defaultDisplayOn */);
    }

    private void pressKey(
            int keyCode, long pressTime, boolean interactive, boolean defaultDisplayOn) {
        long eventTime = SystemClock.uptimeMillis();
        final KeyEvent keyDown =
                new KeyEvent(
                        eventTime,
                        eventTime,
                        ACTION_DOWN,
                        keyCode,
                        0 /* repeat */,
                        0 /* metaState */);
        mDetector.interceptKey(keyDown, interactive, defaultDisplayOn);

        // keep press down.
        try {
            Thread.sleep(pressTime);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        eventTime += pressTime;
        final KeyEvent keyUp =
                new KeyEvent(
                        eventTime,
                        eventTime,
                        ACTION_UP,
                        keyCode,
                        0 /* repeat */,
                        0 /* metaState */);

        mDetector.interceptKey(keyUp, interactive, defaultDisplayOn);
    }

    @Test
    public void testShortPress() throws InterruptedException {
        pressKey(KEYCODE_POWER, 0 /* pressTime */);
        assertTrue(mShortPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testLongPress() throws InterruptedException {
        pressKey(KEYCODE_POWER, mLongPressTime);
        assertTrue(mLongPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testVeryLongPress() throws InterruptedException {
        pressKey(KEYCODE_POWER, mVeryLongPressTime);
        assertTrue(mVeryLongPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testMultiPress() throws InterruptedException {
        // Double presses.
        mExpectedMultiPressCount = 2;
        pressKey(KEYCODE_POWER, 0 /* pressTime */);
        pressKey(KEYCODE_POWER, 0 /* pressTime */);
        assertTrue(mMultiPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));

        // Triple presses.
        mExpectedMultiPressCount = 3;
        mMultiPressed = new CountDownLatch(1);
        pressKey(KEYCODE_POWER, 0 /* pressTime */);
        pressKey(KEYCODE_POWER, 0 /* pressTime */);
        pressKey(KEYCODE_POWER, 0 /* pressTime */);
        assertTrue(mMultiPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testOnKeyUp() throws InterruptedException {
        pressKey(KEYCODE_POWER, 0 /* pressTime */);

        verifyKeyUpData(KEYCODE_POWER, 1 /* expectedMultiPressCount */);
    }

    private void verifyKeyUpData(int expectedKeyCode, int expectedMultiPressCount)
            throws InterruptedException {
        KeyUpData keyUpData = mKeyUpQueue.poll(mWaitTimeout, TimeUnit.MILLISECONDS);
        assertNotNull(keyUpData);
        assertEquals(expectedKeyCode, keyUpData.keyCode);
        assertEquals(expectedMultiPressCount, keyUpData.pressCount);
    }

    @Test
    public void testNonInteractive() throws InterruptedException {
        // Disallow short press behavior from non interactive.
        mAllowNonInteractiveForPress = false;
        pressKey(KEYCODE_POWER, 0 /* pressTime */, false /* interactive */);
        assertFalse(mShortPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));

        // Allow long press behavior from non interactive.
        pressKey(KEYCODE_POWER, mLongPressTime, false /* interactive */);
        assertTrue(mLongPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testShortPress_Pressure() throws InterruptedException {
        final HandlerThread handlerThread =
                new HandlerThread("testInputReader", Process.THREAD_PRIORITY_DISPLAY);
        handlerThread.start();
        Handler newHandler = new Handler(handlerThread.getLooper());
        mMaxMultiPressCount = 1; // Will trigger short press when event up.
        try {
            // To make sure we won't get any crash while panic pressing keys.
            for (int i = 0; i < 100; i++) {
                mShortPressed = new CountDownLatch(2);
                newHandler.runWithScissors(
                        () -> {
                            pressKey(KEYCODE_POWER, 0 /* pressTime */);
                            pressKey(KEYCODE_BACK, 0 /* pressTime */);
                        },
                        mWaitTimeout);
                assertTrue(mShortPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
            }
        } finally {
            handlerThread.quitSafely();
        }
    }

    @Test
    public void testMultiPress_Pressure() throws InterruptedException {
        final HandlerThread handlerThread =
                new HandlerThread("testInputReader", Process.THREAD_PRIORITY_DISPLAY);
        handlerThread.start();
        Handler newHandler = new Handler(handlerThread.getLooper());
        try {
            // To make sure we won't get any unexpected multi-press count.
            for (int i = 0; i < 5; i++) {
                mMultiPressed = new CountDownLatch(1);
                mShortPressed = new CountDownLatch(1);
                newHandler.runWithScissors(
                        () -> {
                            pressKey(KEYCODE_POWER, 0 /* pressTime */);
                            pressKey(KEYCODE_POWER, 0 /* pressTime */);
                        },
                        mWaitTimeout);
                assertTrue(mMultiPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));

                newHandler.runWithScissors(
                        () -> pressKey(KEYCODE_POWER, 0 /* pressTime */), mWaitTimeout);
                assertTrue(mShortPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
            }
        } finally {
            handlerThread.quitSafely();
        }
    }

    @Test
    public void testOnKeyUp_Pressure() throws InterruptedException {
        final HandlerThread handlerThread =
                new HandlerThread("testInputReader", Process.THREAD_PRIORITY_DISPLAY);
        handlerThread.start();
        Handler newHandler = new Handler(handlerThread.getLooper());
        try {
            // To make sure we won't get any unexpected multi-press count.
            for (int i = 0; i < 5; i++) {
                newHandler.runWithScissors(
                        () -> {
                            pressKey(KEYCODE_POWER, 0 /* pressTime */);
                            pressKey(KEYCODE_POWER, 0 /* pressTime */);
                        },
                        mWaitTimeout);
                newHandler.runWithScissors(
                        () -> pressKey(KEYCODE_BACK, 0 /* pressTime */), mWaitTimeout);

                verifyKeyUpData(KEYCODE_POWER, 1 /* expectedMultiPressCount */);
                verifyKeyUpData(KEYCODE_POWER, 2 /* expectedMultiPressCount */);
                verifyKeyUpData(KEYCODE_BACK, 1 /* expectedMultiPressCount */);
            }
        } finally {
            handlerThread.quitSafely();
        }
    }

    @Test
    public void testUpdateRule() throws InterruptedException {
        // Power key rule doesn't allow the long press gesture.
        mLongPressOnPowerBehavior = false;
        pressKey(KEYCODE_POWER, mLongPressTime);
        assertFalse(mLongPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));

        // Back key rule allows the long press gesture.
        mLongPressOnBackBehavior = true;
        pressKey(KEYCODE_BACK, mLongPressTime);
        assertTrue(mLongPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testAddRemove() throws InterruptedException {
        final SingleKeyGestureDetector.SingleKeyRule rule =
                new SingleKeyGestureDetector.SingleKeyRule(KEYCODE_POWER) {
                    @Override
                    void onPress(long downTime, int displayId) {
                        mShortPressed.countDown();
                    }
                };

        mDetector.removeRule(rule);
        pressKey(KEYCODE_POWER, 0 /* pressTime */);
        assertFalse(mShortPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));

        mDetector.addRule(rule);
        pressKey(KEYCODE_POWER, 0 /* pressTime */);
        assertTrue(mShortPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
    }

    // Verify short press should not be triggered if no very long press behavior defined but the
    // press time exceeded the very long press timeout.
    @Test
    public void testTimeoutExceedVeryLongPress() throws InterruptedException {
        mVeryLongPressOnPowerBehavior = false;

        pressKey(KEYCODE_POWER, mVeryLongPressTime + 50);
        assertTrue(mLongPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
        assertEquals(mVeryLongPressed.getCount(), 1);
        assertEquals(mShortPressed.getCount(), 1);
    }
}
