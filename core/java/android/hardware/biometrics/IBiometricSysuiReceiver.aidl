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
 * Communication channel from AuthController back to BiometricService.
 * @hide
 */
oneway interface IBiometricSysuiReceiver {
    // Notifies that the SystemUI dialog has been dismissed.
    void onDialogDismissed(int reason, in byte[] credentialAttestation);
    // Notifies that the user has pressed the "try again" button on SystemUI
    void onTryAgainPressed();
    // Notifies that the user has pressed the "use password" button on SystemUI
    void onDeviceCredentialPressed();
    // Notifies the client that an internal event, e.g. back button has occurred.
    void onSystemEvent(int event);
    // Notifies that the dialog has finished animating.
    void onDialogAnimatedIn();
}
