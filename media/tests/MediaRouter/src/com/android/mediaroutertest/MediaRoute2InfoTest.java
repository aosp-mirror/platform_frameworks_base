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

package com.android.mediaroutertest;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertThrows;

import android.media.MediaRoute2Info;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests {@link MediaRoute2Info} and its {@link MediaRoute2Info.Builder builder}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class MediaRoute2InfoTest {

    public static final String TEST_ID = "test_id";
    public static final String TEST_NAME = "test_name";
    public static final String TEST_ROUTE_TYPE_0 = "test_route_type_0";
    public static final String TEST_ROUTE_TYPE_1 = "test_route_type_1";
    public static final int TEST_DEVICE_TYPE = MediaRoute2Info.DEVICE_TYPE_REMOTE_SPEAKER;
    public static final Uri TEST_ICON_URI = Uri.parse("https://developer.android.com");
    public static final String TEST_DESCRIPTION = "test_description";
    public static final int TEST_CONNECTION_STATE = MediaRoute2Info.CONNECTION_STATE_CONNECTING;
    public static final String TEST_CLIENT_PACKAGE_NAME = "com.test.client.package.name";
    public static final int TEST_VOLUME_HANDLING = MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE;
    public static final int TEST_VOLUME_MAX = 100;
    public static final int TEST_VOLUME = 65;

    public static final String TEST_KEY = "test_key";
    public static final String TEST_VALUE = "test_value";

    @Test
    public void testBuilderConstructorWithInvalidValues() {
        final String nullId = null;
        final String nullName = null;
        final String emptyId = "";
        final String emptyName = "";
        final String validId = "valid_id";
        final String validName = "valid_name";

        // ID is invalid
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRoute2Info.Builder(nullId, validName));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRoute2Info.Builder(emptyId, validName));

        // name is invalid
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRoute2Info.Builder(validId, nullName));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRoute2Info.Builder(validId, emptyName));

        // Both are invalid
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRoute2Info.Builder(nullId, nullName));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRoute2Info.Builder(nullId, emptyName));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRoute2Info.Builder(emptyId, nullName));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRoute2Info.Builder(emptyId, emptyName));


        // Null RouteInfo (1-argument constructor)
        final MediaRoute2Info nullRouteInfo = null;
        assertThrows(NullPointerException.class,
                () -> new MediaRoute2Info.Builder(nullRouteInfo));
    }

    @Test
    public void testBuilderBuildWithEmptyRouteTypesShouldThrowIAE() {
        MediaRoute2Info.Builder builder = new MediaRoute2Info.Builder(TEST_ID, TEST_NAME);
        assertThrows(IllegalArgumentException.class, () -> builder.build());
    }

    @Test
    public void testBuilderAndGettersOfMediaRoute2Info() {
        Bundle extras = new Bundle();
        extras.putString(TEST_KEY, TEST_VALUE);

        MediaRoute2Info routeInfo = new MediaRoute2Info.Builder(TEST_ID, TEST_NAME)
                .addFeature(TEST_ROUTE_TYPE_0)
                .addFeature(TEST_ROUTE_TYPE_1)
                .setDeviceType(TEST_DEVICE_TYPE)
                .setIconUri(TEST_ICON_URI)
                .setDescription(TEST_DESCRIPTION)
                .setConnectionState(TEST_CONNECTION_STATE)
                .setClientPackageName(TEST_CLIENT_PACKAGE_NAME)
                .setVolumeHandling(TEST_VOLUME_HANDLING)
                .setVolumeMax(TEST_VOLUME_MAX)
                .setVolume(TEST_VOLUME)
                .setExtras(extras)
                .build();

        assertEquals(TEST_ID, routeInfo.getId());
        assertEquals(TEST_NAME, routeInfo.getName());

        assertEquals(2, routeInfo.getFeatures().size());
        assertEquals(TEST_ROUTE_TYPE_0, routeInfo.getFeatures().get(0));
        assertEquals(TEST_ROUTE_TYPE_1, routeInfo.getFeatures().get(1));

        assertEquals(TEST_DEVICE_TYPE, routeInfo.getDeviceType());
        assertEquals(TEST_ICON_URI, routeInfo.getIconUri());
        assertEquals(TEST_DESCRIPTION, routeInfo.getDescription());
        assertEquals(TEST_CONNECTION_STATE, routeInfo.getConnectionState());
        assertEquals(TEST_CLIENT_PACKAGE_NAME, routeInfo.getClientPackageName());
        assertEquals(TEST_VOLUME_HANDLING, routeInfo.getVolumeHandling());
        assertEquals(TEST_VOLUME_MAX, routeInfo.getVolumeMax());
        assertEquals(TEST_VOLUME, routeInfo.getVolume());

        Bundle extrasOut = routeInfo.getExtras();
        assertNotNull(extrasOut);
        assertTrue(extrasOut.containsKey(TEST_KEY));
        assertEquals(TEST_VALUE, extrasOut.getString(TEST_KEY));
    }

    @Test
    public void testBuilderSetExtrasWithNull() {
        MediaRoute2Info routeInfo = new MediaRoute2Info.Builder(TEST_ID, TEST_NAME)
                .addFeature(TEST_ROUTE_TYPE_0)
                .setExtras(null)
                .build();

        assertNull(routeInfo.getExtras());
    }

    @Test
    public void testBuilderaddFeatures() {
        List<String> routeTypes = new ArrayList<>();
        routeTypes.add(TEST_ROUTE_TYPE_0);
        routeTypes.add(TEST_ROUTE_TYPE_1);

        MediaRoute2Info routeInfo = new MediaRoute2Info.Builder(TEST_ID, TEST_NAME)
                .addFeatures(routeTypes)
                .build();

        assertEquals(routeTypes, routeInfo.getFeatures());
    }

    @Test
    public void testBuilderclearFeatures() {
        MediaRoute2Info routeInfo = new MediaRoute2Info.Builder(TEST_ID, TEST_NAME)
                .addFeature(TEST_ROUTE_TYPE_0)
                .addFeature(TEST_ROUTE_TYPE_1)
                // clearFeatures should clear the route types.
                .clearFeatures()
                .addFeature(TEST_ROUTE_TYPE_1)
                .build();

        assertEquals(1, routeInfo.getFeatures().size());
        assertEquals(TEST_ROUTE_TYPE_1, routeInfo.getFeatures().get(0));
    }

    @Test
    public void testhasAnyFeaturesReturnsFalse() {
        MediaRoute2Info routeInfo = new MediaRoute2Info.Builder(TEST_ID, TEST_NAME)
                .addFeature(TEST_ROUTE_TYPE_0)
                .addFeature(TEST_ROUTE_TYPE_1)
                .build();

        List<String> testRouteTypes = new ArrayList<>();
        testRouteTypes.add("non_matching_route_type_1");
        testRouteTypes.add("non_matching_route_type_2");
        testRouteTypes.add("non_matching_route_type_3");
        testRouteTypes.add("non_matching_route_type_4");

        assertFalse(routeInfo.hasAnyFeatures(testRouteTypes));
    }

    @Test
    public void testhasAnyFeaturesReturnsTrue() {
        MediaRoute2Info routeInfo = new MediaRoute2Info.Builder(TEST_ID, TEST_NAME)
                .addFeature(TEST_ROUTE_TYPE_0)
                .addFeature(TEST_ROUTE_TYPE_1)
                .build();

        List<String> testRouteTypes = new ArrayList<>();
        testRouteTypes.add("non_matching_route_type_1");
        testRouteTypes.add("non_matching_route_type_2");
        testRouteTypes.add("non_matching_route_type_3");
        testRouteTypes.add(TEST_ROUTE_TYPE_1);

        assertTrue(routeInfo.hasAnyFeatures(testRouteTypes));
    }

    @Test
    public void testEqualsCreatedWithSameArguments() {
        Bundle extras = new Bundle();
        extras.putString(TEST_KEY, TEST_VALUE);

        MediaRoute2Info routeInfo1 = new MediaRoute2Info.Builder(TEST_ID, TEST_NAME)
                .addFeature(TEST_ROUTE_TYPE_0)
                .addFeature(TEST_ROUTE_TYPE_1)
                .setDeviceType(TEST_DEVICE_TYPE)
                .setIconUri(TEST_ICON_URI)
                .setDescription(TEST_DESCRIPTION)
                .setConnectionState(TEST_CONNECTION_STATE)
                .setClientPackageName(TEST_CLIENT_PACKAGE_NAME)
                .setVolumeHandling(TEST_VOLUME_HANDLING)
                .setVolumeMax(TEST_VOLUME_MAX)
                .setVolume(TEST_VOLUME)
                .setExtras(extras)
                .build();

        MediaRoute2Info routeInfo2 = new MediaRoute2Info.Builder(TEST_ID, TEST_NAME)
                .addFeature(TEST_ROUTE_TYPE_0)
                .addFeature(TEST_ROUTE_TYPE_1)
                .setDeviceType(TEST_DEVICE_TYPE)
                .setIconUri(TEST_ICON_URI)
                .setDescription(TEST_DESCRIPTION)
                .setConnectionState(TEST_CONNECTION_STATE)
                .setClientPackageName(TEST_CLIENT_PACKAGE_NAME)
                .setVolumeHandling(TEST_VOLUME_HANDLING)
                .setVolumeMax(TEST_VOLUME_MAX)
                .setVolume(TEST_VOLUME)
                .setExtras(extras)
                .build();

        assertEquals(routeInfo1, routeInfo2);
        assertEquals(routeInfo1.hashCode(), routeInfo2.hashCode());
    }

    @Test
    public void testEqualsCreatedWithBuilderCopyConstructor() {
        Bundle extras = new Bundle();
        extras.putString(TEST_KEY, TEST_VALUE);

        MediaRoute2Info routeInfo1 = new MediaRoute2Info.Builder(TEST_ID, TEST_NAME)
                .addFeature(TEST_ROUTE_TYPE_0)
                .addFeature(TEST_ROUTE_TYPE_1)
                .setDeviceType(TEST_DEVICE_TYPE)
                .setIconUri(TEST_ICON_URI)
                .setDescription(TEST_DESCRIPTION)
                .setConnectionState(TEST_CONNECTION_STATE)
                .setClientPackageName(TEST_CLIENT_PACKAGE_NAME)
                .setVolumeHandling(TEST_VOLUME_HANDLING)
                .setVolumeMax(TEST_VOLUME_MAX)
                .setVolume(TEST_VOLUME)
                .setExtras(extras)
                .build();

        MediaRoute2Info routeInfo2 = new MediaRoute2Info.Builder(routeInfo1).build();

        assertEquals(routeInfo1, routeInfo2);
        assertEquals(routeInfo1.hashCode(), routeInfo2.hashCode());
    }

    @Test
    public void testEqualsReturnFalse() {
        Bundle extras = new Bundle();
        extras.putString(TEST_KEY, TEST_VALUE);

        MediaRoute2Info routeInfo = new MediaRoute2Info.Builder(TEST_ID, TEST_NAME)
                .addFeature(TEST_ROUTE_TYPE_0)
                .addFeature(TEST_ROUTE_TYPE_1)
                .setDeviceType(TEST_DEVICE_TYPE)
                .setIconUri(TEST_ICON_URI)
                .setDescription(TEST_DESCRIPTION)
                .setConnectionState(TEST_CONNECTION_STATE)
                .setClientPackageName(TEST_CLIENT_PACKAGE_NAME)
                .setVolumeHandling(TEST_VOLUME_HANDLING)
                .setVolumeMax(TEST_VOLUME_MAX)
                .setVolume(TEST_VOLUME)
                .setExtras(extras)
                .build();

        // Now, we will use copy constructor
        assertNotEquals(routeInfo, new MediaRoute2Info.Builder(routeInfo)
                .addFeature("randomRouteType")
                .build());
        assertNotEquals(routeInfo, new MediaRoute2Info.Builder(routeInfo)
                .setDeviceType(TEST_DEVICE_TYPE + 1)
                .build());
        assertNotEquals(routeInfo, new MediaRoute2Info.Builder(routeInfo)
                .setIconUri(Uri.parse("randomUri"))
                .build());
        assertNotEquals(routeInfo, new MediaRoute2Info.Builder(routeInfo)
                .setDescription("randomDescription")
                .build());
        assertNotEquals(routeInfo, new MediaRoute2Info.Builder(routeInfo)
                .setConnectionState(TEST_CONNECTION_STATE + 1)
                .build());
        assertNotEquals(routeInfo, new MediaRoute2Info.Builder(routeInfo)
                .setClientPackageName("randomPackageName")
                .build());
        assertNotEquals(routeInfo, new MediaRoute2Info.Builder(routeInfo)
                .setVolumeHandling(TEST_VOLUME_HANDLING + 1)
                .build());
        assertNotEquals(routeInfo, new MediaRoute2Info.Builder(routeInfo)
                .setVolumeMax(TEST_VOLUME_MAX + 100)
                .build());
        assertNotEquals(routeInfo, new MediaRoute2Info.Builder(routeInfo)
                .setVolume(TEST_VOLUME + 10)
                .build());
        // Note: Extras will not affect the equals.
    }

    @Test
    public void testParcelingAndUnParceling() {
        Bundle extras = new Bundle();
        extras.putString(TEST_KEY, TEST_VALUE);

        MediaRoute2Info routeInfo = new MediaRoute2Info.Builder(TEST_ID, TEST_NAME)
                .addFeature(TEST_ROUTE_TYPE_0)
                .addFeature(TEST_ROUTE_TYPE_1)
                .setDeviceType(TEST_DEVICE_TYPE)
                .setIconUri(TEST_ICON_URI)
                .setDescription(TEST_DESCRIPTION)
                .setConnectionState(TEST_CONNECTION_STATE)
                .setClientPackageName(TEST_CLIENT_PACKAGE_NAME)
                .setVolumeHandling(TEST_VOLUME_HANDLING)
                .setVolumeMax(TEST_VOLUME_MAX)
                .setVolume(TEST_VOLUME)
                .setExtras(extras)
                .build();

        Parcel parcel = Parcel.obtain();
        routeInfo.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        MediaRoute2Info routeInfoFromParcel = MediaRoute2Info.CREATOR.createFromParcel(parcel);
        assertEquals(routeInfo, routeInfoFromParcel);
        assertEquals(routeInfo.hashCode(), routeInfoFromParcel.hashCode());

        // Check extras
        Bundle extrasOut = routeInfoFromParcel.getExtras();
        assertNotNull(extrasOut);
        assertTrue(extrasOut.containsKey(TEST_KEY));
        assertEquals(TEST_VALUE, extrasOut.getString(TEST_KEY));
    }
}
