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


import static android.app.time.Capabilities.CAPABILITY_NOT_ALLOWED;
import static android.app.time.Capabilities.CAPABILITY_NOT_APPLICABLE;
import static android.app.time.Capabilities.CAPABILITY_NOT_SUPPORTED;
import static android.app.time.Capabilities.CAPABILITY_POSSESSED;
import static android.app.time.ParcelableTestSupport.assertEqualsAndHashCode;
import static android.app.time.ParcelableTestSupport.assertRoundTripParcelable;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class TimeCapabilitiesTest {

    private static final UserHandle TEST_USER_HANDLE = UserHandle.of(332211);

    @Test
    public void testEquals() {
        TimeCapabilities.Builder builder1 = new TimeCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSetManualTimeCapability(CAPABILITY_POSSESSED);
        TimeCapabilities.Builder builder2 = new TimeCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSetManualTimeCapability(CAPABILITY_POSSESSED);
        {
            TimeCapabilities one = builder1.build();
            TimeCapabilities two = builder2.build();
            assertEqualsAndHashCode(one, two);
        }

        builder2.setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeCapabilities one = builder1.build();
            TimeCapabilities two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeCapabilities one = builder1.build();
            TimeCapabilities two = builder2.build();
            assertEqualsAndHashCode(one, two);
        }

        builder2.setSetManualTimeCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeCapabilities one = builder1.build();
            TimeCapabilities two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setSetManualTimeCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeCapabilities one = builder1.build();
            TimeCapabilities two = builder2.build();
            assertEqualsAndHashCode(one, two);
        }
    }

    @Test
    public void userHandle_notIgnoredInEquals() {
        TimeCapabilities firstUserCapabilities = new TimeCapabilities.Builder(UserHandle.of(1))
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSetManualTimeCapability(CAPABILITY_POSSESSED)
                .build();

        TimeCapabilities secondUserCapabilities = new TimeCapabilities.Builder(UserHandle.of(2))
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSetManualTimeCapability(CAPABILITY_POSSESSED)
                .build();

        assertThat(firstUserCapabilities).isNotEqualTo(secondUserCapabilities);
    }

    @Test
    public void testBuilder() {
        TimeCapabilities capabilities = new TimeCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_APPLICABLE)
                .setSetManualTimeCapability(CAPABILITY_NOT_SUPPORTED)
                .build();

        assertThat(capabilities.getConfigureAutoDetectionEnabledCapability())
                .isEqualTo(CAPABILITY_NOT_APPLICABLE);
        assertThat(capabilities.getSetManualTimeCapability())
                .isEqualTo(CAPABILITY_NOT_SUPPORTED);

        try {
            new TimeCapabilities.Builder(TEST_USER_HANDLE)
                    .build();
            fail("Should throw IllegalStateException");
        } catch (IllegalStateException ignored) {
            // expected
        }

        try {
            new TimeCapabilities.Builder(TEST_USER_HANDLE)
                    .setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_APPLICABLE)
                    .build();
            fail("Should throw IllegalStateException");
        } catch (IllegalStateException ignored) {
            // expected
        }

        try {
            new TimeCapabilities.Builder(TEST_USER_HANDLE)
                    .setSetManualTimeCapability(CAPABILITY_NOT_APPLICABLE)
                    .build();
            fail("Should throw IllegalStateException");
        } catch (IllegalStateException ignored) {
            // expected
        }
    }

    @Test
    public void testParcelable() {
        TimeCapabilities.Builder builder = new TimeCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_SUPPORTED)
                .setSetManualTimeCapability(CAPABILITY_NOT_SUPPORTED);

        assertRoundTripParcelable(builder.build());

        builder.setSetManualTimeCapability(CAPABILITY_POSSESSED);
        assertRoundTripParcelable(builder.build());

        builder.setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED);
        assertRoundTripParcelable(builder.build());
    }

    @Test
    public void testTryApplyConfigChanges_permitted() {
        TimeConfiguration oldConfiguration =
                new TimeConfiguration.Builder()
                        .setAutoDetectionEnabled(true)
                        .build();
        TimeCapabilities capabilities = new TimeCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSetManualTimeCapability(CAPABILITY_POSSESSED)
                .build();

        TimeConfiguration configChange = new TimeConfiguration.Builder()
                .setAutoDetectionEnabled(false)
                .build();

        TimeConfiguration expected = new TimeConfiguration.Builder(oldConfiguration)
                .setAutoDetectionEnabled(false)
                .build();
        assertEquals(expected, capabilities.tryApplyConfigChanges(oldConfiguration, configChange));
    }

    @Test
    public void testTryApplyConfigChanges_notPermitted() {
        TimeConfiguration oldConfiguration =
                new TimeConfiguration.Builder()
                        .setAutoDetectionEnabled(true)
                        .build();
        TimeCapabilities capabilities = new TimeCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                .setSetManualTimeCapability(CAPABILITY_NOT_ALLOWED)
                .build();

        TimeConfiguration configChange = new TimeConfiguration.Builder()
                .setAutoDetectionEnabled(false)
                .build();

        assertNull(capabilities.tryApplyConfigChanges(oldConfiguration, configChange));
    }

    @Test
    public void copyBuilder_copiesAllFields() {
        TimeCapabilities capabilities = new TimeCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                .setSetManualTimeCapability(CAPABILITY_NOT_ALLOWED)
                .build();

        {
            TimeCapabilities updatedCapabilities =
                    new TimeCapabilities.Builder(capabilities)
                            .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                            .build();
            TimeCapabilities expectedCapabilities =
                    new TimeCapabilities.Builder(TEST_USER_HANDLE)
                            .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                            .setSetManualTimeCapability(CAPABILITY_NOT_ALLOWED)
                            .build();

            assertThat(updatedCapabilities).isEqualTo(expectedCapabilities);
        }

        {
            TimeCapabilities updatedCapabilities =
                    new TimeCapabilities.Builder(capabilities)
                            .setSetManualTimeCapability(CAPABILITY_POSSESSED)
                            .build();

            TimeCapabilities expectedCapabilities =
                    new TimeCapabilities.Builder(TEST_USER_HANDLE)
                            .setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                            .setSetManualTimeCapability(CAPABILITY_POSSESSED)
                            .build();

            assertThat(updatedCapabilities).isEqualTo(expectedCapabilities);
        }
    }
}
