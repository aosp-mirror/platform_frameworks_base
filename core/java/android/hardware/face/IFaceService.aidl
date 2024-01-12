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
import android.hardware.biometrics.IBiometricStateListener;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.face.IFaceAuthenticatorsRegisteredCallback;
import android.hardware.face.IFaceServiceReceiver;
import android.hardware.face.Face;
import android.hardware.face.FaceAuthenticateOptions;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.FaceSensorConfigurations;
import android.view.Surface;

/**
 * Communication channel from client to the face service. These methods are all require the
 * MANAGE_BIOMETRIC signature permission.
 * @hide
 */
interface IFaceService {

    // Creates a test session with the specified sensorId
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    ITestSession createTestSession(int sensorId, ITestSessionCallback callback, String opPackageName);

    // Requests a proto dump of the specified sensor
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    byte[] dumpSensorServiceStateProto(int sensorId, boolean clearSchedulerBuffer);

    // Retrieve static sensor properties for all face sensors
    @EnforcePermission(anyOf = {"USE_BIOMETRIC_INTERNAL", "USE_BACKGROUND_FACE_AUTHENTICATION"})
    List<FaceSensorPropertiesInternal> getSensorPropertiesInternal(String opPackageName);

    // Retrieve static sensor properties for the specified sensor
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    FaceSensorPropertiesInternal getSensorProperties(int sensorId, String opPackageName);

    // Authenticate with a face. A requestId is returned that can be used to cancel this operation.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    long authenticate(IBinder token, long operationId, IFaceServiceReceiver receiver,
            in FaceAuthenticateOptions options);

    // Authenticate with a face. A requestId is returned that can be used to cancel this operation.
    @EnforcePermission("USE_BACKGROUND_FACE_AUTHENTICATION")
    long authenticateInBackground(IBinder token, long operationId, IFaceServiceReceiver receiver,
            in FaceAuthenticateOptions options);

    // Uses the face hardware to detect for the presence of a face, without giving details
    // about accept/reject/lockout. A requestId is returned that can be used to cancel this
    // operation.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    long detectFace(IBinder token, IFaceServiceReceiver receiver, in FaceAuthenticateOptions options);

    // This method prepares the service to start authenticating, but doesn't start authentication.
    // This is protected by the MANAGE_BIOMETRIC signatuer permission. This method should only be
    // called from BiometricService. The additional uid, pid, userId arguments should be determined
    // by BiometricService. To start authentication after the clients are ready, use
    // startPreparedClient().
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void prepareForAuthentication(boolean requireConfirmation, IBinder token,
            long operationId, IBiometricSensorReceiver sensorReceiver,
            in FaceAuthenticateOptions options, long requestId, int cookie,
            boolean allowBackgroundAuthentication);

    // Starts authentication with the previously prepared client.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void startPreparedClient(int sensorId, int cookie);

    // Cancel authentication for the given requestId.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void cancelAuthentication(IBinder token, String opPackageName, long requestId);

    // Cancel face detection for the given requestId.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void cancelFaceDetect(IBinder token, String opPackageName, long requestId);

    // Same as above, with extra arguments.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void cancelAuthenticationFromService(int sensorId, IBinder token, String opPackageName, long requestId);

    // Start face enrollment
    @EnforcePermission("MANAGE_BIOMETRIC")
    long enroll(int userId, IBinder token, in byte [] hardwareAuthToken, IFaceServiceReceiver receiver,
            String opPackageName, in int [] disabledFeatures,
            in Surface previewSurface, boolean debugConsent);

    // Start remote face enrollment
    @EnforcePermission("MANAGE_BIOMETRIC")
    long enrollRemotely(int userId, IBinder token, in byte [] hardwareAuthToken, IFaceServiceReceiver receiver,
            String opPackageName, in int [] disabledFeatures);

    // Cancel enrollment in progress
    @EnforcePermission("MANAGE_BIOMETRIC")
    void cancelEnrollment(IBinder token, long requestId);

    // Removes the specified face enrollment for the specified userId.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void remove(IBinder token, int faceId, int userId, IFaceServiceReceiver receiver,
            String opPackageName);

    // Removes all face enrollments for the specified userId.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void removeAll(IBinder token, int userId, IFaceServiceReceiver receiver, String opPackageName);

    // Get the enrolled face for user.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    List<Face> getEnrolledFaces(int sensorId, int userId, String opPackageName);

    // Determine if HAL is loaded and ready
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    boolean isHardwareDetected(int sensorId, String opPackageName);

    // Get a pre-enrollment authentication token
    @EnforcePermission("MANAGE_BIOMETRIC")
    void generateChallenge(IBinder token, int sensorId, int userId, IFaceServiceReceiver receiver, String opPackageName);

    // Finish an enrollment sequence and invalidate the authentication token
    @EnforcePermission("MANAGE_BIOMETRIC")
    void revokeChallenge(IBinder token, int sensorId, int userId, String opPackageName, long challenge);

    // Determine if a user has at least one enrolled face
    @EnforcePermission(anyOf = {"USE_BIOMETRIC_INTERNAL", "USE_BACKGROUND_FACE_AUTHENTICATION"})
    boolean hasEnrolledFaces(int sensorId, int userId, String opPackageName);

    // Return the LockoutTracker status for the specified user
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    int getLockoutModeForUser(int sensorId, int userId);

    // Requests for the specified sensor+userId's authenticatorId to be invalidated
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void invalidateAuthenticatorId(int sensorId, int userId, IInvalidationCallback callback);

    // Gets the authenticator ID for face
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    long getAuthenticatorId(int sensorId, int callingUserId);

    // Reset the lockout when user authenticates with strong auth (e.g. PIN, pattern or password)
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void resetLockout(IBinder token, int sensorId, int userId, in byte [] hardwareAuthToken, String opPackageName);

    // Add a callback which gets notified when the face lockout period expired.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void addLockoutResetCallback(IBiometricServiceLockoutResetCallback callback, String opPackageName);

    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void setFeature(IBinder token, int userId, int feature, boolean enabled,
            in byte [] hardwareAuthToken, IFaceServiceReceiver receiver, String opPackageName);

    @EnforcePermission("MANAGE_BIOMETRIC")
    void getFeature(IBinder token, int userId, int feature, IFaceServiceReceiver receiver,
            String opPackageName);

    // Registers all HIDL and AIDL sensors. Only HIDL sensor properties need to be provided, because
    // AIDL sensor properties are retrieved directly from the available HALs. If no HIDL HALs exist,
    // hidlSensors must be non-null and empty. See AuthService.java
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void registerAuthenticators(in List<FaceSensorPropertiesInternal> hidlSensors);

    //Register all available face sensors.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void registerAuthenticatorsLegacy(in FaceSensorConfigurations faceSensorConfigurations);

    // Adds a callback which gets called when the service registers all of the face
    // authenticators. The callback is automatically removed after it's invoked.
    void addAuthenticatorsRegisteredCallback(IFaceAuthenticatorsRegisteredCallback callback);

    // Registers BiometricStateListener.
    void registerBiometricStateListener(IBiometricStateListener listener);

    // Internal operation used to clear face biometric scheduler.
    // Ensures that the scheduler is not stuck.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    oneway void scheduleWatchdog();
}
