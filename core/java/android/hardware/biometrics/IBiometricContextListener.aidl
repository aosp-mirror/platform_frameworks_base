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
package android.hardware.biometrics;

/**
 * A secondary communication channel from AuthController back to BiometricService for
 * events that are not associated with an authentication session. See
 * {@link IBiometricSysuiReceiver} for events associated with a session.
 *
 * @hide
 */
oneway interface IBiometricContextListener {
    @VintfStability
    @Backing(type="int")
    enum FoldState {
        UNKNOWN = 0,
        HALF_OPENED = 1,
        FULLY_OPENED = 2,
        FULLY_CLOSED = 3,
    }

    // Called when the fold state of the device changes.
    void onFoldChanged(FoldState FoldState);

    // Called when the display state of the device changes.
    // Where `displayState` is defined in AuthenticateOptions.DisplayState
    void onDisplayStateChanged(int displayState);

    // Called when the HAL ignoring touches state changes.
    // When true, the HAL ignores touches on the sensor.
    void onHardwareIgnoreTouchesChanged(boolean shouldIgnore);
}
