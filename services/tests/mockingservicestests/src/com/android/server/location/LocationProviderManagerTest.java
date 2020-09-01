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

package com.android.server.location;

import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.app.AlarmManager.WINDOW_EXACT;
import static android.app.AppOpsManager.OP_FINE_LOCATION;
import static android.app.AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION;
import static android.app.AppOpsManager.OP_MONITOR_LOCATION;
import static android.location.Criteria.ACCURACY_COARSE;
import static android.location.Criteria.ACCURACY_FINE;
import static android.location.Criteria.POWER_HIGH;
import static android.location.LocationManager.PASSIVE_PROVIDER;
import static android.os.PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF;

import static androidx.test.ext.truth.location.LocationSubject.assertThat;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;
import static com.android.server.location.LocationPermissions.PERMISSION_COARSE;
import static com.android.server.location.LocationPermissions.PERMISSION_FINE;
import static com.android.server.location.LocationUtils.createLocation;
import static com.android.server.location.listeners.RemoteListenerRegistration.IN_PROCESS_EXECUTOR;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.MockitoAnnotations.initMocks;

import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.content.Context;
import android.location.ILocationCallback;
import android.location.ILocationListener;
import android.location.Location;
import android.location.LocationManagerInternal;
import android.location.LocationManagerInternal.ProviderEnabledListener;
import android.location.LocationRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.ICancellationSignal;
import android.os.IRemoteCallback;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.WorkSource;
import android.platform.test.annotations.Presubmit;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.location.ProviderProperties;
import com.android.internal.location.ProviderRequest;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.location.util.FakeUserInfoHelper;
import com.android.server.location.util.TestInjector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LocationProviderManagerTest {

    private static final String TAG = "LocationProviderManagerTest";

    private static final long TIMEOUT_MS = 1000;

    private static final int CURRENT_USER = FakeUserInfoHelper.DEFAULT_USERID;
    private static final int OTHER_USER = CURRENT_USER + 10;

    private static final String NAME = "test";
    private static final ProviderProperties PROPERTIES = new ProviderProperties(false, false, false,
            false, true, true, true, POWER_HIGH, ACCURACY_FINE);
    private static final CallerIdentity IDENTITY = CallerIdentity.forTest(CURRENT_USER, 1,
            "mypackage",
            "attribution");

    private Random mRandom;

    @Mock
    private LocationManagerInternal mInternal;
    @Mock
    private Context mContext;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private PowerManager.WakeLock mWakeLock;

    private TestInjector mInjector;
    private PassiveLocationProviderManager mPassive;
    private TestProvider mProvider;

    private LocationProviderManager mManager;

    @Before
    public void setUp() {
        initMocks(this);

        long seed = System.currentTimeMillis();
        Log.i(TAG, "location random seed: " + seed);

        mRandom = new Random(seed);

        LocalServices.addService(LocationManagerInternal.class, mInternal);

        doReturn("android").when(mContext).getPackageName();
        doReturn(mAlarmManager).when(mContext).getSystemService(AlarmManager.class);
        doReturn(mPowerManager).when(mContext).getSystemService(PowerManager.class);
        doReturn(mWakeLock).when(mPowerManager).newWakeLock(anyInt(), anyString());

        mInjector = new TestInjector();
        mInjector.getUserInfoHelper().startUser(OTHER_USER);

        mPassive = new PassiveLocationProviderManager(mContext, mInjector);
        mPassive.startManager();
        mPassive.setRealProvider(new PassiveProvider(mContext));

        mProvider = new TestProvider(PROPERTIES, IDENTITY);
        mProvider.setProviderAllowed(true);

        mManager = new LocationProviderManager(mContext, mInjector, NAME, mPassive);
        mManager.startManager();
        mManager.setRealProvider(mProvider);
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(LocationManagerInternal.class);

        // some test failures may leave the fg thread stuck, interrupt until we get out of it
        CountDownLatch latch = new CountDownLatch(1);
        FgThread.getExecutor().execute(latch::countDown);
        int count = 0;
        while (++count < 10 && !latch.await(10, TimeUnit.MILLISECONDS)) {
            FgThread.get().getLooper().getThread().interrupt();
        }
    }

    @Test
    public void testProperties() {
        assertThat(mManager.getName()).isEqualTo(NAME);
        assertThat(mManager.getProperties()).isEqualTo(PROPERTIES);
        assertThat(mManager.getIdentity()).isEqualTo(IDENTITY);
        assertThat(mManager.hasProvider()).isTrue();

        ProviderProperties newProperties = new ProviderProperties(true, true, true,
                true, false, false, false, POWER_HIGH, ACCURACY_COARSE);
        mProvider.setProperties(newProperties);
        assertThat(mManager.getProperties()).isEqualTo(newProperties);

        CallerIdentity newIdentity = CallerIdentity.forTest(OTHER_USER, 1, "otherpackage",
                "otherattribution");
        mProvider.setIdentity(newIdentity);
        assertThat(mManager.getIdentity()).isEqualTo(newIdentity);

        mManager.setRealProvider(null);
        assertThat(mManager.hasProvider()).isFalse();
    }

    @Test
    public void testRemoveProvider() {
        mManager.setRealProvider(null);
        assertThat(mManager.hasProvider()).isFalse();
    }

    @Test
    public void testIsEnabled() {
        assertThat(mManager.isEnabled(CURRENT_USER)).isTrue();

        mInjector.getSettingsHelper().setLocationEnabled(false, CURRENT_USER);
        assertThat(mManager.isEnabled(CURRENT_USER)).isFalse();

        mInjector.getSettingsHelper().setLocationEnabled(true, CURRENT_USER);
        mProvider.setAllowed(false);
        assertThat(mManager.isEnabled(CURRENT_USER)).isFalse();

        mProvider.setAllowed(true);
        mInjector.getUserInfoHelper().setCurrentUserId(OTHER_USER);
        assertThat(mManager.isEnabled(CURRENT_USER)).isFalse();
        assertThat(mManager.isEnabled(OTHER_USER)).isTrue();

        mInjector.getUserInfoHelper().setCurrentUserId(CURRENT_USER);
        assertThat(mManager.isEnabled(CURRENT_USER)).isTrue();
        assertThat(mManager.isEnabled(OTHER_USER)).isFalse();
    }

    @Test
    public void testIsEnabledListener() {
        ProviderEnabledListener listener = mock(ProviderEnabledListener.class);
        mManager.addEnabledListener(listener);
        verify(listener, never()).onProviderEnabledChanged(anyString(), anyInt(), anyBoolean());

        mInjector.getSettingsHelper().setLocationEnabled(false, CURRENT_USER);
        verify(listener, timeout(TIMEOUT_MS).times(1)).onProviderEnabledChanged(NAME, CURRENT_USER,
                false);

        mInjector.getSettingsHelper().setLocationEnabled(true, CURRENT_USER);
        verify(listener, timeout(TIMEOUT_MS).times(1)).onProviderEnabledChanged(NAME, CURRENT_USER,
                true);

        mProvider.setAllowed(false);
        verify(listener, timeout(TIMEOUT_MS).times(2)).onProviderEnabledChanged(NAME, CURRENT_USER,
                false);

        mProvider.setAllowed(true);
        verify(listener, timeout(TIMEOUT_MS).times(2)).onProviderEnabledChanged(NAME, CURRENT_USER,
                true);

        mInjector.getUserInfoHelper().setCurrentUserId(OTHER_USER);
        verify(listener, timeout(TIMEOUT_MS).times(3)).onProviderEnabledChanged(NAME, CURRENT_USER,
                false);
        verify(listener, timeout(TIMEOUT_MS).times(1)).onProviderEnabledChanged(NAME, OTHER_USER,
                true);

        mInjector.getUserInfoHelper().setCurrentUserId(CURRENT_USER);
        verify(listener, timeout(TIMEOUT_MS).times(3)).onProviderEnabledChanged(NAME, CURRENT_USER,
                true);
        verify(listener, timeout(TIMEOUT_MS).times(1)).onProviderEnabledChanged(NAME, OTHER_USER,
                false);

        mManager.removeEnabledListener(listener);
        mInjector.getSettingsHelper().setLocationEnabled(false, CURRENT_USER);
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void testGetLastLocation_Fine() {
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, false)).isNull();

        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, false)).isEqualTo(loc);
    }

    @Test
    public void testGetLastLocation_Coarse() {
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, false)).isNull();

        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        Location coarse = mManager.getLastLocation(IDENTITY, PERMISSION_COARSE, false);
        assertThat(coarse).isNotEqualTo(loc);
        assertThat(coarse).isNearby(loc, 5000);
    }

    @Test
    public void testGetLastLocation_Bypass() {
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, false)).isNull();
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, true)).isNull();

        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, false)).isEqualTo(loc);
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, true)).isEqualTo(
                loc);

        mProvider.setProviderAllowed(false);
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, false)).isNull();
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, true)).isEqualTo(
                loc);

        loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, false)).isNull();
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, true)).isEqualTo(
                loc);

        mProvider.setProviderAllowed(true);
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, false)).isNull();
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, true)).isEqualTo(
                loc);

        loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, false)).isEqualTo(loc);
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, true)).isEqualTo(
                loc);
    }

    @Test
    public void testGetLastLocation_ClearOnMockRemoval() {
        MockProvider mockProvider = new MockProvider(PROPERTIES, IDENTITY);
        mockProvider.setAllowed(true);
        mManager.setMockProvider(mockProvider);

        Location loc = createLocation(NAME, mRandom);
        mockProvider.setProviderLocation(loc);
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, false)).isEqualTo(loc);

        mManager.setMockProvider(null);
        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, false)).isNull();
    }

    @Test
    public void testInjectLastLocation() {
        Location loc1 = createLocation(NAME, mRandom);
        mManager.injectLastLocation(loc1, CURRENT_USER);

        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, false)).isEqualTo(loc1);

        Location loc2 = createLocation(NAME, mRandom);
        mManager.injectLastLocation(loc2, CURRENT_USER);

        assertThat(mManager.getLastLocation(IDENTITY, PERMISSION_FINE, false)).isEqualTo(loc1);
    }

    @Test
    public void testPassive_Listener() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(PASSIVE_PROVIDER, 0,
                0, false);
        mPassive.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);

        ArgumentCaptor<Location> locationCaptor = ArgumentCaptor.forClass(Location.class);
        verify(listener).onLocationChanged(locationCaptor.capture(),
                nullable(IRemoteCallback.class));
        assertThat(locationCaptor.getValue()).isEqualTo(loc);
    }

    @Test
    public void testPassive_LastLocation() {
        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);

        assertThat(mPassive.getLastLocation(IDENTITY, PERMISSION_FINE, false)).isEqualTo(loc);
    }

    @Test
    public void testRegisterListener() throws Exception {
        ArgumentCaptor<Location> locationCaptor = ArgumentCaptor.forClass(Location.class);

        ILocationListener listener = createMockLocationListener();
        mManager.registerLocationRequest(
                LocationRequest.createFromDeprecatedProvider(NAME, 0, 0, false), IDENTITY,
                PERMISSION_FINE, listener);

        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        verify(listener, times(1)).onLocationChanged(locationCaptor.capture(),
                nullable(IRemoteCallback.class));
        assertThat(locationCaptor.getValue()).isEqualTo(loc);

        mInjector.getSettingsHelper().setLocationEnabled(false, CURRENT_USER);
        verify(listener, timeout(TIMEOUT_MS).times(1)).onProviderEnabledChanged(NAME, false);
        loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        verify(listener, times(1)).onLocationChanged(any(Location.class),
                nullable(IRemoteCallback.class));

        mInjector.getSettingsHelper().setLocationEnabled(true, CURRENT_USER);
        verify(listener, timeout(TIMEOUT_MS).times(1)).onProviderEnabledChanged(NAME, true);

        mProvider.setAllowed(false);
        verify(listener, timeout(TIMEOUT_MS).times(2)).onProviderEnabledChanged(NAME, false);
        loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        verify(listener, times(1)).onLocationChanged(any(Location.class),
                nullable(IRemoteCallback.class));

        mProvider.setAllowed(true);
        verify(listener, timeout(TIMEOUT_MS).times(2)).onProviderEnabledChanged(NAME, true);

        mInjector.getUserInfoHelper().setCurrentUserId(OTHER_USER);
        verify(listener, timeout(TIMEOUT_MS).times(3)).onProviderEnabledChanged(NAME, false);
        loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        verify(listener, times(1)).onLocationChanged(any(Location.class),
                nullable(IRemoteCallback.class));

        mInjector.getUserInfoHelper().setCurrentUserId(CURRENT_USER);
        verify(listener, timeout(TIMEOUT_MS).times(3)).onProviderEnabledChanged(NAME, true);

        loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        verify(listener, times(2)).onLocationChanged(locationCaptor.capture(),
                nullable(IRemoteCallback.class));
        assertThat(locationCaptor.getValue()).isEqualTo(loc);
    }

    @Test
    public void testRegisterListener_SameProcess() throws Exception {
        ArgumentCaptor<Location> locationCaptor = ArgumentCaptor.forClass(Location.class);

        CallerIdentity identity = CallerIdentity.forTest(CURRENT_USER, Process.myPid(), "mypackage",
                "attribution");

        ILocationListener listener = createMockLocationListener();
        mManager.registerLocationRequest(
                LocationRequest.createFromDeprecatedProvider(NAME, 0, 0, false), identity,
                PERMISSION_FINE, listener);

        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        verify(listener, timeout(TIMEOUT_MS).times(1)).onLocationChanged(locationCaptor.capture(),
                nullable(IRemoteCallback.class));
        assertThat(locationCaptor.getValue()).isEqualTo(loc);
    }

    @Test
    public void testRegisterListener_Unregister() throws Exception {
        ILocationListener listener = createMockLocationListener();
        mManager.registerLocationRequest(
                LocationRequest.createFromDeprecatedProvider(NAME, 0, 0, false), IDENTITY,
                PERMISSION_FINE, listener);
        mManager.unregisterLocationRequest(listener);

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, never()).onLocationChanged(any(Location.class),
                nullable(IRemoteCallback.class));

        mInjector.getSettingsHelper().setLocationEnabled(false, CURRENT_USER);
        verify(listener, after(TIMEOUT_MS).never()).onProviderEnabledChanged(NAME, false);
    }

    @Test
    public void testRegisterListener_Unregister_SameProcess() throws Exception {
        CallerIdentity identity = CallerIdentity.forTest(CURRENT_USER, Process.myPid(), "mypackage",
                "attribution");

        ILocationListener listener = createMockLocationListener();
        mManager.registerLocationRequest(
                LocationRequest.createFromDeprecatedProvider(NAME, 0, 0, false), identity,
                PERMISSION_FINE, listener);

        CountDownLatch blocker = new CountDownLatch(1);
        IN_PROCESS_EXECUTOR.execute(() -> {
            try {
                blocker.await();
            } catch (InterruptedException e) {
                // do nothing
            }
        });

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mManager.unregisterLocationRequest(listener);
        blocker.countDown();
        verify(listener, after(TIMEOUT_MS).never()).onLocationChanged(any(Location.class),
                nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_NumUpdates() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(NAME, 0, 0,
                false).setNumUpdates(5);
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mProvider.setProviderLocation(createLocation(NAME, mRandom));

        verify(listener, times(5)).onLocationChanged(any(Location.class),
                nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_ExpiringAlarm() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(NAME, 0, 0,
                false).setExpireIn(5000);
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);
        long baseTimeMs = SystemClock.elapsedRealtime();

        ArgumentCaptor<Long> timeoutCapture = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<OnAlarmListener> listenerCapture = ArgumentCaptor.forClass(
                OnAlarmListener.class);
        verify(mAlarmManager).set(eq(ELAPSED_REALTIME_WAKEUP), timeoutCapture.capture(),
                eq(WINDOW_EXACT), eq(0L), listenerCapture.capture(), any(Handler.class),
                any(WorkSource.class));

        assertThat(timeoutCapture.getValue()).isAtLeast(baseTimeMs + 4000);
        assertThat(timeoutCapture.getValue()).isAtMost(baseTimeMs + 5000);
        listenerCapture.getValue().onAlarm();

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, never()).onLocationChanged(any(Location.class),
                nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_ExpiringNoAlarm() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(NAME, 0, 0,
                false).setExpireIn(25);
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        Thread.sleep(25);

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, never()).onLocationChanged(any(Location.class),
                nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_AlreadyExpired() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(NAME, 0, 0,
                false).setExpireIn(-1);
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, never()).onLocationChanged(any(Location.class),
                nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_FastestInterval() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(NAME, 5000, 0,
                false).setFastestInterval(5000);
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mProvider.setProviderLocation(createLocation(NAME, mRandom));

        verify(listener, times(1)).onLocationChanged(any(Location.class),
                nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_SmallestDisplacement() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(NAME, 5000, 0,
                false).setSmallestDisplacement(1f);
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        mProvider.setProviderLocation(loc);

        verify(listener, times(1)).onLocationChanged(any(Location.class),
                nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_NoteOpFailure() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(NAME, 0, 0, false);
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        mInjector.getAppOpsHelper().setAppOpAllowed(OP_FINE_LOCATION, IDENTITY.getPackageName(),
                false);

        mProvider.setProviderLocation(createLocation(NAME, mRandom));

        verify(listener, never()).onLocationChanged(any(Location.class),
                nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_Wakelock() throws Exception {
        CallerIdentity identity = CallerIdentity.forTest(CURRENT_USER, Process.myPid(), "mypackage",
                "attribution");

        ILocationListener listener = createMockLocationListener();
        mManager.registerLocationRequest(
                LocationRequest.createFromDeprecatedProvider(NAME, 0, 0, false), identity,
                PERMISSION_FINE, listener);

        CountDownLatch blocker = new CountDownLatch(1);
        IN_PROCESS_EXECUTOR.execute(() -> {
            try {
                blocker.await();
            } catch (InterruptedException e) {
                // do nothing
            }
        });

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(mWakeLock).acquire(anyLong());
        verify(mWakeLock, never()).release();

        blocker.countDown();
        verify(listener, timeout(TIMEOUT_MS)).onLocationChanged(any(Location.class),
                nullable(IRemoteCallback.class));
        verify(mWakeLock).acquire(anyLong());
        verify(mWakeLock, timeout(TIMEOUT_MS)).release();
    }

    @Test
    public void testGetCurrentLocation() throws Exception {
        ArgumentCaptor<Location> locationCaptor = ArgumentCaptor.forClass(Location.class);

        ILocationCallback listener = createMockGetCurrentLocationListener();
        LocationRequest locationRequest = LocationRequest.createFromDeprecatedProvider(NAME, 0, 0,
                false);
        ICancellationSignal cancellationSignal = CancellationSignal.createTransport();
        mManager.getCurrentLocation(locationRequest, IDENTITY,
                PERMISSION_FINE, cancellationSignal, listener);

        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        mProvider.setProviderLocation(createLocation(NAME, mRandom));

        verify(listener, times(1)).onLocation(locationCaptor.capture());
        assertThat(locationCaptor.getValue()).isEqualTo(loc);
    }

    @Test
    public void testGetCurrentLocation_Cancel() throws Exception {
        ILocationCallback listener = createMockGetCurrentLocationListener();
        LocationRequest locationRequest = LocationRequest.createFromDeprecatedProvider(NAME, 0, 0,
                false);
        ICancellationSignal cancellationSignal = CancellationSignal.createTransport();
        mManager.getCurrentLocation(locationRequest, IDENTITY,
                PERMISSION_FINE, cancellationSignal, listener);

        cancellationSignal.cancel();
        mProvider.setProviderLocation(createLocation(NAME, mRandom));

        verify(listener, never()).onLocation(nullable(Location.class));
    }

    @Test
    public void testGetCurrentLocation_ProviderDisabled() throws Exception {
        ILocationCallback listener = createMockGetCurrentLocationListener();
        LocationRequest locationRequest = LocationRequest.createFromDeprecatedProvider(NAME, 0, 0,
                false);
        ICancellationSignal cancellationSignal = CancellationSignal.createTransport();
        mManager.getCurrentLocation(locationRequest, IDENTITY,
                PERMISSION_FINE, cancellationSignal, listener);

        mProvider.setProviderAllowed(false);
        mProvider.setProviderAllowed(true);
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, times(1)).onLocation(isNull());
    }

    @Test
    public void testGetCurrentLocation_ProviderAlreadyDisabled() throws Exception {
        mProvider.setProviderAllowed(false);

        ILocationCallback listener = createMockGetCurrentLocationListener();
        LocationRequest locationRequest = LocationRequest.createFromDeprecatedProvider(NAME, 0, 0,
                false);
        ICancellationSignal cancellationSignal = CancellationSignal.createTransport();
        mManager.getCurrentLocation(locationRequest, IDENTITY,
                PERMISSION_FINE, cancellationSignal, listener);

        mProvider.setProviderAllowed(true);
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, times(1)).onLocation(isNull());
    }

    @Test
    public void testGetCurrentLocation_LastLocation() throws Exception {
        ArgumentCaptor<Location> locationCaptor = ArgumentCaptor.forClass(Location.class);

        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);

        ILocationCallback listener = createMockGetCurrentLocationListener();
        LocationRequest locationRequest = LocationRequest.createFromDeprecatedProvider(NAME, 0, 0,
                false);
        ICancellationSignal cancellationSignal = CancellationSignal.createTransport();
        mManager.getCurrentLocation(locationRequest, IDENTITY,
                PERMISSION_FINE, cancellationSignal, listener);

        verify(listener, times(1)).onLocation(locationCaptor.capture());
        assertThat(locationCaptor.getValue()).isEqualTo(loc);
    }

    @Test
    public void testGetCurrentLocation_Timeout() throws Exception {
        ILocationCallback listener = createMockGetCurrentLocationListener();
        LocationRequest locationRequest = LocationRequest.createFromDeprecatedProvider(NAME, 0, 0,
                false);
        ICancellationSignal cancellationSignal = CancellationSignal.createTransport();
        mManager.getCurrentLocation(locationRequest, IDENTITY,
                PERMISSION_FINE, cancellationSignal, listener);

        ArgumentCaptor<OnAlarmListener> listenerCapture = ArgumentCaptor.forClass(
                OnAlarmListener.class);
        verify(mAlarmManager).set(eq(ELAPSED_REALTIME_WAKEUP), anyLong(),
                eq(WINDOW_EXACT), eq(0L), listenerCapture.capture(), any(Handler.class),
                any(WorkSource.class));
        listenerCapture.getValue().onAlarm();

        verify(listener, times(1)).onLocation(isNull());
    }

    @Test
    public void testLocationMonitoring() {
        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_LOCATION,
                IDENTITY.getPackageName())).isFalse();
        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_HIGH_POWER_LOCATION,
                IDENTITY.getPackageName())).isFalse();

        ILocationListener listener = createMockLocationListener();
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(NAME, 0, 0, false);
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_LOCATION,
                IDENTITY.getPackageName())).isTrue();
        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_HIGH_POWER_LOCATION,
                IDENTITY.getPackageName())).isTrue();

        mInjector.getAppForegroundHelper().setAppForeground(IDENTITY.getUid(), false);

        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_LOCATION,
                IDENTITY.getPackageName())).isTrue();
        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_HIGH_POWER_LOCATION,
                IDENTITY.getPackageName())).isFalse();

        mManager.unregisterLocationRequest(listener);

        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_LOCATION,
                IDENTITY.getPackageName())).isFalse();
        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_HIGH_POWER_LOCATION,
                IDENTITY.getPackageName())).isFalse();
    }

    @Test
    public void testProviderRequest() {
        assertThat(mProvider.getRequest().reportLocation).isFalse();
        assertThat(mProvider.getRequest().locationRequests).isEmpty();

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = LocationRequest.createFromDeprecatedProvider(NAME, 5, 0, false);
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);

        assertThat(mProvider.getRequest().reportLocation).isTrue();
        assertThat(mProvider.getRequest().locationRequests).containsExactly(request1);
        assertThat(mProvider.getRequest().locationSettingsIgnored).isFalse();
        assertThat(mProvider.getRequest().interval).isEqualTo(5);
        assertThat(mProvider.getRequest().lowPowerMode).isFalse();
        assertThat(mProvider.getRequest().workSource).isNotNull();

        ILocationListener listener2 = createMockLocationListener();
        LocationRequest request2 = LocationRequest.createFromDeprecatedProvider(NAME, 1, 0,
                false).setLowPowerMode(true);
        mManager.registerLocationRequest(request2, IDENTITY, PERMISSION_FINE, listener2);

        assertThat(mProvider.getRequest().reportLocation).isTrue();
        assertThat(mProvider.getRequest().locationRequests).containsExactly(request1, request2);
        assertThat(mProvider.getRequest().locationSettingsIgnored).isFalse();
        assertThat(mProvider.getRequest().interval).isEqualTo(1);
        assertThat(mProvider.getRequest().lowPowerMode).isFalse();
        assertThat(mProvider.getRequest().workSource).isNotNull();

        mManager.unregisterLocationRequest(listener1);

        assertThat(mProvider.getRequest().reportLocation).isTrue();
        assertThat(mProvider.getRequest().locationRequests).containsExactly(request2);
        assertThat(mProvider.getRequest().locationSettingsIgnored).isFalse();
        assertThat(mProvider.getRequest().interval).isEqualTo(1);
        assertThat(mProvider.getRequest().lowPowerMode).isTrue();
        assertThat(mProvider.getRequest().workSource).isNotNull();

        mManager.unregisterLocationRequest(listener2);

        assertThat(mProvider.getRequest().reportLocation).isFalse();
        assertThat(mProvider.getRequest().locationRequests).isEmpty();
    }

    @Test
    public void testProviderRequest_BackgroundThrottle() {
        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = LocationRequest.createFromDeprecatedProvider(NAME, 5, 0, false);
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);

        assertThat(mProvider.getRequest().interval).isEqualTo(5);

        mInjector.getAppForegroundHelper().setAppForeground(IDENTITY.getUid(), false);
        assertThat(mProvider.getRequest().interval).isEqualTo(
                mInjector.getSettingsHelper().getBackgroundThrottleIntervalMs());
    }

    @Test
    public void testProviderRequest_IgnoreLocationSettings() {
        mInjector.getSettingsHelper().setIgnoreSettingsPackageWhitelist(
                Collections.singleton(IDENTITY.getPackageName()));

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = LocationRequest.createFromDeprecatedProvider(NAME, 5, 0, false);
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);

        assertThat(mProvider.getRequest().reportLocation).isTrue();
        assertThat(mProvider.getRequest().interval).isEqualTo(5);
        assertThat(mProvider.getRequest().locationSettingsIgnored).isFalse();

        ILocationListener listener2 = createMockLocationListener();
        LocationRequest request2 = LocationRequest.createFromDeprecatedProvider(NAME, 1, 0,
                false).setLocationSettingsIgnored(true);
        mManager.registerLocationRequest(request2, IDENTITY, PERMISSION_FINE, listener2);

        assertThat(mProvider.getRequest().reportLocation).isTrue();
        assertThat(mProvider.getRequest().interval).isEqualTo(1);
        assertThat(mProvider.getRequest().locationSettingsIgnored).isTrue();
    }

    @Test
    public void testProviderRequest_IgnoreLocationSettings_ProviderDisabled() {
        mInjector.getSettingsHelper().setIgnoreSettingsPackageWhitelist(
                Collections.singleton(IDENTITY.getPackageName()));

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = LocationRequest.createFromDeprecatedProvider(NAME, 1, 0, false);
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);

        ILocationListener listener2 = createMockLocationListener();
        LocationRequest request2 = LocationRequest.createFromDeprecatedProvider(NAME, 5, 0,
                false).setLocationSettingsIgnored(true);
        mManager.registerLocationRequest(request2, IDENTITY, PERMISSION_FINE, listener2);

        mInjector.getSettingsHelper().setLocationEnabled(false, IDENTITY.getUserId());

        assertThat(mProvider.getRequest().reportLocation).isTrue();
        assertThat(mProvider.getRequest().locationRequests).containsExactly(request2);
        assertThat(mProvider.getRequest().interval).isEqualTo(5);
        assertThat(mProvider.getRequest().locationSettingsIgnored).isTrue();
    }

    @Test
    public void testProviderRequest_IgnoreLocationSettings_NoAllowlist() {
        mInjector.getSettingsHelper().setIgnoreSettingsPackageWhitelist(
                Collections.singleton(IDENTITY.getPackageName()));

        ILocationListener listener = createMockLocationListener();
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(NAME, 1, 0,
                false).setLocationSettingsIgnored(true);
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        mInjector.getSettingsHelper().setIgnoreSettingsPackageWhitelist(Collections.emptySet());

        assertThat(mProvider.getRequest().reportLocation).isTrue();
        assertThat(mProvider.getRequest().interval).isEqualTo(1);
        assertThat(mProvider.getRequest().locationSettingsIgnored).isFalse();
    }

    @Test
    public void testProviderRequest_BackgroundThrottle_IgnoreLocationSettings() {
        mInjector.getSettingsHelper().setIgnoreSettingsPackageWhitelist(
                Collections.singleton(IDENTITY.getPackageName()));

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = LocationRequest.createFromDeprecatedProvider(NAME, 5, 0,
                false).setLocationSettingsIgnored(true);
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);

        assertThat(mProvider.getRequest().interval).isEqualTo(5);

        mInjector.getAppForegroundHelper().setAppForeground(IDENTITY.getUid(), false);
        assertThat(mProvider.getRequest().interval).isEqualTo(5);
    }

    @Test
    public void testProviderRequest_BatterySaver_ScreenOnOff() {
        mInjector.getLocationPowerSaveModeHelper().setLocationPowerSaveMode(
                LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF);

        ILocationListener listener = createMockLocationListener();
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(NAME, 5, 0, false);
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        assertThat(mProvider.getRequest().reportLocation).isTrue();

        mInjector.getScreenInteractiveHelper().setScreenInteractive(false);
        assertThat(mProvider.getRequest().reportLocation).isFalse();
    }

    private ILocationListener createMockLocationListener() {
        return spy(new ILocationListener.Stub() {
            @Override
            public void onLocationChanged(Location location, IRemoteCallback onCompleteCallback) {
                if (onCompleteCallback != null) {
                    try {
                        onCompleteCallback.sendResult(null);
                    } catch (RemoteException e) {
                        e.rethrowFromSystemServer();
                    }
                }
            }

            @Override
            public void onProviderEnabledChanged(String provider, boolean enabled) {
            }
        });
    }

    private ILocationCallback createMockGetCurrentLocationListener() {
        return spy(new ILocationCallback.Stub() {
            @Override
            public void onLocation(Location location) {
            }
        });
    }

    private static class TestProvider extends AbstractLocationProvider {

        private ProviderRequest mProviderRequest = ProviderRequest.EMPTY_REQUEST;

        TestProvider(ProviderProperties properties, CallerIdentity identity) {
            super(DIRECT_EXECUTOR, identity);
            setProperties(properties);
        }

        public void setProviderAllowed(boolean allowed) {
            setAllowed(allowed);
        }

        public void setProviderLocation(Location l) {
            reportLocation(new Location(l));
        }

        public ProviderRequest getRequest() {
            return mProviderRequest;
        }

        @Override
        public void onSetRequest(ProviderRequest request) {
            mProviderRequest = request;
        }

        @Override
        protected void onExtraCommand(int uid, int pid, String command, Bundle extras) {
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        }
    }
}
