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

package com.android.settingslib.bluetooth;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothVolumeControl;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.settingslib.testutils.shadow.ShadowBluetoothAdapter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowBluetoothAdapter.class})
public class VolumeControlProfileTest {

    private static final int TEST_VOLUME_OFFSET = 10;
    private static final int TEST_VOLUME_VALUE = 10;

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock private CachedBluetoothDeviceManager mDeviceManager;
    @Mock private LocalBluetoothProfileManager mProfileManager;
    @Mock private BluetoothDevice mBluetoothDevice;
    @Mock private BluetoothVolumeControl mService;

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private BluetoothProfile.ServiceListener mServiceListener;
    private VolumeControlProfile mProfile;

    @Before
    public void setUp() {
        mProfile = new VolumeControlProfile(mContext, mDeviceManager, mProfileManager);
        final BluetoothManager bluetoothManager = mContext.getSystemService(BluetoothManager.class);
        final ShadowBluetoothAdapter shadowBluetoothAdapter =
                Shadow.extract(bluetoothManager.getAdapter());
        mServiceListener = shadowBluetoothAdapter.getServiceListener();
    }

    @Test
    public void onServiceConnected_isProfileReady() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);

        assertThat(mProfile.isProfileReady()).isTrue();
        verify(mProfileManager).callServiceConnectedListeners();
    }

    @Test
    public void onServiceDisconnected_isProfileNotReady() {
        mServiceListener.onServiceDisconnected(BluetoothProfile.VOLUME_CONTROL);

        assertThat(mProfile.isProfileReady()).isFalse();
        verify(mProfileManager).callServiceDisconnectedListeners();
    }

    @Test
    public void getConnectionStatus_returnCorrectConnectionState() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);
        when(mService.getConnectionState(mBluetoothDevice))
                .thenReturn(BluetoothProfile.STATE_CONNECTED);

        assertThat(mProfile.getConnectionStatus(mBluetoothDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);
    }

    @Test
    public void isEnabled_connectionPolicyAllowed_returnTrue() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);
        when(mService.getConnectionPolicy(mBluetoothDevice)).thenReturn(CONNECTION_POLICY_ALLOWED);

        assertThat(mProfile.isEnabled(mBluetoothDevice)).isTrue();
    }

    @Test
    public void isEnabled_connectionPolicyForbidden_returnFalse() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);
        when(mService.getConnectionPolicy(mBluetoothDevice))
                .thenReturn(CONNECTION_POLICY_FORBIDDEN);

        assertThat(mProfile.isEnabled(mBluetoothDevice)).isFalse();
    }

    @Test
    public void getConnectionPolicy_returnCorrectConnectionPolicy() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);
        when(mService.getConnectionPolicy(mBluetoothDevice)).thenReturn(CONNECTION_POLICY_ALLOWED);

        assertThat(mProfile.getConnectionPolicy(mBluetoothDevice))
                .isEqualTo(CONNECTION_POLICY_ALLOWED);
    }

    @Test
    public void setEnabled_connectionPolicyAllowed_setConnectionPolicyAllowed_returnFalse() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);
        when(mService.getConnectionPolicy(mBluetoothDevice)).thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mService.setConnectionPolicy(mBluetoothDevice, CONNECTION_POLICY_ALLOWED))
                .thenReturn(true);

        assertThat(mProfile.setEnabled(mBluetoothDevice, true)).isFalse();
    }

    @Test
    public void setEnabled_connectionPolicyForbidden_setConnectionPolicyAllowed_returnTrue() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);
        when(mService.getConnectionPolicy(mBluetoothDevice))
                .thenReturn(CONNECTION_POLICY_FORBIDDEN);
        when(mService.setConnectionPolicy(mBluetoothDevice, CONNECTION_POLICY_ALLOWED))
                .thenReturn(true);

        assertThat(mProfile.setEnabled(mBluetoothDevice, true)).isTrue();
    }

    @Test
    public void setEnabled_connectionPolicyAllowed_setConnectionPolicyForbidden_returnTrue() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);
        when(mService.getConnectionPolicy(mBluetoothDevice)).thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mService.setConnectionPolicy(mBluetoothDevice, CONNECTION_POLICY_FORBIDDEN))
                .thenReturn(true);

        assertThat(mProfile.setEnabled(mBluetoothDevice, false)).isTrue();
    }

    @Test
    public void setEnabled_connectionPolicyForbidden_setConnectionPolicyForbidden_returnTrue() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);
        when(mService.getConnectionPolicy(mBluetoothDevice))
                .thenReturn(CONNECTION_POLICY_FORBIDDEN);
        when(mService.setConnectionPolicy(mBluetoothDevice, CONNECTION_POLICY_FORBIDDEN))
                .thenReturn(true);

        assertThat(mProfile.setEnabled(mBluetoothDevice, false)).isTrue();
    }

    @Test
    public void getConnectedDevices_returnCorrectList() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);
        int[] connectedStates =
                new int[] {
                    BluetoothProfile.STATE_CONNECTED,
                    BluetoothProfile.STATE_CONNECTING,
                    BluetoothProfile.STATE_DISCONNECTING
                };
        List<BluetoothDevice> connectedList =
                Arrays.asList(mBluetoothDevice, mBluetoothDevice, mBluetoothDevice);
        when(mService.getDevicesMatchingConnectionStates(connectedStates))
                .thenReturn(connectedList);

        assertThat(mProfile.getConnectedDevices().size()).isEqualTo(connectedList.size());
    }

    @Test
    public void registerCallback_verifyIsCalled() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);

        final Executor executor = (command -> new Thread(command).start());
        final BluetoothVolumeControl.Callback callback = new BluetoothVolumeControl.Callback() {};
        mProfile.registerCallback(executor, callback);

        verify(mService).registerCallback(executor, callback);
    }

    @Test
    public void unregisterCallback_verifyIsCalled() {
        final BluetoothVolumeControl.Callback callback = new BluetoothVolumeControl.Callback() {};
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);

        mProfile.unregisterCallback(callback);

        verify(mService).unregisterCallback(callback);
    }

    @Test
    public void setVolumeOffset_verifyIsCalled() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);

        mProfile.setVolumeOffset(mBluetoothDevice, TEST_VOLUME_OFFSET);

        verify(mService).setVolumeOffset(mBluetoothDevice, TEST_VOLUME_OFFSET);
    }

    @Test
    public void setDeviceVolume_verifyIsCalled() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);

        mProfile.setDeviceVolume(mBluetoothDevice, TEST_VOLUME_VALUE, /* isGroupOp= */ true);

        verify(mService)
                .setDeviceVolume(mBluetoothDevice, TEST_VOLUME_VALUE, /* isGroupOp= */ true);
    }

    @Test
    public void isVolumeOffsetAvailable_verifyIsCalledAndReturnTrue() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);
        when(mService.isVolumeOffsetAvailable(mBluetoothDevice)).thenReturn(true);

        final boolean available = mProfile.isVolumeOffsetAvailable(mBluetoothDevice);

        verify(mService).isVolumeOffsetAvailable(mBluetoothDevice);
        assertThat(available).isTrue();
    }

    @Test
    public void isVolumeOffsetAvailable_verifyIsCalledAndReturnFalse() {
        mServiceListener.onServiceConnected(BluetoothProfile.VOLUME_CONTROL, mService);
        when(mService.isVolumeOffsetAvailable(mBluetoothDevice)).thenReturn(false);

        final boolean available = mProfile.isVolumeOffsetAvailable(mBluetoothDevice);

        verify(mService).isVolumeOffsetAvailable(mBluetoothDevice);
        assertThat(available).isFalse();
    }
}
