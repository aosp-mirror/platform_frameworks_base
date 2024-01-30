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

import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.PersistableBundle;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.MonotonicClock;
import com.android.internal.os.PowerStats;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PowerStatsAggregatorTest {
    private static final int TEST_POWER_COMPONENT = 77;
    private static final int TEST_UID = 42;
    private static final long START_TIME = 1234;

    private final MockClock mClock = new MockClock();
    private final MonotonicClock mMonotonicClock = new MonotonicClock(START_TIME, mClock);
    private BatteryStatsHistory mHistory;
    private PowerStatsAggregator mAggregator;
    private int mAggregatedStatsCount;

    @Before
    public void setup() throws ParseException {
        mHistory = new BatteryStatsHistory(32, 1024,
                mock(BatteryStatsHistory.HistoryStepDetailsCalculator.class), mClock,
                mMonotonicClock, mock(BatteryStatsHistory.TraceDelegate.class));

        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(TEST_POWER_COMPONENT)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE);
        mAggregator = new PowerStatsAggregator(config, mHistory);
    }

    @Test
    public void stateUpdates() {
        PowerStats.Descriptor descriptor =
                new PowerStats.Descriptor(TEST_POWER_COMPONENT, "majorDrain", 1, 1,
                        new PersistableBundle());
        PowerStats powerStats = new PowerStats(descriptor);

        mClock.currentTime = 1222156800000L;    // An important date in world history

        mHistory.forceRecordAllHistory();
        powerStats.stats = new long[]{0};
        powerStats.uidStats.put(TEST_UID, new long[]{0});
        mHistory.recordPowerStats(mClock.realtime, mClock.uptime, powerStats);

        mHistory.recordBatteryState(mClock.realtime, mClock.uptime, 10, /* plugged */ true);
        mHistory.recordStateStartEvent(mClock.realtime, mClock.uptime,
                BatteryStats.HistoryItem.STATE_SCREEN_ON_FLAG);
        mHistory.recordProcessStateChange(mClock.realtime, mClock.uptime, TEST_UID,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);

        advance(1000);

        powerStats.stats = new long[]{10000};
        powerStats.uidStats.put(TEST_UID, new long[]{1234});
        mHistory.recordPowerStats(mClock.realtime, mClock.uptime, powerStats);

        mHistory.recordBatteryState(mClock.realtime, mClock.uptime, 90, /* plugged */ false);
        mHistory.recordStateStopEvent(mClock.realtime, mClock.uptime,
                BatteryStats.HistoryItem.STATE_SCREEN_ON_FLAG);

        advance(1000);

        mHistory.recordProcessStateChange(mClock.realtime, mClock.uptime, TEST_UID,
                BatteryConsumer.PROCESS_STATE_BACKGROUND);

        advance(1000);

        mClock.currentTime += 60 * 60 * 1000;       // one hour
        mHistory.recordCurrentTimeChange(mClock.realtime, mClock.uptime, mClock.currentTime);

        advance(2000);

        powerStats.stats = new long[]{20000};
        powerStats.uidStats.put(TEST_UID, new long[]{4444});
        mHistory.recordPowerStats(mClock.realtime, mClock.uptime, powerStats);

        mAggregator.aggregatePowerStats(0, 0, stats -> {
            assertThat(mAggregatedStatsCount++).isEqualTo(0);
            assertThat(stats.getStartTime()).isEqualTo(START_TIME);

            List<AggregatedPowerStats.ClockUpdate> clockUpdates = stats.getClockUpdates();
            assertThat(clockUpdates).hasSize(2);

            AggregatedPowerStats.ClockUpdate clockUpdate0 = clockUpdates.get(0);
            assertThat(clockUpdate0.monotonicTime).isEqualTo(1234);
            assertThat(formatDateTime(clockUpdate0.currentTime)).isEqualTo("2008-09-23 08:00:00");

            AggregatedPowerStats.ClockUpdate clockUpdate1 = clockUpdates.get(1);
            assertThat(clockUpdate1.monotonicTime).isEqualTo(1234 + 3000);
            assertThat(formatDateTime(clockUpdate1.currentTime)).isEqualTo("2008-09-23 09:00:03");

            assertThat(stats.getDuration()).isEqualTo(5000);

            long[] values = new long[1];

            PowerComponentAggregatedPowerStats powerComponentStats = stats.getPowerComponentStats(
                    TEST_POWER_COMPONENT);

            assertThat(powerComponentStats.getDeviceStats(values, new int[]{
                    AggregatedPowerStatsConfig.POWER_STATE_OTHER,
                    AggregatedPowerStatsConfig.SCREEN_STATE_ON}))
                    .isTrue();
            assertThat(values).isEqualTo(new long[]{10000});

            assertThat(powerComponentStats.getDeviceStats(values, new int[]{
                    AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                    AggregatedPowerStatsConfig.SCREEN_STATE_OTHER}))
                    .isTrue();
            assertThat(values).isEqualTo(new long[]{20000});

            assertThat(powerComponentStats.getUidStats(values, TEST_UID, new int[]{
                    AggregatedPowerStatsConfig.POWER_STATE_OTHER,
                    AggregatedPowerStatsConfig.SCREEN_STATE_ON,
                    BatteryConsumer.PROCESS_STATE_FOREGROUND}))
                    .isTrue();
            assertThat(values).isEqualTo(new long[]{1234});

            assertThat(powerComponentStats.getUidStats(values, TEST_UID, new int[]{
                    AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                    AggregatedPowerStatsConfig.SCREEN_STATE_OTHER,
                    BatteryConsumer.PROCESS_STATE_FOREGROUND}))
                    .isTrue();
            assertThat(values).isEqualTo(new long[]{1111});

            assertThat(powerComponentStats.getUidStats(values, TEST_UID, new int[]{
                    AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                    AggregatedPowerStatsConfig.SCREEN_STATE_OTHER,
                    BatteryConsumer.PROCESS_STATE_BACKGROUND}))
                    .isTrue();
            assertThat(values).isEqualTo(new long[]{3333});
        });
    }

    @NonNull
    private static CharSequence formatDateTime(long timeInMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
        format.getCalendar().setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date(timeInMillis));
    }

    @Test
    public void incompatiblePowerStats() {
        PowerStats.Descriptor descriptor =
                new PowerStats.Descriptor(TEST_POWER_COMPONENT, "majorDrain", 1, 1,
                        new PersistableBundle());
        PowerStats powerStats = new PowerStats(descriptor);

        mHistory.forceRecordAllHistory();
        powerStats.stats = new long[]{0};
        powerStats.uidStats.put(TEST_UID, new long[]{0});
        mHistory.recordPowerStats(mClock.realtime, mClock.uptime, powerStats);
        mHistory.recordBatteryState(mClock.realtime, mClock.uptime, 10, /* plugged */ true);
        mHistory.recordProcessStateChange(mClock.realtime, mClock.uptime, TEST_UID,
                BatteryConsumer.PROCESS_STATE_FOREGROUND);

        advance(1000);

        powerStats.stats = new long[]{10000};
        powerStats.uidStats.put(TEST_UID, new long[]{1234});
        mHistory.recordPowerStats(mClock.realtime, mClock.uptime, powerStats);

        mHistory.recordBatteryState(mClock.realtime, mClock.uptime, 90, /* plugged */ false);

        advance(1000);

        descriptor = new PowerStats.Descriptor(TEST_POWER_COMPONENT, "majorDrain", 1, 1,
                PersistableBundle.forPair("something", "changed"));
        powerStats = new PowerStats(descriptor);
        powerStats.stats = new long[]{20000};
        powerStats.uidStats.put(TEST_UID, new long[]{4444});
        mHistory.recordPowerStats(mClock.realtime, mClock.uptime, powerStats);

        advance(1000);

        mHistory.recordBatteryState(mClock.realtime, mClock.uptime, 50, /* plugged */ true);

        mAggregator.aggregatePowerStats(0, 0, stats -> {
            long[] values = new long[1];

            PowerComponentAggregatedPowerStats powerComponentStats =
                    stats.getPowerComponentStats(TEST_POWER_COMPONENT);

            if (mAggregatedStatsCount == 0) {
                assertThat(stats.getStartTime()).isEqualTo(START_TIME);
                assertThat(stats.getDuration()).isEqualTo(2000);

                assertThat(powerComponentStats.getDeviceStats(values, new int[]{
                        AggregatedPowerStatsConfig.POWER_STATE_OTHER,
                        AggregatedPowerStatsConfig.SCREEN_STATE_ON}))
                        .isTrue();
                assertThat(values).isEqualTo(new long[]{10000});
                assertThat(powerComponentStats.getUidStats(values, TEST_UID, new int[]{
                        AggregatedPowerStatsConfig.POWER_STATE_OTHER,
                        AggregatedPowerStatsConfig.SCREEN_STATE_ON,
                        BatteryConsumer.PROCESS_STATE_FOREGROUND}))
                        .isTrue();
                assertThat(values).isEqualTo(new long[]{1234});
            } else if (mAggregatedStatsCount == 1) {
                assertThat(stats.getStartTime()).isEqualTo(START_TIME + 2000);
                assertThat(stats.getDuration()).isEqualTo(1000);

                assertThat(powerComponentStats.getDeviceStats(values, new int[]{
                        AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                        AggregatedPowerStatsConfig.SCREEN_STATE_ON}))
                        .isTrue();
                assertThat(values).isEqualTo(new long[]{20000});
                assertThat(powerComponentStats.getUidStats(values, TEST_UID, new int[]{
                        AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                        AggregatedPowerStatsConfig.SCREEN_STATE_ON,
                        BatteryConsumer.PROCESS_STATE_FOREGROUND}))
                        .isTrue();
                assertThat(values).isEqualTo(new long[]{4444});
            } else {
                fail();
            }
            mAggregatedStatsCount++;
        });
    }

    private void advance(long durationMs) {
        mClock.realtime += durationMs;
        mClock.uptime += durationMs;
        mClock.currentTime += durationMs;
    }
}
