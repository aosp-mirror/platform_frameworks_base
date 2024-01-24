/*
 * Copyright (C) 2023 The Android Open Source Project
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

/**
 * Low-level callback interface between <Biometric>Manager and <Auth>Service. Allows core system
 * services (e.g. SystemUI) to register a listener for updates about the current state of biometric
 * authentication.
 * @hide
 */
oneway interface AuthenticationStateListener {
    /**
     * Defines behavior in response to authentication starting
     * @param requestReason reason from [BiometricRequestConstants.RequestReason] for requesting
     * authentication starting
     */
    void onAuthenticationStarted(int requestReason);

    /**
     * Defines behavior in response to authentication stopping
     */
    void onAuthenticationStopped();

    /**
     * Defines behavior in response to a successful authentication
     * @param requestReason Reason from [BiometricRequestConstants.RequestReason] for the requested
     *                      authentication
     * @param userId The user Id for the requested authentication
     */
    void onAuthenticationSucceeded(int requestReason, int userId);

    /**
     * Defines behavior in response to a failed authentication
     * @param requestReason Reason from [BiometricRequestConstants.RequestReason] for the requested
     *                      authentication
     * @param userId The user Id for the requested authentication
     */
    void onAuthenticationFailed(int requestReason, int userId);
}
