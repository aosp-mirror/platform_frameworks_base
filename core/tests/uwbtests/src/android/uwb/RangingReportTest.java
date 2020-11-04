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

import java.util.List;

/**
 * Test of {@link RangingReport}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class RangingReportTest {

    @Test
    public void testBuilder() {
        List<RangingMeasurement> measurements = UwbTestUtils.getRangingMeasurements(5);

        RangingReport.Builder builder = new RangingReport.Builder();
        builder.addMeasurements(measurements);
        RangingReport report = tryBuild(builder, true);
        verifyMeasurementsEqual(measurements, report.getMeasurements());


        builder = new RangingReport.Builder();
        for (RangingMeasurement measurement : measurements) {
            builder.addMeasurement(measurement);
        }
        report = tryBuild(builder, true);
        verifyMeasurementsEqual(measurements, report.getMeasurements());
    }

    private void verifyMeasurementsEqual(List<RangingMeasurement> expected,
            List<RangingMeasurement> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i));
        }
    }

    private RangingReport tryBuild(RangingReport.Builder builder,
            boolean expectSuccess) {
        RangingReport report = null;
        try {
            report = builder.build();
            if (!expectSuccess) {
                fail("Expected RangingReport.Builder.build() to fail");
            }
        } catch (IllegalStateException e) {
            if (expectSuccess) {
                fail("Expected RangingReport.Builder.build() to succeed");
            }
        }
        return report;
    }

    @Test
    public void testParcel() {
        Parcel parcel = Parcel.obtain();
        RangingReport report = UwbTestUtils.getRangingReports(5);
        report.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        RangingReport fromParcel = RangingReport.CREATOR.createFromParcel(parcel);
        assertEquals(report, fromParcel);
    }
}
