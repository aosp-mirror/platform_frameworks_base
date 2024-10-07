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

package com.android.settingslib.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Looper;
import android.os.Parcel;
import android.platform.test.flag.junit.SetFlagsRule;

import com.android.settingslib.flags.Flags;
import com.android.settingslib.testutils.shadow.ShadowBluetoothAdapter;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowBluetoothAdapter.class})
public class CsipDeviceManagerTest {
    private final static String DEVICE_NAME_1 = "TestName_1";
    private final static String DEVICE_NAME_2 = "TestName_2";
    private final static String DEVICE_NAME_3 = "TestName_3";
    private final static String DEVICE_ALIAS_1 = "TestAlias_1";
    private final static String DEVICE_ALIAS_2 = "TestAlias_2";
    private final static String DEVICE_ALIAS_3 = "TestAlias_3";
    private final static String DEVICE_ADDRESS_1 = "AA:BB:CC:DD:EE:11";
    private final static String DEVICE_ADDRESS_2 = "AA:BB:CC:DD:EE:22";
    private final static String DEVICE_ADDRESS_3 = "AA:BB:CC:DD:EE:33";
    private final static int GROUP1 = 1;
    private final BluetoothClass DEVICE_CLASS_1 =
            createBtClass(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES);
    private final BluetoothClass DEVICE_CLASS_2 =
            createBtClass(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE);

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private LocalBluetoothProfileManager mLocalProfileManager;
    @Mock
    private BluetoothEventManager mBluetoothEventManager;
    @Mock
    private BluetoothDevice mDevice1;
    @Mock
    private BluetoothDevice mDevice2;
    @Mock
    private BluetoothDevice mDevice3;
    @Mock
    private HeadsetProfile mHfpProfile;
    @Mock
    private A2dpProfile mA2dpProfile;
    @Mock
    private LeAudioProfile mLeAudioProfile;
    @Mock
    private LocalBluetoothLeBroadcast mBroadcast;
    @Mock
    private LocalBluetoothLeBroadcastAssistant mAssistant;

