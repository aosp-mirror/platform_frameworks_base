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

import com.google.common.primitives.Ints;
import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

@EnableFlags(Flags.FLAG_HD_RADIO_EMERGENCY_ALERT_SYSTEM)
public final class RadioAlertUnitTest {

    private static final int TEST_FLAGS = 0;
    private static final int CREATOR_ARRAY_SIZE = 3;
    private static final int TEST_STATUS = RadioAlert.STATUS_ACTUAL;
    private static final int TEST_TYPE = RadioAlert.MESSAGE_TYPE_ALERT;
    private static final int[] TEST_CATEGORIES_1 = new int[]{RadioAlert.CATEGORY_CBRNE,
            RadioAlert.CATEGORY_GEO};
    private static final int[] TEST_CATEGORIES_2 = new int[]{RadioAlert.CATEGORY_CBRNE,
            RadioAlert.CATEGORY_FIRE};
    private static final int TEST_URGENCY_1 = RadioAlert.URGENCY_EXPECTED;
    private static final int TEST_URGENCY_2 = RadioAlert.URGENCY_FUTURE;
    private static final int TEST_SEVERITY_1 = RadioAlert.SEVERITY_SEVERE;
    private static final int TEST_SEVERITY_2 = RadioAlert.SEVERITY_MODERATE;
    private static final int TEST_CERTAINTY_1 = RadioAlert.CERTAINTY_POSSIBLE;
    private static final int TEST_CERTAINTY_2 = RadioAlert.CERTAINTY_UNLIKELY;
    private static final String TEST_DESCRIPTION_MESSAGE_1 = "Test Alert Description Message 1.";
    private static final String TEST_DESCRIPTION_MESSAGE_2 = "Test Alert Description Message 2.";
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
    private static final List<RadioAlert.AlertArea> TEST_AREA_LIST_1 = List.of(TEST_AREA_1);
    private static final List<RadioAlert.AlertArea> TEST_AREA_LIST_2 = List.of(TEST_AREA_2);
    private static final String TEST_LANGUAGE_1 = "en-US";

    private static final RadioAlert.AlertInfo TEST_ALERT_INFO_1 = new RadioAlert.AlertInfo(
            TEST_CATEGORIES_1, TEST_URGENCY_1, TEST_SEVERITY_1, TEST_CERTAINTY_1,
            TEST_DESCRIPTION_MESSAGE_1, TEST_AREA_LIST_1, TEST_LANGUAGE_1);
    private static final RadioAlert.AlertInfo TEST_ALERT_INFO_2 = new RadioAlert.AlertInfo(
            TEST_CATEGORIES_2, TEST_URGENCY_2, TEST_SEVERITY_2, TEST_CERTAINTY_2,
            TEST_DESCRIPTION_MESSAGE_2, TEST_AREA_LIST_2, /* language= */ null);
    private static final RadioAlert TEST_ALERT = new RadioAlert(TEST_STATUS, TEST_TYPE,
            List.of(TEST_ALERT_INFO_1, TEST_ALERT_INFO_2));

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

