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

import static android.app.AppOpsManager.OP_COARSE_LOCATION;
import static android.app.AppOpsManager.OP_FINE_LOCATION;
import static android.location.LocationManager.GPS_PROVIDER;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssAntennaInfo;
import android.location.GnssAntennaInfo.SphericalCorrections;
import android.location.GnssClock;
import android.location.GnssMeasurementCorrections;
import android.location.GnssMeasurementRequest;
import android.location.GnssMeasurementsEvent;
import android.location.GnssNavigationMessage;
import android.location.GnssSingleSatCorrection;
import android.location.IGnssAntennaInfoListener;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssNavigationMessageListener;
import android.location.IGnssStatusListener;
import android.location.INetInitiatedListener;
import android.location.LocationManagerInternal;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Message;
import android.os.RemoteException;

import com.android.server.LocalServices;
import com.android.server.location.gnss.GnssAntennaInfoProvider.GnssAntennaInfoProviderNative;
import com.android.server.location.gnss.GnssMeasurementsProvider.GnssMeasurementProviderNative;
import com.android.server.location.gnss.GnssNavigationMessageProvider.GnssNavigationMessageProviderNative;
import com.android.server.location.injector.TestInjector;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.AdditionalMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.location.gnss.GnssManagerService}.
 */
public class GnssManagerServiceTest {

    private static final long TIMEOUT_MS = 5000;
    private static final long FAILURE_TIMEOUT_MS = 200;

    private static final String TEST_PACKAGE = "com.test";

    private TestInjector mInjector;

    @Mock private Handler mMockHandler;
    @Mock private Context mMockContext;
    @Mock private PackageManager mPackageManager;
    @Mock private LocationManagerInternal mLocationManagerInternal;
    @Mock private GnssNative.GnssNativeInitNative mGnssInitNative;
    @Mock private GnssLocationProvider mMockGnssLocationProvider;
    @Mock private GnssLocationProvider.GnssSystemInfoProvider mMockGnssSystemInfoProvider;
    @Mock private GnssCapabilitiesProvider mMockGnssCapabilitiesProvider;
    @Mock private GnssMeasurementCorrectionsProvider mMockGnssMeasurementCorrectionsProvider;
    @Mock private INetInitiatedListener mNetInitiatedListener;

    private GnssMeasurementsProvider mTestGnssMeasurementsProvider;
    private GnssStatusProvider mTestGnssStatusProvider;
    private GnssNavigationMessageProvider mTestGnssNavigationMessageProvider;
    private GnssAntennaInfoProvider mTestGnssAntennaInfoProvider;

