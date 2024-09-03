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

import static com.android.server.power.stats.BatteryStatsImpl.ExternalStatsSync.UPDATE_ALL;
import static com.android.server.power.stats.BatteryStatsImpl.ExternalStatsSync.UPDATE_BT;
import static com.android.server.power.stats.BatteryStatsImpl.ExternalStatsSync.UPDATE_CAMERA;
import static com.android.server.power.stats.BatteryStatsImpl.ExternalStatsSync.UPDATE_CPU;
import static com.android.server.power.stats.BatteryStatsImpl.ExternalStatsSync.UPDATE_DISPLAY;
import static com.android.server.power.stats.BatteryStatsImpl.ExternalStatsSync.UPDATE_RADIO;
import static com.android.server.power.stats.BatteryStatsImpl.ExternalStatsSync.UPDATE_WIFI;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.hardware.power.stats.Channel;
import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.hardware.power.stats.EnergyMeasurement;
import android.hardware.power.stats.PowerEntity;
import android.hardware.power.stats.StateResidencyResult;
import android.os.Handler;
import android.os.Looper;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.platform.test.ravenwood.RavenwoodRule;
import android.power.PowerStatsInternal;
import android.util.IntArray;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;

import com.android.internal.os.Clock;
import com.android.internal.os.CpuScalingPolicies;
import com.android.internal.os.MonotonicClock;
import com.android.internal.os.PowerProfile;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * Tests for {@link BatteryExternalStatsWorker}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:BatteryExternalStatsWorkerTest
 */
