/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.os.Parcel;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test of {@link RangingMeasurement}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class RangingMeasurementTest {

    @Test
    public void testBuilder() {
        int status = RangingMeasurement.RANGING_STATUS_SUCCESS;
        UwbAddress address = UwbTestUtils.getUwbAddress(false);
        long time = SystemClock.elapsedRealtimeNanos();
        AngleOfArrivalMeasurement angleMeasurement = UwbTestUtils.getAngleOfArrivalMeasurement();
        DistanceMeasurement distanceMeasurement = UwbTestUtils.getDistanceMeasurement();

        RangingMeasurement.Builder builder = new RangingMeasurement.Builder();

        builder.setStatus(status);
        tryBuild(builder, false);

        builder.setElapsedRealtimeNanos(time);
        tryBuild(builder, false);

        builder.setAngleOfArrivalMeasurement(angleMeasurement);
        tryBuild(builder, false);

        builder.setDistanceMeasurement(distanceMeasurement);
        tryBuild(builder, false);

        builder.setRemoteDeviceAddress(address);
        RangingMeasurement measurement = tryBuild(builder, true);

        assertEquals(status, measurement.getStatus());
        assertEquals(address, measurement.getRemoteDeviceAddress());
        assertEquals(time, measurement.getElapsedRealtimeNanos());
        assertEquals(angleMeasurement, measurement.getAngleOfArrivalMeasurement());
        assertEquals(distanceMeasurement, measurement.getDistanceMeasurement());
    }

    private RangingMeasurement tryBuild(RangingMeasurement.Builder builder,
            boolean expectSuccess) {
        RangingMeasurement measurement = null;
        try {
            measurement = builder.build();
            if (!expectSuccess) {
                fail("Expected RangingMeasurement.Builder.build() to fail");
            }
        } catch (IllegalStateException e) {
            if (expectSuccess) {
                fail("Expected DistanceMeasurement.Builder.build() to succeed");
            }
        }
        return measurement;
    }

    @Test
    public void testParcel() {
        Parcel parcel = Parcel.obtain();
        RangingMeasurement measurement = UwbTestUtils.getRangingMeasurement();
        measurement.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        RangingMeasurement fromParcel = RangingMeasurement.CREATOR.createFromParcel(parcel);
        assertEquals(measurement, fromParcel);
    }
}
