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

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.CombinedVibrationEffect;
import android.os.Handler;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.SparseArray;
import android.view.InputDevice;

import com.android.internal.annotations.GuardedBy;

/** Delegates vibrations to all connected {@link InputDevice} with available {@link Vibrator}. */
// TODO(b/159207608): Make this package-private once vibrator services are moved to this package
public final class InputDeviceDelegate implements InputManager.InputDeviceListener {
    private static final String TAG = "InputDeviceDelegate";

    private final Object mLock = new Object();
    private final Handler mHandler;
    private final InputManager mInputManager;

    @GuardedBy("mLock")
    private final SparseArray<Vibrator> mInputDeviceVibrators = new SparseArray<>();

    /**
     * Flag updated via {@link #updateInputDeviceVibrators(boolean)}, holding the value of {@link
     * android.provider.Settings.System#VIBRATE_INPUT_DEVICES}.
     */
    @GuardedBy("mLock")
    private boolean mShouldVibrateInputDevices;

    public InputDeviceDelegate(Context context, Handler handler) {
        mHandler = handler;
        mInputManager = context.getSystemService(InputManager.class);
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
     * Vibrate all {@link InputDevice} with {@link Vibrator} available using given effect.
     *
     * @return {@link #isAvailable()}
     */
    public boolean vibrateIfAvailable(int uid, String opPkg, CombinedVibrationEffect effect,
            String reason, VibrationAttributes attrs) {
        synchronized (mLock) {
            // TODO(b/159207608): Pass on the combined vibration once InputManager is merged
            if (effect instanceof CombinedVibrationEffect.Mono) {
                VibrationEffect e = ((CombinedVibrationEffect.Mono) effect).getEffect();
                if (e instanceof VibrationEffect.Prebaked) {
                    VibrationEffect fallback = ((VibrationEffect.Prebaked) e).getFallbackEffect();
                    if (fallback != null) {
                        e = fallback;
                    }
                }
                for (int i = 0; i < mInputDeviceVibrators.size(); i++) {
                    mInputDeviceVibrators.valueAt(i).vibrate(uid, opPkg, e, reason, attrs);
                }
            }
            return mInputDeviceVibrators.size() > 0;
        }
    }

    /**
     * Cancel vibration on all {@link InputDevice} with {@link Vibrator} available.
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
                    Vibrator vibrator = device.getVibrator();
                    if (vibrator.hasVibrator()) {
                        mInputDeviceVibrators.put(device.getId(), vibrator);
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
            if (!mShouldVibrateInputDevices) {
                // No need to keep this device vibrator if setting is off.
                return;
            }
            InputDevice device = mInputManager.getInputDevice(deviceId);
            if (device == null) {
                mInputDeviceVibrators.remove(deviceId);
                return;
            }
            Vibrator vibrator = device.getVibrator();
            if (vibrator.hasVibrator()) {
                mInputDeviceVibrators.put(deviceId, vibrator);
            } else {
                mInputDeviceVibrators.remove(deviceId);
            }
        }
    }
}
