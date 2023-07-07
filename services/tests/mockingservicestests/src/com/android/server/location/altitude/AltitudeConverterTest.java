/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.location.altitude;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.location.Location;
import android.location.altitude.AltitudeConverter;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class AltitudeConverterTest {

    private AltitudeConverter mAltitudeConverter;
    private Context mContext;

    @Before
    public void setUp() {
        mAltitudeConverter = new AltitudeConverter();
        mContext = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void testAddMslAltitudeToLocation_expectedBehavior() throws IOException {
        // Interpolates between bffffc, 955554, and 000004.
        Location location = new Location("");
        location.setLatitude(-35.246789);
        location.setLongitude(-44.962683);
        location.setAltitude(-1);
        location.setVerticalAccuracyMeters(1);
        // Requires data to be loaded from raw assets.
        assertThat(mAltitudeConverter.addMslAltitudeToLocation(location)).isFalse();
        assertThat(location.hasMslAltitude()).isFalse();
        assertThat(location.hasMslAltitudeAccuracy()).isFalse();
        // Loads data from raw assets.
        mAltitudeConverter.addMslAltitudeToLocation(mContext, location);
        assertThat(location.getMslAltitudeMeters()).isWithin(2).of(5.1076);
        assertThat(location.getMslAltitudeAccuracyMeters()).isGreaterThan(1f);
        assertThat(location.getMslAltitudeAccuracyMeters()).isLessThan(1.1f);

        // Again interpolates between bffffc, 955554, and 000004.
        location = new Location("");
        location.setLatitude(-35.246789);
        location.setLongitude(-44.962683);
        location.setAltitude(-1);
        location.setVerticalAccuracyMeters(1);
        // Requires no data to be loaded from raw assets.
        assertThat(mAltitudeConverter.addMslAltitudeToLocation(location)).isTrue();
        assertThat(location.getMslAltitudeMeters()).isWithin(2).of(5.1076);
        assertThat(location.getMslAltitudeAccuracyMeters()).isGreaterThan(1f);
        assertThat(location.getMslAltitudeAccuracyMeters()).isLessThan(1.1f);
        // Results in same outcome.
        mAltitudeConverter.addMslAltitudeToLocation(mContext, location);
        assertThat(location.getMslAltitudeMeters()).isWithin(2).of(5.1076);
        assertThat(location.getMslAltitudeAccuracyMeters()).isGreaterThan(1f);
        assertThat(location.getMslAltitudeAccuracyMeters()).isLessThan(1.1f);

        // Interpolate between 955554, 000004, 00000c, and 95554c - no vertical accuracy.
        location = new Location("");
        location.setLatitude(-35.176383);
        location.setLongitude(-44.962683);
        location.setAltitude(-1);
        location.setVerticalAccuracyMeters(-1); // Invalid vertical accuracy
        // Requires no data to be loaded from raw assets.
        assertThat(mAltitudeConverter.addMslAltitudeToLocation(location)).isTrue();
        assertThat(location.getMslAltitudeMeters()).isWithin(2).of(5.1919);
        assertThat(location.hasMslAltitudeAccuracy()).isFalse();
        // Results in same outcome.
        mAltitudeConverter.addMslAltitudeToLocation(mContext, location);
        assertThat(location.getMslAltitudeMeters()).isWithin(2).of(5.1919);
        assertThat(location.hasMslAltitudeAccuracy()).isFalse();

        // Interpolates somewhere else more interesting, i.e., Hawaii.
        location = new Location("");
        location.setLatitude(19.545519);
        location.setLongitude(-155.998774);
        location.setAltitude(-1);
        location.setVerticalAccuracyMeters(1);
        // Requires data to be loaded from raw assets.
        assertThat(mAltitudeConverter.addMslAltitudeToLocation(location)).isFalse();
        assertThat(location.hasMslAltitude()).isFalse();
        assertThat(location.hasMslAltitudeAccuracy()).isFalse();
        // Loads data from raw assets.
        mAltitudeConverter.addMslAltitudeToLocation(mContext, location);
        assertThat(location.getMslAltitudeMeters()).isWithin(2).of(-19.2359);
        assertThat(location.getMslAltitudeAccuracyMeters()).isGreaterThan(1f);
        assertThat(location.getMslAltitudeAccuracyMeters()).isLessThan(1.1f);
    }

    @Test
    public void testAddMslAltitudeToLocation_invalidLatitudeThrows() {
        Location location = new Location("");
        location.setLongitude(-44.962683);
        location.setAltitude(-1);

        location.setLatitude(Double.NaN);
        assertThrows(IllegalArgumentException.class,
                () -> mAltitudeConverter.addMslAltitudeToLocation(location));

        location.setLatitude(91);
        assertThrows(IllegalArgumentException.class,
                () -> mAltitudeConverter.addMslAltitudeToLocation(location));

        location.setLatitude(-91);
        assertThrows(IllegalArgumentException.class,
                () -> mAltitudeConverter.addMslAltitudeToLocation(location));
    }

    @Test
    public void testAddMslAltitudeToLocation_invalidLongitudeThrows() {
        Location location = new Location("");
        location.setLatitude(-35.246789);
        location.setAltitude(-1);

        location.setLongitude(Double.NaN);
        assertThrows(IllegalArgumentException.class,
                () -> mAltitudeConverter.addMslAltitudeToLocation(location));

        location.setLongitude(181);
        assertThrows(IllegalArgumentException.class,
                () -> mAltitudeConverter.addMslAltitudeToLocation(location));

        location.setLongitude(-181);
        assertThrows(IllegalArgumentException.class,
                () -> mAltitudeConverter.addMslAltitudeToLocation(location));
    }

    @Test
    public void testAddMslAltitudeToLocation_invalidAltitudeThrows() {
        Location location = new Location("");
        location.setLatitude(-35.246789);
        location.setLongitude(-44.962683);

        assertThrows(IllegalArgumentException.class,
                () -> mAltitudeConverter.addMslAltitudeToLocation(location));

        location.setAltitude(Double.NaN);
        assertThrows(IllegalArgumentException.class,
                () -> mAltitudeConverter.addMslAltitudeToLocation(location));

        location.setAltitude(Double.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class,
                () -> mAltitudeConverter.addMslAltitudeToLocation(location));
    }
}
