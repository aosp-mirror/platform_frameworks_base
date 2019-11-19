/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.biometrics;

import static android.hardware.biometrics.BiometricManager.Authenticators;

import android.content.Context;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

public class Utils {
    public static boolean isDebugEnabled(Context context, int targetUserId) {
        if (targetUserId == UserHandle.USER_NULL) {
            return false;
        }

        if (!(Build.IS_ENG || Build.IS_USERDEBUG)) {
            return false;
        }

        if (Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.BIOMETRIC_DEBUG_ENABLED, 0,
                targetUserId) == 0) {
            return false;
        }
        return true;
    }

    /**
     * Combines {@link BiometricPrompt#KEY_ALLOW_DEVICE_CREDENTIAL} with
     * {@link BiometricPrompt#KEY_AUTHENTICATORS_ALLOWED}, as the former is not flexible enough.
     */
    public static void combineAuthenticatorBundles(Bundle bundle) {
        // Cache and remove explicit ALLOW_DEVICE_CREDENTIAL boolean flag from the bundle.
        final boolean deviceCredentialAllowed =
                bundle.getBoolean(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL, false);
        bundle.remove(BiometricPrompt.KEY_ALLOW_DEVICE_CREDENTIAL);

        final @Authenticators.Types int authenticators;
        if (bundle.containsKey(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED)) {
            // Ignore ALLOW_DEVICE_CREDENTIAL flag if AUTH_TYPES_ALLOWED is defined.
            authenticators = bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, 0);
        } else {
            // Otherwise, use ALLOW_DEVICE_CREDENTIAL flag along with Weak+ biometrics by default.
            authenticators = deviceCredentialAllowed
                    ? Authenticators.DEVICE_CREDENTIAL | Authenticators.BIOMETRIC_WEAK
                    : Authenticators.BIOMETRIC_WEAK;
        }

        bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
    }

    /**
     * @param authenticators composed of one or more values from {@link Authenticators}
     * @return true if device credential is allowed.
     */
    public static boolean isDeviceCredentialAllowed(@Authenticators.Types int authenticators) {
        return (authenticators & Authenticators.DEVICE_CREDENTIAL) != 0;
    }

    /**
     * @param bundle should be first processed by {@link #combineAuthenticatorBundles(Bundle)}
     * @return true if device credential is allowed.
     */
    public static boolean isDeviceCredentialAllowed(Bundle bundle) {
        return isDeviceCredentialAllowed(bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED));
    }

    /**
     * @param authenticators composed of one or more values from {@link Authenticators}
     * @return minimal allowed biometric strength or 0 if biometric authentication is not allowed.
     */
    public static int getBiometricStrength(@Authenticators.Types int authenticators) {
        // Only biometrics WEAK and above are allowed to integrate with the public APIs.
        return authenticators & Authenticators.BIOMETRIC_WEAK;
    }

    /**
     * @param bundle should be first processed by {@link #combineAuthenticatorBundles(Bundle)}
     * @return minimal allowed biometric strength or 0 if biometric authentication is not allowed.
     */
    public static int getBiometricStrength(Bundle bundle) {
        return getBiometricStrength(bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED));
    }

    /**
     * @param bundle should be first processed by {@link #combineAuthenticatorBundles(Bundle)}
     * @return true if biometric authentication is allowed.
     */
    public static boolean isBiometricAllowed(Bundle bundle) {
        return getBiometricStrength(bundle) != 0;
    }

    /**
     * @param sensorStrength the strength of the sensor
     * @param requestedStrength the strength that it must meet
     * @return true only if the sensor is at least as strong as the requested strength
     */
    public static boolean isAtLeastStrength(int sensorStrength, int requestedStrength) {
        // If the authenticator contains bits outside of the requested strength, it is too weak.
        return (~requestedStrength & sensorStrength) == 0;
    }

    /**
     * Converts error codes from BiometricConstants, which are used in most of the internal plumbing
     * and eventually returned to {@link BiometricPrompt.AuthenticationCallback} to public
     * {@link BiometricManager} constants, which are used by APIs such as
     * {@link BiometricManager#canAuthenticate(int)}
     *
     * @param biometricConstantsCode see {@link BiometricConstants}
     * @return see {@link BiometricManager}
     */
    public static int biometricConstantsToBiometricManager(int biometricConstantsCode) {
        final int biometricManagerCode;

        switch (biometricConstantsCode) {
            case BiometricConstants.BIOMETRIC_SUCCESS:
                biometricManagerCode = BiometricManager.BIOMETRIC_SUCCESS;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS:
            case BiometricConstants.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;
                break;
            case BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT:
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE;
                break;
            default:
                Slog.e(BiometricService.TAG, "Unhandled result code: " + biometricConstantsCode);
                biometricManagerCode = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;
                break;
        }
        return biometricManagerCode;
    }
}
