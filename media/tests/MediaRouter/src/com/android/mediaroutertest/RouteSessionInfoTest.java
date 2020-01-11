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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertThrows;

import android.media.RouteSessionInfo;
import android.os.Bundle;
import android.os.Parcel;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests {@link RouteSessionInfo} and its {@link RouteSessionInfo.Builder builder}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class RouteSessionInfoTest {
    public static final String TEST_ID = "test_id";
    public static final String TEST_CLIENT_PACKAGE_NAME = "com.test.client.package.name";
    public static final String TEST_ROUTE_FEATURE = "test_route_feature";

    public static final String TEST_ROUTE_ID_0 = "test_route_type_0";
    public static final String TEST_ROUTE_ID_1 = "test_route_type_1";
    public static final String TEST_ROUTE_ID_2 = "test_route_type_2";
    public static final String TEST_ROUTE_ID_3 = "test_route_type_3";
    public static final String TEST_ROUTE_ID_4 = "test_route_type_4";
    public static final String TEST_ROUTE_ID_5 = "test_route_type_5";
    public static final String TEST_ROUTE_ID_6 = "test_route_type_6";
    public static final String TEST_ROUTE_ID_7 = "test_route_type_7";

    public static final String TEST_KEY = "test_key";
    public static final String TEST_VALUE = "test_value";

    @Test
    public void testBuilderConstructorWithInvalidValues() {
        final String nullId = null;
        final String nullClientPackageName = null;
        final String nullRouteFeature = null;

        final String emptyId = "";
        // Note: An empty string as client package name is valid.
        final String emptyRouteFeature = "";

        final String validId = TEST_ID;
        final String validClientPackageName = TEST_CLIENT_PACKAGE_NAME;
        final String validRouteFeature = TEST_ROUTE_FEATURE;

        // ID is invalid
        assertThrows(IllegalArgumentException.class, () -> new RouteSessionInfo.Builder(
                nullId, validClientPackageName, validRouteFeature));
        assertThrows(IllegalArgumentException.class, () -> new RouteSessionInfo.Builder(
                emptyId, validClientPackageName, validRouteFeature));

        // client package name is invalid (null)
        assertThrows(NullPointerException.class, () -> new RouteSessionInfo.Builder(
                validId, nullClientPackageName, validRouteFeature));

        // route feature is invalid
        assertThrows(IllegalArgumentException.class, () -> new RouteSessionInfo.Builder(
                validId, validClientPackageName, nullRouteFeature));
        assertThrows(IllegalArgumentException.class, () -> new RouteSessionInfo.Builder(
                validId, validClientPackageName, emptyRouteFeature));

        // Two arguments are invalid - (1) ID and clientPackageName
        assertThrows(IllegalArgumentException.class, () -> new RouteSessionInfo.Builder(
                nullId, nullClientPackageName, validRouteFeature));
        assertThrows(IllegalArgumentException.class, () -> new RouteSessionInfo.Builder(
                emptyId, nullClientPackageName, validRouteFeature));

        // Two arguments are invalid - (2) ID and routeFeature
        assertThrows(IllegalArgumentException.class, () -> new RouteSessionInfo.Builder(
                nullId, validClientPackageName, nullRouteFeature));
        assertThrows(IllegalArgumentException.class, () -> new RouteSessionInfo.Builder(
                nullId, validClientPackageName, emptyRouteFeature));
        assertThrows(IllegalArgumentException.class, () -> new RouteSessionInfo.Builder(
                emptyId, validClientPackageName, nullRouteFeature));
        assertThrows(IllegalArgumentException.class, () -> new RouteSessionInfo.Builder(
                emptyId, validClientPackageName, emptyRouteFeature));

        // Two arguments are invalid - (3) clientPackageName and routeFeature
        // Note that this throws NullPointerException.
        assertThrows(NullPointerException.class, () -> new RouteSessionInfo.Builder(
                validId, nullClientPackageName, nullRouteFeature));
        assertThrows(NullPointerException.class, () -> new RouteSessionInfo.Builder(
                validId, nullClientPackageName, emptyRouteFeature));

        // All arguments are invalid
        assertThrows(IllegalArgumentException.class, () -> new RouteSessionInfo.Builder(
                nullId, nullClientPackageName, nullRouteFeature));
        assertThrows(IllegalArgumentException.class, () -> new RouteSessionInfo.Builder(
                nullId, nullClientPackageName, emptyRouteFeature));
        assertThrows(IllegalArgumentException.class, () -> new RouteSessionInfo.Builder(
                emptyId, nullClientPackageName, nullRouteFeature));
        assertThrows(IllegalArgumentException.class, () -> new RouteSessionInfo.Builder(
                emptyId, nullClientPackageName, emptyRouteFeature));

        // Null RouteInfo (1-argument constructor)
        final RouteSessionInfo nullRouteSessionInfo = null;
        assertThrows(NullPointerException.class,
                () -> new RouteSessionInfo.Builder(nullRouteSessionInfo));
    }

    @Test
    public void testBuilderConstructorWithEmptyClientPackageName() {
        // An empty string for client package name is valid. (for unknown cases)
        // Creating builder with it should not throw any exception.
        RouteSessionInfo.Builder builder = new RouteSessionInfo.Builder(
                TEST_ID, "" /* clientPackageName*/, TEST_ROUTE_FEATURE);
    }

    @Test
    public void testBuilderBuildWithEmptySelectedRoutesThrowsIAE() {
        RouteSessionInfo.Builder builder = new RouteSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME, TEST_ROUTE_FEATURE);
        // Note: Calling build() without adding any selected routes.
        assertThrows(IllegalArgumentException.class, () -> builder.build());
    }

    @Test
    public void testBuilderAddRouteMethodsWithIllegalArgumentsThrowsIAE() {
        RouteSessionInfo.Builder builder = new RouteSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME, TEST_ROUTE_FEATURE);

        final String nullRouteId = null;
        final String emptyRouteId = "";

        assertThrows(IllegalArgumentException.class,
                () -> builder.addSelectedRoute(nullRouteId));
        assertThrows(IllegalArgumentException.class,
                () -> builder.addSelectableRoute(nullRouteId));
        assertThrows(IllegalArgumentException.class,
                () -> builder.addDeselectableRoute(nullRouteId));
        assertThrows(IllegalArgumentException.class,
                () -> builder.addTransferrableRoute(nullRouteId));

        assertThrows(IllegalArgumentException.class,
                () -> builder.addSelectedRoute(emptyRouteId));
        assertThrows(IllegalArgumentException.class,
                () -> builder.addSelectableRoute(emptyRouteId));
        assertThrows(IllegalArgumentException.class,
                () -> builder.addDeselectableRoute(emptyRouteId));
        assertThrows(IllegalArgumentException.class,
                () -> builder.addTransferrableRoute(emptyRouteId));
    }

    @Test
    public void testBuilderRemoveRouteMethodsWithIllegalArgumentsThrowsIAE() {
        RouteSessionInfo.Builder builder = new RouteSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME, TEST_ROUTE_FEATURE);

        final String nullRouteId = null;
        final String emptyRouteId = "";

        assertThrows(IllegalArgumentException.class,
                () -> builder.removeSelectedRoute(nullRouteId));
        assertThrows(IllegalArgumentException.class,
                () -> builder.removeSelectableRoute(nullRouteId));
        assertThrows(IllegalArgumentException.class,
                () -> builder.removeDeselectableRoute(nullRouteId));
        assertThrows(IllegalArgumentException.class,
                () -> builder.removeTransferrableRoute(nullRouteId));

        assertThrows(IllegalArgumentException.class,
                () -> builder.removeSelectedRoute(emptyRouteId));
        assertThrows(IllegalArgumentException.class,
                () -> builder.removeSelectableRoute(emptyRouteId));
        assertThrows(IllegalArgumentException.class,
                () -> builder.removeDeselectableRoute(emptyRouteId));
        assertThrows(IllegalArgumentException.class,
                () -> builder.removeTransferrableRoute(emptyRouteId));
    }

    @Test
    public void testBuilderAndGettersOfRouteSessionInfo() {
        Bundle controlHints = new Bundle();
        controlHints.putString(TEST_KEY, TEST_VALUE);

        RouteSessionInfo sessionInfo = new RouteSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME, TEST_ROUTE_FEATURE)
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .addSelectedRoute(TEST_ROUTE_ID_1)
                .addSelectableRoute(TEST_ROUTE_ID_2)
                .addSelectableRoute(TEST_ROUTE_ID_3)
                .addDeselectableRoute(TEST_ROUTE_ID_4)
                .addDeselectableRoute(TEST_ROUTE_ID_5)
                .addTransferrableRoute(TEST_ROUTE_ID_6)
                .addTransferrableRoute(TEST_ROUTE_ID_7)
                .setControlHints(controlHints)
                .build();

        assertEquals(TEST_ID, sessionInfo.getId());
        assertEquals(TEST_CLIENT_PACKAGE_NAME, sessionInfo.getClientPackageName());
        assertEquals(TEST_ROUTE_FEATURE, sessionInfo.getRouteFeature());

        assertEquals(2, sessionInfo.getSelectedRoutes().size());
        assertEquals(TEST_ROUTE_ID_0, sessionInfo.getSelectedRoutes().get(0));
        assertEquals(TEST_ROUTE_ID_1, sessionInfo.getSelectedRoutes().get(1));

        assertEquals(2, sessionInfo.getSelectableRoutes().size());
        assertEquals(TEST_ROUTE_ID_2, sessionInfo.getSelectableRoutes().get(0));
        assertEquals(TEST_ROUTE_ID_3, sessionInfo.getSelectableRoutes().get(1));

        assertEquals(2, sessionInfo.getDeselectableRoutes().size());
        assertEquals(TEST_ROUTE_ID_4, sessionInfo.getDeselectableRoutes().get(0));
        assertEquals(TEST_ROUTE_ID_5, sessionInfo.getDeselectableRoutes().get(1));

        assertEquals(2, sessionInfo.getTransferrableRoutes().size());
        assertEquals(TEST_ROUTE_ID_6, sessionInfo.getTransferrableRoutes().get(0));
        assertEquals(TEST_ROUTE_ID_7, sessionInfo.getTransferrableRoutes().get(1));

        Bundle controlHintsOut = sessionInfo.getControlHints();
        assertNotNull(controlHintsOut);
        assertTrue(controlHintsOut.containsKey(TEST_KEY));
        assertEquals(TEST_VALUE, controlHintsOut.getString(TEST_KEY));
    }

    @Test
    public void testBuilderAddRouteMethodsWithBuilderCopyConstructor() {
        RouteSessionInfo sessionInfo = new RouteSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME, TEST_ROUTE_FEATURE)
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .addSelectableRoute(TEST_ROUTE_ID_2)
                .addDeselectableRoute(TEST_ROUTE_ID_4)
                .addTransferrableRoute(TEST_ROUTE_ID_6)
                .build();

        RouteSessionInfo newSessionInfo = new RouteSessionInfo.Builder(sessionInfo)
                .addSelectedRoute(TEST_ROUTE_ID_1)
                .addSelectableRoute(TEST_ROUTE_ID_3)
                .addDeselectableRoute(TEST_ROUTE_ID_5)
                .addTransferrableRoute(TEST_ROUTE_ID_7)
                .build();

        assertEquals(2, newSessionInfo.getSelectedRoutes().size());
        assertEquals(TEST_ROUTE_ID_0, newSessionInfo.getSelectedRoutes().get(0));
        assertEquals(TEST_ROUTE_ID_1, newSessionInfo.getSelectedRoutes().get(1));

        assertEquals(2, newSessionInfo.getSelectableRoutes().size());
        assertEquals(TEST_ROUTE_ID_2, newSessionInfo.getSelectableRoutes().get(0));
        assertEquals(TEST_ROUTE_ID_3, newSessionInfo.getSelectableRoutes().get(1));

        assertEquals(2, newSessionInfo.getDeselectableRoutes().size());
        assertEquals(TEST_ROUTE_ID_4, newSessionInfo.getDeselectableRoutes().get(0));
        assertEquals(TEST_ROUTE_ID_5, newSessionInfo.getDeselectableRoutes().get(1));

        assertEquals(2, newSessionInfo.getTransferrableRoutes().size());
        assertEquals(TEST_ROUTE_ID_6, newSessionInfo.getTransferrableRoutes().get(0));
        assertEquals(TEST_ROUTE_ID_7, newSessionInfo.getTransferrableRoutes().get(1));
    }

    @Test
    public void testBuilderRemoveRouteMethods() {
        RouteSessionInfo sessionInfo = new RouteSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME, TEST_ROUTE_FEATURE)
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .addSelectedRoute(TEST_ROUTE_ID_1)
                .removeSelectedRoute(TEST_ROUTE_ID_1)

                .addSelectableRoute(TEST_ROUTE_ID_2)
                .addSelectableRoute(TEST_ROUTE_ID_3)
                .removeSelectableRoute(TEST_ROUTE_ID_3)

                .addDeselectableRoute(TEST_ROUTE_ID_4)
                .addDeselectableRoute(TEST_ROUTE_ID_5)
                .removeDeselectableRoute(TEST_ROUTE_ID_5)

                .addTransferrableRoute(TEST_ROUTE_ID_6)
                .addTransferrableRoute(TEST_ROUTE_ID_7)
                .removeTransferrableRoute(TEST_ROUTE_ID_7)

                .build();

        assertEquals(1, sessionInfo.getSelectedRoutes().size());
        assertEquals(TEST_ROUTE_ID_0, sessionInfo.getSelectedRoutes().get(0));

        assertEquals(1, sessionInfo.getSelectableRoutes().size());
        assertEquals(TEST_ROUTE_ID_2, sessionInfo.getSelectableRoutes().get(0));

        assertEquals(1, sessionInfo.getDeselectableRoutes().size());
        assertEquals(TEST_ROUTE_ID_4, sessionInfo.getDeselectableRoutes().get(0));

        assertEquals(1, sessionInfo.getTransferrableRoutes().size());
        assertEquals(TEST_ROUTE_ID_6, sessionInfo.getTransferrableRoutes().get(0));
    }

    @Test
    public void testBuilderRemoveRouteMethodsWithBuilderCopyConstructor() {
        RouteSessionInfo sessionInfo = new RouteSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME, TEST_ROUTE_FEATURE)
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .addSelectedRoute(TEST_ROUTE_ID_1)
                .addSelectableRoute(TEST_ROUTE_ID_2)
                .addSelectableRoute(TEST_ROUTE_ID_3)
                .addDeselectableRoute(TEST_ROUTE_ID_4)
                .addDeselectableRoute(TEST_ROUTE_ID_5)
                .addTransferrableRoute(TEST_ROUTE_ID_6)
                .addTransferrableRoute(TEST_ROUTE_ID_7)
                .build();

        RouteSessionInfo newSessionInfo = new RouteSessionInfo.Builder(sessionInfo)
                .removeSelectedRoute(TEST_ROUTE_ID_1)
                .removeSelectableRoute(TEST_ROUTE_ID_3)
                .removeDeselectableRoute(TEST_ROUTE_ID_5)
                .removeTransferrableRoute(TEST_ROUTE_ID_7)
                .build();

        assertEquals(1, newSessionInfo.getSelectedRoutes().size());
        assertEquals(TEST_ROUTE_ID_0, newSessionInfo.getSelectedRoutes().get(0));

        assertEquals(1, newSessionInfo.getSelectableRoutes().size());
        assertEquals(TEST_ROUTE_ID_2, newSessionInfo.getSelectableRoutes().get(0));

        assertEquals(1, newSessionInfo.getDeselectableRoutes().size());
        assertEquals(TEST_ROUTE_ID_4, newSessionInfo.getDeselectableRoutes().get(0));

        assertEquals(1, newSessionInfo.getTransferrableRoutes().size());
        assertEquals(TEST_ROUTE_ID_6, newSessionInfo.getTransferrableRoutes().get(0));
    }

    @Test
    public void testBuilderClearRouteMethods() {
        RouteSessionInfo sessionInfo = new RouteSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME, TEST_ROUTE_FEATURE)
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .addSelectedRoute(TEST_ROUTE_ID_1)
                .clearSelectedRoutes()

                .addSelectableRoute(TEST_ROUTE_ID_2)
                .addSelectableRoute(TEST_ROUTE_ID_3)
                .clearSelectableRoutes()

                .addDeselectableRoute(TEST_ROUTE_ID_4)
                .addDeselectableRoute(TEST_ROUTE_ID_5)
                .clearDeselectableRoutes()

                .addTransferrableRoute(TEST_ROUTE_ID_6)
                .addTransferrableRoute(TEST_ROUTE_ID_7)
                .clearTransferrableRoutes()

                // SelectedRoutes must not be empty
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .build();

        assertEquals(1, sessionInfo.getSelectedRoutes().size());
        assertEquals(TEST_ROUTE_ID_0, sessionInfo.getSelectedRoutes().get(0));

        assertTrue(sessionInfo.getSelectableRoutes().isEmpty());
        assertTrue(sessionInfo.getDeselectableRoutes().isEmpty());
        assertTrue(sessionInfo.getTransferrableRoutes().isEmpty());
    }

    @Test
    public void testBuilderClearRouteMethodsWithBuilderCopyConstructor() {
        RouteSessionInfo sessionInfo = new RouteSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME, TEST_ROUTE_FEATURE)
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .addSelectedRoute(TEST_ROUTE_ID_1)
                .addSelectableRoute(TEST_ROUTE_ID_2)
                .addSelectableRoute(TEST_ROUTE_ID_3)
                .addDeselectableRoute(TEST_ROUTE_ID_4)
                .addDeselectableRoute(TEST_ROUTE_ID_5)
                .addTransferrableRoute(TEST_ROUTE_ID_6)
                .addTransferrableRoute(TEST_ROUTE_ID_7)
                .build();

        RouteSessionInfo newSessionInfo = new RouteSessionInfo.Builder(sessionInfo)
                .clearSelectedRoutes()
                .clearSelectableRoutes()
                .clearDeselectableRoutes()
                .clearTransferrableRoutes()
                // SelectedRoutes must not be empty
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .build();

        assertEquals(1, newSessionInfo.getSelectedRoutes().size());
        assertEquals(TEST_ROUTE_ID_0, newSessionInfo.getSelectedRoutes().get(0));

        assertTrue(newSessionInfo.getSelectableRoutes().isEmpty());
        assertTrue(newSessionInfo.getDeselectableRoutes().isEmpty());
        assertTrue(newSessionInfo.getTransferrableRoutes().isEmpty());
    }

    @Test
    public void testEqualsCreatedWithSameArguments() {
        Bundle controlHints = new Bundle();
        controlHints.putString(TEST_KEY, TEST_VALUE);

        RouteSessionInfo sessionInfo1 = new RouteSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME, TEST_ROUTE_FEATURE)
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .addSelectedRoute(TEST_ROUTE_ID_1)
                .addSelectableRoute(TEST_ROUTE_ID_2)
                .addSelectableRoute(TEST_ROUTE_ID_3)
                .addDeselectableRoute(TEST_ROUTE_ID_4)
                .addDeselectableRoute(TEST_ROUTE_ID_5)
                .addTransferrableRoute(TEST_ROUTE_ID_6)
                .addTransferrableRoute(TEST_ROUTE_ID_7)
                .setControlHints(controlHints)
                .build();

        RouteSessionInfo sessionInfo2 = new RouteSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME, TEST_ROUTE_FEATURE)
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .addSelectedRoute(TEST_ROUTE_ID_1)
                .addSelectableRoute(TEST_ROUTE_ID_2)
                .addSelectableRoute(TEST_ROUTE_ID_3)
                .addDeselectableRoute(TEST_ROUTE_ID_4)
                .addDeselectableRoute(TEST_ROUTE_ID_5)
                .addTransferrableRoute(TEST_ROUTE_ID_6)
                .addTransferrableRoute(TEST_ROUTE_ID_7)
                .setControlHints(controlHints)
                .build();

        assertEquals(sessionInfo1, sessionInfo2);
        assertEquals(sessionInfo1.hashCode(), sessionInfo2.hashCode());
    }

    @Test
    public void testEqualsCreatedWithBuilderCopyConstructor() {
        Bundle controlHints = new Bundle();
        controlHints.putString(TEST_KEY, TEST_VALUE);

        RouteSessionInfo sessionInfo1 = new RouteSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME, TEST_ROUTE_FEATURE)
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .addSelectedRoute(TEST_ROUTE_ID_1)
                .addSelectableRoute(TEST_ROUTE_ID_2)
                .addSelectableRoute(TEST_ROUTE_ID_3)
                .addDeselectableRoute(TEST_ROUTE_ID_4)
                .addDeselectableRoute(TEST_ROUTE_ID_5)
                .addTransferrableRoute(TEST_ROUTE_ID_6)
                .addTransferrableRoute(TEST_ROUTE_ID_7)
                .setControlHints(controlHints)
                .build();

        RouteSessionInfo sessionInfo2 = new RouteSessionInfo.Builder(sessionInfo1).build();

        assertEquals(sessionInfo1, sessionInfo2);
        assertEquals(sessionInfo1.hashCode(), sessionInfo2.hashCode());
    }

    @Test
    public void testEqualsReturnFalse() {
        Bundle controlHints = new Bundle();
        controlHints.putString(TEST_KEY, TEST_VALUE);

        RouteSessionInfo sessionInfo = new RouteSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME, TEST_ROUTE_FEATURE)
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .addSelectedRoute(TEST_ROUTE_ID_1)
                .addSelectableRoute(TEST_ROUTE_ID_2)
                .addSelectableRoute(TEST_ROUTE_ID_3)
                .addDeselectableRoute(TEST_ROUTE_ID_4)
                .addDeselectableRoute(TEST_ROUTE_ID_5)
                .addTransferrableRoute(TEST_ROUTE_ID_6)
                .addTransferrableRoute(TEST_ROUTE_ID_7)
                .setControlHints(controlHints)
                .build();

        // Now, we will use copy constructor
        assertNotEquals(sessionInfo, new RouteSessionInfo.Builder(sessionInfo)
                .addSelectedRoute("randomRoute")
                .build());
        assertNotEquals(sessionInfo, new RouteSessionInfo.Builder(sessionInfo)
                .addSelectableRoute("randomRoute")
                .build());
        assertNotEquals(sessionInfo, new RouteSessionInfo.Builder(sessionInfo)
                .addDeselectableRoute("randomRoute")
                .build());
        assertNotEquals(sessionInfo, new RouteSessionInfo.Builder(sessionInfo)
                .addTransferrableRoute("randomRoute")
                .build());

        assertNotEquals(sessionInfo, new RouteSessionInfo.Builder(sessionInfo)
                .removeSelectedRoute(TEST_ROUTE_ID_1)
                .build());
        assertNotEquals(sessionInfo, new RouteSessionInfo.Builder(sessionInfo)
                .removeSelectableRoute(TEST_ROUTE_ID_3)
                .build());
        assertNotEquals(sessionInfo, new RouteSessionInfo.Builder(sessionInfo)
                .removeDeselectableRoute(TEST_ROUTE_ID_5)
                .build());
        assertNotEquals(sessionInfo, new RouteSessionInfo.Builder(sessionInfo)
                .removeTransferrableRoute(TEST_ROUTE_ID_7)
                .build());

        assertNotEquals(sessionInfo, new RouteSessionInfo.Builder(sessionInfo)
                .clearSelectedRoutes()
                // Note: Calling build() with empty selected routes will throw IAE.
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .build());
        assertNotEquals(sessionInfo, new RouteSessionInfo.Builder(sessionInfo)
                .clearSelectableRoutes()
                .build());
        assertNotEquals(sessionInfo, new RouteSessionInfo.Builder(sessionInfo)
                .clearDeselectableRoutes()
                .build());
        assertNotEquals(sessionInfo, new RouteSessionInfo.Builder(sessionInfo)
                .clearTransferrableRoutes()
                .build());

        // Note: ControlHints will not affect the equals.
    }

    @Test
    public void testParcelingAndUnParceling() {
        Bundle controlHints = new Bundle();
        controlHints.putString(TEST_KEY, TEST_VALUE);

        RouteSessionInfo sessionInfo = new RouteSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME, TEST_ROUTE_FEATURE)
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .addSelectedRoute(TEST_ROUTE_ID_1)
                .addSelectableRoute(TEST_ROUTE_ID_2)
                .addSelectableRoute(TEST_ROUTE_ID_3)
                .addDeselectableRoute(TEST_ROUTE_ID_4)
                .addDeselectableRoute(TEST_ROUTE_ID_5)
                .addTransferrableRoute(TEST_ROUTE_ID_6)
                .addTransferrableRoute(TEST_ROUTE_ID_7)
                .setControlHints(controlHints)
                .build();

        Parcel parcel = Parcel.obtain();
        sessionInfo.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        RouteSessionInfo sessionInfoFromParcel = RouteSessionInfo.CREATOR.createFromParcel(parcel);
        assertEquals(sessionInfo, sessionInfoFromParcel);
        assertEquals(sessionInfo.hashCode(), sessionInfoFromParcel.hashCode());

        // Check controlHints
        Bundle controlHintsOut = sessionInfoFromParcel.getControlHints();
        assertNotNull(controlHintsOut);
        assertTrue(controlHintsOut.containsKey(TEST_KEY));
        assertEquals(TEST_VALUE, controlHintsOut.getString(TEST_KEY));
    }
}
