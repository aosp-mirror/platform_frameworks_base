/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricServiceReceiver;

/**
 * Communication channel from BiometricPrompt and BiometricManager to AuthService. The
 * interface does not expose specific biometric modalities. The system will use the default
 * biometric for apps. On devices with more than one, the choice is dictated by user preference in
 * Settings.
 * @hide
 */
interface IAuthService {
    // Requests authentication. The service choose the appropriate biometric to use, and show
    // the corresponding BiometricDialog.
    void authenticate(IBinder token, long sessionId, int userId,
            IBiometricServiceReceiver receiver, String opPackageName, in Bundle bundle);

    // Cancel authentication for the given sessionId
    void cancelAuthentication(IBinder token, String opPackageName);

    // TODO(b/141025588): Make userId the first arg to be consistent with hasEnrolledBiometrics.
    // Checks if biometrics can be used.
    int canAuthenticate(String opPackageName, int userId, int authenticators);

    // Checks if any biometrics are enrolled.
    boolean hasEnrolledBiometrics(int userId, String opPackageName);

    // Register callback for when keyguard biometric eligibility changes.
    void registerEnabledOnKeyguardCallback(IBiometricEnabledOnKeyguardCallback callback);

    // Explicitly set the active user.
    void setActiveUser(int userId);

    // Reset the lockout when user authenticates with strong auth (e.g. PIN, pattern or password)
    void resetLockout(in byte [] token);

    // Get a list of AuthenticatorIDs for authenticators which have enrolled templates and meet
    // the requirements for integrating with Keystore. The AuthenticatorID are known in Keystore
    // land as SIDs, and are used during key generation.
    // If userId is not equal to the calling user ID, the caller must have the
    // USE_BIOMETRIC_INTERNAL permission.
    long[] getAuthenticatorIds(in int userId);
}
