/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import static com.android.server.biometrics.sensors.BiometricScheduler.SENSOR_TYPE_FACE;
import static com.android.server.biometrics.sensors.BiometricScheduler.SENSOR_TYPE_UDFPS;
import static com.android.server.biometrics.sensors.BiometricScheduler.sensorTypeToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.biometrics.BiometricConstants;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.sensors.BiometricScheduler.SensorType;
import com.android.server.biometrics.sensors.fingerprint.Udfps;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * Singleton that contains the core logic for determining if haptics and authentication callbacks
 * should be sent to receivers. Note that this class is used even when coex is not required (e.g.
 * single sensor devices, or multi-sensor devices where only a single sensor is authenticating).
 * This allows us to have all business logic in one testable place.
 */
public class CoexCoordinator {

    private static final String TAG = "BiometricCoexCoordinator";
    public static final String SETTING_ENABLE_NAME =
            "com.android.server.biometrics.sensors.CoexCoordinator.enable";
    public static final String FACE_HAPTIC_DISABLE =
            "com.android.server.biometrics.sensors.CoexCoordinator.disable_face_haptics";
    private static final boolean DEBUG = true;

    // Successful authentications should be used within this amount of time.
    static final long SUCCESSFUL_AUTH_VALID_DURATION_MS = 5000;

    /**
     * Callback interface notifying the owner of "results" from the CoexCoordinator's business
     * logic for accept and reject.
     */
    interface Callback {
        /**
         * Requests the owner to send the result (success/reject) and any associated info to the
         * receiver (e.g. keyguard, BiometricService, etc).
         */
        void sendAuthenticationResult(boolean addAuthTokenIfStrong);

        /**
         * Requests the owner to initiate a vibration for this event.
         */
        void sendHapticFeedback();

        /**
         * Requests the owner to handle the AuthenticationClient's lifecycle (e.g. finish and remove
         * from scheduler if auth was successful).
         */
        void handleLifecycleAfterAuth();

        /**
         * Requests the owner to notify the caller that authentication was canceled.
         */
        void sendAuthenticationCanceled();
    }

    /**
     * Callback interface notifying the owner of "results" from the CoexCoordinator's business
     * logic for errors.
     */
    interface ErrorCallback {
        /**
         * Requests the owner to initiate a vibration for this event.
         */
        void sendHapticFeedback();
    }

    private static CoexCoordinator sInstance;

    @VisibleForTesting
    public static class SuccessfulAuth {
        final long mAuthTimestamp;
        final @SensorType int mSensorType;
        final AuthenticationClient<?> mAuthenticationClient;
        final Callback mCallback;
        final CleanupRunnable mCleanupRunnable;

        public static class CleanupRunnable implements Runnable {
            @NonNull final LinkedList<SuccessfulAuth> mSuccessfulAuths;
            @NonNull final SuccessfulAuth mAuth;
            @NonNull final Callback mCallback;

            public CleanupRunnable(@NonNull LinkedList<SuccessfulAuth> successfulAuths,
                    @NonNull SuccessfulAuth auth, @NonNull Callback callback) {
                mSuccessfulAuths = successfulAuths;
                mAuth = auth;
                mCallback = callback;
            }

            @Override
            public void run() {
                final boolean removed = mSuccessfulAuths.remove(mAuth);
                Slog.w(TAG, "Removing stale successfulAuth: " + mAuth.toString()
                        + ", success: " + removed);
                mCallback.handleLifecycleAfterAuth();
            }
        }

        public SuccessfulAuth(@NonNull Handler handler,
                @NonNull LinkedList<SuccessfulAuth> successfulAuths,
                long currentTimeMillis,
                @SensorType int sensorType,
                @NonNull AuthenticationClient<?> authenticationClient,
                @NonNull Callback callback) {
            mAuthTimestamp = currentTimeMillis;
            mSensorType = sensorType;
            mAuthenticationClient = authenticationClient;
            mCallback = callback;

            mCleanupRunnable = new CleanupRunnable(successfulAuths, this, callback);

            handler.postDelayed(mCleanupRunnable, SUCCESSFUL_AUTH_VALID_DURATION_MS);
        }

