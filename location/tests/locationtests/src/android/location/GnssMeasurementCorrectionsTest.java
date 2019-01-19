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

import java.util.ArrayList;
import java.util.List;

/** Unit tests for {@link GnssMeasurementCorrections}. */
public class GnssMeasurementCorrectionsTest extends TestCase {
    public void testDescribeContents() {
        GnssMeasurementCorrections measurementCorrections =
                new GnssMeasurementCorrections.Builder().build();
        measurementCorrections.describeContents();
    }

    public void testWriteToParcel() {
        GnssMeasurementCorrections.Builder measurementCorrections =
                new GnssMeasurementCorrections.Builder();
        setTestValues(measurementCorrections);
        Parcel parcel = Parcel.obtain();
        measurementCorrections.build().writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        GnssMeasurementCorrections newMeasurementCorrection =
                GnssMeasurementCorrections.CREATOR.createFromParcel(parcel);
        verifyTestValues(newMeasurementCorrection);
        parcel.recycle();
    }

    private static void verifyTestValues(GnssMeasurementCorrections measurementCorrections) {
        assertEquals(37.386051, measurementCorrections.getLatitudeDegrees());
        assertEquals(-122.083855, measurementCorrections.getLongitudeDegrees());
        assertEquals(32.0, measurementCorrections.getAltitudeMeters());
        assertEquals(604000000000000L, measurementCorrections.getToaGpsNanosecondsOfWeek());

        GnssSingleSatCorrection singleSatCorrection =
                measurementCorrections.getSingleSatCorrectionList().get(0);
        GnssSingleSatCorrectionsTest.verifyTestValues(singleSatCorrection);

        singleSatCorrection = measurementCorrections.getSingleSatCorrectionList().get(1);
        assertEquals(15, singleSatCorrection.getSingleSatCorrectionFlags());
        assertEquals(GnssStatus.CONSTELLATION_GPS, singleSatCorrection.getConstellationType());
        assertEquals(11, singleSatCorrection.getSatId());
        assertEquals(1575430000f, singleSatCorrection.getCarrierFrequencyHz());
        assertEquals(0.9f, singleSatCorrection.getProbSatIsLos());
        assertEquals(50.0f, singleSatCorrection.getExcessPathLengthMeters());
        assertEquals(55.0f, singleSatCorrection.getExcessPathLengthUncertaintyMeters());
        GnssReflectingPlane reflectingPlane = singleSatCorrection.getReflectingPlane();
        assertEquals(37.386054, reflectingPlane.getLatitudeDegrees());
        assertEquals(-122.083855, reflectingPlane.getLongitudeDegrees());
        assertEquals(120.0, reflectingPlane.getAltitudeMeters());
        assertEquals(153.0, reflectingPlane.getAzimuthDegrees());
    }

    private static void setTestValues(GnssMeasurementCorrections.Builder measurementCorrections) {
        measurementCorrections
                .setLatitudeDegrees(37.386051)
                .setLongitudeDegrees(-122.083855)
                .setAltitudeMeters(32)
                .setToaGpsNanosecondsOfWeek(604000000000000L);
        List<GnssSingleSatCorrection> singleSatCorrectionList = new ArrayList<>();
        singleSatCorrectionList.add(GnssSingleSatCorrectionsTest.generateTestSingleSatCorrection());
        singleSatCorrectionList.add(generateTestSingleSatCorrection());
        measurementCorrections.setSingleSatCorrectionList(singleSatCorrectionList);
    }

    private static GnssSingleSatCorrection generateTestSingleSatCorrection() {
        GnssSingleSatCorrection.Builder singleSatCorrection = new GnssSingleSatCorrection.Builder();
        singleSatCorrection
                .setSingleSatCorrectionFlags(8)
                .setConstellationType(GnssStatus.CONSTELLATION_GPS)
                .setSatId(11)
                .setCarrierFrequencyHz(1575430000f)
                .setProbSatIsLos(0.9f)
                .setExcessPathLengthMeters(50.0f)
                .setExcessPathLengthUncertaintyMeters(55.0f)
                .setReflectingPlane(generateTestReflectingPlane());
        return singleSatCorrection.build();
    }

    private static GnssReflectingPlane generateTestReflectingPlane() {
        GnssReflectingPlane.Builder reflectingPlane =
                new GnssReflectingPlane.Builder()
                        .setLatitudeDegrees(37.386054)
                        .setLongitudeDegrees(-122.083855)
                        .setAltitudeMeters(120.0)
                        .setAzimuthDegrees(153);
        return reflectingPlane.build();
    }
}
