/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.voicerecognition;

import static com.android.systemui.car.voicerecognition.ConnectedDeviceVoiceRecognitionNotifier.INVALID_VALUE;
import static com.android.systemui.car.voicerecognition.ConnectedDeviceVoiceRecognitionNotifier.VOICE_RECOGNITION_STARTED;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothHeadsetClient;
import android.content.Intent;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class ConnectedDeviceVoiceRecognitionNotifierTest extends SysuiTestCase {

    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private ConnectedDeviceVoiceRecognitionNotifier mVoiceRecognitionNotifier;
    private Handler mTestHandler;

    @Before
    public void setUp() throws Exception {
        TestableLooper testableLooper = TestableLooper.get(this);
        mTestHandler = spy(new Handler(testableLooper.getLooper()));
        mVoiceRecognitionNotifier = new ConnectedDeviceVoiceRecognitionNotifier(
                mContext, mTestHandler);
        mVoiceRecognitionNotifier.onBootCompleted();
    }

    @Test
    public void testReceiveIntent_started_showToast() {
        Intent intent = new Intent(BluetoothHeadsetClient.ACTION_AG_EVENT);
        intent.putExtra(BluetoothHeadsetClient.EXTRA_VOICE_RECOGNITION, VOICE_RECOGNITION_STARTED);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        waitForIdleSync();

        verify(mTestHandler).post(any());
    }

    @Test
    public void testReceiveIntent_invalidExtra_noToast() {
        Intent intent = new Intent(BluetoothHeadsetClient.ACTION_AG_EVENT);
        intent.putExtra(BluetoothHeadsetClient.EXTRA_VOICE_RECOGNITION, INVALID_VALUE);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        waitForIdleSync();

        verify(mTestHandler, never()).post(any());
    }

    @Test
    public void testReceiveIntent_noExtra_noToast() {
        Intent intent = new Intent(BluetoothHeadsetClient.ACTION_AG_EVENT);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        waitForIdleSync();

        verify(mTestHandler, never()).post(any());
    }

    @Test
    public void testReceiveIntent_invalidIntent_noToast() {
        Intent intent = new Intent(BluetoothHeadsetClient.ACTION_AUDIO_STATE_CHANGED);
        mContext.sendBroadcast(intent, BLUETOOTH_PERM);
        waitForIdleSync();

        verify(mTestHandler, never()).post(any());
    }
}
