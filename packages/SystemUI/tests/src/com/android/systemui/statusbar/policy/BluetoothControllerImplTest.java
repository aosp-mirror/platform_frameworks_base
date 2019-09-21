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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.settingslib.bluetooth.BluetoothEventManager;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.LocalBluetoothAdapter;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
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
        when(mMockBluetoothManager.getProfileManager())
                .thenReturn(mock(LocalBluetoothProfileManager.class));

        mBluetoothControllerImpl = new BluetoothControllerImpl(mContext,
                mTestableLooper.getLooper(),
                mTestableLooper.getLooper(),
                mMockBluetoothManager);
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

    @Test
    public void testDefaultConnectionState() {
        CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        assertEquals(BluetoothDevice.BOND_NONE, mBluetoothControllerImpl.getBondState(device));
        assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                mBluetoothControllerImpl.getMaxConnectionState(device));
    }

    @Test
    public void testAsyncBondState() throws Exception {
        CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        when(device.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        BluetoothController.Callback callback = mock(BluetoothController.Callback.class);
        mBluetoothControllerImpl.addCallback(callback);

        // Trigger the state getting.
        assertEquals(BluetoothDevice.BOND_NONE, mBluetoothControllerImpl.getBondState(device));

        mTestableLooper.processAllMessages();

        assertEquals(BluetoothDevice.BOND_BONDED, mBluetoothControllerImpl.getBondState(device));
        verify(callback).onBluetoothDevicesChanged();
    }

    @Test
    public void testAsyncConnectionState() throws Exception {
        CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        when(device.getMaxConnectionState()).thenReturn(BluetoothProfile.STATE_CONNECTED);
        BluetoothController.Callback callback = mock(BluetoothController.Callback.class);
        mBluetoothControllerImpl.addCallback(callback);

        // Trigger the state getting.
        assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                mBluetoothControllerImpl.getMaxConnectionState(device));

        mTestableLooper.processAllMessages();

        assertEquals(BluetoothProfile.STATE_CONNECTED,
                mBluetoothControllerImpl.getMaxConnectionState(device));
        verify(callback).onBluetoothDevicesChanged();
    }

    @Test
    public void testNullAsync_DoesNotCrash() throws Exception {
        CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        when(device.getMaxConnectionState()).thenReturn(BluetoothProfile.STATE_CONNECTED);
        BluetoothController.Callback callback = mock(BluetoothController.Callback.class);
        mBluetoothControllerImpl.addCallback(callback);

        // Trigger the state getting.
        assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                mBluetoothControllerImpl.getMaxConnectionState(null));

        mTestableLooper.processAllMessages();
    }

    @Test
    public void testOnServiceConnected_updatesConnectionState() {
        when(mMockAdapter.getConnectionState()).thenReturn(BluetoothAdapter.STATE_CONNECTING);

        mBluetoothControllerImpl.onServiceConnected();

        assertTrue(mBluetoothControllerImpl.isBluetoothConnecting());
        assertFalse(mBluetoothControllerImpl.isBluetoothConnected());
    }

    @Test
    public void testOnBluetoothStateChange_updatesBluetoothState() {
        mBluetoothControllerImpl.onBluetoothStateChanged(BluetoothAdapter.STATE_OFF);

        assertEquals(BluetoothAdapter.STATE_OFF, mBluetoothControllerImpl.getBluetoothState());

        mBluetoothControllerImpl.onBluetoothStateChanged(BluetoothAdapter.STATE_ON);

        assertEquals(BluetoothAdapter.STATE_ON, mBluetoothControllerImpl.getBluetoothState());
    }

    @Test
    public void testOnBluetoothStateChange_updatesConnectionState() {
        when(mMockAdapter.getConnectionState()).thenReturn(
                BluetoothAdapter.STATE_CONNECTING,
                BluetoothAdapter.STATE_DISCONNECTED);

        mBluetoothControllerImpl.onServiceConnected();
        mBluetoothControllerImpl.onBluetoothStateChanged(BluetoothAdapter.STATE_OFF);

        assertFalse(mBluetoothControllerImpl.isBluetoothConnecting());
        assertFalse(mBluetoothControllerImpl.isBluetoothConnected());
    }

    @Test
    public void testOnACLConnectionStateChange_updatesBluetoothStateOnConnection()
            throws Exception {
        BluetoothController.Callback callback = mock(BluetoothController.Callback.class);
        mBluetoothControllerImpl.addCallback(callback);

        assertFalse(mBluetoothControllerImpl.isBluetoothConnected());
        CachedBluetoothDevice device = mock(CachedBluetoothDevice.class);
        mDevices.add(device);
        when(device.isConnected()).thenReturn(true);
        when(device.getMaxConnectionState()).thenReturn(BluetoothProfile.STATE_CONNECTED);
        reset(callback);
        mBluetoothControllerImpl.onAclConnectionStateChanged(device,
                BluetoothProfile.STATE_CONNECTED);

        mTestableLooper.processAllMessages();

        assertTrue(mBluetoothControllerImpl.isBluetoothConnected());
        verify(callback, atLeastOnce()).onBluetoothStateChange(anyBoolean());
    }
}
