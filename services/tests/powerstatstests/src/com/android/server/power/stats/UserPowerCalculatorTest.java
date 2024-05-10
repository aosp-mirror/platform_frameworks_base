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

package com.android.server.power.stats;

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.os.UserBatteryConsumer;
import android.os.UserHandle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class UserPowerCalculatorTest {
    public static final int USER1 = 0;
    public static final int USER2 = 1625;

    private static final int APP_UID1 = Process.FIRST_APPLICATION_UID + 42;
    private static final int APP_UID2 = Process.FIRST_APPLICATION_UID + 272;
    private static final int APP_UID3 = Process.FIRST_APPLICATION_UID + 314;

    @Rule
    public final BatteryUsageStatsRule mStatsRule = new BatteryUsageStatsRule();

    @Test
    public void testAllUsers() {
        prepareUidBatteryConsumers();

        UserPowerCalculator calculator = new UserPowerCalculator();

        mStatsRule.apply(BatteryUsageStatsQuery.DEFAULT, calculator, new FakeAudioPowerCalculator(),
                new FakeVideoPowerCalculator());

        assertThat(mStatsRule.getUserBatteryConsumer(USER1)).isNull();

        assertThat(mStatsRule.getUidBatteryConsumer(UserHandle.getUid(USER1, APP_UID1))
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_AUDIO)).isEqualTo(3000);
        assertThat(mStatsRule.getUidBatteryConsumer(UserHandle.getUid(USER1, APP_UID1))
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_VIDEO)).isEqualTo(7000);

        assertThat(mStatsRule.getUserBatteryConsumer(USER2)).isNull();

        assertThat(mStatsRule.getUidBatteryConsumer(UserHandle.getUid(USER2, APP_UID2))
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_AUDIO)).isEqualTo(5555);
        assertThat(mStatsRule.getUidBatteryConsumer(UserHandle.getUid(USER2, APP_UID2))
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_VIDEO)).isEqualTo(9999);
    }

    @Test
    public void testSpecificUser() {
        prepareUidBatteryConsumers();

        UserPowerCalculator calculator = new UserPowerCalculator();

        mStatsRule.apply(new BatteryUsageStatsQuery.Builder().addUser(UserHandle.of(USER1)).build(),
                calculator, new FakeAudioPowerCalculator(), new FakeVideoPowerCalculator());

        assertThat(mStatsRule.getUserBatteryConsumer(USER1)).isNull();

        assertThat(mStatsRule.getUidBatteryConsumer(UserHandle.getUid(USER1, APP_UID1))
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_AUDIO)).isEqualTo(3000);
        assertThat(mStatsRule.getUidBatteryConsumer(UserHandle.getUid(USER1, APP_UID1))
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_VIDEO)).isEqualTo(7000);
        assertThat(mStatsRule.getUidBatteryConsumer(UserHandle.getUid(USER1, APP_UID2))).isNull();
        assertThat(mStatsRule.getUidBatteryConsumer(UserHandle.getUid(USER1, APP_UID3))
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_AUDIO)).isEqualTo(7070);
        assertThat(mStatsRule.getUidBatteryConsumer(UserHandle.getUid(USER1, APP_UID3))
                .getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_VIDEO)).isEqualTo(11110);

        UserBatteryConsumer user2 = mStatsRule.getUserBatteryConsumer(USER2);
        assertThat(user2.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_AUDIO))
                .isEqualTo(15308);
        assertThat(user2.getUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_VIDEO))
                .isEqualTo(24196);

        assertThat(mStatsRule.getUidBatteryConsumer(UserHandle.getUid(USER2, APP_UID1))).isNull();
        assertThat(mStatsRule.getUidBatteryConsumer(UserHandle.getUid(USER2, APP_UID2))).isNull();
        assertThat(mStatsRule.getUidBatteryConsumer(UserHandle.getUid(USER2, APP_UID3))).isNull();
    }

    private void prepareUidBatteryConsumers() {
        prepareUidBatteryConsumer(USER1, APP_UID1, 1000, 2000, 3000, 4000);
        prepareUidBatteryConsumer(USER2, APP_UID2, 2222, 3333, 4444, 5555);
        prepareUidBatteryConsumer(USER1, APP_UID3, 3030, 4040, 5050, 6060);
        prepareUidBatteryConsumer(USER2, APP_UID3, 4321, 5432, 6543, 7654);
    }

    private void prepareUidBatteryConsumer(int userId, int uid, long audioDuration1Ms,
            long audioDuration2Ms, long videoDuration1Ms, long videoDuration2Ms) {
        BatteryStatsImpl.Uid uidStats = mStatsRule.getUidStats(UserHandle.getUid(userId, uid));

        // Use "audio" and "video" to fake some power consumption. Could be any other type of usage.
        uidStats.noteAudioTurnedOnLocked(0);
        uidStats.noteAudioTurnedOffLocked(audioDuration1Ms);
        uidStats.noteAudioTurnedOnLocked(1000000);
        uidStats.noteAudioTurnedOffLocked(1000000 + audioDuration2Ms);

        uidStats.noteVideoTurnedOnLocked(0);
        uidStats.noteVideoTurnedOffLocked(videoDuration1Ms);
        uidStats.noteVideoTurnedOnLocked(2000000);
        uidStats.noteVideoTurnedOffLocked(2000000 + videoDuration2Ms);
    }

    private static class FakeAudioPowerCalculator extends PowerCalculator {

        @Override
        public boolean isPowerComponentSupported(
                @BatteryConsumer.PowerComponent int powerComponent) {
            return powerComponent == BatteryConsumer.POWER_COMPONENT_AUDIO;
        }

        @Override
        protected void calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
                long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
            long durationMs = u.getAudioTurnedOnTimer().getTotalTimeLocked(rawRealtimeUs, 0);
            app.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_AUDIO, durationMs / 1000);
        }
    }

    private static class FakeVideoPowerCalculator extends PowerCalculator {

        @Override
        public boolean isPowerComponentSupported(
                @BatteryConsumer.PowerComponent int powerComponent) {
            return powerComponent == BatteryConsumer.POWER_COMPONENT_VIDEO;
        }

        @Override
        protected void calculateApp(UidBatteryConsumer.Builder app, BatteryStats.Uid u,
                long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
            long durationMs = u.getVideoTurnedOnTimer().getTotalTimeLocked(rawRealtimeUs, 0);
            app.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_VIDEO, durationMs / 1000);
        }
    }
}
