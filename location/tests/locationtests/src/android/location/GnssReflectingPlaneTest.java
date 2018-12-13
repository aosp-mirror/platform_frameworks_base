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

/** Unit tests for {@link GnssReflectingPlane}. */
public class GnssReflectingPlaneTest extends TestCase {
    public void testDescribeContents() {
        GnssReflectingPlane reflectingPlane = new GnssReflectingPlane.Builder().build();
        reflectingPlane.describeContents();
    }

    public void testWriteToParcel() {
        GnssReflectingPlane.Builder reflectingPlane = new GnssReflectingPlane.Builder();
        setTestValues(reflectingPlane);
        Parcel parcel = Parcel.obtain();
        reflectingPlane.build().writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        GnssReflectingPlane newReflectingPlane =
                GnssReflectingPlane.CREATOR.createFromParcel(parcel);
        verifyTestValues(newReflectingPlane);
        parcel.recycle();
    }

    public static void verifyTestValues(GnssReflectingPlane reflectingPlane) {
        assertEquals(37.386052, reflectingPlane.getLatitudeDegrees());
        assertEquals(-122.083853, reflectingPlane.getLongitudeDegrees());
        assertEquals(100.0, reflectingPlane.getAltitudeMeters());
        assertEquals(123.0, reflectingPlane.getAzimuthDegrees());
    }

    private static void setTestValues(GnssReflectingPlane.Builder reflectingPlane) {
        GnssReflectingPlane refPlane = generateTestReflectingPlane();
        reflectingPlane
                .setLatitudeDegrees(refPlane.getLatitudeDegrees())
                .setLongitudeDegrees(refPlane.getLongitudeDegrees())
                .setAltitudeMeters(refPlane.getAltitudeMeters())
                .setAzimuthDegrees(refPlane.getAzimuthDegrees());
    }

    public static GnssReflectingPlane generateTestReflectingPlane() {
        GnssReflectingPlane.Builder reflectingPlane =
                new GnssReflectingPlane.Builder()
                        .setLatitudeDegrees(37.386052)
                        .setLongitudeDegrees(-122.083853)
                        .setAltitudeMeters(100.0)
                        .setAzimuthDegrees(123.0);
        return reflectingPlane.build();
    }
}
