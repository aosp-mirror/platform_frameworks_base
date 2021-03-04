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

import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.PromptInfo;
import android.hardware.biometrics.SensorPropertiesInternal;

/**
 * Communication channel from AuthService to BiometricService.
 * @hide
 */
interface IBiometricService {
    // Creates a test session with the specified sensorId
    ITestSession createTestSession(int sensorId, ITestSessionCallback callback, String opPackageName);

    // Retrieve static sensor properties for all biometric sensors
    List<SensorPropertiesInternal> getSensorProperties(String opPackageName);

    // Requests authentication. The service choose the appropriate biometric to use, and show
    // the corresponding BiometricDialog.
    void authenticate(IBinder token, long operationId, int userId,
            IBiometricServiceReceiver receiver, String opPackageName, in PromptInfo promptInfo);

    // Cancel authentication for the given session.
    void cancelAuthentication(IBinder token, String opPackageName);

    // Checks if biometrics can be used.
    int canAuthenticate(String opPackageName, int userId, int callingUserId, int authenticators);

    // Checks if any biometrics are enrolled.
    boolean hasEnrolledBiometrics(int userId, String opPackageName);

    // Registers an authenticator (e.g. face, fingerprint, iris).
    // Id must be unique, whereas strength and modality don't need to be.
    // TODO(b/123321528): Turn strength and modality into enums.
    void registerAuthenticator(int id, int modality, int strength,
            IBiometricAuthenticator authenticator);

    // Register callback for when keyguard biometric eligibility changes.
    void registerEnabledOnKeyguardCallback(IBiometricEnabledOnKeyguardCallback callback,
            int callingUserId);

    // Notify BiometricService when <Biometric>Service is ready to start the prepared client.
    // Client lifecycle is still managed in <Biometric>Service.
    void onReadyForAuthentication(int cookie);

    // Requests all BIOMETRIC_STRONG sensors to have their authenticatorId invalidated for the
    // specified user. This happens when enrollments have been added on devices with multiple
    // biometric sensors.
    void invalidateAuthenticatorIds(int userId, int fromSensorId, IInvalidationCallback callback);

    // Get a list of AuthenticatorIDs for authenticators which have enrolled templates and meet
    // the requirements for integrating with Keystore. The AuthenticatorID are known in Keystore
    // land as SIDs, and are used during key generation.
    long[] getAuthenticatorIds(int callingUserId);

    int getCurrentStrength(int sensorId);

    // Returns a bit field of the modality (or modalities) that are will be used for authentication.
    int getCurrentModality(String opPackageName, int userId, int callingUserId, int authenticators);

    // Returns a bit field of the authentication modalities that are supported by this device.
    int getSupportedModalities(int authenticators);
}
