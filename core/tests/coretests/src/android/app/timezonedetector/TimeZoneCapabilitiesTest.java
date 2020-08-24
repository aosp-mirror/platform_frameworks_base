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

package android.app.timezonedetector;

import static android.app.timezonedetector.ParcelableTestSupport.assertRoundTripParcelable;
import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_NOT_ALLOWED;
import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_POSSESSED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TimeZoneCapabilitiesTest {

    private static final int ARBITRARY_USER_ID = 12345;

    @Test
    public void testEquals() {
        TimeZoneConfiguration configuration1 = new TimeZoneConfiguration.Builder(ARBITRARY_USER_ID)
                .setAutoDetectionEnabled(true)
                .setGeoDetectionEnabled(true)
                .build();
        TimeZoneConfiguration configuration2 = new TimeZoneConfiguration.Builder(ARBITRARY_USER_ID)
                .setAutoDetectionEnabled(false)
                .setGeoDetectionEnabled(false)
                .build();

        TimeZoneCapabilities.Builder builder1 = new TimeZoneCapabilities.Builder()
                .setConfiguration(configuration1)
                .setConfigureAutoDetectionEnabled(CAPABILITY_POSSESSED)
                .setConfigureGeoDetectionEnabled(CAPABILITY_POSSESSED)
                .setSuggestManualTimeZone(CAPABILITY_POSSESSED);
        TimeZoneCapabilities.Builder builder2 = new TimeZoneCapabilities.Builder()
                .setConfiguration(configuration1)
                .setConfigureAutoDetectionEnabled(CAPABILITY_POSSESSED)
                .setConfigureGeoDetectionEnabled(CAPABILITY_POSSESSED)
                .setSuggestManualTimeZone(CAPABILITY_POSSESSED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertEquals(one, two);
        }

        builder2.setConfiguration(configuration2);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setConfiguration(configuration2);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertEquals(one, two);
        }

        builder2.setConfigureAutoDetectionEnabled(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setConfigureAutoDetectionEnabled(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertEquals(one, two);
        }

        builder2.setConfigureGeoDetectionEnabled(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setConfigureGeoDetectionEnabled(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertEquals(one, two);
        }

        builder2.setSuggestManualTimeZone(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setSuggestManualTimeZone(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertEquals(one, two);
        }
    }

    @Test
    public void testParcelable() {
        TimeZoneConfiguration configuration = new TimeZoneConfiguration.Builder(ARBITRARY_USER_ID)
                .setAutoDetectionEnabled(true)
                .setGeoDetectionEnabled(true)
                .build();
        TimeZoneCapabilities.Builder builder = new TimeZoneCapabilities.Builder()
                .setConfiguration(configuration)
                .setConfigureAutoDetectionEnabled(CAPABILITY_POSSESSED)
                .setConfigureGeoDetectionEnabled(CAPABILITY_POSSESSED)
                .setSuggestManualTimeZone(CAPABILITY_POSSESSED);
        assertRoundTripParcelable(builder.build());

        builder.setConfigureAutoDetectionEnabled(CAPABILITY_NOT_ALLOWED);
        assertRoundTripParcelable(builder.build());

        builder.setConfigureGeoDetectionEnabled(CAPABILITY_NOT_ALLOWED);
        assertRoundTripParcelable(builder.build());

        builder.setSuggestManualTimeZone(CAPABILITY_NOT_ALLOWED);
        assertRoundTripParcelable(builder.build());
    }

    @Test
    public void testApplyUpdate_permitted() {
        TimeZoneConfiguration oldConfiguration =
                new TimeZoneConfiguration.Builder(ARBITRARY_USER_ID)
                        .setAutoDetectionEnabled(true)
                        .setGeoDetectionEnabled(true)
                        .build();
        TimeZoneCapabilities capabilities = new TimeZoneCapabilities.Builder()
                .setConfiguration(oldConfiguration)
                .setConfigureAutoDetectionEnabled(CAPABILITY_POSSESSED)
                .setConfigureGeoDetectionEnabled(CAPABILITY_POSSESSED)
                .setSuggestManualTimeZone(CAPABILITY_POSSESSED)
                .build();
        assertEquals(oldConfiguration, capabilities.getConfiguration());

        TimeZoneConfiguration configChange = new TimeZoneConfiguration.Builder(ARBITRARY_USER_ID)
                .setAutoDetectionEnabled(false)
                .build();

        TimeZoneConfiguration expected = new TimeZoneConfiguration.Builder(oldConfiguration)
                .setAutoDetectionEnabled(false)
                .build();
        assertEquals(expected, capabilities.applyUpdate(configChange));
    }

    @Test
    public void testApplyUpdate_notPermitted() {
        TimeZoneConfiguration oldConfiguration =
                new TimeZoneConfiguration.Builder(ARBITRARY_USER_ID)
                        .setAutoDetectionEnabled(true)
                        .setGeoDetectionEnabled(true)
                        .build();
        TimeZoneCapabilities capabilities = new TimeZoneCapabilities.Builder()
                .setConfiguration(oldConfiguration)
                .setConfigureAutoDetectionEnabled(CAPABILITY_NOT_ALLOWED)
                .setConfigureGeoDetectionEnabled(CAPABILITY_NOT_ALLOWED)
                .setSuggestManualTimeZone(CAPABILITY_NOT_ALLOWED)
                .build();
        assertEquals(oldConfiguration, capabilities.getConfiguration());

        TimeZoneConfiguration configChange = new TimeZoneConfiguration.Builder(ARBITRARY_USER_ID)
                .setAutoDetectionEnabled(false)
                .build();

        assertNull(capabilities.applyUpdate(configChange));
    }
}
