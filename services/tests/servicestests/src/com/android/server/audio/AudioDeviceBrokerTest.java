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
package com.android.server.audio;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceAttributes;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.BluetoothProfileConnectionInfo;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Spy;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class AudioDeviceBrokerTest {

    private static final String TAG = "AudioDeviceBrokerTest";
    private static final int MAX_MESSAGE_HANDLING_DELAY_MS = 100;

    private Context mContext;
    // the actual class under test
    private AudioDeviceBroker mAudioDeviceBroker;

    @Mock private AudioService mMockAudioService;
    @Spy private AudioDeviceInventory mSpyDevInventory;
    @Spy private AudioSystemAdapter mSpyAudioSystem;
    @Spy private SystemServerAdapter mSpySystemServer;

    private BluetoothDevice mFakeBtDevice;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();

        mMockAudioService = mock(AudioService.class);
        mSpyAudioSystem = spy(new NoOpAudioSystemAdapter());
        mSpyDevInventory = spy(new AudioDeviceInventory(mSpyAudioSystem));
        mSpySystemServer = spy(new NoOpSystemServerAdapter());
        mAudioDeviceBroker = new AudioDeviceBroker(mContext, mMockAudioService, mSpyDevInventory,
                mSpySystemServer);
        mSpyDevInventory.setDeviceBroker(mAudioDeviceBroker);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mFakeBtDevice = adapter.getRemoteDevice("00:01:02:03:04:05");
        Assert.assertNotNull("invalid null BT device", mFakeBtDevice);
    }

    @After
    public void tearDown() throws Exception { }

