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

import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.util.SparseArray;

import com.android.internal.util.ArrayUtils;

import java.util.List;

/**
 * Computes power consumed by Users
 */
public class UserPowerCalculator extends PowerCalculator {

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
            UidBatteryConsumer.Builder uidBuilder = uidBatteryConsumerBuilders.valueAt(i);
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

    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {
        final boolean forAllUsers = (asUsers.get(UserHandle.USER_ALL) != null);
        if (forAllUsers) {
            return;
        }

        SparseArray<BatterySipper> userSippers = new SparseArray<>();

        for (int i = sippers.size() - 1; i >= 0; i--) {
            BatterySipper sipper = sippers.get(i);
            final int uid = sipper.getUid();
            final int userId = UserHandle.getUserId(uid);
            if (asUsers.get(userId) == null
                    && UserHandle.getAppId(uid) >= Process.FIRST_APPLICATION_UID) {
                // We are told to just report this user's apps as one accumulated entry.
                BatterySipper userSipper = userSippers.get(userId);
                if (userSipper == null) {
                    userSipper = new BatterySipper(BatterySipper.DrainType.USER, null, 0);
                    userSipper.userId = userId;
                    userSippers.put(userId, userSipper);
                }
                userSipper.add(sipper);
                sipper.isAggregated = true;
            }
        }

        for (int i = 0; i < userSippers.size(); i++) {
            BatterySipper sipper = userSippers.valueAt(i);
            if (sipper.sumPower() > 0) {
                sippers.add(sipper);
            }
        }
    }
}
