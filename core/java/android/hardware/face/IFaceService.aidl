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
package android.hardware.face;

import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.face.IFaceServiceReceiver;
import android.hardware.face.Face;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.view.Surface;

/**
 * Communication channel from client to the face service. These methods are all require the
 * MANAGE_BIOMETRIC signature permission.
 * @hide
 */
interface IFaceService {

    // Creates a test session with the specified sensorId
    ITestSession createTestSession(int sensorId, ITestSessionCallback callback, String opPackageName);

    // Requests a proto dump of the specified sensor
    byte[] dumpSensorServiceStateProto(int sensorId, boolean clearSchedulerBuffer);

    // Retrieve static sensor properties for all face sensors
    List<FaceSensorPropertiesInternal> getSensorPropertiesInternal(String opPackageName);

    // Retrieve static sensor properties for the specified sensor
    FaceSensorPropertiesInternal getSensorProperties(int sensorId, String opPackageName);

    // Authenticate the given sessionId with a face
    void authenticate(IBinder token, long operationId, int userId, IFaceServiceReceiver receiver,
            String opPackageName, boolean isKeyguardBypassEnabled);

    // Uses the face hardware to detect for the presence of a face, without giving details
    // about accept/reject/lockout.
    void detectFace(IBinder token, int userId, IFaceServiceReceiver receiver, String opPackageName);

    // This method prepares the service to start authenticating, but doesn't start authentication.
    // This is protected by the MANAGE_BIOMETRIC signatuer permission. This method should only be
    // called from BiometricService. The additional uid, pid, userId arguments should be determined
    // by BiometricService. To start authentication after the clients are ready, use
    // startPreparedClient().
    void prepareForAuthentication(int sensorId, boolean requireConfirmation, IBinder token, long operationId,
            int userId, IBiometricSensorReceiver sensorReceiver, String opPackageName,
            int cookie, boolean allowBackgroundAuthentication);

    // Starts authentication with the previously prepared client.
    void startPreparedClient(int sensorId, int cookie);

    // Cancel authentication for the given sessionId
    void cancelAuthentication(IBinder token, String opPackageName);

    // Cancel face detection
    void cancelFaceDetect(IBinder token, String opPackageName);

    // Same as above, with extra arguments.
    void cancelAuthenticationFromService(int sensorId, IBinder token, String opPackageName);

    // Start face enrollment
    void enroll(int userId, IBinder token, in byte [] hardwareAuthToken, IFaceServiceReceiver receiver,
            String opPackageName, in int [] disabledFeatures, in Surface previewSurface, boolean debugConsent);

    // Start remote face enrollment
    void enrollRemotely(int userId, IBinder token, in byte [] hardwareAuthToken, IFaceServiceReceiver receiver,
            String opPackageName, in int [] disabledFeatures);

    // Cancel enrollment in progress
    void cancelEnrollment(IBinder token);

    // Removes the specified face enrollment for the specified userId.
    void remove(IBinder token, int faceId, int userId, IFaceServiceReceiver receiver,
            String opPackageName);

    // Removes all face enrollments for the specified userId.
    void removeAll(IBinder token, int userId, IFaceServiceReceiver receiver, String opPackageName);

    // Get the enrolled face for user.
    List<Face> getEnrolledFaces(int sensorId, int userId, String opPackageName);

    // Determine if HAL is loaded and ready
    boolean isHardwareDetected(int sensorId, String opPackageName);

    // Get a pre-enrollment authentication token
    void generateChallenge(IBinder token, int sensorId, int userId, IFaceServiceReceiver receiver, String opPackageName);

    // Finish an enrollment sequence and invalidate the authentication token
    void revokeChallenge(IBinder token, int sensorId, int userId, String opPackageName, long challenge);

    // Determine if a user has at least one enrolled face
    boolean hasEnrolledFaces(int sensorId, int userId, String opPackageName);

    // Return the LockoutTracker status for the specified user
    int getLockoutModeForUser(int sensorId, int userId);

    // Requests for the specified sensor+userId's authenticatorId to be invalidated
    void invalidateAuthenticatorId(int sensorId, int userId, IInvalidationCallback callback);

    // Gets the authenticator ID for face
    long getAuthenticatorId(int sensorId, int callingUserId);

    // Reset the lockout when user authenticates with strong auth (e.g. PIN, pattern or password)
    void resetLockout(IBinder token, int sensorId, int userId, in byte [] hardwareAuthToken, String opPackageName);

    // Add a callback which gets notified when the face lockout period expired.
    void addLockoutResetCallback(IBiometricServiceLockoutResetCallback callback, String opPackageName);

    void setFeature(IBinder token, int userId, int feature, boolean enabled,
            in byte [] hardwareAuthToken, IFaceServiceReceiver receiver, String opPackageName);

    void getFeature(IBinder token, int userId, int feature, IFaceServiceReceiver receiver,
            String opPackageName);

    // Registers all HIDL and AIDL sensors. Only HIDL sensor properties need to be provided, because
    // AIDL sensor properties are retrieved directly from the available HALs. If no HIDL HALs exist,
    // hidlSensors must be non-null and empty. See AuthService.java
    void registerAuthenticators(in List<FaceSensorPropertiesInternal> hidlSensors);
}
