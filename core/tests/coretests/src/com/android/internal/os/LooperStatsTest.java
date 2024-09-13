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

package com.android.internal.os;

import static com.google.common.truth.Truth.assertThat;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.platform.test.annotations.Presubmit;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Comparator;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
@Presubmit
public final class LooperStatsTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private HandlerThread mThreadFirst;
    private HandlerThread mThreadSecond;
    private Handler mHandlerFirst;
    private Handler mHandlerSecond;
    private Handler mHandlerAnonymous;
    private CachedDeviceState mDeviceState;

    @Before
    public void setUp() {
        // The tests are all single-threaded. HandlerThreads are created to allow creating Handlers
        // and to test Thread name collection.
        mThreadFirst = new HandlerThread("TestThread1");
        mThreadSecond = new HandlerThread("TestThread2");
        mThreadFirst.start();
        mThreadSecond.start();

        mHandlerFirst = new TestHandlerFirst(mThreadFirst.getLooper());
        mHandlerSecond = new TestHandlerSecond(mThreadSecond.getLooper());
        mHandlerAnonymous = new Handler(mThreadFirst.getLooper()) {
            /* To create an anonymous subclass. */
        };
        mDeviceState = new CachedDeviceState();
        mDeviceState.setCharging(false);
        mDeviceState.setScreenInteractive(true);
    }

    @After
    public void tearDown() {
        mThreadFirst.quit();
        mThreadSecond.quit();
    }

    @Test
    public void testSingleMessageDispatched() {
        TestableLooperStats looperStats = new TestableLooperStats(1, 100);

        Message message = mHandlerFirst.obtainMessage(1000);
        message.workSourceUid = 1000;
        message.when = looperStats.getSystemUptimeMillis();

        looperStats.tickUptime(30);
        Object token = looperStats.messageDispatchStarting();
        looperStats.tickRealtime(100);
        looperStats.tickThreadTime(10);
        looperStats.tickUptime(200);
        looperStats.messageDispatched(token, message);

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        assertThat(entries).hasSize(1);
        LooperStats.ExportedEntry entry = entries.get(0);
        assertThat(entry.workSourceUid).isEqualTo(1000);
        assertThat(entry.threadName).isEqualTo("TestThread1");
        assertThat(entry.handlerClassName).isEqualTo(
                "com.android.internal.os.LooperStatsTest$TestHandlerFirst");
        assertThat(entry.messageName).isEqualTo("0x3e8" /* 1000 in hex */);
        assertThat(entry.isInteractive).isEqualTo(true);
        assertThat(entry.messageCount).isEqualTo(1);
        assertThat(entry.recordedMessageCount).isEqualTo(1);
        assertThat(entry.exceptionCount).isEqualTo(0);
        assertThat(entry.totalLatencyMicros).isEqualTo(100);
        assertThat(entry.maxLatencyMicros).isEqualTo(100);
        assertThat(entry.cpuUsageMicros).isEqualTo(10);
        assertThat(entry.maxCpuUsageMicros).isEqualTo(10);
        assertThat(entry.recordedDelayMessageCount).isEqualTo(1);
        assertThat(entry.delayMillis).isEqualTo(30);
        assertThat(entry.maxDelayMillis).isEqualTo(30);
    }

    @Test
    public void testThrewException() {
        TestableLooperStats looperStats = new TestableLooperStats(1, 100);

        Message message = mHandlerFirst.obtainMessage(7);
        message.workSourceUid = 123;
        Object token = looperStats.messageDispatchStarting();
        looperStats.tickRealtime(100);
        looperStats.tickThreadTime(10);
        looperStats.dispatchingThrewException(token, message, new ArithmeticException());

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        assertThat(entries).hasSize(1);
        LooperStats.ExportedEntry entry = entries.get(0);
        assertThat(entry.workSourceUid).isEqualTo(123);
        assertThat(entry.threadName).isEqualTo("TestThread1");
        assertThat(entry.handlerClassName).isEqualTo(
                "com.android.internal.os.LooperStatsTest$TestHandlerFirst");
        assertThat(entry.messageName).isEqualTo("0x7"  /* 7 in hex */);
        assertThat(entry.isInteractive).isEqualTo(true);
        assertThat(entry.messageCount).isEqualTo(0);
        assertThat(entry.recordedMessageCount).isEqualTo(0);
        assertThat(entry.exceptionCount).isEqualTo(1);
        assertThat(entry.totalLatencyMicros).isEqualTo(0);
        assertThat(entry.maxLatencyMicros).isEqualTo(0);
        assertThat(entry.cpuUsageMicros).isEqualTo(0);
        assertThat(entry.maxCpuUsageMicros).isEqualTo(0);
    }

    @Test
    public void testThrewException_notSampled() {
        TestableLooperStats looperStats = new TestableLooperStats(2, 100);

        Object token = looperStats.messageDispatchStarting();
        looperStats.tickRealtime(10);
        looperStats.tickThreadTime(10);
        looperStats.messageDispatched(token, mHandlerFirst.obtainMessage(0));
        assertThat(looperStats.getEntries()).hasSize(1);

        // Will not be sampled so does not contribute to any entries.
        Object token2 = looperStats.messageDispatchStarting();
        looperStats.tickRealtime(100);
        looperStats.tickThreadTime(10);
        looperStats.dispatchingThrewException(
                token2, mHandlerSecond.obtainMessage(7), new ArithmeticException());
        assertThat(looperStats.getEntries()).hasSize(1);
        assertThat(looperStats.getEntries().get(0).messageCount).isEqualTo(1);
    }

    @Test
    public void testMultipleMessagesDispatched() {
        TestableLooperStats looperStats = new TestableLooperStats(2, 100);

        // Contributes to entry2.
        Object token1 = looperStats.messageDispatchStarting();
        looperStats.tickRealtime(100);
        looperStats.tickThreadTime(10);
        looperStats.messageDispatched(token1, mHandlerFirst.obtainMessage(1000));

        // Contributes to entry2.
        Object token2 = looperStats.messageDispatchStarting();
        looperStats.tickRealtime(50);
        looperStats.tickThreadTime(20);
        looperStats.messageDispatched(token2, mHandlerFirst.obtainMessage(1000));

        // Contributes to entry3.
        Object token3 = looperStats.messageDispatchStarting();
        looperStats.tickRealtime(10);
        looperStats.tickThreadTime(10);
        looperStats.messageDispatched(token3, mHandlerSecond.obtainMessage().setCallback(() -> {
        }));

        // Will not be sampled so does not contribute to any entries.
        Object token4 = looperStats.messageDispatchStarting();
        looperStats.tickRealtime(10);
        looperStats.tickThreadTime(10);
        looperStats.messageDispatched(token4, mHandlerSecond.obtainMessage(0));

        // Contributes to entry1.
        Object token5 = looperStats.messageDispatchStarting();
        looperStats.tickRealtime(100);
        looperStats.tickThreadTime(100);
        looperStats.messageDispatched(token5, mHandlerAnonymous.obtainMessage(1));

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        assertThat(entries).hasSize(3);
        entries.sort(Comparator.comparing(e -> e.handlerClassName));

        // Captures data for token5 call.
        LooperStats.ExportedEntry entry1 = entries.get(0);
        assertThat(entry1.workSourceUid).isEqualTo(-1);
        assertThat(entry1.threadName).isEqualTo("TestThread1");
        assertThat(entry1.handlerClassName).isEqualTo("com.android.internal.os.LooperStatsTest$1");
        assertThat(entry1.messageName).isEqualTo("0x1" /* 1 in hex */);
        assertThat(entry1.messageCount).isEqualTo(1);
        assertThat(entry1.recordedMessageCount).isEqualTo(1);
        assertThat(entry1.exceptionCount).isEqualTo(0);
        assertThat(entry1.totalLatencyMicros).isEqualTo(100);
        assertThat(entry1.maxLatencyMicros).isEqualTo(100);
        assertThat(entry1.cpuUsageMicros).isEqualTo(100);
        assertThat(entry1.maxCpuUsageMicros).isEqualTo(100);

        // Captures data for token1 and token2 calls.
        LooperStats.ExportedEntry entry2 = entries.get(1);
        assertThat(entry2.workSourceUid).isEqualTo(-1);
        assertThat(entry2.threadName).isEqualTo("TestThread1");
        assertThat(entry2.handlerClassName).isEqualTo(
                "com.android.internal.os.LooperStatsTest$TestHandlerFirst");
        assertThat(entry2.messageName).isEqualTo("0x3e8" /* 1000 in hex */);
        assertThat(entry2.messageCount).isEqualTo(2);
        assertThat(entry2.recordedMessageCount).isEqualTo(1);
        assertThat(entry2.exceptionCount).isEqualTo(0);
        assertThat(entry2.totalLatencyMicros).isEqualTo(100);
        assertThat(entry2.maxLatencyMicros).isEqualTo(100);
        assertThat(entry2.cpuUsageMicros).isEqualTo(10);
        assertThat(entry2.maxCpuUsageMicros).isEqualTo(10);

        // Captures data for token3 call.
        LooperStats.ExportedEntry entry3 = entries.get(2);
        assertThat(entry3.workSourceUid).isEqualTo(-1);
        assertThat(entry3.threadName).isEqualTo("TestThread2");
        assertThat(entry3.handlerClassName).isEqualTo(
                "com.android.internal.os.LooperStatsTest$TestHandlerSecond");
        assertThat(entry3.messageName).startsWith(
                "com.android.internal.os");
        assertThat(entry3.messageCount).isEqualTo(1);
        assertThat(entry3.recordedMessageCount).isEqualTo(1);
        assertThat(entry3.exceptionCount).isEqualTo(0);
        assertThat(entry3.totalLatencyMicros).isEqualTo(10);
        assertThat(entry3.maxLatencyMicros).isEqualTo(10);
        assertThat(entry3.cpuUsageMicros).isEqualTo(10);
        assertThat(entry3.maxCpuUsageMicros).isEqualTo(10);
    }

    @Test
    public void testDispatchDelayIsRecorded() {
        TestableLooperStats looperStats = new TestableLooperStats(1, 100);

        // Dispatched right on time.
        Message message1 = mHandlerFirst.obtainMessage(1000);
        message1.when = looperStats.getSystemUptimeMillis();
        Object token1 = looperStats.messageDispatchStarting();
        looperStats.tickUptime(10);
        looperStats.messageDispatched(token1, message1);

        // Dispatched 100ms late.
        Message message2 = mHandlerFirst.obtainMessage(1000);
        message2.when = looperStats.getSystemUptimeMillis() - 100;
        Object token2 = looperStats.messageDispatchStarting();
        looperStats.tickUptime(10);
        looperStats.messageDispatched(token2, message2);

        // No target dispatching time.
        Message message3 = mHandlerFirst.obtainMessage(1000);
        message3.when = 0;
        Object token3 = looperStats.messageDispatchStarting();
        looperStats.tickUptime(10);
        looperStats.messageDispatched(token3, message3);

        // Dispatched too soon (should never happen).
        Message message4 = mHandlerFirst.obtainMessage(1000);
        message4.when = looperStats.getSystemUptimeMillis() + 200;
        Object token4 = looperStats.messageDispatchStarting();
        looperStats.tickUptime(10);
        looperStats.messageDispatched(token4, message4);

        // Dispatched 300ms late.
        Message message5 = mHandlerFirst.obtainMessage(1000);
        message5.when = looperStats.getSystemUptimeMillis() - 300;
        Object token5 = looperStats.messageDispatchStarting();
        looperStats.tickUptime(10);
        looperStats.messageDispatched(token5, message5);

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        assertThat(entries).hasSize(1);

        LooperStats.ExportedEntry entry = entries.get(0);
        assertThat(entry.messageCount).isEqualTo(5);
        assertThat(entry.recordedMessageCount).isEqualTo(5);
        assertThat(entry.recordedDelayMessageCount).isEqualTo(4);
        assertThat(entry.delayMillis).isEqualTo(400);
        assertThat(entry.maxDelayMillis).isEqualTo(300);
    }

    @Test
    public void testDataNotCollectedBeforeDeviceStateSet() {
        TestableLooperStats looperStats = new TestableLooperStats(1, 100, null);

        Object token1 = looperStats.messageDispatchStarting();
        looperStats.messageDispatched(token1, mHandlerFirst.obtainMessage(1000));
        Object token2 = looperStats.messageDispatchStarting();
        looperStats.dispatchingThrewException(token2, mHandlerFirst.obtainMessage(1000),
                new IllegalArgumentException());

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        assertThat(entries).hasSize(0);
    }

    @Test
    public void testDataNotCollectedOnCharger() {
        TestableLooperStats looperStats = new TestableLooperStats(1, 100);
        mDeviceState.setCharging(true);

        Object token1 = looperStats.messageDispatchStarting();
        looperStats.messageDispatched(token1, mHandlerFirst.obtainMessage(1000));
        Object token2 = looperStats.messageDispatchStarting();
        looperStats.dispatchingThrewException(token2, mHandlerFirst.obtainMessage(1000),
                new IllegalArgumentException());

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        assertThat(entries).hasSize(0);
    }

    @Test
    public void testDataCollectedIfIgnoreBatteryStatusFlagSet() {
        TestableLooperStats looperStats = new TestableLooperStats(1, 100);
        mDeviceState.setCharging(true);
        looperStats.setIgnoreBatteryStatus(true);

        Object token1 = looperStats.messageDispatchStarting();
        looperStats.messageDispatched(token1, mHandlerFirst.obtainMessage(1000));
        Object token2 = looperStats.messageDispatchStarting();
        looperStats.dispatchingThrewException(token2, mHandlerFirst.obtainMessage(1000),
                new IllegalArgumentException());

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        assertThat(entries).hasSize(1);

    }

    @Test
    public void testScreenStateCollected() {
        TestableLooperStats looperStats = new TestableLooperStats(1, 100);

        mDeviceState.setScreenInteractive(true);
        Object token1 = looperStats.messageDispatchStarting();
        looperStats.messageDispatched(token1, mHandlerFirst.obtainMessage(1000));
        Object token2 = looperStats.messageDispatchStarting();
        looperStats.dispatchingThrewException(token2, mHandlerFirst.obtainMessage(1000),
                new IllegalArgumentException());

        Object token3 = looperStats.messageDispatchStarting();
        // If screen state changed during the call, we take the final state into account.
        mDeviceState.setScreenInteractive(false);
        looperStats.messageDispatched(token3, mHandlerFirst.obtainMessage(1000));
        Object token4 = looperStats.messageDispatchStarting();
        looperStats.dispatchingThrewException(token4, mHandlerFirst.obtainMessage(1000),
                new IllegalArgumentException());

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        assertThat(entries).hasSize(2);
        entries.sort(Comparator.comparing(e -> e.isInteractive));

        LooperStats.ExportedEntry entry1 = entries.get(0);
        assertThat(entry1.isInteractive).isEqualTo(false);
        assertThat(entry1.messageCount).isEqualTo(1);
        assertThat(entry1.exceptionCount).isEqualTo(1);

        LooperStats.ExportedEntry entry2 = entries.get(1);
        assertThat(entry2.isInteractive).isEqualTo(true);
        assertThat(entry2.messageCount).isEqualTo(1);
        assertThat(entry2.exceptionCount).isEqualTo(1);
    }

    @Test
    public void testMessagesOverSizeCap() {
        TestableLooperStats looperStats = new TestableLooperStats(1, 1 /* sizeCap */);

        Object token1 = looperStats.messageDispatchStarting();
        looperStats.tickRealtime(100);
        looperStats.tickThreadTime(10);
        looperStats.messageDispatched(token1, mHandlerFirst.obtainMessage(1000));

        Object token2 = looperStats.messageDispatchStarting();
        looperStats.tickRealtime(50);
        looperStats.tickThreadTime(20);
        looperStats.messageDispatched(token2, mHandlerFirst.obtainMessage(1001));

        Object token3 = looperStats.messageDispatchStarting();
        looperStats.tickRealtime(10);
        looperStats.tickThreadTime(10);
        looperStats.messageDispatched(token3, mHandlerFirst.obtainMessage(1002));

        Object token4 = looperStats.messageDispatchStarting();
        looperStats.tickRealtime(10);
        looperStats.tickThreadTime(10);
        looperStats.messageDispatched(token4, mHandlerSecond.obtainMessage(1003));

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        assertThat(entries).hasSize(2);
        entries.sort(Comparator.comparing(e -> e.handlerClassName));

        LooperStats.ExportedEntry entry1 = entries.get(0);
        assertThat(entry1.threadName).isEqualTo("");
        assertThat(entry1.handlerClassName).isEqualTo("");
        assertThat(entry1.messageName).isEqualTo("OVERFLOW");
        assertThat(entry1.messageCount).isEqualTo(3);
        assertThat(entry1.recordedMessageCount).isEqualTo(3);
        assertThat(entry1.exceptionCount).isEqualTo(0);
        assertThat(entry1.totalLatencyMicros).isEqualTo(70);
        assertThat(entry1.maxLatencyMicros).isEqualTo(50);
        assertThat(entry1.cpuUsageMicros).isEqualTo(40);
        assertThat(entry1.maxCpuUsageMicros).isEqualTo(20);

        LooperStats.ExportedEntry entry2 = entries.get(1);
        assertThat(entry2.threadName).isEqualTo("TestThread1");
        assertThat(entry2.handlerClassName).isEqualTo(
                "com.android.internal.os.LooperStatsTest$TestHandlerFirst");
    }

    @Test
    public void testInvalidTokensCauseException() {
        TestableLooperStats looperStats = new TestableLooperStats(1, 100);
        assertThrows(ClassCastException.class,
                () -> looperStats.dispatchingThrewException(new Object(),
                        mHandlerFirst.obtainMessage(),
                        new ArithmeticException()));
        assertThrows(ClassCastException.class,
                () -> looperStats.messageDispatched(new Object(), mHandlerFirst.obtainMessage()));
        assertThrows(ClassCastException.class,
                () -> looperStats.messageDispatched(123, mHandlerFirst.obtainMessage()));
        assertThrows(ClassCastException.class,
                () -> looperStats.messageDispatched(mHandlerFirst.obtainMessage(),
                        mHandlerFirst.obtainMessage()));

        assertThat(looperStats.getEntries()).hasSize(0);
    }

    @Test
    public void testTracksMultipleHandlerInstancesIfSameClass() {
        TestableLooperStats looperStats = new TestableLooperStats(1, 100);
        Handler handlerFirstAnother = new TestHandlerFirst(mHandlerFirst.getLooper());

        Object token1 = looperStats.messageDispatchStarting();
        looperStats.messageDispatched(token1, mHandlerFirst.obtainMessage(1000));

        Object token2 = looperStats.messageDispatchStarting();
        looperStats.messageDispatched(token2, handlerFirstAnother.obtainMessage(1000));

        assertThat(looperStats.getEntries()).hasSize(1);
        assertThat(looperStats.getEntries().get(0).messageCount).isEqualTo(2);
    }

    @Test
    public void testReset() {
        TestableLooperStats looperStats = new TestableLooperStats(1, 1);

        Object token1 = looperStats.messageDispatchStarting();
        looperStats.messageDispatched(token1, mHandlerFirst.obtainMessage(1000));
        Object token2 = looperStats.messageDispatchStarting();
        looperStats.messageDispatched(token2, mHandlerFirst.obtainMessage(2000));
        looperStats.reset();

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        assertThat(entries).hasSize(0);
    }

    @Test
    public void testAddsDebugEntries() {
        TestableLooperStats looperStats = new TestableLooperStats(1, 100);
        looperStats.setAddDebugEntries(true);
        looperStats.setSamplingInterval(10);

        Message message = mHandlerFirst.obtainMessage(1000);
        message.when = looperStats.getSystemUptimeMillis();
        Object token = looperStats.messageDispatchStarting();
        looperStats.messageDispatched(token, message);

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        assertThat(entries).hasSize(5);
        LooperStats.ExportedEntry debugEntry1 = entries.get(1);
        assertThat(debugEntry1.handlerClassName).isEqualTo("");
        assertThat(debugEntry1.messageName).isEqualTo("__DEBUG_start_time_millis");
        assertThat(debugEntry1.totalLatencyMicros).isEqualTo(
                looperStats.getStartElapsedTimeMillis());
        LooperStats.ExportedEntry debugEntry2 = entries.get(2);
        assertThat(debugEntry2.handlerClassName).isEqualTo("");
        assertThat(debugEntry2.messageName).isEqualTo("__DEBUG_end_time_millis");
        assertThat(debugEntry2.totalLatencyMicros).isAtLeast(
                looperStats.getStartElapsedTimeMillis());
        LooperStats.ExportedEntry debugEntry3 = entries.get(3);
        assertThat(debugEntry3.handlerClassName).isEqualTo("");
        assertThat(debugEntry3.messageName).isEqualTo("__DEBUG_battery_time_millis");
        assertThat(debugEntry3.totalLatencyMicros).isAtLeast(0L);
        LooperStats.ExportedEntry debugEntry4 = entries.get(4);
        assertThat(debugEntry4.messageName).isEqualTo("__DEBUG_sampling_interval");
        assertThat(debugEntry4.totalLatencyMicros).isEqualTo(10L);
    }

    @Test
    public void testScreenStateTrackingDisabled() {
        TestableLooperStats looperStats = new TestableLooperStats(1, 100);
        looperStats.setTrackScreenInteractive(false);

        Message message = mHandlerFirst.obtainMessage(1000);
        message.workSourceUid = 1000;
        message.when = looperStats.getSystemUptimeMillis();

        looperStats.tickUptime(30);
        mDeviceState.setScreenInteractive(false);
        Object token = looperStats.messageDispatchStarting();
        looperStats.messageDispatched(token, message);

        looperStats.tickUptime(30);
        mDeviceState.setScreenInteractive(true);
        token = looperStats.messageDispatchStarting();
        looperStats.messageDispatched(token, message);

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        assertThat(entries).hasSize(1);
        LooperStats.ExportedEntry entry = entries.get(0);
        assertThat(entry.isInteractive).isEqualTo(false);
        assertThat(entry.messageCount).isEqualTo(2);
        assertThat(entry.recordedMessageCount).isEqualTo(2);
    }

    private static void assertThrows(Class<? extends Exception> exceptionClass, Runnable r) {
        try {
            r.run();
            Assert.fail("Expected " + exceptionClass + " to be thrown.");
        } catch (Exception exception) {
            assertThat(exception).isInstanceOf(exceptionClass);
        }
    }

    private final class TestableLooperStats extends LooperStats {
        private static final long INITIAL_MICROS = 10001000123L;
        private int mCount;
        private long mRealtimeMicros;
        private long mThreadTimeMicros;
        private long mUptimeMillis;
        private int mSamplingInterval;

        TestableLooperStats(int samplingInterval, int sizeCap) {
            this(samplingInterval, sizeCap, mDeviceState);
        }

        TestableLooperStats(int samplingInterval, int sizeCap, CachedDeviceState deviceState) {
            super(samplingInterval, sizeCap);
            mSamplingInterval = samplingInterval;
            setAddDebugEntries(false);
            setTrackScreenInteractive(true);
            if (deviceState != null) {
                setDeviceState(deviceState.getReadonlyClient());
            }
        }

        void tickRealtime(long micros) {
            mRealtimeMicros += micros;
        }

        void tickThreadTime(long micros) {
            mThreadTimeMicros += micros;
        }

        void tickUptime(long millis) {
            mUptimeMillis += millis;
        }

        @Override
        protected long getElapsedRealtimeMicro() {
            return INITIAL_MICROS + mRealtimeMicros;
        }

        @Override
        protected long getThreadTimeMicro() {
            return INITIAL_MICROS + mThreadTimeMicros;
        }

        @Override
        protected long getSystemUptimeMillis() {
            return INITIAL_MICROS / 1000 + mUptimeMillis;
        }

        @Override
        protected boolean shouldCollectDetailedData() {
            return mCount++ % mSamplingInterval == 0;
        }
    }

    private static final class TestHandlerFirst extends Handler {
        TestHandlerFirst(Looper looper) {
            super(looper);
        }
    }

    private static final class TestHandlerSecond extends Handler {
        TestHandlerSecond(Looper looper) {
            super(looper);
        }
    }
}
