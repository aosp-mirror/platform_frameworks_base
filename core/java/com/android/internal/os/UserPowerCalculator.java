/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.util.SparseArray;

import com.android.internal.util.ArrayUtils;

/**
 * Computes power consumed by Users
 */
public class UserPowerCalculator extends PowerCalculator {

    @Override
    public boolean isPowerComponentSupported(@BatteryConsumer.PowerComponent int powerComponent) {
        return true;
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final int[] userIds = query.getUserIds();
        if (ArrayUtils.contains(userIds, UserHandle.USER_ALL)) {
            return;
        }

        SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();

        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder uidBuilder = uidBatteryConsumerBuilders.valueAt(i);
            if (uidBuilder.isVirtualUid()) {
                continue;
            }

            final int uid = uidBuilder.getUid();
            if (UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
                continue;
            }

            final int userId = UserHandle.getUserId(uid);
            if (!ArrayUtils.contains(userIds, userId)) {
                uidBuilder.excludeFromBatteryUsageStats();
                builder.getOrCreateUserBatteryConsumerBuilder(userId)
                        .addUidBatteryConsumer(uidBuilder);
            }
        }
    }
}
