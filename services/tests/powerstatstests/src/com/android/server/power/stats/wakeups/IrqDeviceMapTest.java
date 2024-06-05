/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.power.stats.wakeups;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.frameworks.powerstatstests.R;
import com.android.internal.util.CollectionUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class IrqDeviceMapTest {
    private static final String TEST_DEVICE_1 = "test.device.1";
    private static final String TEST_DEVICE_2 = "test.device.2";
    private static final String TEST_DEVICE_3 = "test.device.3";
    private static final String TEST_DEVICE_4 = "test.device.4";

    private static final String TEST_SUBSYSTEM_1 = "test.subsystem.1";
    private static final String TEST_SUBSYSTEM_2 = "test.subsystem.2";
    private static final String TEST_SUBSYSTEM_3 = "test.subsystem.3";
    private static final String TEST_SUBSYSTEM_4 = "test.subsystem.4";
    private static final String TEST_SUBSYSTEM_5 = "test.subsystem.5";

    private static final Context sContext = InstrumentationRegistry.getTargetContext();

    @Test
    public void cachesInstancesPerXml() {
        IrqDeviceMap irqDeviceMap1 = IrqDeviceMap.getInstance(sContext, R.xml.irq_device_map_1);
        IrqDeviceMap irqDeviceMap2 = IrqDeviceMap.getInstance(sContext, R.xml.irq_device_map_1);
        assertThat(irqDeviceMap1).isSameInstanceAs(irqDeviceMap2);

        irqDeviceMap2 = IrqDeviceMap.getInstance(sContext, R.xml.irq_device_map_2);
        assertThat(irqDeviceMap1).isNotSameInstanceAs(irqDeviceMap2);

        irqDeviceMap1 = IrqDeviceMap.getInstance(sContext, R.xml.irq_device_map_2);
        assertThat(irqDeviceMap1).isSameInstanceAs(irqDeviceMap2);
    }

    @Test
    public void simpleXml() {
        IrqDeviceMap deviceMap = IrqDeviceMap.getInstance(sContext, R.xml.irq_device_map_1);

        List<String> subsystems = deviceMap.getSubsystemsForDevice(TEST_DEVICE_1);
        assertThat(subsystems).hasSize(2);
        // No specific order is required.
        assertThat(subsystems).containsExactly(TEST_SUBSYSTEM_2, TEST_SUBSYSTEM_1);

        subsystems = deviceMap.getSubsystemsForDevice(TEST_DEVICE_2);
        assertThat(subsystems).hasSize(2);
        // No specific order is required.
        assertThat(subsystems).containsExactly(TEST_SUBSYSTEM_2, TEST_SUBSYSTEM_3);
    }

    @Test
    public void complexXml() {
        IrqDeviceMap deviceMap = IrqDeviceMap.getInstance(sContext, R.xml.irq_device_map_2);

        List<String> subsystems = deviceMap.getSubsystemsForDevice(TEST_DEVICE_1);
        assertThat(CollectionUtils.isEmpty(subsystems)).isTrue();

        subsystems = deviceMap.getSubsystemsForDevice(TEST_DEVICE_2);
        assertThat(subsystems).hasSize(3);
        // No specific order is required.
        assertThat(subsystems).containsExactly(TEST_SUBSYSTEM_3, TEST_SUBSYSTEM_4,
                TEST_SUBSYSTEM_5);

        subsystems = deviceMap.getSubsystemsForDevice(TEST_DEVICE_3);
        assertThat(CollectionUtils.isEmpty(subsystems)).isTrue();

        subsystems = deviceMap.getSubsystemsForDevice(TEST_DEVICE_4);
        assertThat(subsystems).hasSize(2);
        // No specific order is required.
        assertThat(subsystems).containsExactly(TEST_SUBSYSTEM_4, TEST_SUBSYSTEM_1);
    }
}
