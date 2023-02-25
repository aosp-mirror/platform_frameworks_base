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

package android.net.wifi.sharedconnectivity.service;

import static android.net.wifi.WifiInfo.SECURITY_TYPE_EAP;
import static android.net.wifi.WifiInfo.SECURITY_TYPE_WEP;
import static android.net.wifi.sharedconnectivity.app.DeviceInfo.DEVICE_TYPE_TABLET;
import static android.net.wifi.sharedconnectivity.app.KnownNetwork.NETWORK_SOURCE_NEARBY_SELF;
import static android.net.wifi.sharedconnectivity.app.KnownNetworkConnectionStatus.CONNECTION_STATUS_SAVED;
import static android.net.wifi.sharedconnectivity.app.TetherNetwork.NETWORK_TYPE_CELLULAR;
import static android.net.wifi.sharedconnectivity.app.TetherNetworkConnectionStatus.CONNECTION_STATUS_UNKNOWN;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.sharedconnectivity.app.DeviceInfo;
import android.net.wifi.sharedconnectivity.app.KnownNetwork;
import android.net.wifi.sharedconnectivity.app.KnownNetworkConnectionStatus;
import android.net.wifi.sharedconnectivity.app.SharedConnectivitySettingsState;
import android.net.wifi.sharedconnectivity.app.TetherNetwork;
import android.net.wifi.sharedconnectivity.app.TetherNetworkConnectionStatus;
import android.os.Bundle;
import android.os.Looper;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/**
 * Unit tests for {@link android.net.wifi.sharedconnectivity.service.SharedConnectivityService}.
 */
@SmallTest
public class SharedConnectivityServiceTest {
    private static final int[] SECURITY_TYPES = {SECURITY_TYPE_WEP, SECURITY_TYPE_EAP};
    private static final DeviceInfo DEVICE_INFO = new DeviceInfo.Builder()
            .setDeviceType(DEVICE_TYPE_TABLET).setDeviceName("TEST_NAME").setModelName("TEST_MODEL")
            .setConnectionStrength(2).setBatteryPercentage(50).build();
    private static final TetherNetwork TETHER_NETWORK =
            new TetherNetwork.Builder().setDeviceId(1).setDeviceInfo(DEVICE_INFO)
                    .setNetworkType(NETWORK_TYPE_CELLULAR).setNetworkName("TEST_NETWORK")
                    .setHotspotSsid("TEST_SSID").setHotspotBssid("TEST_BSSID")
                    .setHotspotSecurityTypes(SECURITY_TYPES).build();
    private static final List<TetherNetwork> TETHER_NETWORKS = List.of(TETHER_NETWORK);
    private static final KnownNetwork KNOWN_NETWORK =
            new KnownNetwork.Builder().setNetworkSource(NETWORK_SOURCE_NEARBY_SELF)
                    .setSsid("TEST_SSID").setSecurityTypes(SECURITY_TYPES)
                    .setDeviceInfo(DEVICE_INFO).build();
    private static final List<KnownNetwork> KNOWN_NETWORKS = List.of(KNOWN_NETWORK);
    private static final SharedConnectivitySettingsState SETTINGS_STATE =
            new SharedConnectivitySettingsState.Builder().setInstantTetherEnabled(true)
                    .setExtras(Bundle.EMPTY).build();
    private static final TetherNetworkConnectionStatus TETHER_NETWORK_CONNECTION_STATUS =
            new TetherNetworkConnectionStatus.Builder().setStatus(CONNECTION_STATUS_UNKNOWN)
                    .setTetherNetwork(TETHER_NETWORK).setExtras(Bundle.EMPTY).build();
    private static final KnownNetworkConnectionStatus KNOWN_NETWORK_CONNECTION_STATUS =
            new KnownNetworkConnectionStatus.Builder().setStatus(CONNECTION_STATUS_SAVED)
                    .setKnownNetwork(KNOWN_NETWORK).setExtras(Bundle.EMPTY).build();

    @Mock
    Context mContext;

    static class FakeSharedConnectivityService extends SharedConnectivityService {
        public void attachBaseContext(Context context) {
            super.attachBaseContext(context);
        }

        @Override
        public void onConnectTetherNetwork(@NonNull TetherNetwork network) {}

        @Override
        public void onDisconnectTetherNetwork(@NonNull TetherNetwork network) {}

        @Override
        public void onConnectKnownNetwork(@NonNull KnownNetwork network) {}

        @Override
        public void onForgetKnownNetwork(@NonNull KnownNetwork network) {}
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
    }

    @Test
    public void onBind_isNotNull() {
        SharedConnectivityService service = createService();
        assertNotNull(service.onBind(new Intent()));
    }

    @Test
    public void getTetherNetworks() throws RemoteException {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());

        service.setTetherNetworks(TETHER_NETWORKS);
        assertArrayEquals(TETHER_NETWORKS.toArray(), binder.getTetherNetworks().toArray());
    }

    @Test
    public void getKnownNetworks() throws RemoteException {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());

        service.setKnownNetworks(KNOWN_NETWORKS);
        assertArrayEquals(KNOWN_NETWORKS.toArray(), binder.getKnownNetworks().toArray());
    }

    @Test
    public void getSharedConnectivitySettingsState() throws RemoteException {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());

        service.setSettingsState(SETTINGS_STATE);
        assertEquals(SETTINGS_STATE, binder.getSettingsState());
    }

    @Test
    public void updateTetherNetworkConnectionStatus() throws RemoteException {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());

        service.updateTetherNetworkConnectionStatus(TETHER_NETWORK_CONNECTION_STATUS);
        assertEquals(TETHER_NETWORK_CONNECTION_STATUS, binder.getTetherNetworkConnectionStatus());
    }

    @Test
    public void updateKnownNetworkConnectionStatus() throws RemoteException {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());

        service.updateKnownNetworkConnectionStatus(KNOWN_NETWORK_CONNECTION_STATUS);
        assertEquals(KNOWN_NETWORK_CONNECTION_STATUS, binder.getKnownNetworkConnectionStatus());
    }

    private SharedConnectivityService createService() {
        FakeSharedConnectivityService service = new FakeSharedConnectivityService();
        service.attachBaseContext(mContext);
        return service;
    }
}
