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

package com.android.server.timedetector;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.timedetector.GnssTimeSuggestion;
import android.app.timedetector.TimeDetector;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationManagerInternal;
import android.location.LocationRequest;
import android.location.LocationTime;
import android.os.TimestampedValue;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public final class GnssTimeUpdateServiceTest {
    private static final long GNSS_TIME = 999_999_999L;
    private static final long ELAPSED_REALTIME_NS = 123_000_000L;
    private static final long ELAPSED_REALTIME_MS = ELAPSED_REALTIME_NS / 1_000_000L;

    @Mock private Context mMockContext;
    @Mock private TimeDetector mMockTimeDetector;
    @Mock private AlarmManager mMockAlarmManager;
    @Mock private LocationManager mMockLocationManager;
    @Mock private LocationManagerInternal mLocationManagerInternal;

    private GnssTimeUpdateService mGnssTimeUpdateService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mMockContext.createAttributionContext(anyString()))
                .thenReturn(mMockContext);

        when(mMockContext.getSystemServiceName(TimeDetector.class))
                .thenReturn((TimeDetector.class).getSimpleName());
        when(mMockContext.getSystemService(TimeDetector.class))
                .thenReturn(mMockTimeDetector);

        when(mMockContext.getSystemServiceName(LocationManager.class))
                .thenReturn((LocationManager.class).getSimpleName());
        when(mMockContext.getSystemService(LocationManager.class))
                .thenReturn(mMockLocationManager);

        when(mMockContext.getSystemServiceName(AlarmManager.class))
                .thenReturn((AlarmManager.class).getSimpleName());
        when(mMockContext.getSystemService(AlarmManager.class))
                .thenReturn(mMockAlarmManager);

        when(mMockLocationManager.hasProvider(LocationManager.GPS_PROVIDER))
                .thenReturn(true);

        LocalServices.addService(LocationManagerInternal.class, mLocationManagerInternal);

        mGnssTimeUpdateService =
                new GnssTimeUpdateService(mMockContext);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(LocationManagerInternal.class);
    }

    @Test
    public void testLocationListenerOnLocationChanged_validLocationTime_suggestsGnssTime() {
        TimestampedValue<Long> timeSignal = new TimestampedValue<>(
                ELAPSED_REALTIME_MS, GNSS_TIME);
        GnssTimeSuggestion timeSuggestion = new GnssTimeSuggestion(timeSignal);
        LocationTime locationTime = new LocationTime(GNSS_TIME, ELAPSED_REALTIME_NS);
        doReturn(locationTime).when(mLocationManagerInternal).getGnssTimeMillis();

        mGnssTimeUpdateService.requestGnssTimeUpdates();

        ArgumentCaptor<LocationListener> argumentCaptor =
                ArgumentCaptor.forClass(LocationListener.class);
        verify(mMockLocationManager).requestLocationUpdates(
                eq(LocationManager.GPS_PROVIDER),
                eq(new LocationRequest.Builder(LocationRequest.PASSIVE_INTERVAL)
                    .setMinUpdateIntervalMillis(0)
                    .build()),
                any(),
                argumentCaptor.capture());
        LocationListener locationListener = argumentCaptor.getValue();
        Location location = new Location(LocationManager.GPS_PROVIDER);

        locationListener.onLocationChanged(location);

        verify(mMockLocationManager).removeUpdates(locationListener);
        verify(mMockTimeDetector).suggestGnssTime(timeSuggestion);
        verify(mMockAlarmManager).set(
                eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                anyLong(),
                any(),
                any(),
                any());
    }

    @Test
    public void testLocationListenerOnLocationChanged_nullLocationTime_doesNotSuggestGnssTime() {
        doReturn(null).when(mLocationManagerInternal).getGnssTimeMillis();

        mGnssTimeUpdateService.requestGnssTimeUpdates();

        ArgumentCaptor<LocationListener> argumentCaptor =
                ArgumentCaptor.forClass(LocationListener.class);
        verify(mMockLocationManager).requestLocationUpdates(
                eq(LocationManager.GPS_PROVIDER),
                eq(new LocationRequest.Builder(LocationRequest.PASSIVE_INTERVAL)
                    .setMinUpdateIntervalMillis(0)
                    .build()),
                any(),
                argumentCaptor.capture());
        LocationListener locationListener = argumentCaptor.getValue();
        Location location = new Location(LocationManager.GPS_PROVIDER);

        locationListener.onLocationChanged(location);

        verify(mMockLocationManager).removeUpdates(locationListener);
        verify(mMockTimeDetector, never()).suggestGnssTime(any());
        verify(mMockAlarmManager).set(
                eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                anyLong(),
                any(),
                any(),
                any());
    }
}
