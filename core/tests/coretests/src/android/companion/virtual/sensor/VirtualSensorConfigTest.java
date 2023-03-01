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

package android.companion.virtual.sensor;

import static android.hardware.Sensor.TYPE_ACCELEROMETER;
import static android.hardware.SensorDirectChannel.RATE_STOP;
import static android.hardware.SensorDirectChannel.RATE_VERY_FAST;
import static android.hardware.SensorDirectChannel.TYPE_HARDWARE_BUFFER;
import static android.hardware.SensorDirectChannel.TYPE_MEMORY_FILE;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class VirtualSensorConfigTest {

    private static final String SENSOR_NAME = "VirtualSensorName";
    private static final String SENSOR_VENDOR = "VirtualSensorVendor";

    @Test
    public void parcelAndUnparcel_matches() {
        final VirtualSensorConfig originalConfig =
                new VirtualSensorConfig.Builder(TYPE_ACCELEROMETER, SENSOR_NAME)
                        .setVendor(SENSOR_VENDOR)
                        .setHighestDirectReportRateLevel(RATE_VERY_FAST)
                        .setDirectChannelTypesSupported(TYPE_MEMORY_FILE)
                        .build();
        final Parcel parcel = Parcel.obtain();
        originalConfig.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);
        final VirtualSensorConfig recreatedConfig =
                VirtualSensorConfig.CREATOR.createFromParcel(parcel);
        assertThat(recreatedConfig.getType()).isEqualTo(originalConfig.getType());
        assertThat(recreatedConfig.getName()).isEqualTo(originalConfig.getName());
        assertThat(recreatedConfig.getVendor()).isEqualTo(originalConfig.getVendor());
        assertThat(recreatedConfig.getHighestDirectReportRateLevel()).isEqualTo(RATE_VERY_FAST);
        assertThat(recreatedConfig.getDirectChannelTypesSupported()).isEqualTo(TYPE_MEMORY_FILE);
        // From hardware/libhardware/include/hardware/sensors-base.h:
        //   0x400 is SENSOR_FLAG_DIRECT_CHANNEL_ASHMEM (i.e. TYPE_MEMORY_FILE)
        //   0x800 is SENSOR_FLAG_DIRECT_CHANNEL_GRALLOC (i.e. TYPE_HARDWARE_BUFFER)
        //   7 is SENSOR_FLAG_SHIFT_DIRECT_REPORT
        assertThat(recreatedConfig.getFlags()).isEqualTo(0x400 | RATE_VERY_FAST << 7);
    }

    @Test
    public void hardwareBufferDirectChannelTypeSupported_throwsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VirtualSensorConfig.Builder(TYPE_ACCELEROMETER, SENSOR_NAME)
                        .setDirectChannelTypesSupported(TYPE_HARDWARE_BUFFER | TYPE_MEMORY_FILE));
    }

    @Test
    public void directChannelTypeSupported_missingHighestReportRateLevel_throwsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VirtualSensorConfig.Builder(TYPE_ACCELEROMETER, SENSOR_NAME)
                        .setDirectChannelTypesSupported(TYPE_MEMORY_FILE)
                        .build());
    }

    @Test
    public void directChannelTypeSupported_missingDirectChannelTypeSupported_throwsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VirtualSensorConfig.Builder(TYPE_ACCELEROMETER, SENSOR_NAME)
                        .setHighestDirectReportRateLevel(RATE_VERY_FAST)
                        .build());
    }

    @Test
    public void sensorConfig_onlyRequiredFields() {
        final VirtualSensorConfig config =
                new VirtualSensorConfig.Builder(TYPE_ACCELEROMETER, SENSOR_NAME).build();
        assertThat(config.getVendor()).isNull();
        assertThat(config.getHighestDirectReportRateLevel()).isEqualTo(RATE_STOP);
        assertThat(config.getDirectChannelTypesSupported()).isEqualTo(0);
        assertThat(config.getFlags()).isEqualTo(0);
    }
}
