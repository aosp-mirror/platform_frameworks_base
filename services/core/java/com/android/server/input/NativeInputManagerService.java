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

package com.android.server.input;

import android.content.Context;
import android.hardware.display.DisplayViewport;
import android.hardware.input.InputSensorInfo;
import android.hardware.lights.Light;
import android.os.IBinder;
import android.os.MessageQueue;
import android.util.SparseArray;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.PointerIcon;
import android.view.VerifiedInputEvent;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/**
 * An interface for the native methods of InputManagerService. We use a public interface so that
 * this can be mocked for testing by Mockito.
 */
@VisibleForTesting
public interface NativeInputManagerService {

    void start();

    void setDisplayViewports(DisplayViewport[] viewports);

    int getScanCodeState(int deviceId, int sourceMask, int scanCode);

    int getKeyCodeState(int deviceId, int sourceMask, int keyCode);

    int getSwitchState(int deviceId, int sourceMask, int sw);

    boolean hasKeys(int deviceId, int sourceMask, int[] keyCodes, boolean[] keyExists);

    int getKeyCodeForKeyLocation(int deviceId, int locationKeyCode);

    InputChannel createInputChannel(String name);

    InputChannel createInputMonitor(int displayId, String name, int pid);

    void removeInputChannel(IBinder connectionToken);

    void pilferPointers(IBinder token);

    void setInputFilterEnabled(boolean enable);

    boolean setInTouchMode(boolean inTouchMode, int pid, int uid, boolean hasPermission);

    void setMaximumObscuringOpacityForTouch(float opacity);

    void setBlockUntrustedTouchesMode(int mode);

    int injectInputEvent(InputEvent event, boolean injectIntoUid, int uid, int syncMode,
            int timeoutMillis, int policyFlags);

    VerifiedInputEvent verifyInputEvent(InputEvent event);

    void toggleCapsLock(int deviceId);

    void displayRemoved(int displayId);

    void setInputDispatchMode(boolean enabled, boolean frozen);

    void setSystemUiLightsOut(boolean lightsOut);

    void setFocusedApplication(int displayId, InputApplicationHandle application);

    void setFocusedDisplay(int displayId);

    boolean transferTouchFocus(IBinder fromChannelToken, IBinder toChannelToken,
            boolean isDragDrop);

    boolean transferTouch(IBinder destChannelToken);

    void setPointerSpeed(int speed);

    void setPointerAcceleration(float acceleration);

    void setShowTouches(boolean enabled);

    void setInteractive(boolean interactive);

    void reloadCalibration();

    void vibrate(int deviceId, long[] pattern, int[] amplitudes, int repeat, int token);

    void vibrateCombined(int deviceId, long[] pattern, SparseArray<int[]> amplitudes,
            int repeat, int token);

    void cancelVibrate(int deviceId, int token);

    boolean isVibrating(int deviceId);

    int[] getVibratorIds(int deviceId);

    int getBatteryCapacity(int deviceId);

    int getBatteryStatus(int deviceId);

    List<Light> getLights(int deviceId);

    int getLightPlayerId(int deviceId, int lightId);

    int getLightColor(int deviceId, int lightId);

    void setLightPlayerId(int deviceId, int lightId, int playerId);

    void setLightColor(int deviceId, int lightId, int color);

    void reloadKeyboardLayouts();

    void reloadDeviceAliases();

    String dump();

    void monitor();

    boolean isInputDeviceEnabled(int deviceId);

    void enableInputDevice(int deviceId);

    void disableInputDevice(int deviceId);

    void setPointerIconType(int iconId);

    void reloadPointerIcons();

    void setCustomPointerIcon(PointerIcon icon);

    void requestPointerCapture(IBinder windowToken, boolean enabled);

    boolean canDispatchToDisplay(int deviceId, int displayId);

    void notifyPortAssociationsChanged();

    void changeUniqueIdAssociation();

    void notifyPointerDisplayIdChanged();

    void setDisplayEligibilityForPointerCapture(int displayId, boolean enabled);

    void setMotionClassifierEnabled(boolean enabled);

    InputSensorInfo[] getSensorList(int deviceId);

    boolean flushSensor(int deviceId, int sensorType);

    boolean enableSensor(int deviceId, int sensorType, int samplingPeriodUs,
            int maxBatchReportLatencyUs);

    void disableSensor(int deviceId, int sensorType);

    void cancelCurrentTouch();

    /** The native implementation of InputManagerService methods. */
    class NativeImpl implements NativeInputManagerService {
        /** Pointer to native input manager service object, used by native code. */
        @SuppressWarnings({"unused", "FieldCanBeLocal"})
        private final long mPtr;

        NativeImpl(InputManagerService service, Context context, MessageQueue messageQueue) {
            mPtr = init(service, context, messageQueue);
        }

        private native long init(InputManagerService service, Context context,
                MessageQueue messageQueue);

        @Override
        public native void start();

