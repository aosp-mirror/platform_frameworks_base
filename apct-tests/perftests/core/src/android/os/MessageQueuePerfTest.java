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

package android.os;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.perftests.utils.Stats;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Performance tests for {@link MessageQueue}.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class MessageQueuePerfTest {
    static final String TAG = "MessageQueuePerfTest";
    private static final int PER_THREAD_MESSAGE_COUNT = 1000;
    private static final int THREAD_COUNT = 8;
    private static final int TOTAL_MESSAGE_COUNT = PER_THREAD_MESSAGE_COUNT * THREAD_COUNT;

    static Object sLock = new Object();
    private ArrayList<Long> mResults;

    @Before
    public void setUp() { }

    @After
    public void tearDown() { }

    class EnqueueThread extends Thread {
        CountDownLatch mStartLatch;
        CountDownLatch mEndLatch;
        Handler mHandler;
        int mMessageStartIdx;
        Message[] mMessages;
        long[] mDelays;

        EnqueueThread(CountDownLatch startLatch, CountDownLatch endLatch, Handler handler,
                int startIdx, Message[] messages, long[] delays) {
            super();
            mStartLatch = startLatch;
            mEndLatch = endLatch;
            mHandler = handler;
            mMessageStartIdx = startIdx;
            mMessages = messages;
            mDelays = delays;
        }

        @Override
        public void run() {
            Log.d(TAG, "Enqueue thread started at message index " + mMessageStartIdx);
            try {
                mStartLatch.await();
            } catch (InterruptedException e) {

            }
            long now = SystemClock.uptimeMillis();
            long startTimeNS = SystemClock.elapsedRealtimeNanos();
            for (int i = mMessageStartIdx; i < (mMessageStartIdx + PER_THREAD_MESSAGE_COUNT); i++) {
                if (mDelays[i] == 0) {
                    mHandler.sendMessageAtFrontOfQueue(mMessages[i]);
                } else {
                    mHandler.sendMessageAtTime(mMessages[i], now + mDelays[i]);
                }
            }
            long endTimeNS = SystemClock.elapsedRealtimeNanos();

            synchronized (sLock) {
                mResults.add(endTimeNS - startTimeNS);
            }
            mEndLatch.countDown();
        }
    }

    class TestHandler extends Handler {
        TestHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) { }
    }

    void reportPerf(String prefix, int threadCount, int perThreadMessageCount) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Stats stats = new Stats(mResults);

        Log.d(TAG, "Reporting perf now");

        Bundle status = new Bundle();
        status.putLong(prefix + "_median_ns", stats.getMedian());
        status.putLong(prefix + "_mean_ns", (long) stats.getMean());
        status.putLong(prefix + "_min_ns", stats.getMin());
        status.putLong(prefix + "_max_ns", stats.getMax());
        status.putLong(prefix + "_stddev_ns", (long) stats.getStandardDeviation());
        status.putLong(prefix + "_nr_threads", threadCount);
        status.putLong(prefix + "_msgs_per_thread", perThreadMessageCount);
        instrumentation.sendStatus(Activity.RESULT_OK, status);
    }

    HandlerThread mHandlerThread;

    private void fillMessagesArray(Message[] messages) {
        for (int i = 0; i < messages.length; i++) {
            messages[i] = mHandlerThread.getThreadHandler().obtainMessage(i);
        }
    }

    private void startTestAndWaitOnThreads(CountDownLatch threadStartLatch, CountDownLatch threadEndLatch) {
        try {
            threadStartLatch.countDown();
            Log.e(TAG, "Test threads started");
            threadEndLatch.await();
        } catch (InterruptedException ignored) {
        }
        Log.e(TAG, "Test threads ended, quitting handler thread");
    }

    @Test
    public void benchmarkEnqueueAtFrontOfQueue() {
        CountDownLatch threadStartLatch = new CountDownLatch(1);
        CountDownLatch threadEndLatch  = new CountDownLatch(THREAD_COUNT);
        mHandlerThread = new HandlerThread("MessageQueuePerfTest");
        mHandlerThread.start();
        Message[] messages = new Message[TOTAL_MESSAGE_COUNT];
        fillMessagesArray(messages);

        long[] delays = new long[TOTAL_MESSAGE_COUNT];
        mResults = new ArrayList<>();

        TestHandler handler = new TestHandler(mHandlerThread.getLooper());
        for (int i = 0; i < THREAD_COUNT; i++) {
            EnqueueThread thread = new EnqueueThread(threadStartLatch, threadEndLatch, handler,
                    i * PER_THREAD_MESSAGE_COUNT, messages, delays);
            thread.start();
        }

        startTestAndWaitOnThreads(threadStartLatch, threadEndLatch);

        mHandlerThread.quitSafely();

        reportPerf("enqueueAtFront", THREAD_COUNT, PER_THREAD_MESSAGE_COUNT);
    }

    /**
     * Fill array with random delays, for benchmarkEnqueueDelayed
     */
    public long[] fillDelayArray() {
        long[] delays = new long[TOTAL_MESSAGE_COUNT];
        Random rand = new Random(0xDEADBEEF);
        for (int i = 0; i < TOTAL_MESSAGE_COUNT; i++) {
            delays[i] = Math.abs(rand.nextLong() % 5000);
        }
        return delays;
    }

    @Test
    public void benchmarkEnqueueDelayed() {
        CountDownLatch threadStartLatch = new CountDownLatch(1);
        CountDownLatch threadEndLatch  = new CountDownLatch(THREAD_COUNT);
        mHandlerThread = new HandlerThread("MessageQueuePerfTest");
        mHandlerThread.start();
        Message[] messages = new Message[TOTAL_MESSAGE_COUNT];
        fillMessagesArray(messages);

        long[] delays = fillDelayArray();
        mResults = new ArrayList<>();

        TestHandler handler = new TestHandler(mHandlerThread.getLooper());
        for (int i = 0; i < THREAD_COUNT; i++) {
            EnqueueThread thread = new EnqueueThread(threadStartLatch, threadEndLatch, handler,
                    i * PER_THREAD_MESSAGE_COUNT, messages, delays);
            thread.start();
        }

        startTestAndWaitOnThreads(threadStartLatch, threadEndLatch);

        mHandlerThread.quitSafely();

        reportPerf("enqueueDelayed", THREAD_COUNT, PER_THREAD_MESSAGE_COUNT);
    }
}
