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

package com.android.server.accessibility;

import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioDevicePort;
import android.media.AudioManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.messages.nano.SystemMessageProto;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Tests for the {@link HearingDevicePhoneCallNotificationController}.
 */
@RunWith(AndroidJUnit4.class)
public class HearingDevicePhoneCallNotificationControllerTest {
    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    private static final String TEST_ADDRESS = "55:66:77:88:99:AA";

    private final Application mApplication = ApplicationProvider.getApplicationContext();
    @Spy
    private final Context mContext = mApplication.getApplicationContext();
    private final Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();

    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private NotificationManager mNotificationManager;
    @Mock
    private AudioManager mAudioManager;
    private HearingDevicePhoneCallNotificationController mController;
    private TestCallStateListener mTestCallStateListener;

    @Before
    public void setUp() {
        mInstrumentation.getUiAutomation().adoptShellPermissionIdentity(BLUETOOTH_PRIVILEGED);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        when(mContext.getSystemService(NotificationManager.class)).thenReturn(mNotificationManager);
        when(mContext.getSystemService(AudioManager.class)).thenReturn(mAudioManager);

        mTestCallStateListener = new TestCallStateListener(mContext);
        mController = new HearingDevicePhoneCallNotificationController(mContext,
                mTestCallStateListener);
        mController.startListenForCallState();
    }

    @Test
    public void startListenForCallState_callbackNotNull() {
        Mockito.reset(mTelephonyManager);
        mController = new HearingDevicePhoneCallNotificationController(mContext);
        ArgumentCaptor<TelephonyCallback> listenerCaptor = ArgumentCaptor.forClass(
                TelephonyCallback.class);

        mController.startListenForCallState();

        verify(mTelephonyManager).registerTelephonyCallback(any(Executor.class),
                listenerCaptor.capture());
        TelephonyCallback callback = listenerCaptor.getValue();
        assertThat(callback).isNotNull();
    }

    @Test
    public void onCallStateChanged_stateOffHook_hapDevice_showNotification() {
        AudioDeviceInfo hapDeviceInfo = createAudioDeviceInfo(TEST_ADDRESS,
                AudioManager.DEVICE_OUT_BLE_HEADSET);
        when(mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(
                new AudioDeviceInfo[]{hapDeviceInfo});
        when(mAudioManager.getAvailableCommunicationDevices()).thenReturn(List.of(hapDeviceInfo));

        mTestCallStateListener.onCallStateChanged(TelephonyManager.CALL_STATE_OFFHOOK);

        verify(mNotificationManager).notify(
                eq(SystemMessageProto.SystemMessage.NOTE_HEARING_DEVICE_INPUT_SWITCH), any());
    }

    @Test
    public void onCallStateChanged_stateOffHook_a2dpDevice_noNotification() {
        AudioDeviceInfo a2dpDeviceInfo = createAudioDeviceInfo(TEST_ADDRESS,
                AudioManager.DEVICE_OUT_BLUETOOTH_A2DP);
        when(mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(
                new AudioDeviceInfo[]{a2dpDeviceInfo});
        when(mAudioManager.getAvailableCommunicationDevices()).thenReturn(List.of(a2dpDeviceInfo));

        mTestCallStateListener.onCallStateChanged(TelephonyManager.CALL_STATE_OFFHOOK);

        verify(mNotificationManager, never()).notify(
                eq(SystemMessageProto.SystemMessage.NOTE_HEARING_DEVICE_INPUT_SWITCH), any());
    }

    @Test
    public void onCallStateChanged_stateOffHookThenIdle_hapDeviceInfo_cancelNotification() {
        AudioDeviceInfo hapDeviceInfo = createAudioDeviceInfo(TEST_ADDRESS,
                AudioManager.DEVICE_OUT_BLE_HEADSET);
        when(mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(
                new AudioDeviceInfo[]{hapDeviceInfo});
        when(mAudioManager.getAvailableCommunicationDevices()).thenReturn(List.of(hapDeviceInfo));

        mTestCallStateListener.onCallStateChanged(TelephonyManager.CALL_STATE_OFFHOOK);
        mTestCallStateListener.onCallStateChanged(TelephonyManager.CALL_STATE_IDLE);

        verify(mNotificationManager).cancel(
                eq(SystemMessageProto.SystemMessage.NOTE_HEARING_DEVICE_INPUT_SWITCH));
    }

    private AudioDeviceInfo createAudioDeviceInfo(String address, int type) {
        AudioDevicePort audioDevicePort = mock(AudioDevicePort.class);
        doReturn(type).when(audioDevicePort).type();
        doReturn(address).when(audioDevicePort).address();
        doReturn("testDevice").when(audioDevicePort).name();

        return new AudioDeviceInfo(audioDevicePort);
    }

    /**
     * For easier testing for CallStateListener, override methods that contain final object.
     */
    private static class TestCallStateListener extends
            HearingDevicePhoneCallNotificationController.CallStateListener {

        TestCallStateListener(@NonNull Context context) {
            super(context);
        }

        @Override
        boolean isHapClientSupported() {
            return true;
        }

        @Override
        boolean isHapClientDevice(BluetoothAdapter bluetoothAdapter, AudioDeviceInfo info) {
            return TEST_ADDRESS.equals(info.getAddress());
        }
    }
}
