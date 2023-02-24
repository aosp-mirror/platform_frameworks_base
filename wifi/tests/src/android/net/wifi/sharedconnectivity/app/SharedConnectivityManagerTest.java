/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.wifi.sharedconnectivity.app;

import static android.net.wifi.WifiInfo.SECURITY_TYPE_EAP;
import static android.net.wifi.WifiInfo.SECURITY_TYPE_WEP;
import static android.net.wifi.sharedconnectivity.app.DeviceInfo.DEVICE_TYPE_TABLET;
import static android.net.wifi.sharedconnectivity.app.KnownNetwork.NETWORK_SOURCE_NEARBY_SELF;
import static android.net.wifi.sharedconnectivity.app.TetherNetwork.NETWORK_TYPE_CELLULAR;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.net.wifi.sharedconnectivity.service.ISharedConnectivityService;
import android.os.Bundle;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Unit tests for {@link SharedConnectivityManager}.
 */
@SmallTest
public class SharedConnectivityManagerTest {
    private static final long DEVICE_ID = 11L;
    private static final DeviceInfo DEVICE_INFO = new DeviceInfo.Builder()
            .setDeviceType(DEVICE_TYPE_TABLET).setDeviceName("TEST_NAME").setModelName("TEST_MODEL")
            .setConnectionStrength(2).setBatteryPercentage(50).build();
    private static final int NETWORK_TYPE = NETWORK_TYPE_CELLULAR;
    private static final String NETWORK_NAME = "TEST_NETWORK";
    private static final String HOTSPOT_SSID = "TEST_SSID";
    private static final int[] HOTSPOT_SECURITY_TYPES = {SECURITY_TYPE_WEP, SECURITY_TYPE_EAP};

    private static final int NETWORK_SOURCE = NETWORK_SOURCE_NEARBY_SELF;
    private static final String SSID = "TEST_SSID";
    private static final int[] SECURITY_TYPES = {SECURITY_TYPE_WEP};

    private static final String SERVICE_PACKAGE_NAME = "TEST_PACKAGE";
    private static final String SERVICE_INTENT_ACTION = "TEST_INTENT_ACTION";


    @Mock Context mContext;
    @Mock
    ISharedConnectivityService mService;
    @Mock Executor mExecutor;
    @Mock
    SharedConnectivityClientCallback mClientCallback;
    @Mock Resources mResources;
    @Mock
    ISharedConnectivityService.Stub mIBinder;

