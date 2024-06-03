/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.hearingaid;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.LocalBluetoothAdapter;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

/** Tests for {@link HearingDevicesDialogManager}. */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class HearingDevicesDialogManagerTest extends SysuiTestCase {

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    private final List<CachedBluetoothDevice> mCachedDevices = new ArrayList<>();
    @Mock
    private Expandable mExpandable;
    @Mock
    private DialogTransitionAnimator mDialogTransitionAnimator;
    @Mock
    private HearingDevicesDialogDelegate.Factory mDialogFactory;
    @Mock
    private HearingDevicesDialogDelegate mDialogDelegate;
    @Mock
    private SystemUIDialog mDialog;
    @Mock
    private LocalBluetoothManager mLocalBluetoothManager;
    @Mock
    private LocalBluetoothAdapter mLocalBluetoothAdapter;
    @Mock
    private CachedBluetoothDeviceManager mCachedBluetoothDeviceManager;
    @Mock
    private CachedBluetoothDevice mCachedDevice;

    private HearingDevicesDialogManager mManager;

    @Before
    public void setUp() {
        when(mDialogFactory.create(anyBoolean())).thenReturn(mDialogDelegate);
        when(mDialogDelegate.createDialog()).thenReturn(mDialog);
        when(mLocalBluetoothManager.getBluetoothAdapter()).thenReturn(mLocalBluetoothAdapter);
        when(mLocalBluetoothManager.getCachedDeviceManager()).thenReturn(
                mCachedBluetoothDeviceManager);
        when(mCachedBluetoothDeviceManager.getCachedDevicesCopy()).thenReturn(mCachedDevices);

        mManager = new HearingDevicesDialogManager(
                mDialogTransitionAnimator,
                mDialogFactory,
                mLocalBluetoothManager
        );
    }

    @Test
    public void showDialog_bluetoothDisable_showPairNewDeviceTrue() {
        when(mLocalBluetoothAdapter.isEnabled()).thenReturn(false);

        mManager.showDialog(mExpandable);

        verify(mDialogFactory).create(eq(true));
    }

    @Test
    public void showDialog_containsHearingAid_showPairNewDeviceFalse() {
        when(mLocalBluetoothAdapter.isEnabled()).thenReturn(true);
        when(mCachedDevice.isHearingAidDevice()).thenReturn(true);
        when(mCachedDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);
        mCachedDevices.add(mCachedDevice);

        mManager.showDialog(mExpandable);

        verify(mDialogFactory).create(eq(false));
    }
}
