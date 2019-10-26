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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioSystem;
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

    private BluetoothDevice mFakeBtDevice;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();

        mMockAudioService = mock(AudioService.class);
        mSpyDevInventory = spy(new AudioDeviceInventory());
        mAudioDeviceBroker = new AudioDeviceBroker(mContext, mMockAudioService, mSpyDevInventory);
        mSpyDevInventory.setDeviceBroker(mAudioDeviceBroker);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mFakeBtDevice = adapter.getRemoteDevice("00:01:02:03:04:05");
        Assert.assertNotNull("invalid null BT device", mFakeBtDevice);
    }

    @After
    public void tearDown() throws Exception { }

    @Test
    public void testSetUpAndTearDown() { }

    /**
     * Verify call to postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent() for connection
     * calls into AudioDeviceInventory with the right params
     * @throws Exception
     */
    @Test
    public void testPostA2dpDeviceConnectionChange() throws Exception {
        Log.i(TAG, "testPostA2dpDeviceConnectionChange");
        Assert.assertNotNull("invalid null BT device", mFakeBtDevice);

        mAudioDeviceBroker.postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(mFakeBtDevice,
                BluetoothProfile.STATE_CONNECTED, BluetoothProfile.A2DP, true, 1);
        Thread.sleep(MAX_MESSAGE_HANDLING_DELAY_MS);
        verify(mSpyDevInventory, times(1)).setBluetoothA2dpDeviceConnectionState(
                any(BluetoothDevice.class),
                ArgumentMatchers.eq(BluetoothProfile.STATE_CONNECTED) /*state*/,
                ArgumentMatchers.eq(BluetoothProfile.A2DP) /*profile*/,
                ArgumentMatchers.eq(true) /*suppressNoisyIntent*/, anyInt() /*musicDevice*/,
                ArgumentMatchers.eq(1) /*a2dpVolume*/
        );
    }

    /**
     * Verify call to postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent() for
     *    connection > pause > disconnection > connection
     * keeps the device connected
     * @throws Exception
     */
    @Test
    public void testA2dpDeviceConnectionDisconnectionConnectionChange() throws Exception {
        Log.i(TAG, "testA2dpDeviceConnectionDisconnectionConnectionChange");

        doTestConnectionDisconnectionReconnection(0);
    }

    /**
     * Verify device disconnection and reconnection within the BECOMING_NOISY window
     * @throws Exception
     */
    @Test
    public void testA2dpDeviceReconnectionWithinBecomingNoisyDelay() throws Exception {
        Log.i(TAG, "testA2dpDeviceReconnectionWithinBecomingNoisyDelay");

        doTestConnectionDisconnectionReconnection(AudioService.BECOMING_NOISY_DELAY_MS / 2);
    }

    /**
     * Verify connecting an A2DP sink will call into AudioService to unmute media
     */
    @Test
    public void testA2dpConnectionUnmutesMedia() throws Exception {
        Log.i(TAG, "testA2dpConnectionUnmutesMedia");
        Assert.assertNotNull("invalid null BT device", mFakeBtDevice);

        mAudioDeviceBroker.postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(mFakeBtDevice,
                BluetoothProfile.STATE_CONNECTED, BluetoothProfile.A2DP, true, 1);
        Thread.sleep(MAX_MESSAGE_HANDLING_DELAY_MS);
        verify(mMockAudioService, times(1)).postAccessoryPlugMediaUnmute(
                ArgumentMatchers.eq(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP));

    }

    private void doTestConnectionDisconnectionReconnection(int delayAfterDisconnection)
            throws Exception {
        when(mMockAudioService.getDeviceForStream(AudioManager.STREAM_MUSIC))
                .thenReturn(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);
        when(mMockAudioService.isInCommunication()).thenReturn(false);
        when(mMockAudioService.hasMediaDynamicPolicy()).thenReturn(false);
        when(mMockAudioService.hasAudioFocusUsers()).thenReturn(false);

        // first connection
        mAudioDeviceBroker.postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(mFakeBtDevice,
                BluetoothProfile.STATE_CONNECTED, BluetoothProfile.A2DP, true, 1);
        Thread.sleep(MAX_MESSAGE_HANDLING_DELAY_MS);

        // disconnection
        mAudioDeviceBroker.postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(mFakeBtDevice,
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.A2DP, false, -1);
        if (delayAfterDisconnection > 0) {
            Thread.sleep(delayAfterDisconnection);
        }

        // reconnection
        mAudioDeviceBroker.postBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(mFakeBtDevice,
                BluetoothProfile.STATE_CONNECTED, BluetoothProfile.A2DP, true, 2);
        Thread.sleep(AudioService.BECOMING_NOISY_DELAY_MS + MAX_MESSAGE_HANDLING_DELAY_MS);

        // Verify disconnection has been cancelled and we're seeing two connections attempts,
        // with the device connected at the end of the test
        verify(mSpyDevInventory, times(2)).onSetA2dpSinkConnectionState(
                any(BtHelper.BluetoothA2dpDeviceInfo.class),
                ArgumentMatchers.eq(BluetoothProfile.STATE_CONNECTED));
        Assert.assertTrue("Mock device not connected",
                mSpyDevInventory.isA2dpDeviceConnected(mFakeBtDevice));
    }
}