    private static final ComponentName COMPONENT_NAME =
            new ComponentName("dummypkg", "dummycls");

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        setResources(mContext);
    }

    /**
     * Verifies constructor is binding to service.
     */
    @Test
    public void bindingToService() {
        SharedConnectivityManager.create(mContext);

        verify(mContext).bindService(any(), any(), anyInt());
    }

    /**
     * Verifies create method returns null when resources are not specified
     */
    @Test
    public void resourcesNotDefined() {
        when(mResources.getString(anyInt())).thenThrow(new Resources.NotFoundException());

        assertNull(SharedConnectivityManager.create(mContext));
    }

    /**
     * Verifies registerCallback behavior.
     */
    @Test
    public void registerCallback_serviceNotConnected_registrationCachedThenConnected()
            throws Exception {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        manager.registerCallback(mExecutor, mClientCallback);
        manager.getServiceConnection().onServiceConnected(COMPONENT_NAME, mIBinder);

        // Since the binder is embedded in a proxy class, the call to registerCallback is done on
        // the proxy. So instead verifying that the proxy is calling the binder.
        verify(mIBinder).transact(anyInt(), any(Parcel.class), any(Parcel.class), anyInt());
    }

    @Test
    public void registerCallback_serviceNotConnected_canUnregisterAndReregister() {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        manager.registerCallback(mExecutor, mClientCallback);
        manager.unregisterCallback(mClientCallback);
        manager.registerCallback(mExecutor, mClientCallback);

        verify(mClientCallback, never()).onRegisterCallbackFailed(any(Exception.class));
    }

    @Test
    public void registerCallback_serviceConnected() throws Exception {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        manager.registerCallback(mExecutor, mClientCallback);

        verify(mService).registerCallback(any());
        verify(mClientCallback, never()).onRegisterCallbackFailed(any(Exception.class));
    }

    @Test
    public void registerCallback_doubleRegistration_shouldFail() throws Exception {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        manager.registerCallback(mExecutor, mClientCallback);
        manager.registerCallback(mExecutor, mClientCallback);

        verify(mClientCallback).onRegisterCallbackFailed(any(IllegalStateException.class));
    }

    @Test
    public void registerCallback_remoteException_shouldFail() throws Exception {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).registerCallback(any());

        manager.registerCallback(mExecutor, mClientCallback);

        verify(mClientCallback).onRegisterCallbackFailed(any(RemoteException.class));
    }

    /**
     * Verifies unregisterCallback behavior.
     */
    @Test
    public void unregisterCallback_withoutRegisteringFirst_serviceNotConnected_shouldFail() {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertFalse(manager.unregisterCallback(mClientCallback));
    }

    @Test
    public void unregisterCallback_withoutRegisteringFirst_serviceConnected_shouldFail() {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        assertFalse(manager.unregisterCallback(mClientCallback));
    }

    @Test
    public void unregisterCallback() throws Exception {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        manager.registerCallback(mExecutor, mClientCallback);

        assertTrue(manager.unregisterCallback(mClientCallback));
        verify(mService).unregisterCallback(any());
    }

    @Test
    public void unregisterCallback_doubleUnregistration_serviceConnected_shouldFail() {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        manager.registerCallback(mExecutor, mClientCallback);
        manager.unregisterCallback(mClientCallback);

        assertFalse(manager.unregisterCallback(mClientCallback));
    }

    @Test
    public void unregisterCallback_doubleUnregistration_serviceNotConnected_shouldFail() {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        manager.registerCallback(mExecutor, mClientCallback);
        manager.unregisterCallback(mClientCallback);

        assertFalse(manager.unregisterCallback(mClientCallback));
    }

    @Test
    public void unregisterCallback_remoteException_shouldFail() throws Exception {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        doThrow(new RemoteException()).when(mService).unregisterCallback(any());

        assertFalse(manager.unregisterCallback(mClientCallback));
    }

    /**
     * Verifies callback is called when service is connected
     */
    @Test
    public void onServiceConnected_registerCallbackBeforeConnection() {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);

        manager.registerCallback(mExecutor, mClientCallback);
        manager.getServiceConnection().onServiceConnected(COMPONENT_NAME, mIBinder);

        verify(mClientCallback).onServiceConnected();
    }

    @Test
    public void onServiceConnected_registerCallbackAfterConnection() {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);

        manager.getServiceConnection().onServiceConnected(COMPONENT_NAME, mIBinder);
        manager.registerCallback(mExecutor, mClientCallback);

        verify(mClientCallback).onServiceConnected();
    }

    /**
     * Verifies callback is called when service is disconnected
     */
    @Test
    public void onServiceDisconnected_registerCallbackBeforeConnection() {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);

        manager.registerCallback(mExecutor, mClientCallback);
        manager.getServiceConnection().onServiceConnected(COMPONENT_NAME, mIBinder);
        manager.getServiceConnection().onServiceDisconnected(COMPONENT_NAME);

        verify(mClientCallback).onServiceDisconnected();
    }

    @Test
    public void onServiceDisconnected_registerCallbackAfterConnection() {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);

        manager.getServiceConnection().onServiceConnected(COMPONENT_NAME, mIBinder);
        manager.registerCallback(mExecutor, mClientCallback);
        manager.getServiceConnection().onServiceDisconnected(COMPONENT_NAME);

        verify(mClientCallback).onServiceDisconnected();
    }

    /**
     * Verifies connectTetherNetwork behavior.
     */
    @Test
    public void connectTetherNetwork_serviceNotConnected_shouldFail() {
        TetherNetwork network = buildTetherNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertFalse(manager.connectTetherNetwork(network));
    }

    @Test
    public void connectTetherNetwork() throws RemoteException {
        TetherNetwork network = buildTetherNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        manager.connectTetherNetwork(network);

        verify(mService).connectTetherNetwork(network);
    }

    @Test
    public void connectTetherNetwork_remoteException_shouldFail() throws RemoteException {
        TetherNetwork network = buildTetherNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).connectTetherNetwork(network);

        assertFalse(manager.connectTetherNetwork(network));
    }

    /**
     * Verifies disconnectTetherNetwork behavior.
     */
    @Test
    public void disconnectTetherNetwork_serviceNotConnected_shouldFail() {
        TetherNetwork network = buildTetherNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertFalse(manager.disconnectTetherNetwork(network));
    }

    @Test
    public void disconnectTetherNetwork() throws RemoteException {
        TetherNetwork network = buildTetherNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        manager.disconnectTetherNetwork(network);

        verify(mService).disconnectTetherNetwork(network);
    }

    @Test
    public void disconnectTetherNetwork_remoteException_shouldFail() throws RemoteException {
        TetherNetwork network = buildTetherNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).disconnectTetherNetwork(any());

        assertFalse(manager.disconnectTetherNetwork(network));
    }

    /**
     * Verifies connectKnownNetwork behavior.
     */
    @Test
    public void connectKnownNetwork_serviceNotConnected_shouldFail() throws RemoteException {
        KnownNetwork network = buildKnownNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertFalse(manager.connectKnownNetwork(network));
    }

    @Test
    public void connectKnownNetwork() throws RemoteException {
        KnownNetwork network = buildKnownNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        manager.connectKnownNetwork(network);

        verify(mService).connectKnownNetwork(network);
    }

    @Test
    public void connectKnownNetwork_remoteException_shouldFail() throws RemoteException {
        KnownNetwork network = buildKnownNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).connectKnownNetwork(network);

        assertFalse(manager.connectKnownNetwork(network));
    }

    /**
     * Verifies forgetKnownNetwork behavior.
     */
    @Test
    public void forgetKnownNetwork_serviceNotConnected_shouldFail() {
        KnownNetwork network = buildKnownNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertFalse(manager.forgetKnownNetwork(network));
    }

    @Test
    public void forgetKnownNetwork_serviceConnected() throws RemoteException {
        KnownNetwork network = buildKnownNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        manager.forgetKnownNetwork(network);

        verify(mService).forgetKnownNetwork(network);
    }

    @Test
    public void forgetKnownNetwork_remoteException_shouldFail() throws RemoteException {
        KnownNetwork network = buildKnownNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).forgetKnownNetwork(network);

        assertFalse(manager.forgetKnownNetwork(network));
    }

    /**
     * Verify getters.
     */
    @Test
    public void getTetherNetworks_serviceNotConnected_shouldReturnEmptyList() {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertArrayEquals(List.of().toArray(), manager.getTetherNetworks().toArray());
    }

    @Test
    public void getTetherNetworks_remoteException_shouldReturnEmptyList() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).getTetherNetworks();

        assertArrayEquals(List.of().toArray(), manager.getTetherNetworks().toArray());
    }

    @Test
    public void getTetherNetworks_shouldReturnNetworksList() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        List<TetherNetwork> networks = List.of(buildTetherNetwork());
        List<TetherNetwork> expected = List.of(buildTetherNetwork());
        manager.setService(mService);
        when(mService.getTetherNetworks()).thenReturn(networks);

        assertArrayEquals(expected.toArray(), manager.getTetherNetworks().toArray());
    }

    @Test
    public void getKnownNetworks_serviceNotConnected_shouldReturnEmptyList()
            throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertArrayEquals(List.of().toArray(), manager.getKnownNetworks().toArray());
    }

    @Test
    public void getKnownNetworks_remoteException_shouldReturnEmptyList() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).getKnownNetworks();

        assertArrayEquals(List.of().toArray(), manager.getKnownNetworks().toArray());
    }

    @Test
    public void getKnownNetworks_shouldReturnNetworksList() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        List<KnownNetwork> networks = List.of(buildKnownNetwork());
        List<KnownNetwork> expected = List.of(buildKnownNetwork());
        manager.setService(mService);
        when(mService.getKnownNetworks()).thenReturn(networks);

        assertArrayEquals(expected.toArray(), manager.getKnownNetworks().toArray());
    }

    @Test
    public void getSettingsState_serviceNotConnected_shouldReturnNull() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertNull(manager.getSettingsState());
    }

    @Test
    public void getSettingsState_remoteException_shouldReturnNull() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).getSettingsState();

        assertNull(manager.getSettingsState());
    }

    @Test
    public void getSettingsState_serviceConnected_shouldReturnState() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        SharedConnectivitySettingsState state = new SharedConnectivitySettingsState.Builder()
                .setInstantTetherEnabled(true).setExtras(new Bundle()).build();
        manager.setService(mService);
        when(mService.getSettingsState()).thenReturn(state);

        assertEquals(state, manager.getSettingsState());
    }

    @Test
    public void getTetherNetworkConnectionStatus_serviceNotConnected_shouldReturnNull()
            throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertNull(manager.getTetherNetworkConnectionStatus());
    }

    @Test
    public void getTetherNetworkConnectionStatus_remoteException_shouldReturnNull()
            throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).getTetherNetworkConnectionStatus();

        assertNull(manager.getTetherNetworkConnectionStatus());
    }

    @Test
    public void getTetherNetworkConnectionStatus_serviceConnected_shouldReturnStatus()
            throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        TetherNetworkConnectionStatus status = new TetherNetworkConnectionStatus.Builder()
                .setStatus(TetherNetworkConnectionStatus.CONNECTION_STATUS_ENABLING_HOTSPOT)
                .setExtras(new Bundle()).build();
        manager.setService(mService);
        when(mService.getTetherNetworkConnectionStatus()).thenReturn(status);

        assertEquals(status, manager.getTetherNetworkConnectionStatus());
    }

    @Test
    public void getKnownNetworkConnectionStatus_serviceNotConnected_shouldReturnNull()
            throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertNull(manager.getKnownNetworkConnectionStatus());
    }

    @Test
    public void getKnownNetworkConnectionStatus_remoteException_shouldReturnNull()
            throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).getKnownNetworkConnectionStatus();

        assertNull(manager.getKnownNetworkConnectionStatus());
    }

    @Test
    public void getKnownNetworkConnectionStatus_serviceConnected_shouldReturnStatus()
            throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        KnownNetworkConnectionStatus status = new KnownNetworkConnectionStatus.Builder()
                .setStatus(KnownNetworkConnectionStatus.CONNECTION_STATUS_SAVED)
                .setExtras(new Bundle()).build();
        manager.setService(mService);
        when(mService.getKnownNetworkConnectionStatus()).thenReturn(status);

        assertEquals(status, manager.getKnownNetworkConnectionStatus());
    }

    private void setResources(@Mock Context context) {
        when(context.getResources()).thenReturn(mResources);
        when(mResources.getString(anyInt()))
                .thenReturn(SERVICE_PACKAGE_NAME, SERVICE_INTENT_ACTION);
    }

    private TetherNetwork buildTetherNetwork() {
        return new TetherNetwork.Builder()
                .setDeviceId(DEVICE_ID)
                .setDeviceInfo(DEVICE_INFO)
                .setNetworkType(NETWORK_TYPE)
                .setNetworkName(NETWORK_NAME)
                .setHotspotSsid(HOTSPOT_SSID)
                .setHotspotSecurityTypes(HOTSPOT_SECURITY_TYPES)
                .build();
    }

    private KnownNetwork buildKnownNetwork() {
        return new KnownNetwork.Builder().setNetworkSource(NETWORK_SOURCE).setSsid(SSID)
                .setSecurityTypes(SECURITY_TYPES).build();
    }
}
