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
import static android.net.wifi.sharedconnectivity.app.HotspotNetwork.NETWORK_TYPE_CELLULAR;
import static android.net.wifi.sharedconnectivity.app.KnownNetwork.NETWORK_SOURCE_NEARBY_SELF;
import static android.net.wifi.sharedconnectivity.app.NetworkProviderInfo.DEVICE_TYPE_TABLET;

import static com.google.common.truth.Truth.assertThat;

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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Unit tests for {@link SharedConnectivityManager}.
 */
@SmallTest
public class SharedConnectivityManagerTest {
    private static final long DEVICE_ID = 11L;
    private static final NetworkProviderInfo NETWORK_PROVIDER_INFO =
            new NetworkProviderInfo.Builder("TEST_NAME", "TEST_MODEL")
                    .setDeviceType(DEVICE_TYPE_TABLET).setConnectionStrength(2)
                    .setBatteryPercentage(50).build();
    private static final int NETWORK_TYPE = NETWORK_TYPE_CELLULAR;
    private static final String NETWORK_NAME = "TEST_NETWORK";
    private static final String HOTSPOT_SSID = "TEST_SSID";
    private static final int[] HOTSPOT_SECURITY_TYPES = {SECURITY_TYPE_WEP, SECURITY_TYPE_EAP};

    private static final int NETWORK_SOURCE = NETWORK_SOURCE_NEARBY_SELF;
    private static final String SSID = "TEST_SSID";
    private static final int[] SECURITY_TYPES = {SECURITY_TYPE_WEP};

    private static final String SERVICE_PACKAGE_NAME = "TEST_PACKAGE";
    private static final String SERVICE_INTENT_ACTION = "TEST_INTENT_ACTION";


    @Mock
    Context mContext;
    @Mock
    ISharedConnectivityService mService;
    @Mock
    Executor mExecutor;
    @Mock
    SharedConnectivityClientCallback mClientCallback;
    @Mock
    Resources mResources;
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

        assertThat(SharedConnectivityManager.create(mContext)).isNull();
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

