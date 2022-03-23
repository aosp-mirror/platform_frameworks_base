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
import static android.view.KeyEvent.KEYCODE_POWER;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.policy.SingleKeyGestureDetector.KEY_LONGPRESS;
import static com.android.server.policy.SingleKeyGestureDetector.KEY_VERYLONGPRESS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test class for {@link SingleKeyGestureDetector}.
 *
 * Build/Install/Run:
 *  atest WmTests:SingleKeyGestureTests
 */
public class SingleKeyGestureTests {
    private SingleKeyGestureDetector mDetector;

    private int mMaxMultiPressPowerCount = 2;

    private CountDownLatch mShortPressed = new CountDownLatch(1);
    private CountDownLatch mLongPressed = new CountDownLatch(1);
    private CountDownLatch mVeryLongPressed = new CountDownLatch(1);
    private CountDownLatch mMultiPressed = new CountDownLatch(1);

    private final Instrumentation mInstrumentation = getInstrumentation();
    private final Context mContext = mInstrumentation.getTargetContext();
    private long mWaitTimeout;
    private long mLongPressTime;
    private long mVeryLongPressTime;

    // Allow press from non interactive mode.
    private boolean mAllowNonInteractiveForPress = true;
    private boolean mAllowNonInteractiveForLongPress = true;

    @Before
    public void setUp() {
        mDetector = new SingleKeyGestureDetector(mContext);
        initSingleKeyGestureRules();
        mWaitTimeout = ViewConfiguration.getMultiPressTimeout() + 50;
        mLongPressTime = ViewConfiguration.get(mContext).getDeviceGlobalActionKeyTimeout() + 50;
        mVeryLongPressTime = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_veryLongPressTimeout) + 50;
    }

    private void initSingleKeyGestureRules() {
        mDetector.addRule(new SingleKeyGestureDetector.SingleKeyRule(KEYCODE_POWER,
                KEY_LONGPRESS | KEY_VERYLONGPRESS) {
            @Override
            int getMaxMultiPressCount() {
                return mMaxMultiPressPowerCount;
            }
            @Override
            public void onPress(long downTime) {
                if (mDetector.beganFromNonInteractive() && !mAllowNonInteractiveForPress) {
                    return;
                }
                mShortPressed.countDown();
            }

            @Override
            void onLongPress(long downTime) {
                if (mDetector.beganFromNonInteractive() && !mAllowNonInteractiveForLongPress) {
                    return;
                }
                mLongPressed.countDown();
            }

            @Override
            void onVeryLongPress(long downTime) {
                mVeryLongPressed.countDown();
            }

            @Override
            void onMultiPress(long downTime, int count) {
                if (mDetector.beganFromNonInteractive() && !mAllowNonInteractiveForPress) {
                    return;
                }
                mMultiPressed.countDown();
                assertEquals(mMaxMultiPressPowerCount, count);
            }
        });
    }

    private void pressKey(long eventTime, int keyCode, long pressTime) {
        pressKey(eventTime, keyCode, pressTime, true /* interactive */);
    }

    private void pressKey(long eventTime, int keyCode, long pressTime, boolean interactive) {
        final KeyEvent keyDown = new KeyEvent(eventTime, eventTime, ACTION_DOWN,
                keyCode, 0 /* repeat */, 0 /* metaState */);
        mDetector.interceptKey(keyDown, interactive);

        // keep press down.
        try {
            Thread.sleep(pressTime);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        eventTime += pressTime;
        final KeyEvent keyUp = new KeyEvent(eventTime, eventTime, ACTION_UP,
                keyCode, 0 /* repeat */, 0 /* metaState */);

        mDetector.interceptKey(keyUp, interactive);
    }

    @Test
    public void testShortPress() throws InterruptedException {
        final long eventTime = SystemClock.uptimeMillis();
        pressKey(eventTime, KEYCODE_POWER, 0 /* pressTime */);
        assertTrue(mShortPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testLongPress() throws InterruptedException {
        final long eventTime = SystemClock.uptimeMillis();
        pressKey(eventTime, KEYCODE_POWER, mLongPressTime);
        assertTrue(mLongPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testVeryLongPress() throws InterruptedException {
        final long eventTime = SystemClock.uptimeMillis();
        pressKey(eventTime, KEYCODE_POWER, mVeryLongPressTime);
        assertTrue(mVeryLongPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testMultiPress() throws InterruptedException {
        final long eventTime = SystemClock.uptimeMillis();
        pressKey(eventTime, KEYCODE_POWER, 0 /* pressTime */);
        pressKey(eventTime, KEYCODE_POWER, 0 /* pressTime */);
        assertTrue(mMultiPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testNonInteractive() throws InterruptedException {
        long eventTime = SystemClock.uptimeMillis();
        // Disallow short press behavior from non interactive.
        mAllowNonInteractiveForPress = false;
        pressKey(eventTime, KEYCODE_POWER, 0 /* pressTime */, false /* interactive */);
        assertFalse(mShortPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));

        // Allow long press behavior from non interactive.
        eventTime = SystemClock.uptimeMillis();
        pressKey(eventTime, KEYCODE_POWER, mLongPressTime, false /* interactive */);
        assertTrue(mLongPressed.await(mWaitTimeout, TimeUnit.MILLISECONDS));
    }
}
