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

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Test for {@link DevicePolicyConstants}.
 *
 m FrameworksServicesTests &&
 adb install \
 -r ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.devicepolicy.DevicePolicyConstantsTest \
 -w com.android.frameworks.servicestests


 -w com.android.frameworks.servicestests/androidx.test.runner.AndroidJUnitRunner
 */
@SmallTest
public class DevicePolicyConstantsTest extends AndroidTestCase {
    private static final String TAG = "DevicePolicyConstantsTest";

    public void testDefaultValues() throws Exception {
        final DevicePolicyConstants constants = DevicePolicyConstants.loadFromString("");

        assertEquals(1 * 60 * 60, constants.DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC);
        assertEquals(24 * 60 * 60, constants.DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC);
        assertEquals(2.0, constants.DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE);
    }

    public void testCustomValues() throws Exception {
        final DevicePolicyConstants constants = DevicePolicyConstants.loadFromString(
                "das_died_service_reconnect_backoff_sec=10,"
                + "das_died_service_reconnect_backoff_increase=1.25,"
                + "das_died_service_reconnect_max_backoff_sec=15"
        );

        assertEquals(10, constants.DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC);
        assertEquals(15, constants.DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC);
        assertEquals(1.25, constants.DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE);
    }

    public void testMinMax() throws Exception {
        final DevicePolicyConstants constants = DevicePolicyConstants.loadFromString(
                "das_died_service_reconnect_backoff_sec=3,"
                        + "das_died_service_reconnect_backoff_increase=.25,"
                        + "das_died_service_reconnect_max_backoff_sec=1"
        );

        assertEquals(5, constants.DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC);
        assertEquals(5, constants.DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC);
        assertEquals(1.0, constants.DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE);
    }
}
