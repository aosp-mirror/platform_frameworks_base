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

import static com.android.server.biometrics.sensors.BiometricScheduler.sensorTypeToString;

import android.annotation.NonNull;
import android.util.Slog;

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

    @NonNull
    static CoexCoordinator getInstance() {
        if (sInstance == null) {
            sInstance = new CoexCoordinator();
        }
        return sInstance;
    }

    // SensorType to AuthenticationClient map
    private final Map<Integer, AuthenticationClient<?>> mClientMap;

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
        } else {
            // Keyguard, FingerprintManager, FaceManager, etc
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
}
