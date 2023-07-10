/*
 * Copyright 2019 The Android Open Source Project
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

import static android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_USB_DEVICE;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADPHONES;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADSET;

import static com.android.settingslib.media.PhoneMediaDevice.PHONE_ID;
import static com.android.settingslib.media.PhoneMediaDevice.USB_HEADSET_ID;
import static com.android.settingslib.media.PhoneMediaDevice.WIRED_HEADSET_ID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.MediaRoute2Info;

import com.android.settingslib.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class PhoneMediaDeviceTest {

    @Mock
    private MediaRoute2Info mInfo;

    private Context mContext;
    private PhoneMediaDevice mPhoneMediaDevice;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;

        mPhoneMediaDevice = new PhoneMediaDevice(mContext, mInfo, null);
    }

    @Test
    public void updateSummary_isActiveIsTrue_returnActiveString() {
        mPhoneMediaDevice.updateSummary(true);

        assertThat(mPhoneMediaDevice.getSummary())
                .isEqualTo(mContext.getString(R.string.bluetooth_active_no_battery_level));
    }

    @Test
    public void updateSummary_notActive_returnEmpty() {
        mPhoneMediaDevice.updateSummary(false);

        assertThat(mPhoneMediaDevice.getSummary()).isEmpty();
    }

    @Test
    public void getDrawableResId_returnCorrectResId() {
        when(mInfo.getType()).thenReturn(TYPE_WIRED_HEADPHONES);

        assertThat(mPhoneMediaDevice.getDrawableResId()).isEqualTo(R.drawable.ic_headphone);

        when(mInfo.getType()).thenReturn(TYPE_WIRED_HEADSET);

        assertThat(mPhoneMediaDevice.getDrawableResId()).isEqualTo(R.drawable.ic_headphone);

        when(mInfo.getType()).thenReturn(TYPE_BUILTIN_SPEAKER);

        assertThat(mPhoneMediaDevice.getDrawableResId()).isEqualTo(R.drawable.ic_smartphone);
    }

    @Test
    public void getName_returnCorrectName() {
        final String deviceName = "test_name";

        when(mInfo.getType()).thenReturn(TYPE_WIRED_HEADPHONES);
        when(mInfo.getName()).thenReturn(deviceName);

        assertThat(mPhoneMediaDevice.getName())
                .isEqualTo(mContext.getString(R.string.media_transfer_wired_usb_device_name));

        when(mInfo.getType()).thenReturn(TYPE_USB_DEVICE);

        assertThat(mPhoneMediaDevice.getName())
                .isEqualTo(mContext.getString(R.string.media_transfer_wired_usb_device_name));

        when(mInfo.getType()).thenReturn(TYPE_BUILTIN_SPEAKER);

        assertThat(mPhoneMediaDevice.getName())
                .isEqualTo(mContext.getString(R.string.media_transfer_this_device_name));
    }

    @Test
    public void getId_returnCorrectId() {
        when(mInfo.getType()).thenReturn(TYPE_WIRED_HEADPHONES);

        assertThat(mPhoneMediaDevice.getId())
                .isEqualTo(WIRED_HEADSET_ID);

        when(mInfo.getType()).thenReturn(TYPE_USB_DEVICE);

        assertThat(mPhoneMediaDevice.getId())
                .isEqualTo(USB_HEADSET_ID);

        when(mInfo.getType()).thenReturn(TYPE_BUILTIN_SPEAKER);

        assertThat(mPhoneMediaDevice.getId())
                .isEqualTo(PHONE_ID);
    }
}
