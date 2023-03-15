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
import static android.net.wifi.sharedconnectivity.app.HotspotNetwork.NETWORK_TYPE_CELLULAR;
import static android.net.wifi.sharedconnectivity.app.HotspotNetworkConnectionStatus.CONNECTION_STATUS_UNKNOWN;
import static android.net.wifi.sharedconnectivity.app.KnownNetwork.NETWORK_SOURCE_NEARBY_SELF;
import static android.net.wifi.sharedconnectivity.app.KnownNetworkConnectionStatus.CONNECTION_STATUS_SAVED;
import static android.net.wifi.sharedconnectivity.app.NetworkProviderInfo.DEVICE_TYPE_TABLET;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.wifi.sharedconnectivity.app.HotspotNetwork;
import android.net.wifi.sharedconnectivity.app.HotspotNetworkConnectionStatus;
import android.net.wifi.sharedconnectivity.app.KnownNetwork;
import android.net.wifi.sharedconnectivity.app.KnownNetworkConnectionStatus;
import android.net.wifi.sharedconnectivity.app.NetworkProviderInfo;
import android.net.wifi.sharedconnectivity.app.SharedConnectivitySettingsState;
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
 * Unit tests for {@link SharedConnectivityService}.
 */
@SmallTest
public class SharedConnectivityServiceTest {
    private static final NetworkProviderInfo NETWORK_PROVIDER_INFO =
            new NetworkProviderInfo.Builder("TEST_NAME", "TEST_MODEL")
                    .setDeviceType(DEVICE_TYPE_TABLET).setConnectionStrength(2)
                    .setBatteryPercentage(50).build();
    private static final HotspotNetwork HOTSPOT_NETWORK =
            new HotspotNetwork.Builder().setDeviceId(1).setNetworkProviderInfo(
                            NETWORK_PROVIDER_INFO)
                    .setHostNetworkType(NETWORK_TYPE_CELLULAR).setNetworkName("TEST_NETWORK")
                    .setHotspotSsid("TEST_SSID").setHotspotBssid("TEST_BSSID")
                    .addHotspotSecurityType(SECURITY_TYPE_WEP)
                    .addHotspotSecurityType(SECURITY_TYPE_EAP).build();
    private static final List<HotspotNetwork> HOTSPOT_NETWORKS = List.of(HOTSPOT_NETWORK);
    private static final KnownNetwork KNOWN_NETWORK =
            new KnownNetwork.Builder().setNetworkSource(NETWORK_SOURCE_NEARBY_SELF)
                    .setSsid("TEST_SSID").addSecurityType(SECURITY_TYPE_WEP)
                    .addSecurityType(SECURITY_TYPE_EAP).setNetworkProviderInfo(
                            NETWORK_PROVIDER_INFO).build();
    private static final List<KnownNetwork> KNOWN_NETWORKS = List.of(KNOWN_NETWORK);
    private static final HotspotNetworkConnectionStatus TETHER_NETWORK_CONNECTION_STATUS =
            new HotspotNetworkConnectionStatus.Builder().setStatus(CONNECTION_STATUS_UNKNOWN)
                    .setHotspotNetwork(HOTSPOT_NETWORK).setExtras(Bundle.EMPTY).build();
    private static final KnownNetworkConnectionStatus KNOWN_NETWORK_CONNECTION_STATUS =
            new KnownNetworkConnectionStatus.Builder().setStatus(CONNECTION_STATUS_SAVED)
                    .setKnownNetwork(KNOWN_NETWORK).setExtras(Bundle.EMPTY).build();

    @Mock
    Context mContext;

    @Mock
    Resources mResources;

    static class FakeSharedConnectivityService extends SharedConnectivityService {
        public void attachBaseContext(Context context) {
            super.attachBaseContext(context);
        }

        @Override
        public void onConnectHotspotNetwork(@NonNull HotspotNetwork network) {
        }

        @Override
        public void onDisconnectHotspotNetwork(@NonNull HotspotNetwork network) {
        }

