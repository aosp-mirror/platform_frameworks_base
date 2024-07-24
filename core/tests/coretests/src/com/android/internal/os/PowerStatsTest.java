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

package com.android.internal.os;

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryConsumer;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@IgnoreUnderRavenwood(reason = "Needs kernel support")
public class PowerStatsTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private PowerStats.DescriptorRegistry mRegistry;
    private PowerStats.Descriptor mDescriptor;

    @Before
    public void setup() {
        mRegistry = new PowerStats.DescriptorRegistry();
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("hasPowerMonitor", true);
        mDescriptor = new PowerStats.Descriptor(BatteryConsumer.POWER_COMPONENT_CPU, 3, 2, extras);
        mRegistry.register(mDescriptor);
    }

    @Test
    public void parceling_compatibleParcel() {
        PowerStats stats = new PowerStats(mDescriptor);
        stats.durationMs = 1234;
        stats.stats[0] = 10;
        stats.stats[1] = 20;
        stats.stats[2] = 30;
        stats.uidStats.put(42, new long[]{40, 50});
        stats.uidStats.put(99, new long[]{60, 70});

        Parcel parcel = Parcel.obtain();
        mDescriptor.writeSummaryToParcel(parcel);
        stats.writeToParcel(parcel);
        parcel.writeString("END");

        Parcel newParcel = marshallAndUnmarshall(parcel);

        PowerStats.Descriptor newDescriptor =
                PowerStats.Descriptor.readSummaryFromParcel(newParcel);
        assertThat(newDescriptor.powerComponentId).isEqualTo(BatteryConsumer.POWER_COMPONENT_CPU);
        assertThat(newDescriptor.name).isEqualTo("cpu");
        assertThat(newDescriptor.statsArrayLength).isEqualTo(3);
        assertThat(newDescriptor.uidStatsArrayLength).isEqualTo(2);
        assertThat(newDescriptor.extras.getBoolean("hasPowerMonitor")).isTrue();

        mRegistry.register(newDescriptor);

        PowerStats newStats = PowerStats.readFromParcel(newParcel, mRegistry);
        assertThat(newStats.durationMs).isEqualTo(1234);
        assertThat(newStats.stats).isEqualTo(new long[]{10, 20, 30});
        assertThat(newStats.uidStats.size()).isEqualTo(2);
        assertThat(newStats.uidStats.get(42)).isEqualTo(new long[]{40, 50});
        assertThat(newStats.uidStats.get(99)).isEqualTo(new long[]{60, 70});

        String end = newParcel.readString();
        assertThat(end).isEqualTo("END");
    }

    @Test
    public void parceling_unrecognizedPowerComponent() {
        PowerStats stats = new PowerStats(
                new PowerStats.Descriptor(777, "luck", 3, 2, new PersistableBundle()));
        stats.durationMs = 1234;

        Parcel parcel = Parcel.obtain();
        stats.writeToParcel(parcel);
        parcel.writeString("END");

        Parcel newParcel = marshallAndUnmarshall(parcel);

        PowerStats newStats = PowerStats.readFromParcel(newParcel, mRegistry);
        assertThat(newStats).isNull();

        String end = newParcel.readString();
        assertThat(end).isEqualTo("END");
    }

    @Test
    public void parceling_wrongArrayLength() {
        PowerStats stats = new PowerStats(mDescriptor);
        stats.stats = new long[5];      // Is expected to be 3

        Parcel parcel = Parcel.obtain();
        stats.writeToParcel(parcel);
        parcel.writeString("END");

        Parcel newParcel = marshallAndUnmarshall(parcel);

        PowerStats newStats = PowerStats.readFromParcel(newParcel, mRegistry);
        assertThat(newStats).isNull();

        String end = newParcel.readString();
        assertThat(end).isEqualTo("END");
    }

    private static Parcel marshallAndUnmarshall(Parcel parcel) {
        byte[] bytes = parcel.marshall();
        parcel.recycle();

        Parcel newParcel = Parcel.obtain();
        newParcel.unmarshall(bytes, 0, bytes.length);
        newParcel.setDataPosition(0);
        return newParcel;
    }
}
