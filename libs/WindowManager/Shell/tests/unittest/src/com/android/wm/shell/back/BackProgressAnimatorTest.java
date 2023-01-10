/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.back;

import static android.window.BackEvent.EDGE_LEFT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.os.Handler;
import android.os.Looper;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.window.BackEvent;
import android.window.BackMotionEvent;
import android.window.BackProgressAnimator;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class BackProgressAnimatorTest {
    private BackProgressAnimator mProgressAnimator;
    private BackEvent mReceivedBackEvent;
    private float mTargetProgress = 0.5f;
    private CountDownLatch mTargetProgressCalled = new CountDownLatch(1);
    private Handler mMainThreadHandler;

    @Before
    public void setUp() throws Exception {
        mMainThreadHandler = new Handler(Looper.getMainLooper());
        final BackMotionEvent backEvent = new BackMotionEvent(
                0, 0,
                0, EDGE_LEFT, null);
        mMainThreadHandler.post(
                () -> {
                    mProgressAnimator = new BackProgressAnimator();
                    mProgressAnimator.onBackStarted(backEvent, this::onGestureProgress);
                });
    }

    @Test
    public void testBackProgressed() throws InterruptedException {
        final BackMotionEvent backEvent = new BackMotionEvent(
                100, 0,
                mTargetProgress, EDGE_LEFT, null);
        mMainThreadHandler.post(
                () -> mProgressAnimator.onBackProgressed(backEvent));

        mTargetProgressCalled.await(1, TimeUnit.SECONDS);

        assertNotNull(mReceivedBackEvent);
        assertEquals(mReceivedBackEvent.getProgress(), mTargetProgress, 0 /* delta */);
    }

    @Test
    public void testBackCancelled() throws InterruptedException {
        // Give the animator some progress.
        final BackMotionEvent backEvent = new BackMotionEvent(
                100, 0,
                mTargetProgress, EDGE_LEFT, null);
        mMainThreadHandler.post(
                () -> mProgressAnimator.onBackProgressed(backEvent));
        mTargetProgressCalled.await(1, TimeUnit.SECONDS);
        assertNotNull(mReceivedBackEvent);

        // Trigger animation cancel, the target progress should be 0.
        mTargetProgress = 0;
        mTargetProgressCalled = new CountDownLatch(1);
        CountDownLatch cancelCallbackCalled = new CountDownLatch(1);
        mMainThreadHandler.post(
                () -> mProgressAnimator.onBackCancelled(() -> cancelCallbackCalled.countDown()));
        cancelCallbackCalled.await(1, TimeUnit.SECONDS);
        mTargetProgressCalled.await(1, TimeUnit.SECONDS);
        assertNotNull(mReceivedBackEvent);
        assertEquals(mReceivedBackEvent.getProgress(), mTargetProgress, 0 /* delta */);
    }

    private void onGestureProgress(BackEvent backEvent) {
        if (mTargetProgress == backEvent.getProgress()) {
            mReceivedBackEvent = backEvent;
            mTargetProgressCalled.countDown();
        }
    }
}