        assertThat(manager.unregisterCallback(mClientCallback)).isFalse();
    }

    @Test
    public void unregisterCallback_withoutRegisteringFirst_serviceConnected_shouldFail() {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        assertThat(manager.unregisterCallback(mClientCallback)).isFalse();
    }

    @Test
    public void unregisterCallback() throws Exception {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        manager.registerCallback(mExecutor, mClientCallback);

        assertThat(manager.unregisterCallback(mClientCallback)).isTrue();
        verify(mService).unregisterCallback(any());
    }

    @Test
    public void unregisterCallback_doubleUnregistration_serviceConnected_shouldFail() {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        manager.registerCallback(mExecutor, mClientCallback);
        manager.unregisterCallback(mClientCallback);

        assertThat(manager.unregisterCallback(mClientCallback)).isFalse();
    }

    @Test
    public void unregisterCallback_doubleUnregistration_serviceNotConnected_shouldFail() {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        manager.registerCallback(mExecutor, mClientCallback);
        manager.unregisterCallback(mClientCallback);

        assertThat(manager.unregisterCallback(mClientCallback)).isFalse();
    }

    @Test
    public void unregisterCallback_remoteException_shouldFail() throws Exception {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        doThrow(new RemoteException()).when(mService).unregisterCallback(any());

        assertThat(manager.unregisterCallback(mClientCallback)).isFalse();
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
     * Verifies connectHotspotNetwork behavior.
     */
    @Test
    public void connectHotspotNetwork_serviceNotConnected_shouldFail() {
        HotspotNetwork network = buildHotspotNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertThat(manager.connectHotspotNetwork(network)).isFalse();
    }

    @Test
    public void connectHotspotNetwork() throws RemoteException {
        HotspotNetwork network = buildHotspotNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        manager.connectHotspotNetwork(network);

        verify(mService).connectHotspotNetwork(network);
    }

    @Test
    public void connectHotspotNetwork_remoteException_shouldFail() throws RemoteException {
        HotspotNetwork network = buildHotspotNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).connectHotspotNetwork(network);

        assertThat(manager.connectHotspotNetwork(network)).isFalse();
    }

    /**
     * Verifies disconnectHotspotNetwork behavior.
     */
    @Test
    public void disconnectHotspotNetwork_serviceNotConnected_shouldFail() {
        HotspotNetwork network = buildHotspotNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertThat(manager.disconnectHotspotNetwork(network)).isFalse();
    }

    @Test
    public void disconnectHotspotNetwork() throws RemoteException {
        HotspotNetwork network = buildHotspotNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);

        manager.disconnectHotspotNetwork(network);

        verify(mService).disconnectHotspotNetwork(network);
    }

    @Test
    public void disconnectHotspotNetwork_remoteException_shouldFail() throws RemoteException {
        HotspotNetwork network = buildHotspotNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).disconnectHotspotNetwork(any());

        assertThat(manager.disconnectHotspotNetwork(network)).isFalse();
    }

    /**
     * Verifies connectKnownNetwork behavior.
     */
    @Test
    public void connectKnownNetwork_serviceNotConnected_shouldFail() throws RemoteException {
        KnownNetwork network = buildKnownNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertThat(manager.connectKnownNetwork(network)).isFalse();
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

        assertThat(manager.connectKnownNetwork(network)).isFalse();
    }

    /**
     * Verifies forgetKnownNetwork behavior.
     */
    @Test
    public void forgetKnownNetwork_serviceNotConnected_shouldFail() {
        KnownNetwork network = buildKnownNetwork();
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertThat(manager.forgetKnownNetwork(network)).isFalse();
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

        assertThat(manager.forgetKnownNetwork(network)).isFalse();
    }

    /**
     * Verify getters.
     */
    @Test
    public void getHotspotNetworks_serviceNotConnected_shouldReturnNull() {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertThat(manager.getHotspotNetworks()).isNull();
    }

    @Test
    public void getHotspotNetworks_remoteException_shouldReturnNull() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).getHotspotNetworks();

        assertThat(manager.getHotspotNetworks()).isNull();
    }

    @Test
    public void getHotspotNetworks_shouldReturnNetworksList() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        List<HotspotNetwork> networks = List.of(buildHotspotNetwork());
        manager.setService(mService);
        when(mService.getHotspotNetworks()).thenReturn(networks);

        assertThat(manager.getHotspotNetworks()).containsExactly(buildHotspotNetwork());
    }

    @Test
    public void getKnownNetworks_serviceNotConnected_shouldReturnNull()
            throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertThat(manager.getKnownNetworks()).isNull();
    }

    @Test
    public void getKnownNetworks_remoteException_shouldReturnNull() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).getKnownNetworks();

        assertThat(manager.getKnownNetworks()).isNull();
    }

    @Test
    public void getKnownNetworks_shouldReturnNetworksList() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        List<KnownNetwork> networks = List.of(buildKnownNetwork());
        manager.setService(mService);
        when(mService.getKnownNetworks()).thenReturn(networks);

        assertThat(manager.getKnownNetworks()).containsExactly(buildKnownNetwork());
    }

    @Test
    public void getSettingsState_serviceNotConnected_shouldReturnNull() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertThat(manager.getSettingsState()).isNull();
    }

    @Test
    public void getSettingsState_remoteException_shouldReturnNull() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).getSettingsState();

        assertThat(manager.getSettingsState()).isNull();
    }

    @Test
    public void getSettingsState_serviceConnected_shouldReturnState() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        SharedConnectivitySettingsState state =
                new SharedConnectivitySettingsState.Builder(mContext).setInstantTetherEnabled(true)
                        .setExtras(new Bundle()).build();
        manager.setService(mService);
        when(mService.getSettingsState()).thenReturn(state);

        assertThat(manager.getSettingsState()).isEqualTo(state);
    }

    @Test
    public void getHotspotNetworkConnectionStatus_serviceNotConnected_shouldReturnNull()
            throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertThat(manager.getHotspotNetworkConnectionStatus()).isNull();
    }

    @Test
    public void getHotspotNetworkConnectionStatus_remoteException_shouldReturnNull()
            throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).getHotspotNetworkConnectionStatus();

        assertThat(manager.getHotspotNetworkConnectionStatus()).isNull();
    }

    @Test
    public void getHotspotNetworkConnectionStatus_serviceConnected_shouldReturnStatus()
            throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        HotspotNetworkConnectionStatus status = new HotspotNetworkConnectionStatus.Builder()
                .setStatus(HotspotNetworkConnectionStatus.CONNECTION_STATUS_ENABLING_HOTSPOT)
                .setExtras(new Bundle()).build();
        manager.setService(mService);
        when(mService.getHotspotNetworkConnectionStatus()).thenReturn(status);

        assertThat(manager.getHotspotNetworkConnectionStatus()).isEqualTo(status);
    }

    @Test
    public void getKnownNetworkConnectionStatus_serviceNotConnected_shouldReturnNull()
            throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);

        assertThat(manager.getKnownNetworkConnectionStatus()).isNull();
    }

    @Test
    public void getKnownNetworkConnectionStatus_remoteException_shouldReturnNull()
            throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).getKnownNetworkConnectionStatus();

        assertThat(manager.getKnownNetworkConnectionStatus()).isNull();
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

        assertThat(manager.getKnownNetworkConnectionStatus()).isEqualTo(status);
    }

    private void setResources(@Mock Context context) {
        when(context.getResources()).thenReturn(mResources);
        when(mResources.getString(anyInt()))
                .thenReturn(SERVICE_PACKAGE_NAME, SERVICE_INTENT_ACTION);
    }

    private HotspotNetwork buildHotspotNetwork() {
        HotspotNetwork.Builder builder = new HotspotNetwork.Builder()
                .setDeviceId(DEVICE_ID)
                .setNetworkProviderInfo(NETWORK_PROVIDER_INFO)
                .setHostNetworkType(NETWORK_TYPE)
                .setNetworkName(NETWORK_NAME)
                .setHotspotSsid(HOTSPOT_SSID);
        Arrays.stream(HOTSPOT_SECURITY_TYPES).forEach(builder::addHotspotSecurityType);
        return builder.build();
    }

    private KnownNetwork buildKnownNetwork() {
        KnownNetwork.Builder builder = new KnownNetwork.Builder().setNetworkSource(NETWORK_SOURCE)
                .setSsid(SSID).setNetworkProviderInfo(NETWORK_PROVIDER_INFO);
        Arrays.stream(SECURITY_TYPES).forEach(builder::addSecurityType);
        return builder.build();
    }
}
