/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.location;

import android.os.Parcel;

import junit.framework.TestCase;

/** Unit tests for {@link GnssSingleSatCorrection}. */
public class GnssSingleSatCorrectionsTest extends TestCase {
    public void testDescribeContents() {
        GnssSingleSatCorrection singleSatCorrection = new GnssSingleSatCorrection.Builder().build();
        singleSatCorrection.describeContents();
    }

    public void testWriteToParcel() {
        GnssSingleSatCorrection.Builder singleSatCorrection = new GnssSingleSatCorrection.Builder();
        setTestValues(singleSatCorrection);
        Parcel parcel = Parcel.obtain();
        singleSatCorrection.build().writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        GnssSingleSatCorrection newSingleSatCorrection =
                GnssSingleSatCorrection.CREATOR.createFromParcel(parcel);
        verifyTestValues(newSingleSatCorrection);
        parcel.recycle();
    }

    public static void verifyTestValues(GnssSingleSatCorrection singleSatCorrection) {
        assertEquals(15, singleSatCorrection.getSingleSatelliteCorrectionFlags());
        assertEquals(GnssStatus.CONSTELLATION_GALILEO, singleSatCorrection.getConstellationType());
        assertEquals(12, singleSatCorrection.getSatelliteId());
        assertEquals(1575420000f, singleSatCorrection.getCarrierFrequencyHz());
        assertEquals(0.1f, singleSatCorrection.getProbabilityLineOfSight());
        assertEquals(10.0f, singleSatCorrection.getExcessPathLengthMeters());
        assertEquals(5.0f, singleSatCorrection.getExcessPathLengthUncertaintyMeters());
        GnssReflectingPlane reflectingPlane = singleSatCorrection.getReflectingPlane();
        GnssReflectingPlaneTest.verifyTestValues(reflectingPlane);
    }

    private static void setTestValues(GnssSingleSatCorrection.Builder singleSatCorrection) {
        GnssSingleSatCorrection singleSatCor = generateTestSingleSatCorrection();
        singleSatCorrection
                .setSingleSatelliteCorrectionFlags(singleSatCor.getSingleSatelliteCorrectionFlags())
                .setConstellationType(singleSatCor.getConstellationType())
                .setSatelliteId(singleSatCor.getSatelliteId())
                .setCarrierFrequencyHz(singleSatCor.getCarrierFrequencyHz())
                .setProbabilityLineOfSight(singleSatCor.getProbabilityLineOfSight())
                .setExcessPathLengthMeters(singleSatCor.getExcessPathLengthMeters())
                .setExcessPathLengthUncertaintyMeters(
                        singleSatCor.getExcessPathLengthUncertaintyMeters())
                .setReflectingPlane(singleSatCor.getReflectingPlane());
    }

    public static GnssSingleSatCorrection generateTestSingleSatCorrection() {
        GnssSingleSatCorrection.Builder singleSatCorrection =
                new GnssSingleSatCorrection.Builder()
                        .setSingleSatelliteCorrectionFlags(15)
                        .setConstellationType(GnssStatus.CONSTELLATION_GALILEO)
                        .setSatelliteId(12)
                        .setCarrierFrequencyHz(1575420000f)
                        .setProbabilityLineOfSight(0.1f)
                        .setExcessPathLengthMeters(10.0f)
                        .setExcessPathLengthUncertaintyMeters(5.0f)
                        .setReflectingPlane(GnssReflectingPlaneTest.generateTestReflectingPlane());
        return singleSatCorrection.build();
    }
}