        @Override
        public void onConnectKnownNetwork(@NonNull KnownNetwork network) {
        }

        @Override
        public void onForgetKnownNetwork(@NonNull KnownNetwork network) {
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
    }

    @Test
    public void onBind_isNotNull() {
        SharedConnectivityService service = createService();

        assertThat(service.onBind(new Intent())).isNotNull();
    }

    @Test
    public void getHotspotNetworks() throws RemoteException {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());

        service.setHotspotNetworks(HOTSPOT_NETWORKS);

        assertThat(binder.getHotspotNetworks())
                .containsExactlyElementsIn(List.copyOf(HOTSPOT_NETWORKS));
    }

    @Test
    public void getKnownNetworks() throws RemoteException {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());

        service.setKnownNetworks(KNOWN_NETWORKS);

        assertThat(binder.getKnownNetworks())
                .containsExactlyElementsIn(List.copyOf(KNOWN_NETWORKS));
    }

    @Test
    public void getSharedConnectivitySettingsState() throws RemoteException {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());
        when(mContext.getPackageName()).thenReturn("android.net.wifi.nonupdatable.test");

        service.setSettingsState(buildSettingsState());

        assertThat(binder.getSettingsState()).isEqualTo(buildSettingsState());
    }

    @Test
    public void updateHotspotNetworkConnectionStatus() throws RemoteException {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());

        service.updateHotspotNetworkConnectionStatus(TETHER_NETWORK_CONNECTION_STATUS);

        assertThat(binder.getHotspotNetworkConnectionStatus())
                .isEqualTo(TETHER_NETWORK_CONNECTION_STATUS);
    }

    @Test
    public void updateKnownNetworkConnectionStatus() throws RemoteException {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());

        service.updateKnownNetworkConnectionStatus(KNOWN_NETWORK_CONNECTION_STATUS);

        assertThat(binder.getKnownNetworkConnectionStatus())
                .isEqualTo(KNOWN_NETWORK_CONNECTION_STATUS);
    }

    @Test
    public void areHotspotNetworksEnabledForService() {
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getPackageName()).thenReturn("package");
        when(mResources.getString(anyInt())).thenReturn("package");
        when(mResources.getBoolean(anyInt())).thenReturn(true);

        assertThat(SharedConnectivityService.areHotspotNetworksEnabledForService(mContext))
                .isTrue();
    }

    @Test
    public void areHotspotNetworksEnabledForService_notSamePackage_shouldReturnFalse() {
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getPackageName()).thenReturn("package");
        when(mResources.getString(anyInt())).thenReturn("other_package");
        when(mResources.getBoolean(anyInt())).thenReturn(true);

        assertThat(SharedConnectivityService.areHotspotNetworksEnabledForService(mContext))
                .isFalse();
    }

    @Test
    public void areKnownNetworksEnabledForService() {
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getPackageName()).thenReturn("package");
        when(mResources.getString(anyInt())).thenReturn("package");
        when(mResources.getBoolean(anyInt())).thenReturn(true);

        assertThat(SharedConnectivityService.areKnownNetworksEnabledForService(mContext)).isTrue();
    }

    @Test
    public void areKnownNetworksEnabledForService_notSamePackage_shouldReturnFalse() {
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getPackageName()).thenReturn("package");
        when(mResources.getString(anyInt())).thenReturn("other_package");
        when(mResources.getBoolean(anyInt())).thenReturn(true);

        assertThat(SharedConnectivityService.areKnownNetworksEnabledForService(mContext)).isFalse();
    }

    private SharedConnectivityService createService() {
        FakeSharedConnectivityService service = new FakeSharedConnectivityService();
        service.attachBaseContext(mContext);
        return service;
    }

    private SharedConnectivitySettingsState buildSettingsState() {
        return new SharedConnectivitySettingsState.Builder(mContext).setInstantTetherEnabled(true)
                .setInstantTetherSettingsPendingIntent(new Intent())
                .setExtras(Bundle.EMPTY).build();
    }
}
