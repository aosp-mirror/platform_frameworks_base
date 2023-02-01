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

import android.annotation.BinderThread;
import android.content.Context;
import android.graphics.Color;
import android.hardware.input.IKeyboardBacklightListener;
import android.hardware.input.IKeyboardBacklightState;
import android.hardware.input.InputManager;
import android.hardware.lights.Light;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputDevice;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * A thread-safe component of {@link InputManagerService} responsible for managing the keyboard
 * backlight for supported keyboards.
 */
final class KeyboardBacklightController implements
        InputManagerService.KeyboardBacklightControllerInterface, InputManager.InputDeviceListener {

    private static final String TAG = "KbdBacklightController";

    // To enable these logs, run:
    // 'adb shell setprop log.tag.KbdBacklightController DEBUG' (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private enum Direction {
        DIRECTION_UP, DIRECTION_DOWN
    }
    private static final int MSG_UPDATE_EXISTING_DEVICES = 1;
    private static final int MSG_INCREMENT_KEYBOARD_BACKLIGHT = 2;
    private static final int MSG_DECREMENT_KEYBOARD_BACKLIGHT = 3;
    private static final int MSG_NOTIFY_USER_ACTIVITY = 4;
    private static final int MSG_NOTIFY_USER_INACTIVITY = 5;
    private static final int MSG_INTERACTIVE_STATE_CHANGED = 6;
    private static final int MAX_BRIGHTNESS = 255;
    private static final int NUM_BRIGHTNESS_CHANGE_STEPS = 10;

    @VisibleForTesting
    static final long USER_INACTIVITY_THRESHOLD_MILLIS = Duration.ofSeconds(30).toMillis();

    @VisibleForTesting
    static final int[] BRIGHTNESS_VALUE_FOR_LEVEL = new int[NUM_BRIGHTNESS_CHANGE_STEPS + 1];

    private final Context mContext;
    private final NativeInputManagerService mNative;
    // The PersistentDataStore should be locked before use.
    @GuardedBy("mDataStore")
    private final PersistentDataStore mDataStore;
    private final Handler mHandler;
    // Always access on handler thread or need to lock this for synchronization.
    private final SparseArray<KeyboardBacklightState> mKeyboardBacklights = new SparseArray<>(1);
    // Maintains state if all backlights should be on or turned off
    private boolean mIsBacklightOn = false;
    // Maintains state if currently the device is interactive or not
    private boolean mIsInteractive = true;

    // List of currently registered keyboard backlight listeners
    @GuardedBy("mKeyboardBacklightListenerRecords")
    private final SparseArray<KeyboardBacklightListenerRecord> mKeyboardBacklightListenerRecords =
            new SparseArray<>();

    static {
        // Fixed brightness levels to avoid issues when converting back and forth from the
        // device brightness range to [0-255]
        // Levels are: 0, 25, 51, ..., 255
        for (int i = 0; i <= NUM_BRIGHTNESS_CHANGE_STEPS; i++) {
            BRIGHTNESS_VALUE_FOR_LEVEL[i] = (int) Math.floor(
                    ((float) i * MAX_BRIGHTNESS) / NUM_BRIGHTNESS_CHANGE_STEPS);
        }
    }

    KeyboardBacklightController(Context context, NativeInputManagerService nativeService,
            PersistentDataStore dataStore, Looper looper) {
        mContext = context;
        mNative = nativeService;
        mDataStore = dataStore;
        mHandler = new Handler(looper, this::handleMessage);
    }

    @Override
    public void systemRunning() {
        InputManager inputManager = Objects.requireNonNull(
                mContext.getSystemService(InputManager.class));
        inputManager.registerInputDeviceListener(this, mHandler);

        Message msg = Message.obtain(mHandler, MSG_UPDATE_EXISTING_DEVICES,
                inputManager.getInputDeviceIds());
        mHandler.sendMessage(msg);
    }

    @Override
    public void incrementKeyboardBacklight(int deviceId) {
        Message msg = Message.obtain(mHandler, MSG_INCREMENT_KEYBOARD_BACKLIGHT, deviceId);
        mHandler.sendMessage(msg);
    }

    @Override
    public void decrementKeyboardBacklight(int deviceId) {
        Message msg = Message.obtain(mHandler, MSG_DECREMENT_KEYBOARD_BACKLIGHT, deviceId);
        mHandler.sendMessage(msg);
    }

    @Override
    public void notifyUserActivity() {
        Message msg = Message.obtain(mHandler, MSG_NOTIFY_USER_ACTIVITY);
        mHandler.sendMessage(msg);
    }

    @Override
    public void onInteractiveChanged(boolean isInteractive) {
        Message msg = Message.obtain(mHandler, MSG_INTERACTIVE_STATE_CHANGED, isInteractive);
        mHandler.sendMessage(msg);
    }

    private void updateKeyboardBacklight(int deviceId, Direction direction) {
        InputDevice inputDevice = getInputDevice(deviceId);
        KeyboardBacklightState state = mKeyboardBacklights.get(deviceId);
        if (inputDevice == null || state == null) {
            return;
        }
        Light keyboardBacklight = state.mLight;
        // Follow preset levels of brightness defined in BRIGHTNESS_LEVELS
        final int currBrightnessLevel = state.mBrightnessLevel;
        final int newBrightnessLevel;
        if (direction == Direction.DIRECTION_UP) {
            newBrightnessLevel = Math.min(currBrightnessLevel + 1, NUM_BRIGHTNESS_CHANGE_STEPS);
        } else {
            newBrightnessLevel = Math.max(currBrightnessLevel - 1, 0);
        }
        updateBacklightState(deviceId, keyboardBacklight, newBrightnessLevel,
                true /* isTriggeredByKeyPress */);

        synchronized (mDataStore) {
            try {
                mDataStore.setKeyboardBacklightBrightness(inputDevice.getDescriptor(),
                        keyboardBacklight.getId(),
                        BRIGHTNESS_VALUE_FOR_LEVEL[newBrightnessLevel]);
            } finally {
                mDataStore.saveIfNeeded();
            }
        }
    }

    private void restoreBacklightBrightness(InputDevice inputDevice, Light keyboardBacklight) {
        OptionalInt brightness;
        synchronized (mDataStore) {
            brightness = mDataStore.getKeyboardBacklightBrightness(
                    inputDevice.getDescriptor(), keyboardBacklight.getId());
        }
        if (brightness.isPresent()) {
            int brightnessValue = Math.max(0, Math.min(MAX_BRIGHTNESS, brightness.getAsInt()));
            int brightnessLevel = Arrays.binarySearch(BRIGHTNESS_VALUE_FOR_LEVEL, brightnessValue);
            updateBacklightState(inputDevice.getId(), keyboardBacklight, brightnessLevel,
                    false /* isTriggeredByKeyPress */);
            if (DEBUG) {
                Slog.d(TAG, "Restoring brightness level " + brightness.getAsInt());
            }
        }
    }

    private void handleUserActivity() {
        // Ignore user activity if device is not interactive. When device becomes interactive, we
        // will send another user activity to turn backlight on.
        if (!mIsInteractive) {
            return;
        }
        if (!mIsBacklightOn) {
            mIsBacklightOn = true;
            for (int i = 0; i < mKeyboardBacklights.size(); i++) {
                int deviceId = mKeyboardBacklights.keyAt(i);
                KeyboardBacklightState state = mKeyboardBacklights.valueAt(i);
                updateBacklightState(deviceId, state.mLight, state.mBrightnessLevel,
                        false /* isTriggeredByKeyPress */);
            }
        }
        mHandler.removeMessages(MSG_NOTIFY_USER_INACTIVITY);
        mHandler.sendEmptyMessageAtTime(MSG_NOTIFY_USER_INACTIVITY,
                SystemClock.uptimeMillis() + USER_INACTIVITY_THRESHOLD_MILLIS);
    }

    private void handleUserInactivity() {
        if (mIsBacklightOn) {
            mIsBacklightOn = false;
            for (int i = 0; i < mKeyboardBacklights.size(); i++) {
                int deviceId = mKeyboardBacklights.keyAt(i);
                KeyboardBacklightState state = mKeyboardBacklights.valueAt(i);
                updateBacklightState(deviceId, state.mLight, state.mBrightnessLevel,
                        false /* isTriggeredByKeyPress */);
            }
        }
    }

    @VisibleForTesting
    public void handleInteractiveStateChange(boolean isInteractive) {
        // Interactive state changes should force the keyboard to turn on/off irrespective of
        // whether time out occurred or not.
        mIsInteractive = isInteractive;
        if (isInteractive) {
            handleUserActivity();
        } else {
            handleUserInactivity();
        }
    }

    private boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_UPDATE_EXISTING_DEVICES:
                for (int deviceId : (int[]) msg.obj) {
                    onInputDeviceAdded(deviceId);
                }
                return true;
            case MSG_INCREMENT_KEYBOARD_BACKLIGHT:
                updateKeyboardBacklight((int) msg.obj, Direction.DIRECTION_UP);
                return true;
            case MSG_DECREMENT_KEYBOARD_BACKLIGHT:
                updateKeyboardBacklight((int) msg.obj, Direction.DIRECTION_DOWN);
                return true;
            case MSG_NOTIFY_USER_ACTIVITY:
                handleUserActivity();
                return true;
            case MSG_NOTIFY_USER_INACTIVITY:
                handleUserInactivity();
                return true;
            case MSG_INTERACTIVE_STATE_CHANGED:
                handleInteractiveStateChange((boolean) msg.obj);
                return true;
        }
        return false;
    }

    @VisibleForTesting
    @Override
    public void onInputDeviceAdded(int deviceId) {
        onInputDeviceChanged(deviceId);
    }

    @VisibleForTesting
    @Override
    public void onInputDeviceRemoved(int deviceId) {
        mKeyboardBacklights.remove(deviceId);
    }

    @VisibleForTesting
    @Override
    public void onInputDeviceChanged(int deviceId) {
        InputDevice inputDevice = getInputDevice(deviceId);
        if (inputDevice == null) {
            return;
        }
        final Light keyboardBacklight = getKeyboardBacklight(inputDevice);
        if (keyboardBacklight == null) {
            mKeyboardBacklights.remove(deviceId);
            return;
        }
        KeyboardBacklightState state = mKeyboardBacklights.get(deviceId);
        if (state != null && state.mLight.getId() == keyboardBacklight.getId()) {
            return;
        }
        // The keyboard backlight was added or changed.
        mKeyboardBacklights.put(deviceId, new KeyboardBacklightState(keyboardBacklight));
        restoreBacklightBrightness(inputDevice, keyboardBacklight);
    }

    private InputDevice getInputDevice(int deviceId) {
        InputManager inputManager = mContext.getSystemService(InputManager.class);
        return inputManager != null ? inputManager.getInputDevice(deviceId) : null;
    }

    private Light getKeyboardBacklight(InputDevice inputDevice) {
        // Assuming each keyboard can have only single Light node for Keyboard backlight control
        // for simplicity.
        for (Light light : inputDevice.getLightsManager().getLights()) {
            if (light.getType() == Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT
                    && light.hasBrightnessControl()) {
                return light;
            }
        }
        return null;
    }

    /** Register the keyboard backlight listener for a process. */
    @BinderThread
    @Override
    public void registerKeyboardBacklightListener(IKeyboardBacklightListener listener,
            int pid) {
        synchronized (mKeyboardBacklightListenerRecords) {
            if (mKeyboardBacklightListenerRecords.get(pid) != null) {
                throw new IllegalStateException("The calling process has already registered "
                        + "a KeyboardBacklightListener.");
            }
            KeyboardBacklightListenerRecord record = new KeyboardBacklightListenerRecord(pid,
                    listener);
            try {
                listener.asBinder().linkToDeath(record, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
            mKeyboardBacklightListenerRecords.put(pid, record);
        }
    }

    /** Unregister the keyboard backlight listener for a process. */
    @BinderThread
    @Override
    public void unregisterKeyboardBacklightListener(IKeyboardBacklightListener listener,
            int pid) {
        synchronized (mKeyboardBacklightListenerRecords) {
            KeyboardBacklightListenerRecord record = mKeyboardBacklightListenerRecords.get(pid);
            if (record == null) {
                throw new IllegalStateException("The calling process has no registered "
                        + "KeyboardBacklightListener.");
            }
            if (record.mListener != listener) {
                throw new IllegalStateException("The calling process has a different registered "
                        + "KeyboardBacklightListener.");
            }
            record.mListener.asBinder().unlinkToDeath(record, 0);
            mKeyboardBacklightListenerRecords.remove(pid);
        }
    }

    private void updateBacklightState(int deviceId, Light light, int brightnessLevel,
            boolean isTriggeredByKeyPress) {
        KeyboardBacklightState state = mKeyboardBacklights.get(deviceId);
        if (state == null) {
            return;
        }

        mNative.setLightColor(deviceId, light.getId(),
                mIsBacklightOn ? Color.argb(BRIGHTNESS_VALUE_FOR_LEVEL[brightnessLevel], 0, 0, 0)
                        : 0);
        if (DEBUG) {
            Slog.d(TAG, "Changing state from " + state.mBrightnessLevel + " to " + brightnessLevel
                    + "(isBacklightOn = " + mIsBacklightOn + ")");
        }
        state.mBrightnessLevel = brightnessLevel;

        synchronized (mKeyboardBacklightListenerRecords) {
            for (int i = 0; i < mKeyboardBacklightListenerRecords.size(); i++) {
                IKeyboardBacklightState callbackState = new IKeyboardBacklightState();
                callbackState.brightnessLevel = brightnessLevel;
                callbackState.maxBrightnessLevel = NUM_BRIGHTNESS_CHANGE_STEPS;
                mKeyboardBacklightListenerRecords.valueAt(i).notifyKeyboardBacklightChanged(
                        deviceId, callbackState, isTriggeredByKeyPress);
            }
        }
    }

    private void onKeyboardBacklightListenerDied(int pid) {
        synchronized (mKeyboardBacklightListenerRecords) {
            mKeyboardBacklightListenerRecords.remove(pid);
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        ipw.println(
                TAG + ": " + mKeyboardBacklights.size() + " keyboard backlights, isBacklightOn = "
                        + mIsBacklightOn);

        ipw.increaseIndent();
        for (int i = 0; i < mKeyboardBacklights.size(); i++) {
            KeyboardBacklightState state = mKeyboardBacklights.valueAt(i);
            ipw.println(i + ": " + state.toString());
        }
        ipw.decreaseIndent();
    }

    // A record of a registered Keyboard backlight listener from one process.
    private class KeyboardBacklightListenerRecord implements IBinder.DeathRecipient {
        public final int mPid;
        public final IKeyboardBacklightListener mListener;

        KeyboardBacklightListenerRecord(int pid, IKeyboardBacklightListener listener) {
            mPid = pid;
            mListener = listener;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Keyboard backlight listener for pid " + mPid + " died.");
            }
            onKeyboardBacklightListenerDied(mPid);
        }

        public void notifyKeyboardBacklightChanged(int deviceId, IKeyboardBacklightState state,
                boolean isTriggeredByKeyPress) {
            try {
                mListener.onBrightnessChanged(deviceId, state, isTriggeredByKeyPress);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid
                        + " that keyboard backlight changed, assuming it died.", ex);
                binderDied();
            }
        }
    }

    private static class KeyboardBacklightState {
        private final Light mLight;
        private int mBrightnessLevel;

        KeyboardBacklightState(Light light) {
            mLight = light;
        }

        @Override
        public String toString() {
            return "KeyboardBacklightState{Light=" + mLight.getId()
                    + ", BrightnessLevel=" + mBrightnessLevel
                    + "}";
        }
    }
}
