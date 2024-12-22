/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.hardware.biometrics.SensorPropertiesInternal;

/**
 * A test service for FingerprintManager and BiometricManager.
 * @hide
 */
interface ITestSession {
    // Switches the specified sensor to use a test HAL. In this mode, the framework will not invoke
    // any methods on the real HAL implementation. This allows the framework to test a substantial
    // portion of the framework code that would otherwise require human interaction. Note that
    // secure pathways such as HAT/Keystore are not testable, since they depend on the TEE or its
    // equivalent for the secret key.
    @EnforcePermission("TEST_BIOMETRIC")
    void setTestHalEnabled(boolean enableTestHal);

    // Starts the enrollment process. This should generally be used when the test HAL is enabled.
    @EnforcePermission("TEST_BIOMETRIC")
    void startEnroll(int userId);

    // Finishes the enrollment process. Simulates the HAL's callback.
    @EnforcePermission("TEST_BIOMETRIC")
    void finishEnroll(int userId);

    // Simulates a successful authentication, but does not provide a valid HAT.
    @EnforcePermission("TEST_BIOMETRIC")
    void acceptAuthentication(int userId);

    // Simulates a rejected attempt.
    @EnforcePermission("TEST_BIOMETRIC")
    void rejectAuthentication(int userId);

    // Simulates an acquired message from the HAL.
    @EnforcePermission("TEST_BIOMETRIC")
    void notifyAcquired(int userId, int acquireInfo);

    // Simulates an error message from the HAL.
    @EnforcePermission("TEST_BIOMETRIC")
    void notifyError(int userId, int errorCode);

    // Matches the framework's cached enrollments against the HAL's enrollments. Any enrollment
    // that isn't known by both sides are deleted. This should generally be used when the test
    // HAL is disabled (e.g. to clean up after a test).
    @EnforcePermission("TEST_BIOMETRIC")
    void cleanupInternalState(int userId);

    // Get the sensor id of the current test session.
    @EnforcePermission("TEST_BIOMETRIC")
    int getSensorId();
}