@SuppressWarnings("GuardedBy")
@android.platform.test.annotations.DisabledOnRavenwood
public class BatteryExternalStatsWorkerTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private BatteryExternalStatsWorker mBatteryExternalStatsWorker;
    private TestPowerStatsInternal mPowerStatsInternal;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getContext();

        BatteryStatsImpl batteryStats = new BatteryStatsImpl(
                new BatteryStatsImpl.BatteryStatsConfig.Builder().build(), Clock.SYSTEM_CLOCK,
                new MonotonicClock(0, Clock.SYSTEM_CLOCK), null,
                new Handler(Looper.getMainLooper()), null, null, null,
                new PowerProfile(context, true /* forTest */), buildScalingPolicies(),
                new PowerStatsUidResolver());
        mPowerStatsInternal = new TestPowerStatsInternal();
        mBatteryExternalStatsWorker =
                new BatteryExternalStatsWorker(new TestInjector(context), batteryStats);
    }

    @Test
    public void testUpdateWifiState() {
        WifiActivityEnergyInfo firstInfo = new WifiActivityEnergyInfo(1111,
                WifiActivityEnergyInfo.STACK_STATE_STATE_ACTIVE, 11, 22, 33, 44);

        WifiActivityEnergyInfo delta = mBatteryExternalStatsWorker.extractDeltaLocked(firstInfo);

        assertEquals(1111, delta.getTimeSinceBootMillis());
        assertEquals(WifiActivityEnergyInfo.STACK_STATE_STATE_ACTIVE, delta.getStackState());
        assertEquals(0, delta.getControllerTxDurationMillis());
        assertEquals(0, delta.getControllerRxDurationMillis());
        assertEquals(0, delta.getControllerScanDurationMillis());
        assertEquals(0, delta.getControllerIdleDurationMillis());

        WifiActivityEnergyInfo secondInfo = new WifiActivityEnergyInfo(91111,
                WifiActivityEnergyInfo.STACK_STATE_STATE_IDLE, 811, 722, 633, 544);

        delta = mBatteryExternalStatsWorker.extractDeltaLocked(secondInfo);

        assertEquals(91111, delta.getTimeSinceBootMillis());
        assertEquals(WifiActivityEnergyInfo.STACK_STATE_STATE_IDLE, delta.getStackState());
        assertEquals(800, delta.getControllerTxDurationMillis());
        assertEquals(700, delta.getControllerRxDurationMillis());
        assertEquals(600, delta.getControllerScanDurationMillis());
        assertEquals(500, delta.getControllerIdleDurationMillis());
    }

    @Test
    public void testTargetedEnergyConsumerQuerying() {
        final int numCpuClusters = 4;
        final int numDisplays = 5;
        final int numOther = 3;

        // Add some energy consumers used by BatteryExternalStatsWorker.
        final IntArray tempAllIds = new IntArray();

        final int[] displayIds = new int[numDisplays];
        for (int i = 0; i < numDisplays; i++) {
            displayIds[i] = mPowerStatsInternal.addEnergyConsumer(
                    EnergyConsumerType.DISPLAY, i, "display" + i);
            tempAllIds.add(displayIds[i]);
            mPowerStatsInternal.incrementEnergyConsumption(displayIds[i], 12345 + i);
        }
        Arrays.sort(displayIds);

        final int wifiId = mPowerStatsInternal.addEnergyConsumer(EnergyConsumerType.WIFI, 0,
                "wifi");
        tempAllIds.add(wifiId);
        mPowerStatsInternal.incrementEnergyConsumption(wifiId, 23456);

        final int btId = mPowerStatsInternal.addEnergyConsumer(EnergyConsumerType.BLUETOOTH, 0,
                "bt");
        tempAllIds.add(btId);
        mPowerStatsInternal.incrementEnergyConsumption(btId, 34567);

        final int gnssId = mPowerStatsInternal.addEnergyConsumer(EnergyConsumerType.GNSS, 0,
                "gnss");
        tempAllIds.add(gnssId);
        mPowerStatsInternal.incrementEnergyConsumption(gnssId, 787878);

        final int cameraId =
                mPowerStatsInternal.addEnergyConsumer(EnergyConsumerType.CAMERA, 0, "camera");
        tempAllIds.add(cameraId);
        mPowerStatsInternal.incrementEnergyConsumption(cameraId, 901234);

        final int mobileRadioId = mPowerStatsInternal.addEnergyConsumer(
                EnergyConsumerType.MOBILE_RADIO, 0, "mobile_radio");
        tempAllIds.add(mobileRadioId);
        mPowerStatsInternal.incrementEnergyConsumption(mobileRadioId, 62626);

        final int[] cpuClusterIds = new int[numCpuClusters];
        for (int i = 0; i < numCpuClusters; i++) {
            cpuClusterIds[i] = mPowerStatsInternal.addEnergyConsumer(
                    EnergyConsumerType.CPU_CLUSTER, i, "cpu_cluster" + i);
            tempAllIds.add(cpuClusterIds[i]);
            mPowerStatsInternal.incrementEnergyConsumption(cpuClusterIds[i], 1111 + i);
        }
        Arrays.sort(cpuClusterIds);

        final int[] otherIds = new int[numOther];
        for (int i = 0; i < numOther; i++) {
            otherIds[i] = mPowerStatsInternal.addEnergyConsumer(
                    EnergyConsumerType.OTHER, i, "other" + i);
            tempAllIds.add(otherIds[i]);
            mPowerStatsInternal.incrementEnergyConsumption(otherIds[i], 3000 + i);
        }
        Arrays.sort(otherIds);

        final int[] allIds = tempAllIds.toArray();
        Arrays.sort(allIds);

        // Inform BESW that PowerStatsInternal is ready to query
        mBatteryExternalStatsWorker.systemServicesReady();

        final EnergyConsumerResult[] displayResults =
                mBatteryExternalStatsWorker.getEnergyConsumersLocked(UPDATE_DISPLAY).getNow(null);
        // Results should only have the cpu cluster energy consumers
        final int[] receivedDisplayIds = new int[displayResults.length];
        for (int i = 0; i < displayResults.length; i++) {
            receivedDisplayIds[i] = displayResults[i].id;
        }
        Arrays.sort(receivedDisplayIds);
        assertArrayEquals(displayIds, receivedDisplayIds);

        final EnergyConsumerResult[] wifiResults =
                mBatteryExternalStatsWorker.getEnergyConsumersLocked(UPDATE_WIFI).getNow(null);
        // Results should only have the wifi energy consumer
        assertEquals(1, wifiResults.length);
        assertEquals(wifiId, wifiResults[0].id);

        final EnergyConsumerResult[] bluetoothResults =
                mBatteryExternalStatsWorker.getEnergyConsumersLocked(UPDATE_BT).getNow(null);
        // Results should only have the bluetooth energy consumer
        assertEquals(1, bluetoothResults.length);
        assertEquals(btId, bluetoothResults[0].id);

        final EnergyConsumerResult[] mobileRadioResults =
                mBatteryExternalStatsWorker.getEnergyConsumersLocked(UPDATE_RADIO).getNow(null);
        // Results should only have the mobile radio energy consumer
        assertEquals(1, mobileRadioResults.length);
        assertEquals(mobileRadioId, mobileRadioResults[0].id);

        final EnergyConsumerResult[] cpuResults =
                mBatteryExternalStatsWorker.getEnergyConsumersLocked(UPDATE_CPU).getNow(null);
        // Results should only have the cpu cluster energy consumers
        final int[] receivedCpuIds = new int[cpuResults.length];
        for (int i = 0; i < cpuResults.length; i++) {
            receivedCpuIds[i] = cpuResults[i].id;
        }
        Arrays.sort(receivedCpuIds);
        assertArrayEquals(cpuClusterIds, receivedCpuIds);

        final EnergyConsumerResult[] cameraResults =
                mBatteryExternalStatsWorker.getEnergyConsumersLocked(UPDATE_CAMERA).getNow(null);
        // Results should only have the camera energy consumer
        assertEquals(1, cameraResults.length);
        assertEquals(cameraId, cameraResults[0].id);

        final EnergyConsumerResult[] allResults =
                mBatteryExternalStatsWorker.getEnergyConsumersLocked(UPDATE_ALL).getNow(null);
        // All energy consumer results should be available
        final int[] receivedAllIds = new int[allResults.length];
        for (int i = 0; i < allResults.length; i++) {
            receivedAllIds[i] = allResults[i].id;
        }
        Arrays.sort(receivedAllIds);
        assertArrayEquals(allIds, receivedAllIds);
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

    private static CpuScalingPolicies buildScalingPolicies() {
        SparseArray<int[]> cpusByPolicy = new SparseArray<>();
        cpusByPolicy.put(0, new int[]{0, 1, 2, 3});
        cpusByPolicy.put(4, new int[]{4, 5, 6, 7});
        SparseArray<int[]> freqsByPolicy = new SparseArray<>();
        freqsByPolicy.put(0, new int[]{300000, 1000000, 2000000});
        freqsByPolicy.put(4, new int[]{300000, 1000000, 2500000, 3000000});
        return new CpuScalingPolicies(freqsByPolicy, freqsByPolicy);
    }

    private static class TestPowerStatsInternal extends PowerStatsInternal {
        private final SparseArray<EnergyConsumer> mEnergyConsumers = new SparseArray();
        private final SparseArray<EnergyConsumerResult> mEnergyConsumerResults = new SparseArray();
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
            final EnergyConsumerResult[] results;
            final int length = energyConsumerIds.length;
            if (length == 0) {
                final int size = mEnergyConsumerResults.size();
                results = new EnergyConsumerResult[size];
                for (int i = 0; i < size; i++) {
                    results[i] = mEnergyConsumerResults.valueAt(i);
                }
            } else {
                results = new EnergyConsumerResult[length];
                for (int i = 0; i < length; i++) {
                    results[i] = mEnergyConsumerResults.get(energyConsumerIds[i]);
                }
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