        @Override
        public String toString() {
            return "SensorType: " + sensorTypeToString(mSensorType)
                    + ", mAuthTimestamp: " + mAuthTimestamp
                    + ", authenticationClient: " + mAuthenticationClient;
        }
    }

    /**
     * @return a singleton instance.
     */
    @NonNull
    public static CoexCoordinator getInstance() {
        if (sInstance == null) {
            sInstance = new CoexCoordinator();
        }
        return sInstance;
    }

    @VisibleForTesting
    public void setAdvancedLogicEnabled(boolean enabled) {
        mAdvancedLogicEnabled = enabled;
    }

    public void setFaceHapticDisabledWhenNonBypass(boolean disabled) {
        mFaceHapticDisabledWhenNonBypass = disabled;
    }

    @VisibleForTesting
    void reset() {
        mClientMap.clear();
    }

    // SensorType to AuthenticationClient map
    private final Map<Integer, AuthenticationClient<?>> mClientMap;
    @VisibleForTesting final LinkedList<SuccessfulAuth> mSuccessfulAuths;
    private boolean mAdvancedLogicEnabled;
    private boolean mFaceHapticDisabledWhenNonBypass;
    private final Handler mHandler;

    private CoexCoordinator() {
        // Singleton
        mClientMap = new HashMap<>();
        mSuccessfulAuths = new LinkedList<>();
        mHandler = new Handler(Looper.getMainLooper());
    }

    public void addAuthenticationClient(@BiometricScheduler.SensorType int sensorType,
            @NonNull AuthenticationClient<?> client) {
        if (DEBUG) {
            Slog.d(TAG, "addAuthenticationClient(" + sensorTypeToString(sensorType) + ")"
                    + ", client: " + client);
        }

        if (mClientMap.containsKey(sensorType)) {
            Slog.w(TAG, "Overwriting existing client: " + mClientMap.get(sensorType)
                    + " with new client: " + client);
        }

        mClientMap.put(sensorType, client);
    }

    public void removeAuthenticationClient(@BiometricScheduler.SensorType int sensorType,
            @NonNull AuthenticationClient<?> client) {
        if (DEBUG) {
            Slog.d(TAG, "removeAuthenticationClient(" + sensorTypeToString(sensorType) + ")"
                    + ", client: " + client);
        }

        if (!mClientMap.containsKey(sensorType)) {
            Slog.e(TAG, "sensorType: " + sensorType + " does not exist in map. Client: " + client);
            return;
        }
        mClientMap.remove(sensorType);
    }

