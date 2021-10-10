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

package com.android.server.location.gnss;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.location.gnss.hal.FakeGnssHal;
import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.injector.TestInjector;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Objects;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class GnssGeofenceProxyTest {

    private static final int GEOFENCE_ID = 12345;
    private static final double LATITUDE = 10.0;
    private static final double LONGITUDE = 20.0;
    private static final double RADIUS = 5.0;
    private static final int LAST_TRANSITION = 0;
    private static final int MONITOR_TRANSITIONS = 0;
    private static final int NOTIFICATION_RESPONSIVENESS = 0;
    private static final int UNKNOWN_TIMER = 0;

    private @Mock Context mContext;
    private @Mock GnssConfiguration mMockConfiguration;
    private @Mock GnssNative.GeofenceCallbacks mGeofenceCallbacks;

    private FakeGnssHal mFakeHal;
    private GnssGeofenceProxy mTestProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mFakeHal = new FakeGnssHal();
        GnssNative.setGnssHalForTest(mFakeHal);

        GnssNative gnssNative = Objects.requireNonNull(
                GnssNative.create(new TestInjector(mContext), mMockConfiguration));
        gnssNative.setGeofenceCallbacks(mGeofenceCallbacks);
        mTestProvider = new GnssGeofenceProxy(gnssNative);
        gnssNative.register();

        mTestProvider.addCircularHardwareGeofence(GEOFENCE_ID, LATITUDE, LONGITUDE, RADIUS,
                LAST_TRANSITION, MONITOR_TRANSITIONS, NOTIFICATION_RESPONSIVENESS, UNKNOWN_TIMER);
    }

    @Test
    public void testAddGeofence() {
        assertThat(mFakeHal.getGeofences()).containsExactly(new FakeGnssHal.GnssHalGeofence(
                GEOFENCE_ID, LATITUDE, LONGITUDE, RADIUS, LAST_TRANSITION, MONITOR_TRANSITIONS,
                NOTIFICATION_RESPONSIVENESS, UNKNOWN_TIMER, false));
    }

    @Test
    public void testRemoveGeofence() {
        mTestProvider.removeHardwareGeofence(GEOFENCE_ID);

        assertThat(mFakeHal.getGeofences()).isEmpty();
    }

    @Test
    public void testPauseGeofence() {
        mTestProvider.pauseHardwareGeofence(GEOFENCE_ID);

        assertThat(mFakeHal.getGeofences()).containsExactly(new FakeGnssHal.GnssHalGeofence(
                GEOFENCE_ID, LATITUDE, LONGITUDE, RADIUS, LAST_TRANSITION, MONITOR_TRANSITIONS,
                NOTIFICATION_RESPONSIVENESS, UNKNOWN_TIMER, true));
    }

    @Test
    public void testResumeGeofence() {
        mTestProvider.pauseHardwareGeofence(GEOFENCE_ID);
        mTestProvider.resumeHardwareGeofence(GEOFENCE_ID, MONITOR_TRANSITIONS);

        assertThat(mFakeHal.getGeofences()).containsExactly(new FakeGnssHal.GnssHalGeofence(
                GEOFENCE_ID, LATITUDE, LONGITUDE, RADIUS, LAST_TRANSITION, MONITOR_TRANSITIONS,
                NOTIFICATION_RESPONSIVENESS, UNKNOWN_TIMER, false));
    }

    @Test
    public void testAddGeofence_restart() {
        mFakeHal.restartHal();

        assertThat(mFakeHal.getGeofences()).containsExactly(new FakeGnssHal.GnssHalGeofence(
                GEOFENCE_ID, LATITUDE, LONGITUDE, RADIUS, LAST_TRANSITION, MONITOR_TRANSITIONS,
                NOTIFICATION_RESPONSIVENESS, UNKNOWN_TIMER, false));
    }

    @Test
    public void testRemoveGeofence_restart() {
        mTestProvider.removeHardwareGeofence(GEOFENCE_ID);
        mFakeHal.restartHal();

        assertThat(mFakeHal.getGeofences()).isEmpty();
    }

    @Test
    public void testPauseGeofence_restart() {
        mTestProvider.pauseHardwareGeofence(GEOFENCE_ID);
        mFakeHal.restartHal();

        assertThat(mFakeHal.getGeofences()).containsExactly(new FakeGnssHal.GnssHalGeofence(
                GEOFENCE_ID, LATITUDE, LONGITUDE, RADIUS, LAST_TRANSITION, MONITOR_TRANSITIONS,
                NOTIFICATION_RESPONSIVENESS, UNKNOWN_TIMER, true));
    }
}
