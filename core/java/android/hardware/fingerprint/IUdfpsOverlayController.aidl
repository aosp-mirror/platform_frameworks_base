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
package android.hardware.fingerprint;

import android.hardware.fingerprint.IUdfpsOverlayControllerCallback;

/**
 * Interface for interacting with the under-display fingerprint sensor (UDFPS) overlay.
 * @hide
 */
oneway interface IUdfpsOverlayController {
    const int REASON_UNKNOWN = 0;
    const int REASON_ENROLL_FIND_SENSOR = 1;
    const int REASON_ENROLL_ENROLLING = 2;
    const int REASON_AUTH_BP = 3; // BiometricPrompt
    const int REASON_AUTH_FPM_KEYGUARD = 4; // FingerprintManager usage from Keyguard
    const int REASON_AUTH_FPM_OTHER = 5; // Other FingerprintManager usage

    // Shows the overlay.
    void showUdfpsOverlay(int sensorId, int reason, IUdfpsOverlayControllerCallback callback);

    // Hides the overlay.
    void hideUdfpsOverlay(int sensorId);

    // Good image captured. Turn off HBM. Success/Reject comes after, which is when hideUdfpsOverlay
    // will be called.
    void onAcquiredGood(int sensorId);

    // Notifies of enrollment progress changes.
    void onEnrollmentProgress(int sensorId, int remaining);

    // Notifies when a non-terminal error occurs (e.g. user moved their finger too fast).
    void onEnrollmentHelp(int sensorId);

    // Shows debug messages on the UDFPS overlay.
    void setDebugMessage(int sensorId, String message);
}