    /**
     * Notify the coordinator that authentication succeeded (accepted)
     */
    public void onAuthenticationSucceeded(long currentTimeMillis,
            @NonNull AuthenticationClient<?> client,
            @NonNull Callback callback) {
        if (client.isBiometricPrompt()) {
            callback.sendHapticFeedback();
            // For BP, BiometricService will add the authToken to Keystore.
            callback.sendAuthenticationResult(false /* addAuthTokenIfStrong */);
            callback.handleLifecycleAfterAuth();
        } else if (isUnknownClient(client)) {
            // Client doesn't exist in our map for some reason. Give the user feedback so the
            // device doesn't feel like it's stuck. All other cases below can assume that the
            // client exists in our map.
            callback.sendHapticFeedback();
            callback.sendAuthenticationResult(true /* addAuthTokenIfStrong */);
            callback.handleLifecycleAfterAuth();
        } else if (mAdvancedLogicEnabled && client.isKeyguard()) {
            if (isSingleAuthOnly(client)) {
                // Single sensor authentication
                callback.sendHapticFeedback();
                callback.sendAuthenticationResult(true /* addAuthTokenIfStrong */);
                callback.handleLifecycleAfterAuth();
            } else {
                // Multi sensor authentication
                AuthenticationClient<?> udfps = mClientMap.getOrDefault(SENSOR_TYPE_UDFPS, null);
                AuthenticationClient<?> face = mClientMap.getOrDefault(SENSOR_TYPE_FACE, null);
                if (isCurrentFaceAuth(client)) {
                    if (isUdfpsActivelyAuthing(udfps)) {
                        // Face auth success while UDFPS is actively authing. No callback, no haptic
                        // Feedback will be provided after UDFPS result:
                        // 1) UDFPS succeeds - simply remove this from the queue
                        // 2) UDFPS rejected - use this face auth success to notify clients
                        mSuccessfulAuths.add(new SuccessfulAuth(mHandler, mSuccessfulAuths,
                                currentTimeMillis, SENSOR_TYPE_FACE, client, callback));
                    } else {
                        if (mFaceHapticDisabledWhenNonBypass && !face.isKeyguardBypassEnabled()) {
                            Slog.w(TAG, "Skipping face success haptic");
                        } else {
                            callback.sendHapticFeedback();
                        }
                        callback.sendAuthenticationResult(true /* addAuthTokenIfStrong */);
                        callback.handleLifecycleAfterAuth();
                    }
                } else if (isCurrentUdfps(client)) {
                    if (isFaceScanning()) {
                        // UDFPS succeeds while face is still scanning
                        // Cancel face auth and/or prevent it from invoking haptics/callbacks after
                        face.cancel();
                    }

                    removeAndFinishAllFaceFromQueue();

                    callback.sendHapticFeedback();
                    callback.sendAuthenticationResult(true /* addAuthTokenIfStrong */);
                    callback.handleLifecycleAfterAuth();
                } else {
                    // Capacitive fingerprint sensor (or other)
                    callback.sendHapticFeedback();
                    callback.sendAuthenticationResult(true /* addAuthTokenIfStrong */);
                    callback.handleLifecycleAfterAuth();
                }
            }
        } else {
            // Non-keyguard authentication. For example, Fingerprint Settings use of
            // FingerprintManager for highlighting fingers
            callback.sendHapticFeedback();
            callback.sendAuthenticationResult(true /* addAuthTokenIfStrong */);
            callback.handleLifecycleAfterAuth();
        }
    }

