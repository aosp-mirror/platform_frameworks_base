/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothHapPresetInfo;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import androidx.annotation.NonNull;
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
public class HapClientProfileTest {

    private static final int TEST_GROUP_ID = 1;
    private static final int TEST_PRESET_INDEX = 1;
    private static final String TEST_DEVICE_NAME = "test_device";

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private CachedBluetoothDeviceManager mDeviceManager;
    @Mock
    private LocalBluetoothProfileManager mProfileManager;
    @Mock
    private BluetoothDevice mBluetoothDevice;
    @Mock
    private BluetoothHapClient mService;
    @Mock
    private BluetoothHapPresetInfo mPresetInfo;

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private BluetoothProfile.ServiceListener mServiceListener;
    private HapClientProfile mProfile;

    @Before
    public void setUp() {
        mProfile = new HapClientProfile(mContext, mDeviceManager, mProfileManager);
        final BluetoothManager bluetoothManager = mContext.getSystemService(BluetoothManager.class);
        final ShadowBluetoothAdapter shadowBluetoothAdapter =
                Shadow.extract(bluetoothManager.getAdapter());
        mServiceListener = shadowBluetoothAdapter.getServiceListener();
    }

    @Test
    public void onServiceConnected_isProfileReady() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        assertThat(mProfile.isProfileReady()).isTrue();
        verify(mProfileManager).callServiceConnectedListeners();
    }

    @Test
    public void onServiceDisconnected_isProfileNotReady() {
        mServiceListener.onServiceDisconnected(BluetoothProfile.HAP_CLIENT);

        assertThat(mProfile.isProfileReady()).isFalse();
        verify(mProfileManager).callServiceDisconnectedListeners();
    }

    @Test
    public void getConnectionStatus_returnCorrectConnectionState() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);
        when(mService.getConnectionState(mBluetoothDevice))
                .thenReturn(BluetoothProfile.STATE_CONNECTED);

        assertThat(mProfile.getConnectionStatus(mBluetoothDevice))
                .isEqualTo(BluetoothProfile.STATE_CONNECTED);
    }

    @Test
    public void isEnabled_connectionPolicyAllowed_returnTrue() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);
        when(mService.getConnectionPolicy(mBluetoothDevice)).thenReturn(CONNECTION_POLICY_ALLOWED);

        assertThat(mProfile.isEnabled(mBluetoothDevice)).isTrue();
    }

    @Test
    public void isEnabled_connectionPolicyForbidden_returnFalse() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);
        when(mService.getConnectionPolicy(mBluetoothDevice))
                .thenReturn(CONNECTION_POLICY_FORBIDDEN);

        assertThat(mProfile.isEnabled(mBluetoothDevice)).isFalse();
    }

    @Test
    public void getConnectionPolicy_returnCorrectConnectionPolicy() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);
        when(mService.getConnectionPolicy(mBluetoothDevice)).thenReturn(CONNECTION_POLICY_ALLOWED);

        assertThat(mProfile.getConnectionPolicy(mBluetoothDevice))
                .isEqualTo(CONNECTION_POLICY_ALLOWED);
    }

    @Test
    public void setEnabled_connectionPolicyAllowed_setConnectionPolicyAllowed_returnFalse() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);
        when(mService.getConnectionPolicy(mBluetoothDevice)).thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mService.setConnectionPolicy(mBluetoothDevice, CONNECTION_POLICY_ALLOWED))
                .thenReturn(true);

        assertThat(mProfile.setEnabled(mBluetoothDevice, true)).isFalse();
    }

    @Test
    public void setEnabled_connectionPolicyForbidden_setConnectionPolicyAllowed_returnTrue() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);
        when(mService.getConnectionPolicy(mBluetoothDevice))
                .thenReturn(CONNECTION_POLICY_FORBIDDEN);
        when(mService.setConnectionPolicy(mBluetoothDevice, CONNECTION_POLICY_ALLOWED))
                .thenReturn(true);

        assertThat(mProfile.setEnabled(mBluetoothDevice, true)).isTrue();
    }

    @Test
    public void setEnabled_connectionPolicyAllowed_setConnectionPolicyForbidden_returnTrue() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);
        when(mService.getConnectionPolicy(mBluetoothDevice)).thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mService.setConnectionPolicy(mBluetoothDevice, CONNECTION_POLICY_FORBIDDEN))
                .thenReturn(true);

        assertThat(mProfile.setEnabled(mBluetoothDevice, false)).isTrue();
    }

    @Test
    public void setEnabled_connectionPolicyForbidden_setConnectionPolicyForbidden_returnTrue() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);
        when(mService.getConnectionPolicy(mBluetoothDevice))
                .thenReturn(CONNECTION_POLICY_FORBIDDEN);
        when(mService.setConnectionPolicy(mBluetoothDevice, CONNECTION_POLICY_FORBIDDEN))
                .thenReturn(true);

        assertThat(mProfile.setEnabled(mBluetoothDevice, false)).isTrue();
    }

    @Test
    public void getConnectedDevices_returnCorrectList() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);
        int[] connectedStates = new int[] {
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTING};
        List<BluetoothDevice> connectedList = Arrays.asList(
                mBluetoothDevice,
                mBluetoothDevice,
                mBluetoothDevice);
        when(mService.getDevicesMatchingConnectionStates(connectedStates))
                .thenReturn(connectedList);

        assertThat(mProfile.getConnectedDevices().size()).isEqualTo(connectedList.size());
    }

    @Test
    public void getConnectableDevices_returnCorrectList() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);
        int[] connectableStates = new int[] {
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTING};
        List<BluetoothDevice> connectableList = Arrays.asList(
                mBluetoothDevice,
                mBluetoothDevice,
                mBluetoothDevice,
                mBluetoothDevice);
        when(mService.getDevicesMatchingConnectionStates(connectableStates))
                .thenReturn(connectableList);

        assertThat(mProfile.getConnectableDevices().size()).isEqualTo(connectableList.size());
    }

    /**
     * Verify registerCallback() call is correctly delegated to {@link BluetoothHapClient} service.
     */
    @Test
    public void registerCallback_verifyIsCalled() {
        final Executor executor = (command -> new Thread(command).start());
        final BluetoothHapClient.Callback callback = new BluetoothHapClient.Callback() {
            @Override
            public void onPresetSelected(@NonNull BluetoothDevice device, int presetIndex,
                    int reason) {

            }

            @Override
            public void onPresetSelectionFailed(@NonNull BluetoothDevice device, int reason) {

            }

            @Override
            public void onPresetSelectionForGroupFailed(int hapGroupId, int reason) {

            }

            @Override
            public void onPresetInfoChanged(@NonNull BluetoothDevice device,
                    @NonNull List<BluetoothHapPresetInfo> presetInfoList, int reason) {

            }

            @Override
            public void onSetPresetNameFailed(@NonNull BluetoothDevice device, int reason) {

            }

            @Override
            public void onSetPresetNameForGroupFailed(int hapGroupId, int reason) {

            }
        };
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        mProfile.registerCallback(executor, callback);

        verify(mService).registerCallback(executor, callback);
    }

    /**
     * Verify unregisterCallback() call is correctly delegated to {@link BluetoothHapClient}
     * service.
     */
    @Test
    public void unregisterCallback_verifyIsCalled() {
        final BluetoothHapClient.Callback callback = new BluetoothHapClient.Callback() {
            @Override
            public void onPresetSelected(@NonNull BluetoothDevice device, int presetIndex,
                    int reason) {

            }

            @Override
            public void onPresetSelectionFailed(@NonNull BluetoothDevice device, int reason) {

            }

            @Override
            public void onPresetSelectionForGroupFailed(int hapGroupId, int reason) {

            }

            @Override
            public void onPresetInfoChanged(@NonNull BluetoothDevice device,
                    @NonNull List<BluetoothHapPresetInfo> presetInfoList, int reason) {

            }

            @Override
            public void onSetPresetNameFailed(@NonNull BluetoothDevice device, int reason) {

            }

            @Override
            public void onSetPresetNameForGroupFailed(int hapGroupId, int reason) {

            }
        };
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        mProfile.unregisterCallback(callback);

        verify(mService).unregisterCallback(callback);
    }

    /**
     * Verify getHapGroup() call is correctly delegated to {@link BluetoothHapClient} service
     * and return correct value.
     */
    @Test
    public void getHapGroup_verifyIsCalledAndReturnCorrectValue() {
        when(mService.getHapGroup(mBluetoothDevice)).thenReturn(TEST_GROUP_ID);
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        final int groupId = mProfile.getHapGroup(mBluetoothDevice);

        verify(mService).getHapGroup(mBluetoothDevice);
        assertThat(groupId).isEqualTo(TEST_GROUP_ID);
    }

    /**
     * Verify getActivePresetIndex() call is correctly delegated to {@link BluetoothHapClient}
     * service and return correct index.
     */
    @Test
    public void getActivePresetIndex_verifyIsCalledAndReturnCorrectValue() {
        when(mService.getActivePresetIndex(mBluetoothDevice)).thenReturn(TEST_PRESET_INDEX);
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        final int activeIndex = mProfile.getActivePresetIndex(mBluetoothDevice);

        verify(mService).getActivePresetIndex(mBluetoothDevice);
        assertThat(activeIndex).isEqualTo(TEST_PRESET_INDEX);
    }

    /**
     * Verify getActivePresetInfo() call is correctly delegated to {@link BluetoothHapClient}
     * service and return correct object.
     */
    @Test
    public void getActivePresetInfo_verifyIsCalledAndReturnCorrectObject() {
        when(mService.getActivePresetInfo(mBluetoothDevice)).thenReturn(mPresetInfo);
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        final BluetoothHapPresetInfo activeInfo = mProfile.getActivePresetInfo(mBluetoothDevice);

        verify(mService).getActivePresetInfo(mBluetoothDevice);
        assertThat(activeInfo).isEqualTo(mPresetInfo);
    }

    /**
     * Verify selectPreset() call is correctly delegated to {@link BluetoothHapClient} service.
     */
    @Test
    public void selectPreset_verifyIsCalled() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        mProfile.selectPreset(mBluetoothDevice, TEST_PRESET_INDEX);

        verify(mService).selectPreset(mBluetoothDevice, TEST_PRESET_INDEX);
    }

    /**
     * Verify selectPresetForGroup() call is correctly delegated to {@link BluetoothHapClient}
     * service.
     */
    @Test
    public void selectPresetForGroup_verifyIsCalled() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        mProfile.selectPresetForGroup(TEST_GROUP_ID, TEST_PRESET_INDEX);

        verify(mService).selectPresetForGroup(TEST_GROUP_ID, TEST_PRESET_INDEX);
    }

    /**
     * Verify switchToNextPreset() call is correctly delegated to {@link BluetoothHapClient}
     * service.
     */
    @Test
    public void switchToNextPreset_verifyIsCalled() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        mProfile.switchToNextPreset(mBluetoothDevice);

        verify(mService).switchToNextPreset(mBluetoothDevice);
    }

    /**
     * Verify switchToNextPresetForGroup() call is correctly delegated to {@link BluetoothHapClient}
     * service.
     */
    @Test
    public void switchToNextPresetForGroup_verifyIsCalled() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        mProfile.switchToNextPresetForGroup(TEST_GROUP_ID);

        verify(mService).switchToNextPresetForGroup(TEST_GROUP_ID);
    }

    /**
     * Verify switchToPreviousPreset() call is correctly delegated to {@link BluetoothHapClient}
     * service.
     */
    @Test
    public void switchToPreviousPreset_verifyIsCalled() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        mProfile.switchToPreviousPreset(mBluetoothDevice);

        verify(mService).switchToPreviousPreset(mBluetoothDevice);
    }

    /**
     * Verify switchToPreviousPresetForGroup() call is correctly delegated to
     * {@link BluetoothHapClient} service.
     */
    @Test
    public void switchToPreviousPresetForGroup_verifyIsCalled() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        mProfile.switchToPreviousPresetForGroup(TEST_GROUP_ID);

        verify(mService).switchToPreviousPresetForGroup(TEST_GROUP_ID);
    }

    /**
     * Verify getPresetInfo() call is correctly delegated to {@link BluetoothHapClient} service and
     * return correct object.
     */
    @Test
    public void getPresetInfo_verifyIsCalledAndReturnCorrectObject() {
        when(mService.getPresetInfo(mBluetoothDevice, TEST_PRESET_INDEX)).thenReturn(mPresetInfo);
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        final BluetoothHapPresetInfo info = mProfile.getPresetInfo(mBluetoothDevice,
                TEST_PRESET_INDEX);

        verify(mService).getPresetInfo(mBluetoothDevice, TEST_PRESET_INDEX);
        assertThat(info).isEqualTo(mPresetInfo);
    }

    /**
     * Verify getAllPresetInfo() call is correctly delegated to {@link BluetoothHapClient} service
     * and return correct list.
     */
    @Test
    public void getAllPresetInfo_verifyIsCalledAndReturnCorrectList() {
        final List<BluetoothHapPresetInfo> testList = Arrays.asList(mPresetInfo, mPresetInfo);
        when(mService.getAllPresetInfo(mBluetoothDevice)).thenReturn(testList);
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        final List<BluetoothHapPresetInfo> infoList = mProfile.getAllPresetInfo(mBluetoothDevice);

        verify(mService).getAllPresetInfo(mBluetoothDevice);
        assertThat(infoList.size()).isEqualTo(testList.size());
    }

    /**
     * Verify setPresetName() call is correctly delegated to {@link BluetoothHapClient} service.
     */
    @Test
    public void setPresetName_verifyIsCalled() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        mProfile.setPresetName(mBluetoothDevice, TEST_PRESET_INDEX, TEST_DEVICE_NAME);

        verify(mService).setPresetName(mBluetoothDevice, TEST_PRESET_INDEX, TEST_DEVICE_NAME);
    }

    /**
     * Verify setPresetNameForGroup() call is correctly delegated to {@link BluetoothHapClient}
     * service.
     */
    @Test
    public void setPresetNameForGroup_verifyIsCalled() {
        mServiceListener.onServiceConnected(BluetoothProfile.HAP_CLIENT, mService);

        mProfile.setPresetNameForGroup(TEST_GROUP_ID, TEST_PRESET_INDEX, TEST_DEVICE_NAME);

        verify(mService).setPresetNameForGroup(TEST_GROUP_ID, TEST_PRESET_INDEX, TEST_DEVICE_NAME);
    }
}
