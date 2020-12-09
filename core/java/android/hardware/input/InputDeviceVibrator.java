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

package android.hardware.input;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.os.Binder;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.concurrent.Executor;

/**
 * Vibrator implementation that communicates with the input device vibrators.
 */
final class InputDeviceVibrator extends Vibrator {
    // mDeviceId represents InputDevice ID the vibrator belongs to
    private final int mDeviceId;
    private final int mVibratorId;
    private final Binder mToken;
    private final InputManager mInputManager;

    InputDeviceVibrator(InputManager inputManager, int deviceId, int vibratorId) {
        mInputManager = inputManager;
        mDeviceId = deviceId;
        mVibratorId = vibratorId;
        mToken = new Binder();
    }

    @Override
    public boolean hasVibrator() {
        return true;
    }

    @Override
    public boolean isVibrating() {
        return mInputManager.isVibrating(mDeviceId);
    }

    /* TODO: b/161634264 Support Vibrator listener API in input devices */
    @Override
    public void addVibratorStateListener(@NonNull OnVibratorStateChangedListener listener) {
        throw new UnsupportedOperationException(
            "addVibratorStateListener not supported in InputDeviceVibrator");
    }

    @Override
    public void addVibratorStateListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnVibratorStateChangedListener listener) {
        throw new UnsupportedOperationException(
            "addVibratorStateListener not supported in InputDeviceVibrator");
    }

    @Override
    public void removeVibratorStateListener(@NonNull OnVibratorStateChangedListener listener) {
        throw new UnsupportedOperationException(
            "removeVibratorStateListener not supported in InputDeviceVibrator");
    }

    @Override
    public boolean hasAmplitudeControl() {
        return true;
    }

    /**
     * @hide
     */
    @Override
    public void vibrate(int uid, String opPkg, @NonNull VibrationEffect effect,
            String reason, @NonNull VibrationAttributes attributes) {
        mInputManager.vibrate(mDeviceId, effect, mToken);
    }

    @Override
    public void cancel() {
        mInputManager.cancelVibrate(mDeviceId, mToken);
    }
}