    /**
     * Notify the coordinator that a rejection has occurred.
     */
    public void onAuthenticationRejected(long currentTimeMillis,
            @NonNull AuthenticationClient<?> client,
            @LockoutTracker.LockoutMode int lockoutMode,
            @NonNull Callback callback) {
        final boolean keyguardAdvancedLogic = mAdvancedLogicEnabled && client.isKeyguard();

        if (keyguardAdvancedLogic) {
            if (isSingleAuthOnly(client)) {
                callback.sendHapticFeedback();
                callback.handleLifecycleAfterAuth();
            } else {
                // Multi sensor authentication
                AuthenticationClient<?> udfps = mClientMap.getOrDefault(SENSOR_TYPE_UDFPS, null);
                AuthenticationClient<?> face = mClientMap.getOrDefault(SENSOR_TYPE_FACE, null);
                if (isCurrentFaceAuth(client)) {
                    if (isUdfpsActivelyAuthing(udfps)) {
                        // UDFPS should still be running in this case, do not vibrate. However, we
                        // should notify the callback and finish the client, so that Keyguard and
                        // BiometricScheduler do not get stuck.
                        Slog.d(TAG, "Face rejected in multi-sensor auth, udfps: " + udfps);
                        callback.handleLifecycleAfterAuth();
                    } else if (isUdfpsAuthAttempted(udfps)) {
                        // If UDFPS is STATE_STARTED_PAUSED (e.g. finger rejected but can still
                        // auth after pointer goes down, it means UDFPS encountered a rejection. In
                        // this case, we need to play the final reject haptic since face auth is
                        // also done now.
                        callback.sendHapticFeedback();
                        callback.handleLifecycleAfterAuth();
                    }
                    else {
                        // UDFPS auth has never been attempted.
                        if (mFaceHapticDisabledWhenNonBypass && !face.isKeyguardBypassEnabled()) {
                            Slog.w(TAG, "Skipping face reject haptic");
                        } else {
                            callback.sendHapticFeedback();
                        }
                        callback.handleLifecycleAfterAuth();
                    }
                } else if (isCurrentUdfps(client)) {
                    // Face should either be running, or have already finished
                    SuccessfulAuth auth = popSuccessfulFaceAuthIfExists(currentTimeMillis);
                    if (auth != null) {
                        Slog.d(TAG, "Using recent auth: " + auth);
                        callback.handleLifecycleAfterAuth();

                        auth.mCallback.sendHapticFeedback();
                        auth.mCallback.sendAuthenticationResult(true /* addAuthTokenIfStrong */);
                        auth.mCallback.handleLifecycleAfterAuth();
                    } else if (isFaceScanning()) {
                        // UDFPS rejected but face is still scanning
                        Slog.d(TAG, "UDFPS rejected in multi-sensor auth, face: " + face);
                        callback.handleLifecycleAfterAuth();

                        // TODO(b/193089985): Enforce/ensure that face auth finishes (whether
                        //  accept/reject) within X amount of time. Otherwise users will be stuck
                        //  waiting with their finger down for a long time.
                    } else {
                        // Face not scanning, and was not found in the queue. Most likely, face
                        // auth was too long ago.
                        Slog.d(TAG, "UDFPS rejected in multi-sensor auth, face not scanning");
                        callback.sendHapticFeedback();
                        callback.handleLifecycleAfterAuth();
                    }
                } else {
                    Slog.d(TAG, "Unknown client rejected: " + client);
                    callback.sendHapticFeedback();
                    callback.handleLifecycleAfterAuth();
                }
            }
        } else {
            callback.sendHapticFeedback();
            callback.handleLifecycleAfterAuth();
        }

        // Always notify keyguard, otherwise the cached "running" state in KeyguardUpdateMonitor
        // will get stuck.
        if (lockoutMode == LockoutTracker.LOCKOUT_NONE) {
            // Don't send onAuthenticationFailed if we're in lockout, it causes a
            // janky UI on Keyguard/BiometricPrompt since "authentication failed"
            // will show briefly and be replaced by "device locked out" message.
            callback.sendAuthenticationResult(false /* addAuthTokenIfStrong */);
        }
    }

    /**
     * Notify the coordinator that an error has occurred.
     */
    public void onAuthenticationError(@NonNull AuthenticationClient<?> client,
            @BiometricConstants.Errors int error, @NonNull ErrorCallback callback) {
        // Figure out non-coex state
        final boolean shouldUsuallyVibrate;
        if (isCurrentFaceAuth(client)) {
            final boolean notDetectedOnKeyguard = client.isKeyguard() && !client.wasUserDetected();
            final boolean authAttempted = client.wasAuthAttempted();

            switch (error) {
                case BiometricConstants.BIOMETRIC_ERROR_TIMEOUT:
                case BiometricConstants.BIOMETRIC_ERROR_LOCKOUT:
                case BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT:
                    shouldUsuallyVibrate = authAttempted && !notDetectedOnKeyguard;
                    break;
                default:
                    shouldUsuallyVibrate = false;
                    break;
            }
        } else {
            shouldUsuallyVibrate = false;
        }

        // Figure out coex state
        final boolean keyguardAdvancedLogic = mAdvancedLogicEnabled && client.isKeyguard();
        final boolean hapticSuppressedByCoex;

        if (keyguardAdvancedLogic) {
            if (isSingleAuthOnly(client)) {
                hapticSuppressedByCoex = false;
            } else {
                hapticSuppressedByCoex = isCurrentFaceAuth(client)
                        && !client.isKeyguardBypassEnabled();
            }
        } else {
            hapticSuppressedByCoex = false;
        }

        // Combine and send feedback if appropriate
        Slog.d(TAG, "shouldUsuallyVibrate: " + shouldUsuallyVibrate
                + ", hapticSuppressedByCoex: " + hapticSuppressedByCoex);
        if (shouldUsuallyVibrate && !hapticSuppressedByCoex) {
            callback.sendHapticFeedback();
        }
    }

