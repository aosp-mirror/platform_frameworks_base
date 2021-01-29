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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test of {@link AngleOfArrivalMeasurement}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AngleOfArrivalMeasurementTest {

    @Test
    public void testBuilder() {
        AngleMeasurement azimuth = UwbTestUtils.getAngleMeasurement();
        AngleMeasurement altitude = UwbTestUtils.getAngleMeasurement();

        AngleOfArrivalMeasurement.Builder builder = new AngleOfArrivalMeasurement.Builder();
        tryBuild(builder, false);

        builder.setAltitude(altitude);
        tryBuild(builder, false);

        builder.setAzimuth(azimuth);
        AngleOfArrivalMeasurement measurement = tryBuild(builder, true);

        assertEquals(azimuth, measurement.getAzimuth());
        assertEquals(altitude, measurement.getAltitude());
    }

    private AngleMeasurement getAngleMeasurement(double radian, double error, double confidence) {
        return new AngleMeasurement.Builder()
                .setRadians(radian)
                .setErrorRadians(error)
                .setConfidenceLevel(confidence)
                .build();
    }

    private AngleOfArrivalMeasurement tryBuild(AngleOfArrivalMeasurement.Builder builder,
            boolean expectSuccess) {
        AngleOfArrivalMeasurement measurement = null;
        try {
            measurement = builder.build();
            if (!expectSuccess) {
                fail("Expected AngleOfArrivalMeasurement.Builder.build() to fail");
            }
        } catch (IllegalStateException e) {
            if (expectSuccess) {
                fail("Expected AngleOfArrivalMeasurement.Builder.build() to succeed");
            }
        }
        return measurement;
    }

    @Test
    public void testParcel() {
        Parcel parcel = Parcel.obtain();
        AngleOfArrivalMeasurement measurement = UwbTestUtils.getAngleOfArrivalMeasurement();
        measurement.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        AngleOfArrivalMeasurement fromParcel =
                AngleOfArrivalMeasurement.CREATOR.createFromParcel(parcel);
        assertEquals(measurement, fromParcel);
    }
}
