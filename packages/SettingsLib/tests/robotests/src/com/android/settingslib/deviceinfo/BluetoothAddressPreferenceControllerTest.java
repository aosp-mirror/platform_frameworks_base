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

package com.android.settingslib.deviceinfo;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.doReturn;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.core.lifecycle.Lifecycle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
public class BluetoothAddressPreferenceControllerTest {
    @Mock
    private Context mContext;
    @Mock
    private Lifecycle mLifecycle;
    @Mock
    private PreferenceScreen mScreen;
    @Mock
    private Preference mPreference;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(mPreference).when(mScreen)
                .findPreference(AbstractBluetoothAddressPreferenceController.KEY_BT_ADDRESS);
    }

    @Implements(BluetoothAdapter.class)
    public static class ShadowEmptyBluetoothAdapter {
        @Implementation
        public static BluetoothAdapter getDefaultAdapter() {
            return null;
        }
    }

    @Test
    @Config(shadows = ShadowEmptyBluetoothAdapter.class)
    public void testNoBluetooth() {
        final AbstractBluetoothAddressPreferenceController bluetoothAddressPreferenceController =
                new ConcreteBluetoothAddressPreferenceController(mContext, mLifecycle);

        assertWithMessage("Should not show pref if no bluetooth")
                .that(bluetoothAddressPreferenceController.isAvailable())
                .isFalse();
    }

    @Test
    public void testHasBluetooth() {
        final AbstractBluetoothAddressPreferenceController bluetoothAddressPreferenceController =
                new ConcreteBluetoothAddressPreferenceController(mContext, mLifecycle);

        assertWithMessage("Should show pref if bluetooth is present")
                .that(bluetoothAddressPreferenceController.isAvailable())
                .isTrue();
    }

    @Test
    public void testHasBluetoothStateChangedFilter() {
        final AbstractBluetoothAddressPreferenceController bluetoothAddressPreferenceController =
                new ConcreteBluetoothAddressPreferenceController(mContext, mLifecycle);

        assertWithMessage("Filter should have BluetoothAdapter.ACTION_STATE_CHANGED")
                .that(bluetoothAddressPreferenceController.getConnectivityIntents())
                .asList().contains(BluetoothAdapter.ACTION_STATE_CHANGED);
    }

    private static class ConcreteBluetoothAddressPreferenceController
            extends AbstractBluetoothAddressPreferenceController {

        public ConcreteBluetoothAddressPreferenceController(Context context,
                Lifecycle lifecycle) {
            super(context, lifecycle);
        }
    }
}
