/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.settingslib.bluetooth.BluetoothEventManager;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.LocalBluetoothAdapter;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class BluetoothControllerImplTest extends SysuiTestCase {

    private LocalBluetoothManager mMockBluetoothManager;
    private CachedBluetoothDeviceManager mMockDeviceManager;
    private LocalBluetoothAdapter mMockAdapter;
    private TestableLooper mTestableLooper;
    private BluetoothControllerImpl mBluetoothControllerImpl;

    private List<CachedBluetoothDevice> mDevices;

    @Before
    public void setup() throws Exception {
        mTestableLooper = TestableLooper.get(this);
        mMockBluetoothManager = mDependency.injectMockDependency(LocalBluetoothManager.class);
        mDevices = new ArrayList<>();
        mMockDeviceManager = mock(CachedBluetoothDeviceManager.class);
        when(mMockDeviceManager.getCachedDevicesCopy()).thenReturn(mDevices);
        when(mMockBluetoothManager.getCachedDeviceManager()).thenReturn(mMockDeviceManager);
        mMockAdapter = mock(LocalBluetoothAdapter.class);
        when(mMockBluetoothManager.getBluetoothAdapter()).thenReturn(mMockAdapter);
        when(mMockBluetoothManager.getEventManager()).thenReturn(mock(BluetoothEventManager.class));

        mBluetoothControllerImpl = new BluetoothControllerImpl(mContext,
                mTestableLooper.getLooper());
    }

    @Test
    public void testNoConnectionWithDevices() {
        CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        when(device.isConnected()).thenReturn(true);
        when(device.getMaxConnectionState()).thenReturn(BluetoothProfile.STATE_CONNECTED);
        mDevices.add(device);
        when(mMockAdapter.getConnectionState()).thenReturn(BluetoothAdapter.STATE_DISCONNECTED);

        mBluetoothControllerImpl.onConnectionStateChanged(null,
                BluetoothAdapter.STATE_DISCONNECTED);
        assertTrue(mBluetoothControllerImpl.isBluetoothConnected());
    }
}
