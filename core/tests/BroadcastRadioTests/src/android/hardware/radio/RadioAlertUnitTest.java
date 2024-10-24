/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.hardware.radio;

import static org.junit.Assert.assertThrows;

import android.os.Parcel;
import android.platform.test.annotations.EnableFlags;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

@EnableFlags(Flags.FLAG_HD_RADIO_EMERGENCY_ALERT_SYSTEM)
public final class RadioAlertUnitTest {

    private static final int TEST_FLAGS = 0;
    private static final int CREATOR_ARRAY_SIZE = 3;
    private static final String TEST_GEOCODE_VALUE_NAME = "SAME";
    private static final String TEST_GEOCODE_VALUE_1 = "006109";
    private static final String TEST_GEOCODE_VALUE_2 = "006009";
    private static final double TEST_POLYGON_LATITUDE_START = -38.47;
    private static final double TEST_POLYGON_LONGITUDE_START = -120.14;
    private static final RadioAlert.Coordinate TEST_POLYGON_COORDINATE_START =
            new RadioAlert.Coordinate(TEST_POLYGON_LATITUDE_START, TEST_POLYGON_LONGITUDE_START);
    private static final List<RadioAlert.Coordinate> TEST_COORDINATES = List.of(
            TEST_POLYGON_COORDINATE_START, new RadioAlert.Coordinate(38.34, -119.95),
            new RadioAlert.Coordinate(38.52, -119.74), new RadioAlert.Coordinate(38.62, -119.89),
            TEST_POLYGON_COORDINATE_START);
    private static final RadioAlert.Polygon TEST_POLYGON = new RadioAlert.Polygon(TEST_COORDINATES);
    private static final RadioAlert.Geocode TEST_GEOCODE_1 = new RadioAlert.Geocode(
            TEST_GEOCODE_VALUE_NAME, TEST_GEOCODE_VALUE_1);
    private static final RadioAlert.Geocode TEST_GEOCODE_2 = new RadioAlert.Geocode(
            TEST_GEOCODE_VALUE_NAME, TEST_GEOCODE_VALUE_2);
    private static final RadioAlert.AlertArea TEST_AREA_1 = new RadioAlert.AlertArea(
            List.of(TEST_POLYGON), List.of(TEST_GEOCODE_1));
    private static final RadioAlert.AlertArea TEST_AREA_2 = new RadioAlert.AlertArea(
            new ArrayList<>(), List.of(TEST_GEOCODE_1, TEST_GEOCODE_2));

    @Rule
    public final Expect mExpect = Expect.create();

