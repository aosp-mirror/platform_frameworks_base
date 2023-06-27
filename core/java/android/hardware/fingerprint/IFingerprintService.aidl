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

import android.hardware.biometrics.IBiometricSensorReceiver;
import android.hardware.biometrics.IBiometricServiceLockoutResetCallback;
import android.hardware.biometrics.IBiometricStateListener;
import android.hardware.biometrics.IInvalidationCallback;
import android.hardware.biometrics.ITestSession;
import android.hardware.biometrics.ITestSessionCallback;
import android.hardware.biometrics.fingerprint.PointerContext;
import android.hardware.fingerprint.IFingerprintClientActiveCallback;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintAuthenticateOptions;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import java.util.List;

/**
 * Communication channel from client to the fingerprint service.
 * @hide
 */
interface IFingerprintService {

    // Creates a test session with the specified sensorId
    @EnforcePermission("TEST_BIOMETRIC")
    ITestSession createTestSession(int sensorId, ITestSessionCallback callback, String opPackageName);

    // Requests a proto dump of the specified sensor
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    byte[] dumpSensorServiceStateProto(int sensorId, boolean clearSchedulerBuffer);

    // Retrieve static sensor properties for all fingerprint sensors
    List<FingerprintSensorPropertiesInternal> getSensorPropertiesInternal(String opPackageName);

    // Retrieve static sensor properties for the specified sensor
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    FingerprintSensorPropertiesInternal getSensorProperties(int sensorId, String opPackageName);

    // Authenticate with a fingerprint. This is protected by USE_FINGERPRINT/USE_BIOMETRIC
    // permission. This is effectively deprecated, since it only comes through FingerprintManager
    // now. A requestId is returned that can be used to cancel this operation.
    long authenticate(IBinder token, long operationId, IFingerprintServiceReceiver receiver,
            in FingerprintAuthenticateOptions options);

