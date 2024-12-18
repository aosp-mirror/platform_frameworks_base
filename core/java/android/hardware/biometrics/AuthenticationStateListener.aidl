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

import android.hardware.biometrics.events.AuthenticationAcquiredInfo;
import android.hardware.biometrics.events.AuthenticationErrorInfo;
import android.hardware.biometrics.events.AuthenticationFailedInfo;
import android.hardware.biometrics.events.AuthenticationHelpInfo;
import android.hardware.biometrics.events.AuthenticationStartedInfo;
import android.hardware.biometrics.events.AuthenticationStoppedInfo;
import android.hardware.biometrics.events.AuthenticationSucceededInfo;

/**
 * Low-level callback interface between <Biometric>Manager and <Auth>Service. Allows core system
 * services (e.g. SystemUI) to register a listener for updates about the current state of biometric
 * authentication.
 * @hide
 */
oneway interface AuthenticationStateListener {
    /**
     * Defines behavior in response to biometric authentication being acquired.
     * @param authInfo information related to the biometric authentication acquired.
     */
    void onAuthenticationAcquired(in AuthenticationAcquiredInfo authInfo);

    /**
     * Defines behavior in response to an unrecoverable error encountered during authentication.
     * @param authInfo information related to the unrecoverable auth error encountered
     */
    void onAuthenticationError(in AuthenticationErrorInfo authInfo);

    /**
     * Defines behavior in response to a failed authentication
     * @param authInfo information related to the failed authentication
     */
    void onAuthenticationFailed(in AuthenticationFailedInfo authInfo);

    /**
     * Defines behavior in response to a recoverable error encountered during authentication.
     * @param authInfo information related to the recoverable auth error encountered
     */
    void onAuthenticationHelp(in AuthenticationHelpInfo authInfo);

    /**
     * Defines behavior in response to authentication starting
     * @param authInfo information related to the authentication starting
     */
    void onAuthenticationStarted(in AuthenticationStartedInfo authInfo);

    /**
     * Defines behavior in response to authentication stopping
     * @param authInfo information related to the authentication stopping
     */
    void onAuthenticationStopped(in AuthenticationStoppedInfo authInfo);

    /**
     * Defines behavior in response to a successful authentication
     * @param authInfo information related to the successful authentication
     */
    void onAuthenticationSucceeded(in AuthenticationSucceededInfo authInfo);
}
