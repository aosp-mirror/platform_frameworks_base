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

package android.app.time;

import static android.app.time.Capabilities.CAPABILITY_NOT_ALLOWED;
import static android.app.time.Capabilities.CAPABILITY_POSSESSED;
import static android.app.time.ParcelableTestSupport.assertRoundTripParcelable;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class TimeZoneCapabilitiesTest {

    private static final UserHandle TEST_USER_HANDLE = UserHandle.of(12345);

    @Test
    public void testEquals() {
        TimeZoneCapabilities.Builder builder1 = new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setUseLocationEnabled(true)
                .setConfigureGeoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSetManualTimeZoneCapability(CAPABILITY_POSSESSED);
        TimeZoneCapabilities.Builder builder2 = new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setUseLocationEnabled(true)
                .setConfigureGeoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSetManualTimeZoneCapability(CAPABILITY_POSSESSED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertEquals(one, two);
        }

        builder2.setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertEquals(one, two);
        }

        builder2.setUseLocationEnabled(false);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setUseLocationEnabled(false);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertEquals(one, two);
        }

        builder2.setConfigureGeoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setConfigureGeoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertEquals(one, two);
        }

        builder2.setSetManualTimeZoneCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setSetManualTimeZoneCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertEquals(one, two);
        }
    }

    @Test
    public void testParcelable() {
        TimeZoneCapabilities.Builder builder = new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setUseLocationEnabled(true)
                .setConfigureGeoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSetManualTimeZoneCapability(CAPABILITY_POSSESSED);
        assertRoundTripParcelable(builder.build());

        builder.setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED);
        assertRoundTripParcelable(builder.build());

        builder.setUseLocationEnabled(false);
        assertRoundTripParcelable(builder.build());

        builder.setConfigureGeoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED);
        assertRoundTripParcelable(builder.build());

        builder.setSetManualTimeZoneCapability(CAPABILITY_NOT_ALLOWED);
        assertRoundTripParcelable(builder.build());
    }

    @Test
    public void testTryApplyConfigChanges_permitted() {
        TimeZoneConfiguration oldConfiguration =
                new TimeZoneConfiguration.Builder()
                        .setAutoDetectionEnabled(true)
                        .setGeoDetectionEnabled(true)
                        .build();
        TimeZoneCapabilities capabilities = new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setUseLocationEnabled(true)
                .setConfigureGeoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSetManualTimeZoneCapability(CAPABILITY_POSSESSED)
                .build();

        TimeZoneConfiguration configChange = new TimeZoneConfiguration.Builder()
                .setAutoDetectionEnabled(false)
                .build();

        TimeZoneConfiguration expected = new TimeZoneConfiguration.Builder(oldConfiguration)
                .setAutoDetectionEnabled(false)
                .build();
        assertEquals(expected, capabilities.tryApplyConfigChanges(oldConfiguration, configChange));
    }

    @Test
    public void testTryApplyConfigChanges_notPermitted() {
        TimeZoneConfiguration oldConfiguration =
                new TimeZoneConfiguration.Builder()
                        .setAutoDetectionEnabled(true)
                        .setGeoDetectionEnabled(true)
                        .build();
        TimeZoneCapabilities capabilities = new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                .setUseLocationEnabled(true)
                .setConfigureGeoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                .setSetManualTimeZoneCapability(CAPABILITY_NOT_ALLOWED)
                .build();

        TimeZoneConfiguration configChange = new TimeZoneConfiguration.Builder()
                .setAutoDetectionEnabled(false)
                .build();

        assertNull(capabilities.tryApplyConfigChanges(oldConfiguration, configChange));
    }

    @Test
    public void copyBuilder_copiesAllFields() {
        TimeZoneCapabilities capabilities = new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                .setUseLocationEnabled(true)
                .setConfigureGeoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                .setSetManualTimeZoneCapability(CAPABILITY_NOT_ALLOWED)
                .build();

        {
            TimeZoneCapabilities updatedCapabilities =
                    new TimeZoneCapabilities.Builder(capabilities)
                            .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                            .build();
            TimeZoneCapabilities expectedCapabilities =
                    new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                            .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                            .setUseLocationEnabled(true)
                            .setConfigureGeoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                            .setSetManualTimeZoneCapability(CAPABILITY_NOT_ALLOWED)
                            .build();

            assertThat(updatedCapabilities).isEqualTo(expectedCapabilities);
        }

        {
            TimeZoneCapabilities updatedCapabilities =
                    new TimeZoneCapabilities.Builder(capabilities)
                            .setUseLocationEnabled(false)
                            .build();

            TimeZoneCapabilities expectedCapabilities =
                    new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                            .setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                            .setUseLocationEnabled(false)
                            .setConfigureGeoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                            .setSetManualTimeZoneCapability(CAPABILITY_NOT_ALLOWED)
                            .build();

            assertThat(updatedCapabilities).isEqualTo(expectedCapabilities);
        }

        {
            TimeZoneCapabilities updatedCapabilities =
                    new TimeZoneCapabilities.Builder(capabilities)
                            .setConfigureGeoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                            .build();

            TimeZoneCapabilities expectedCapabilities =
                    new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                            .setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                            .setUseLocationEnabled(true)
                            .setConfigureGeoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                            .setSetManualTimeZoneCapability(CAPABILITY_NOT_ALLOWED)
                            .build();

            assertThat(updatedCapabilities).isEqualTo(expectedCapabilities);
        }

        {
            TimeZoneCapabilities updatedCapabilities =
                    new TimeZoneCapabilities.Builder(capabilities)
                            .setSetManualTimeZoneCapability(CAPABILITY_POSSESSED)
                            .build();

            TimeZoneCapabilities expectedCapabilities =
                    new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                            .setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                            .setUseLocationEnabled(true)
                            .setConfigureGeoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                            .setSetManualTimeZoneCapability(CAPABILITY_POSSESSED)
                            .build();

            assertThat(updatedCapabilities).isEqualTo(expectedCapabilities);
        }
    }
}
