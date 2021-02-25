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

package com.android.server.vibrator;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.CombinedVibrationEffect;
import android.os.Handler;
import android.os.VibrationAttributes;
import android.os.VibratorManager;
import android.util.SparseArray;
import android.view.InputDevice;

import com.android.internal.annotations.GuardedBy;

/** Delegates vibrations to all connected {@link InputDevice} with one or more vibrators. */
final class InputDeviceDelegate implements InputManager.InputDeviceListener {
    private static final String TAG = "InputDeviceDelegate";

    private final Object mLock = new Object();
    private final Handler mHandler;
    private final Context mContext;

    @GuardedBy("mLock")
    @Nullable
    private InputManager mInputManager;

    @GuardedBy("mLock")
    private final SparseArray<VibratorManager> mInputDeviceVibrators = new SparseArray<>();

    /**
     * Flag updated via {@link #updateInputDeviceVibrators(boolean)}, holding the value of {@link
     * android.provider.Settings.System#VIBRATE_INPUT_DEVICES}.
     */
    @GuardedBy("mLock")
    private boolean mShouldVibrateInputDevices;

    InputDeviceDelegate(Context context, Handler handler) {
        mHandler = handler;
        mContext = context;
    }

    public void onSystemReady() {
        synchronized (mLock) {
            mInputManager = mContext.getSystemService(InputManager.class);
        }
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        updateInputDevice(deviceId);
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        updateInputDevice(deviceId);
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        synchronized (mLock) {
            mInputDeviceVibrators.remove(deviceId);
        }
    }

    /**
     * Return {@code true} is there are input devices with vibrators available and vibrations should
     * be delegated to them.
     */
    public boolean isAvailable() {
        synchronized (mLock) {
            // mInputDeviceVibrators is cleared when settings are disabled, so this check is enough.
            return mInputDeviceVibrators.size() > 0;
        }
    }

    /**
     * Vibrate all {@link InputDevice} with vibrators using given effect.
     *
     * @return {@link #isAvailable()}
     */
    public boolean vibrateIfAvailable(int uid, String opPkg, CombinedVibrationEffect effect,
            String reason, VibrationAttributes attrs) {
        synchronized (mLock) {
            for (int i = 0; i < mInputDeviceVibrators.size(); i++) {
                mInputDeviceVibrators.valueAt(i).vibrate(uid, opPkg, effect, reason, attrs);
            }
            return mInputDeviceVibrators.size() > 0;
        }
    }

    /**
     * Cancel vibration on all {@link InputDevice} with vibrators.
     *
     * @return {@link #isAvailable()}
     */
    public boolean cancelVibrateIfAvailable() {
        synchronized (mLock) {
            for (int i = 0; i < mInputDeviceVibrators.size(); i++) {
                mInputDeviceVibrators.valueAt(i).cancel();
            }
            return mInputDeviceVibrators.size() > 0;
        }
    }

    /**
     * Updates the list of {@link InputDevice} vibrators based on the {@link
     * VibrationSettings#shouldVibrateInputDevices()} setting current value and the
     * devices currently available in {@link InputManager#getInputDeviceIds()}.
     *
     * @return true if there was any change in input devices available or related settings.
     */
    public boolean updateInputDeviceVibrators(boolean vibrateInputDevices) {
        synchronized (mLock) {
            if (mInputManager == null) {
                // Ignore update, service not loaded yet so change cannot be applied.
                return false;
            }
            if (vibrateInputDevices == mShouldVibrateInputDevices) {
                // No need to update if settings haven't changed.
                return false;
            }

            mShouldVibrateInputDevices = vibrateInputDevices;
            mInputDeviceVibrators.clear();

            if (vibrateInputDevices) {
                // Register the listener first so any device added/updated/removed after the call to
                // getInputDeviceIds() will trigger the callbacks (which will wait on the lock for
                // this loop to finish).
                mInputManager.registerInputDeviceListener(this, mHandler);

                for (int deviceId : mInputManager.getInputDeviceIds()) {
                    InputDevice device = mInputManager.getInputDevice(deviceId);
                    if (device == null) {
                        continue;
                    }
                    VibratorManager vibratorManager = device.getVibratorManager();
                    if (vibratorManager.getVibratorIds().length > 0) {
                        mInputDeviceVibrators.put(device.getId(), vibratorManager);
                    }
                }
            } else {
                mInputManager.unregisterInputDeviceListener(this);
            }
        }

        return true;
    }

    private void updateInputDevice(int deviceId) {
        synchronized (mLock) {
            if (mInputManager == null) {
                // Ignore update, service not loaded yet so change cannot be applied.
                return;
            }
            if (!mShouldVibrateInputDevices) {
                // No need to keep this device vibrator if setting is off.
                return;
            }
            InputDevice device = mInputManager.getInputDevice(deviceId);
            if (device == null) {
                mInputDeviceVibrators.remove(deviceId);
                return;
            }
            VibratorManager vibratorManager = device.getVibratorManager();
            if (vibratorManager.getVibratorIds().length > 0) {
                mInputDeviceVibrators.put(device.getId(), vibratorManager);
            } else {
                mInputDeviceVibrators.remove(deviceId);
            }
        }
    }
}
