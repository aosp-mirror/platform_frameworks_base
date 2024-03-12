/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.devicepolicy;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Test for {@link DevicePolicyConstants}.
 *
 * <p>Run this test with:
 *
 * {@code atest FrameworksServicesTests:com.android.server.devicepolicy.DevicePolicyConstantsTest}
 */
@SmallTest
public class DevicePolicyConstantsTest {
    private static final String TAG = "DevicePolicyConstantsTest";

    @Test
    public void testDefaultValues() throws Exception {
        final DevicePolicyConstants constants = DevicePolicyConstants.loadFromString("");

        assertThat(constants.DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC).isEqualTo(1 * 60 * 60);
        assertThat(constants.DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC).isEqualTo(24 * 60 * 60);
        assertThat(constants.DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE).isWithin(1.0e-10).of(2.0);
    }

    @Test
    public void testCustomValues() throws Exception {
        final DevicePolicyConstants constants = DevicePolicyConstants.loadFromString(
                "das_died_service_reconnect_backoff_sec=10,"
                + "das_died_service_reconnect_backoff_increase=1.25,"
                + "das_died_service_reconnect_max_backoff_sec=15"
        );

        assertThat(constants.DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC).isEqualTo(10);
        assertThat(constants.DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC).isEqualTo(15);
        assertThat(constants.DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE).isWithin(1.0e-10)
                .of(1.25);
    }

    @Test
    public void testMinMax() throws Exception {
        final DevicePolicyConstants constants = DevicePolicyConstants.loadFromString(
                "das_died_service_reconnect_backoff_sec=3,"
                        + "das_died_service_reconnect_backoff_increase=.25,"
                        + "das_died_service_reconnect_max_backoff_sec=1"
        );

        assertThat(constants.DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC).isEqualTo(5);
        assertThat(constants.DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC).isEqualTo(5);
        assertThat(constants.DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE).isWithin(1.0e-10).of(1.0);
    }
}
