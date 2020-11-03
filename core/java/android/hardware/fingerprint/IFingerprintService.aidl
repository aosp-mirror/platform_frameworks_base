/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.fingerprint.IFingerprintClientActiveCallback;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.hardware.fingerprint.Fingerprint;
import java.util.List;

/**
 * Communication channel from client to the fingerprint service.
 * @hide
 */
interface IFingerprintService {
    // Authenticate the given sessionId with a fingerprint. This is protected by
    // USE_FINGERPRINT/USE_BIOMETRIC permission. This is effectively deprecated, since it only comes
    // through FingerprintManager now.
    void authenticate(IBinder token, long sessionId, int userId,
            IFingerprintServiceReceiver receiver, int flags, String opPackageName);

    // Uses the fingerprint hardware to detect for the presence of a finger, without giving details
    // about accept/reject/lockout.
    void detectFingerprint(IBinder token, int userId, IFingerprintServiceReceiver receiver,
            String opPackageName);

    // This method prepares the service to start authenticating, but doesn't start authentication.
    // This is protected by the MANAGE_BIOMETRIC signatuer permission. This method should only be
    // called from BiometricService. The additional uid, pid, userId arguments should be determined
    // by BiometricService. To start authentication after the clients are ready, use
    // startPreparedClient().
    void prepareForAuthentication(IBinder token, long sessionId, int userId,
            IBiometricServiceReceiverInternal wrapperReceiver, String opPackageName, int cookie,
            int callingUid, int callingPid, int callingUserId);

    // Starts authentication with the previously prepared client.
    void startPreparedClient(int cookie);

    // Cancel authentication for the given sessionId
    void cancelAuthentication(IBinder token, String opPackageName);

    // Cancel finger detection
    void cancelFingerprintDetect(IBinder token, String opPackageName);

    // Same as above, except this is protected by the MANAGE_BIOMETRIC signature permission. Takes
    // an additional uid, pid, userid.
    void cancelAuthenticationFromService(IBinder token, String opPackageName,
            int callingUid, int callingPid, int callingUserId, boolean fromClient);

    // Start fingerprint enrollment
    void enroll(IBinder token, in byte [] cryptoToken, int groupId, IFingerprintServiceReceiver receiver,
            int flags, String opPackageName);

    // Cancel enrollment in progress
    void cancelEnrollment(IBinder token);

    // Any errors resulting from this call will be returned to the listener
    void remove(IBinder token, int fingerId, int groupId, int userId,
            IFingerprintServiceReceiver receiver);

    // Rename the fingerprint specified by fingerId and groupId to the given name
    void rename(int fingerId, int groupId, String name);

    // Get a list of enrolled fingerprints in the given group.
    List<Fingerprint> getEnrolledFingerprints(int groupId, String opPackageName);

    // Determine if HAL is loaded and ready
    boolean isHardwareDetected(String opPackageName);

    // Get a pre-enrollment authentication token
    long preEnroll(IBinder token);

    // Finish an enrollment sequence and invalidate the authentication token
    int postEnroll(IBinder token);

    // Determine if a user has at least one enrolled fingerprint
    boolean hasEnrolledFingerprints(int groupId, String opPackageName);

    // Gets the number of hardware devices
    // int getHardwareDeviceCount();

    // Gets the unique device id for hardware enumerated at i
    // long getHardwareDevice(int i);

    // Gets the authenticator ID for fingerprint
    long getAuthenticatorId(int callingUserId);

    // Reset the timeout when user authenticates with strong auth (e.g. PIN, pattern or password)
    void resetTimeout(in byte [] cryptoToken);

    // Add a callback which gets notified when the fingerprint lockout period expired.
    void addLockoutResetCallback(IBiometricServiceLockoutResetCallback callback);

    // Explicitly set the active user (for enrolling work profile)
    void setActiveUser(int uid);

    // Enumerate all fingerprints
    void enumerate(IBinder token, int userId, IFingerprintServiceReceiver receiver);

    // Check if a client request is currently being handled
    boolean isClientActive();

    // Add a callback which gets notified when the service starts and stops handling client requests
    void addClientActiveCallback(IFingerprintClientActiveCallback callback);

    // Removes a callback set by addClientActiveCallback
    void removeClientActiveCallback(IFingerprintClientActiveCallback callback);

    // Initialize the OEM configured biometric strength
    void initConfiguredStrength(int strength);
}
