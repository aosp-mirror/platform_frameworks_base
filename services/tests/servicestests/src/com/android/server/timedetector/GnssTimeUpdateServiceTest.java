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

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.app.time.UnixEpochTime;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationManagerInternal;
import android.location.LocationRequest;
import android.location.LocationTime;

import androidx.test.runner.AndroidJUnit4;

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
    @Mock private AlarmManager mMockAlarmManager;
    @Mock private LocationManager mMockLocationManager;
    @Mock private LocationManagerInternal mMockLocationManagerInternal;
    @Mock private TimeDetectorInternal mMockTimeDetectorInternal;

    private GnssTimeUpdateService mGnssTimeUpdateService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        installGpsProviderInMockLocationManager();

        mGnssTimeUpdateService = new GnssTimeUpdateService(
                mMockContext, mMockAlarmManager, mMockLocationManager, mMockLocationManagerInternal,
                mMockTimeDetectorInternal);
    }

    @Test
    public void testLocationListenerOnLocationChanged_validLocationTime_suggestsGnssTime() {
        UnixEpochTime timeSignal = new UnixEpochTime(
                ELAPSED_REALTIME_MS, GNSS_TIME);
        GnssTimeSuggestion timeSuggestion = new GnssTimeSuggestion(timeSignal);
        LocationTime locationTime = new LocationTime(GNSS_TIME, ELAPSED_REALTIME_NS);
        doReturn(locationTime).when(mMockLocationManagerInternal).getGnssTimeMillis();

        assertTrue(mGnssTimeUpdateService.startGnssListeningInternal());

        ArgumentCaptor<LocationListener> locationListenerCaptor =
                ArgumentCaptor.forClass(LocationListener.class);
        verify(mMockLocationManager).requestLocationUpdates(
                eq(LocationManager.GPS_PROVIDER),
                eq(new LocationRequest.Builder(LocationRequest.PASSIVE_INTERVAL)
                    .setMinUpdateIntervalMillis(0)
                    .build()),
                any(),
                locationListenerCaptor.capture());
        LocationListener locationListener = locationListenerCaptor.getValue();
        Location location = new Location(LocationManager.GPS_PROVIDER);

        locationListener.onLocationChanged(location);

        verify(mMockLocationManager).removeUpdates(locationListener);
        verify(mMockTimeDetectorInternal).suggestGnssTime(timeSuggestion);
        verify(mMockAlarmManager).set(
                eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                anyLong(),
                any(),
                any(),
                any());
    }

    @Test
    public void testLocationListenerOnLocationChanged_nullLocationTime_doesNotSuggestGnssTime() {
        doReturn(null).when(mMockLocationManagerInternal).getGnssTimeMillis();

        assertTrue(mGnssTimeUpdateService.startGnssListeningInternal());

        ArgumentCaptor<LocationListener> locationListenerCaptor =
                ArgumentCaptor.forClass(LocationListener.class);
        verify(mMockLocationManager).requestLocationUpdates(
                eq(LocationManager.GPS_PROVIDER),
                eq(new LocationRequest.Builder(LocationRequest.PASSIVE_INTERVAL)
                    .setMinUpdateIntervalMillis(0)
                    .build()),
                any(),
                locationListenerCaptor.capture());
        LocationListener locationListener = locationListenerCaptor.getValue();
        Location location = new Location(LocationManager.GPS_PROVIDER);

        locationListener.onLocationChanged(location);

        verify(mMockLocationManager).removeUpdates(locationListener);
        verifyZeroInteractions(mMockTimeDetectorInternal);
        verify(mMockAlarmManager).set(
                eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                anyLong(),
                any(),
                any(),
                any());
    }

    @Test
    public void testLocationListeningRestartsAfterSleep() {
        ArgumentCaptor<LocationListener> locationListenerCaptor =
                ArgumentCaptor.forClass(LocationListener.class);
        ArgumentCaptor<OnAlarmListener> alarmListenerCaptor =
                ArgumentCaptor.forClass(OnAlarmListener.class);

        advanceServiceToSleepingState(locationListenerCaptor, alarmListenerCaptor);

        // Simulate the alarm manager's wake-up call.
        OnAlarmListener wakeUpListener = alarmListenerCaptor.getValue();
        wakeUpListener.onAlarm();

        // Verify the service returned to location listening.
        verify(mMockLocationManager).requestLocationUpdates(any(), any(), any(), any());
        verifyZeroInteractions(mMockAlarmManager, mMockTimeDetectorInternal);
    }

    // Tests what happens when a call is made to startGnssListeningInternal() when service is
    // sleeping. This can happen when the start_gnss_listening shell command is used.
    @Test
    public void testStartGnssListeningInternalCalledWhenSleeping() {
        ArgumentCaptor<LocationListener> locationListenerCaptor =
                ArgumentCaptor.forClass(LocationListener.class);
        ArgumentCaptor<OnAlarmListener> alarmListenerCaptor =
                ArgumentCaptor.forClass(OnAlarmListener.class);

        advanceServiceToSleepingState(locationListenerCaptor, alarmListenerCaptor);

        // Call startGnssListeningInternal(), as can happen if the start_gnss_listening shell
        // command is used.
        assertTrue(mGnssTimeUpdateService.startGnssListeningInternal());

        // Verify the alarm manager is told to stopped sleeping and the location manager is
        // listening again.
        verify(mMockAlarmManager).cancel(alarmListenerCaptor.getValue());
        verify(mMockLocationManager).requestLocationUpdates(any(), any(), any(), any());
        verifyZeroInteractions(mMockTimeDetectorInternal);
    }

    private void advanceServiceToSleepingState(
            ArgumentCaptor<LocationListener> locationListenerCaptor,
            ArgumentCaptor<OnAlarmListener> alarmListenerCaptor) {
        UnixEpochTime timeSignal = new UnixEpochTime(
                ELAPSED_REALTIME_MS, GNSS_TIME);
        GnssTimeSuggestion timeSuggestion = new GnssTimeSuggestion(timeSignal);
        LocationTime locationTime = new LocationTime(GNSS_TIME, ELAPSED_REALTIME_NS);
        doReturn(locationTime).when(mMockLocationManagerInternal).getGnssTimeMillis();

        assertTrue(mGnssTimeUpdateService.startGnssListeningInternal());

        verify(mMockLocationManager).requestLocationUpdates(
                any(), any(), any(), locationListenerCaptor.capture());
        LocationListener locationListener = locationListenerCaptor.getValue();
        Location location = new Location(LocationManager.GPS_PROVIDER);
        verifyZeroInteractions(mMockAlarmManager, mMockTimeDetectorInternal);

        locationListener.onLocationChanged(location);

        verify(mMockLocationManager).removeUpdates(locationListener);
        verify(mMockTimeDetectorInternal).suggestGnssTime(timeSuggestion);

        // Verify the service is now "sleeping", i.e. waiting for a period before listening for
        // GNSS locations again.
        verify(mMockAlarmManager).set(
                eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                anyLong(),
                any(),
                alarmListenerCaptor.capture(),
                any());

        // Reset mocks making it easier to verify the calls that follow.
        reset(mMockAlarmManager, mMockTimeDetectorInternal, mMockLocationManager,
                mMockLocationManagerInternal);
        installGpsProviderInMockLocationManager();
    }

    /**
     * Configures the mock response to ensure {@code
     * locationManager.hasProvider(LocationManager.GPS_PROVIDER) == true }
     */
    private void installGpsProviderInMockLocationManager() {
        when(mMockLocationManager.hasProvider(LocationManager.GPS_PROVIDER))
                .thenReturn(true);
    }
}