    private ShadowBluetoothAdapter mShadowBluetoothAdapter;
    private CachedBluetoothDevice mCachedDevice1;
    private CachedBluetoothDevice mCachedDevice2;
    private CachedBluetoothDevice mCachedDevice3;
    private CachedBluetoothDeviceManager mCachedDeviceManager;
    private CsipDeviceManager mCsipDeviceManager;
    private Context mContext;
    private List<CachedBluetoothDevice> mCachedDevices = new ArrayList<CachedBluetoothDevice>();

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
        mShadowBluetoothAdapter = Shadow.extract(BluetoothAdapter.getDefaultAdapter());
        mShadowBluetoothAdapter.setEnabled(true);
        mShadowBluetoothAdapter.setIsLeAudioBroadcastSourceSupported(
                BluetoothStatusCodes.FEATURE_SUPPORTED);
        mShadowBluetoothAdapter.setIsLeAudioBroadcastAssistantSupported(
                BluetoothStatusCodes.FEATURE_SUPPORTED);
        when(mDevice1.getAddress()).thenReturn(DEVICE_ADDRESS_1);
        when(mDevice2.getAddress()).thenReturn(DEVICE_ADDRESS_2);
        when(mDevice3.getAddress()).thenReturn(DEVICE_ADDRESS_3);
        when(mDevice1.getName()).thenReturn(DEVICE_NAME_1);
        when(mDevice2.getName()).thenReturn(DEVICE_NAME_2);
        when(mDevice3.getName()).thenReturn(DEVICE_NAME_3);
        when(mDevice1.getAlias()).thenReturn(DEVICE_ALIAS_1);
        when(mDevice2.getAlias()).thenReturn(DEVICE_ALIAS_2);
        when(mDevice3.getAlias()).thenReturn(DEVICE_ALIAS_3);
        when(mDevice1.getBluetoothClass()).thenReturn(DEVICE_CLASS_1);
        when(mDevice2.getBluetoothClass()).thenReturn(DEVICE_CLASS_2);
        when(mDevice3.getBluetoothClass()).thenReturn(DEVICE_CLASS_2);
        when(mDevice1.isConnected()).thenReturn(true);
        when(mDevice2.isConnected()).thenReturn(true);
        when(mDevice3.isConnected()).thenReturn(true);
        when(mDevice1.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice2.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mDevice3.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mLocalBluetoothManager.getEventManager()).thenReturn(mBluetoothEventManager);
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mLocalProfileManager);
        when(mLocalProfileManager.getLeAudioProfile()).thenReturn(mLeAudioProfile);
        when(mLocalProfileManager.getA2dpProfile()).thenReturn(mA2dpProfile);
        when(mLocalProfileManager.getHeadsetProfile()).thenReturn(mHfpProfile);
        when(mLocalProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(mAssistant);
        when(mLocalProfileManager.getLeAudioBroadcastProfile()).thenReturn(mBroadcast);

        when(mLeAudioProfile.getConnectedGroupLeadDevice(anyInt())).thenReturn(null);
        mCachedDeviceManager = new CachedBluetoothDeviceManager(mContext, mLocalBluetoothManager);
        when(mLocalBluetoothManager.getCachedDeviceManager()).thenReturn(mCachedDeviceManager);

        mCachedDevices = mCachedDeviceManager.mCachedDevices;
        mCsipDeviceManager = mCachedDeviceManager.mCsipDeviceManager;

        // Setup the default for testing
        mCachedDevice1 = spy(new CachedBluetoothDevice(mContext, mLocalProfileManager, mDevice1));
        mCachedDevice2 = spy(new CachedBluetoothDevice(mContext, mLocalProfileManager, mDevice2));
        mCachedDevice3 = spy(new CachedBluetoothDevice(mContext, mLocalProfileManager, mDevice3));

        mCachedDevice1.setGroupId(GROUP1);
        mCachedDevice2.setGroupId(GROUP1);
        mCachedDevice1.addMemberDevice(mCachedDevice2);
        mCachedDevices.add(mCachedDevice1);
        mCachedDevices.add(mCachedDevice3);

        List<LocalBluetoothProfile> profiles = new ArrayList<LocalBluetoothProfile>();
        profiles.add(mHfpProfile);
        profiles.add(mA2dpProfile);
        profiles.add(mLeAudioProfile);
        when(mCachedDevice1.getUiAccessibleProfiles()).thenReturn(profiles);
        when(mCachedDevice1.isConnected()).thenReturn(true);

        profiles.clear();
        profiles.add(mLeAudioProfile);
        when(mCachedDevice2.getUiAccessibleProfiles()).thenReturn(profiles);
        when(mCachedDevice2.isConnected()).thenReturn(true);

        profiles.clear();
        profiles.add(mHfpProfile);
        profiles.add(mA2dpProfile);
        when(mCachedDevice3.getUiAccessibleProfiles()).thenReturn(profiles);
        when(mCachedDevice3.isConnected()).thenReturn(true);
    }

    @Test
    public void onProfileConnectionStateChangedIfProcessed_profileIsConnecting_returnOff() {
        assertThat(mCsipDeviceManager.onProfileConnectionStateChangedIfProcessed(mCachedDevice1,
                BluetoothProfile.STATE_CONNECTING)).isFalse();
    }

    @Test
    public void onProfileConnectionStateChangedIfProcessed_profileIsDisconnecting_returnOff() {
        assertThat(mCsipDeviceManager.onProfileConnectionStateChangedIfProcessed(mCachedDevice1,
                BluetoothProfile.STATE_DISCONNECTING)).isFalse();
    }

    @Test
    public void updateRelationshipOfGroupDevices_invalidGroupId_returnOff() {
        assertThat(mCsipDeviceManager.updateRelationshipOfGroupDevices(
                BluetoothCsipSetCoordinator.GROUP_ID_INVALID)).isFalse();
    }

    @Test
    public void getGroupDevicesFromAllOfDevicesList_invalidGroupId_returnEmpty() {
        assertThat(mCsipDeviceManager.getGroupDevicesFromAllOfDevicesList(
                BluetoothCsipSetCoordinator.GROUP_ID_INVALID).isEmpty()).isTrue();
    }

    @Test
    public void getGroupDevicesFromAllOfDevicesList_validGroupId_returnGroupDevices() {
        List<CachedBluetoothDevice> expectedList = new ArrayList<>();
        expectedList.add(mCachedDevice1);
        expectedList.add(mCachedDevice2);

        assertThat(mCsipDeviceManager.getGroupDevicesFromAllOfDevicesList(GROUP1))
                .isEqualTo(expectedList);
    }

    @Test
    public void getPreferredMainDevice_dualModeDevice_returnDualModeDevice() {
        CachedBluetoothDevice expectedDevice = mCachedDevice1;

        assertThat(
                mCsipDeviceManager.getPreferredMainDevice(GROUP1,
                        mCsipDeviceManager.getGroupDevicesFromAllOfDevicesList(GROUP1)))
                .isEqualTo(expectedDevice);
    }

    @Test
    public void getPreferredMainDevice_noConnectedDualModeDevice_returnLeadDevice() {
        when(mCachedDevice1.isConnected()).thenReturn(false);
        when(mDevice1.isConnected()).thenReturn(false);
        when(mLeAudioProfile.getConnectedGroupLeadDevice(anyInt())).thenReturn(mDevice2);
        CachedBluetoothDevice expectedDevice = mCachedDevice2;

        assertThat(
                mCsipDeviceManager.getPreferredMainDevice(GROUP1,
                        mCsipDeviceManager.getGroupDevicesFromAllOfDevicesList(GROUP1)))
                .isEqualTo(expectedDevice);
    }

    @Test
    public void getPreferredMainDevice_noConnectedDualModeDeviceNoLeadDevice_returnConnectedOne() {
        when(mCachedDevice1.isConnected()).thenReturn(false);
        when(mCachedDevice2.isConnected()).thenReturn(true);
        when(mDevice1.isConnected()).thenReturn(false);
        when(mDevice2.isConnected()).thenReturn(true);
        CachedBluetoothDevice expectedDevice = mCachedDevice2;

        assertThat(
                mCsipDeviceManager.getPreferredMainDevice(GROUP1,
                        mCsipDeviceManager.getGroupDevicesFromAllOfDevicesList(GROUP1)))
                .isEqualTo(expectedDevice);
    }

    @Test
    public void getPreferredMainDevice_noConnectedDevice_returnDualModeDevice() {
        when(mCachedDevice1.isConnected()).thenReturn(false);
        when(mCachedDevice2.isConnected()).thenReturn(false);
        when(mDevice1.isConnected()).thenReturn(false);
        when(mDevice2.isConnected()).thenReturn(false);
        CachedBluetoothDevice expectedDevice = mCachedDevice1;

        assertThat(
                mCsipDeviceManager.getPreferredMainDevice(GROUP1,
                        mCsipDeviceManager.getGroupDevicesFromAllOfDevicesList(GROUP1)))
                .isEqualTo(expectedDevice);
    }

    @Test
    public void getPreferredMainDevice_noConnectedDeviceNoDualMode_returnFirstOneDevice() {
        when(mCachedDevice1.isConnected()).thenReturn(false);
        when(mCachedDevice2.isConnected()).thenReturn(false);
        when(mDevice1.isConnected()).thenReturn(false);
        when(mDevice2.isConnected()).thenReturn(false);
        List<LocalBluetoothProfile> profiles = new ArrayList<LocalBluetoothProfile>();
        profiles.add(mLeAudioProfile);
        when(mCachedDevice1.getUiAccessibleProfiles()).thenReturn(profiles);
        CachedBluetoothDevice expectedDevice = mCachedDevice1;

        assertThat(
                mCsipDeviceManager.getPreferredMainDevice(GROUP1,
                        mCsipDeviceManager.getGroupDevicesFromAllOfDevicesList(GROUP1)))
                .isEqualTo(expectedDevice);
    }

    @Test
    public void addMemberDevicesIntoMainDevice_noPreferredDevice_returnFalseAndNoChangeList() {
        CachedBluetoothDevice preferredDevice = null;
        List<CachedBluetoothDevice> expectedList = new ArrayList<>();
        for (CachedBluetoothDevice item : mCachedDevices) {
            expectedList.add(item);
        }

        assertThat(mCsipDeviceManager.addMemberDevicesIntoMainDevice(GROUP1, preferredDevice))
                .isFalse();
        for (CachedBluetoothDevice expectedItem : expectedList) {
            assertThat(mCachedDevices.contains(expectedItem)).isTrue();
        }
    }

    @Test
    public void
            addMemberDevicesIntoMainDevice_preferredDeviceIsMainAndNoOtherInList_noChangeList() {
        // Condition: The preferredDevice is main and no other main device in top list
        // Expected Result: return false and the list is no changed
        CachedBluetoothDevice preferredDevice = mCachedDevice1;
        List<CachedBluetoothDevice> expectedList = new ArrayList<>();
        for (CachedBluetoothDevice item : mCachedDevices) {
            expectedList.add(item);
        }

        assertThat(mCsipDeviceManager.addMemberDevicesIntoMainDevice(GROUP1, preferredDevice))
                .isFalse();
        for (CachedBluetoothDevice expectedItem : expectedList) {
            assertThat(mCachedDevices.contains(expectedItem)).isTrue();
        }
    }

    @Test
    public void addMemberDevicesIntoMainDevice_preferredDeviceIsMainAndTwoMain_returnTrue() {
        // Condition: The preferredDevice is main and there is another main device in top list
        // Expected Result: return true and there is the preferredDevice in top list
        CachedBluetoothDevice preferredDevice = mCachedDevice1;
        mCachedDevice1.getMemberDevice().clear();
        mCachedDevices.clear();
        mCachedDevices.add(preferredDevice);
        mCachedDevices.add(mCachedDevice2);
        mCachedDevices.add(mCachedDevice3);
        mSetFlagsRule.disableFlags(Flags.FLAG_ENABLE_LE_AUDIO_SHARING);

        assertThat(mCsipDeviceManager.addMemberDevicesIntoMainDevice(GROUP1, preferredDevice))
                .isTrue();
        assertThat(mCachedDevices.contains(preferredDevice)).isTrue();
        assertThat(mCachedDevices.contains(mCachedDevice2)).isFalse();
        assertThat(mCachedDevices.contains(mCachedDevice3)).isTrue();
        assertThat(preferredDevice.getMemberDevice()).contains(mCachedDevice2);
        verify(mAssistant, never()).addSource(any(BluetoothDevice.class),
                any(BluetoothLeBroadcastMetadata.class), anyBoolean());
    }

    @Test
    public void addMemberDevicesIntoMainDevice_preferredDeviceIsMainAndTwoMain_syncSource() {
        // Condition: The preferredDevice is main and there is another main device in top list
        // Expected Result: return true and there is the preferredDevice in top list
        CachedBluetoothDevice preferredDevice = mCachedDevice1;
        mCachedDevice1.getMemberDevice().clear();
        mCachedDevices.clear();
        mCachedDevices.add(preferredDevice);
        mCachedDevices.add(mCachedDevice2);
        mCachedDevices.add(mCachedDevice3);
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_LE_AUDIO_SHARING);
        when(mBroadcast.isEnabled(null)).thenReturn(true);
        BluetoothLeBroadcastMetadata metadata = Mockito.mock(BluetoothLeBroadcastMetadata.class);
        when(mBroadcast.getLatestBluetoothLeBroadcastMetadata()).thenReturn(metadata);
        BluetoothLeBroadcastReceiveState state = Mockito.mock(
                BluetoothLeBroadcastReceiveState.class);
        when(state.getBisSyncState()).thenReturn(ImmutableList.of(1L));
        when(mAssistant.getAllSources(mDevice2)).thenReturn(ImmutableList.of(state));

        assertThat(mCsipDeviceManager.addMemberDevicesIntoMainDevice(GROUP1, preferredDevice))
                .isTrue();
        assertThat(mCachedDevices.contains(preferredDevice)).isTrue();
        assertThat(mCachedDevices.contains(mCachedDevice2)).isFalse();
        assertThat(mCachedDevices.contains(mCachedDevice3)).isTrue();
        assertThat(preferredDevice.getMemberDevice()).contains(mCachedDevice2);
        verify(mAssistant).addSource(mDevice1, metadata, /* isGroupOp= */ false);
    }

    @Test
    public void addMemberDevicesIntoMainDevice_preferredDeviceIsMember_returnTrue() {
        // Condition: The preferredDevice is member
        // Expected Result: return true and there is the preferredDevice in top list
        CachedBluetoothDevice preferredDevice = mCachedDevice2;
        BluetoothDevice expectedMainBluetoothDevice = preferredDevice.getDevice();

        assertThat(mCsipDeviceManager.addMemberDevicesIntoMainDevice(GROUP1, preferredDevice))
                .isTrue();
        // expected main is mCachedDevice1 which is the main of preferredDevice, since system
        // switch the relationship between preferredDevice and the main of preferredDevice
        assertThat(mCachedDevices.contains(mCachedDevice1)).isTrue();
        assertThat(mCachedDevices.contains(mCachedDevice2)).isFalse();
        assertThat(mCachedDevices.contains(mCachedDevice3)).isTrue();
        assertThat(mCachedDevice1.getMemberDevice()).contains(mCachedDevice2);
        assertThat(mCachedDevice1.getDevice()).isEqualTo(expectedMainBluetoothDevice);
    }

    @Test
    public void addMemberDevicesIntoMainDevice_preferredDeviceIsMemberAndTwoMain_returnTrue() {
        // Condition: The preferredDevice is member and there are two main device in top list
        // Expected Result: return true and there is the preferredDevice in top list
        CachedBluetoothDevice preferredDevice = mCachedDevice2;
        BluetoothDevice expectedMainBluetoothDevice = preferredDevice.getDevice();
        mCachedDevice3.setGroupId(GROUP1);
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_LE_AUDIO_SHARING);
        when(mBroadcast.isEnabled(null)).thenReturn(false);

        assertThat(mCsipDeviceManager.addMemberDevicesIntoMainDevice(GROUP1, preferredDevice))
                .isTrue();
        // expected main is mCachedDevice1 which is the main of preferredDevice, since system
        // switch the relationship between preferredDevice and the main of preferredDevice
        assertThat(mCachedDevices.contains(mCachedDevice1)).isTrue();
        assertThat(mCachedDevices.contains(mCachedDevice2)).isFalse();
        assertThat(mCachedDevices.contains(mCachedDevice3)).isFalse();
        assertThat(mCachedDevice1.getMemberDevice()).contains(mCachedDevice2);
        assertThat(mCachedDevice1.getMemberDevice()).contains(mCachedDevice3);
        assertThat(mCachedDevice1.getDevice()).isEqualTo(expectedMainBluetoothDevice);
        verify(mAssistant, never()).addSource(any(BluetoothDevice.class),
                any(BluetoothLeBroadcastMetadata.class), anyBoolean());
    }

    @Test
    public void addMemberDevicesIntoMainDevice_preferredDeviceIsMemberAndTwoMain_syncSource() {
        // Condition: The preferredDevice is member and there are two main device in top list
        // Expected Result: return true and there is the preferredDevice in top list
        CachedBluetoothDevice preferredDevice = mCachedDevice2;
        BluetoothDevice expectedMainBluetoothDevice = preferredDevice.getDevice();
        mCachedDevice3.setGroupId(GROUP1);
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_LE_AUDIO_SHARING);
        when(mBroadcast.isEnabled(null)).thenReturn(true);
        BluetoothLeBroadcastMetadata metadata = Mockito.mock(BluetoothLeBroadcastMetadata.class);
        when(mBroadcast.getLatestBluetoothLeBroadcastMetadata()).thenReturn(metadata);
        BluetoothLeBroadcastReceiveState state = Mockito.mock(
                BluetoothLeBroadcastReceiveState.class);
        when(state.getBisSyncState()).thenReturn(ImmutableList.of(1L));
        when(mAssistant.getAllSources(mDevice1)).thenReturn(ImmutableList.of(state));

        assertThat(mCsipDeviceManager.addMemberDevicesIntoMainDevice(GROUP1, preferredDevice))
                .isTrue();
        shadowOf(Looper.getMainLooper()).idle();
        // expected main is mCachedDevice1 which is the main of preferredDevice, since system
        // switch the relationship between preferredDevice and the main of preferredDevice
        assertThat(mCachedDevices.contains(mCachedDevice1)).isTrue();
        assertThat(mCachedDevices.contains(mCachedDevice2)).isFalse();
        assertThat(mCachedDevices.contains(mCachedDevice3)).isFalse();
        assertThat(mCachedDevice1.getMemberDevice()).contains(mCachedDevice2);
        assertThat(mCachedDevice1.getMemberDevice()).contains(mCachedDevice3);
        assertThat(mCachedDevice1.getDevice()).isEqualTo(expectedMainBluetoothDevice);
        verify(mAssistant).addSource(mDevice2, metadata, /* isGroupOp= */ false);
        verify(mAssistant).addSource(mDevice3, metadata, /* isGroupOp= */ false);
    }

    @Test
    public void onProfileConnectionStateChangedIfProcessed_addMemberDevice_refreshUI() {
        mCachedDevice3.setGroupId(GROUP1);

        mCsipDeviceManager.onProfileConnectionStateChangedIfProcessed(mCachedDevice3,
                BluetoothProfile.STATE_CONNECTED);

        verify(mCachedDevice1).refresh();
    }

    @Test
    public void onProfileConnectionStateChangedIfProcessed_switchMainDevice_refreshUI() {
        when(mDevice3.isConnected()).thenReturn(true);
        when(mDevice2.isConnected()).thenReturn(false);
        when(mDevice1.isConnected()).thenReturn(false);
        mCachedDevice3.setGroupId(GROUP1);
        mCsipDeviceManager.onProfileConnectionStateChangedIfProcessed(mCachedDevice3,
                BluetoothProfile.STATE_CONNECTED);

        when(mDevice3.isConnected()).thenReturn(false);
        mCsipDeviceManager.onProfileConnectionStateChangedIfProcessed(mCachedDevice3,
                BluetoothProfile.STATE_DISCONNECTED);
        when(mDevice1.isConnected()).thenReturn(true);
        mCsipDeviceManager.onProfileConnectionStateChangedIfProcessed(mCachedDevice1,
                BluetoothProfile.STATE_CONNECTED);

        verify(mCachedDevice3).switchMemberDeviceContent(mCachedDevice1);
        verify(mCachedDevice3, atLeastOnce()).refresh();
    }
}
