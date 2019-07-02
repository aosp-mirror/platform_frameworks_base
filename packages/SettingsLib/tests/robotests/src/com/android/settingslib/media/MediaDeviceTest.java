/*
 * Copyright 2018 The Android Open Source Project
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
package com.android.settingslib.media;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;

import androidx.mediarouter.media.MediaRouter;

import com.android.settingslib.bluetooth.A2dpProfile;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.HearingAidProfile;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class MediaDeviceTest {
    private static final Comparator<MediaDevice> COMPARATOR = Comparator.naturalOrder();
    private static final String DEVICE_ADDRESS_1 = "AA:BB:CC:DD:EE:11";
    private static final String DEVICE_ADDRESS_2 = "AA:BB:CC:DD:EE:22";
    private static final String DEVICE_ADDRESS_3 = "AA:BB:CC:DD:EE:33";
    private static final String DEVICE_NAME_1 = "TestName_1";
    private static final String DEVICE_NAME_2 = "TestName_2";
    private static final String DEVICE_NAME_3 = "TestName_3";
    private static final String ROUTER_ID_1 = "RouterId_1";
    private static final String ROUTER_ID_2 = "RouterId_2";
    private static final String ROUTER_ID_3 = "RouterId_3";
    private final BluetoothClass mHeadreeClass =
            new BluetoothClass(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES);
    private final BluetoothClass mCarkitClass =
            new BluetoothClass(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO);

    @Mock
    private BluetoothDevice mDevice1;
    @Mock
    private BluetoothDevice mDevice2;
    @Mock
    private BluetoothDevice mDevice3;
    @Mock
    private CachedBluetoothDevice mCachedDevice1;
    @Mock
    private CachedBluetoothDevice mCachedDevice2;
    @Mock
    private CachedBluetoothDevice mCachedDevice3;
    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private MediaRouter.RouteInfo mRouteInfo1;
    @Mock
    private MediaRouter.RouteInfo mRouteInfo2;
    @Mock
    private MediaRouter.RouteInfo mRouteInfo3;
    @Mock
    private LocalBluetoothProfileManager mProfileManager;
    @Mock
    private HearingAidProfile mHapProfile;
    @Mock
    private A2dpProfile mA2dpProfile;
    @Mock
    private BluetoothDevice mDevice;

    private BluetoothMediaDevice mBluetoothMediaDevice1;
    private BluetoothMediaDevice mBluetoothMediaDevice2;
    private BluetoothMediaDevice mBluetoothMediaDevice3;
    private Context mContext;
    private InfoMediaDevice mInfoMediaDevice1;
    private InfoMediaDevice mInfoMediaDevice2;
    private InfoMediaDevice mInfoMediaDevice3;
    private List<MediaDevice> mMediaDevices = new ArrayList<>();
    private PhoneMediaDevice mPhoneMediaDevice;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;

        when(mCachedDevice1.getAddress()).thenReturn(DEVICE_ADDRESS_1);
        when(mCachedDevice2.getAddress()).thenReturn(DEVICE_ADDRESS_2);
        when(mCachedDevice3.getAddress()).thenReturn(DEVICE_ADDRESS_3);
        when(mCachedDevice1.getName()).thenReturn(DEVICE_NAME_1);
        when(mCachedDevice2.getName()).thenReturn(DEVICE_NAME_2);
        when(mCachedDevice3.getName()).thenReturn(DEVICE_NAME_3);
        when(mCachedDevice1.getDevice()).thenReturn(mDevice1);
        when(mCachedDevice2.getDevice()).thenReturn(mDevice2);
        when(mCachedDevice3.getDevice()).thenReturn(mDevice3);
        when(mCachedDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mCachedDevice1.isConnected()).thenReturn(true);
        when(mCachedDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mCachedDevice2.isConnected()).thenReturn(true);
        when(mCachedDevice3.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mCachedDevice3.isConnected()).thenReturn(true);
        when(mRouteInfo1.getId()).thenReturn(ROUTER_ID_1);
        when(mRouteInfo2.getId()).thenReturn(ROUTER_ID_2);
        when(mRouteInfo3.getId()).thenReturn(ROUTER_ID_3);
        when(mRouteInfo1.getName()).thenReturn(DEVICE_NAME_1);
        when(mRouteInfo2.getName()).thenReturn(DEVICE_NAME_2);
        when(mRouteInfo3.getName()).thenReturn(DEVICE_NAME_3);
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mProfileManager);
        when(mProfileManager.getA2dpProfile()).thenReturn(mA2dpProfile);
        when(mProfileManager.getHearingAidProfile()).thenReturn(mHapProfile);
        when(mA2dpProfile.getActiveDevice()).thenReturn(mDevice);

        mBluetoothMediaDevice1 = new BluetoothMediaDevice(mContext, mCachedDevice1);
        mBluetoothMediaDevice2 = new BluetoothMediaDevice(mContext, mCachedDevice2);
        mBluetoothMediaDevice3 = new BluetoothMediaDevice(mContext, mCachedDevice3);
        mInfoMediaDevice1 = new InfoMediaDevice(mContext, mRouteInfo1);
        mInfoMediaDevice2 = new InfoMediaDevice(mContext, mRouteInfo2);
        mInfoMediaDevice3 = new InfoMediaDevice(mContext, mRouteInfo3);
        mPhoneMediaDevice = new PhoneMediaDevice(mContext, mLocalBluetoothManager);
    }

    @Test
    public void compareTo_carKit_nonCarKitBluetooth_carKitFirst() {
        when(mDevice1.getBluetoothClass()).thenReturn(mHeadreeClass);
        when(mDevice2.getBluetoothClass()).thenReturn(mCarkitClass);
        mMediaDevices.add(mBluetoothMediaDevice1);
        mMediaDevices.add(mBluetoothMediaDevice2);

        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice1);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice2);
    }

    @Test
    public void compareTo_carKit_info_carKitFirst() {
        when(mDevice1.getBluetoothClass()).thenReturn(mCarkitClass);
        mMediaDevices.add(mInfoMediaDevice1);
        mMediaDevices.add(mBluetoothMediaDevice1);

        assertThat(mMediaDevices.get(0)).isEqualTo(mInfoMediaDevice1);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice1);
    }

    @Test
    public void compareTo_carKit_phone_phoneFirst() {
        when(mDevice1.getBluetoothClass()).thenReturn(mCarkitClass);
        mMediaDevices.add(mBluetoothMediaDevice1);
        mMediaDevices.add(mPhoneMediaDevice);

        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice1);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mPhoneMediaDevice);
    }

    @Test
    public void compareTo_carKitIsDisConnected_nonCarKitBluetooth_nonCarKitBluetoothFirst() {
        when(mDevice1.getBluetoothClass()).thenReturn(mHeadreeClass);
        when(mDevice2.getBluetoothClass()).thenReturn(mCarkitClass);
        when(mCachedDevice2.isConnected()).thenReturn(false);
        mMediaDevices.add(mBluetoothMediaDevice1);
        mMediaDevices.add(mBluetoothMediaDevice2);

        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice1);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice1);
    }

    @Test
    public void compareTo_lastSelected_others_lastSelectedFirst() {
        mMediaDevices.add(mBluetoothMediaDevice1);
        mMediaDevices.add(mBluetoothMediaDevice2);
        mBluetoothMediaDevice2.connect();

        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice1);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice2);
    }

    @Test
    public void compareTo_connectionRecord_sortByRecord() {
        mMediaDevices.add(mBluetoothMediaDevice1);
        mMediaDevices.add(mBluetoothMediaDevice2);
        mBluetoothMediaDevice1.connect();
        mBluetoothMediaDevice2.connect();
        mBluetoothMediaDevice2.connect();
        // Reset last selected record
        ConnectionRecordManager.getInstance().setConnectionRecord(mContext, null, 0);

        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice1);
        assertThat(mMediaDevices.get(1)).isEqualTo(mBluetoothMediaDevice2);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice2);
        assertThat(mMediaDevices.get(1)).isEqualTo(mBluetoothMediaDevice1);
    }

    @Test
    public void compareTo_sortByRecord_connectedDeviceFirst() {
        mMediaDevices.add(mBluetoothMediaDevice1);
        mMediaDevices.add(mBluetoothMediaDevice2);
        when(mCachedDevice2.isConnected()).thenReturn(false);

        mBluetoothMediaDevice1.connect();
        mBluetoothMediaDevice2.connect();
        mBluetoothMediaDevice2.connect();
        // Reset last selected record
        ConnectionRecordManager.getInstance().setConnectionRecord(mContext, null, 0);

        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice1);
        assertThat(mMediaDevices.get(1)).isEqualTo(mBluetoothMediaDevice2);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice1);
        assertThat(mMediaDevices.get(1)).isEqualTo(mBluetoothMediaDevice2);
    }

    @Test
    public void compareTo_info_bluetooth_infoFirst() {
        mMediaDevices.add(mBluetoothMediaDevice1);
        mMediaDevices.add(mInfoMediaDevice1);

        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice1);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mInfoMediaDevice1);
    }

    @Test
    public void compareTo_bluetooth_phone_phoneFirst() {
        mMediaDevices.add(mBluetoothMediaDevice1);
        mMediaDevices.add(mPhoneMediaDevice);

        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice1);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mPhoneMediaDevice);
    }

    @Test
    public void compareTo_twoInfo_sortByAlphabet() {
        mMediaDevices.add(mInfoMediaDevice2);
        mMediaDevices.add(mInfoMediaDevice1);

        assertThat(mMediaDevices.get(0)).isEqualTo(mInfoMediaDevice2);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mInfoMediaDevice1);
    }

    @Test
    public void compareTo_twoBluetooth_sortByAlphabet() {
        mMediaDevices.add(mBluetoothMediaDevice2);
        mMediaDevices.add(mBluetoothMediaDevice1);

        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice2);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice1);
    }

    @Test
    public void compareTo_sortByAlphabet_connectDeviceFirst() {
        mMediaDevices.add(mBluetoothMediaDevice2);
        mMediaDevices.add(mBluetoothMediaDevice1);
        when(mCachedDevice1.isConnected()).thenReturn(false);

        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice2);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice2);
    }

    // 1.mInfoMediaDevice1:      Last Selected device
    // 2.mBluetoothMediaDevice1: CarKit device
    // 3.mInfoMediaDevice2:      * 2 times usage
    // 4.mInfoMediaDevice3:      * 1 time usage
    // 5.mBluetoothMediaDevice2: * 2 times usage
    // 6.mBluetoothMediaDevice3: * 1 time usage
    // 7.mPhoneMediaDevice:      * 0 time usage
    // Order: 7 -> 2 -> 1 -> 3 -> 5 -> 4 -> 6
    @Test
    public void compareTo_mixedDevices_carKitFirst() {
        when(mDevice1.getBluetoothClass()).thenReturn(mCarkitClass);
        when(mDevice2.getBluetoothClass()).thenReturn(mHeadreeClass);
        when(mDevice3.getBluetoothClass()).thenReturn(mHeadreeClass);
        mMediaDevices.add(mBluetoothMediaDevice1);
        mMediaDevices.add(mBluetoothMediaDevice2);
        mMediaDevices.add(mBluetoothMediaDevice3);
        mMediaDevices.add(mInfoMediaDevice1);
        mMediaDevices.add(mInfoMediaDevice2);
        mMediaDevices.add(mInfoMediaDevice3);
        mMediaDevices.add(mPhoneMediaDevice);
        mBluetoothMediaDevice3.connect();
        mBluetoothMediaDevice2.connect();
        mBluetoothMediaDevice2.connect();
        mInfoMediaDevice3.connect();
        mInfoMediaDevice2.connect();
        mInfoMediaDevice2.connect();
        mInfoMediaDevice1.connect();

        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mPhoneMediaDevice);
        assertThat(mMediaDevices.get(1)).isEqualTo(mBluetoothMediaDevice1);
        assertThat(mMediaDevices.get(2)).isEqualTo(mInfoMediaDevice1);
        assertThat(mMediaDevices.get(3)).isEqualTo(mInfoMediaDevice2);
        assertThat(mMediaDevices.get(4)).isEqualTo(mBluetoothMediaDevice2);
        assertThat(mMediaDevices.get(5)).isEqualTo(mInfoMediaDevice3);
        assertThat(mMediaDevices.get(6)).isEqualTo(mBluetoothMediaDevice3);
    }

    // 1.mInfoMediaDevice1:      Last Selected device
    // 2.mBluetoothMediaDevice1: CarKit device not connected
    // 3.mInfoMediaDevice2:      * 2 times usage
    // 4.mInfoMediaDevice3:      * 1 time usage
    // 5.mBluetoothMediaDevice2: * 4 times usage not connected
    // 6.mBluetoothMediaDevice3: * 1 time usage
    // 7.mPhoneMediaDevice:      * 0 time usage
    // Order: 7 -> 1 -> 3 -> 4 -> 6 -> 2 -> 5
    @Test
    public void compareTo_mixedDevices_connectDeviceFirst() {
        when(mDevice1.getBluetoothClass()).thenReturn(mCarkitClass);
        when(mDevice2.getBluetoothClass()).thenReturn(mHeadreeClass);
        when(mDevice3.getBluetoothClass()).thenReturn(mHeadreeClass);
        when(mCachedDevice1.isConnected()).thenReturn(false);
        when(mCachedDevice2.isConnected()).thenReturn(false);
        mMediaDevices.add(mBluetoothMediaDevice1);
        mMediaDevices.add(mBluetoothMediaDevice2);
        mMediaDevices.add(mBluetoothMediaDevice3);
        mMediaDevices.add(mInfoMediaDevice1);
        mMediaDevices.add(mInfoMediaDevice2);
        mMediaDevices.add(mInfoMediaDevice3);
        mMediaDevices.add(mPhoneMediaDevice);
        mBluetoothMediaDevice3.connect();
        mBluetoothMediaDevice2.connect();
        mBluetoothMediaDevice2.connect();
        mBluetoothMediaDevice2.connect();
        mBluetoothMediaDevice2.connect();
        mInfoMediaDevice3.connect();
        mInfoMediaDevice2.connect();
        mInfoMediaDevice2.connect();
        mInfoMediaDevice1.connect();

        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mPhoneMediaDevice);
        assertThat(mMediaDevices.get(1)).isEqualTo(mInfoMediaDevice1);
        assertThat(mMediaDevices.get(2)).isEqualTo(mInfoMediaDevice2);
        assertThat(mMediaDevices.get(3)).isEqualTo(mInfoMediaDevice3);
        assertThat(mMediaDevices.get(4)).isEqualTo(mBluetoothMediaDevice3);
        assertThat(mMediaDevices.get(5)).isEqualTo(mBluetoothMediaDevice1);
        assertThat(mMediaDevices.get(6)).isEqualTo(mBluetoothMediaDevice2);
    }

}
