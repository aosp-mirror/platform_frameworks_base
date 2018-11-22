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
 * Communication channel from
 *   1) BiometricDialogImpl (SysUI) back to BiometricService
 *   2) <Biometric>Service back to BiometricService
 *   3) BiometricService back to BiometricPrompt
 * BiometricPrompt sends a receiver to BiometricService, BiometricService contains another
 * "trampoline" receiver which intercepts messages from <Biometric>Service and does some
 * logic before forwarding results as necessary to BiometricPrompt.
 * @hide
 */
oneway interface IBiometricServiceReceiver {
    // Notify BiometricPrompt that authentication was successful
    void onAuthenticationSucceeded();
    // Notify BiometricService that authentication was successful. If user confirmation is required,
    // the auth token must be submitted into KeyStore.
    void onAuthenticationSucceededInternal(boolean requireConfirmation, in byte[] token);
    // Noties that authentication failed.
    void onAuthenticationFailed();
    // Notifies that an error has occurred.
    void onError(int error, String message);
    // Notifies that a biometric has been acquired.
    void onAcquired(int acquiredInfo, String message);
    // Notifies that the SystemUI dialog has been dismissed.
    void onDialogDismissed(int reason);
}
