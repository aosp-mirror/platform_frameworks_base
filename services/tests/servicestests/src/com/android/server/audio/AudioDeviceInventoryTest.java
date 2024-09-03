/*
 * Copyright 2024 The Android Open Source Project
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
package com.android.server.audio;

import static com.android.media.audio.Flags.asDeviceConnectionFailure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioDeviceAttributes;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.platform.test.annotations.Presubmit;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;

@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class AudioDeviceInventoryTest {

    private static final String TAG = "AudioDeviceInventoryTest";

    @Mock private AudioService mMockAudioService;
    private AudioDeviceInventory mDevInventory;
    @Spy private AudioDeviceBroker mSpyAudioDeviceBroker;
    @Spy private AudioSystemAdapter mSpyAudioSystem;

    private SystemServerAdapter mSystemServer;

    private BluetoothDevice mFakeBtDevice;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        mMockAudioService = mock(AudioService.class);
        mSpyAudioSystem = spy(new NoOpAudioSystemAdapter());
        mDevInventory = new AudioDeviceInventory(mSpyAudioSystem);
        mSystemServer = new NoOpSystemServerAdapter();
        mSpyAudioDeviceBroker = spy(new AudioDeviceBroker(context, mMockAudioService, mDevInventory,
                mSystemServer, mSpyAudioSystem));
        mDevInventory.setDeviceBroker(mSpyAudioDeviceBroker);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mFakeBtDevice = adapter.getRemoteDevice("00:01:02:03:04:05");
    }

    @After
    public void tearDown() throws Exception { }

    /**
     * test that for DEVICE_OUT_BLUETOOTH_A2DP devices, when the device connects, it's only
     * added to the connected devices when the connection through AudioSystem is successful
     * @throws Exception on error
     */
    @Test
    public void testSetDeviceConnectionStateA2dp() throws Exception {
        Log.i(TAG, "starting testSetDeviceConnectionStateA2dp");
        assertTrue("collection of connected devices not empty at start",
                mDevInventory.getConnectedDevices().isEmpty());

        final AudioDeviceAttributes ada = new AudioDeviceAttributes(
                AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, mFakeBtDevice.getAddress());
        AudioDeviceBroker.BtDeviceInfo btInfo =
                new AudioDeviceBroker.BtDeviceInfo(mFakeBtDevice, BluetoothProfile.A2DP,
                        BluetoothProfile.STATE_CONNECTED, AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                        AudioSystem.AUDIO_FORMAT_SBC);

        // test that no device is added when AudioSystem returns AUDIO_STATUS_ERROR
        // when setDeviceConnectionState is called for the connection
        // NOTE: for now this is only when flag asDeviceConnectionFailure is true
        if (asDeviceConnectionFailure()) {
            when(mSpyAudioSystem.setDeviceConnectionState(ada, AudioSystem.DEVICE_STATE_AVAILABLE,
                    AudioSystem.AUDIO_FORMAT_DEFAULT))
                    .thenReturn(AudioSystem.AUDIO_STATUS_ERROR);
            runWithBluetoothPrivilegedPermission(
                    () ->  mDevInventory.onSetBtActiveDevice(/*btInfo*/ btInfo,
                        /*codec*/ AudioSystem.AUDIO_FORMAT_DEFAULT, AudioManager.STREAM_MUSIC));

            assertEquals(0, mDevInventory.getConnectedDevices().size());
        }

        // test that the device is added when AudioSystem returns AUDIO_STATUS_OK
        // when setDeviceConnectionState is called for the connection
        when(mSpyAudioSystem.setDeviceConnectionState(ada, AudioSystem.DEVICE_STATE_AVAILABLE,
                AudioSystem.AUDIO_FORMAT_DEFAULT))
                .thenReturn(AudioSystem.AUDIO_STATUS_OK);
        runWithBluetoothPrivilegedPermission(
                () ->  mDevInventory.onSetBtActiveDevice(/*btInfo*/ btInfo,
                    /*codec*/ AudioSystem.AUDIO_FORMAT_DEFAULT, AudioManager.STREAM_MUSIC));
        assertEquals(1, mDevInventory.getConnectedDevices().size());
    }

    // TODO add test for hearing aid

    // TODO add test for BLE

    /**
     * Executes a Runnable while holding the BLUETOOTH_PRIVILEGED permission
     * @param toRunWithPermission the runnable to run with BT privileges
     */
    private void runWithBluetoothPrivilegedPermission(Runnable toRunWithPermission) {
        try {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .adoptShellPermissionIdentity(Manifest.permission.BLUETOOTH_PRIVILEGED);
            toRunWithPermission.run();
        } finally {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }
}
