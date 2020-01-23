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

package com.android.server;

import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.os.Looper;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.BluetoothAirplaneModeListener.AirplaneModeHelper;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class BluetoothAirplaneModeListenerTest {
    private Context mContext;
    private BluetoothAirplaneModeListener mBluetoothAirplaneModeListener;
    private BluetoothAdapter mBluetoothAdapter;
    private AirplaneModeHelper mHelper;

    @Mock BluetoothManagerService mBluetoothManagerService;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();

        mHelper = mock(AirplaneModeHelper.class);
        when(mHelper.getSettingsInt(BluetoothAirplaneModeListener.TOAST_COUNT))
                .thenReturn(BluetoothAirplaneModeListener.MAX_TOAST_COUNT);
        doNothing().when(mHelper).setSettingsInt(anyString(), anyInt());
        doNothing().when(mHelper).showToastMessage();
        doNothing().when(mHelper).onAirplaneModeChanged(any(BluetoothManagerService.class));

        mBluetoothAirplaneModeListener = new BluetoothAirplaneModeListener(
                    mBluetoothManagerService, Looper.getMainLooper(), mContext);
        mBluetoothAirplaneModeListener.start(mHelper);
    }

    @Test
    public void testIgnoreOnAirplanModeChange() {
        Assert.assertFalse(mBluetoothAirplaneModeListener.shouldSkipAirplaneModeChange());

        when(mHelper.isBluetoothOn()).thenReturn(true);
        Assert.assertFalse(mBluetoothAirplaneModeListener.shouldSkipAirplaneModeChange());

        when(mHelper.isA2dpOrHearingAidConnected()).thenReturn(true);
        Assert.assertFalse(mBluetoothAirplaneModeListener.shouldSkipAirplaneModeChange());

        when(mHelper.isAirplaneModeOn()).thenReturn(true);
        Assert.assertTrue(mBluetoothAirplaneModeListener.shouldSkipAirplaneModeChange());
    }

    @Test
    public void testHandleAirplaneModeChange_InvokeAirplaneModeChanged() {
        mBluetoothAirplaneModeListener.handleAirplaneModeChange();
        verify(mHelper).onAirplaneModeChanged(mBluetoothManagerService);
    }

    @Test
    public void testHandleAirplaneModeChange_NotInvokeAirplaneModeChanged_NotPopToast() {
        mBluetoothAirplaneModeListener.mToastCount = BluetoothAirplaneModeListener.MAX_TOAST_COUNT;
        when(mHelper.isBluetoothOn()).thenReturn(true);
        when(mHelper.isA2dpOrHearingAidConnected()).thenReturn(true);
        when(mHelper.isAirplaneModeOn()).thenReturn(true);
        mBluetoothAirplaneModeListener.handleAirplaneModeChange();

        verify(mHelper).setSettingsInt(Settings.Global.BLUETOOTH_ON,
                BluetoothManagerService.BLUETOOTH_ON_AIRPLANE);
        verify(mHelper, times(0)).showToastMessage();
        verify(mHelper, times(0)).onAirplaneModeChanged(mBluetoothManagerService);
    }

    @Test
    public void testHandleAirplaneModeChange_NotInvokeAirplaneModeChanged_PopToast() {
        mBluetoothAirplaneModeListener.mToastCount = 0;
        when(mHelper.isBluetoothOn()).thenReturn(true);
        when(mHelper.isA2dpOrHearingAidConnected()).thenReturn(true);
        when(mHelper.isAirplaneModeOn()).thenReturn(true);
        mBluetoothAirplaneModeListener.handleAirplaneModeChange();

        verify(mHelper).setSettingsInt(Settings.Global.BLUETOOTH_ON,
                BluetoothManagerService.BLUETOOTH_ON_AIRPLANE);
        verify(mHelper).showToastMessage();
        verify(mHelper, times(0)).onAirplaneModeChanged(mBluetoothManagerService);
    }

    @Test
    public void testIsPopToast_PopToast() {
        mBluetoothAirplaneModeListener.mToastCount = 0;
        Assert.assertTrue(mBluetoothAirplaneModeListener.shouldPopToast());
        verify(mHelper).setSettingsInt(BluetoothAirplaneModeListener.TOAST_COUNT, 1);
    }

    @Test
    public void testIsPopToast_NotPopToast() {
        mBluetoothAirplaneModeListener.mToastCount = BluetoothAirplaneModeListener.MAX_TOAST_COUNT;
        Assert.assertFalse(mBluetoothAirplaneModeListener.shouldPopToast());
        verify(mHelper, times(0)).setSettingsInt(anyString(), anyInt());
    }
}
