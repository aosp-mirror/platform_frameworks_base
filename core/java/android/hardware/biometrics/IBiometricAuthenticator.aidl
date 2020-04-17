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

import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.face.IFaceServiceReceiver;
import android.hardware.face.Face;

/**
 * This interface encapsulates fingerprint, face, iris, etc. authenticators.
 * Implementations of this interface are meant to be registered with BiometricService.
 * @hide
 */
interface IBiometricAuthenticator {

    // This method prepares the service to start authenticating, but doesn't start authentication.
    // This is protected by the MANAGE_BIOMETRIC signature permission. This method should only be
    // called from BiometricService. The additional uid, pid, userId arguments should be determined
    // by BiometricService. To start authentication after the clients are ready, use
    // startPreparedClient().
    void prepareForAuthentication(boolean requireConfirmation, IBinder token, long operationId,
            int userId, IBiometricServiceReceiverInternal wrapperReceiver, String opPackageName,
            int cookie, int callingUid, int callingPid, int callingUserId);

    // Starts authentication with the previously prepared client.
    void startPreparedClient(int cookie);

    // Same as above, with extra arguments.
    void cancelAuthenticationFromService(IBinder token, String opPackageName,
            int callingUid, int callingPid, int callingUserId, boolean fromClient);

    // Determine if HAL is loaded and ready
    boolean isHardwareDetected(String opPackageName);

    // Determine if a user has at least one enrolled face
    boolean hasEnrolledTemplates(int userId, String opPackageName);

    // Reset the lockout when user authenticates with strong auth (e.g. PIN, pattern or password)
    void resetLockout(in byte [] token);

    // Explicitly set the active user (for enrolling work profile)
    void setActiveUser(int uid);

    // Gets the authenticator ID representing the current set of enrolled templates
    long getAuthenticatorId();
}
