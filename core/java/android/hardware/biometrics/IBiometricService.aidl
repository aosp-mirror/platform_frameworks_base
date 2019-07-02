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

import android.os.Bundle;
import android.hardware.biometrics.IBiometricConfirmDeviceCredentialCallback;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricServiceReceiver;

/**
 * Communication channel from BiometricPrompt and BiometricManager to BiometricService. The
 * interface does not expose specific biometric modalities. The system will use the default
 * biometric for apps. On devices with more than one, the choice is dictated by user preference in
 * Settings.
 * @hide
 */
interface IBiometricService {
    // Requests authentication. The service choose the appropriate biometric to use, and show
    // the corresponding BiometricDialog.
    // TODO(b/123378871): Remove callback when moved.
    void authenticate(IBinder token, long sessionId, int userId,
            IBiometricServiceReceiver receiver, String opPackageName, in Bundle bundle,
            IBiometricConfirmDeviceCredentialCallback callback);

    // Cancel authentication for the given sessionId
    void cancelAuthentication(IBinder token, String opPackageName);

    // Checks if biometrics can be used.
    int canAuthenticate(String opPackageName);

    // Register callback for when keyguard biometric eligibility changes.
    void registerEnabledOnKeyguardCallback(IBiometricEnabledOnKeyguardCallback callback);

    // Explicitly set the active user.
    void setActiveUser(int userId);

    // Notify BiometricService when <Biometric>Service is ready to start the prepared client.
    // Client lifecycle is still managed in <Biometric>Service.
    void onReadyForAuthentication(int cookie, boolean requireConfirmation, int userId);

    // Reset the lockout when user authenticates with strong auth (e.g. PIN, pattern or password)
    void resetLockout(in byte [] token);

    // TODO(b/123378871): Remove when moved.
    // CDCA needs to send results to BiometricService if it was invoked using BiometricPrompt's
    // setAllowDeviceCredential method, since there's no way for us to intercept onActivityResult.
    // CDCA is launched from BiometricService (startActivityAsUser) instead of *ForResult.
    void onConfirmDeviceCredentialSuccess();
    // TODO(b/123378871): Remove when moved.
    void onConfirmDeviceCredentialError(int error, String message);
    // TODO(b/123378871): Remove when moved.
    // When ConfirmLock* is invoked from BiometricPrompt, it needs to register a callback so that
    // it can receive the cancellation signal.
    void registerCancellationCallback(IBiometricConfirmDeviceCredentialCallback callback);
}
