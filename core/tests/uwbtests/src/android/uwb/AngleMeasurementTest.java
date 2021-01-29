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
 * Test of {@link AngleMeasurement}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AngleMeasurementTest {
    private static final double EPSILON = 0.00000000001;

    @Test
    public void testBuilder() {
        double radians = 0.1234;
        double errorRadians = 0.5678;
        double confidence = 0.5;

        AngleMeasurement.Builder builder = new AngleMeasurement.Builder();
        tryBuild(builder, false);

        builder.setRadians(radians);
        tryBuild(builder, false);

        builder.setErrorRadians(errorRadians);
        tryBuild(builder, false);

        builder.setConfidenceLevel(confidence);
        AngleMeasurement measurement = tryBuild(builder, true);

        assertEquals(measurement.getRadians(), radians, 0);
        assertEquals(measurement.getErrorRadians(), errorRadians, 0);
        assertEquals(measurement.getConfidenceLevel(), confidence, 0);
    }

    private AngleMeasurement tryBuild(AngleMeasurement.Builder builder, boolean expectSuccess) {
        AngleMeasurement measurement = null;
        try {
            measurement = builder.build();
            if (!expectSuccess) {
                fail("Expected AngleMeasurement.Builder.build() to fail, but it succeeded");
            }
        } catch (IllegalStateException e) {
            if (expectSuccess) {
                fail("Expected AngleMeasurement.Builder.build() to succeed, but it failed");
            }
        }
        return measurement;
    }

    @Test
    public void testParcel() {
        Parcel parcel = Parcel.obtain();
        AngleMeasurement measurement = UwbTestUtils.getAngleMeasurement();
        measurement.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        AngleMeasurement fromParcel = AngleMeasurement.CREATOR.createFromParcel(parcel);
        assertEquals(measurement, fromParcel);
    }
}
