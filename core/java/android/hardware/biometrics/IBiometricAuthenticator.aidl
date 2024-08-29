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

import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.hardware.face.IFaceServiceReceiver;
import android.hardware.face.Face;

/**
 * This interface encapsulates fingerprint, face, iris, etc. authenticators.
 * Implementations of this interface are meant to be registered with BiometricService.
 * @hide
 */
interface IBiometricAuthenticator {

    // Creates a test session
    ITestSession createTestSession(ITestSessionCallback callback, String opPackageName);

    // Retrieve static sensor properties
    SensorPropertiesInternal getSensorProperties(String opPackageName);

    // Requests a proto dump of the sensor. See biometrics.proto
    byte[] dumpSensorServiceStateProto(boolean clearSchedulerBuffer);

    // This method prepares the service to start authenticating, but doesn't start authentication.
    // This is protected by the MANAGE_BIOMETRIC signature permission. This method should only be
    // called from BiometricService. The additional uid, pid, userId arguments should be determined
    // by BiometricService. To start authentication after the clients are ready, use
    // startPreparedClient().
    void prepareForAuthentication(boolean requireConfirmation, IBinder token, long operationId,
            int userId, IBiometricSensorReceiver sensorReceiver, String opPackageName,
            long requestId, int cookie, boolean allowBackgroundAuthentication,
            boolean isForLegacyFingerprintManager, boolean isMandatoryBiometrics);

    // Starts authentication with the previously prepared client.
    void startPreparedClient(int cookie);

    // Cancels authentication for the given requestId.
    void cancelAuthenticationFromService(IBinder token, String opPackageName, long requestId);

    // Determine if HAL is loaded and ready
    boolean isHardwareDetected(String opPackageName);

    // Determine if a user has at least one enrolled face
    boolean hasEnrolledTemplates(int userId, String opPackageName);

    // Return the LockoutTracker status for the specified user
    int getLockoutModeForUser(int userId);

    // Request the authenticatorId to be invalidated for the specified user
    void invalidateAuthenticatorId(int userId, IInvalidationCallback callback);

    // Gets the authenticator ID representing the current set of enrolled templates
    long getAuthenticatorId(int callingUserId);

    // Requests the sensor to reset its lockout state
    void resetLockout(IBinder token, String opPackageName, int userId,
            in byte[] hardwareAuthToken);
}
