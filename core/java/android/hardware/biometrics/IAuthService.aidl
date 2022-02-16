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

import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.PromptInfo;
import android.hardware.biometrics.SensorPropertiesInternal;

/**
 * Communication channel from BiometricPrompt and BiometricManager to AuthService. The
 * interface does not expose specific biometric modalities. The system will use the default
 * biometric for apps. On devices with more than one, the choice is dictated by user preference in
 * Settings.
 * @hide
 */
interface IAuthService {
    // Creates a test session with the specified sensorId
    ITestSession createTestSession(int sensorId, ITestSessionCallback callback, String opPackageName);

    // Retrieve static sensor properties for all biometric sensors
    List<SensorPropertiesInternal> getSensorProperties(String opPackageName);

    // Retrieve the package where BIometricOrompt's UI is implemented
    String getUiPackage();

    // Requests authentication. The service choose the appropriate biometric to use, and show
    // the corresponding BiometricDialog.
    void authenticate(IBinder token, long sessionId, int userId,
            IBiometricServiceReceiver receiver, String opPackageName, in PromptInfo promptInfo);

    // Cancel authentication for the given sessionId
    void cancelAuthentication(IBinder token, String opPackageName);

    // TODO(b/141025588): Make userId the first arg to be consistent with hasEnrolledBiometrics.
    // Checks if biometrics can be used.
    int canAuthenticate(String opPackageName, int userId, int authenticators);

    // Checks if any biometrics are enrolled.
    boolean hasEnrolledBiometrics(int userId, String opPackageName);

    // Register callback for when keyguard biometric eligibility changes.
    void registerEnabledOnKeyguardCallback(IBiometricEnabledOnKeyguardCallback callback);

    // Requests all BIOMETRIC_STRONG sensors to have their authenticatorId invalidated for the
    // specified user. This happens when enrollments have been added on devices with multiple
    // biometric sensors.
    void invalidateAuthenticatorIds(int userId, int fromSensorId, IInvalidationCallback callback);

    // Get a list of AuthenticatorIDs for authenticators which have enrolled templates and meet
    // the requirements for integrating with Keystore. The AuthenticatorID are known in Keystore
    // land as SIDs, and are used during key generation.
    // If userId is not equal to the calling user ID, the caller must have the
    // USE_BIOMETRIC_INTERNAL permission.
    long[] getAuthenticatorIds(in int userId);

    // See documentation in BiometricManager.
    void resetLockoutTimeBound(IBinder token, String opPackageName, int fromSensorId, int userId,
            in byte[] hardwareAuthToken);

    // Provides a localized string that may be used as the label for a button that invokes
    // BiometricPrompt.
    CharSequence getButtonLabel(int userId, String opPackageName, int authenticators);

    // Provides a localized string that may be shown while the user is authenticating with
    // BiometricPrompt.
    CharSequence getPromptMessage(int userId, String opPackageName, int authenticators);

    // Provides a localized string that may be shown as the title for an app setting that enables
    // biometric authentication.
    CharSequence getSettingName(int userId, String opPackageName, int authenticators);
}