    // Uses the fingerprint hardware to detect for the presence of a finger, without giving details
    // about accept/reject/lockout. A requestId is returned that can be used to cancel this
    // operation.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    long detectFingerprint(IBinder token, IFingerprintServiceReceiver receiver,
            in FingerprintAuthenticateOptions options);

    // This method prepares the service to start authenticating, but doesn't start authentication.
    // This is protected by the MANAGE_BIOMETRIC signatuer permission. This method should only be
    // called from BiometricService. The additional uid, pid, userId arguments should be determined
    // by BiometricService. To start authentication after the clients are ready, use
    // startPreparedClient().
    @EnforcePermission("MANAGE_BIOMETRIC")
    void prepareForAuthentication(IBinder token, long operationId,
            IBiometricSensorReceiver sensorReceiver, in FingerprintAuthenticateOptions options, long requestId,
            int cookie, boolean allowBackgroundAuthentication);

    // Starts authentication with the previously prepared client.
    @EnforcePermission("MANAGE_BIOMETRIC")
    void startPreparedClient(int sensorId, int cookie);

    // Cancel authentication for the given requestId.
    void cancelAuthentication(IBinder token, String opPackageName, String attributionTag, long requestId);

    // Cancel finger detection for the given requestId.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void cancelFingerprintDetect(IBinder token, String opPackageName, long requestId);

    // Same as above, except this is protected by the MANAGE_BIOMETRIC signature permission. Takes
    // an additional uid, pid, userid.
    @EnforcePermission("MANAGE_BIOMETRIC")
    void cancelAuthenticationFromService(int sensorId, IBinder token, String opPackageName, long requestId);

    // Start fingerprint enrollment
    @EnforcePermission("MANAGE_FINGERPRINT")
    long enroll(IBinder token, in byte [] hardwareAuthToken, int userId, IFingerprintServiceReceiver receiver,
            String opPackageName, int enrollReason);

    // Cancel enrollment in progress
    @EnforcePermission("MANAGE_FINGERPRINT")
    void cancelEnrollment(IBinder token, long requestId);

    // Any errors resulting from this call will be returned to the listener
    @EnforcePermission("MANAGE_FINGERPRINT")
    void remove(IBinder token, int fingerId, int userId, IFingerprintServiceReceiver receiver,
            String opPackageName);

    // Removes all face enrollments for the specified userId.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void removeAll(IBinder token, int userId, IFingerprintServiceReceiver receiver, String opPackageName);

    // Rename the fingerprint specified by fingerId and userId to the given name
    @EnforcePermission("MANAGE_FINGERPRINT")
    void rename(int fingerId, int userId, String name);

    // Get a list of enrolled fingerprints in the given userId.
    List<Fingerprint> getEnrolledFingerprints(int userId, String opPackageName, String attributionTag);

    // Determine if the HAL is loaded and ready. Meant to support the deprecated FingerprintManager APIs
    boolean isHardwareDetectedDeprecated(String opPackageName, String attributionTag);

    // Determine if the specified HAL is loaded and ready
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    boolean isHardwareDetected(int sensorId, String opPackageName);

    // Get a pre-enrollment authentication token
    @EnforcePermission("MANAGE_FINGERPRINT")
    void generateChallenge(IBinder token, int sensorId, int userId, IFingerprintServiceReceiver receiver, String opPackageName);

    // Finish an enrollment sequence and invalidate the authentication token
    @EnforcePermission("MANAGE_FINGERPRINT")
    void revokeChallenge(IBinder token, int sensorId, int userId, String opPackageName, long challenge);

    // Determine if a user has at least one enrolled fingerprint. Meant to support the deprecated FingerprintManager APIs
    boolean hasEnrolledFingerprintsDeprecated(int userId, String opPackageName, String attributionTag);

    // Determine if a user has at least one enrolled fingerprint.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    boolean hasEnrolledFingerprints(int sensorId, int userId, String opPackageName);

    // Return the LockoutTracker status for the specified user
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    int getLockoutModeForUser(int sensorId, int userId);

    // Requests for the specified sensor+userId's authenticatorId to be invalidated
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void invalidateAuthenticatorId(int sensorId, int userId, IInvalidationCallback callback);

    // Gets the authenticator ID for fingerprint
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    long getAuthenticatorId(int sensorId, int callingUserId);

    // Reset the timeout when user authenticates with strong auth (e.g. PIN, pattern or password)
    @EnforcePermission("RESET_FINGERPRINT_LOCKOUT")
    void resetLockout(IBinder token, int sensorId, int userId, in byte[] hardwareAuthToken, String opPackageNAame);

    // Add a callback which gets notified when the fingerprint lockout period expired.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void addLockoutResetCallback(IBiometricServiceLockoutResetCallback callback, String opPackageName);

    // Check if a client request is currently being handled
    @EnforcePermission("MANAGE_FINGERPRINT")
    boolean isClientActive();

    // Add a callback which gets notified when the service starts and stops handling client requests
    @EnforcePermission("MANAGE_FINGERPRINT")
    void addClientActiveCallback(IFingerprintClientActiveCallback callback);

    // Removes a callback set by addClientActiveCallback
    @EnforcePermission("MANAGE_FINGERPRINT")
    void removeClientActiveCallback(IFingerprintClientActiveCallback callback);

    // Registers all HIDL and AIDL sensors. Only HIDL sensor properties need to be provided, because
    // AIDL sensor properties are retrieved directly from the available HALs. If no HIDL HALs exist,
    // hidlSensors must be non-null and empty. See AuthService.java
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void registerAuthenticators(in List<FingerprintSensorPropertiesInternal> hidlSensors);

    // Adds a callback which gets called when the service registers all of the fingerprint
    // authenticators. The callback is automatically removed after it's invoked.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void addAuthenticatorsRegisteredCallback(IFingerprintAuthenticatorsRegisteredCallback callback);

    // Notifies about a finger touching the sensor area.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void onPointerDown(long requestId, int sensorId, in PointerContext pc);

    // Notifies about a finger leaving the sensor area.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void onPointerUp(long requestId, int sensorId, in PointerContext pc);

    // Notifies about the fingerprint UI being ready (e.g. HBM illumination is enabled).
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void onUdfpsUiEvent(int event, long requestId, int sensorId);

    // Sets the controller for managing the UDFPS overlay.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void setUdfpsOverlayController(in IUdfpsOverlayController controller);

    // Sets the controller for managing the SideFPS overlay.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void setSidefpsController(in ISidefpsController controller);

    // Registers BiometricStateListener.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void registerBiometricStateListener(IBiometricStateListener listener);

    // Sends a power button pressed event to all listeners.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    oneway void onPowerPressed();

    // Internal operation used to clear fingerprint biometric scheduler.
    // Ensures that the scheduler is not stuck.
    @EnforcePermission("USE_BIOMETRIC_INTERNAL")
    void scheduleWatchdog();
}
