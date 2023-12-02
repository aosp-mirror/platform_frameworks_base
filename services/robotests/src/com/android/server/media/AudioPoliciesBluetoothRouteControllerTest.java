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

package com.android.server.media;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaRoute2Info;
import android.os.UserHandle;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowBluetoothAdapter;
import org.robolectric.shadows.ShadowBluetoothDevice;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class AudioPoliciesBluetoothRouteControllerTest {

    private static final String DEVICE_ADDRESS_UNKNOWN = ":unknown:ip:address:";
    private static final String DEVICE_ADDRESS_SAMPLE_1 = "30:59:8B:E4:C6:35";
    private static final String DEVICE_ADDRESS_SAMPLE_2 = "0D:0D:A6:FF:8D:B6";
    private static final String DEVICE_ADDRESS_SAMPLE_3 = "2D:9B:0C:C2:6F:78";
    private static final String DEVICE_ADDRESS_SAMPLE_4 = "66:88:F9:2D:A8:1E";

    private Context mContext;

    private ShadowBluetoothAdapter mShadowBluetoothAdapter;

    @Mock
    private BluetoothRouteController.BluetoothRoutesUpdatedListener mListener;

    @Mock
    private BluetoothProfileMonitor mBluetoothProfileMonitor;

    private AudioPoliciesBluetoothRouteController mAudioPoliciesBluetoothRouteController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Application application = ApplicationProvider.getApplicationContext();
        mContext = application;

        BluetoothManager bluetoothManager = (BluetoothManager)
                mContext.getSystemService(Context.BLUETOOTH_SERVICE);

        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        mShadowBluetoothAdapter = Shadows.shadowOf(bluetoothAdapter);

        mAudioPoliciesBluetoothRouteController =
                new AudioPoliciesBluetoothRouteController(mContext, bluetoothAdapter,
                        mBluetoothProfileMonitor, mListener) {
                    @Override
                    boolean isDeviceConnected(BluetoothDevice device) {
                        return true;
                    }
                };

        // Enable A2DP profile.
        when(mBluetoothProfileMonitor.isProfileSupported(eq(BluetoothProfile.A2DP), any()))
                .thenReturn(true);
        mShadowBluetoothAdapter.setProfileConnectionState(BluetoothProfile.A2DP,
                BluetoothProfile.STATE_CONNECTED);

        mAudioPoliciesBluetoothRouteController.start(UserHandle.of(0));
    }

    @Test
    public void getSelectedRoute_noBluetoothRoutesAvailable_returnsNull() {
        assertThat(mAudioPoliciesBluetoothRouteController.getSelectedRoute()).isNull();
    }

    @Test
    public void selectRoute_noBluetoothRoutesAvailable_returnsFalse() {
        assertThat(mAudioPoliciesBluetoothRouteController
                .selectRoute(DEVICE_ADDRESS_UNKNOWN)).isFalse();
    }

    @Test
    public void selectRoute_noDeviceWithGivenAddress_returnsFalse() {
        Set<BluetoothDevice> devices = generateFakeBluetoothDevicesSet(
                DEVICE_ADDRESS_SAMPLE_1, DEVICE_ADDRESS_SAMPLE_3);

        mShadowBluetoothAdapter.setBondedDevices(devices);

        assertThat(mAudioPoliciesBluetoothRouteController
                .selectRoute(DEVICE_ADDRESS_SAMPLE_2)).isFalse();
    }

    @Test
    public void selectRoute_deviceIsInDevicesSet_returnsTrue() {
        Set<BluetoothDevice> devices = generateFakeBluetoothDevicesSet(
                DEVICE_ADDRESS_SAMPLE_1, DEVICE_ADDRESS_SAMPLE_2);

        mShadowBluetoothAdapter.setBondedDevices(devices);

        assertThat(mAudioPoliciesBluetoothRouteController
                .selectRoute(DEVICE_ADDRESS_SAMPLE_1)).isTrue();
    }

    @Test
    public void selectRoute_resetSelectedDevice_returnsTrue() {
        Set<BluetoothDevice> devices = generateFakeBluetoothDevicesSet(
                DEVICE_ADDRESS_SAMPLE_1, DEVICE_ADDRESS_SAMPLE_2);

        mShadowBluetoothAdapter.setBondedDevices(devices);

        mAudioPoliciesBluetoothRouteController.selectRoute(DEVICE_ADDRESS_SAMPLE_1);
        assertThat(mAudioPoliciesBluetoothRouteController.selectRoute(null)).isTrue();
    }

    @Test
    public void selectRoute_noSelectedDevice_returnsTrue() {
        Set<BluetoothDevice> devices = generateFakeBluetoothDevicesSet(
                DEVICE_ADDRESS_SAMPLE_1, DEVICE_ADDRESS_SAMPLE_2);

        mShadowBluetoothAdapter.setBondedDevices(devices);

        assertThat(mAudioPoliciesBluetoothRouteController.selectRoute(null)).isTrue();
    }

    @Test
    public void getSelectedRoute_updateRouteFailed_returnsNull() {
        Set<BluetoothDevice> devices = generateFakeBluetoothDevicesSet(
                DEVICE_ADDRESS_SAMPLE_1, DEVICE_ADDRESS_SAMPLE_2);

        mShadowBluetoothAdapter.setBondedDevices(devices);
        mAudioPoliciesBluetoothRouteController
                .selectRoute(DEVICE_ADDRESS_SAMPLE_3);

        assertThat(mAudioPoliciesBluetoothRouteController.getSelectedRoute()).isNull();
    }

    @Test
    public void getSelectedRoute_updateRouteSuccessful_returnsUpdateDevice() {
        Set<BluetoothDevice> devices = generateFakeBluetoothDevicesSet(
                DEVICE_ADDRESS_SAMPLE_1, DEVICE_ADDRESS_SAMPLE_2, DEVICE_ADDRESS_SAMPLE_4);

        assertThat(mAudioPoliciesBluetoothRouteController.getSelectedRoute()).isNull();

        mShadowBluetoothAdapter.setBondedDevices(devices);

        assertThat(mAudioPoliciesBluetoothRouteController
                .selectRoute(DEVICE_ADDRESS_SAMPLE_4)).isTrue();

        MediaRoute2Info selectedRoute = mAudioPoliciesBluetoothRouteController.getSelectedRoute();
        assertThat(selectedRoute.getAddress()).isEqualTo(DEVICE_ADDRESS_SAMPLE_4);
    }

    @Test
    public void getSelectedRoute_resetSelectedRoute_returnsNull() {
        Set<BluetoothDevice> devices = generateFakeBluetoothDevicesSet(
                DEVICE_ADDRESS_SAMPLE_1, DEVICE_ADDRESS_SAMPLE_2, DEVICE_ADDRESS_SAMPLE_4);

        mShadowBluetoothAdapter.setBondedDevices(devices);

        // Device is not null now.
        mAudioPoliciesBluetoothRouteController.selectRoute(DEVICE_ADDRESS_SAMPLE_4);
        // Rest the device.
        mAudioPoliciesBluetoothRouteController.selectRoute(null);

        assertThat(mAudioPoliciesBluetoothRouteController.getSelectedRoute())
                .isNull();
    }

    @Test
    public void getTransferableRoutes_noSelectedRoute_returnsAllBluetoothDevices() {
        String[] addresses = new String[] { DEVICE_ADDRESS_SAMPLE_1,
                DEVICE_ADDRESS_SAMPLE_2, DEVICE_ADDRESS_SAMPLE_4 };
        Set<BluetoothDevice> devices = generateFakeBluetoothDevicesSet(addresses);
        mShadowBluetoothAdapter.setBondedDevices(devices);

        // Force route controller to update bluetooth devices list.
        sendBluetoothDevicesChangedBroadcast();

        Set<String> transferableDevices = extractAddressesListFrom(
                mAudioPoliciesBluetoothRouteController.getTransferableRoutes());
        assertThat(transferableDevices).containsExactlyElementsIn(addresses);
    }

    @Test
    public void getTransferableRoutes_hasSelectedRoute_returnsRoutesWithoutSelectedDevice() {
        String[] addresses = new String[] { DEVICE_ADDRESS_SAMPLE_1,
                DEVICE_ADDRESS_SAMPLE_2, DEVICE_ADDRESS_SAMPLE_4 };
        Set<BluetoothDevice> devices = generateFakeBluetoothDevicesSet(addresses);
        mShadowBluetoothAdapter.setBondedDevices(devices);

        // Force route controller to update bluetooth devices list.
        sendBluetoothDevicesChangedBroadcast();
        mAudioPoliciesBluetoothRouteController.selectRoute(DEVICE_ADDRESS_SAMPLE_4);

        Set<String> transferableDevices = extractAddressesListFrom(
                mAudioPoliciesBluetoothRouteController.getTransferableRoutes());
        assertThat(transferableDevices).containsExactly(DEVICE_ADDRESS_SAMPLE_1,
                DEVICE_ADDRESS_SAMPLE_2);
    }

    @Test
    public void getAllBluetoothRoutes_hasSelectedRoute_returnsAllRoutes() {
        String[] addresses = new String[] { DEVICE_ADDRESS_SAMPLE_1,
                DEVICE_ADDRESS_SAMPLE_2, DEVICE_ADDRESS_SAMPLE_4 };
        Set<BluetoothDevice> devices = generateFakeBluetoothDevicesSet(addresses);
        mShadowBluetoothAdapter.setBondedDevices(devices);

        // Force route controller to update bluetooth devices list.
        sendBluetoothDevicesChangedBroadcast();
        mAudioPoliciesBluetoothRouteController.selectRoute(DEVICE_ADDRESS_SAMPLE_4);

        Set<String> bluetoothDevices = extractAddressesListFrom(
                mAudioPoliciesBluetoothRouteController.getAllBluetoothRoutes());
        assertThat(bluetoothDevices).containsExactlyElementsIn(addresses);
    }

    @Test
    public void updateVolumeForDevice_setVolumeForA2DPTo25_selectedRouteVolumeIsUpdated() {
        String[] addresses = new String[] { DEVICE_ADDRESS_SAMPLE_1,
                DEVICE_ADDRESS_SAMPLE_2, DEVICE_ADDRESS_SAMPLE_4 };
        Set<BluetoothDevice> devices = generateFakeBluetoothDevicesSet(addresses);
        mShadowBluetoothAdapter.setBondedDevices(devices);

        // Force route controller to update bluetooth devices list.
        sendBluetoothDevicesChangedBroadcast();
        mAudioPoliciesBluetoothRouteController.selectRoute(DEVICE_ADDRESS_SAMPLE_4);

        mAudioPoliciesBluetoothRouteController.updateVolumeForDevices(
                AudioManager.DEVICE_OUT_BLUETOOTH_A2DP, 25);

        MediaRoute2Info selectedRoute = mAudioPoliciesBluetoothRouteController.getSelectedRoute();
        assertThat(selectedRoute.getVolume()).isEqualTo(25);
    }

    private void sendBluetoothDevicesChangedBroadcast() {
        Intent intent = new Intent(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        mContext.sendBroadcast(intent);
    }

    private static Set<String> extractAddressesListFrom(Collection<MediaRoute2Info> routes) {
        Set<String> addresses = new HashSet<>();

        for (MediaRoute2Info route: routes) {
            addresses.add(route.getAddress());
        }

        return addresses;
    }

    private static Set<BluetoothDevice> generateFakeBluetoothDevicesSet(String... addresses) {
        Set<BluetoothDevice> devices = new HashSet<>();

        for (String address: addresses) {
            devices.add(ShadowBluetoothDevice.newInstance(address));
        }

        return devices;
    }
}
