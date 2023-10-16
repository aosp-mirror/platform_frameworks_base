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

import static com.android.media.flags.Flags.FLAG_ENABLE_AUDIO_POLICIES_DEVICE_AND_BLUETOOTH_CONTROLLER;

import android.content.Context;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.platform.app.InstrumentationRegistry;

import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BluetoothRouteControllerTest {

    private final BluetoothRouteController.BluetoothRoutesUpdatedListener
            mBluetoothRoutesUpdatedListener =
                    () -> {
                        // Empty on purpose.
                    };

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
    }

    @Test
    @RequiresFlagsDisabled(FLAG_ENABLE_AUDIO_POLICIES_DEVICE_AND_BLUETOOTH_CONTROLLER)
    public void createInstance_audioPoliciesFlagIsDisabled_createsLegacyController() {
        BluetoothRouteController deviceRouteController =
                BluetoothRouteController.createInstance(mContext, mBluetoothRoutesUpdatedListener);

        Truth.assertThat(deviceRouteController).isInstanceOf(LegacyBluetoothRouteController.class);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_ENABLE_AUDIO_POLICIES_DEVICE_AND_BLUETOOTH_CONTROLLER)
    public void createInstance_audioPoliciesFlagIsEnabled_createsAudioPoliciesController() {
        BluetoothRouteController deviceRouteController =
                BluetoothRouteController.createInstance(mContext, mBluetoothRoutesUpdatedListener);

        Truth.assertThat(deviceRouteController)
                .isInstanceOf(AudioPoliciesBluetoothRouteController.class);
    }
}