    @Test
    public void constructor_withNullCategories_forAlertInfo_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new RadioAlert.AlertInfo(/* categories= */ null, TEST_URGENCY_1, TEST_SEVERITY_1,
                        TEST_CERTAINTY_1, TEST_DESCRIPTION_MESSAGE_1, TEST_AREA_LIST_1,
                        TEST_LANGUAGE_1));

        mExpect.withMessage("Exception for alert info constructor with null categories")
                .that(thrown).hasMessageThat().contains("Categories can not be null");
    }

    @Test
    public void constructor_withNullAreaList_forAlertInfo_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new RadioAlert.AlertInfo(TEST_CATEGORIES_1, TEST_URGENCY_1, TEST_SEVERITY_1,
                        TEST_CERTAINTY_1, TEST_DESCRIPTION_MESSAGE_1, /* areaList= */ null,
                        TEST_LANGUAGE_1));

        mExpect.withMessage("Exception for alert info constructor with null area list")
                .that(thrown).hasMessageThat().contains("Area list can not be null");
    }

    @Test
    public void getCategories_forAlertInfo() {
        mExpect.withMessage("Radio alert info categories")
                .that(Ints.asList(TEST_ALERT_INFO_1.getCategories()))
                .containsExactlyElementsIn(Ints.asList(TEST_CATEGORIES_1));
    }

    @Test
    public void getUrgency_forAlertInfo() {
        mExpect.withMessage("Radio alert info urgency")
                .that(TEST_ALERT_INFO_1.getUrgency()).isEqualTo(TEST_URGENCY_1);
    }

    @Test
    public void getSeverity_forAlertInfo() {
        mExpect.withMessage("Radio alert info severity")
                .that(TEST_ALERT_INFO_1.getSeverity()).isEqualTo(TEST_SEVERITY_1);
    }

    @Test
    public void getCertainty_forAlertInfo() {
        mExpect.withMessage("Radio alert info certainty")
                .that(TEST_ALERT_INFO_1.getCertainty()).isEqualTo(TEST_CERTAINTY_1);
    }

    @Test
    public void getDescription_forAlertInfo() {
        mExpect.withMessage("Radio alert info description")
                .that(TEST_ALERT_INFO_1.getDescription()).isEqualTo(TEST_DESCRIPTION_MESSAGE_1);
    }

    @Test
    public void getAreas_forAlertInfo() {
        mExpect.withMessage("Radio alert info areas")
                .that(TEST_ALERT_INFO_1.getAreas()).containsAtLeastElementsIn(TEST_AREA_LIST_1);
    }

    @Test
    public void getLanguage_forAlertInfo() {
        mExpect.withMessage("Radio alert language")
                .that(TEST_ALERT_INFO_1.getLanguage()).isEqualTo(TEST_LANGUAGE_1);
    }

    @Test
    public void describeContents_forAlertInfo() {
        mExpect.withMessage("Contents of alert info")
                .that(TEST_ALERT_INFO_1.describeContents()).isEqualTo(0);
    }

    @Test
    public void writeToParcel_forAlertInfoWithNullLanguage() {
        Parcel parcel = Parcel.obtain();

        TEST_ALERT_INFO_2.writeToParcel(parcel, TEST_FLAGS);

        parcel.setDataPosition(0);
        RadioAlert.AlertInfo alertInfoFromParcel = RadioAlert.AlertInfo.CREATOR
                .createFromParcel(parcel);
        mExpect.withMessage("Alert info from parcel with null language")
                .that(alertInfoFromParcel).isEqualTo(TEST_ALERT_INFO_2);
    }

    @Test
    public void writeToParcel_forAlertInfoWithNonnullLanguage() {
        Parcel parcel = Parcel.obtain();

        TEST_ALERT_INFO_1.writeToParcel(parcel, TEST_FLAGS);

        parcel.setDataPosition(0);
        RadioAlert.AlertInfo alertInfoFromParcel = RadioAlert.AlertInfo.CREATOR
                .createFromParcel(parcel);
        mExpect.withMessage("Alert info with nonnull language from parcel")
                .that(alertInfoFromParcel).isEqualTo(TEST_ALERT_INFO_1);
    }

    @Test
    public void newArray_forAlertInfoCreator() {
        RadioAlert.AlertInfo[] alertInfos = RadioAlert.AlertInfo.CREATOR
                .newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("Alert infos").that(alertInfos).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void hashCode_withSameAlertInfos() {
        RadioAlert.AlertInfo alertInfoCompared = new RadioAlert.AlertInfo(
                TEST_CATEGORIES_1, TEST_URGENCY_1, TEST_SEVERITY_1, TEST_CERTAINTY_1,
                TEST_DESCRIPTION_MESSAGE_1, TEST_AREA_LIST_1, TEST_LANGUAGE_1);

        mExpect.withMessage("Hash code of the same alert info")
                .that(alertInfoCompared.hashCode()).isEqualTo(TEST_ALERT_INFO_1.hashCode());
    }

    @Test
    public void constructor_forRadioAlert() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new RadioAlert(TEST_STATUS, TEST_TYPE, /* infoList= */ null));

        mExpect.withMessage("Exception for alert constructor with null alert info list")
                .that(thrown).hasMessageThat().contains("Alert info list can not be null");
    }

    @Test
    public void equals_withDifferentAlertInfo() {
        mExpect.withMessage("Different alert info").that(TEST_ALERT_INFO_1)
                .isNotEqualTo(TEST_ALERT_INFO_2);
    }

    @Test
    @SuppressWarnings("TruthIncompatibleType")
    public void equals_withDifferentTypeObject_forAlertInfo() {
        mExpect.withMessage("Non-alert-info object").that(TEST_ALERT_INFO_1)
                .isNotEqualTo(TEST_AREA_1);
    }

    @Test
    public void getStatus() {
        mExpect.withMessage("Radio alert status").that(TEST_ALERT.getStatus())
                .isEqualTo(TEST_STATUS);
    }

    @Test
    public void getMessageType() {
        mExpect.withMessage("Radio alert message type")
                .that(TEST_ALERT.getMessageType()).isEqualTo(TEST_TYPE);
    }

    @Test
    public void getInfoList() {
        mExpect.withMessage("Radio alert info list").that(TEST_ALERT.getInfoList())
                .containsExactly(TEST_ALERT_INFO_1, TEST_ALERT_INFO_2);
    }

    @Test
    public void describeContents() {
        mExpect.withMessage("Contents of radio alert")
                .that(TEST_ALERT.describeContents()).isEqualTo(0);
    }

    @Test
    public void writeToParcel() {
        Parcel parcel = Parcel.obtain();

        TEST_ALERT.writeToParcel(parcel, TEST_FLAGS);

        parcel.setDataPosition(0);
        RadioAlert alertFromParcel = RadioAlert.CREATOR.createFromParcel(parcel);
        mExpect.withMessage("Alert from parcel").that(alertFromParcel)
                .isEqualTo(TEST_ALERT);
    }

    @Test
    public void hashCode_withSameAlerts() {
        RadioAlert alertCompared = new RadioAlert(TEST_STATUS, TEST_TYPE,
                List.of(TEST_ALERT_INFO_1, TEST_ALERT_INFO_2));

        mExpect.withMessage("Hash code of the same alert")
                .that(alertCompared.hashCode()).isEqualTo(TEST_ALERT.hashCode());
    }

    @Test
    public void newArray_forAlertCreator() {
        RadioAlert[] alerts = RadioAlert.CREATOR.newArray(CREATOR_ARRAY_SIZE);

        mExpect.withMessage("Alerts").that(alerts).hasLength(CREATOR_ARRAY_SIZE);
    }

    @Test
    public void equals_withDifferentAlert() {
        RadioAlert differentAlert = new RadioAlert(TEST_STATUS, TEST_TYPE,
                List.of(TEST_ALERT_INFO_2));

        mExpect.withMessage("Different alert").that(TEST_ALERT)
                .isNotEqualTo(differentAlert);
    }

    @Test
    @SuppressWarnings("TruthIncompatibleType")
    public void equals_withDifferentTypeObject() {
        mExpect.withMessage("Non-alert object").that(TEST_ALERT)
                .isNotEqualTo(TEST_ALERT_INFO_2);
    }
}
