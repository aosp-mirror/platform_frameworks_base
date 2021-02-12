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

package com.android.server.am;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.hardware.power.stats.Channel;
import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.hardware.power.stats.EnergyMeasurement;
import android.hardware.power.stats.PowerEntity;
import android.hardware.power.stats.StateResidencyResult;
import android.power.PowerStatsInternal;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;

import com.android.internal.os.BatteryStatsImpl;

import org.junit.Before;

import java.util.concurrent.CompletableFuture;

/**
 * Tests for {@link BatteryExternalStatsWorker}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:BatteryExternalStatsWorkerTest
 */
public class BatteryExternalStatsWorkerTest {
    private BatteryExternalStatsWorker mBatteryExternalStatsWorker;
    private TestBatteryStatsImpl mBatteryStatsImpl;
    private TestPowerStatsInternal mPowerStatsInternal;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getContext();

        mBatteryStatsImpl = new TestBatteryStatsImpl();
        mPowerStatsInternal = new TestPowerStatsInternal();
        mBatteryExternalStatsWorker = new BatteryExternalStatsWorker(new TestInjector(context),
                mBatteryStatsImpl);
    }

    public class TestInjector extends BatteryExternalStatsWorker.Injector {
        public TestInjector(Context context) {
            super(context);
        }

        public <T> T getSystemService(Class<T> serviceClass) {
            return null;
        }

        public <T> T getLocalService(Class<T> serviceClass) {
            if (serviceClass == PowerStatsInternal.class) {
                return (T) mPowerStatsInternal;
            }
            return null;
        }
    }

    public class TestBatteryStatsImpl extends BatteryStatsImpl {
    }

    public class TestPowerStatsInternal extends PowerStatsInternal {
        private final SparseArray<EnergyConsumer> mEnergyConsumers = new SparseArray<>();
        private final SparseArray<EnergyConsumerResult> mEnergyConsumerResults =
                new SparseArray<>();
        private final int mTimeSinceBoot = 0;

        @Override
        public EnergyConsumer[] getEnergyConsumerInfo() {
            final int size = mEnergyConsumers.size();
            final EnergyConsumer[] consumers = new EnergyConsumer[size];
            for (int i = 0; i < size; i++) {
                consumers[i] = mEnergyConsumers.valueAt(i);
            }
            return consumers;
        }

        @Override
        public CompletableFuture<EnergyConsumerResult[]> getEnergyConsumedAsync(
                int[] energyConsumerIds) {
            final CompletableFuture<EnergyConsumerResult[]> future = new CompletableFuture();
            final int size = mEnergyConsumerResults.size();
            final EnergyConsumerResult[] results = new EnergyConsumerResult[size];
            for (int i = 0; i < size; i++) {
                results[i] = mEnergyConsumerResults.valueAt(i);
            }
            future.complete(results);
            return future;
        }

        @Override
        public PowerEntity[] getPowerEntityInfo() {
            return new PowerEntity[0];
        }

        @Override
        public CompletableFuture<StateResidencyResult[]> getStateResidencyAsync(
                int[] powerEntityIds) {
            return new CompletableFuture<>();
        }

        @Override
        public Channel[] getEnergyMeterInfo() {
            return new Channel[0];
        }

        @Override
        public CompletableFuture<EnergyMeasurement[]> readEnergyMeterAsync(
                int[] channelIds) {
            return new CompletableFuture<>();
        }

        /**
         * Util method to add a new EnergyConsumer for testing
         *
         * @return the EnergyConsumer id of the new EnergyConsumer
         */
        public int addEnergyConsumer(@EnergyConsumerType byte type, int ordinal, String name) {
            final EnergyConsumer consumer = new EnergyConsumer();
            final int id = getNextAvailableId();
            consumer.id = id;
            consumer.type = type;
            consumer.ordinal = ordinal;
            consumer.name = name;
            mEnergyConsumers.put(id, consumer);

            final EnergyConsumerResult result = new EnergyConsumerResult();
            result.id = id;
            result.timestampMs = mTimeSinceBoot;
            result.energyUWs = 0;
            mEnergyConsumerResults.put(id, result);
            return id;
        }

        public void incrementEnergyConsumption(int id, long energyUWs) {
            EnergyConsumerResult result = mEnergyConsumerResults.get(id, null);
            assertNotNull(result);
            result.energyUWs += energyUWs;
        }

        private int getNextAvailableId() {
            final int size = mEnergyConsumers.size();
            // Just return the first index that does not match the key (aka the EnergyConsumer id)
            for (int i = size - 1; i >= 0; i--) {
                if (mEnergyConsumers.keyAt(i) == i) return i + 1;
            }
            // Otherwise return the lowest id
            return 0;
        }
    }
}
