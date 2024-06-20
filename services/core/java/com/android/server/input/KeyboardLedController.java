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

package com.android.server.input;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.SensorPrivacyManager;
import android.hardware.SensorPrivacyManager.Sensors;
import android.hardware.input.InputManager;
import android.hardware.lights.Light;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;
import android.view.InputDevice;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * This class is used to control the light of keyboard.
 */
public final class KeyboardLedController implements InputManager.InputDeviceListener {

    private static final String TAG = KeyboardLedController.class.getSimpleName();
    private static final int MSG_UPDATE_EXISTING_DEVICES = 1;
    private static final int MSG_UPDATE_MIC_MUTE_LED_STATE = 2;

    private final Context mContext;
    private final Handler mHandler;
    private final NativeInputManagerService mNative;
    private final SparseArray<InputDevice> mKeyboardsWithMicMuteLed = new SparseArray<>();
    @NonNull
    private InputManager mInputManager;
    @NonNull
    private SensorPrivacyManager mSensorPrivacyManager;
    @NonNull
    private AudioManager mAudioManager;
    private BroadcastReceiver mMicrophoneMuteChangedIntentReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Message msg =  Message.obtain(mHandler, MSG_UPDATE_MIC_MUTE_LED_STATE);
                    mHandler.sendMessage(msg);
                }
            };

    KeyboardLedController(Context context, Looper looper,
            NativeInputManagerService nativeService) {
        mContext = context;
        mNative = nativeService;
        mHandler = new Handler(looper, this::handleMessage);
    }

    private boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_UPDATE_EXISTING_DEVICES:
                for (int deviceId : (int[]) msg.obj) {
                    onInputDeviceAdded(deviceId);
                }
                return true;
            case MSG_UPDATE_MIC_MUTE_LED_STATE:
                updateMicMuteLedState();
                return true;
        }
        return false;
    }

    private void updateMicMuteLedState() {
        // We determine if the microphone is muted by querying both the hardware state of the
        // microphone and the microphone sensor privacy hardware and sensor toggles
        boolean isMicrophoneMute = mAudioManager.isMicrophoneMute()
                || mSensorPrivacyManager.areAnySensorPrivacyTogglesEnabled(Sensors.MICROPHONE);
        int color = isMicrophoneMute ? Color.WHITE : Color.TRANSPARENT;
        for (int i = 0; i < mKeyboardsWithMicMuteLed.size(); i++) {
            InputDevice device = mKeyboardsWithMicMuteLed.valueAt(i);
            if (device != null) {
                int deviceId = device.getId();
                Light light = getKeyboardMicMuteLight(device);
                if (light != null) {
                    mNative.setLightColor(deviceId, light.getId(), color);
                }
            }
        }
    }

    private Light getKeyboardMicMuteLight(InputDevice device) {
        for (Light light : device.getLightsManager().getLights()) {
            if (light.getType() == Light.LIGHT_TYPE_KEYBOARD_MIC_MUTE
                    && light.hasBrightnessControl()) {
                return light;
            }
        }
        return null;
    }

    /** Called when the system is ready for us to start third-party code. */
    public void systemRunning() {
        mSensorPrivacyManager = Objects.requireNonNull(
                mContext.getSystemService(SensorPrivacyManager.class));
        mInputManager = Objects.requireNonNull(mContext.getSystemService(InputManager.class));
        mAudioManager = Objects.requireNonNull(mContext.getSystemService(AudioManager.class));
        mInputManager.registerInputDeviceListener(this, mHandler);
        Message msg = Message.obtain(mHandler, MSG_UPDATE_EXISTING_DEVICES,
                mInputManager.getInputDeviceIds());
        mHandler.sendMessage(msg);
        mContext.registerReceiverAsUser(
                mMicrophoneMuteChangedIntentReceiver,
                UserHandle.ALL,
                new IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED),
                null,
                mHandler);
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        onInputDeviceChanged(deviceId);
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        mKeyboardsWithMicMuteLed.remove(deviceId);
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        InputDevice inputDevice = mInputManager.getInputDevice(deviceId);
        if (inputDevice == null) {
            return;
        }
        if (getKeyboardMicMuteLight(inputDevice) != null) {
            mKeyboardsWithMicMuteLed.put(deviceId, inputDevice);
            Message msg = Message.obtain(mHandler, MSG_UPDATE_MIC_MUTE_LED_STATE);
            mHandler.sendMessage(msg);
        }
    }

    /** Dump the diagnostic information */
    public void dump(PrintWriter pw) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        ipw.println(TAG + ": " + mKeyboardsWithMicMuteLed.size() + " keyboard mic mute lights");
        ipw.increaseIndent();
        for (int i = 0; i < mKeyboardsWithMicMuteLed.size(); i++) {
            InputDevice inputDevice = mKeyboardsWithMicMuteLed.valueAt(i);
            ipw.println(i + " " + inputDevice.getName() + ": "
                    + getKeyboardMicMuteLight(inputDevice).toString());
        }
        ipw.decreaseIndent();
    }
}