    @Nullable
    private SuccessfulAuth popSuccessfulFaceAuthIfExists(long currentTimeMillis) {
        for (SuccessfulAuth auth : mSuccessfulAuths) {
            if (currentTimeMillis - auth.mAuthTimestamp >= SUCCESSFUL_AUTH_VALID_DURATION_MS) {
                // TODO(b/193089985): This removes the auth but does not notify the client with
                //  an appropriate lifecycle event (such as ERROR_CANCELED), and violates the
                //  API contract. However, this might be OK for now since the validity duration
                //  is way longer than the time it takes to auth with fingerprint.
                Slog.e(TAG, "Removing stale auth: " + auth);
                mSuccessfulAuths.remove(auth);
            } else if (auth.mSensorType == SENSOR_TYPE_FACE) {
                mSuccessfulAuths.remove(auth);
                return auth;
            }
        }
        return null;
    }

    private void removeAndFinishAllFaceFromQueue() {
        // Note that these auth are all successful, but have never notified the client (e.g.
        // keyguard). To comply with the authentication lifecycle, we must notify the client that
        // auth is "done". The safest thing to do is to send ERROR_CANCELED.
        for (SuccessfulAuth auth : mSuccessfulAuths) {
            if (auth.mSensorType == SENSOR_TYPE_FACE) {
                Slog.d(TAG, "Removing from queue, canceling, and finishing: " + auth);
                auth.mCallback.sendAuthenticationCanceled();
                auth.mCallback.handleLifecycleAfterAuth();
                mSuccessfulAuths.remove(auth);
            }
        }
    }

    private boolean isCurrentFaceAuth(@NonNull AuthenticationClient<?> client) {
        return client == mClientMap.getOrDefault(SENSOR_TYPE_FACE, null);
    }

    private boolean isCurrentUdfps(@NonNull AuthenticationClient<?> client) {
        return client == mClientMap.getOrDefault(SENSOR_TYPE_UDFPS, null);
    }

    private boolean isFaceScanning() {
        AuthenticationClient<?> client = mClientMap.getOrDefault(SENSOR_TYPE_FACE, null);
        return client != null && client.getState() == AuthenticationClient.STATE_STARTED;
    }

    private static boolean isUdfpsActivelyAuthing(@Nullable AuthenticationClient<?> client) {
        if (client instanceof Udfps) {
            return client.getState() == AuthenticationClient.STATE_STARTED;
        }
        return false;
    }

    private static boolean isUdfpsAuthAttempted(@Nullable AuthenticationClient<?> client) {
        if (client instanceof Udfps) {
            return client.getState() == AuthenticationClient.STATE_STARTED_PAUSED_ATTEMPTED;
        }
        return false;
    }

    private boolean isUnknownClient(@NonNull AuthenticationClient<?> client) {
        for (AuthenticationClient<?> c : mClientMap.values()) {
            if (c == client) {
                return false;
            }
        }
        return true;
    }

    private boolean isSingleAuthOnly(@NonNull AuthenticationClient<?> client) {
        if (mClientMap.values().size() != 1) {
            return false;
        }

        for (AuthenticationClient<?> c : mClientMap.values()) {
            if (c != client) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Enabled: ").append(mAdvancedLogicEnabled);
        sb.append(", Face Haptic Disabled: ").append(mFaceHapticDisabledWhenNonBypass);
        sb.append(", Queue size: " ).append(mSuccessfulAuths.size());
        for (SuccessfulAuth auth : mSuccessfulAuths) {
            sb.append(", Auth: ").append(auth.toString());
        }

        return sb.toString();
    }
}
