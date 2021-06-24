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

import static com.google.common.truth.Truth.assertThat;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.BatteryConsumer;
import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Binder;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.Parcel;
import android.os.UidBatteryConsumer;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class BatteryUsageStatsPerfTest {

    @Rule
    public final PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    /**
     * Measures the performance of {@link BatteryStatsManager#getBatteryUsageStats()},
     * which triggers a battery stats sync on every iteration.
     */
    @Test
    public void testGetBatteryUsageStats() {
        final Context context = InstrumentationRegistry.getContext();
        final BatteryStatsManager batteryStatsManager =
                context.getSystemService(BatteryStatsManager.class);

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            BatteryUsageStats batteryUsageStats = batteryStatsManager.getBatteryUsageStats(
                    new BatteryUsageStatsQuery.Builder().setMaxStatsAgeMs(0).build());

            state.pauseTiming();

            List<UidBatteryConsumer> uidBatteryConsumers =
                    batteryUsageStats.getUidBatteryConsumers();
            double power = 0;
            for (int i = 0; i < uidBatteryConsumers.size(); i++) {
                UidBatteryConsumer uidBatteryConsumer = uidBatteryConsumers.get(i);
                power += uidBatteryConsumer.getConsumedPower();
            }

            assertThat(power).isGreaterThan(0.0);

            state.resumeTiming();
        }
    }

    private final ConditionVariable mServiceConnected = new ConditionVariable();
    private IBinder mService;

    private final ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            mService = service;
            mServiceConnected.open();
        }

        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    /**
     * Measures the performance of transferring BatteryUsageStats over a Binder.
     */
    @Test
    public void testBatteryUsageStatsTransferOverBinder() throws Exception {
        final Context context = InstrumentationRegistry.getContext();
        context.bindService(
                new Intent(context, BatteryUsageStatsService.class),
                mConnection, Context.BIND_AUTO_CREATE);
        mServiceConnected.block(30000);
        assertThat(mService).isNotNull();

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            final Parcel data = Parcel.obtain();
            final Parcel reply = Parcel.obtain();
            mService.transact(42, data, reply, 0);
            final BatteryUsageStats batteryUsageStats =
                    BatteryUsageStats.CREATOR.createFromParcel(reply);
            reply.recycle();
            data.recycle();

            state.pauseTiming();

            assertThat(batteryUsageStats.getBatteryCapacity()).isEqualTo(4000);
            assertThat(batteryUsageStats.getUidBatteryConsumers()).hasSize(1000);
            final UidBatteryConsumer uidBatteryConsumer =
                    batteryUsageStats.getUidBatteryConsumers().get(0);
            assertThat(uidBatteryConsumer.getConsumedPower(1)).isEqualTo(123);

            state.resumeTiming();
        }

        context.unbindService(mConnection);
    }

    /* This service runs in a separate process */
    public static class BatteryUsageStatsService extends Service {
        private final BatteryUsageStats mBatteryUsageStats;

        public BatteryUsageStatsService() {
            mBatteryUsageStats = buildBatteryUsageStats();
        }

        @Nullable
        @Override
        public IBinder onBind(Intent intent) {
            return new Binder() {
                @Override
                protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply,
                        int flags) {
                    mBatteryUsageStats.writeToParcel(reply, 0);
                    return true;
                }
            };
        }
    }

    private static BatteryUsageStats buildBatteryUsageStats() {
        final BatteryUsageStats.Builder builder =
                new BatteryUsageStats.Builder(new String[]{"FOO"}, true)
                        .setBatteryCapacity(4000)
                        .setDischargePercentage(20)
                        .setDischargedPowerRange(1000, 2000)
                        .setStatsStartTimestamp(1000)
                        .setStatsEndTimestamp(3000);

        builder.getAggregateBatteryConsumerBuilder(
                BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                .setConsumedPower(123)
                .setConsumedPower(
                        BatteryConsumer.POWER_COMPONENT_CPU, 10100)
                .setConsumedPowerForCustomComponent(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 10200)
                .setUsageDurationMillis(
                        BatteryConsumer.POWER_COMPONENT_CPU, 10300)
                .setUsageDurationForCustomComponentMillis(
                        BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 10400);

        for (int i = 0; i < 1000; i++) {
            final UidBatteryConsumer.Builder consumerBuilder =
                    builder.getOrCreateUidBatteryConsumerBuilder(i)
                            .setPackageWithHighestDrain("example.packagename" + i)
                            .setTimeInStateMs(UidBatteryConsumer.STATE_FOREGROUND, i * 2000)
                            .setTimeInStateMs(UidBatteryConsumer.STATE_BACKGROUND, i * 1000);
            for (int componentId = 0; componentId < BatteryConsumer.POWER_COMPONENT_COUNT;
                    componentId++) {
                consumerBuilder.setConsumedPower(componentId, componentId * 123.0,
                        BatteryConsumer.POWER_MODEL_POWER_PROFILE);
                consumerBuilder.setUsageDurationMillis(componentId, componentId * 1000);
            }

            consumerBuilder.setConsumedPowerForCustomComponent(
                    BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 1234)
                    .setUsageDurationForCustomComponentMillis(
                            BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID, 4321);
        }
        return builder.build();
    }
}
