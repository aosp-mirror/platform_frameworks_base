package com.android.server.location;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for {@link GnssGeofenceProvider}.
 */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class GnssGeofenceProviderTest {
    private static final int GEOFENCE_ID = 12345;
    private static final double LATITUDE = 10.0;
    private static final double LONGITUDE = 20.0;
    private static final double RADIUS = 5.0;
    private static final int LAST_TRANSITION = 0;
    private static final int MONITOR_TRANSITIONS = 0;
    private static final int NOTIFICATION_RESPONSIVENESS = 0;
    private static final int UNKNOWN_TIMER = 0;
    @Mock
    private GnssGeofenceProvider.GnssGeofenceProviderNative mMockNative;
    private GnssGeofenceProvider mTestProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockNative.addGeofence(anyInt(), anyDouble(), anyDouble(), anyDouble(), anyInt(),
                anyInt(), anyInt(), anyInt())).thenReturn(true);
        when(mMockNative.pauseGeofence(anyInt())).thenReturn(true);
        when(mMockNative.removeGeofence(anyInt())).thenReturn(true);
        when(mMockNative.resumeGeofence(anyInt(), anyInt())).thenReturn(true);
        mTestProvider = new GnssGeofenceProvider(mMockNative);
        mTestProvider.addCircularHardwareGeofence(GEOFENCE_ID, LATITUDE,
                LONGITUDE, RADIUS, LAST_TRANSITION, MONITOR_TRANSITIONS,
                NOTIFICATION_RESPONSIVENESS,
                UNKNOWN_TIMER);
    }

    @Test
    public void addGeofence_nativeAdded() {
        verify(mMockNative).addGeofence(eq(GEOFENCE_ID), eq(LATITUDE), eq(LONGITUDE),
                eq(RADIUS), eq(LAST_TRANSITION), eq(MONITOR_TRANSITIONS),
                eq(NOTIFICATION_RESPONSIVENESS),
                eq(UNKNOWN_TIMER));
    }

    @Test
    public void pauseGeofence_nativePaused() {
        mTestProvider.pauseHardwareGeofence(GEOFENCE_ID);
        verify(mMockNative).pauseGeofence(eq(GEOFENCE_ID));
    }

    @Test
    public void removeGeofence_nativeRemoved() {
        mTestProvider.removeHardwareGeofence(GEOFENCE_ID);
        verify(mMockNative).removeGeofence(eq(GEOFENCE_ID));
    }

    @Test
    public void resumeGeofence_nativeResumed() {
        mTestProvider.pauseHardwareGeofence(GEOFENCE_ID);
        mTestProvider.resumeHardwareGeofence(GEOFENCE_ID, MONITOR_TRANSITIONS);
        verify(mMockNative).resumeGeofence(eq(GEOFENCE_ID), eq(MONITOR_TRANSITIONS));
    }

    @Test
    public void addGeofence_restart_added() throws RemoteException {
        mTestProvider.resumeIfStarted();

        verify(mMockNative, times(2)).addGeofence(eq(GEOFENCE_ID), eq(LATITUDE), eq(LONGITUDE),
                eq(RADIUS), eq(LAST_TRANSITION), eq(MONITOR_TRANSITIONS),
                eq(NOTIFICATION_RESPONSIVENESS),
                eq(UNKNOWN_TIMER));
    }

    @Test
    public void removeGeofence_restart_notAdded() throws RemoteException {
        mTestProvider.removeHardwareGeofence(GEOFENCE_ID);
        mTestProvider.resumeIfStarted();

        verify(mMockNative, times(1)).addGeofence(eq(GEOFENCE_ID), eq(LATITUDE), eq(LONGITUDE),
                eq(RADIUS), eq(LAST_TRANSITION), eq(MONITOR_TRANSITIONS),
                eq(NOTIFICATION_RESPONSIVENESS),
                eq(UNKNOWN_TIMER));
    }

    @Test
    public void pauseGeofence_restart_paused() throws RemoteException {
        mTestProvider.pauseHardwareGeofence(GEOFENCE_ID);
        mTestProvider.resumeIfStarted();

        verify(mMockNative, times(2)).addGeofence(eq(GEOFENCE_ID), eq(LATITUDE), eq(LONGITUDE),
                eq(RADIUS), eq(LAST_TRANSITION), eq(MONITOR_TRANSITIONS),
                eq(NOTIFICATION_RESPONSIVENESS),
                eq(UNKNOWN_TIMER));
        verify(mMockNative, times(2)).pauseGeofence(eq(GEOFENCE_ID));
    }
}
