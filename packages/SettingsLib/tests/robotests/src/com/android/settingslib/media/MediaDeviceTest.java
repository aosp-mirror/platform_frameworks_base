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

import static android.media.MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
import static android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADPHONES;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;
import android.media.NearbyDevice;
import android.os.Parcel;

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
    private static final String TEST_PACKAGE_NAME = "com.test.playmusic";
    private final BluetoothClass mHeadreeClass =
            createBtClass(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES);
    private final BluetoothClass mCarkitClass =
            createBtClass(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO);

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
    private MediaRoute2Info mRouteInfo1;
    @Mock
    private MediaRoute2Info mRouteInfo2;
    @Mock
    private MediaRoute2Info mRouteInfo3;
    @Mock
    private MediaRoute2Info mBluetoothRouteInfo1;
    @Mock
    private MediaRoute2Info mBluetoothRouteInfo2;
    @Mock
    private MediaRoute2Info mBluetoothRouteInfo3;
    @Mock
    private MediaRoute2Info mPhoneRouteInfo;
    @Mock
    private LocalBluetoothProfileManager mProfileManager;
    @Mock
    private HearingAidProfile mHapProfile;
    @Mock
    private A2dpProfile mA2dpProfile;
    @Mock
    private BluetoothDevice mDevice;
    @Mock
    private MediaRouter2Manager mMediaRouter2Manager;

    private BluetoothMediaDevice mBluetoothMediaDevice1;
    private BluetoothMediaDevice mBluetoothMediaDevice2;
    private BluetoothMediaDevice mBluetoothMediaDevice3;
    private Context mContext;
    private InfoMediaDevice mInfoMediaDevice1;
    private InfoMediaDevice mInfoMediaDevice2;
    private InfoMediaDevice mInfoMediaDevice3;
    private List<MediaDevice> mMediaDevices = new ArrayList<>();
    private PhoneMediaDevice mPhoneMediaDevice;

    private BluetoothClass createBtClass(int deviceClass) {
        Parcel p = Parcel.obtain();
        p.writeInt(deviceClass);
        p.setDataPosition(0); // reset position of parcel before passing to constructor

        BluetoothClass bluetoothClass = BluetoothClass.CREATOR.createFromParcel(p);
        p.recycle();
        return bluetoothClass;
    }

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
        when(mBluetoothRouteInfo1.getType()).thenReturn(TYPE_BLUETOOTH_A2DP);
        when(mBluetoothRouteInfo2.getType()).thenReturn(TYPE_BLUETOOTH_A2DP);
        when(mBluetoothRouteInfo3.getType()).thenReturn(TYPE_BLUETOOTH_A2DP);
        when(mRouteInfo1.getId()).thenReturn(ROUTER_ID_1);
        when(mRouteInfo2.getId()).thenReturn(ROUTER_ID_2);
        when(mRouteInfo3.getId()).thenReturn(ROUTER_ID_3);
        when(mRouteInfo1.getName()).thenReturn(DEVICE_NAME_1);
        when(mRouteInfo2.getName()).thenReturn(DEVICE_NAME_2);
        when(mRouteInfo3.getName()).thenReturn(DEVICE_NAME_3);
        when(mRouteInfo1.getType()).thenReturn(TYPE_REMOTE_SPEAKER);
        when(mRouteInfo2.getType()).thenReturn(TYPE_REMOTE_SPEAKER);
        when(mRouteInfo3.getType()).thenReturn(TYPE_REMOTE_SPEAKER);
        when(mPhoneRouteInfo.getType()).thenReturn(TYPE_BUILTIN_SPEAKER);
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mProfileManager);
        when(mProfileManager.getA2dpProfile()).thenReturn(mA2dpProfile);
        when(mProfileManager.getHearingAidProfile()).thenReturn(mHapProfile);
        when(mA2dpProfile.getActiveDevice()).thenReturn(mDevice);

        mBluetoothMediaDevice1 =
                new BluetoothMediaDevice(mContext, mCachedDevice1, mMediaRouter2Manager,
                        mBluetoothRouteInfo1, TEST_PACKAGE_NAME);
        mBluetoothMediaDevice2 =
                new BluetoothMediaDevice(mContext, mCachedDevice2, mMediaRouter2Manager,
                        mBluetoothRouteInfo2, TEST_PACKAGE_NAME);
        mBluetoothMediaDevice3 =
                new BluetoothMediaDevice(mContext, mCachedDevice3, mMediaRouter2Manager,
                        mBluetoothRouteInfo3, TEST_PACKAGE_NAME);
        mInfoMediaDevice1 = new InfoMediaDevice(mContext, mMediaRouter2Manager, mRouteInfo1,
                TEST_PACKAGE_NAME);
        mInfoMediaDevice2 = new InfoMediaDevice(mContext, mMediaRouter2Manager, mRouteInfo2,
                TEST_PACKAGE_NAME);
        mInfoMediaDevice3 = new InfoMediaDevice(mContext, mMediaRouter2Manager, mRouteInfo3,
                TEST_PACKAGE_NAME);
        mPhoneMediaDevice =
                new PhoneMediaDevice(mContext, mMediaRouter2Manager, mPhoneRouteInfo,
                        TEST_PACKAGE_NAME);
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
    public void compareTo_differentRange_sortWithRange() {
        mBluetoothMediaDevice1.setRangeZone(NearbyDevice.RANGE_FAR);
        mBluetoothMediaDevice2.setRangeZone(NearbyDevice.RANGE_CLOSE);
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
    public void compareTo_info_bluetooth_bluetoothFirst() {
        mMediaDevices.add(mInfoMediaDevice1);
        mMediaDevices.add(mBluetoothMediaDevice1);

        assertThat(mMediaDevices.get(0)).isEqualTo(mInfoMediaDevice1);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice1);
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
    public void compareTo_bluetooth_wiredHeadset_wiredHeadsetFirst() {
        final MediaRoute2Info phoneRouteInfo = mock(MediaRoute2Info.class);
        when(phoneRouteInfo.getType()).thenReturn(TYPE_WIRED_HEADPHONES);

        final PhoneMediaDevice phoneMediaDevice = new PhoneMediaDevice(mContext,
                mMediaRouter2Manager, phoneRouteInfo, TEST_PACKAGE_NAME);

        mMediaDevices.add(mBluetoothMediaDevice1);
        mMediaDevices.add(phoneMediaDevice);

        assertThat(mMediaDevices.get(0)).isEqualTo(mBluetoothMediaDevice1);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(phoneMediaDevice);
    }

    @Test
    public void compareTo_info_wiredHeadset_wiredHeadsetFirst() {
        final MediaRoute2Info phoneRouteInfo = mock(MediaRoute2Info.class);
        when(phoneRouteInfo.getType()).thenReturn(TYPE_WIRED_HEADPHONES);

        final PhoneMediaDevice phoneMediaDevice = new PhoneMediaDevice(mContext,
                mMediaRouter2Manager, phoneRouteInfo, TEST_PACKAGE_NAME);

        mMediaDevices.add(mInfoMediaDevice1);
        mMediaDevices.add(phoneMediaDevice);

        assertThat(mMediaDevices.get(0)).isEqualTo(mInfoMediaDevice1);
        Collections.sort(mMediaDevices, COMPARATOR);
        assertThat(mMediaDevices.get(0)).isEqualTo(phoneMediaDevice);
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
    // Order: 7 -> 2 -> 5 -> 6 -> 1 -> 3 -> 4
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
        assertThat(mMediaDevices.get(2)).isEqualTo(mBluetoothMediaDevice2);
        assertThat(mMediaDevices.get(3)).isEqualTo(mBluetoothMediaDevice3);
        assertThat(mMediaDevices.get(4)).isEqualTo(mInfoMediaDevice1);
        assertThat(mMediaDevices.get(5)).isEqualTo(mInfoMediaDevice2);
        assertThat(mMediaDevices.get(6)).isEqualTo(mInfoMediaDevice3);
    }

    // 1.mInfoMediaDevice1:      Last Selected device
    // 2.mBluetoothMediaDevice1: CarKit device not connected
    // 3.mInfoMediaDevice2:      * 2 times usage
    // 4.mInfoMediaDevice3:      * 1 time usage
    // 5.mBluetoothMediaDevice2: * 4 times usage not connected
    // 6.mBluetoothMediaDevice3: * 1 time usage
    // 7.mPhoneMediaDevice:      * 0 time usage
    // Order: 7 -> 6 -> 1 -> 3 -> 4 -> 2 -> 5
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
        assertThat(mMediaDevices.get(1)).isEqualTo(mBluetoothMediaDevice3);
        assertThat(mMediaDevices.get(2)).isEqualTo(mInfoMediaDevice1);
        assertThat(mMediaDevices.get(3)).isEqualTo(mInfoMediaDevice2);
        assertThat(mMediaDevices.get(4)).isEqualTo(mInfoMediaDevice3);
        assertThat(mMediaDevices.get(5)).isEqualTo(mBluetoothMediaDevice1);
        assertThat(mMediaDevices.get(6)).isEqualTo(mBluetoothMediaDevice2);
    }

    @Test
    public void connect_shouldSelectRoute() {
        mInfoMediaDevice1.connect();

        verify(mMediaRouter2Manager).selectRoute(TEST_PACKAGE_NAME, mRouteInfo1);
    }

    @Test
    public void getClientPackageName_returnPackageName() {
        when(mRouteInfo1.getClientPackageName()).thenReturn(TEST_PACKAGE_NAME);

        assertThat(mInfoMediaDevice1.getClientPackageName()).isEqualTo(TEST_PACKAGE_NAME);
    }

    @Test
    public void setState_verifyGetState() {
        mInfoMediaDevice1.setState(LocalMediaManager.MediaDeviceState.STATE_CONNECTED);
        assertThat(mInfoMediaDevice1.getState()).isEqualTo(
                LocalMediaManager.MediaDeviceState.STATE_CONNECTED);

        mInfoMediaDevice1.setState(LocalMediaManager.MediaDeviceState.STATE_CONNECTING);
        assertThat(mInfoMediaDevice1.getState()).isEqualTo(
                LocalMediaManager.MediaDeviceState.STATE_CONNECTING);

        mInfoMediaDevice1.setState(LocalMediaManager.MediaDeviceState.STATE_DISCONNECTED);
        assertThat(mInfoMediaDevice1.getState()).isEqualTo(
                LocalMediaManager.MediaDeviceState.STATE_DISCONNECTED);

        mInfoMediaDevice1.setState(LocalMediaManager.MediaDeviceState.STATE_CONNECTING_FAILED);
        assertThat(mInfoMediaDevice1.getState()).isEqualTo(
                LocalMediaManager.MediaDeviceState.STATE_CONNECTING_FAILED);
    }

    @Test
    public void getFeatures_noRouteInfo_returnEmptyList() {
        mBluetoothMediaDevice1 = new BluetoothMediaDevice(mContext, mCachedDevice1,
                mMediaRouter2Manager, null /* MediaRoute2Info */, TEST_PACKAGE_NAME);

        assertThat(mBluetoothMediaDevice1.getFeatures().size()).isEqualTo(0);
    }
}