        @Override
        public native void setDisplayViewports(DisplayViewport[] viewports);

        @Override
        public native int getScanCodeState(int deviceId, int sourceMask, int scanCode);

        @Override
        public native int getKeyCodeState(int deviceId, int sourceMask, int keyCode);

        @Override
        public native int getSwitchState(int deviceId, int sourceMask, int sw);

        @Override
        public native boolean hasKeys(int deviceId, int sourceMask, int[] keyCodes,
                boolean[] keyExists);

        @Override
        public native int getKeyCodeForKeyLocation(int deviceId, int locationKeyCode);

        @Override
        public native InputChannel createInputChannel(String name);

        @Override
        public native InputChannel createInputMonitor(int displayId, String name, int pid);

        @Override
        public native void removeInputChannel(IBinder connectionToken);

        @Override
        public native void pilferPointers(IBinder token);

        @Override
        public native void setInputFilterEnabled(boolean enable);

        @Override
        public native boolean setInTouchMode(boolean inTouchMode, int pid, int uid,
                boolean hasPermission);

        @Override
        public native void setMaximumObscuringOpacityForTouch(float opacity);

        @Override
        public native void setBlockUntrustedTouchesMode(int mode);

        @Override
        public native int injectInputEvent(InputEvent event, boolean injectIntoUid, int uid,
                int syncMode,
                int timeoutMillis, int policyFlags);

        @Override
        public native VerifiedInputEvent verifyInputEvent(InputEvent event);

        @Override
        public native void toggleCapsLock(int deviceId);

        @Override
        public native void displayRemoved(int displayId);

        @Override
        public native void setInputDispatchMode(boolean enabled, boolean frozen);

        @Override
        public native void setSystemUiLightsOut(boolean lightsOut);

        @Override
        public native void setFocusedApplication(int displayId, InputApplicationHandle application);

        @Override
        public native void setFocusedDisplay(int displayId);

        @Override
        public native boolean transferTouchFocus(IBinder fromChannelToken, IBinder toChannelToken,
                boolean isDragDrop);

        @Override
        public native boolean transferTouch(IBinder destChannelToken);

        @Override
        public native void setPointerSpeed(int speed);

        @Override
        public native void setPointerAcceleration(float acceleration);

        @Override
        public native void setShowTouches(boolean enabled);

        @Override
        public native void setInteractive(boolean interactive);

        @Override
        public native void reloadCalibration();

        @Override
        public native void vibrate(int deviceId, long[] pattern, int[] amplitudes, int repeat,
                int token);

        @Override
        public native void vibrateCombined(int deviceId, long[] pattern,
                SparseArray<int[]> amplitudes,
                int repeat, int token);

        @Override
        public native void cancelVibrate(int deviceId, int token);

        @Override
        public native boolean isVibrating(int deviceId);

        @Override
        public native int[] getVibratorIds(int deviceId);

        @Override
        public native int getBatteryCapacity(int deviceId);

        @Override
        public native int getBatteryStatus(int deviceId);

        @Override
        public native List<Light> getLights(int deviceId);

        @Override
        public native int getLightPlayerId(int deviceId, int lightId);

        @Override
        public native int getLightColor(int deviceId, int lightId);

        @Override
        public native void setLightPlayerId(int deviceId, int lightId, int playerId);

        @Override
        public native void setLightColor(int deviceId, int lightId, int color);

        @Override
        public native void reloadKeyboardLayouts();

        @Override
        public native void reloadDeviceAliases();

        @Override
        public native String dump();

        @Override
        public native void monitor();

        @Override
        public native boolean isInputDeviceEnabled(int deviceId);

        @Override
        public native void enableInputDevice(int deviceId);

        @Override
        public native void disableInputDevice(int deviceId);

        @Override
        public native void setPointerIconType(int iconId);

        @Override
        public native void reloadPointerIcons();

        @Override
        public native void setCustomPointerIcon(PointerIcon icon);

        @Override
        public native void requestPointerCapture(IBinder windowToken, boolean enabled);

        @Override
        public native boolean canDispatchToDisplay(int deviceId, int displayId);

        @Override
        public native void notifyPortAssociationsChanged();

        @Override
        public native void changeUniqueIdAssociation();

        @Override
        public native void notifyPointerDisplayIdChanged();

        @Override
        public native void setDisplayEligibilityForPointerCapture(int displayId, boolean enabled);

        @Override
        public native void setMotionClassifierEnabled(boolean enabled);

        @Override
        public native InputSensorInfo[] getSensorList(int deviceId);

        @Override
        public native boolean flushSensor(int deviceId, int sensorType);

        @Override
        public native boolean enableSensor(int deviceId, int sensorType, int samplingPeriodUs,
                int maxBatchReportLatencyUs);

        @Override
        public native void disableSensor(int deviceId, int sensorType);

        @Override
        public native void cancelCurrentTouch();
    }
}
