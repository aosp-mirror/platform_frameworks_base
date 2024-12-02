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

package com.android.server.location.provider;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.LOCATION_BYPASS;
import static android.app.AppOpsManager.OP_FINE_LOCATION;
import static android.app.AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION;
import static android.app.AppOpsManager.OP_MONITOR_LOCATION;
import static android.content.pm.PackageManager.FEATURE_AUTOMOTIVE;
import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationRequest.PASSIVE_INTERVAL;
import static android.location.provider.ProviderProperties.POWER_USAGE_HIGH;
import static android.os.PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF;

import static androidx.test.ext.truth.location.LocationSubject.assertThat;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;
import static com.android.server.location.LocationPermissions.PERMISSION_COARSE;
import static com.android.server.location.LocationPermissions.PERMISSION_FINE;
import static com.android.server.location.LocationUtils.createLocation;
import static com.android.server.location.LocationUtils.createLocationResult;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.ILocationCallback;
import android.location.ILocationListener;
import android.location.LastLocationRequest;
import android.location.Location;
import android.location.LocationManagerInternal;
import android.location.LocationManagerInternal.ProviderEnabledListener;
import android.location.LocationRequest;
import android.location.LocationResult;
import android.location.flags.Flags;
import android.location.provider.IProviderRequestListener;
import android.location.provider.IS2LevelCallback;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Bundle;
import android.os.ICancellationSignal;
import android.os.IRemoteCallback;
import android.os.PackageTagsList;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.WorkSource;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.location.fudger.LocationFudgerCache;
import com.android.server.location.injector.FakeUserInfoHelper;
import com.android.server.location.injector.TestInjector;
import com.android.server.location.provider.proxy.ProxyPopulationDensityProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
    private static final ProviderProperties PROPERTIES = new ProviderProperties.Builder()
            .setHasAltitudeSupport(true)
            .setHasSpeedSupport(true)
            .setHasBearingSupport(true)
            .setPowerUsage(POWER_USAGE_HIGH)
            .setAccuracy(ProviderProperties.ACCURACY_FINE)
            .build();
    private static final CallerIdentity PROVIDER_IDENTITY = CallerIdentity.forTest(CURRENT_USER, 1,
            "mypackage", "attribution");
    private static final CallerIdentity IDENTITY = CallerIdentity.forTest(CURRENT_USER, 1,
            "mypackage", "attribution", "listener");
    private static final WorkSource WORK_SOURCE = new WorkSource(IDENTITY.getUid());
    private static final String MISSING_PERMISSION = "missing_permission";

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private Random mRandom;

    @Mock
    private LocationProviderManager.StateChangedListener mStateChangedListener;
    @Mock
    private LocationManagerInternal mInternal;
    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private PackageManager mPackageManager;
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
        doReturn(mResources).when(mContext).getResources();
        doReturn(mPackageManager).when(mContext).getPackageManager();
        doReturn(mPowerManager).when(mContext).getSystemService(PowerManager.class);
        doReturn(ApplicationProvider.getApplicationContext()).when(
                mContext).getApplicationContext();
        doReturn(mWakeLock).when(mPowerManager).newWakeLock(anyInt(), anyString());
        doReturn(PackageManager.PERMISSION_DENIED)
                .when(mContext)
                .checkCallingOrSelfPermission(MISSING_PERMISSION);

        mInjector = new TestInjector(mContext);
        mInjector.getUserInfoHelper().setUserVisible(CURRENT_USER, true);
        mInjector.getUserInfoHelper().startUser(OTHER_USER);
        mInjector.getUserInfoHelper().setUserVisible(OTHER_USER, true);

        mPassive = new PassiveLocationProviderManager(mContext, mInjector);
        mPassive.startManager(null);
        mPassive.setRealProvider(new PassiveLocationProvider(mContext));

        createManager(NAME);
    }

    private void createManager(String name) {
        createManager(name, Collections.emptyList());
    }

    private void createManager(String name, Collection<String> requiredPermissions) {
        mStateChangedListener = mock(LocationProviderManager.StateChangedListener.class);

        mProvider = new TestProvider(PROPERTIES, PROVIDER_IDENTITY);
        mProvider.setProviderAllowed(true);

        mManager =
                new LocationProviderManager(
                        mContext, mInjector, name, mPassive, requiredPermissions);
        mManager.startManager(mStateChangedListener);
        mManager.setRealProvider(mProvider);
    }

    @After
    public void tearDown() throws Exception {
        DeviceConfig.resetToDefaults(Settings.RESET_MODE_PACKAGE_DEFAULTS,
                DeviceConfig.NAMESPACE_LOCATION);
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
        assertThat(mManager.getProviderIdentity()).isEqualTo(IDENTITY);
        assertThat(mManager.hasProvider()).isTrue();

        ProviderProperties newProperties = new ProviderProperties.Builder()
                .setHasNetworkRequirement(true)
                .setHasSatelliteRequirement(true)
                .setHasCellRequirement(true)
                .setHasMonetaryCost(true)
                .setPowerUsage(POWER_USAGE_HIGH)
                .setAccuracy(ProviderProperties.ACCURACY_COARSE)
                .build();
        mProvider.setProperties(newProperties);
        assertThat(mManager.getProperties()).isEqualTo(newProperties);

        CallerIdentity newIdentity = CallerIdentity.forTest(OTHER_USER, 1, "otherpackage",
                "otherattribution");
        mProvider.setIdentity(newIdentity);
        assertThat(mManager.getProviderIdentity()).isEqualTo(newIdentity);

        mManager.setRealProvider(null);
        assertThat(mManager.hasProvider()).isFalse();
    }

    @Test
    public void testStateChangedListener() {
        mProvider.setExtraAttributionTags(Collections.singleton("extra"));

        ArgumentCaptor<AbstractLocationProvider.State> captorOld = ArgumentCaptor.forClass(
                AbstractLocationProvider.State.class);
        ArgumentCaptor<AbstractLocationProvider.State> captorNew = ArgumentCaptor.forClass(
                AbstractLocationProvider.State.class);
        verify(mStateChangedListener, timeout(TIMEOUT_MS).times(2)).onStateChanged(eq(NAME),
                captorOld.capture(), captorNew.capture());

        assertThat(captorOld.getAllValues().get(1).extraAttributionTags).isEmpty();
        assertThat(captorNew.getAllValues().get(1).extraAttributionTags).containsExactly("extra");
    }

    @Test
    public void testRemoveProvider() {
        mManager.setRealProvider(null);
        assertThat(mManager.hasProvider()).isFalse();
    }

    @Test
    public void testIsEnabled() {
        assertThat(mManager.isEnabled(CURRENT_USER)).isTrue();
        assertThat(mManager.isEnabled(OTHER_USER)).isTrue();

        mInjector.getSettingsHelper().setLocationEnabled(false, CURRENT_USER);
        assertThat(mManager.isEnabled(CURRENT_USER)).isFalse();
        assertThat(mManager.isEnabled(OTHER_USER)).isTrue();

        mInjector.getSettingsHelper().setLocationEnabled(true, CURRENT_USER);
        mProvider.setAllowed(false);
        assertThat(mManager.isEnabled(CURRENT_USER)).isFalse();
        assertThat(mManager.isEnabled(OTHER_USER)).isFalse();

        mProvider.setAllowed(true);
        assertThat(mManager.isEnabled(CURRENT_USER)).isTrue();
        assertThat(mManager.isEnabled(OTHER_USER)).isTrue();
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
        verify(listener, timeout(TIMEOUT_MS).times(1)).onProviderEnabledChanged(NAME, OTHER_USER,
                false);

        mProvider.setAllowed(true);
        verify(listener, timeout(TIMEOUT_MS).times(2)).onProviderEnabledChanged(NAME, CURRENT_USER,
                true);
        verify(listener, timeout(TIMEOUT_MS).times(1)).onProviderEnabledChanged(NAME, OTHER_USER,
                true);

        mManager.removeEnabledListener(listener);
        mInjector.getSettingsHelper().setLocationEnabled(false, CURRENT_USER);
        verifyNoMoreInteractions(listener);
    }

    @Test
    public void testGetLastLocation_Fine() {
        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isNull();

        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isEqualTo(loc);
    }

    @Test
    public void testGetLastLocation_Coarse() {
        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isNull();

        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        Location coarse = mManager.getLastLocation(new LastLocationRequest.Builder().build(),
                IDENTITY, PERMISSION_COARSE);
        assertThat(coarse).isNotEqualTo(loc);
        assertThat(coarse).isNearby(loc, 5000);
    }

    @Test
    public void testGetLastLocation_InvisibleUser() {
        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);

        mInjector.getUserInfoHelper().setUserVisible(CURRENT_USER, false);
        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isNull();

        mInjector.getUserInfoHelper().setUserVisible(CURRENT_USER, true);
        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isEqualTo(loc);
    }

    @Test
    public void testGetLastLocation_Bypass() {
        mInjector.getSettingsHelper().setIgnoreSettingsAllowlist(
                new PackageTagsList.Builder().add(
                        IDENTITY.getPackageName()).build());

        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isNull();
        assertThat(mManager.getLastLocation(
                new LastLocationRequest.Builder().setLocationSettingsIgnored(true).build(),
                IDENTITY, PERMISSION_FINE)).isNull();

        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isEqualTo(loc);
        assertThat(mManager.getLastLocation(
                new LastLocationRequest.Builder().setLocationSettingsIgnored(true).build(),
                IDENTITY, PERMISSION_FINE)).isEqualTo(
                loc);

        mProvider.setProviderAllowed(false);
        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isNull();
        assertThat(mManager.getLastLocation(
                new LastLocationRequest.Builder().setLocationSettingsIgnored(true).build(),
                IDENTITY, PERMISSION_FINE)).isEqualTo(
                loc);

        loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isNull();
        assertThat(mManager.getLastLocation(
                new LastLocationRequest.Builder().setLocationSettingsIgnored(true).build(),
                IDENTITY, PERMISSION_FINE)).isEqualTo(
                loc);

        mProvider.setProviderAllowed(true);
        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isNull();
        assertThat(mManager.getLastLocation(
                new LastLocationRequest.Builder().setLocationSettingsIgnored(true).build(),
                IDENTITY, PERMISSION_FINE)).isEqualTo(
                loc);

        loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isEqualTo(loc);
        assertThat(mManager.getLastLocation(
                new LastLocationRequest.Builder().setLocationSettingsIgnored(true).build(),
                IDENTITY, PERMISSION_FINE)).isEqualTo(
                loc);

        mInjector.getSettingsHelper().setIgnoreSettingsAllowlist(
                new PackageTagsList.Builder().build());
        mProvider.setProviderAllowed(false);

        assertThat(mManager.getLastLocation(
                new LastLocationRequest.Builder().setLocationSettingsIgnored(true).build(),
                IDENTITY, PERMISSION_FINE)).isNull();
    }

    @Test
    public void testGetLastLocation_ClearOnMockRemoval() {
        MockLocationProvider mockProvider = new MockLocationProvider(PROPERTIES, PROVIDER_IDENTITY,
                Collections.emptySet());
        mockProvider.setAllowed(true);
        mManager.setMockProvider(mockProvider);

        Location loc = createLocation(NAME, mRandom);
        mockProvider.setProviderLocation(loc);
        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isEqualTo(loc);

        mManager.setMockProvider(null);
        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isNull();
    }

    @Test
    public void testInjectLastLocation() {
        Location loc1 = createLocation(NAME, mRandom);
        mManager.injectLastLocation(loc1, CURRENT_USER);

        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isEqualTo(loc1);

        Location loc2 = createLocation(NAME, mRandom);
        mManager.injectLastLocation(loc2, CURRENT_USER);

        assertThat(mManager.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isEqualTo(loc1);
    }

    @Test
    public void testPassive_Listener() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
        mPassive.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        LocationResult loc = createLocationResult(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        verify(listener).onLocationChanged(eq(loc.asList()), nullable(IRemoteCallback.class));
    }

    @Test
    public void testPassive_LastLocation() {
        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);

        assertThat(mPassive.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isEqualTo(loc);
    }

    @Test
    public void testRegisterListener() throws Exception {
        ILocationListener listener = createMockLocationListener();
        mManager.registerLocationRequest(
                new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build(),
                IDENTITY,
                PERMISSION_FINE,
                listener);

        LocationResult loc = createLocationResult(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        verify(listener).onLocationChanged(eq(loc.asList()), nullable(IRemoteCallback.class));

        mInjector.getSettingsHelper().setLocationEnabled(false, CURRENT_USER);
        verify(listener, timeout(TIMEOUT_MS).times(1)).onProviderEnabledChanged(NAME, false);
        loc = createLocationResult(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        verify(listener, times(1)).onLocationChanged(any(List.class),
                nullable(IRemoteCallback.class));

        mInjector.getSettingsHelper().setLocationEnabled(true, CURRENT_USER);
        verify(listener, timeout(TIMEOUT_MS).times(1)).onProviderEnabledChanged(NAME, true);

        mProvider.setAllowed(false);
        verify(listener, timeout(TIMEOUT_MS).times(2)).onProviderEnabledChanged(NAME, false);
        loc = createLocationResult(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        verify(listener, times(1)).onLocationChanged(any(List.class),
                nullable(IRemoteCallback.class));

        mProvider.setAllowed(true);
        verify(listener, timeout(TIMEOUT_MS).times(2)).onProviderEnabledChanged(NAME, true);

        loc = createLocationResult(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        verify(listener).onLocationChanged(eq(loc.asList()), nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_SameProcess() throws Exception {
        CallerIdentity identity = CallerIdentity.forTest(CURRENT_USER, Process.myPid(), "mypackage",
                "attribution", "listener");

        ILocationListener listener = createMockLocationListener();
        mManager.registerLocationRequest(
                new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build(),
                identity,
                PERMISSION_FINE,
                listener);

        LocationResult loc = createLocationResult(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        verify(listener, timeout(TIMEOUT_MS).times(1)).onLocationChanged(eq(loc.asList()),
                nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_Unregister() throws Exception {
        ILocationListener listener = createMockLocationListener();
        mManager.registerLocationRequest(
                new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build(),
                IDENTITY,
                PERMISSION_FINE,
                listener);
        mManager.unregisterLocationRequest(listener);

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, never()).onLocationChanged(any(List.class),
                nullable(IRemoteCallback.class));

        mInjector.getSettingsHelper().setLocationEnabled(false, CURRENT_USER);
        verify(listener, after(TIMEOUT_MS).never()).onProviderEnabledChanged(NAME, false);
    }

    @Test
    public void testRegisterListener_Unregister_SameProcess() throws Exception {
        CallerIdentity identity = CallerIdentity.forTest(CURRENT_USER, Process.myPid(), "mypackage",
                "attribution", "listener");

        ILocationListener listener = createMockLocationListener();
        mManager.registerLocationRequest(
                new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build(),
                identity,
                PERMISSION_FINE,
                listener);

        CountDownLatch blocker = new CountDownLatch(1);
        FgThread.getExecutor().execute(() -> {
            try {
                blocker.await();
            } catch (InterruptedException e) {
                // do nothing
            }
        });

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mManager.unregisterLocationRequest(listener);
        blocker.countDown();
        verify(listener, after(TIMEOUT_MS).never()).onLocationChanged(any(List.class),
                nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_NumUpdates() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(0)
                .setMaxUpdates(5)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mProvider.setProviderLocation(createLocation(NAME, mRandom));

        verify(listener, times(5)).onLocationChanged(any(List.class),
                nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_InvisibleUser() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(0)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        mInjector.getUserInfoHelper().setUserVisible(CURRENT_USER, false);
        mProvider.setProviderLocation(createLocationResult(NAME, mRandom));
        verify(listener, never()).onLocationChanged(any(List.class),
                nullable(IRemoteCallback.class));

        mInjector.getUserInfoHelper().setUserVisible(CURRENT_USER, true);
        LocationResult loc = createLocationResult(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        verify(listener).onLocationChanged(eq(loc.asList()), nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_ExpiringAlarm() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(0)
                .setDurationMillis(5000)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        mInjector.getAlarmHelper().incrementAlarmTime(5000);
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, never()).onLocationChanged(any(List.class),
                nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_ExpiringNoAlarm() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(0)
                .setDurationMillis(25)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        Thread.sleep(25);

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, never()).onLocationChanged(any(List.class),
                nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_FastestInterval() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(5000)
                .setMinUpdateIntervalMillis(5000)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mProvider.setProviderLocation(createLocation(NAME, mRandom));

        verify(listener, times(1)).onLocationChanged(
                any(List.class), nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_SmallestDisplacement() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(5000)
                .setMinUpdateDistanceMeters(1f)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        mProvider.setProviderLocation(loc);

        verify(listener, times(1)).onLocationChanged(
                any(List.class), nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_NoteOpFailure() throws Exception {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        mInjector.getAppOpsHelper().setAppOpAllowed(OP_FINE_LOCATION, IDENTITY.getPackageName(),
                false);

        mProvider.setProviderLocation(createLocation(NAME, mRandom));

        verify(listener, never()).onLocationChanged(any(List.class),
                nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_Wakelock() throws Exception {
        CallerIdentity identity = CallerIdentity.forTest(CURRENT_USER, Process.myPid(), "mypackage",
                "attribution", "listener");

        ILocationListener listener = createMockLocationListener();
        mManager.registerLocationRequest(
                new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build(),
                identity,
                PERMISSION_FINE,
                listener);

        CountDownLatch blocker = new CountDownLatch(1);
        FgThread.getExecutor().execute(() -> {
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
        verify(listener, timeout(TIMEOUT_MS)).onLocationChanged(any(List.class),
                nullable(IRemoteCallback.class));
        verify(mWakeLock).acquire(anyLong());
        verify(mWakeLock, timeout(TIMEOUT_MS)).release();
    }

    @Test
    public void testRegisterListener_Coarse() throws Exception {
        ILocationListener listener = createMockLocationListener();
        mManager.registerLocationRequest(
                new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build(),
                IDENTITY,
                PERMISSION_COARSE,
                listener);

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, times(1))
                .onLocationChanged(any(List.class), nullable(IRemoteCallback.class));
    }

    @Test
    public void testRegisterListener_Coarse_Passive() throws Exception {
        ILocationListener listener = createMockLocationListener();
        mManager.registerLocationRequest(
                new LocationRequest.Builder(PASSIVE_INTERVAL)
                        .setMinUpdateIntervalMillis(0)
                        .setWorkSource(WORK_SOURCE).build(),
                IDENTITY,
                PERMISSION_COARSE,
                listener);

        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, times(1))
                .onLocationChanged(any(List.class), nullable(IRemoteCallback.class));
    }

    @Test
    public void testProviderRequestListener() throws Exception {
        IProviderRequestListener requestListener = mock(IProviderRequestListener.class);
        mManager.addProviderRequestListener(requestListener);

        ILocationListener locationListener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(1).setWorkSource(
                WORK_SOURCE).build();
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, locationListener);

        verify(requestListener, timeout(TIMEOUT_MS).times(1)).onProviderRequestChanged(anyString(),
                any(ProviderRequest.class));

        mManager.unregisterLocationRequest(locationListener);
        mManager.removeProviderRequestListener(requestListener);
    }

    @Test
    public void testGetCurrentLocation() throws Exception {
        ILocationCallback listener = createMockGetCurrentLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
        mManager.getCurrentLocation(request, IDENTITY, PERMISSION_FINE, listener);

        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, times(1)).onLocation(loc);
    }

    @Test
    public void testGetCurrentLocation_Cancel() throws Exception {
        ILocationCallback listener = createMockGetCurrentLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
        ICancellationSignal cancellationSignal = mManager.getCurrentLocation(request,
                IDENTITY, PERMISSION_FINE, listener);

        cancellationSignal.cancel();
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, never()).onLocation(nullable(Location.class));
    }

    @Test
    public void testGetCurrentLocation_ProviderDisabled() throws Exception {
        ILocationCallback listener = createMockGetCurrentLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
        mManager.getCurrentLocation(request, IDENTITY, PERMISSION_FINE, listener);

        mProvider.setProviderAllowed(false);
        mProvider.setProviderAllowed(true);
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, times(1)).onLocation(isNull());
    }

    @Test
    public void testGetCurrentLocation_ProviderAlreadyDisabled() throws Exception {
        mProvider.setProviderAllowed(false);

        ILocationCallback listener = createMockGetCurrentLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
        mManager.getCurrentLocation(request, IDENTITY, PERMISSION_FINE, listener);

        mProvider.setProviderAllowed(true);
        mProvider.setProviderLocation(createLocation(NAME, mRandom));
        verify(listener, times(1)).onLocation(isNull());
    }

    @Test
    public void testGetCurrentLocation_LastLocation() throws Exception {
        Location loc = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(loc);

        ILocationCallback listener = createMockGetCurrentLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
        mManager.getCurrentLocation(request, IDENTITY, PERMISSION_FINE, listener);
        verify(listener, times(1)).onLocation(eq(loc));
    }

    @Test
    public void testGetCurrentLocation_Timeout() throws Exception {
        ILocationCallback listener = createMockGetCurrentLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
        mManager.getCurrentLocation(request, IDENTITY, PERMISSION_FINE, listener);

        mInjector.getAlarmHelper().incrementAlarmTime(60000);
        verify(listener, times(1)).onLocation(isNull());
    }

    @Test
    public void testGetCurrentLocation_InvisibleUser() throws Exception {
        mInjector.getUserInfoHelper().setUserVisible(CURRENT_USER, false);

        ILocationCallback listener = createMockGetCurrentLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
        mManager.getCurrentLocation(request, IDENTITY, PERMISSION_FINE, listener);

        verify(listener).onLocation(isNull());
    }

    @Test
    public void testFlush() throws Exception {
        ILocationListener listener = createMockLocationListener();
        mManager.registerLocationRequest(
                new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build(),
                IDENTITY,
                PERMISSION_FINE,
                listener);

        mManager.flush(listener, 99);

        LocationResult loc = createLocationResult(NAME, mRandom);
        mProvider.setProviderLocation(loc);
        mProvider.completeFlushes();

        InOrder inOrder = inOrder(listener);
        inOrder.verify(listener).onLocationChanged(eq(loc.asList()), any(IRemoteCallback.class));
        inOrder.verify(listener).onFlushComplete(99);
    }

    @Test
    public void testFlush_UnknownKey() {
        assertThrows(IllegalArgumentException.class,
                () -> mManager.flush(createMockLocationListener(), 0));
    }

    @Test
    public void testLocationMonitoring() {
        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_LOCATION,
                IDENTITY.getPackageName())).isFalse();
        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_HIGH_POWER_LOCATION,
                IDENTITY.getPackageName())).isFalse();

        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
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
    public void testLocationMonitoring_multipleIdentities() {
        CallerIdentity identity1 = CallerIdentity.forTest(CURRENT_USER, 1,
                "mypackage", "attribution", "listener1");
        CallerIdentity identity2 = CallerIdentity.forTest(CURRENT_USER, 1,
                "mypackage", "attribution", "listener2");

        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_LOCATION,
                IDENTITY.getPackageName())).isFalse();
        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_HIGH_POWER_LOCATION,
                IDENTITY.getPackageName())).isFalse();

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = new LocationRequest.Builder(0).setWorkSource(
                WORK_SOURCE).build();
        mManager.registerLocationRequest(request1, identity1, PERMISSION_FINE, listener1);

        ILocationListener listener2 = createMockLocationListener();
        LocationRequest request2 = new LocationRequest.Builder(0).setWorkSource(
                WORK_SOURCE).build();
        mManager.registerLocationRequest(request2, identity2, PERMISSION_FINE, listener2);

        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_LOCATION,
                "mypackage")).isTrue();
        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_HIGH_POWER_LOCATION,
                "mypackage")).isTrue();

        mManager.unregisterLocationRequest(listener2);

        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_LOCATION,
                "mypackage")).isTrue();
        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_HIGH_POWER_LOCATION,
                "mypackage")).isTrue();

        mManager.unregisterLocationRequest(listener1);

        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_LOCATION,
                "mypackage")).isFalse();
        assertThat(mInjector.getAppOpsHelper().isAppOpStarted(OP_MONITOR_HIGH_POWER_LOCATION,
                "mypackage")).isFalse();
    }

    @Test
    public void testProviderRequest() {
        assertThat(mProvider.getRequest().isActive()).isFalse();

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = new LocationRequest.Builder(5).setWorkSource(
                WORK_SOURCE).build();
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);

        assertThat(mProvider.getRequest().isActive()).isTrue();
        assertThat(mProvider.getRequest().isLocationSettingsIgnored()).isFalse();
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(5);
        assertThat(mProvider.getRequest().isLowPower()).isFalse();
        assertThat(mProvider.getRequest().getWorkSource()).isNotNull();

        ILocationListener listener2 = createMockLocationListener();
        LocationRequest request2 = new LocationRequest.Builder(1)
                .setLowPower(true)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request2, IDENTITY, PERMISSION_FINE, listener2);

        assertThat(mProvider.getRequest().isActive()).isTrue();
        assertThat(mProvider.getRequest().isLocationSettingsIgnored()).isFalse();
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(1);
        assertThat(mProvider.getRequest().isLowPower()).isFalse();
        assertThat(mProvider.getRequest().getWorkSource()).isNotNull();

        mManager.unregisterLocationRequest(listener1);

        assertThat(mProvider.getRequest().isActive()).isTrue();
        assertThat(mProvider.getRequest().isLocationSettingsIgnored()).isFalse();
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(1);
        assertThat(mProvider.getRequest().isLowPower()).isTrue();
        assertThat(mProvider.getRequest().getWorkSource()).isNotNull();

        mManager.unregisterLocationRequest(listener2);

        assertThat(mProvider.getRequest().isActive()).isFalse();
    }

    @Test
    public void testProviderRequest_DelayedRequest() throws Exception {
        mProvider.setProviderLocation(createLocation(NAME, mRandom));

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = new LocationRequest.Builder(60000)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);

        verify(listener1).onLocationChanged(any(List.class),
                nullable(IRemoteCallback.class));

        assertThat(mProvider.getRequest().isActive()).isFalse();

        mInjector.getAlarmHelper().incrementAlarmTime(60000);
        assertThat(mProvider.getRequest().isActive()).isTrue();
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(60000);
    }

    @Test
    public void testProviderRequest_DelayedRequest_Remove() {
        mProvider.setProviderLocation(createLocation(NAME, mRandom));

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = new LocationRequest.Builder(60000)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);
        mManager.unregisterLocationRequest(listener1);

        mInjector.getAlarmHelper().incrementAlarmTime(60000);
        assertThat(mProvider.getRequest().isActive()).isFalse();
    }

    @Test
    public void testProviderRequest_SpamRequesting() {
        mProvider.setProviderLocation(createLocation(NAME, mRandom));

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = new LocationRequest.Builder(60000)
                .setWorkSource(WORK_SOURCE)
                .build();

        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);
        assertThat(mProvider.getRequest().isActive()).isFalse();
        mManager.unregisterLocationRequest(listener1);
        assertThat(mProvider.getRequest().isActive()).isFalse();
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);
        assertThat(mProvider.getRequest().isActive()).isFalse();
        mManager.unregisterLocationRequest(listener1);
        assertThat(mProvider.getRequest().isActive()).isFalse();
    }

    @Test
    public void testProviderRequest_BackgroundThrottle() {
        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = new LocationRequest.Builder(5)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);

        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(5);

        mInjector.getAppForegroundHelper().setAppForeground(IDENTITY.getUid(), false);
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(
                mInjector.getSettingsHelper().getBackgroundThrottleIntervalMs());
    }

    @Test
    public void testProviderRequest_InvisibleUser() {
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(5)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        mInjector.getUserInfoHelper().setUserVisible(CURRENT_USER, false);
        assertThat(mProvider.getRequest().isActive()).isFalse();

        mInjector.getUserInfoHelper().setUserVisible(CURRENT_USER, true);
        assertThat(mProvider.getRequest().isActive()).isTrue();
    }

    @Test
    public void testProviderRequest_IgnoreLocationSettings() {
        mInjector.getSettingsHelper().setIgnoreSettingsAllowlist(
                new PackageTagsList.Builder().add(
                        IDENTITY.getPackageName()).build());

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = new LocationRequest.Builder(5)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);

        assertThat(mProvider.getRequest().isActive()).isTrue();
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(5);
        assertThat(mProvider.getRequest().isLocationSettingsIgnored()).isFalse();

        ILocationListener listener2 = createMockLocationListener();
        LocationRequest request2 = new LocationRequest.Builder(1)
                .setLocationSettingsIgnored(true)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request2, IDENTITY, PERMISSION_FINE, listener2);

        assertThat(mProvider.getRequest().isActive()).isTrue();
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(1);
        assertThat(mProvider.getRequest().isLocationSettingsIgnored()).isTrue();
    }

    @Test
    public void testProviderRequest_IgnoreLocationSettings_ProviderDisabled() {
        mInjector.getSettingsHelper().setIgnoreSettingsAllowlist(
                new PackageTagsList.Builder().add(
                        IDENTITY.getPackageName()).build());

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = new LocationRequest.Builder(1)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);

        ILocationListener listener2 = createMockLocationListener();
        LocationRequest request2 = new LocationRequest.Builder(5)
                .setLocationSettingsIgnored(true)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request2, IDENTITY, PERMISSION_FINE, listener2);

        mInjector.getSettingsHelper().setLocationEnabled(false, IDENTITY.getUserId());

        assertThat(mProvider.getRequest().isActive()).isTrue();
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(5);
        assertThat(mProvider.getRequest().isLocationSettingsIgnored()).isTrue();
    }

    @Test
    public void testProviderRequest_IgnoreLocationSettings_NoAllowlist() {
        mInjector.getSettingsHelper().setIgnoreSettingsAllowlist(
                new PackageTagsList.Builder().add(
                        IDENTITY.getPackageName()).build());

        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(1)
                .setLocationSettingsIgnored(true)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        mInjector.getSettingsHelper().setIgnoreSettingsAllowlist(
                new PackageTagsList.Builder().build());

        assertThat(mProvider.getRequest().isActive()).isTrue();
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(1);
        assertThat(mProvider.getRequest().isLocationSettingsIgnored()).isFalse();
    }

    @Test
    public void testProviderRequest_IgnoreLocationSettings_LocationBypass() {
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_LOCATION_BYPASS);

        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(mContext)
                .checkPermission(LOCATION_BYPASS, IDENTITY.getPid(), IDENTITY.getUid());
        mInjector.getLocationPermissionsHelper()
                .revokePermission(IDENTITY.getPackageName(), ACCESS_FINE_LOCATION);
        mInjector.getLocationPermissionsHelper()
                .revokePermission(IDENTITY.getPackageName(), ACCESS_COARSE_LOCATION);
        mInjector
                .getSettingsHelper()
                .setIgnoreSettingsAllowlist(
                        new PackageTagsList.Builder().add(IDENTITY.getPackageName()).build());

        ILocationListener listener = createMockLocationListener();
        LocationRequest request =
                new LocationRequest.Builder(1)
                        .setLocationSettingsIgnored(true)
                        .setWorkSource(WORK_SOURCE)
                        .build();
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        assertThat(mProvider.getRequest().isActive()).isFalse();
    }

    @Test
    public void testProviderRequest_IgnoreLocationSettings_LocationBypass_EmergencyCall() {
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_LOCATION_BYPASS);

        doReturn(PackageManager.PERMISSION_GRANTED)
                .when(mContext)
                .checkPermission(LOCATION_BYPASS, IDENTITY.getPid(), IDENTITY.getUid());
        mInjector.getLocationPermissionsHelper()
                .revokePermission(IDENTITY.getPackageName(), ACCESS_FINE_LOCATION);
        mInjector.getLocationPermissionsHelper()
                .revokePermission(IDENTITY.getPackageName(), ACCESS_COARSE_LOCATION);
        mInjector.getEmergencyHelper().setInEmergency(true);
        mInjector
                .getSettingsHelper()
                .setIgnoreSettingsAllowlist(
                        new PackageTagsList.Builder().add(IDENTITY.getPackageName()).build());

        ILocationListener listener = createMockLocationListener();
        LocationRequest request =
                new LocationRequest.Builder(1)
                        .setLocationSettingsIgnored(true)
                        .setWorkSource(WORK_SOURCE)
                        .build();
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        assertThat(mProvider.getRequest().isActive()).isTrue();
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(1);
        assertThat(mProvider.getRequest().isLocationSettingsIgnored()).isTrue();
    }

    @Test
    public void testProviderRequest_BackgroundThrottle_IgnoreLocationSettings() {
        mInjector.getSettingsHelper().setIgnoreSettingsAllowlist(
                new PackageTagsList.Builder().add(
                        IDENTITY.getPackageName()).build());

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = new LocationRequest.Builder(5)
                .setLocationSettingsIgnored(true)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);

        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(5);

        mInjector.getAppForegroundHelper().setAppForeground(IDENTITY.getUid(), false);
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(5);
    }

    @Test
    public void testProviderRequest_AdasGnssBypass() {
        doReturn(true).when(mPackageManager).hasSystemFeature(FEATURE_AUTOMOTIVE);
        doReturn(true).when(mResources).getBoolean(R.bool.config_defaultAdasGnssLocationEnabled);

        mInjector.getSettingsHelper().setAdasSettingsAllowlist(
                new PackageTagsList.Builder().add(
                        IDENTITY.getPackageName()).build());

        createManager(GPS_PROVIDER);

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = new LocationRequest.Builder(5)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);

        assertThat(mProvider.getRequest().isActive()).isTrue();
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(5);
        assertThat(mProvider.getRequest().isAdasGnssBypass()).isFalse();

        ILocationListener listener2 = createMockLocationListener();
        LocationRequest request2 = new LocationRequest.Builder(1)
                .setAdasGnssBypass(true)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request2, IDENTITY, PERMISSION_FINE, listener2);

        assertThat(mProvider.getRequest().isActive()).isTrue();
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(1);
        assertThat(mProvider.getRequest().isAdasGnssBypass()).isTrue();
    }

    @Test
    public void testProviderRequest_AdasGnssBypass_ProviderDisabled() {
        doReturn(true).when(mPackageManager).hasSystemFeature(FEATURE_AUTOMOTIVE);
        doReturn(true).when(mResources).getBoolean(R.bool.config_defaultAdasGnssLocationEnabled);

        mInjector.getSettingsHelper().setAdasSettingsAllowlist(
                new PackageTagsList.Builder().add(
                        IDENTITY.getPackageName()).build());

        createManager(GPS_PROVIDER);

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = new LocationRequest.Builder(1)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);

        ILocationListener listener2 = createMockLocationListener();
        LocationRequest request2 = new LocationRequest.Builder(5)
                .setAdasGnssBypass(true)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request2, IDENTITY, PERMISSION_FINE, listener2);

        mInjector.getSettingsHelper().setLocationEnabled(false, IDENTITY.getUserId());

        assertThat(mProvider.getRequest().isActive()).isTrue();
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(5);
        assertThat(mProvider.getRequest().isAdasGnssBypass()).isTrue();
    }

    @Test
    public void testProviderRequest_AdasGnssBypass_ProviderDisabled_AdasDisabled() {
        doReturn(true).when(mPackageManager).hasSystemFeature(FEATURE_AUTOMOTIVE);
        doReturn(true).when(mResources).getBoolean(R.bool.config_defaultAdasGnssLocationEnabled);

        mInjector.getSettingsHelper().setIgnoreSettingsAllowlist(
                new PackageTagsList.Builder().add(
                        IDENTITY.getPackageName()).build());

        mInjector.getSettingsHelper().setAdasSettingsAllowlist(
                new PackageTagsList.Builder().add(
                        IDENTITY.getPackageName()).build());

        createManager(GPS_PROVIDER);

        ILocationListener listener1 = createMockLocationListener();
        LocationRequest request1 = new LocationRequest.Builder(5)
                .setLocationSettingsIgnored(true)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request1, IDENTITY, PERMISSION_FINE, listener1);

        ILocationListener listener2 = createMockLocationListener();
        LocationRequest request2 = new LocationRequest.Builder(1)
                .setAdasGnssBypass(true)
                .setWorkSource(WORK_SOURCE)
                .build();
        mManager.registerLocationRequest(request2, IDENTITY, PERMISSION_FINE, listener2);

        mInjector.getLocationSettings().updateUserSettings(IDENTITY.getUserId(),
                settings -> settings.withAdasGnssLocationEnabled(false));
        mInjector.getSettingsHelper().setLocationEnabled(false, IDENTITY.getUserId());

        assertThat(mProvider.getRequest().isActive()).isTrue();
        assertThat(mProvider.getRequest().getIntervalMillis()).isEqualTo(5);
        assertThat(mProvider.getRequest().isAdasGnssBypass()).isFalse();
    }

    @Test
    public void testProviderRequest_BatterySaver_ScreenOnOff() {
        mInjector.getLocationPowerSaveModeHelper().setLocationPowerSaveMode(
                LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF);

        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(5).setWorkSource(WORK_SOURCE).build();
        mManager.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);

        assertThat(mProvider.getRequest().isActive()).isTrue();

        mInjector.getScreenInteractiveHelper().setScreenInteractive(false);
        assertThat(mProvider.getRequest().isActive()).isFalse();
    }

    @Test
    public void testQueryPackageReset() {
        assertThat(mInjector.getPackageResetHelper().isResetableForPackage("mypackage")).isFalse();

        ILocationListener listener1 = createMockLocationListener();
        mManager.registerLocationRequest(new LocationRequest.Builder(0).setWorkSource(
                WORK_SOURCE).build(), IDENTITY, PERMISSION_FINE, listener1);
        assertThat(mInjector.getPackageResetHelper().isResetableForPackage("mypackage")).isTrue();

        ILocationListener listener2 = createMockLocationListener();
        mManager.registerLocationRequest(new LocationRequest.Builder(0).setWorkSource(
                WORK_SOURCE).build(), IDENTITY, PERMISSION_FINE, listener2);
        assertThat(mInjector.getPackageResetHelper().isResetableForPackage("mypackage")).isTrue();

        mManager.unregisterLocationRequest(listener1);
        assertThat(mInjector.getPackageResetHelper().isResetableForPackage("mypackage")).isTrue();

        mManager.unregisterLocationRequest(listener2);
        assertThat(mInjector.getPackageResetHelper().isResetableForPackage("mypackage")).isFalse();
    }

    @Test
    public void testPackageReset() {
        ILocationListener listener1 = createMockLocationListener();
        mManager.registerLocationRequest(new LocationRequest.Builder(0).setWorkSource(
                WORK_SOURCE).build(), IDENTITY, PERMISSION_FINE, listener1);
        ILocationListener listener2 = createMockLocationListener();
        mManager.registerLocationRequest(new LocationRequest.Builder(0).setWorkSource(
                WORK_SOURCE).build(), IDENTITY, PERMISSION_FINE, listener2);

        assertThat(mProvider.getRequest().isActive()).isTrue();
        assertThat(mInjector.getPackageResetHelper().isResetableForPackage("mypackage")).isTrue();

        mInjector.getPackageResetHelper().reset("mypackage");
        assertThat(mProvider.getRequest().isActive()).isFalse();
        assertThat(mInjector.getPackageResetHelper().isResetableForPackage("mypackage")).isFalse();
    }

    @Test
    public void testIsVisibleToCaller() {
        assertThat(mManager.isVisibleToCaller()).isTrue();
    }

    @Test
    public void testIsVisibleToCaller_noPermissions() {
        createManager("any_name", Collections.singletonList(MISSING_PERMISSION));
        assertThat(mManager.isVisibleToCaller()).isFalse();
    }

    @Test
    public void testValidateLocation_futureLocation() {
        mSetFlagsRule.enableFlags(Flags.FLAG_LOCATION_VALIDATION);
        Location location = createLocation(NAME, mRandom);
        mProvider.setProviderLocation(location);

        assertThat(mPassive.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isEqualTo(location);

        Location futureLocation = createLocation(NAME, mRandom);
        futureLocation.setElapsedRealtimeNanos(
                SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(2));
        mProvider.setProviderLocation(futureLocation);

        assertThat(mPassive.getLastLocation(new LastLocationRequest.Builder().build(), IDENTITY,
                PERMISSION_FINE)).isEqualTo(location);
    }

    @Test
    public void testLocationFudger_withFlagDisabled_cacheIsNotSetAndOldAlgoIsUsed() {
        mSetFlagsRule.disableFlags(Flags.FLAG_DENSITY_BASED_COARSE_LOCATIONS);
        createManager("some-name");
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);

        mManager.setLocationFudgerCache(cache);

        Location test = new Location("any-provider");
        mManager.getPermittedLocation(test, PERMISSION_COARSE);

        verify(provider, never()).getCoarsenedS2Cell(anyDouble(), anyDouble(), any());
    }

    @Test
    public void testLocationFudger_withFlagEnabledButNoDefaults_oldAlgoIsUsed()
            throws RemoteException {
        mSetFlagsRule.enableFlags(Flags.FLAG_DENSITY_BASED_COARSE_LOCATIONS);
        createManager("some-other-name");
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);

        mManager.setLocationFudgerCache(cache);

        ArgumentCaptor<IS2LevelCallback> captor = ArgumentCaptor.forClass(IS2LevelCallback.class);
        verify(provider).getDefaultCoarseningLevel(captor.capture());

        IS2LevelCallback cb = captor.getValue();

        // Act: the provider didn't provide a default
        cb.onError();

        Location test = new Location("any-provider");
        mManager.getPermittedLocation(test, PERMISSION_COARSE);

        verify(provider, never()).getCoarsenedS2Cell(anyDouble(), anyDouble(), any());
    }

    @Test
    public void testLocationFudger_withFlagEnabled_cacheIsSetAndNewAlgoIsUsed()
            throws RemoteException {
        mSetFlagsRule.enableFlags(Flags.FLAG_DENSITY_BASED_COARSE_LOCATIONS);
        createManager("some-other-name");
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);
        int defaultLevel = 2;

        mManager.setLocationFudgerCache(cache);

        ArgumentCaptor<IS2LevelCallback> captor = ArgumentCaptor.forClass(IS2LevelCallback.class);
        verify(provider).getDefaultCoarseningLevel(captor.capture());

        IS2LevelCallback cb = captor.getValue();
        cb.onResult(defaultLevel);

        Location test = new Location("any-provider");
        test.setLatitude(10.0);
        test.setLongitude(20.0);
        mManager.getPermittedLocation(test, PERMISSION_COARSE);

        // We can't test that 10.0, 20.0 was passed due to the offset. We only test that a call
        // happened.
        verify(provider).getCoarsenedS2Cell(anyDouble(), anyDouble(), any());
    }

    @MediumTest
    @Test
    public void testEnableMsl_expectedBehavior() throws Exception {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_LOCATION,
                "enable_location_provider_manager_msl", Boolean.toString(true), false);

        // Create a random location and set provider location to cache necessary MSL assets.
        Location loc = createLocation(NAME, mRandom);
        loc.setAltitude(mRandom.nextDouble());
        loc.setVerticalAccuracyMeters(mRandom.nextFloat());
        mProvider.setProviderLocation(LocationResult.wrap(loc));
        Thread.sleep(1000);

        // Register listener and reset provider location to capture.
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
        mPassive.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);
        mProvider.setProviderLocation(LocationResult.wrap(loc));
        ArgumentCaptor<List<Location>> captor = ArgumentCaptor.forClass(List.class);
        verify(listener).onLocationChanged(captor.capture(), nullable(IRemoteCallback.class));

        // Assert that MSL fields are populated.
        Location actual = captor.getValue().get(0);
        assertThat(actual.hasMslAltitude()).isTrue();
        assertThat(actual.hasMslAltitudeAccuracy()).isTrue();
    }

    @MediumTest
    @Test
    public void testEnableMsl_noVerticalAccuracy() throws Exception {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_LOCATION,
                "enable_location_provider_manager_msl", Boolean.toString(true), false);

        // Create a random location and set provider location to cache necessary MSL assets.
        Location loc = createLocation(NAME, mRandom);
        loc.setAltitude(mRandom.nextDouble());
        loc.setVerticalAccuracyMeters(mRandom.nextFloat());
        mProvider.setProviderLocation(LocationResult.wrap(loc));
        Thread.sleep(1000);

        // Register listener and reset provider location with no vertical accuracy to capture.
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
        mPassive.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);
        loc.removeVerticalAccuracy();
        mProvider.setProviderLocation(LocationResult.wrap(loc));
        ArgumentCaptor<List<Location>> captor = ArgumentCaptor.forClass(List.class);
        verify(listener).onLocationChanged(captor.capture(), nullable(IRemoteCallback.class));

        // Assert that only the MSL accuracy field is populated.
        Location actual = captor.getValue().get(0);
        assertThat(actual.hasMslAltitude()).isTrue();
        assertThat(actual.hasMslAltitudeAccuracy()).isFalse();
    }

    @MediumTest
    @Test
    public void testEnableMsl_noAltitude() throws Exception {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_LOCATION,
                "enable_location_provider_manager_msl", Boolean.toString(true), false);

        // Create a random location and set provider location to cache necessary MSL assets.
        Location loc = createLocation(NAME, mRandom);
        loc.setAltitude(mRandom.nextDouble());
        loc.setVerticalAccuracyMeters(mRandom.nextFloat());
        mProvider.setProviderLocation(LocationResult.wrap(loc));
        Thread.sleep(1000);

        // Register listener and reset provider location with no altitude to capture.
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
        mPassive.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);
        loc.removeAltitude();
        mProvider.setProviderLocation(LocationResult.wrap(loc));
        ArgumentCaptor<List<Location>> captor = ArgumentCaptor.forClass(List.class);
        verify(listener).onLocationChanged(captor.capture(), nullable(IRemoteCallback.class));

        // Assert that no MSL fields are populated.
        Location actual = captor.getValue().get(0);
        assertThat(actual.hasMslAltitude()).isFalse();
        assertThat(actual.hasMslAltitudeAccuracy()).isFalse();
    }

    @MediumTest
    @Test
    public void testEnableMsl_invalidAltitude() throws Exception {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_LOCATION,
                "enable_location_provider_manager_msl", Boolean.toString(true), false);

        // Create a random location and set provider location to cache necessary MSL assets.
        Location loc = createLocation(NAME, mRandom);
        loc.setAltitude(mRandom.nextDouble());
        loc.setVerticalAccuracyMeters(mRandom.nextFloat());
        mProvider.setProviderLocation(LocationResult.wrap(loc));
        Thread.sleep(1000);

        // Register listener and reset provider location with invalid altitude to capture.
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
        mPassive.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);
        loc.setAltitude(Double.POSITIVE_INFINITY);
        mProvider.setProviderLocation(LocationResult.wrap(loc));
        ArgumentCaptor<List<Location>> captor = ArgumentCaptor.forClass(List.class);
        verify(listener).onLocationChanged(captor.capture(), nullable(IRemoteCallback.class));

        // Assert that no MSL fields are populated.
        Location actual = captor.getValue().get(0);
        assertThat(actual.hasMslAltitude()).isFalse();
        assertThat(actual.hasMslAltitudeAccuracy()).isFalse();
    }

    @MediumTest
    @Test
    public void testDisableMsl_expectedBehavior() throws Exception {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_LOCATION,
                "enable_location_provider_manager_msl", Boolean.toString(false), false);

        // Create a random location and set provider location to cache necessary MSL assets.
        Location loc = createLocation(NAME, mRandom);
        loc.setAltitude(mRandom.nextDouble());
        loc.setVerticalAccuracyMeters(mRandom.nextFloat());
        mProvider.setProviderLocation(LocationResult.wrap(loc));
        Thread.sleep(1000);

        // Register listener and reset provider location to capture.
        ILocationListener listener = createMockLocationListener();
        LocationRequest request = new LocationRequest.Builder(0).setWorkSource(WORK_SOURCE).build();
        mPassive.registerLocationRequest(request, IDENTITY, PERMISSION_FINE, listener);
        mProvider.setProviderLocation(LocationResult.wrap(loc));
        ArgumentCaptor<List<Location>> captor = ArgumentCaptor.forClass(List.class);
        verify(listener).onLocationChanged(captor.capture(), nullable(IRemoteCallback.class));

        // Assert that no MSL fields are populated.
        Location actual = captor.getValue().get(0);
        assertThat(actual.hasMslAltitude()).isFalse();
        assertThat(actual.hasMslAltitudeAccuracy()).isFalse();
    }

    private ILocationListener createMockLocationListener() {
        return spy(new ILocationListener.Stub() {
            @Override
            public void onLocationChanged(List<Location> locations,
                    IRemoteCallback onCompleteCallback) {
                if (onCompleteCallback != null) {
                    try {
                        onCompleteCallback.sendResult(null);
                    } catch (RemoteException e) {
                        e.rethrowFromSystemServer();
                    }
                }
            }

            @Override
            public void onFlushComplete(int requestCode) {
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

        private final ArrayList<Runnable> mFlushCallbacks = new ArrayList<>();

        TestProvider(ProviderProperties properties, CallerIdentity identity) {
            super(DIRECT_EXECUTOR, identity, properties, Collections.emptySet());
        }

        public void setProviderAllowed(boolean allowed) {
            setAllowed(allowed);
        }

        public void setProviderLocation(Location l) {
            reportLocation(LocationResult.create(new Location(l)));
        }

        public void setProviderLocation(LocationResult l) {
            reportLocation(l);
        }

        public void completeFlushes() {
            for (Runnable r : mFlushCallbacks) {
                r.run();
            }
            mFlushCallbacks.clear();
        }

        public ProviderRequest getRequest() {
            return mProviderRequest;
        }

        @Override
        public void onSetRequest(ProviderRequest request) {
            mProviderRequest = request;
        }

        @Override
        protected void onFlush(Runnable callback) {
            mFlushCallbacks.add(callback);
        }

        @Override
        protected void onExtraCommand(int uid, int pid, String command, Bundle extras) {
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        }
    }
}
