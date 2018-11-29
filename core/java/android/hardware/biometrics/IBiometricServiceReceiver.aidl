/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * Communication channel from BiometricService back to BiometricPrompt
 * @hide
 */
oneway interface IBiometricServiceReceiver {
    // Notify BiometricPrompt that authentication was successful
    void onAuthenticationSucceeded();
    // Noties that authentication failed.
    void onAuthenticationFailed();
    // Notify BiometricPrompt that an error has occurred.
    void onError(int error, String message);
    // Notifies that a biometric has been acquired.
    void onAcquired(int acquiredInfo, String message);
    // Notifies that the SystemUI dialog has been dismissed.
    void onDialogDismissed(int reason);
}
