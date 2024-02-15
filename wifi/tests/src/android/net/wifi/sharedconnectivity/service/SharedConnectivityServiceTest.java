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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
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
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link SharedConnectivityService}.
 */
@SmallTest
public class SharedConnectivityServiceTest {
    private static final int LATCH_TIMEOUT = 2;

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
    private static final HotspotNetworkConnectionStatus HOTSPOT_NETWORK_CONNECTION_STATUS =
            new HotspotNetworkConnectionStatus.Builder().setStatus(CONNECTION_STATUS_UNKNOWN)
                    .setHotspotNetwork(HOTSPOT_NETWORK).setExtras(Bundle.EMPTY).build();
    private static final KnownNetworkConnectionStatus KNOWN_NETWORK_CONNECTION_STATUS =
            new KnownNetworkConnectionStatus.Builder().setStatus(CONNECTION_STATUS_SAVED)
                    .setKnownNetwork(KNOWN_NETWORK).setExtras(Bundle.EMPTY).build();

    @Mock
    Context mContext;

    @Mock
    Resources mResources;

    @Mock
    ISharedConnectivityCallback mCallback;

    @Mock
    IBinder mBinder;

    static class FakeSharedConnectivityService extends SharedConnectivityService {
        public void attachBaseContext(Context context) {
            super.attachBaseContext(context);
        }

        private HotspotNetwork mConnectedHotspotNetwork;
        private HotspotNetwork mDisconnectedHotspotNetwork;
        private KnownNetwork mConnectedKnownNetwork;
        private KnownNetwork mForgottenKnownNetwork;
        private CountDownLatch mLatch;

        public HotspotNetwork getConnectedHotspotNetwork() {
            return mConnectedHotspotNetwork;
        }

        public HotspotNetwork getDisconnectedHotspotNetwork() {
            return mDisconnectedHotspotNetwork;
        }

        public KnownNetwork getConnectedKnownNetwork() {
            return mConnectedKnownNetwork;
        }

        public KnownNetwork getForgottenKnownNetwork() {
            return mForgottenKnownNetwork;
        }

        public void initializeLatch() {
            mLatch = new CountDownLatch(1);
        }

        public CountDownLatch getLatch() {
            return mLatch;
        }

        @Override
        public void onConnectHotspotNetwork(@NonNull HotspotNetwork network) {
            mConnectedHotspotNetwork = network;
            if (mLatch != null) {
                mLatch.countDown();
            }
        }

        @Override
        public void onDisconnectHotspotNetwork(@NonNull HotspotNetwork network) {
            mDisconnectedHotspotNetwork = network;
            if (mLatch != null) {
                mLatch.countDown();
            }
        }

        @Override
        public void onConnectKnownNetwork(@NonNull KnownNetwork network) {
            mConnectedKnownNetwork = network;
            if (mLatch != null) {
                mLatch.countDown();
            }
        }

        @Override
        public void onForgetKnownNetwork(@NonNull KnownNetwork network) {
            mForgottenKnownNetwork = network;
            if (mLatch != null) {
                mLatch.countDown();
            }
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

        service.updateHotspotNetworkConnectionStatus(HOTSPOT_NETWORK_CONNECTION_STATUS);

        assertThat(binder.getHotspotNetworkConnectionStatus())
                .isEqualTo(HOTSPOT_NETWORK_CONNECTION_STATUS);
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

    @Test
    public void connectHotspotNetwork() throws RemoteException, InterruptedException {
        FakeSharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());
        service.initializeLatch();

        binder.connectHotspotNetwork(HOTSPOT_NETWORK);

        assertThat(service.getLatch().await(LATCH_TIMEOUT, TimeUnit.SECONDS)).isTrue();
        assertThat(service.getConnectedHotspotNetwork()).isEqualTo(HOTSPOT_NETWORK);
    }

    @Test
    public void disconnectHotspotNetwork() throws RemoteException, InterruptedException {
        FakeSharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());
        service.initializeLatch();

        binder.disconnectHotspotNetwork(HOTSPOT_NETWORK);

        assertThat(service.getLatch().await(LATCH_TIMEOUT, TimeUnit.SECONDS)).isTrue();
        assertThat(service.getDisconnectedHotspotNetwork()).isEqualTo(HOTSPOT_NETWORK);
    }

    @Test
    public void connectKnownNetwork() throws RemoteException , InterruptedException {
        FakeSharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());
        service.initializeLatch();

        binder.connectKnownNetwork(KNOWN_NETWORK);

