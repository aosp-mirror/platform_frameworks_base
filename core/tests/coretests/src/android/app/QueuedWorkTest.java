/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Semaphore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@Presubmit
@SmallTest
public class QueuedWorkTest {

    private QueuedWork mQueuedWork;
    private AtomicInteger mCounter;

    private class AddToCounter implements Runnable {
        private final int mDelta;

        public AddToCounter(int delta) {
            mDelta = delta;
        }

        @Override
        public void run() {
            mCounter.addAndGet(mDelta);
        }
    }

    private class IncrementCounter extends AddToCounter {
        public IncrementCounter() {
            super(1);
        }
    }

    @Before
    public void setup() {
        mQueuedWork = new QueuedWork();
        mCounter = new AtomicInteger(0);
    }

    @After
    public void teardown() {
        mQueuedWork.waitToFinish();
        QueuedWork.resetHandler();
    }

    @Test
    public void testQueueThenWait() {
        mQueuedWork.queue(new IncrementCounter(), false);
        mQueuedWork.waitToFinish();
        assertThat(mCounter.get()).isEqualTo(1);
        assertThat(mQueuedWork.hasPendingWork()).isFalse();
    }

    @Test
    public void testQueueWithDelayThenWait() {
        mQueuedWork.queue(new IncrementCounter(), true);
        mQueuedWork.waitToFinish();
        assertThat(mCounter.get()).isEqualTo(1);
        assertThat(mQueuedWork.hasPendingWork()).isFalse();
    }

    @Test
    public void testWorkHappensNotOnCallerThread() {
        AtomicBoolean childThreadStarted = new AtomicBoolean(false);
        InheritableThreadLocal<Boolean> setTrueInChild =
                new InheritableThreadLocal<Boolean>() {
                    @Override
                    protected Boolean initialValue() {
                        return false;
                    }

                    @Override
                    protected Boolean childValue(Boolean parentValue) {
                        childThreadStarted.set(true);
                        return true;
                    }
                };

        // Enqueue work to force a worker thread to be created
        setTrueInChild.get();
        assertThat(childThreadStarted.get()).isFalse();
        mQueuedWork.queue(() -> setTrueInChild.get(), false);
        mQueuedWork.waitToFinish();
        assertThat(childThreadStarted.get()).isTrue();
    }

    @Test
    public void testWaitToFinishDoesNotCreateThread() {
        InheritableThreadLocal<Boolean> throwInChild =
                new InheritableThreadLocal<Boolean>() {
                    @Override
                    protected Boolean initialValue() {
                        return false;
                    }

                    @Override
                    protected Boolean childValue(Boolean parentValue) {
                        throw new RuntimeException("New thread should not be started!");
                    }
                };

        try {
            throwInChild.get();
            // Intentionally don't enqueue work.
            mQueuedWork.waitToFinish();
            throwInChild.get();
            // If a worker thread was unnecessarily started, we will have crashed.
        } finally {
            throwInChild.remove();
        }
    }

    @Test
    public void testFinisher() {
        mQueuedWork.addFinisher(new AddToCounter(3));
        mQueuedWork.addFinisher(new AddToCounter(7));
        mQueuedWork.queue(new IncrementCounter(), false);
        mQueuedWork.waitToFinish();
        // The queued task and the two finishers all ran
        assertThat(mCounter.get()).isEqualTo(1 + 3 + 7);
    }

    @Test
    public void testRemoveFinisher() {
        Runnable addThree = new AddToCounter(3);
        Runnable addSeven = new AddToCounter(7);
        mQueuedWork.addFinisher(addThree);
        mQueuedWork.addFinisher(addSeven);
        mQueuedWork.removeFinisher(addThree);
        mQueuedWork.queue(new IncrementCounter(), false);
        mQueuedWork.waitToFinish();
        // The queued task and the two finishers all ran
        assertThat(mCounter.get()).isEqualTo(1 + 7);
    }

    @Test
    public void testHasPendingWork() {
        Semaphore releaser = new Semaphore(0);
        mQueuedWork.queue(
                () -> {
                    try {
                        releaser.acquire();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }, false);
        assertThat(mQueuedWork.hasPendingWork()).isTrue();
        releaser.release();
        mQueuedWork.waitToFinish();
        assertThat(mQueuedWork.hasPendingWork()).isFalse();
    }
}