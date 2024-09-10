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
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;
import android.util.Xml;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import com.google.common.truth.StringSubject;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
@SmallTest
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
        SparseArray<String> stateLabels = new SparseArray<>();
        stateLabels.put(0x0F, "idle");
        extras.putString(PowerStats.Descriptor.EXTRA_DEVICE_STATS_FORMAT, "device:0[3]");
        extras.putString(PowerStats.Descriptor.EXTRA_STATE_STATS_FORMAT, "state:0");
        extras.putString(PowerStats.Descriptor.EXTRA_UID_STATS_FORMAT, "a:0 b:1");
        mDescriptor = new PowerStats.Descriptor(BatteryConsumer.POWER_COMPONENT_CPU, 3, stateLabels,
                1, 2, extras);
        mRegistry.register(mDescriptor);
    }

    @Test
    public void parceling_compatibleParcel() {
        PowerStats stats = new PowerStats(mDescriptor);
        stats.durationMs = 1234;
        stats.stats[0] = 10;
        stats.stats[1] = 20;
        stats.stats[2] = 30;
        stats.stateStats.put(0x0F, new long[]{16});
        stats.stateStats.put(0xF0, new long[]{17});
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
        assertThat(newDescriptor.stateStatsArrayLength).isEqualTo(1);
        assertThat(newDescriptor.uidStatsArrayLength).isEqualTo(2);
        assertThat(newDescriptor.extras.getBoolean("hasPowerMonitor")).isTrue();

        mRegistry.register(newDescriptor);

        PowerStats newStats = PowerStats.readFromParcel(newParcel, mRegistry);
        assertThat(newStats.durationMs).isEqualTo(1234);
        assertThat(newStats.stats).isEqualTo(new long[]{10, 20, 30});
        assertThat(newStats.stateStats.size()).isEqualTo(2);
        assertThat(newStats.stateStats.get(0x0F)).isEqualTo(new long[]{16});
        assertThat(newStats.descriptor.getStateLabel(0x0F)).isEqualTo("idle");
        assertThat(newStats.stateStats.get(0xF0)).isEqualTo(new long[]{17});
        assertThat(newStats.descriptor.getStateLabel(0xF0)).isEqualTo("cpu-f0");
        assertThat(newStats.uidStats.size()).isEqualTo(2);
        assertThat(newStats.uidStats.get(42)).isEqualTo(new long[]{40, 50});
        assertThat(newStats.uidStats.get(99)).isEqualTo(new long[]{60, 70});

        String end = newParcel.readString();
        assertThat(end).isEqualTo("END");
    }

    @Test
    public void xmlFormat() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TypedXmlSerializer serializer = Xml.newBinarySerializer();
        serializer.setOutput(out, StandardCharsets.UTF_8.name());
        mDescriptor.writeXml(serializer);
        serializer.flush();

        byte[] bytes = out.toByteArray();

        TypedXmlPullParser parser = Xml.newBinaryPullParser();
        parser.setInput(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.name());
        PowerStats.Descriptor actual = PowerStats.Descriptor.createFromXml(parser);

        assertThat(actual.powerComponentId).isEqualTo(BatteryConsumer.POWER_COMPONENT_CPU);
        assertThat(actual.name).isEqualTo("cpu");
        assertThat(actual.statsArrayLength).isEqualTo(3);
        assertThat(actual.stateStatsArrayLength).isEqualTo(1);
        assertThat(actual.getStateLabel(0x0F)).isEqualTo("idle");
        assertThat(actual.getStateLabel(0xF0)).isEqualTo("cpu-f0");
        assertThat(actual.uidStatsArrayLength).isEqualTo(2);
        assertThat(actual.extras.getBoolean("hasPowerMonitor")).isEqualTo(true);
    }

    @Test
    public void parceling_unrecognizedPowerComponent() {
        PowerStats stats = new PowerStats(
                new PowerStats.Descriptor(777, "luck", 3, null, 1, 2, new PersistableBundle()));
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

    @Test
    public void parceling_corruptParcel() {
        PowerStats stats = new PowerStats(mDescriptor);
        Parcel parcel = Parcel.obtain();
        stats.writeToParcel(parcel);

        Parcel newParcel = marshallAndUnmarshall(parcel);
        newParcel.writeInt(-42);        // Negative section length
        newParcel.setDataPosition(0);

        PowerStats newStats = PowerStats.readFromParcel(newParcel, mRegistry);
        assertThat(newStats).isNull();
    }

    private static Parcel marshallAndUnmarshall(Parcel parcel) {
        byte[] bytes = parcel.marshall();
        parcel.recycle();

        Parcel newParcel = Parcel.obtain();
        newParcel.unmarshall(bytes, 0, bytes.length);
        newParcel.setDataPosition(0);
        return newParcel;
    }

    @Test
    public void formatForBatteryHistory() {
        PowerStats stats = new PowerStats(mDescriptor);
        stats.durationMs = 1234;
        stats.stats[0] = 10;
        stats.stats[1] = 20;
        stats.stats[2] = 30;
        stats.stateStats.put(0x0F, new long[]{16});
        stats.stateStats.put(0xF0, new long[]{17});
        stats.uidStats.put(42, new long[]{40, 50});
        stats.uidStats.put(99, new long[]{60, 70});

        assertThat(stats.formatForBatteryHistory(" #"))
                .isEqualTo("duration=1234 cpu="
                        + "device: [10, 20, 30]"
                        + " (idle) state: 16"
                        + " (cpu-f0) state: 17"
                        + " #42: a: 40 b: 50"
                        + " #99: a: 60 b: 70");
    }

    @Test
    public void dump() {
        PowerStats stats = new PowerStats(mDescriptor);
        stats.durationMs = 1234;
        stats.stats[0] = 10;
        stats.stats[1] = 20;
        stats.stats[2] = 30;
        stats.stateStats.put(0x0F, new long[]{16});
        stats.stateStats.put(0xF0, new long[]{17});
        stats.uidStats.put(42, new long[]{40, 50});
        stats.uidStats.put(99, new long[]{60, 70});

        StringWriter sw = new StringWriter();
        IndentingPrintWriter pw = new IndentingPrintWriter(sw);
        stats.dump(pw);
        pw.flush();
        String dump = sw.toString();

        assertThat(dump).contains("duration=1234");
        assertThat(dump).contains("device: [10, 20, 30]");
        assertThat(dump).contains("(idle) state: 16");
        assertThat(dump).contains("(cpu-f0) state: 17");
        assertThat(dump).contains("UID 42: a: 40 b: 50");
        assertThat(dump).contains("UID 99: a: 60 b: 70");
    }

    @Test
    public void formatter() {
        assertThatFormatted(new long[]{12, 34, 56}, "a:0 b:1[2]")
                .isEqualTo("a: 12 b: [34, 56]");
        assertThatFormatted(new long[]{12, 0, 0}, "a:0? b:1[2]?")
                .isEqualTo("a: 12");
        assertThatFormatted(new long[]{0, 34, 56}, "a:0? b:1[2]?")
                .isEqualTo("b: [34, 56]");
        assertThatFormatted(new long[]{3141592, 2000000, 1414213}, "pi:0p sqrt:1[2]p")
                .isEqualTo("pi: 3.14 sqrt: [2.00, 1.41]");
    }

    private static StringSubject assertThatFormatted(long[] stats, String format) {
        return assertThat(new PowerStats.PowerStatsFormatter(format)
                .format(stats));
    }
}