    @Test
    public void constructor_withNullValueName_forGeocode_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new RadioAlert.Geocode(/* valueName= */ null, TEST_GEOCODE_VALUE_1));

        mExpect.withMessage("Exception for geocode constructor with null value name")
                .that(thrown).hasMessageThat()
                .contains("Geocode value name can not be null");
    }

    @Test
    public void constructor_withNullValue_forGeocode_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new RadioAlert.Geocode(TEST_GEOCODE_VALUE_NAME, /* value= */ null));

        mExpect.withMessage("Exception for geocode constructor with null value")
                .that(thrown).hasMessageThat()
                .contains("Geocode value can not be null");
    }

    @Test
    public void getValueName_forGeocode() {
        mExpect.withMessage("Value name of geocode").that(TEST_GEOCODE_1.getValueName())
                .isEqualTo(TEST_GEOCODE_VALUE_NAME);
    }

    @Test
    public void getValue_forGeocode() {
        mExpect.withMessage("Value of geocode").that(TEST_GEOCODE_1.getValue())
                .isEqualTo(TEST_GEOCODE_VALUE_1);
    }

    @Test
    public void describeContents_forGeocode() {
        mExpect.withMessage("Contents of geocode")
                .that(TEST_GEOCODE_1.describeContents()).isEqualTo(0);
    }

    @Test
    public void writeToParcel_forGeocode() {
        Parcel parcel = Parcel.obtain();

        TEST_GEOCODE_1.writeToParcel(parcel, TEST_FLAGS);

        parcel.setDataPosition(0);
        RadioAlert.Geocode geocodeFromParcel = RadioAlert.Geocode.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("Geocode from parcel").that(geocodeFromParcel)
                .isEqualTo(TEST_GEOCODE_1);
    }

    @Test
    public void newArray_forGeocodeCreator() {
        RadioAlert.Geocode[] geocodes = RadioAlert.Geocode.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("Geocodes").that(geocodes).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void hashCode_withSameGeocodes() {
        RadioAlert.Geocode geocodeCompared = new RadioAlert.Geocode(TEST_GEOCODE_VALUE_NAME,
                TEST_GEOCODE_VALUE_1);

        mExpect.withMessage("Hash code of the same gecode")
                .that(geocodeCompared.hashCode()).isEqualTo(TEST_GEOCODE_1.hashCode());
    }

    @Test
    public void equals_withDifferentGeocodes() {
        mExpect.withMessage("Different geocode").that(TEST_GEOCODE_1)
                .isNotEqualTo(TEST_GEOCODE_2);
    }

    @Test
    @SuppressWarnings("TruthIncompatibleType")
    public void equals_withDifferentTypeObject_forGeocode() {
        mExpect.withMessage("Non-geocode object").that(TEST_GEOCODE_1)
                .isNotEqualTo(TEST_POLYGON_COORDINATE_START);
    }

    @Test
    public void constructor_withInvalidLatitude_forCoordinate_fails() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                new RadioAlert.Coordinate(/* latitude= */ -92.0, TEST_POLYGON_LONGITUDE_START));

        mExpect.withMessage("Exception for coordinate constructor with invalid latitude")
                .that(thrown).hasMessageThat().contains("Latitude");
    }

    @Test
    public void constructor_withInvalidLongitude_forCoordinate_fails() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                new RadioAlert.Coordinate(TEST_POLYGON_LATITUDE_START, /* longitude= */ 200.0));

        mExpect.withMessage("Exception for coordinate constructor with invalid longitude")
                .that(thrown).hasMessageThat().contains("Longitude");
    }

    @Test
    public void getLatitude_forCoordinate() {
        mExpect.withMessage("Latitude of coordinate")
                .that(TEST_POLYGON_COORDINATE_START.getLatitude())
                .isEqualTo(TEST_POLYGON_LATITUDE_START);
    }

    @Test
    public void getLongitude_forCoordinate() {
        mExpect.withMessage("Longitude of coordinate")
                .that(TEST_POLYGON_COORDINATE_START.getLongitude())
                .isEqualTo(TEST_POLYGON_LONGITUDE_START);
    }

    @Test
    public void describeContents_forCoordinate() {
        mExpect.withMessage("Contents of coordinate")
                .that(TEST_POLYGON_COORDINATE_START.describeContents()).isEqualTo(0);
    }

    @Test
    public void writeToParcel_forCoordinate() {
        Parcel parcel = Parcel.obtain();

        TEST_POLYGON_COORDINATE_START.writeToParcel(parcel, TEST_FLAGS);

        parcel.setDataPosition(0);
        RadioAlert.Coordinate coordinateFromParcel = RadioAlert.Coordinate.CREATOR
                .createFromParcel(parcel);
        mExpect.withMessage("Coordinate from parcel").that(coordinateFromParcel)
                .isEqualTo(TEST_POLYGON_COORDINATE_START);
    }

    @Test
    public void newArray_forCoordinateCreator() {
        RadioAlert.Coordinate[] coordinates = RadioAlert.Coordinate.CREATOR
                .newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("Coordinates").that(coordinates).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void hashCode_withSameCoordinates() {
        RadioAlert.Coordinate coordinateCompared = new RadioAlert.Coordinate(
                TEST_POLYGON_LATITUDE_START, TEST_POLYGON_LONGITUDE_START);

        mExpect.withMessage("Hash code of the same coordinate")
                .that(coordinateCompared.hashCode())
                .isEqualTo(TEST_POLYGON_COORDINATE_START.hashCode());
    }

    @Test
    public void equals_withDifferentCoordinates() {
        mExpect.withMessage("Different coordinate").that(TEST_POLYGON_COORDINATE_START)
                .isNotEqualTo(TEST_COORDINATES.get(1));
    }

    @Test
    @SuppressWarnings("TruthIncompatibleType")
    public void equals_withDifferentTypeObject_forCoordinate() {
        mExpect.withMessage("Non-coordinate object").that(TEST_POLYGON_COORDINATE_START)
                .isNotEqualTo(TEST_GEOCODE_1);
    }

    @Test
    public void constructor_withNullCoordinates_forPolygon_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new RadioAlert.Polygon(/* coordinates= */ null));

        mExpect.withMessage("Exception for polygon constructor with null coordinates")
                .that(thrown).hasMessageThat().contains("Coordinates can not be null");
    }

    @Test
    public void constructor_withLessThanFourCoordinates_forPolygon_fails() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                new RadioAlert.Polygon(List.of(TEST_POLYGON_COORDINATE_START,
                        TEST_POLYGON_COORDINATE_START)));

        mExpect.withMessage("Exception for polygon constructor with less than four coordinates")
                .that(thrown).hasMessageThat().contains("Number of coordinates must be at least 4");
    }

    @Test
    public void constructor_withDifferentFirstAndLastCoordinates_forPolygon_fails() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                new RadioAlert.Polygon(List.of(TEST_POLYGON_COORDINATE_START,
                        new RadioAlert.Coordinate(38.34, -119.95),
                        new RadioAlert.Coordinate(38.52, -119.74),
                        new RadioAlert.Coordinate(38.62, -119.89))));

        mExpect.withMessage(
                "Exception for polygon constructor with different first and last coordinates")
                .that(thrown).hasMessageThat().contains(
                        "last and first coordinates must be the same");
    }

    @Test
    public void getCoordinates_forPolygon() {
        mExpect.withMessage("Coordinates in polygon").that(TEST_POLYGON.getCoordinates())
                .containsExactlyElementsIn(TEST_COORDINATES);
    }

    @Test
    public void describeContents_forPolygon() {
        mExpect.withMessage("Contents of polygon")
                .that(TEST_POLYGON.describeContents()).isEqualTo(0);
    }

    @Test
    public void writeToParcel_forPolygon() {
        Parcel parcel = Parcel.obtain();

        TEST_POLYGON.writeToParcel(parcel, TEST_FLAGS);

        parcel.setDataPosition(0);
        RadioAlert.Polygon polygonFromParcel = RadioAlert.Polygon.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("Polygon from parcel").that(polygonFromParcel)
                .isEqualTo(TEST_POLYGON);
    }

    @Test
    public void newArray_forPolygonCreator() {
        RadioAlert.Polygon[] polygons = RadioAlert.Polygon.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("Polygons").that(polygons).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void hashCode_withSamePolygons() {
        RadioAlert.Polygon polygonCompared = new RadioAlert.Polygon(TEST_COORDINATES);

        mExpect.withMessage("Hash code of the same polygon")
                .that(polygonCompared.hashCode()).isEqualTo(TEST_POLYGON.hashCode());
    }

    @Test
    @SuppressWarnings("TruthIncompatibleType")
    public void equals_withDifferentTypeObject_forPolygon() {
        mExpect.withMessage("Non-polygon object").that(TEST_POLYGON)
                .isNotEqualTo(TEST_GEOCODE_1);
    }

    @Test
    public void constructor_withNullPolygons_forAlertArea_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new RadioAlert.AlertArea(/* polygons= */ null, List.of(TEST_GEOCODE_1)));

        mExpect.withMessage("Exception for alert area constructor with null polygon list")
                .that(thrown).hasMessageThat().contains("Polygons can not be null");
    }

    @Test
    public void constructor_withNullGeocodes_forAlertArea_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new RadioAlert.AlertArea(List.of(TEST_POLYGON), /* geocodes= */ null));

        mExpect.withMessage("Exception for alert area constructor with null geocode list")
                .that(thrown).hasMessageThat().contains("Geocodes can not be null");
    }

    @Test
    public void getPolygons_forAlertArea() {
        mExpect.withMessage("Polygons in alert area").that(TEST_AREA_1.getPolygons())
                .containsExactly(TEST_POLYGON);
    }

    @Test
    public void getGeocodes_forAlertArea() {
        mExpect.withMessage("Polygons in alert area").that(TEST_AREA_2.getGeocodes())
                .containsExactly(TEST_GEOCODE_1, TEST_GEOCODE_2);
    }

    @Test
    public void describeContents_forAlertArea() {
        mExpect.withMessage("Contents of alert area")
                .that(TEST_AREA_1.describeContents()).isEqualTo(0);
    }

    @Test
    public void writeToParcel_forAlertArea() {
        Parcel parcel = Parcel.obtain();

        TEST_AREA_1.writeToParcel(parcel, TEST_FLAGS);

        parcel.setDataPosition(0);
        RadioAlert.AlertArea areaFromParcel = RadioAlert.AlertArea.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("Alert area from parcel").that(areaFromParcel)
                .isEqualTo(TEST_AREA_1);
    }

    @Test
    public void newArray_forAlertAreaCreator() {
        RadioAlert.AlertArea[] alertAreas = RadioAlert.AlertArea.CREATOR
                .newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("Alert areas").that(alertAreas).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void hashCode_withSameAlertAreas() {
        RadioAlert.AlertArea alertAreaCompared = new RadioAlert.AlertArea(List.of(TEST_POLYGON),
                List.of(TEST_GEOCODE_1));

        mExpect.withMessage("Hash code of the same alert area")
                .that(alertAreaCompared.hashCode()).isEqualTo(TEST_AREA_1.hashCode());
    }

    @Test
    public void equals_withDifferentAlertAreas() {
        mExpect.withMessage("Different alert area").that(TEST_AREA_1).isNotEqualTo(TEST_AREA_2);
    }

    @Test
    @SuppressWarnings("TruthIncompatibleType")
    public void equals_withDifferentTypeObject_forAlertArea() {
        mExpect.withMessage("Non-alert-area object").that(TEST_AREA_1)
                .isNotEqualTo(TEST_GEOCODE_1);
    }
}
