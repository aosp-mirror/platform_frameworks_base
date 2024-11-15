/*
 * Copyright 2024 The Android Open Source Project
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

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.platform.test.flag.junit.SetFlagsRule;

import com.android.settingslib.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class InputMediaDeviceTest {

    private final int BUILTIN_MIC_ID = 1;
    private final int WIRED_HEADSET_ID = 2;
    private final int USB_HEADSET_ID = 3;
    private final int BT_HEADSET_ID = 4;
    private final int BLE_HEADSET_ID = 5;
    private final int MAX_VOLUME = 1;
    private final int CURRENT_VOLUME = 0;
    private final boolean IS_VOLUME_FIXED = true;
    private static final String PRODUCT_NAME_BUILTIN_MIC = "Built-in Mic";
    private static final String PRODUCT_NAME_WIRED_HEADSET = "My Wired Headset";
    private static final String PRODUCT_NAME_USB_HEADSET = "My USB Headset";
    private static final String PRODUCT_NAME_BT_HEADSET = "My Bluetooth Headset";
    private static final String PRODUCT_NAME_BLE_HEADSET = "My BLE Headset";

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private Context mContext;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
    }

    @Test
    public void getDrawableResId_returnCorrectResId() {
        InputMediaDevice builtinMediaDevice =
                InputMediaDevice.create(
                        mContext,
                        String.valueOf(BUILTIN_MIC_ID),
                        AudioDeviceInfo.TYPE_BUILTIN_MIC,
                        MAX_VOLUME,
                        CURRENT_VOLUME,
                        IS_VOLUME_FIXED,
                        PRODUCT_NAME_BUILTIN_MIC);
        assertThat(builtinMediaDevice).isNotNull();
        assertThat(builtinMediaDevice.getDrawableResId()).isEqualTo(R.drawable.ic_media_microphone);
    }

    @Test
    public void getName_returnCorrectName_builtinMic() {
        InputMediaDevice builtinMediaDevice =
                InputMediaDevice.create(
                        mContext,
                        String.valueOf(BUILTIN_MIC_ID),
                        AudioDeviceInfo.TYPE_BUILTIN_MIC,
                        MAX_VOLUME,
                        CURRENT_VOLUME,
                        IS_VOLUME_FIXED,
                        PRODUCT_NAME_BUILTIN_MIC);
        assertThat(builtinMediaDevice).isNotNull();
        assertThat(builtinMediaDevice.getName())
                .isEqualTo(mContext.getString(R.string.media_transfer_this_device_name_desktop));
    }

    @Test
    public void getName_returnCorrectName_wiredHeadset() {
        InputMediaDevice wiredMediaDevice =
                InputMediaDevice.create(
                        mContext,
                        String.valueOf(WIRED_HEADSET_ID),
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        MAX_VOLUME,
                        CURRENT_VOLUME,
                        IS_VOLUME_FIXED,
                        PRODUCT_NAME_WIRED_HEADSET);
        assertThat(wiredMediaDevice).isNotNull();
        assertThat(wiredMediaDevice.getName())
                .isEqualTo(mContext.getString(R.string.media_transfer_wired_device_mic_name));
    }

    @Test
    public void getName_returnCorrectName_usbHeadset() {
        InputMediaDevice usbMediaDevice =
                InputMediaDevice.create(
                        mContext,
                        String.valueOf(USB_HEADSET_ID),
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        MAX_VOLUME,
                        CURRENT_VOLUME,
                        IS_VOLUME_FIXED,
                        PRODUCT_NAME_USB_HEADSET);
        assertThat(usbMediaDevice).isNotNull();
        assertThat(usbMediaDevice.getName()).isEqualTo(PRODUCT_NAME_USB_HEADSET);
    }

    @Test
    public void getName_returnCorrectName_usbHeadset_nullProductName() {
        InputMediaDevice usbMediaDevice =
                InputMediaDevice.create(
                        mContext,
                        String.valueOf(USB_HEADSET_ID),
                        AudioDeviceInfo.TYPE_USB_HEADSET,
                        MAX_VOLUME,
                        CURRENT_VOLUME,
                        IS_VOLUME_FIXED,
                        null);
        assertThat(usbMediaDevice).isNotNull();
        assertThat(usbMediaDevice.getName())
                .isEqualTo(mContext.getString(R.string.media_transfer_usb_device_mic_name));
    }

    @Test
    public void getName_returnCorrectName_btHeadset() {
        InputMediaDevice btMediaDevice =
                InputMediaDevice.create(
                        mContext,
                        String.valueOf(BT_HEADSET_ID),
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        MAX_VOLUME,
                        CURRENT_VOLUME,
                        IS_VOLUME_FIXED,
                        PRODUCT_NAME_BT_HEADSET);
        assertThat(btMediaDevice).isNotNull();
        assertThat(btMediaDevice.getName()).isEqualTo(PRODUCT_NAME_BT_HEADSET);
    }

    @Test
    public void getName_returnCorrectName_btHeadset_nullProductName() {
        InputMediaDevice btMediaDevice =
                InputMediaDevice.create(
                        mContext,
                        String.valueOf(BT_HEADSET_ID),
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        MAX_VOLUME,
                        CURRENT_VOLUME,
                        IS_VOLUME_FIXED,
                        null);
        assertThat(btMediaDevice).isNotNull();
        assertThat(btMediaDevice.getName())
                .isEqualTo(mContext.getString(R.string.media_transfer_bt_device_mic_name));
    }

    @Test
    public void getName_returnCorrectName_bleHeadset() {
        InputMediaDevice bleMediaDevice =
                InputMediaDevice.create(
                        mContext,
                        String.valueOf(BLE_HEADSET_ID),
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        MAX_VOLUME,
                        CURRENT_VOLUME,
                        IS_VOLUME_FIXED,
                        PRODUCT_NAME_BLE_HEADSET);
        assertThat(bleMediaDevice).isNotNull();
        assertThat(bleMediaDevice.getName()).isEqualTo(PRODUCT_NAME_BLE_HEADSET);
    }

    @Test
    public void getName_returnCorrectName_bleHeadset_nullProductName() {
        InputMediaDevice bleMediaDevice =
                InputMediaDevice.create(
                        mContext,
                        String.valueOf(BLE_HEADSET_ID),
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        MAX_VOLUME,
                        CURRENT_VOLUME,
                        IS_VOLUME_FIXED,
                        null);
        assertThat(bleMediaDevice).isNotNull();
        assertThat(bleMediaDevice.getName())
                .isEqualTo(mContext.getString(R.string.media_transfer_bt_device_mic_name));
    }
}
