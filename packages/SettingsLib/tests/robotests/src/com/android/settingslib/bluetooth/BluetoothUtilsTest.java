/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.settingslib.bluetooth.BluetoothUtils.isAvailableAudioSharingMediaBluetoothDevice;
import static com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast.UNKNOWN_VALUE_PLACEHOLDER;
import static com.android.settingslib.flags.Flags.FLAG_ENABLE_DETERMINING_ADVANCED_DETAILS_HEADER_WITH_METADATA;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.util.Pair;

import com.android.internal.R;
import com.android.settingslib.widget.AdaptiveIcon;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class BluetoothUtilsTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private CachedBluetoothDevice mCachedBluetoothDevice;

    @Mock private BluetoothDevice mBluetoothDevice;
    @Mock private AudioManager mAudioManager;
    @Mock private PackageManager mPackageManager;
    @Mock private LeAudioProfile mA2dpProfile;
    @Mock private LeAudioProfile mLeAudioProfile;
    @Mock private LeAudioProfile mHearingAid;
    @Mock private LocalBluetoothLeBroadcast mBroadcast;
    @Mock private LocalBluetoothProfileManager mProfileManager;
    @Mock private LocalBluetoothManager mLocalBluetoothManager;
    @Mock private LocalBluetoothLeBroadcastAssistant mAssistant;
    @Mock private CachedBluetoothDeviceManager mDeviceManager;
    @Mock private BluetoothLeBroadcastReceiveState mLeBroadcastReceiveState;

    private Context mContext;
    private static final String STRING_METADATA = "string_metadata";
    private static final String BOOL_METADATA = "true";
    private static final String INT_METADATA = "25";
    private static final int METADATA_FAST_PAIR_CUSTOMIZED_FIELDS = 25;
    private static final String KEY_HEARABLE_CONTROL_SLICE = "HEARABLE_CONTROL_SLICE_WITH_WIDTH";
    private static final String CONTROL_METADATA =
            "<HEARABLE_CONTROL_SLICE_WITH_WIDTH>"
                    + STRING_METADATA
                    + "</HEARABLE_CONTROL_SLICE_WITH_WIDTH>";
    private static final String TEST_EXCLUSIVE_MANAGER_PACKAGE = "com.test.manager";
    private static final String TEST_EXCLUSIVE_MANAGER_COMPONENT = "com.test.manager/.component";
    private static final int TEST_BROADCAST_ID = 25;

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = spy(RuntimeEnvironment.application);
        mSetFlagsRule.disableFlags(FLAG_ENABLE_DETERMINING_ADVANCED_DETAILS_HEADER_WITH_METADATA);
        when(mLocalBluetoothManager.getProfileManager()).thenReturn(mProfileManager);
        when(mLocalBluetoothManager.getCachedDeviceManager()).thenReturn(mDeviceManager);
        when(mProfileManager.getLeAudioBroadcastProfile()).thenReturn(mBroadcast);
        when(mProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(mAssistant);
        when(mProfileManager.getLeAudioProfile()).thenReturn(mLeAudioProfile);
        when(mA2dpProfile.getProfileId()).thenReturn(BluetoothProfile.A2DP);
        when(mLeAudioProfile.getProfileId()).thenReturn(BluetoothProfile.LE_AUDIO);
        when(mHearingAid.getProfileId()).thenReturn(BluetoothProfile.HEARING_AID);
    }

    @Test
    public void
            getDerivedBtClassDrawableWithDescription_isAdvancedUntetheredDevice_returnHeadset() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET))
                .thenReturn(BOOL_METADATA.getBytes());
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        Pair<Drawable, String> pair =
                BluetoothUtils.getDerivedBtClassDrawableWithDescription(
                        mContext, mCachedBluetoothDevice);

        verify(mContext).getDrawable(R.drawable.ic_bt_headphones_a2dp);
    }

    @Test
    public void
            getDerivedBtClassDrawableWithDescription_notAdvancedUntetheredDevice_returnPhone() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET))
                .thenReturn("false".getBytes());
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mCachedBluetoothDevice.getBtClass().getMajorDeviceClass())
                .thenReturn(BluetoothClass.Device.Major.PHONE);
        Pair<Drawable, String> pair =
                BluetoothUtils.getDerivedBtClassDrawableWithDescription(
                        mContext, mCachedBluetoothDevice);

        verify(mContext).getDrawable(R.drawable.ic_phone);
    }

    @Test
    public void getBtClassDrawableWithDescription_typePhone_returnPhoneDrawable() {
        when(mCachedBluetoothDevice.getBtClass().getMajorDeviceClass())
                .thenReturn(BluetoothClass.Device.Major.PHONE);
        final Pair<Drawable, String> pair =
                BluetoothUtils.getBtClassDrawableWithDescription(mContext, mCachedBluetoothDevice);

        verify(mContext).getDrawable(com.android.internal.R.drawable.ic_phone);
    }

    @Test
    public void getBtClassDrawableWithDescription_typeComputer_returnComputerDrawable() {
        when(mCachedBluetoothDevice.getBtClass().getMajorDeviceClass())
                .thenReturn(BluetoothClass.Device.Major.COMPUTER);
        final Pair<Drawable, String> pair =
                BluetoothUtils.getBtClassDrawableWithDescription(mContext, mCachedBluetoothDevice);

        verify(mContext).getDrawable(com.android.internal.R.drawable.ic_bt_laptop);
    }

    @Test
    public void getBtClassDrawableWithDescription_typeHearingAid_returnHearingAidDrawable() {
        when(mCachedBluetoothDevice.isHearingAidDevice()).thenReturn(true);
        BluetoothUtils.getBtClassDrawableWithDescription(mContext, mCachedBluetoothDevice);

        verify(mContext).getDrawable(com.android.internal.R.drawable.ic_bt_hearing_aid);
    }

    @Test
    public void getBtRainbowDrawableWithDescription_normalHeadset_returnAdaptiveIcon() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET))
                .thenReturn("false".getBytes());
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mCachedBluetoothDevice.getAddress()).thenReturn("1f:aa:bb");

        assertThat(
                        BluetoothUtils.getBtRainbowDrawableWithDescription(
                                        RuntimeEnvironment.application, mCachedBluetoothDevice)
                                .first)
                .isInstanceOf(AdaptiveIcon.class);
    }

    @Test
    public void getStringMetaData_hasMetaData_getCorrectMetaData() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_LEFT_ICON))
                .thenReturn(STRING_METADATA.getBytes());

        assertThat(
                        BluetoothUtils.getStringMetaData(
                                mBluetoothDevice, BluetoothDevice.METADATA_UNTETHERED_LEFT_ICON))
                .isEqualTo(STRING_METADATA);
    }

    @Test
    public void getIntMetaData_hasMetaData_getCorrectMetaData() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY))
                .thenReturn(INT_METADATA.getBytes());

        assertThat(
                        BluetoothUtils.getIntMetaData(
                                mBluetoothDevice, BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY))
                .isEqualTo(Integer.parseInt(INT_METADATA));
    }

    @Test
    public void getIntMetaData_invalidMetaData_getErrorCode() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY))
                .thenReturn(null);

        assertThat(
                        BluetoothUtils.getIntMetaData(
                                mBluetoothDevice, BluetoothDevice.METADATA_UNTETHERED_LEFT_ICON))
                .isEqualTo(BluetoothUtils.META_INT_ERROR);
    }

    @Test
    public void getBooleanMetaData_hasMetaData_getCorrectMetaData() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET))
                .thenReturn(BOOL_METADATA.getBytes());

        assertThat(
                        BluetoothUtils.getBooleanMetaData(
                                mBluetoothDevice, BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET))
                .isTrue();
    }

    @Test
    public void getUriMetaData_hasMetaData_getCorrectMetaData() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_MAIN_ICON))
                .thenReturn(STRING_METADATA.getBytes());

        assertThat(
                        BluetoothUtils.getUriMetaData(
                                mBluetoothDevice, BluetoothDevice.METADATA_MAIN_ICON))
                .isEqualTo(Uri.parse(STRING_METADATA));
    }

    @Test
    public void getUriMetaData_nullMetaData_getNullUri() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_MAIN_ICON)).thenReturn(null);

        assertThat(
                        BluetoothUtils.getUriMetaData(
                                mBluetoothDevice, BluetoothDevice.METADATA_MAIN_ICON))
                .isNull();
    }

    @Test
    public void getControlUriMetaData_hasMetaData_returnsCorrectMetaData() {
        when(mBluetoothDevice.getMetadata(METADATA_FAST_PAIR_CUSTOMIZED_FIELDS))
                .thenReturn(CONTROL_METADATA.getBytes());

        assertThat(BluetoothUtils.getControlUriMetaData(mBluetoothDevice))
                .isEqualTo(STRING_METADATA);
    }

    @Test
    public void getFastPairCustomizedField_hasMetaData_returnsCorrectMetaData() {
        when(mBluetoothDevice.getMetadata(METADATA_FAST_PAIR_CUSTOMIZED_FIELDS))
                .thenReturn(CONTROL_METADATA.getBytes());

        assertThat(
                        BluetoothUtils.getFastPairCustomizedField(
                                mBluetoothDevice, KEY_HEARABLE_CONTROL_SLICE))
                .isEqualTo(STRING_METADATA);
    }

    @Test
    public void isAdvancedDetailsHeader_untetheredHeadset_returnTrue() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET))
                .thenReturn(BOOL_METADATA.getBytes());

        assertThat(BluetoothUtils.isAdvancedDetailsHeader(mBluetoothDevice)).isTrue();
    }

    @Test
    public void isAdvancedDetailsHeader_deviceTypeUntetheredHeadset_returnTrue() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_DEVICE_TYPE))
                .thenReturn(BluetoothDevice.DEVICE_TYPE_UNTETHERED_HEADSET.getBytes());

        assertThat(BluetoothUtils.isAdvancedDetailsHeader(mBluetoothDevice)).isTrue();
    }

    @Test
    public void isAdvancedDetailsHeader_deviceTypeWatch_returnTrue() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_DEVICE_TYPE))
                .thenReturn(BluetoothDevice.DEVICE_TYPE_WATCH.getBytes());

        assertThat(BluetoothUtils.isAdvancedDetailsHeader(mBluetoothDevice)).isTrue();
    }

    @Test
    public void isAdvancedDetailsHeader_deviceTypeStylus_returnTrue() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_DEVICE_TYPE))
                .thenReturn(BluetoothDevice.DEVICE_TYPE_STYLUS.getBytes());

        assertThat(BluetoothUtils.isAdvancedDetailsHeader(mBluetoothDevice)).isTrue();
    }

    @Test
    public void isAdvancedDetailsHeader_deviceTypeDefault_returnTrue() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_DEVICE_TYPE))
                .thenReturn(BluetoothDevice.DEVICE_TYPE_DEFAULT.getBytes());

        assertThat(BluetoothUtils.isAdvancedDetailsHeader(mBluetoothDevice)).isTrue();
    }

    @Test
    public void isAdvancedDetailsHeader_noMetadata_returnFalse() {
        assertThat(BluetoothUtils.isAdvancedDetailsHeader(mBluetoothDevice)).isFalse();
    }

    @Test
    public void isAdvancedDetailsHeader_noMainIcon_returnFalse() {
        mSetFlagsRule.enableFlags(FLAG_ENABLE_DETERMINING_ADVANCED_DETAILS_HEADER_WITH_METADATA);

        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_MAIN_ICON)).thenReturn(null);

        assertThat(BluetoothUtils.isAdvancedDetailsHeader(mBluetoothDevice)).isFalse();
    }

    @Test
    public void isAdvancedDetailsHeader_hasMainIcon_returnTrue() {
        mSetFlagsRule.enableFlags(FLAG_ENABLE_DETERMINING_ADVANCED_DETAILS_HEADER_WITH_METADATA);

        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_MAIN_ICON))
                .thenReturn(STRING_METADATA.getBytes());

        assertThat(BluetoothUtils.isAdvancedDetailsHeader(mBluetoothDevice)).isTrue();
    }

    @Test
    public void isAdvancedUntetheredDevice_untetheredHeadset_returnTrue() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET))
                .thenReturn(BOOL_METADATA.getBytes());

        assertThat(BluetoothUtils.isAdvancedUntetheredDevice(mBluetoothDevice)).isTrue();
    }

    @Test
    public void isAdvancedUntetheredDevice_deviceTypeUntetheredHeadset_returnTrue() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_DEVICE_TYPE))
                .thenReturn(BluetoothDevice.DEVICE_TYPE_UNTETHERED_HEADSET.getBytes());

        assertThat(BluetoothUtils.isAdvancedUntetheredDevice(mBluetoothDevice)).isTrue();
    }

    @Test
    public void isAdvancedUntetheredDevice_deviceTypeWatch_returnFalse() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_DEVICE_TYPE))
                .thenReturn(BluetoothDevice.DEVICE_TYPE_WATCH.getBytes());

        assertThat(BluetoothUtils.isAdvancedUntetheredDevice(mBluetoothDevice)).isFalse();
    }

    @Test
    public void isAdvancedUntetheredDevice_deviceTypeDefault_returnFalse() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_DEVICE_TYPE))
                .thenReturn(BluetoothDevice.DEVICE_TYPE_DEFAULT.getBytes());

        assertThat(BluetoothUtils.isAdvancedUntetheredDevice(mBluetoothDevice)).isFalse();
    }

    @Test
    public void isAdvancedUntetheredDevice_noMetadata_returnFalse() {
        assertThat(BluetoothUtils.isAdvancedUntetheredDevice(mBluetoothDevice)).isFalse();
    }

    @Test
    public void isAdvancedUntetheredDevice_untetheredHeadsetMetadataIsFalse_returnFalse() {
        mSetFlagsRule.enableFlags(FLAG_ENABLE_DETERMINING_ADVANCED_DETAILS_HEADER_WITH_METADATA);

        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET))
                .thenReturn("false".getBytes());
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_DEVICE_TYPE))
                .thenReturn(BluetoothDevice.DEVICE_TYPE_UNTETHERED_HEADSET.getBytes());

        assertThat(BluetoothUtils.isAdvancedUntetheredDevice(mBluetoothDevice)).isFalse();
    }

    @Test
    public void isAvailableMediaBluetoothDevice_isConnectedLeAudioDevice_returnTrue() {
        when(mCachedBluetoothDevice.isConnectedLeAudioDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(
                        BluetoothUtils.isAvailableMediaBluetoothDevice(
                                mCachedBluetoothDevice, mAudioManager))
                .isTrue();
    }

    @Test
    public void isAvailableMediaBluetoothDevice_isHeadset_isConnectedA2dpDevice_returnFalse() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_RINGTONE);
        when(mCachedBluetoothDevice.isConnectedA2dpDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(
                        BluetoothUtils.isAvailableMediaBluetoothDevice(
                                mCachedBluetoothDevice, mAudioManager))
                .isFalse();
    }

    @Test
    public void isAvailableMediaBluetoothDevice_isA2dp_isConnectedA2dpDevice_returnTrue() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        when(mCachedBluetoothDevice.isConnectedA2dpDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(
                        BluetoothUtils.isAvailableMediaBluetoothDevice(
                                mCachedBluetoothDevice, mAudioManager))
                .isTrue();
    }

    @Test
    public void isAvailableMediaBluetoothDevice_isHeadset_isConnectedHfpDevice_returnTrue() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_RINGTONE);
        when(mCachedBluetoothDevice.isConnectedHfpDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(
                        BluetoothUtils.isAvailableMediaBluetoothDevice(
                                mCachedBluetoothDevice, mAudioManager))
                .isTrue();
    }

    @Test
    public void isConnectedBluetoothDevice_isConnectedLeAudioDevice_returnFalse() {
        when(mCachedBluetoothDevice.isConnectedLeAudioDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(BluetoothUtils.isConnectedBluetoothDevice(mCachedBluetoothDevice, mAudioManager))
                .isFalse();
    }

    @Test
    public void isConnectedBluetoothDevice_isHeadset_isConnectedA2dpDevice_returnTrue() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_RINGTONE);
        when(mCachedBluetoothDevice.isConnectedA2dpDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(BluetoothUtils.isConnectedBluetoothDevice(mCachedBluetoothDevice, mAudioManager))
                .isTrue();
    }

    @Test
    public void isConnectedBluetoothDevice_isA2dp_isConnectedA2dpDevice_returnFalse() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        when(mCachedBluetoothDevice.isConnectedA2dpDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(BluetoothUtils.isConnectedBluetoothDevice(mCachedBluetoothDevice, mAudioManager))
                .isFalse();
    }

    @Test
    public void isConnectedBluetoothDevice_isHeadset_isConnectedHfpDevice_returnFalse() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_RINGTONE);
        when(mCachedBluetoothDevice.isConnectedHfpDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(BluetoothUtils.isConnectedBluetoothDevice(mCachedBluetoothDevice, mAudioManager))
                .isFalse();
    }

    @Test
    public void isConnectedBluetoothDevice_isNotConnected_returnFalse() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_RINGTONE);
        when(mCachedBluetoothDevice.isConnectedA2dpDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(false);

        assertThat(BluetoothUtils.isConnectedBluetoothDevice(mCachedBluetoothDevice, mAudioManager))
                .isFalse();
    }

    @Test
    public void isExclusivelyManaged_hasNoManager_returnFalse() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER))
                .thenReturn(null);

        assertThat(BluetoothUtils.isExclusivelyManagedBluetoothDevice(mContext, mBluetoothDevice))
                .isFalse();
    }

    @Test
    public void isExclusivelyManaged_hasPackageName_packageNotInstalled_returnFalse()
            throws Exception {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER))
                .thenReturn(TEST_EXCLUSIVE_MANAGER_PACKAGE.getBytes());
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        doThrow(new PackageManager.NameNotFoundException())
                .when(mPackageManager)
                .getApplicationInfo(TEST_EXCLUSIVE_MANAGER_PACKAGE, 0);

        assertThat(BluetoothUtils.isExclusivelyManagedBluetoothDevice(mContext, mBluetoothDevice))
                .isFalse();
    }

    @Test
    public void isExclusivelyManaged_hasComponentName_packageNotInstalled_returnFalse()
            throws Exception {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER))
                .thenReturn(TEST_EXCLUSIVE_MANAGER_COMPONENT.getBytes());
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        doThrow(new PackageManager.NameNotFoundException())
                .when(mPackageManager)
                .getApplicationInfo(TEST_EXCLUSIVE_MANAGER_PACKAGE, 0);

        assertThat(BluetoothUtils.isExclusivelyManagedBluetoothDevice(mContext, mBluetoothDevice))
                .isFalse();
    }

    @Test
    public void isExclusivelyManaged_hasPackageName_packageNotEnabled_returnFalse()
            throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.enabled = false;
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        doReturn(appInfo)
                .when(mPackageManager)
                .getApplicationInfo(TEST_EXCLUSIVE_MANAGER_PACKAGE, 0);
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER))
                .thenReturn(TEST_EXCLUSIVE_MANAGER_PACKAGE.getBytes());

        assertThat(BluetoothUtils.isExclusivelyManagedBluetoothDevice(mContext, mBluetoothDevice))
                .isFalse();
    }

    @Test
    public void isExclusivelyManaged_hasComponentName_packageNotEnabled_returnFalse()
            throws Exception {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.enabled = false;
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        doReturn(appInfo)
                .when(mPackageManager)
                .getApplicationInfo(TEST_EXCLUSIVE_MANAGER_PACKAGE, 0);
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER))
                .thenReturn(TEST_EXCLUSIVE_MANAGER_COMPONENT.getBytes());

        assertThat(BluetoothUtils.isExclusivelyManagedBluetoothDevice(mContext, mBluetoothDevice))
                .isFalse();
    }

    @Test
    public void isExclusivelyManaged_hasPackageName_packageInstalledAndEnabled_returnTrue()
            throws Exception {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER))
                .thenReturn(TEST_EXCLUSIVE_MANAGER_PACKAGE.getBytes());
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        doReturn(new ApplicationInfo())
                .when(mPackageManager)
                .getApplicationInfo(TEST_EXCLUSIVE_MANAGER_PACKAGE, 0);

        assertThat(BluetoothUtils.isExclusivelyManagedBluetoothDevice(mContext, mBluetoothDevice))
                .isTrue();
    }

    @Test
    public void isExclusivelyManaged_hasComponentName_packageInstalledAndEnabled_returnTrue()
            throws Exception {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER))
                .thenReturn(TEST_EXCLUSIVE_MANAGER_COMPONENT.getBytes());
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        doReturn(new ApplicationInfo())
                .when(mPackageManager)
                .getApplicationInfo(TEST_EXCLUSIVE_MANAGER_PACKAGE, 0);

        assertThat(BluetoothUtils.isExclusivelyManagedBluetoothDevice(mContext, mBluetoothDevice))
                .isTrue();
    }

    @Test
    public void testIsBroadcasting_broadcastEnabled_returnTrue() {
        when(mBroadcast.isEnabled(any())).thenReturn(true);
        assertThat(BluetoothUtils.isBroadcasting(mLocalBluetoothManager)).isTrue();
    }

    @Test
    public void testHasConnectedBroadcastSource_leadDeviceConnectedToBroadcastSource() {
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        CachedBluetoothDevice memberCachedDevice = mock(CachedBluetoothDevice.class);
        BluetoothDevice memberDevice = mock(BluetoothDevice.class);
        when(memberCachedDevice.getDevice()).thenReturn(memberDevice);
        Set<CachedBluetoothDevice> memberCachedDevices = new HashSet<>();
        memberCachedDevices.add(memberCachedDevice);
        when(mCachedBluetoothDevice.getMemberDevice()).thenReturn(memberCachedDevices);

        List<Long> bisSyncState = new ArrayList<>();
        bisSyncState.add(1L);
        when(mLeBroadcastReceiveState.getBisSyncState()).thenReturn(bisSyncState);

        List<BluetoothLeBroadcastReceiveState> sourceList = new ArrayList<>();
        sourceList.add(mLeBroadcastReceiveState);
        when(mAssistant.getAllSources(mBluetoothDevice)).thenReturn(sourceList);
        when(mAssistant.getAllSources(memberDevice)).thenReturn(Collections.emptyList());

        assertThat(
                        BluetoothUtils.hasConnectedBroadcastSource(
                                mCachedBluetoothDevice, mLocalBluetoothManager))
                .isTrue();
    }

    @Test
    public void testHasConnectedBroadcastSource_memberDeviceConnectedToBroadcastSource() {
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        CachedBluetoothDevice memberCachedDevice = mock(CachedBluetoothDevice.class);
        BluetoothDevice memberDevice = mock(BluetoothDevice.class);
        when(memberCachedDevice.getDevice()).thenReturn(memberDevice);
        Set<CachedBluetoothDevice> memberCachedDevices = new HashSet<>();
        memberCachedDevices.add(memberCachedDevice);
        when(mCachedBluetoothDevice.getMemberDevice()).thenReturn(memberCachedDevices);

        List<Long> bisSyncState = new ArrayList<>();
        bisSyncState.add(1L);
        when(mLeBroadcastReceiveState.getBisSyncState()).thenReturn(bisSyncState);

        List<BluetoothLeBroadcastReceiveState> sourceList = new ArrayList<>();
        sourceList.add(mLeBroadcastReceiveState);
        when(mAssistant.getAllSources(memberDevice)).thenReturn(sourceList);
        when(mAssistant.getAllSources(mBluetoothDevice)).thenReturn(Collections.emptyList());

        assertThat(
                        BluetoothUtils.hasConnectedBroadcastSource(
                                mCachedBluetoothDevice, mLocalBluetoothManager))
                .isTrue();
    }

    @Test
    public void testHasConnectedBroadcastSource_deviceNotConnectedToBroadcastSource() {
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);

        List<Long> bisSyncState = new ArrayList<>();
        when(mLeBroadcastReceiveState.getBisSyncState()).thenReturn(bisSyncState);

        List<BluetoothLeBroadcastReceiveState> sourceList = new ArrayList<>();
        sourceList.add(mLeBroadcastReceiveState);
        when(mAssistant.getAllSources(mBluetoothDevice)).thenReturn(sourceList);

        assertThat(
                        BluetoothUtils.hasConnectedBroadcastSource(
                                mCachedBluetoothDevice, mLocalBluetoothManager))
                .isFalse();
    }

    @Test
    public void testHasConnectedBroadcastSourceForBtDevice_deviceConnectedToBroadcastSource() {
        List<Long> bisSyncState = new ArrayList<>();
        bisSyncState.add(1L);
        when(mLeBroadcastReceiveState.getBisSyncState()).thenReturn(bisSyncState);

        List<BluetoothLeBroadcastReceiveState> sourceList = new ArrayList<>();
        sourceList.add(mLeBroadcastReceiveState);
        when(mAssistant.getAllSources(mBluetoothDevice)).thenReturn(sourceList);

        assertThat(
                        BluetoothUtils.hasConnectedBroadcastSourceForBtDevice(
                                mBluetoothDevice, mLocalBluetoothManager))
                .isTrue();
    }

    @Test
    public void testHasConnectedBroadcastSourceForBtDevice_deviceNotConnectedToBroadcastSource() {
        List<Long> bisSyncState = new ArrayList<>();
        when(mLeBroadcastReceiveState.getBisSyncState()).thenReturn(bisSyncState);

        List<BluetoothLeBroadcastReceiveState> sourceList = new ArrayList<>();
        sourceList.add(mLeBroadcastReceiveState);
        when(mAssistant.getAllSources(mBluetoothDevice)).thenReturn(sourceList);

        assertThat(
                        BluetoothUtils.hasConnectedBroadcastSourceForBtDevice(
                                mBluetoothDevice, mLocalBluetoothManager))
                .isFalse();
    }

    @Test
    public void testHasActiveLocalBroadcastSourceForBtDevice_hasActiveLocalSource() {
        when(mBroadcast.getLatestBroadcastId()).thenReturn(TEST_BROADCAST_ID);
        when(mLeBroadcastReceiveState.getBroadcastId()).thenReturn(TEST_BROADCAST_ID);
        List<BluetoothLeBroadcastReceiveState> sourceList = new ArrayList<>();
        sourceList.add(mLeBroadcastReceiveState);
        when(mAssistant.getAllSources(mBluetoothDevice)).thenReturn(sourceList);

        assertThat(
                        BluetoothUtils.hasActiveLocalBroadcastSourceForBtDevice(
                                mBluetoothDevice, mLocalBluetoothManager))
                .isTrue();
    }

    @Test
    public void testHasActiveLocalBroadcastSourceForBtDevice_noActiveLocalSource() {
        when(mLeBroadcastReceiveState.getBroadcastId()).thenReturn(UNKNOWN_VALUE_PLACEHOLDER);
        List<BluetoothLeBroadcastReceiveState> sourceList = new ArrayList<>();
        sourceList.add(mLeBroadcastReceiveState);
        when(mAssistant.getAllSources(mBluetoothDevice)).thenReturn(sourceList);

        assertThat(
                        BluetoothUtils.hasActiveLocalBroadcastSourceForBtDevice(
                                mBluetoothDevice, mLocalBluetoothManager))
                .isFalse();
    }

    @Test
    public void isAvailableHearingDevice_isConnectedHearingAid_returnTure() {
        when(mCachedBluetoothDevice.isConnectedHearingAidDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(BluetoothUtils.isAvailableHearingDevice(mCachedBluetoothDevice)).isTrue();
    }

    @Test
    public void getGroupId_getCsipProfileId() {
        when(mCachedBluetoothDevice.getGroupId()).thenReturn(1);

        assertThat(BluetoothUtils.getGroupId(mCachedBluetoothDevice)).isEqualTo(1);
    }

    @Test
    public void getGroupId_getLeAudioProfileId() {
        when(mCachedBluetoothDevice.getGroupId())
                .thenReturn(BluetoothCsipSetCoordinator.GROUP_ID_INVALID);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        LeAudioProfile leAudio = mock(LeAudioProfile.class);
        when(leAudio.getGroupId(mBluetoothDevice)).thenReturn(1);
        when(mCachedBluetoothDevice.getProfiles()).thenReturn(ImmutableList.of(leAudio));

        assertThat(BluetoothUtils.getGroupId(mCachedBluetoothDevice)).isEqualTo(1);
    }

    @Test
    public void getSecondaryDeviceForBroadcast_noBroadcast_returnNull() {
        assertThat(
                        BluetoothUtils.getSecondaryDeviceForBroadcast(
                                mContext.getContentResolver(), mLocalBluetoothManager))
                .isNull();
    }

    @Test
    public void getSecondaryDeviceForBroadcast_nullProfile_returnNull() {
        when(mProfileManager.getLeAudioBroadcastProfile()).thenReturn(null);
        assertThat(
                        BluetoothUtils.getSecondaryDeviceForBroadcast(
                                mContext.getContentResolver(), mLocalBluetoothManager))
                .isNull();
    }

    @Test
    public void getSecondaryDeviceForBroadcast_noSecondary_returnNull() {
        Settings.Secure.putInt(
                mContext.getContentResolver(),
                BluetoothUtils.getPrimaryGroupIdUriForBroadcast(),
                1);
        when(mBroadcast.isEnabled(any())).thenReturn(true);
        CachedBluetoothDeviceManager deviceManager = mock(CachedBluetoothDeviceManager.class);
        when(mLocalBluetoothManager.getCachedDeviceManager()).thenReturn(deviceManager);
        when(deviceManager.findDevice(mBluetoothDevice)).thenReturn(mCachedBluetoothDevice);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mCachedBluetoothDevice.getGroupId()).thenReturn(1);
        BluetoothLeBroadcastReceiveState state = mock(BluetoothLeBroadcastReceiveState.class);
        when(mAssistant.getAllSources(mBluetoothDevice)).thenReturn(ImmutableList.of(state));
        when(mAssistant.getAllConnectedDevices()).thenReturn(ImmutableList.of(mBluetoothDevice));

        assertThat(
                        BluetoothUtils.getSecondaryDeviceForBroadcast(
                                mContext.getContentResolver(), mLocalBluetoothManager))
                .isNull();
    }

    @Test
    public void getSecondaryDeviceForBroadcast_returnCorrectDevice() {
        Settings.Secure.putInt(
                mContext.getContentResolver(),
                BluetoothUtils.getPrimaryGroupIdUriForBroadcast(),
                1);
        when(mBroadcast.isEnabled(any())).thenReturn(true);
        CachedBluetoothDeviceManager deviceManager = mock(CachedBluetoothDeviceManager.class);
        when(mLocalBluetoothManager.getCachedDeviceManager()).thenReturn(deviceManager);
        CachedBluetoothDevice cachedBluetoothDevice = mock(CachedBluetoothDevice.class);
        BluetoothDevice bluetoothDevice = mock(BluetoothDevice.class);
        when(cachedBluetoothDevice.getDevice()).thenReturn(bluetoothDevice);
        when(cachedBluetoothDevice.getGroupId()).thenReturn(1);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mCachedBluetoothDevice.getGroupId()).thenReturn(2);
        when(deviceManager.findDevice(bluetoothDevice)).thenReturn(cachedBluetoothDevice);
        when(deviceManager.findDevice(mBluetoothDevice)).thenReturn(mCachedBluetoothDevice);
        BluetoothLeBroadcastReceiveState state = mock(BluetoothLeBroadcastReceiveState.class);
        List<Long> bisSyncState = new ArrayList<>();
        bisSyncState.add(1L);
        when(state.getBisSyncState()).thenReturn(bisSyncState);
        when(mAssistant.getAllSources(any(BluetoothDevice.class)))
                .thenReturn(ImmutableList.of(state));
        when(mAssistant.getAllConnectedDevices())
                .thenReturn(ImmutableList.of(mBluetoothDevice, bluetoothDevice));

        assertThat(
                        BluetoothUtils.getSecondaryDeviceForBroadcast(
                                mContext.getContentResolver(), mLocalBluetoothManager))
                .isEqualTo(mCachedBluetoothDevice);
    }

    @Test
    public void testIsAvailableAudioSharingMediaBluetoothDevice_nullProfiles() {
        when(mProfileManager.getLeAudioBroadcastAssistantProfile()).thenReturn(null);
        boolean result =
                isAvailableAudioSharingMediaBluetoothDevice(
                        mCachedBluetoothDevice, mLocalBluetoothManager);

        assertThat(result).isFalse();
    }

    @Test
    public void testIsAvailableAudioSharingMediaBluetoothDevice_alreadyBroadcasting() {
        when(mBroadcast.isEnabled(any())).thenReturn(true);

        boolean result =
                isAvailableAudioSharingMediaBluetoothDevice(
                        mCachedBluetoothDevice, mLocalBluetoothManager);

        assertThat(result).isFalse();
    }

    @Test
    public void testIsAvailableAudioSharingMediaBluetoothDevice_availableDevice() {
        when(mCachedBluetoothDevice.getGroupId()).thenReturn(1);
        CachedBluetoothDevice cachedBluetoothDevice2 = mock(CachedBluetoothDevice.class);
        when(cachedBluetoothDevice2.getGroupId()).thenReturn(2);

        BluetoothDevice device1 = mock(BluetoothDevice.class);
        when(mDeviceManager.findDevice(device1)).thenReturn(mCachedBluetoothDevice);
        BluetoothDevice device2 = mock(BluetoothDevice.class);
        when(mDeviceManager.findDevice(device2)).thenReturn(cachedBluetoothDevice2);

        when(mAssistant.getAllConnectedDevices()).thenReturn(ImmutableList.of(device1, device2));
        when(mLeAudioProfile.getActiveDevices()).thenReturn(ImmutableList.of(device1));

        boolean result =
                isAvailableAudioSharingMediaBluetoothDevice(
                        cachedBluetoothDevice2, mLocalBluetoothManager);

        assertThat(result).isTrue();
    }

    @Test
    public void testIsAvailableAudioSharingMediaBluetoothDevice_alreadyActive() {
        when(mCachedBluetoothDevice.getGroupId()).thenReturn(1);
        CachedBluetoothDevice cachedBluetoothDevice2 = mock(CachedBluetoothDevice.class);
        when(cachedBluetoothDevice2.getGroupId()).thenReturn(2);

        BluetoothDevice device1 = mock(BluetoothDevice.class);
        when(mDeviceManager.findDevice(device1)).thenReturn(mCachedBluetoothDevice);
        BluetoothDevice device2 = mock(BluetoothDevice.class);
        when(mDeviceManager.findDevice(device2)).thenReturn(cachedBluetoothDevice2);

        when(mAssistant.getAllConnectedDevices()).thenReturn(ImmutableList.of(device1, device2));
        when(mLeAudioProfile.getActiveDevices()).thenReturn(ImmutableList.of(device1));

        boolean result =
                isAvailableAudioSharingMediaBluetoothDevice(
                        mCachedBluetoothDevice, mLocalBluetoothManager);

        assertThat(result).isFalse();
    }

    @Test
    public void testIsAvailableAudioSharingMediaBluetoothDevice_notConnected() {
        when(mCachedBluetoothDevice.getGroupId()).thenReturn(1);
        CachedBluetoothDevice cachedBluetoothDevice2 = mock(CachedBluetoothDevice.class);
        when(cachedBluetoothDevice2.getGroupId()).thenReturn(2);

        BluetoothDevice device1 = mock(BluetoothDevice.class);
        when(mDeviceManager.findDevice(device1)).thenReturn(mCachedBluetoothDevice);
        BluetoothDevice device2 = mock(BluetoothDevice.class);
        when(mDeviceManager.findDevice(device2)).thenReturn(cachedBluetoothDevice2);

        when(mAssistant.getAllConnectedDevices()).thenReturn(ImmutableList.of(device2));
        when(mLeAudioProfile.getActiveDevices()).thenReturn(ImmutableList.of(device1));

        boolean result =
                isAvailableAudioSharingMediaBluetoothDevice(
                        mCachedBluetoothDevice, mLocalBluetoothManager);

        assertThat(result).isFalse();
    }

    @Test
    public void testIsAvailableAudioSharingMediaBluetoothDevice_moreThanTwoConnected() {
        when(mCachedBluetoothDevice.getGroupId()).thenReturn(1);
        CachedBluetoothDevice cachedBluetoothDevice2 = mock(CachedBluetoothDevice.class);
        when(cachedBluetoothDevice2.getGroupId()).thenReturn(2);
        CachedBluetoothDevice cachedBluetoothDevice3 = mock(CachedBluetoothDevice.class);
        when(cachedBluetoothDevice3.getGroupId()).thenReturn(3);

        BluetoothDevice device1 = mock(BluetoothDevice.class);
        when(mDeviceManager.findDevice(device1)).thenReturn(mCachedBluetoothDevice);
        BluetoothDevice device2 = mock(BluetoothDevice.class);
        when(mDeviceManager.findDevice(device2)).thenReturn(cachedBluetoothDevice2);
        BluetoothDevice device3 = mock(BluetoothDevice.class);
        when(mDeviceManager.findDevice(device3)).thenReturn(cachedBluetoothDevice3);

        when(mAssistant.getAllConnectedDevices())
                .thenReturn(ImmutableList.of(device1, device2, device3));
        when(mLeAudioProfile.getActiveDevices()).thenReturn(ImmutableList.of(device1));

        boolean result =
                isAvailableAudioSharingMediaBluetoothDevice(
                        cachedBluetoothDevice2, mLocalBluetoothManager);

        assertThat(result).isFalse();
    }

    @Test
    public void getAudioDeviceAttributesForSpatialAudio_bleHeadset() {
        String address = "11:22:33:44:55:66";
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mCachedBluetoothDevice.getAddress()).thenReturn(address);
        when(mCachedBluetoothDevice.getProfiles()).thenReturn(List.of(mLeAudioProfile));
        when(mLeAudioProfile.isEnabled(mBluetoothDevice)).thenReturn(true);

        AudioDeviceAttributes attr =
                BluetoothUtils.getAudioDeviceAttributesForSpatialAudio(
                        mCachedBluetoothDevice, AudioManager.AUDIO_DEVICE_CATEGORY_HEADPHONES);

        assertThat(attr)
                .isEqualTo(
                        new AudioDeviceAttributes(
                                AudioDeviceAttributes.ROLE_OUTPUT,
                                AudioDeviceInfo.TYPE_BLE_HEADSET,
                                address));
    }

    @Test
    public void getAudioDeviceAttributesForSpatialAudio_bleSpeaker() {
        String address = "11:22:33:44:55:66";
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mCachedBluetoothDevice.getAddress()).thenReturn(address);
        when(mCachedBluetoothDevice.getProfiles()).thenReturn(List.of(mLeAudioProfile));
        when(mLeAudioProfile.isEnabled(mBluetoothDevice)).thenReturn(true);

        AudioDeviceAttributes attr =
                BluetoothUtils.getAudioDeviceAttributesForSpatialAudio(
                        mCachedBluetoothDevice, AudioManager.AUDIO_DEVICE_CATEGORY_SPEAKER);

        assertThat(attr)
                .isEqualTo(
                        new AudioDeviceAttributes(
                                AudioDeviceAttributes.ROLE_OUTPUT,
                                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                                address));
    }

    @Test
    public void getAudioDeviceAttributesForSpatialAudio_a2dp() {
        String address = "11:22:33:44:55:66";
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mCachedBluetoothDevice.getAddress()).thenReturn(address);
        when(mCachedBluetoothDevice.getProfiles()).thenReturn(List.of(mA2dpProfile));
        when(mA2dpProfile.isEnabled(mBluetoothDevice)).thenReturn(true);

        AudioDeviceAttributes attr =
                BluetoothUtils.getAudioDeviceAttributesForSpatialAudio(
                        mCachedBluetoothDevice, AudioManager.AUDIO_DEVICE_CATEGORY_HEADPHONES);

        assertThat(attr)
                .isEqualTo(
                        new AudioDeviceAttributes(
                                AudioDeviceAttributes.ROLE_OUTPUT,
                                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                                address));
    }

    @Test
    public void getAudioDeviceAttributesForSpatialAudio_hearingAid() {
        String address = "11:22:33:44:55:66";
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mCachedBluetoothDevice.getAddress()).thenReturn(address);
        when(mCachedBluetoothDevice.getProfiles()).thenReturn(List.of(mHearingAid));
        when(mHearingAid.isEnabled(mBluetoothDevice)).thenReturn(true);

        AudioDeviceAttributes attr =
                BluetoothUtils.getAudioDeviceAttributesForSpatialAudio(
                        mCachedBluetoothDevice, AudioManager.AUDIO_DEVICE_CATEGORY_HEARING_AID);

        assertThat(attr)
                .isEqualTo(
                        new AudioDeviceAttributes(
                                AudioDeviceAttributes.ROLE_OUTPUT,
                                AudioDeviceInfo.TYPE_HEARING_AID,
                                address));
    }
}
