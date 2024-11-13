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

package com.android.server.power.stats.processor;

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryConsumer;
import android.os.PersistableBundle;
import android.util.SparseArray;
import android.util.Xml;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.PowerStats;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.ParseException;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class AggregatedPowerStatsTest {
    private static final int TEST_POWER_COMPONENT = 1077;
    private static final int CUSTOM_POWER_COMPONENT = 1042;
    private static final int APP_1 = 27;
    private static final int APP_2 = 42;
    private static final int COMPONENT_STATE_0 = 0;
    private static final int COMPONENT_STATE_1 = 1;
    private static final int COMPONENT_STATE_2 = 2;

    private AggregatedPowerStatsConfig
            mAggregatedPowerStatsConfig;
    private PowerStats.Descriptor mPowerComponentDescriptor;

    @Before
    public void setup() throws ParseException {
        mAggregatedPowerStatsConfig = new AggregatedPowerStatsConfig();
        mAggregatedPowerStatsConfig.trackPowerComponent(TEST_POWER_COMPONENT)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE);

        mAggregatedPowerStatsConfig.trackCustomPowerComponents(
                        () -> new PowerStatsProcessor() {
                            @Override
                            void finish(
                                    PowerComponentAggregatedPowerStats stats,
                                    long timestampMs) {
                            }
                        })
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE);
        SparseArray<String> stateLabels = new SparseArray<>();
        stateLabels.put(COMPONENT_STATE_1, "one");
        mPowerComponentDescriptor = new PowerStats.Descriptor(TEST_POWER_COMPONENT, "fan", 2,
                stateLabels, 1, 3, PersistableBundle.forPair("speed", "fast"));
    }

    @Test
    public void aggregation() {
        AggregatedPowerStats stats = prepareAggregatePowerStats();

        verifyAggregatedPowerStats(stats);
    }

    @Test
    public void xmlPersistence() throws Exception {
        AggregatedPowerStats stats = prepareAggregatePowerStats();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TypedXmlSerializer serializer = Xml.newFastSerializer();
        serializer.setOutput(baos, "UTF-8");
        stats.writeXml(serializer);
        serializer.flush();

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new ByteArrayInputStream(baos.toByteArray()), "UTF-8");
        AggregatedPowerStats actualStats =
                AggregatedPowerStats.createFromXml(parser, mAggregatedPowerStatsConfig);

        verifyAggregatedPowerStats(actualStats);
    }

    private AggregatedPowerStats prepareAggregatePowerStats() {
        AggregatedPowerStats stats = new AggregatedPowerStats(mAggregatedPowerStatsConfig);
        stats.start(0);

        PowerStats ps = new PowerStats(mPowerComponentDescriptor);
        stats.addPowerStats(ps, 0);

        stats.addClockUpdate(1000, 456);
        stats.setDuration(789);

        stats.setDeviceState(AggregatedPowerStatsConfig.STATE_SCREEN,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON, 2000);
        stats.setUidState(APP_1, AggregatedPowerStatsConfig.STATE_PROCESS_STATE,
                BatteryConsumer.PROCESS_STATE_CACHED, 2000);
        stats.setUidState(APP_2, AggregatedPowerStatsConfig.STATE_PROCESS_STATE,
                BatteryConsumer.PROCESS_STATE_FOREGROUND, 2000);

        ps.stats[0] = 100;
        ps.stats[1] = 987;

        ps.stateStats.put(COMPONENT_STATE_0, new long[]{1111});
        ps.stateStats.put(COMPONENT_STATE_1, new long[]{5000});

        ps.uidStats.put(APP_1, new long[]{389, 0, 739});
        ps.uidStats.put(APP_2, new long[]{278, 314, 628});

        stats.addPowerStats(ps, 3000);

        stats.setDeviceState(AggregatedPowerStatsConfig.STATE_SCREEN,
                AggregatedPowerStatsConfig.SCREEN_STATE_OTHER, 4000);
        stats.setUidState(APP_2, AggregatedPowerStatsConfig.STATE_PROCESS_STATE,
                BatteryConsumer.PROCESS_STATE_BACKGROUND, 4000);

        ps.stats[0] = 444;
        ps.stats[1] = 0;

        ps.stateStats.clear();
        ps.stateStats.put(COMPONENT_STATE_1, new long[]{1000});
        ps.stateStats.put(COMPONENT_STATE_2, new long[]{9000});

        ps.uidStats.put(APP_1, new long[]{0, 0, 400});
        ps.uidStats.put(APP_2, new long[]{100, 200, 300});

        stats.addPowerStats(ps, 5000);

        PowerStats custom = new PowerStats(
                new PowerStats.Descriptor(CUSTOM_POWER_COMPONENT, "cu570m", 1, null, 0, 2,
                        new PersistableBundle()));
        custom.stats = new long[]{123};
        custom.uidStats.put(APP_1, new long[]{500, 600});
        stats.addPowerStats(custom, 6000);
        return stats;
    }

    private void verifyAggregatedPowerStats(
            AggregatedPowerStats stats) {
        PowerStats.Descriptor descriptor = stats.getPowerComponentStats(TEST_POWER_COMPONENT)
                .getPowerStatsDescriptor();
        assertThat(descriptor.powerComponentId).isEqualTo(TEST_POWER_COMPONENT);
        assertThat(descriptor.name).isEqualTo("fan");
        assertThat(descriptor.statsArrayLength).isEqualTo(2);
        assertThat(descriptor.uidStatsArrayLength).isEqualTo(3);
        assertThat(descriptor.extras.getString("speed")).isEqualTo("fast");

        assertThat(getDeviceStats(stats, TEST_POWER_COMPONENT,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON))
                .isEqualTo(new long[]{322, 987});

        assertThat(getDeviceStats(stats, TEST_POWER_COMPONENT,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_OTHER))
                .isEqualTo(new long[]{222, 0});

        assertThat(getStateStats(stats, COMPONENT_STATE_0,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON))
                .isEqualTo(new long[]{1111});

        assertThat(getStateStats(stats, COMPONENT_STATE_1,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON))
                .isEqualTo(new long[]{5500});

        assertThat(getStateStats(stats, COMPONENT_STATE_1,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_OTHER))
                .isEqualTo(new long[]{500});

        assertThat(getStateStats(stats, COMPONENT_STATE_2,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON))
                .isEqualTo(new long[]{4500});

        assertThat(getStateStats(stats, COMPONENT_STATE_2,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_OTHER))
                .isEqualTo(new long[]{4500});

        assertThat(getUidDeviceStats(stats,
                TEST_POWER_COMPONENT, APP_1,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON,
                BatteryConsumer.PROCESS_STATE_UNSPECIFIED))
                .isEqualTo(new long[]{259, 0, 492});

        assertThat(getUidDeviceStats(stats,
                TEST_POWER_COMPONENT, APP_1,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON,
                BatteryConsumer.PROCESS_STATE_CACHED))
                .isEqualTo(new long[]{129, 0, 446});

        assertThat(getUidDeviceStats(stats,
                TEST_POWER_COMPONENT, APP_1,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_OTHER,
                BatteryConsumer.PROCESS_STATE_CACHED))
                .isEqualTo(new long[]{0, 0, 200});

        assertThat(getUidDeviceStats(stats,
                TEST_POWER_COMPONENT, APP_2,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON,
                BatteryConsumer.PROCESS_STATE_UNSPECIFIED))
                .isEqualTo(new long[]{185, 209, 418});

        assertThat(getUidDeviceStats(stats,
                TEST_POWER_COMPONENT, APP_2,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON,
                BatteryConsumer.PROCESS_STATE_FOREGROUND))
                .isEqualTo(new long[]{142, 204, 359});

        assertThat(getUidDeviceStats(stats,
                TEST_POWER_COMPONENT, APP_2,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_OTHER,
                BatteryConsumer.PROCESS_STATE_BACKGROUND))
                .isEqualTo(new long[]{50, 100, 150});

        descriptor = stats.getPowerComponentStats(CUSTOM_POWER_COMPONENT)
                .getPowerStatsDescriptor();
        assertThat(descriptor.powerComponentId).isEqualTo(CUSTOM_POWER_COMPONENT);
        assertThat(descriptor.statsArrayLength).isEqualTo(1);
        assertThat(descriptor.uidStatsArrayLength).isEqualTo(2);

        assertThat(getDeviceStats(stats, CUSTOM_POWER_COMPONENT,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON))
                .isEqualTo(new long[]{61});
        assertThat(getDeviceStats(stats, CUSTOM_POWER_COMPONENT,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_OTHER))
                .isEqualTo(new long[]{61});
        assertThat(getUidDeviceStats(stats,
                CUSTOM_POWER_COMPONENT, APP_1,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_ON,
                BatteryConsumer.PROCESS_STATE_CACHED))
                .isEqualTo(new long[]{250, 300});
        assertThat(getUidDeviceStats(stats,
                CUSTOM_POWER_COMPONENT, APP_1,
                AggregatedPowerStatsConfig.POWER_STATE_BATTERY,
                AggregatedPowerStatsConfig.SCREEN_STATE_OTHER,
                BatteryConsumer.PROCESS_STATE_CACHED))
                .isEqualTo(new long[]{250, 300});
    }

    private static long[] getDeviceStats(
            AggregatedPowerStats stats, int powerComponentId,
            int... states) {
        PowerComponentAggregatedPowerStats powerComponentStats =
                stats.getPowerComponentStats(powerComponentId);
        long[] out = new long[powerComponentStats.getPowerStatsDescriptor().statsArrayLength];
        powerComponentStats.getDeviceStats(out, states);
        return out;
    }

    private static long[] getStateStats(
            AggregatedPowerStats stats, int key, int... states) {
        PowerComponentAggregatedPowerStats powerComponentStats =
                stats.getPowerComponentStats(TEST_POWER_COMPONENT);
        long[] out = new long[powerComponentStats.getPowerStatsDescriptor().stateStatsArrayLength];
        powerComponentStats.getStateStats(out, key, states);
        return out;
    }

    private static long[] getUidDeviceStats(
            AggregatedPowerStats stats, int powerComponentId,
            int uid, int... states) {
        PowerComponentAggregatedPowerStats powerComponentStats =
                stats.getPowerComponentStats(powerComponentId);
        long[] out = new long[powerComponentStats.getPowerStatsDescriptor().uidStatsArrayLength];
        powerComponentStats.getUidStats(out, uid, states);
        return out;
    }
}
