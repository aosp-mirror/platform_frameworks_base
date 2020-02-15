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

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import com.android.settingslib.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class PhoneMediaDeviceTest {

    private Context mContext;
    private PhoneMediaDevice mPhoneMediaDevice;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;

        mPhoneMediaDevice =
                new PhoneMediaDevice(mContext, null, null, null);
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
}
