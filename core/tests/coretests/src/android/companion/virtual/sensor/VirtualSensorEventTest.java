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

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.os.Parcel;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class VirtualSensorEventTest {

    private static final long TIMESTAMP_NANOS = SystemClock.elapsedRealtimeNanos();
    private static final float[] SENSOR_VALUES = new float[] {1.2f, 3.4f, 5.6f};

    @Test
    public void parcelAndUnparcel_matches() {
        final VirtualSensorEvent originalEvent = new VirtualSensorEvent.Builder(SENSOR_VALUES)
                .setTimestampNanos(TIMESTAMP_NANOS)
                .build();
        final Parcel parcel = Parcel.obtain();
        originalEvent.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);
        final VirtualSensorEvent recreatedEvent =
                VirtualSensorEvent.CREATOR.createFromParcel(parcel);
        assertThat(recreatedEvent.getValues()).isEqualTo(originalEvent.getValues());
        assertThat(recreatedEvent.getTimestampNanos()).isEqualTo(originalEvent.getTimestampNanos());
    }

    @Test
    public void sensorEvent_nullValues() {
        assertThrows(
                IllegalArgumentException.class, () -> new VirtualSensorEvent.Builder(null).build());
    }

    @Test
    public void sensorEvent_noValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VirtualSensorEvent.Builder(new float[0]).build());
    }

    @Test
    public void sensorEvent_noTimestamp_usesCurrentTime() {
        final VirtualSensorEvent event = new VirtualSensorEvent.Builder(SENSOR_VALUES).build();
        assertThat(event.getValues()).isEqualTo(SENSOR_VALUES);
        assertThat(TIMESTAMP_NANOS).isLessThan(event.getTimestampNanos());
        assertThat(event.getTimestampNanos()).isLessThan(SystemClock.elapsedRealtimeNanos());
    }

    @Test
    public void sensorEvent_created() {
        final VirtualSensorEvent event = new VirtualSensorEvent.Builder(SENSOR_VALUES)
                .setTimestampNanos(TIMESTAMP_NANOS)
                .build();
        assertThat(event.getTimestampNanos()).isEqualTo(TIMESTAMP_NANOS);
        assertThat(event.getValues()).isEqualTo(SENSOR_VALUES);
    }
}