    private GnssManagerService mGnssManagerService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mGnssInitNative.isSupported()).thenReturn(true);
        GnssNative.setInitNativeForTest(mGnssInitNative);
        GnssNative.resetCallbacksForTest();

        when(mMockContext.createAttributionContext(anyString())).thenReturn(mMockContext);
        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getPackagesForUid(anyInt())).thenReturn(
                new String[]{TEST_PACKAGE});

        mInjector = new TestInjector();

        enableLocationPermissions();

        LocalServices.addService(LocationManagerInternal.class, mLocationManagerInternal);

        // Mock Handler will execute posted runnables immediately
        when(mMockHandler.sendMessageAtTime(any(Message.class), anyLong())).thenAnswer(
                (InvocationOnMock invocation) -> {
                    Message msg = (Message) (invocation.getArguments()[0]);
                    msg.getCallback().run();
                    return null;
                });

        // Setup providers
        mTestGnssMeasurementsProvider = createGnssMeasurementsProvider();
        mTestGnssStatusProvider = createGnssStatusListenerHelper();
        mTestGnssNavigationMessageProvider = createGnssNavigationMessageProvider();
        mTestGnssAntennaInfoProvider = createGnssAntennaInfoProvider();

        // Setup GnssLocationProvider to return providers
        when(mMockGnssLocationProvider.getGnssStatusProvider()).thenReturn(
                mTestGnssStatusProvider);
        when(mMockGnssLocationProvider.getGnssCapabilitiesProvider()).thenReturn(
                mMockGnssCapabilitiesProvider);
        when(mMockGnssLocationProvider.getGnssSystemInfoProvider()).thenReturn(
                mMockGnssSystemInfoProvider);
        when(mMockGnssLocationProvider.getGnssMeasurementCorrectionsProvider()).thenReturn(
                mMockGnssMeasurementCorrectionsProvider);
        when(mMockGnssLocationProvider.getGnssMeasurementsProvider()).thenReturn(
                mTestGnssMeasurementsProvider);
        when(mMockGnssLocationProvider.getGnssNavigationMessageProvider()).thenReturn(
                mTestGnssNavigationMessageProvider);
        when(mMockGnssLocationProvider.getNetInitiatedListener()).thenReturn(
                mNetInitiatedListener);
        when(mMockGnssLocationProvider.getGnssAntennaInfoProvider()).thenReturn(
                mTestGnssAntennaInfoProvider);

        // Create GnssManagerService
        mGnssManagerService = new GnssManagerService(mMockContext, mInjector,
                mMockGnssLocationProvider);
        mGnssManagerService.onSystemReady();
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(LocationManagerInternal.class);
    }

    private void overrideAsBinder(IInterface mockListener) {
        IBinder mockBinder = mock(IBinder.class);
        when(mockListener.asBinder()).thenReturn(mockBinder);
    }

    private IGnssStatusListener createMockGnssStatusListener() {
        IGnssStatusListener mockListener = mock(IGnssStatusListener.class);
        overrideAsBinder(mockListener);
        return mockListener;
    }

    private IGnssMeasurementsListener createMockGnssMeasurementsListener() {
        IGnssMeasurementsListener mockListener = mock(
                IGnssMeasurementsListener.class);
        overrideAsBinder(mockListener);
        return mockListener;
    }

    private IGnssAntennaInfoListener createMockGnssAntennaInfoListener() {
        IGnssAntennaInfoListener mockListener = mock(IGnssAntennaInfoListener.class);
        overrideAsBinder(mockListener);
        return mockListener;
    }

    private IGnssNavigationMessageListener createMockGnssNavigationMessageListener() {
        IGnssNavigationMessageListener mockListener = mock(IGnssNavigationMessageListener.class);
        overrideAsBinder(mockListener);
        return mockListener;
    }

    private GnssMeasurementCorrections createDummyGnssMeasurementCorrections() {
        GnssSingleSatCorrection gnssSingleSatCorrection =
                new GnssSingleSatCorrection.Builder().build();
        return
                new GnssMeasurementCorrections.Builder().setSingleSatelliteCorrectionList(
                        Collections.singletonList(gnssSingleSatCorrection)).build();
    }

    private static List<GnssAntennaInfo> createDummyGnssAntennaInfos() {
        double carrierFrequencyMHz = 13758.0;

        GnssAntennaInfo.PhaseCenterOffset phaseCenterOffset = new
                GnssAntennaInfo.PhaseCenterOffset(
                4.3d,
                1.4d,
                2.10d,
                2.1d,
                3.12d,
                0.5d);

        double[][] phaseCenterVariationCorrectionsMillimeters = new double[10][10];
        double[][] phaseCenterVariationCorrectionsUncertaintyMillimeters = new double[10][10];
        SphericalCorrections
                phaseCenterVariationCorrections =
                new SphericalCorrections(
                        phaseCenterVariationCorrectionsMillimeters,
                        phaseCenterVariationCorrectionsUncertaintyMillimeters);

        double[][] signalGainCorrectionsDbi = new double[10][10];
        double[][] signalGainCorrectionsUncertaintyDbi = new double[10][10];
        SphericalCorrections signalGainCorrections = new
                SphericalCorrections(
                signalGainCorrectionsDbi,
                signalGainCorrectionsUncertaintyDbi);

        List<GnssAntennaInfo> gnssAntennaInfos = new ArrayList<>();
        gnssAntennaInfos.add(new GnssAntennaInfo.Builder()
                .setCarrierFrequencyMHz(carrierFrequencyMHz)
                .setPhaseCenterOffset(phaseCenterOffset)
                .setPhaseCenterVariationCorrections(phaseCenterVariationCorrections)
                .setSignalGainCorrections(signalGainCorrections)
                .build());
        return gnssAntennaInfos;
    }

    private void enableLocationPermissions() {
        Mockito.doThrow(new SecurityException()).when(
                mMockContext).enforceCallingOrSelfPermission(
                AdditionalMatchers.and(
                        AdditionalMatchers.not(eq(Manifest.permission.LOCATION_HARDWARE)),
                        AdditionalMatchers.not(eq(Manifest.permission.ACCESS_FINE_LOCATION))),
                anyString());
        when(mMockContext.checkPermission(
                eq(android.Manifest.permission.LOCATION_HARDWARE), anyInt(), anyInt())).thenReturn(
                PackageManager.PERMISSION_GRANTED);
        when(mMockContext.checkPermission(
                eq(Manifest.permission.ACCESS_FINE_LOCATION), anyInt(), anyInt())).thenReturn(
                PackageManager.PERMISSION_GRANTED);
        when(mMockContext.checkPermission(
                eq(Manifest.permission.ACCESS_COARSE_LOCATION), anyInt(), anyInt())).thenReturn(
                PackageManager.PERMISSION_GRANTED);

        mInjector.getAppOpsHelper().setAppOpAllowed(OP_FINE_LOCATION, TEST_PACKAGE, true);
        mInjector.getAppOpsHelper().setAppOpAllowed(OP_COARSE_LOCATION, TEST_PACKAGE, true);

        when(mLocationManagerInternal.isProviderEnabledForUser(eq(GPS_PROVIDER), anyInt()))
                .thenReturn(true);
    }

    private void disableLocationPermissions() {
        Mockito.doThrow(new SecurityException()).when(
                mMockContext).enforceCallingOrSelfPermission(anyString(), nullable(String.class));

        when(mMockContext.checkPermission(
                anyString(), anyInt(), anyInt())).thenReturn(
                PackageManager.PERMISSION_DENIED);

        mInjector.getAppOpsHelper().setAppOpAllowed(OP_FINE_LOCATION, TEST_PACKAGE, false);
        mInjector.getAppOpsHelper().setAppOpAllowed(OP_COARSE_LOCATION, TEST_PACKAGE, false);

        when(mLocationManagerInternal.isProviderEnabledForUser(eq(GPS_PROVIDER), anyInt()))
                .thenReturn(false);
    }

    private GnssStatusProvider createGnssStatusListenerHelper() {
        return new GnssStatusProvider(mInjector);
    }

    private GnssMeasurementsProvider createGnssMeasurementsProvider() {
        GnssMeasurementProviderNative
                mockGnssMeasurementProviderNative = mock(GnssMeasurementProviderNative.class);
        when(mockGnssMeasurementProviderNative.isMeasurementSupported()).thenReturn(
                true);
        return new GnssMeasurementsProvider(mInjector,  mockGnssMeasurementProviderNative);
    }

    private GnssNavigationMessageProvider createGnssNavigationMessageProvider() {
        GnssNavigationMessageProviderNative mockGnssNavigationMessageProviderNative = mock(
                GnssNavigationMessageProviderNative.class);
        when(mockGnssNavigationMessageProviderNative.isNavigationMessageSupported()).thenReturn(
                true);
        return new GnssNavigationMessageProvider(mInjector,
                mockGnssNavigationMessageProviderNative);
    }

    private GnssAntennaInfoProvider createGnssAntennaInfoProvider() {
        GnssAntennaInfoProviderNative mockGnssAntenaInfoProviderNative = mock(
                GnssAntennaInfoProviderNative.class);
        when(mockGnssAntenaInfoProviderNative.isAntennaInfoSupported()).thenReturn(
                true);
        return new GnssAntennaInfoProvider(mInjector, mockGnssAntenaInfoProviderNative);
    }

    @Test
    public void getGnssYearOfHardwareTest() {
        final int gnssYearOfHardware = 2012;
        when(mMockGnssSystemInfoProvider.getGnssYearOfHardware()).thenReturn(gnssYearOfHardware);
        enableLocationPermissions();

        assertThat(mGnssManagerService.getGnssYearOfHardware()).isEqualTo(gnssYearOfHardware);
    }

    @Test
    public void getGnssHardwareModelNameTest() {
        final String gnssHardwareModelName = "hardwarename";
        when(mMockGnssSystemInfoProvider.getGnssHardwareModelName()).thenReturn(
                gnssHardwareModelName);
        enableLocationPermissions();

        assertThat(mGnssManagerService.getGnssHardwareModelName()).isEqualTo(
                gnssHardwareModelName);
    }

    @Test
    public void getGnssCapabilitiesWithPermissionsTest() {
        final long mGnssCapabilities = 23132L;
        when(mMockGnssCapabilitiesProvider.getGnssCapabilities()).thenReturn(mGnssCapabilities);
        enableLocationPermissions();

        assertThat(mGnssManagerService.getGnssCapabilities()).isEqualTo(mGnssCapabilities);
    }

    @Test
    public void registerGnssStatusCallbackWithoutPermissionsTest() throws RemoteException {
        final int timeToFirstFix = 20000;
        IGnssStatusListener mockGnssStatusListener = createMockGnssStatusListener();

        disableLocationPermissions();

        assertThrows(SecurityException.class, () -> mGnssManagerService
                .registerGnssStatusCallback(
                        mockGnssStatusListener, TEST_PACKAGE, "abcd123"));

        mTestGnssStatusProvider.onFirstFix(timeToFirstFix);

        verify(mockGnssStatusListener, after(FAILURE_TIMEOUT_MS).times(0)).onFirstFix(
                timeToFirstFix);
    }

    @Test
    public void registerGnssStatusCallbackWithPermissionsTest() throws RemoteException {
        final int timeToFirstFix = 20000;
        IGnssStatusListener mockGnssStatusListener = createMockGnssStatusListener();

        enableLocationPermissions();

        mGnssManagerService.registerGnssStatusCallback(
                mockGnssStatusListener, TEST_PACKAGE, "abcd123");

        mTestGnssStatusProvider.onFirstFix(timeToFirstFix);

        verify(mockGnssStatusListener, timeout(TIMEOUT_MS).times(1)).onFirstFix(timeToFirstFix);
    }

    @Test
    public void unregisterGnssStatusCallbackWithPermissionsTest() throws RemoteException {
        final int timeToFirstFix = 20000;
        IGnssStatusListener mockGnssStatusListener = createMockGnssStatusListener();

        enableLocationPermissions();

        mGnssManagerService.registerGnssStatusCallback(
                mockGnssStatusListener, TEST_PACKAGE, "abcd123");

        mGnssManagerService.unregisterGnssStatusCallback(mockGnssStatusListener);

        mTestGnssStatusProvider.onFirstFix(timeToFirstFix);

        verify(mockGnssStatusListener, after(FAILURE_TIMEOUT_MS).times(0)).onFirstFix(
                timeToFirstFix);
    }

    @Test
    public void addGnssMeasurementsListenerWithoutPermissionsTest() throws RemoteException {
        IGnssMeasurementsListener mockGnssMeasurementsListener =
                createMockGnssMeasurementsListener();
        GnssMeasurementsEvent gnssMeasurementsEvent = new GnssMeasurementsEvent(new GnssClock(),
                null);

        disableLocationPermissions();

        assertThrows(SecurityException.class,
                () -> mGnssManagerService.addGnssMeasurementsListener(
                        new GnssMeasurementRequest.Builder().build(), mockGnssMeasurementsListener,
                        TEST_PACKAGE, null));

        mTestGnssMeasurementsProvider.onMeasurementsAvailable(gnssMeasurementsEvent);
        verify(mockGnssMeasurementsListener,
                after(FAILURE_TIMEOUT_MS).times(0)).onGnssMeasurementsReceived(
                gnssMeasurementsEvent);
    }

    @Test
    public void addGnssMeasurementsListenerWithPermissionsTest() throws RemoteException {
        IGnssMeasurementsListener mockGnssMeasurementsListener =
                createMockGnssMeasurementsListener();
        GnssMeasurementsEvent gnssMeasurementsEvent = new GnssMeasurementsEvent(new GnssClock(),
                null);

        enableLocationPermissions();

        mGnssManagerService.addGnssMeasurementsListener(
                new GnssMeasurementRequest.Builder().build(),
                mockGnssMeasurementsListener,
                TEST_PACKAGE, null);

        mTestGnssMeasurementsProvider.onMeasurementsAvailable(gnssMeasurementsEvent);
        verify(mockGnssMeasurementsListener,
                timeout(TIMEOUT_MS).times(1)).onGnssMeasurementsReceived(
                gnssMeasurementsEvent);
    }

    @Test
    public void injectGnssMeasurementCorrectionsWithoutPermissionsTest() {
        GnssMeasurementCorrections gnssMeasurementCorrections =
                createDummyGnssMeasurementCorrections();

        disableLocationPermissions();

        assertThrows(SecurityException.class,
                () -> mGnssManagerService.injectGnssMeasurementCorrections(
                        gnssMeasurementCorrections));
        verify(mMockGnssMeasurementCorrectionsProvider, times(0))
                .injectGnssMeasurementCorrections(
                        gnssMeasurementCorrections);
    }

    @Test
    public void injectGnssMeasurementCorrectionsWithPermissionsTest() {
        GnssMeasurementCorrections gnssMeasurementCorrections =
                createDummyGnssMeasurementCorrections();

        enableLocationPermissions();

        mGnssManagerService.injectGnssMeasurementCorrections(
                gnssMeasurementCorrections);
        verify(mMockGnssMeasurementCorrectionsProvider, times(1))
                .injectGnssMeasurementCorrections(
                        gnssMeasurementCorrections);
    }

    @Test
    public void removeGnssMeasurementsListenerWithoutPermissionsTest() throws RemoteException {
        IGnssMeasurementsListener mockGnssMeasurementsListener =
                createMockGnssMeasurementsListener();
        GnssMeasurementsEvent gnssMeasurementsEvent = new GnssMeasurementsEvent(new GnssClock(),
                null);

        enableLocationPermissions();

        mGnssManagerService.addGnssMeasurementsListener(
                new GnssMeasurementRequest.Builder().build(),
                mockGnssMeasurementsListener,
                TEST_PACKAGE, null);

        disableLocationPermissions();

        mGnssManagerService.removeGnssMeasurementsListener(
                mockGnssMeasurementsListener);

        mTestGnssMeasurementsProvider.onMeasurementsAvailable(gnssMeasurementsEvent);
        verify(mockGnssMeasurementsListener,
                after(FAILURE_TIMEOUT_MS).times(0)).onGnssMeasurementsReceived(
                gnssMeasurementsEvent);
    }

    @Test
    public void removeGnssMeasurementsListenerWithPermissionsTest() throws RemoteException {
        IGnssMeasurementsListener mockGnssMeasurementsListener =
                createMockGnssMeasurementsListener();
        GnssMeasurementsEvent gnssMeasurementsEvent = new GnssMeasurementsEvent(new GnssClock(),
                null);

        enableLocationPermissions();

        mGnssManagerService.addGnssMeasurementsListener(
                new GnssMeasurementRequest.Builder().build(),
                mockGnssMeasurementsListener,
                TEST_PACKAGE, null);

        disableLocationPermissions();

        mGnssManagerService.removeGnssMeasurementsListener(
                mockGnssMeasurementsListener);

        mTestGnssMeasurementsProvider.onMeasurementsAvailable(gnssMeasurementsEvent);
        verify(mockGnssMeasurementsListener,
                after(FAILURE_TIMEOUT_MS).times(0)).onGnssMeasurementsReceived(
                gnssMeasurementsEvent);
    }

    @Test
    public void addGnssAntennaInfoListenerWithoutPermissionsTest() throws RemoteException {
        IGnssAntennaInfoListener mockGnssAntennaInfoListener =
                createMockGnssAntennaInfoListener();
        List<GnssAntennaInfo> gnssAntennaInfos = createDummyGnssAntennaInfos();

        disableLocationPermissions();

        assertThrows(SecurityException.class,
                () -> mGnssManagerService.addGnssAntennaInfoListener(
                        mockGnssAntennaInfoListener,
                        TEST_PACKAGE, null));

        mTestGnssAntennaInfoProvider.onGnssAntennaInfoAvailable(gnssAntennaInfos);
        verify(mockGnssAntennaInfoListener, after(FAILURE_TIMEOUT_MS).times(0))
                .onGnssAntennaInfoReceived(gnssAntennaInfos);
    }

    @Test
    public void addGnssAntennaInfoListenerWithPermissionsTest() throws RemoteException {
        IGnssAntennaInfoListener mockGnssAntennaInfoListener =
                createMockGnssAntennaInfoListener();
        List<GnssAntennaInfo> gnssAntennaInfos = createDummyGnssAntennaInfos();

        enableLocationPermissions();

        mGnssManagerService.addGnssAntennaInfoListener(mockGnssAntennaInfoListener,
                TEST_PACKAGE, null);

        mTestGnssAntennaInfoProvider.onGnssAntennaInfoAvailable(gnssAntennaInfos);
        verify(mockGnssAntennaInfoListener, timeout(TIMEOUT_MS).times(1))
                .onGnssAntennaInfoReceived(gnssAntennaInfos);
    }

    @Test
    public void removeGnssAntennaInfoListenerWithoutPermissionsTest() throws RemoteException {
        IGnssAntennaInfoListener mockGnssAntennaInfoListener =
                createMockGnssAntennaInfoListener();
        List<GnssAntennaInfo> gnssAntennaInfos = createDummyGnssAntennaInfos();

        enableLocationPermissions();

        mGnssManagerService.addGnssAntennaInfoListener(
                mockGnssAntennaInfoListener,
                TEST_PACKAGE, null);

        disableLocationPermissions();

        mGnssManagerService.removeGnssAntennaInfoListener(
                mockGnssAntennaInfoListener);

        mTestGnssAntennaInfoProvider.onGnssAntennaInfoAvailable(gnssAntennaInfos);
        verify(mockGnssAntennaInfoListener, after(FAILURE_TIMEOUT_MS).times(0))
                .onGnssAntennaInfoReceived(gnssAntennaInfos);
    }

    @Test
    public void removeGnssAntennaInfoListenerWithPermissionsTest() throws RemoteException {
        IGnssAntennaInfoListener mockGnssAntennaInfoListener =
                createMockGnssAntennaInfoListener();
        List<GnssAntennaInfo> gnssAntennaInfos = createDummyGnssAntennaInfos();

        enableLocationPermissions();

        mGnssManagerService.addGnssAntennaInfoListener(
                mockGnssAntennaInfoListener,
                TEST_PACKAGE, null);

        mGnssManagerService.removeGnssAntennaInfoListener(
                mockGnssAntennaInfoListener);

        mTestGnssAntennaInfoProvider.onGnssAntennaInfoAvailable(gnssAntennaInfos);
        verify(mockGnssAntennaInfoListener,
                after(FAILURE_TIMEOUT_MS).times(0)).onGnssAntennaInfoReceived(
                gnssAntennaInfos);
    }

    @Test
    public void addGnssNavigationMessageListenerWithoutPermissionsTest() throws RemoteException {
        IGnssNavigationMessageListener mockGnssNavigationMessageListener =
                createMockGnssNavigationMessageListener();
        GnssNavigationMessage gnssNavigationMessage = new GnssNavigationMessage();

        disableLocationPermissions();

        assertThrows(SecurityException.class,
                () -> mGnssManagerService.addGnssNavigationMessageListener(
                        mockGnssNavigationMessageListener, TEST_PACKAGE, null));

        mTestGnssNavigationMessageProvider.onNavigationMessageAvailable(gnssNavigationMessage);

        verify(mockGnssNavigationMessageListener,
                after(FAILURE_TIMEOUT_MS).times(0)).onGnssNavigationMessageReceived(
                gnssNavigationMessage);
    }

    @Test
    public void addGnssNavigationMessageListenerWithPermissionsTest() throws RemoteException {
        IGnssNavigationMessageListener mockGnssNavigationMessageListener =
                createMockGnssNavigationMessageListener();
        GnssNavigationMessage gnssNavigationMessage = new GnssNavigationMessage();

        enableLocationPermissions();

        mGnssManagerService.addGnssNavigationMessageListener(
                mockGnssNavigationMessageListener, TEST_PACKAGE, null);

        mTestGnssNavigationMessageProvider.onNavigationMessageAvailable(gnssNavigationMessage);

        verify(mockGnssNavigationMessageListener,
                timeout(TIMEOUT_MS).times(1)).onGnssNavigationMessageReceived(
                gnssNavigationMessage);
    }

    @Test
    public void removeGnssNavigationMessageListenerWithoutPermissionsTest() throws RemoteException {
        IGnssNavigationMessageListener mockGnssNavigationMessageListener =
                createMockGnssNavigationMessageListener();
        GnssNavigationMessage gnssNavigationMessage = new GnssNavigationMessage();

        enableLocationPermissions();

        mGnssManagerService.addGnssNavigationMessageListener(
                mockGnssNavigationMessageListener, TEST_PACKAGE, null);

        disableLocationPermissions();

        mGnssManagerService.removeGnssNavigationMessageListener(
                mockGnssNavigationMessageListener);

        mTestGnssNavigationMessageProvider.onNavigationMessageAvailable(gnssNavigationMessage);

        verify(mockGnssNavigationMessageListener,
                after(FAILURE_TIMEOUT_MS).times(0)).onGnssNavigationMessageReceived(
                gnssNavigationMessage);
    }

    @Test
    public void removeGnssNavigationMessageListenerWithPermissionsTest() throws RemoteException {
        IGnssNavigationMessageListener mockGnssNavigationMessageListener =
                createMockGnssNavigationMessageListener();
        GnssNavigationMessage gnssNavigationMessage = new GnssNavigationMessage();

        enableLocationPermissions();

        mGnssManagerService.addGnssNavigationMessageListener(
                mockGnssNavigationMessageListener, TEST_PACKAGE, null);

        mGnssManagerService.removeGnssNavigationMessageListener(
                mockGnssNavigationMessageListener);

        mTestGnssNavigationMessageProvider.onNavigationMessageAvailable(gnssNavigationMessage);

        verify(mockGnssNavigationMessageListener,
                after(FAILURE_TIMEOUT_MS).times(0)).onGnssNavigationMessageReceived(
                gnssNavigationMessage);
    }

    @Test
    public void sendNiResponseWithPermissionsTest() throws RemoteException {
        int notifId = 0;
        int userResponse = 0;
        enableLocationPermissions();

        mGnssManagerService.sendNiResponse(notifId, userResponse);

        verify(mNetInitiatedListener, times(1)).sendNiResponse(notifId, userResponse);
    }
}
