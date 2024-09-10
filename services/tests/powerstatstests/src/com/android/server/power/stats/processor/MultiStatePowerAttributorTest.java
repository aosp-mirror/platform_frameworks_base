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
package com.android.server.power.stats.processor;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.platform.test.ravenwood.RavenwoodRule;

import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.MonotonicClock;
import com.android.server.power.stats.MockClock;
import com.android.server.power.stats.PowerStatsScheduler;
import com.android.server.power.stats.PowerStatsSpan;
import com.android.server.power.stats.PowerStatsStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class MultiStatePowerAttributorTest {

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private PowerStatsStore mPowerStatsStore;
    private Handler mHandler;
    private final MockClock mClock = new MockClock();
    private final MonotonicClock mMonotonicClock = new MonotonicClock(0, mClock);
    private PowerStatsScheduler mPowerStatsScheduler;
    private PowerStatsAggregator mPowerStatsAggregator;
    private MultiStatePowerAttributor mPowerAttributor;
    private final List<Long> mScheduledAlarms = new ArrayList<>();
    private boolean mPowerStatsCollectionOccurred;

    private static final int START_REALTIME = 7654321;

    @Before
    public void setup() throws IOException {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        mClock.currentTime = Instant.parse("2023-01-02T03:04:05.00Z").toEpochMilli();
        mClock.realtime = START_REALTIME;

        HandlerThread bgThread = new HandlerThread("bg thread");
        bgThread.start();
        mHandler = new Handler(bgThread.getLooper());
        mPowerStatsStore = new PowerStatsStore(
                Files.createTempDirectory("MultiStatePowerAttributorTest").toFile(), mHandler);
        mPowerStatsAggregator = mock(PowerStatsAggregator.class);
        mPowerAttributor = new MultiStatePowerAttributor(mPowerStatsStore, mPowerStatsAggregator);
        mPowerStatsScheduler = new PowerStatsScheduler(
                () -> mPowerStatsCollectionOccurred = true,
                mock(BatteryStatsHistory.class),
                mPowerAttributor, TimeUnit.MINUTES.toMillis(30), TimeUnit.HOURS.toMillis(1),
                mPowerStatsStore,
                ((triggerAtMillis, tag, onAlarmListener, handler) ->
                        mScheduledAlarms.add(triggerAtMillis)),
                mClock, mMonotonicClock, () -> 12345L, mHandler);
    }

    @Test
    public void storeAggregatedPowerStats() {
        mPowerStatsStore.reset();

        assertThat(mPowerStatsStore.getTableOfContents()).isEmpty();

        mPowerAttributor.storeAggregatedPowerStats(
                createAggregatedPowerStats(mMonotonicClock.monotonicTime(), mClock.currentTime,
                        123));

        long delayBeforeAggregating = TimeUnit.MINUTES.toMillis(90);
        mClock.realtime += delayBeforeAggregating;
        mClock.currentTime += delayBeforeAggregating;

        doAnswer(invocation -> {
            // The first span is longer than 30 min, because the end time is being aligned with
            // the wall clock.  Subsequent spans should be precisely 30 minutes.
            long startTime = invocation.getArgument(1);
            long endTime = invocation.getArgument(2);
            Consumer<AggregatedPowerStats> consumer = invocation.getArgument(3);

            long startTimeWallClock =
                    mClock.currentTime - (mMonotonicClock.monotonicTime() - startTime);
            long endTimeWallClock =
                    mClock.currentTime - (mMonotonicClock.monotonicTime() - endTime);

            assertThat(startTime).isEqualTo(START_REALTIME + 123);
            assertThat(endTime - startTime).isAtLeast(TimeUnit.MINUTES.toMillis(30));
            assertThat(Instant.ofEpochMilli(endTimeWallClock))
                    .isEqualTo(Instant.parse("2023-01-02T04:00:00Z"));

            consumer.accept(
                    createAggregatedPowerStats(startTime, startTimeWallClock, endTime - startTime));
            return null;
        }).doAnswer(invocation -> {
            long startTime = invocation.getArgument(1);
            long endTime = invocation.getArgument(2);
            Consumer<AggregatedPowerStats> consumer = invocation.getArgument(3);

            long startTimeWallClock =
                    mClock.currentTime - (mMonotonicClock.monotonicTime() - startTime);
            long endTimeWallClock =
                    mClock.currentTime - (mMonotonicClock.monotonicTime() - endTime);

            assertThat(Instant.ofEpochMilli(startTimeWallClock))
                    .isEqualTo(Instant.parse("2023-01-02T04:00:00Z"));
            assertThat(Instant.ofEpochMilli(endTimeWallClock))
                    .isEqualTo(Instant.parse("2023-01-02T04:30:00Z"));

            consumer.accept(
                    createAggregatedPowerStats(startTime, startTimeWallClock, endTime - startTime));
            return null;
        }).when(mPowerStatsAggregator).aggregatePowerStats(any(BatteryStatsHistory.class),
                anyLong(), anyLong(), any(Consumer.class));

        mPowerStatsScheduler.start(/*enabled*/ true);
        ConditionVariable done = new ConditionVariable();
        mHandler.post(done::open);
        done.block();

        assertThat(mPowerStatsCollectionOccurred).isTrue();
        assertThat(mScheduledAlarms).containsExactly(
                START_REALTIME + TimeUnit.MINUTES.toMillis(90) + TimeUnit.HOURS.toMillis(1));

        verify(mPowerStatsAggregator, times(2)).aggregatePowerStats(
                any(BatteryStatsHistory.class), anyLong(), anyLong(), any(Consumer.class));

        List<PowerStatsSpan.Metadata> contents = mPowerStatsStore.getTableOfContents();
        assertThat(contents).hasSize(3);
        // Skip the first entry, which was placed in the store at the beginning of this test
        PowerStatsSpan.TimeFrame timeFrame1 = contents.get(1).getTimeFrames().get(0);
        PowerStatsSpan.TimeFrame timeFrame2 = contents.get(2).getTimeFrames().get(0);
        assertThat(timeFrame1.startMonotonicTime).isEqualTo(START_REALTIME + 123);
        assertThat(timeFrame2.startMonotonicTime)
                .isEqualTo(timeFrame1.startMonotonicTime + timeFrame1.duration);
        assertThat(Instant.ofEpochMilli(timeFrame2.startTime))
                .isEqualTo(Instant.parse("2023-01-02T04:00:00Z"));
        assertThat(Duration.ofMillis(timeFrame2.duration)).isEqualTo(Duration.ofMinutes(30));
    }

    private AggregatedPowerStats createAggregatedPowerStats(long monotonicTime, long currentTime,
            long duration) {
        AggregatedPowerStats stats = new AggregatedPowerStats(new AggregatedPowerStatsConfig());
        stats.addClockUpdate(monotonicTime, currentTime);
        stats.setDuration(duration);
        return stats;
    }
}
