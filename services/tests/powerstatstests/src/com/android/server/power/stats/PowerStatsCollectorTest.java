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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PersistableBundle;
import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.power.PowerStatsInternal;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.PowerStats;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PowerStatsCollectorTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private final MockClock mMockClock = new MockClock();
    private final HandlerThread mHandlerThread = new HandlerThread("test");
    private Handler mHandler;
    private PowerStatsCollector mCollector;
    private PowerStats mCollectedStats;

    @Before
    public void setup() {
        mHandlerThread.start();
        mHandler = mHandlerThread.getThreadHandler();
        mCollector = new PowerStatsCollector(mHandler, 60000, mock(PowerStatsUidResolver.class),
                mMockClock) {
            @Override
            protected PowerStats collectStats() {
                return new PowerStats(
                        new PowerStats.Descriptor(0, 0, null, 0, 0, new PersistableBundle()));
            }
        };
        mCollector.addConsumer(stats -> mCollectedStats = stats);
        mCollector.setEnabled(true);
    }

    @Test
    public void throttlePeriod() {
        mMockClock.uptime = 1000;
        mCollector.schedule();
        waitForIdle();

        assertThat(mCollectedStats).isNotNull();

        mMockClock.uptime += 1000;
        mCollectedStats = null;
        mCollector.schedule();      // Should be throttled
        waitForIdle();

        assertThat(mCollectedStats).isNull();

        // Should be allowed to run
        mMockClock.uptime += 100_000;
        mCollector.schedule();
        waitForIdle();

        assertThat(mCollectedStats).isNotNull();
    }

    private void waitForIdle() {
        ConditionVariable done = new ConditionVariable();
        mHandler.post(done::open);
        done.block();
    }

    @Test
    @DisabledOnRavenwood
    public void consumedEnergyRetriever() throws Exception {
        PowerStatsInternal powerStatsInternal = mock(PowerStatsInternal.class);
        mockEnergyConsumers(powerStatsInternal);

        PowerStatsCollector.ConsumedEnergyRetrieverImpl retriever =
                new PowerStatsCollector.ConsumedEnergyRetrieverImpl(powerStatsInternal, ()-> 3500);
        int[] energyConsumerIds = retriever.getEnergyConsumerIds(EnergyConsumerType.CPU_CLUSTER);
        assertThat(energyConsumerIds).isEqualTo(new int[]{1, 2});
        EnergyConsumerResult[] energy = retriever.getConsumedEnergy(energyConsumerIds);
        assertThat(energy[0].energyUWs).isEqualTo(1000);
        assertThat(energy[1].energyUWs).isEqualTo(2000);
        energy = retriever.getConsumedEnergy(energyConsumerIds);
        assertThat(energy[0].energyUWs).isEqualTo(1500);
        assertThat(energy[1].energyUWs).isEqualTo(2700);
    }

    @SuppressWarnings("unchecked")
    private void mockEnergyConsumers(PowerStatsInternal powerStatsInternal) throws Exception {
        when(powerStatsInternal.getEnergyConsumerInfo())
                .thenReturn(new EnergyConsumer[]{
                        new EnergyConsumer() {{
                            id = 1;
                            type = EnergyConsumerType.CPU_CLUSTER;
                            ordinal = 0;
                            name = "CPU0";
                        }},
                        new EnergyConsumer() {{
                            id = 2;
                            type = EnergyConsumerType.CPU_CLUSTER;
                            ordinal = 1;
                            name = "CPU4";
                        }},
                        new EnergyConsumer() {{
                            id = 3;
                            type = EnergyConsumerType.BLUETOOTH;
                            name = "BT";
                        }},
                });

        CompletableFuture<EnergyConsumerResult[]> future1 = mock(CompletableFuture.class);
        when(future1.get(anyLong(), any(TimeUnit.class)))
                .thenReturn(new EnergyConsumerResult[]{
                        new EnergyConsumerResult() {{
                            id = 1;
                            energyUWs = 1000;
                        }},
                        new EnergyConsumerResult() {{
                            id = 2;
                            energyUWs = 2000;
                        }}
                });

        CompletableFuture<EnergyConsumerResult[]> future2 = mock(CompletableFuture.class);
        when(future2.get(anyLong(), any(TimeUnit.class)))
                .thenReturn(new EnergyConsumerResult[]{
                        new EnergyConsumerResult() {{
                            id = 1;
                            energyUWs = 1500;
                        }},
                        new EnergyConsumerResult() {{
                            id = 2;
                            energyUWs = 2700;
                        }}
                });

        when(powerStatsInternal.getEnergyConsumedAsync(eq(new int[]{1, 2})))
                .thenReturn(future1)
                .thenReturn(future2);
    }

    private EnergyConsumerResult mockEnergyConsumerResult(long energyUWs) {
        EnergyConsumerResult ecr = new EnergyConsumerResult();
        ecr.energyUWs = energyUWs;
        return ecr;
    }

}
