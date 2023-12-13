/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.power.stats;

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

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.MonotonicClock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
public class PowerStatsSchedulerTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private PowerStatsStore mPowerStatsStore;
    private Handler mHandler;
    private MockClock mClock = new MockClock();
    private MonotonicClock mMonotonicClock = new MonotonicClock(0, mClock);
    private PowerStatsScheduler mPowerStatsScheduler;
    private PowerStatsAggregator mPowerStatsAggregator;
    private AggregatedPowerStatsConfig mAggregatedPowerStatsConfig;
    private List<Long> mScheduledAlarms = new ArrayList<>();
    private boolean mPowerStatsCollectionOccurred;

    private static final int START_REALTIME = 7654321;

    @Before
    @SuppressWarnings("GuardedBy")
    public void setup() throws IOException {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        mClock.currentTime = Instant.parse("2023-01-02T03:04:05.00Z").toEpochMilli();
        mClock.realtime = START_REALTIME;

        HandlerThread bgThread = new HandlerThread("bg thread");
        bgThread.start();
        mHandler = new Handler(bgThread.getLooper());
        mAggregatedPowerStatsConfig = new AggregatedPowerStatsConfig();
        mPowerStatsStore = new PowerStatsStore(
                Files.createTempDirectory("PowerStatsSchedulerTest").toFile(),
                mHandler, mAggregatedPowerStatsConfig);
        mPowerStatsAggregator = mock(PowerStatsAggregator.class);
        mPowerStatsScheduler = new PowerStatsScheduler(
                () -> mPowerStatsCollectionOccurred = true,
                mPowerStatsAggregator, TimeUnit.MINUTES.toMillis(30), TimeUnit.HOURS.toMillis(1),
                mPowerStatsStore,
                ((triggerAtMillis, tag, onAlarmListener, handler) ->
                        mScheduledAlarms.add(triggerAtMillis)),
                mClock, mMonotonicClock, () -> 12345L, mHandler);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void storeAggregatePowerStats() {
        mPowerStatsStore.reset();

        assertThat(mPowerStatsStore.getTableOfContents()).isEmpty();

        mPowerStatsStore.storeAggregatedPowerStats(
                createAggregatedPowerStats(mMonotonicClock.monotonicTime(), mClock.currentTime,
                        123));

        long delayBeforeAggregating = TimeUnit.MINUTES.toMillis(90);
        mClock.realtime += delayBeforeAggregating;
        mClock.currentTime += delayBeforeAggregating;

        doAnswer(invocation -> {
            // The first span is longer than 30 min, because the end time is being aligned with
            // the wall clock.  Subsequent spans should be precisely 30 minutes.
            long startTime = invocation.getArgument(0);
            long endTime = invocation.getArgument(1);
            Consumer<AggregatedPowerStats> consumer = invocation.getArgument(2);

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
            long startTime = invocation.getArgument(0);
            long endTime = invocation.getArgument(1);
            Consumer<AggregatedPowerStats> consumer = invocation.getArgument(2);

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
        }).when(mPowerStatsAggregator).aggregatePowerStats(anyLong(), anyLong(),
                any(Consumer.class));

        mPowerStatsScheduler.start(/*enabled*/ true);
        ConditionVariable done = new ConditionVariable();
        mHandler.post(done::open);
        done.block();

        assertThat(mPowerStatsCollectionOccurred).isTrue();
        assertThat(mScheduledAlarms).containsExactly(
                START_REALTIME + TimeUnit.MINUTES.toMillis(90) + TimeUnit.HOURS.toMillis(1));

        verify(mPowerStatsAggregator, times(2))
                .aggregatePowerStats(anyLong(), anyLong(), any(Consumer.class));

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
        AggregatedPowerStats stats = new AggregatedPowerStats(mAggregatedPowerStatsConfig);
        stats.addClockUpdate(monotonicTime, currentTime);
        stats.setDuration(duration);
        return stats;
    }

    @Test
    public void alignToWallClock() {
        // Expect the aligned value to be adjusted by 1 min 30 sec - rounded to the next 15 min
        assertThat(PowerStatsScheduler.alignToWallClock(123, TimeUnit.MINUTES.toMillis(15),
                123 + TimeUnit.HOURS.toMillis(2),
                Instant.parse("2007-12-03T10:13:30.00Z").toEpochMilli())).isEqualTo(
                123 + Duration.parse("PT1M30S").toMillis());

        // Expect the aligned value to be adjusted by 2 min 45 sec - rounded to the next 15 min
        assertThat(PowerStatsScheduler.alignToWallClock(123, TimeUnit.MINUTES.toMillis(15),
                123 + TimeUnit.HOURS.toMillis(2),
                Instant.parse("2007-12-03T10:57:15.00Z").toEpochMilli())).isEqualTo(
                123 + Duration.parse("PT2M45S").toMillis());

        // Expect the aligned value to be adjusted by 15 sec - rounded to the next 1 min
        assertThat(PowerStatsScheduler.alignToWallClock(123, TimeUnit.MINUTES.toMillis(1),
                123 + TimeUnit.HOURS.toMillis(2),
                Instant.parse("2007-12-03T10:14:45.00Z").toEpochMilli())).isEqualTo(
                123 + Duration.parse("PT15S").toMillis());

        // Expect the aligned value to be adjusted by 1 hour 46 min 30 sec -
        // rounded to the next 3 hours
        assertThat(PowerStatsScheduler.alignToWallClock(123, TimeUnit.HOURS.toMillis(3),
                123 + TimeUnit.HOURS.toMillis(9),
                Instant.parse("2007-12-03T10:13:30.00Z").toEpochMilli())).isEqualTo(
                123 + Duration.parse("PT1H46M30S").toMillis());
    }
}
