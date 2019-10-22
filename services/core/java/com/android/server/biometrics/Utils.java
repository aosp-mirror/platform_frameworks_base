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
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;

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
     * @param bundle should be first processed by {@link #combineAuthenticatorBundles(Bundle)}
     * @return true if device credential allowed.
     */
    public static boolean isDeviceCredentialAllowed(Bundle bundle) {
        final int authenticators = bundle.getInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED);
        return (authenticators & Authenticators.DEVICE_CREDENTIAL) != 0;
    }
}
