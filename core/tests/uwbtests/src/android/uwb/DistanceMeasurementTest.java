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
 * Test of {@link DistanceMeasurement}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DistanceMeasurementTest {
    private static final double EPSILON = 0.00000000001;

    @Test
    public void testBuilder() {
        double meters = 0.12;
        double error = 0.54;
        double confidence = 0.99;

        DistanceMeasurement.Builder builder = new DistanceMeasurement.Builder();
        tryBuild(builder, false);

        builder.setMeters(meters);
        tryBuild(builder, false);

        builder.setErrorMeters(error);
        tryBuild(builder, false);

        builder.setConfidenceLevel(confidence);
        DistanceMeasurement measurement = tryBuild(builder, true);

        assertEquals(meters, measurement.getMeters(), 0);
        assertEquals(error, measurement.getErrorMeters(), 0);
        assertEquals(confidence, measurement.getConfidenceLevel(), 0);
    }

    private DistanceMeasurement tryBuild(DistanceMeasurement.Builder builder,
            boolean expectSuccess) {
        DistanceMeasurement measurement = null;
        try {
            measurement = builder.build();
            if (!expectSuccess) {
                fail("Expected DistanceMeasurement.Builder.build() to fail");
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
        DistanceMeasurement measurement = UwbTestUtils.getDistanceMeasurement();
        measurement.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        DistanceMeasurement fromParcel =
                DistanceMeasurement.CREATOR.createFromParcel(parcel);
        assertEquals(measurement, fromParcel);
    }
}