//    @Test
//    public void testSetUpAndTearDown() { }

    /**
     * postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent() for connection:
     * - verify it calls into AudioDeviceInventory with the right params
     * - verify it calls into AudioSystem and stays connected (no 2nd call to disconnect)
     * @throws Exception
     */
    @Test
    public void testPostA2dpDeviceConnectionChange() throws Exception {
        Log.i(TAG, "starting testPostA2dpDeviceConnectionChange");
        Assert.assertNotNull("invalid null BT device", mFakeBtDevice);

        mAudioDeviceBroker.queueOnBluetoothActiveDeviceChanged(
                new AudioDeviceBroker.BtDeviceChangedData(mFakeBtDevice, null,
                    BluetoothProfileConnectionInfo.createA2dpInfo(true, 1), "testSource"));
        Thread.sleep(2 * MAX_MESSAGE_HANDLING_DELAY_MS);
        verify(mSpyDevInventory, times(1)).setBluetoothActiveDevice(
                any(AudioDeviceBroker.BtDeviceInfo.class)
        );

        // verify the connection was reported to AudioSystem
        checkSingleSystemConnection(mFakeBtDevice);
    }

    /**
     * Verify call to postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent() for
     *    connection > pause > disconnection > connection
     * keeps the device connected
     * @throws Exception
     */
    @Test
    public void testA2dpDeviceConnectionDisconnectionConnectionChange() throws Exception {
        Log.i(TAG, "starting testA2dpDeviceConnectionDisconnectionConnectionChange");

        doTestConnectionDisconnectionReconnection(0, false,
                // cannot guarantee single connection since commands are posted in separate thread
                // than they are processed
                false);
    }

    /**
     * Verify device disconnection and reconnection within the BECOMING_NOISY window
     * in the absence of media playback
     * @throws Exception
     */
    @Test
    public void testA2dpDeviceReconnectionWithinBecomingNoisyDelay() throws Exception {
        Log.i(TAG, "starting testA2dpDeviceReconnectionWithinBecomingNoisyDelay");

        doTestConnectionDisconnectionReconnection(AudioService.BECOMING_NOISY_DELAY_MS / 2,
                false,
                // do not check single connection since the connection command will come much
                // after the disconnection command
                false);
    }

    /**
     * Same as testA2dpDeviceConnectionDisconnectionConnectionChange() but with mock media playback
     * @throws Exception
     */
    @Test
    public void testA2dpConnectionDisconnectionConnectionChange_MediaPlayback() throws Exception {
        Log.i(TAG, "starting testA2dpConnectionDisconnectionConnectionChange_MediaPlayback");

        doTestConnectionDisconnectionReconnection(0, true,
                // guarantee single connection since because of media playback the disconnection
                // is supposed to be delayed, and thus cancelled because of the connection
                true);
    }

    /**
     * Same as testA2dpDeviceReconnectionWithinBecomingNoisyDelay() but with mock media playback
     * @throws Exception
     */
    @Test
    public void testA2dpReconnectionWithinBecomingNoisyDelay_MediaPlayback() throws Exception {
        Log.i(TAG, "starting testA2dpReconnectionWithinBecomingNoisyDelay_MediaPlayback");

        doTestConnectionDisconnectionReconnection(AudioService.BECOMING_NOISY_DELAY_MS / 2,
                true,
                // guarantee single connection since because of media playback the disconnection
                // is supposed to be delayed, and thus cancelled because of the connection
                true);
    }

    /**
     * Test that device wired state intents are broadcasted on connection state change
     * @throws Exception
     */
    @Test
    public void testSetWiredDeviceConnectionState() throws Exception {
        Log.i(TAG, "starting postSetWiredDeviceConnectionState");

        final String address = "testAddress";
        final String name = "testName";
        final String caller = "testCaller";

        doNothing().when(mSpySystemServer).broadcastStickyIntentToCurrentProfileGroup(
                any(Intent.class));

        mSpyDevInventory.setWiredDeviceConnectionState(new AudioDeviceAttributes(
                        AudioSystem.DEVICE_OUT_WIRED_HEADSET, address, name),
                AudioService.CONNECTION_STATE_CONNECTED, caller);
        Thread.sleep(MAX_MESSAGE_HANDLING_DELAY_MS);

        // Verify that the sticky intent is broadcasted
        verify(mSpySystemServer, times(1)).broadcastStickyIntentToCurrentProfileGroup(
                any(Intent.class));
    }

    private void doTestConnectionDisconnectionReconnection(int delayAfterDisconnection,
            boolean mockMediaPlayback, boolean guaranteeSingleConnection) throws Exception {
        when(mMockAudioService.getDeviceForStream(AudioManager.STREAM_MUSIC))
                .thenReturn(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);
        when(mMockAudioService.isInCommunication()).thenReturn(false);
        when(mMockAudioService.hasMediaDynamicPolicy()).thenReturn(false);
        when(mMockAudioService.hasAudioFocusUsers()).thenReturn(false);

        ((NoOpAudioSystemAdapter) mSpyAudioSystem).configureIsStreamActive(mockMediaPlayback);

        // first connection: ensure the device is connected as a starting condition for the test
        mAudioDeviceBroker.queueOnBluetoothActiveDeviceChanged(
                new AudioDeviceBroker.BtDeviceChangedData(mFakeBtDevice, null,
                    BluetoothProfileConnectionInfo.createA2dpInfo(true, 1), "testSource"));
        Thread.sleep(MAX_MESSAGE_HANDLING_DELAY_MS);

        // disconnection
        mAudioDeviceBroker.queueOnBluetoothActiveDeviceChanged(
                new AudioDeviceBroker.BtDeviceChangedData(null, mFakeBtDevice,
                    BluetoothProfileConnectionInfo.createA2dpInfo(false, -1), "testSource"));
        if (delayAfterDisconnection > 0) {
            Thread.sleep(delayAfterDisconnection);
        }

        // reconnection
        mAudioDeviceBroker.queueOnBluetoothActiveDeviceChanged(
                new AudioDeviceBroker.BtDeviceChangedData(mFakeBtDevice, null,
                    BluetoothProfileConnectionInfo.createA2dpInfo(true, 2), "testSource"));
        Thread.sleep(AudioService.BECOMING_NOISY_DELAY_MS + MAX_MESSAGE_HANDLING_DELAY_MS);

        // Verify disconnection has been cancelled and we're seeing two connections attempts,
        // with the device connected at the end of the test
        verify(mSpyDevInventory, times(2)).onSetBtActiveDevice(
                any(AudioDeviceBroker.BtDeviceInfo.class), anyInt());
        Assert.assertTrue("Mock device not connected",
                mSpyDevInventory.isA2dpDeviceConnected(mFakeBtDevice));

        if (guaranteeSingleConnection) {
            // when the disconnection was expected to be cancelled, there should have been a single
            //  call to AudioSystem to declare the device connected (available)
            checkSingleSystemConnection(mFakeBtDevice);
        }
    }

    /**
     * Verifies the given device was reported to AudioSystem exactly once as available
     * @param btDevice
     * @throws Exception
     */
    private void checkSingleSystemConnection(BluetoothDevice btDevice) throws Exception {
        final String expectedName = btDevice.getName() == null ? "" : btDevice.getName();
        AudioDeviceAttributes expected = new AudioDeviceAttributes(
                AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, btDevice.getAddress(), expectedName);
        verify(mSpyAudioSystem, times(1)).setDeviceConnectionState(
                ArgumentMatchers.argThat(x -> x.equalTypeAddress(expected)),
                ArgumentMatchers.eq(AudioSystem.DEVICE_STATE_AVAILABLE),
                anyInt() /*codec*/);
    }
}
