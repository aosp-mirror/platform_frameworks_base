/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.hardware.biometrics.BiometricManager.Authenticators;

/**
 * An interface that listens to authentication events.
 */
interface AuthSessionListener {
    /**
     * Indicates an auth operation has started for a given user and sensor.
     */
    void authStartedFor(int userId, int sensorId, long requestId);

    /**
     * Indicates authentication ended for a sensor of a given strength.
     */
    void authEndedFor(int userId, @Authenticators.Types int biometricStrength, int sensorId,
            long requestId, boolean wasSuccessful);

    /**
     * Indicates a lockout occurred for a sensor of a given strength.
     */
    void lockedOutFor(int userId, @Authenticators.Types int biometricStrength, int sensorId,
            long requestId);

    /**
     * Indicates a timed lockout occurred for a sensor of a given strength.
     */
    void lockOutTimed(int userId, @Authenticators.Types int biometricStrength, int sensorId,
            long duration, long requestId);

    /**
     * Indicates that a reset lockout has happened for a given strength.
     */
    void resetLockoutFor(int uerId, @Authenticators.Types int biometricStrength, long requestId);
}
