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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Binder;
import android.os.CombinedVibration;
import android.os.NullVibrator;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.SparseArray;
import android.view.InputDevice;

import com.android.internal.annotations.GuardedBy;

/**
 * Vibrator manager implementation that communicates with the input device vibrators.
 *
 * @hide
 */
public class InputDeviceVibratorManager extends VibratorManager
        implements InputManager.InputDeviceListener {
    private static final String TAG = "InputDeviceVibratorManager";
    private static final boolean DEBUG = false;

    private final Binder mToken;
    private final InputManager mInputManager;

    // The input device Id.
    private final int mDeviceId;
    // Vibrator map from Vibrator Id to Vibrator
    @GuardedBy("mVibrators")
    private final SparseArray<Vibrator> mVibrators = new SparseArray<>();

    public InputDeviceVibratorManager(InputManager inputManager, int deviceId) {
        mInputManager = inputManager;
        mDeviceId = deviceId;
        mToken = new Binder();

        initializeVibrators();
    }

    private void initializeVibrators() {
        synchronized (mVibrators) {
            mVibrators.clear();
            InputDevice inputDevice = InputDevice.getDevice(mDeviceId);
            final int[] vibratorIds =
                    mInputManager.getVibratorIds(mDeviceId);
            for (int i = 0; i < vibratorIds.length; i++) {
                mVibrators.put(vibratorIds[i],
                        new InputDeviceVibrator(mInputManager, mDeviceId, vibratorIds[i]));
            }
        }
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        synchronized (mVibrators) {
            if (deviceId == mDeviceId) {
                mVibrators.clear();
            }
        }
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        if (deviceId == mDeviceId) {
            initializeVibrators();
        }
    }

    @NonNull
    @Override
    public int[] getVibratorIds() {
        synchronized (mVibrators) {
            int[] vibratorIds = new int[mVibrators.size()];
            for (int idx = 0; idx < mVibrators.size(); idx++) {
                vibratorIds[idx++] = mVibrators.keyAt(idx);
            }
            return vibratorIds;
        }
    }

    @NonNull
    @Override
    public Vibrator getVibrator(int vibratorId) {
        synchronized (mVibrators) {
            if (mVibrators.contains(vibratorId)) {
                return mVibrators.get(vibratorId);
            }
        }
        return NullVibrator.getInstance();
    }

    @NonNull
    @Override
    public Vibrator getDefaultVibrator() {
        // Returns vibrator ID 0
        synchronized (mVibrators) {
            if (mVibrators.size() > 0) {
                return mVibrators.valueAt(0);
            }
        }
        return NullVibrator.getInstance();
    }

    @Override
    public void vibrate(int uid, String opPkg, @NonNull CombinedVibration effect,
            String reason, @Nullable VibrationAttributes attributes) {
        mInputManager.vibrate(mDeviceId, effect, mToken);
    }

    @Override
    public void cancel() {
        mInputManager.cancelVibrate(mDeviceId, mToken);
    }

    @Override
    public void cancel(int usageFilter) {
        cancel();
    }
}