        assertThat(service.getLatch().await(LATCH_TIMEOUT, TimeUnit.SECONDS)).isTrue();
        assertThat(service.getConnectedKnownNetwork()).isEqualTo(KNOWN_NETWORK);
    }

    @Test
    public void forgetKnownNetwork() throws RemoteException, InterruptedException {
        FakeSharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());
        service.initializeLatch();

        binder.forgetKnownNetwork(KNOWN_NETWORK);

        assertThat(service.getLatch().await(LATCH_TIMEOUT, TimeUnit.SECONDS)).isTrue();
        assertThat(service.getForgottenKnownNetwork()).isEqualTo(KNOWN_NETWORK);
    }

    @Test
    public void registerCallback() throws RemoteException, InterruptedException {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());
        when(mCallback.asBinder()).thenReturn(mBinder);
        when(mContext.getPackageName()).thenReturn("android.net.wifi.nonupdatable.test");
        SharedConnectivitySettingsState state = buildSettingsState();

        CountDownLatch latch = new CountDownLatch(1);
        service.setCountdownLatch(latch);
        binder.registerCallback(mCallback);
        assertThat(latch.await(LATCH_TIMEOUT, TimeUnit.SECONDS)).isTrue();
        service.setHotspotNetworks(HOTSPOT_NETWORKS);
        service.setKnownNetworks(KNOWN_NETWORKS);
        service.setSettingsState(state);
        service.updateHotspotNetworkConnectionStatus(HOTSPOT_NETWORK_CONNECTION_STATUS);
        service.updateKnownNetworkConnectionStatus(KNOWN_NETWORK_CONNECTION_STATUS);

        verify(mCallback).onHotspotNetworksUpdated(HOTSPOT_NETWORKS);
        verify(mCallback).onKnownNetworksUpdated(KNOWN_NETWORKS);
        verify(mCallback).onSharedConnectivitySettingsChanged(state);
        verify(mCallback).onHotspotNetworkConnectionStatusChanged(
                HOTSPOT_NETWORK_CONNECTION_STATUS);
        verify(mCallback).onKnownNetworkConnectionStatusChanged(KNOWN_NETWORK_CONNECTION_STATUS);
    }

    @Test
    public void unregisterCallback() throws RemoteException, InterruptedException {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());
        when(mCallback.asBinder()).thenReturn(mBinder);
        when(mContext.getPackageName()).thenReturn("android.net.wifi.nonupdatable.test");

        CountDownLatch latch = new CountDownLatch(1);
        service.setCountdownLatch(latch);
        binder.registerCallback(mCallback);
        assertThat(latch.await(LATCH_TIMEOUT, TimeUnit.SECONDS)).isTrue();
        latch = new CountDownLatch(1);
        service.setCountdownLatch(latch);
        binder.unregisterCallback(mCallback);
        assertThat(latch.await(LATCH_TIMEOUT, TimeUnit.SECONDS)).isTrue();
        service.setHotspotNetworks(HOTSPOT_NETWORKS);
        service.setKnownNetworks(KNOWN_NETWORKS);
        service.setSettingsState(buildSettingsState());
        service.updateHotspotNetworkConnectionStatus(HOTSPOT_NETWORK_CONNECTION_STATUS);
        service.updateKnownNetworkConnectionStatus(KNOWN_NETWORK_CONNECTION_STATUS);

        verify(mCallback, never()).onHotspotNetworksUpdated(any());
        verify(mCallback, never()).onKnownNetworksUpdated(any());
        verify(mCallback, never()).onSharedConnectivitySettingsChanged(any());
        verify(mCallback, never()).onHotspotNetworkConnectionStatusChanged(any());
        verify(mCallback, never()).onKnownNetworkConnectionStatusChanged(any());
    }

    @Test
    public void getHotspotNetworkConnectionStatus_withoutUpdate_returnsNull()
            throws RemoteException {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());

        assertThat(binder.getHotspotNetworkConnectionStatus()).isNull();
    }

    @Test
    public void getKnownNetworkConnectionStatus_withoutUpdate_returnsNull()
            throws RemoteException {
        SharedConnectivityService service = createService();
        ISharedConnectivityService.Stub binder =
                (ISharedConnectivityService.Stub) service.onBind(new Intent());

        assertThat(binder.getKnownNetworkConnectionStatus()).isNull();
    }

    private FakeSharedConnectivityService createService() {
        FakeSharedConnectivityService service = new FakeSharedConnectivityService();
        service.attachBaseContext(mContext);
        return service;
    }

    private SharedConnectivitySettingsState buildSettingsState() {
        return new SharedConnectivitySettingsState.Builder().setInstantTetherEnabled(true)
                .setInstantTetherSettingsPendingIntent(
                        PendingIntent.getActivity(mContext, 0, new Intent(),
                                PendingIntent.FLAG_IMMUTABLE))
                .setExtras(Bundle.EMPTY).build();
    }
}
