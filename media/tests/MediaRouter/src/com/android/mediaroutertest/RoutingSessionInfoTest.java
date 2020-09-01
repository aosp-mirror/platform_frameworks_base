/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.media.RoutingSessionInfo;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests {@link RoutingSessionInfo} and its {@link RoutingSessionInfo.Builder builder}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class RoutingSessionInfoTest {
    public static final String TEST_ID = "test_id";
    public static final String TEST_CLIENT_PACKAGE_NAME = "com.test.client.package.name";
    public static final String TEST_NAME = "test_name";

    public static final String TEST_ROUTE_ID_0 = "test_route_type_0";
    public static final String TEST_ROUTE_ID_2 = "test_route_type_2";
    public static final String TEST_ROUTE_ID_4 = "test_route_type_4";
    public static final String TEST_ROUTE_ID_6 = "test_route_type_6";

    public static final String TEST_PROVIDER_ID = "test_provider_id";
    public static final String TEST_OTHER_PROVIDER_ID = "test_other_provider_id";

    // Tests if route IDs are changed properly according to provider ID.
    @Test
    public void testProviderId() {
        RoutingSessionInfo sessionInfo = new RoutingSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME)
                .setName(TEST_NAME)
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .addSelectableRoute(TEST_ROUTE_ID_2)
                .addDeselectableRoute(TEST_ROUTE_ID_4)
                .addTransferableRoute(TEST_ROUTE_ID_6)
                .build();

        RoutingSessionInfo sessionInfoWithProviderId = new RoutingSessionInfo.Builder(sessionInfo)
                .setProviderId(TEST_PROVIDER_ID).build();

        assertNotEquals(sessionInfo.getSelectedRoutes(),
                sessionInfoWithProviderId.getSelectedRoutes());
        assertNotEquals(sessionInfo.getSelectableRoutes(),
                sessionInfoWithProviderId.getSelectableRoutes());
        assertNotEquals(sessionInfo.getDeselectableRoutes(),
                sessionInfoWithProviderId.getDeselectableRoutes());
        assertNotEquals(sessionInfo.getTransferableRoutes(),
                sessionInfoWithProviderId.getTransferableRoutes());

        RoutingSessionInfo sessionInfoWithOtherProviderId =
                new RoutingSessionInfo.Builder(sessionInfoWithProviderId)
                        .setProviderId(TEST_OTHER_PROVIDER_ID).build();

        assertNotEquals(sessionInfoWithOtherProviderId.getSelectedRoutes(),
                sessionInfoWithProviderId.getSelectedRoutes());
        assertNotEquals(sessionInfoWithOtherProviderId.getSelectableRoutes(),
                sessionInfoWithProviderId.getSelectableRoutes());
        assertNotEquals(sessionInfoWithOtherProviderId.getDeselectableRoutes(),
                sessionInfoWithProviderId.getDeselectableRoutes());
        assertNotEquals(sessionInfoWithOtherProviderId.getTransferableRoutes(),
                sessionInfoWithProviderId.getTransferableRoutes());

        RoutingSessionInfo sessionInfoWithProviderId2 =
                new RoutingSessionInfo.Builder(sessionInfoWithProviderId).build();

        assertEquals(sessionInfoWithProviderId2.getSelectedRoutes(),
                sessionInfoWithProviderId.getSelectedRoutes());
        assertEquals(sessionInfoWithProviderId2.getSelectableRoutes(),
                sessionInfoWithProviderId.getSelectableRoutes());
        assertEquals(sessionInfoWithProviderId2.getDeselectableRoutes(),
                sessionInfoWithProviderId.getDeselectableRoutes());
        assertEquals(sessionInfoWithProviderId2.getTransferableRoutes(),
                sessionInfoWithProviderId.getTransferableRoutes());
    }
}
