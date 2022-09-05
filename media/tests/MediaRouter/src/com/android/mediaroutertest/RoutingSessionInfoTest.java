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

import static com.google.common.truth.Truth.assertThat;

import android.content.res.Resources;
import android.media.MediaRoute2Info;
import android.media.RoutingSessionInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

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

        assertThat(sessionInfoWithProviderId.getSelectedRoutes())
                .isNotEqualTo(sessionInfo.getSelectedRoutes());
        assertThat(sessionInfoWithProviderId.getSelectableRoutes())
                .isNotEqualTo(sessionInfo.getSelectableRoutes());
        assertThat(sessionInfoWithProviderId.getDeselectableRoutes())
                .isNotEqualTo(sessionInfo.getDeselectableRoutes());
        assertThat(sessionInfoWithProviderId.getTransferableRoutes())
                .isNotEqualTo(sessionInfo.getTransferableRoutes());

        RoutingSessionInfo sessionInfoWithOtherProviderId =
                new RoutingSessionInfo.Builder(sessionInfoWithProviderId)
                        .setProviderId(TEST_OTHER_PROVIDER_ID).build();

        assertThat(sessionInfoWithOtherProviderId.getSelectedRoutes())
                .isNotEqualTo(sessionInfoWithProviderId.getSelectedRoutes());
        assertThat(sessionInfoWithOtherProviderId.getSelectableRoutes())
                .isNotEqualTo(sessionInfoWithProviderId.getSelectableRoutes());
        assertThat(sessionInfoWithOtherProviderId.getDeselectableRoutes())
                .isNotEqualTo(sessionInfoWithProviderId.getDeselectableRoutes());
        assertThat(sessionInfoWithOtherProviderId.getTransferableRoutes())
                .isNotEqualTo(sessionInfoWithProviderId.getTransferableRoutes());

        RoutingSessionInfo sessionInfoWithProviderId2 =
                new RoutingSessionInfo.Builder(sessionInfoWithProviderId).build();

        assertThat(sessionInfoWithProviderId2.getSelectedRoutes())
                .isEqualTo(sessionInfoWithProviderId.getSelectedRoutes());
        assertThat(sessionInfoWithProviderId2.getSelectableRoutes())
                .isEqualTo(sessionInfoWithProviderId.getSelectableRoutes());
        assertThat(sessionInfoWithProviderId2.getDeselectableRoutes())
                .isEqualTo(sessionInfoWithProviderId.getDeselectableRoutes());
        assertThat(sessionInfoWithProviderId2.getTransferableRoutes())
                .isEqualTo(sessionInfoWithProviderId.getTransferableRoutes());
    }

    @Test
    public void testGetVolumeHandlingGroupSession() {
        RoutingSessionInfo sessionInfo = new RoutingSessionInfo.Builder(
                TEST_ID, TEST_CLIENT_PACKAGE_NAME)
                .setName(TEST_NAME)
                .addSelectedRoute(TEST_ROUTE_ID_0)
                .addSelectedRoute(TEST_ROUTE_ID_2)
                .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                .build();

        boolean volumeAdjustmentForRemoteGroupSessions = Resources.getSystem().getBoolean(
                com.android.internal.R.bool.config_volumeAdjustmentForRemoteGroupSessions);

        int expectedResult = volumeAdjustmentForRemoteGroupSessions
                ? MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE :
                MediaRoute2Info.PLAYBACK_VOLUME_FIXED;

        assertThat(sessionInfo.getVolumeHandling()).isEqualTo(expectedResult);
    }
}
