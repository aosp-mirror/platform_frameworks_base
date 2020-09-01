/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.deviceidle;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.inOrder;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.DeviceIdleInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.Arrays;

/**
 * Tests for {@link com.android.server.deviceidle.BluetoothConstraint}.
 */
@RunWith(AndroidJUnit4.class)
public class BluetoothConstraintTest {

    private MockitoSession mMockingSession;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Context mContext;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Handler mHandler;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private BluetoothManager mBluetoothManager;

    @Mock
    private DeviceIdleInternal mDeviceIdleService;

    private BluetoothConstraint mConstraint;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();
        doReturn(mBluetoothManager)
                .when(mContext).getSystemService(BluetoothManager.class);
        mConstraint = new BluetoothConstraint(mContext, mHandler, mDeviceIdleService);
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void testIsConnected_noBluetoothAdapter() {
        doReturn(null).when(mBluetoothManager).getAdapter();
        assertFalse(BluetoothConstraint.isBluetoothConnected(mBluetoothManager));
    }

    @Test
    public void testIsConnected_noConnectedDevice() {
        enableBluetooth(true);
        assertFalse(BluetoothConstraint.isBluetoothConnected(mBluetoothManager));
    }

    @Test
    public void testIsConnected_twoConnectedDevices() {
        enableBluetooth(true, mock(BluetoothDevice.class), mock(BluetoothDevice.class));
        assertTrue(BluetoothConstraint.isBluetoothConnected(mBluetoothManager));
    }

    @Test
    public void testStartMonitoring_updatesActiveAtCorrectTimes() {
        // First setup -> no callbacks should fire.
        BluetoothConstraint constraint = mConstraint;
        verify(mDeviceIdleService, never()).onConstraintStateChanged(any(), anyBoolean());
        verify(mContext, never()).registerReceiver(eq(constraint.mReceiver), any());

        InOrder order = inOrder(mDeviceIdleService);

        // No devices -> active=false should be triggered.
        enableBluetooth(true);
        constraint.startMonitoring();
        order.verify(mDeviceIdleService, times(1)).onConstraintStateChanged(any(), eq(false));

        // One device -> active=true should be triggered.
        enableBluetooth(true, mock(BluetoothDevice.class));
        constraint.mReceiver.onReceive(
                mContext, new Intent(BluetoothDevice.ACTION_ACL_CONNECTED));
        constraint.startMonitoring();
        order.verify(mDeviceIdleService, times(1)).exitIdle(eq("bluetooth"));

        // Stop monitoring -> broadcast receiver should be unregistered.
        constraint.stopMonitoring();
        verify(mContext, times(1)).unregisterReceiver(eq(constraint.mReceiver));
        order.verifyNoMoreInteractions();

    }

    private void enableBluetooth(boolean enabled, BluetoothDevice... devices) {
        when(mBluetoothManager.getAdapter().isEnabled()).thenReturn(enabled);
        when(mBluetoothManager.getConnectedDevices(eq(BluetoothProfile.GATT)))
                .thenReturn(Arrays.asList(devices));
    }
}
