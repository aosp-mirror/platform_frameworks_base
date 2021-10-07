/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app.time;


import static android.app.time.Capabilities.CAPABILITY_NOT_APPLICABLE;
import static android.app.time.Capabilities.CAPABILITY_NOT_SUPPORTED;
import static android.app.time.Capabilities.CAPABILITY_POSSESSED;
import static android.app.timezonedetector.ParcelableTestSupport.assertRoundTripParcelable;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.os.UserHandle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TimeCapabilitiesTest {

    private static final UserHandle USER_HANDLE = UserHandle.of(332211);

    @Test
    public void testBuilder() {
        TimeCapabilities capabilities = new TimeCapabilities.Builder(USER_HANDLE)
                .setConfigureAutoTimeDetectionEnabledCapability(CAPABILITY_NOT_APPLICABLE)
                .setSuggestTimeManuallyCapability(CAPABILITY_NOT_SUPPORTED)
                .build();

        assertThat(capabilities.getConfigureAutoTimeDetectionEnabledCapability())
                .isEqualTo(CAPABILITY_NOT_APPLICABLE);
        assertThat(capabilities.getSuggestTimeManuallyCapability())
                .isEqualTo(CAPABILITY_NOT_SUPPORTED);

        try {
            new TimeCapabilities.Builder(USER_HANDLE)
                    .build();
            fail("Should throw IllegalStateException");
        } catch (IllegalStateException ignored) {
            // expected
        }

        try {
            new TimeCapabilities.Builder(USER_HANDLE)
                    .setConfigureAutoTimeDetectionEnabledCapability(CAPABILITY_NOT_APPLICABLE)
                    .build();
            fail("Should throw IllegalStateException");
        } catch (IllegalStateException ignored) {
            // expected
        }

        try {
            new TimeCapabilities.Builder(USER_HANDLE)
                    .setSuggestTimeManuallyCapability(CAPABILITY_NOT_APPLICABLE)
                    .build();
            fail("Should throw IllegalStateException");
        } catch (IllegalStateException ignored) {
            // expected
        }
    }

    @Test
    public void userHandle_notIgnoredInEquals() {
        TimeCapabilities firstUserCapabilities = new TimeCapabilities.Builder(UserHandle.of(1))
                .setConfigureAutoTimeDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSuggestTimeManuallyCapability(CAPABILITY_POSSESSED)
                .build();

        TimeCapabilities secondUserCapabilities = new TimeCapabilities.Builder(UserHandle.of(2))
                .setConfigureAutoTimeDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSuggestTimeManuallyCapability(CAPABILITY_POSSESSED)
                .build();

        assertThat(firstUserCapabilities).isNotEqualTo(secondUserCapabilities);
    }

    @Test
    public void testParcelable() {
        TimeCapabilities.Builder builder = new TimeCapabilities.Builder(USER_HANDLE)
                .setConfigureAutoTimeDetectionEnabledCapability(CAPABILITY_NOT_SUPPORTED)
                .setSuggestTimeManuallyCapability(CAPABILITY_NOT_SUPPORTED);

        assertRoundTripParcelable(builder.build());

        builder.setSuggestTimeManuallyCapability(CAPABILITY_POSSESSED);
        assertRoundTripParcelable(builder.build());

        builder.setConfigureAutoTimeDetectionEnabledCapability(CAPABILITY_POSSESSED);
        assertRoundTripParcelable(builder.build());
    }

}
