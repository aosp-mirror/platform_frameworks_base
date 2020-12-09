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

package android.os;

import android.annotation.NonNull;

/**
 * VibratorManager provides access to multiple vibrators, as well as the ability to run them in
 * a synchronized fashion.
 */
public abstract class VibratorManager {
    /** @hide */
    protected static final String TAG = "VibratorManager";

    /**
     * {@hide}
     */
    public VibratorManager() {
    }

    /**
     * This method lists all available actuator ids, returning a possible empty list.
     * If the device has only a single actuator, this should return a single entry with a
     * default id.
     */
    @NonNull
    public abstract int[] getVibratorIds();

    /**
    * Returns a Vibrator service for given id.
    * This allows users to perform a vibration effect on a single actuator.
    */
    @NonNull
    public abstract Vibrator getVibrator(int vibratorId);

    /**
    * Returns the system default Vibrator service.
    */
    @NonNull
    public abstract Vibrator getDefaultVibrator();

    /**
     * Vibrates all actuators by passing each VibrationEffect within CombinedVibrationEffect
     * to the respective actuator, in sync.
     */
    public abstract void vibrate(@NonNull CombinedVibrationEffect effect);
}
