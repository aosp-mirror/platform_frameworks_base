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
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.sensors.fingerprint.Udfps;

import java.util.HashMap;
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
    private static final boolean DEBUG = true;

    /**
     * Callback interface notifying the owner of "results" from the CoexCoordinator's business
     * logic.
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
    }

    private static CoexCoordinator sInstance;

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

    @VisibleForTesting
    void reset() {
        mClientMap.clear();
    }

    // SensorType to AuthenticationClient map
    private final Map<Integer, AuthenticationClient<?>> mClientMap;
    private boolean mAdvancedLogicEnabled;

    private CoexCoordinator() {
        // Singleton
        mClientMap = new HashMap<>();
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

    public void onAuthenticationSucceeded(@NonNull AuthenticationClient<?> client,
            @NonNull Callback callback) {
        if (client.isBiometricPrompt()) {
            callback.sendHapticFeedback();
            // For BP, BiometricService will add the authToken to Keystore.
            callback.sendAuthenticationResult(false /* addAuthTokenIfStrong */);
        } else if (isUnknownClient(client)) {
            // Client doesn't exist in our map for some reason. Give the user feedback so the
            // device doesn't feel like it's stuck. All other cases below can assume that the
            // client exists in our map.
            callback.sendHapticFeedback();
            callback.sendAuthenticationResult(true /* addAuthTokenIfStrong */);
        } else if (mAdvancedLogicEnabled && client.isKeyguard()) {
            if (isSingleAuthOnly(client)) {
                // Single sensor authentication
                callback.sendHapticFeedback();
                callback.sendAuthenticationResult(true /* addAuthTokenIfStrong */);
            } else {
                // Multi sensor authentication
                AuthenticationClient<?> udfps = mClientMap.getOrDefault(SENSOR_TYPE_UDFPS, null);
                if (isCurrentFaceAuth(client)) {
                    if (isPointerDown(udfps)) {
                        // Face auth success while UDFPS pointer down. No callback, no haptic.
                        // Feedback will be provided after UDFPS result.
                    } else {
                        callback.sendHapticFeedback();
                        callback.sendAuthenticationResult(true /* addAuthTokenIfStrong */);
                    }
                }
            }
        } else {
            // Non-keyguard authentication. For example, Fingerprint Settings use of
            // FingerprintManager for highlighting fingers
            callback.sendHapticFeedback();
            callback.sendAuthenticationResult(true /* addAuthTokenIfStrong */);
        }
    }

    public void onAuthenticationRejected(@NonNull AuthenticationClient<?> client,
            @LockoutTracker.LockoutMode int lockoutMode,
            @NonNull Callback callback) {
        callback.sendHapticFeedback();
        if (lockoutMode == LockoutTracker.LOCKOUT_NONE) {
            // Don't send onAuthenticationFailed if we're in lockout, it causes a
            // janky UI on Keyguard/BiometricPrompt since "authentication failed"
            // will show briefly and be replaced by "device locked out" message.
            callback.sendAuthenticationResult(false /* addAuthTokenIfStrong */);
        }
    }

    private boolean isCurrentFaceAuth(@NonNull AuthenticationClient<?> client) {
        return client == mClientMap.getOrDefault(SENSOR_TYPE_FACE, null);
    }

    private boolean isPointerDown(@Nullable AuthenticationClient<?> client) {
        if (client instanceof Udfps) {
            return ((Udfps) client).isPointerDown();
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

    public String toString() {
        return "Enabled: " + mAdvancedLogicEnabled;
    }
}
