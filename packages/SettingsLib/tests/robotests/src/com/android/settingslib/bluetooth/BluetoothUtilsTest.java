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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.util.Pair;

import com.android.settingslib.widget.AdaptiveIcon;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class BluetoothUtilsTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private CachedBluetoothDevice mCachedBluetoothDevice;
    @Mock
    private BluetoothDevice mBluetoothDevice;
    @Mock
    private AudioManager mAudioManager;
    @Mock
    private PackageManager mPackageManager;

    private Context mContext;
    private static final String STRING_METADATA = "string_metadata";
    private static final String BOOL_METADATA = "true";
    private static final String INT_METADATA = "25";
    private static final int METADATA_FAST_PAIR_CUSTOMIZED_FIELDS = 25;
    private static final String KEY_HEARABLE_CONTROL_SLICE = "HEARABLE_CONTROL_SLICE_WITH_WIDTH";
    private static final String CONTROL_METADATA =
            "<HEARABLE_CONTROL_SLICE_WITH_WIDTH>" + STRING_METADATA
                    + "</HEARABLE_CONTROL_SLICE_WITH_WIDTH>";
    private static final String FAKE_EXCLUSIVE_MANAGER_NAME = "com.fake.name";

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = spy(RuntimeEnvironment.application);
    }

    @Test
    public void getBtClassDrawableWithDescription_typePhone_returnPhoneDrawable() {
        when(mCachedBluetoothDevice.getBtClass().getMajorDeviceClass()).thenReturn(
                BluetoothClass.Device.Major.PHONE);
        final Pair<Drawable, String> pair = BluetoothUtils.getBtClassDrawableWithDescription(
                mContext, mCachedBluetoothDevice);

        verify(mContext).getDrawable(com.android.internal.R.drawable.ic_phone);
    }

    @Test
    public void getBtClassDrawableWithDescription_typeComputer_returnComputerDrawable() {
        when(mCachedBluetoothDevice.getBtClass().getMajorDeviceClass()).thenReturn(
                BluetoothClass.Device.Major.COMPUTER);
        final Pair<Drawable, String> pair = BluetoothUtils.getBtClassDrawableWithDescription(
                mContext, mCachedBluetoothDevice);

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
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)).thenReturn("false".getBytes());
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mCachedBluetoothDevice.getAddress()).thenReturn("1f:aa:bb");

        assertThat(BluetoothUtils.getBtRainbowDrawableWithDescription(
                RuntimeEnvironment.application,
                mCachedBluetoothDevice).first).isInstanceOf(AdaptiveIcon.class);
    }

    @Test
    public void getStringMetaData_hasMetaData_getCorrectMetaData() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_UNTETHERED_LEFT_ICON)).thenReturn(
                STRING_METADATA.getBytes());

        assertThat(BluetoothUtils.getStringMetaData(mBluetoothDevice,
                BluetoothDevice.METADATA_UNTETHERED_LEFT_ICON)).isEqualTo(STRING_METADATA);
    }

    @Test
    public void getIntMetaData_hasMetaData_getCorrectMetaData() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY)).thenReturn(
                INT_METADATA.getBytes());

        assertThat(BluetoothUtils.getIntMetaData(mBluetoothDevice,
                BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY))
                .isEqualTo(Integer.parseInt(INT_METADATA));
    }

    @Test
    public void getIntMetaData_invalidMetaData_getErrorCode() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY)).thenReturn(null);

        assertThat(BluetoothUtils.getIntMetaData(mBluetoothDevice,
                BluetoothDevice.METADATA_UNTETHERED_LEFT_ICON))
                .isEqualTo(BluetoothUtils.META_INT_ERROR);
    }

    @Test
    public void getBooleanMetaData_hasMetaData_getCorrectMetaData() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)).thenReturn(
                BOOL_METADATA.getBytes());

        assertThat(BluetoothUtils.getBooleanMetaData(mBluetoothDevice,
                BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)).isEqualTo(true);
    }

    @Test
    public void getUriMetaData_hasMetaData_getCorrectMetaData() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_MAIN_ICON)).thenReturn(
                STRING_METADATA.getBytes());

        assertThat(BluetoothUtils.getUriMetaData(mBluetoothDevice,
                BluetoothDevice.METADATA_MAIN_ICON)).isEqualTo(Uri.parse(STRING_METADATA));
    }

    @Test
    public void getUriMetaData_nullMetaData_getNullUri() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_MAIN_ICON)).thenReturn(null);

        assertThat(BluetoothUtils.getUriMetaData(mBluetoothDevice,
                BluetoothDevice.METADATA_MAIN_ICON)).isNull();
    }

    @Test
    public void getControlUriMetaData_hasMetaData_returnsCorrectMetaData() {
        when(mBluetoothDevice.getMetadata(METADATA_FAST_PAIR_CUSTOMIZED_FIELDS)).thenReturn(
                CONTROL_METADATA.getBytes());

        assertThat(BluetoothUtils.getControlUriMetaData(mBluetoothDevice)).isEqualTo(
                STRING_METADATA);
    }

    @Test
    public void isAdvancedDetailsHeader_untetheredHeadset_returnTrue() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)).thenReturn(
                BOOL_METADATA.getBytes());

        assertThat(BluetoothUtils.isAdvancedDetailsHeader(mBluetoothDevice)).isEqualTo(true);
    }

    @Test
    public void isAdvancedDetailsHeader_deviceTypeUntetheredHeadset_returnTrue() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_DEVICE_TYPE)).thenReturn(
                BluetoothDevice.DEVICE_TYPE_UNTETHERED_HEADSET.getBytes());

        assertThat(BluetoothUtils.isAdvancedDetailsHeader(mBluetoothDevice)).isEqualTo(true);
    }

    @Test
    public void isAdvancedDetailsHeader_deviceTypeWatch_returnTrue() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_DEVICE_TYPE)).thenReturn(
                BluetoothDevice.DEVICE_TYPE_WATCH.getBytes());

        assertThat(BluetoothUtils.isAdvancedDetailsHeader(mBluetoothDevice)).isEqualTo(true);
    }

    @Test
    public void isAdvancedDetailsHeader_deviceTypeStylus_returnTrue() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_DEVICE_TYPE)).thenReturn(
                BluetoothDevice.DEVICE_TYPE_STYLUS.getBytes());

        assertThat(BluetoothUtils.isAdvancedDetailsHeader(mBluetoothDevice)).isEqualTo(true);
    }

    @Test
    public void isAdvancedDetailsHeader_deviceTypeDefault_returnTrue() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_DEVICE_TYPE)).thenReturn(
                BluetoothDevice.DEVICE_TYPE_DEFAULT.getBytes());

        assertThat(BluetoothUtils.isAdvancedDetailsHeader(mBluetoothDevice)).isEqualTo(true);
    }

    @Test
    public void isAdvancedDetailsHeader_noMetadata_returnFalse() {
        assertThat(BluetoothUtils.isAdvancedDetailsHeader(mBluetoothDevice)).isEqualTo(false);
    }

    @Test
    public void isAdvancedUntetheredDevice_untetheredHeadset_returnTrue() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)).thenReturn(
                BOOL_METADATA.getBytes());

        assertThat(BluetoothUtils.isAdvancedUntetheredDevice(mBluetoothDevice)).isEqualTo(true);
    }

    @Test
    public void isAdvancedUntetheredDevice_deviceTypeUntetheredHeadset_returnTrue() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_DEVICE_TYPE)).thenReturn(
                BluetoothDevice.DEVICE_TYPE_UNTETHERED_HEADSET.getBytes());

        assertThat(BluetoothUtils.isAdvancedUntetheredDevice(mBluetoothDevice)).isEqualTo(true);
    }

    @Test
    public void isAdvancedUntetheredDevice_deviceTypeWatch_returnFalse() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_DEVICE_TYPE)).thenReturn(
                BluetoothDevice.DEVICE_TYPE_WATCH.getBytes());

        assertThat(BluetoothUtils.isAdvancedUntetheredDevice(mBluetoothDevice)).isEqualTo(false);
    }

    @Test
    public void isAdvancedUntetheredDevice_deviceTypeDefault_returnFalse() {
        when(mBluetoothDevice.getMetadata(
                BluetoothDevice.METADATA_DEVICE_TYPE)).thenReturn(
                BluetoothDevice.DEVICE_TYPE_DEFAULT.getBytes());

        assertThat(BluetoothUtils.isAdvancedUntetheredDevice(mBluetoothDevice)).isEqualTo(false);
    }

    @Test
    public void isAdvancedUntetheredDevice_noMetadata_returnFalse() {
        assertThat(BluetoothUtils.isAdvancedUntetheredDevice(mBluetoothDevice)).isEqualTo(false);
    }

    @Test
    public void isAvailableMediaBluetoothDevice_isConnectedLeAudioDevice_returnTrue() {
        when(mCachedBluetoothDevice.isConnectedLeAudioDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(BluetoothUtils.isAvailableMediaBluetoothDevice(mCachedBluetoothDevice,
                mAudioManager)).isEqualTo(true);
    }

    @Test
    public void isAvailableMediaBluetoothDevice_isHeadset_isConnectedA2dpDevice_returnFalse() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_RINGTONE);
        when(mCachedBluetoothDevice.isConnectedA2dpDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(BluetoothUtils.isAvailableMediaBluetoothDevice(mCachedBluetoothDevice,
                mAudioManager)).isEqualTo(false);
    }

    @Test
    public void isAvailableMediaBluetoothDevice_isA2dp_isConnectedA2dpDevice_returnTrue() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        when(mCachedBluetoothDevice.isConnectedA2dpDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(BluetoothUtils.isAvailableMediaBluetoothDevice(mCachedBluetoothDevice,
                mAudioManager)).isEqualTo(true);
    }

    @Test
    public void isAvailableMediaBluetoothDevice_isHeadset_isConnectedHfpDevice_returnTrue() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_RINGTONE);
        when(mCachedBluetoothDevice.isConnectedHfpDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(BluetoothUtils.isAvailableMediaBluetoothDevice(mCachedBluetoothDevice,
                mAudioManager)).isEqualTo(true);
    }

    @Test
    public void isConnectedBluetoothDevice_isConnectedLeAudioDevice_returnFalse() {
        when(mCachedBluetoothDevice.isConnectedLeAudioDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(BluetoothUtils.isConnectedBluetoothDevice(mCachedBluetoothDevice,
                mAudioManager)).isEqualTo(false);
    }

    @Test
    public void isConnectedBluetoothDevice_isHeadset_isConnectedA2dpDevice_returnTrue() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_RINGTONE);
        when(mCachedBluetoothDevice.isConnectedA2dpDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(BluetoothUtils.isConnectedBluetoothDevice(mCachedBluetoothDevice,
                mAudioManager)).isEqualTo(true);
    }

    @Test
    public void isConnectedBluetoothDevice_isA2dp_isConnectedA2dpDevice_returnFalse() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        when(mCachedBluetoothDevice.isConnectedA2dpDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(BluetoothUtils.isConnectedBluetoothDevice(mCachedBluetoothDevice,
                mAudioManager)).isEqualTo(false);
    }

    @Test
    public void isConnectedBluetoothDevice_isHeadset_isConnectedHfpDevice_returnFalse() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_RINGTONE);
        when(mCachedBluetoothDevice.isConnectedHfpDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(true);

        assertThat(BluetoothUtils.isConnectedBluetoothDevice(mCachedBluetoothDevice,
                mAudioManager)).isEqualTo(false);
    }

    @Test
    public void isConnectedBluetoothDevice_isNotConnected_returnFalse() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_RINGTONE);
        when(mCachedBluetoothDevice.isConnectedA2dpDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        when(mBluetoothDevice.isConnected()).thenReturn(false);

        assertThat(BluetoothUtils.isConnectedBluetoothDevice(mCachedBluetoothDevice,
                mAudioManager)).isEqualTo(false);
    }

    @Test
    public void isExclusivelyManagedBluetoothDevice_isNotExclusivelyManaged_returnFalse() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER)).thenReturn(
                null);

        assertThat(BluetoothUtils.isExclusivelyManagedBluetoothDevice(mContext,
                mBluetoothDevice)).isEqualTo(false);
    }

    @Test
    public void isExclusivelyManagedBluetoothDevice_isNotInAllowList_returnFalse() {
        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER)).thenReturn(
                FAKE_EXCLUSIVE_MANAGER_NAME.getBytes());

        assertThat(BluetoothUtils.isExclusivelyManagedBluetoothDevice(mContext,
                mBluetoothDevice)).isEqualTo(false);
    }

    @Test
    public void isExclusivelyManagedBluetoothDevice_packageNotInstalled_returnFalse()
            throws Exception {
        final String exclusiveManagerName =
                BluetoothUtils.getExclusiveManagers().stream().findAny().orElse(
                        FAKE_EXCLUSIVE_MANAGER_NAME);

        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER)).thenReturn(
                exclusiveManagerName.getBytes());
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        doThrow(new PackageManager.NameNotFoundException()).when(mPackageManager).getPackageInfo(
                exclusiveManagerName, 0);

        assertThat(BluetoothUtils.isExclusivelyManagedBluetoothDevice(mContext,
                mBluetoothDevice)).isEqualTo(false);
    }

    @Test
    public void isExclusivelyManagedBluetoothDevice_isExclusivelyManaged_returnTrue()
            throws Exception {
        final String exclusiveManagerName =
                BluetoothUtils.getExclusiveManagers().stream().findAny().orElse(
                        FAKE_EXCLUSIVE_MANAGER_NAME);

        when(mBluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER)).thenReturn(
                exclusiveManagerName.getBytes());
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        doReturn(new PackageInfo()).when(mPackageManager).getPackageInfo(exclusiveManagerName, 0);

        assertThat(BluetoothUtils.isExclusivelyManagedBluetoothDevice(mContext,
                mBluetoothDevice)).isEqualTo(true);
    }
}
