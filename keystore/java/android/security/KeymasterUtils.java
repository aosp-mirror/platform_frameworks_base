/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security;

import android.hardware.fingerprint.FingerprintManager;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;

/**
 * @hide
 */
public abstract class KeymasterUtils {

    private KeymasterUtils() {}

    public static int getDigestOutputSizeBits(int keymasterDigest) {
        switch (keymasterDigest) {
            case KeymasterDefs.KM_DIGEST_NONE:
                return -1;
            case KeymasterDefs.KM_DIGEST_MD5:
                return 128;
            case KeymasterDefs.KM_DIGEST_SHA1:
                return 160;
            case KeymasterDefs.KM_DIGEST_SHA_2_224:
                return 224;
            case KeymasterDefs.KM_DIGEST_SHA_2_256:
                return 256;
            case KeymasterDefs.KM_DIGEST_SHA_2_384:
                return 384;
            case KeymasterDefs.KM_DIGEST_SHA_2_512:
                return 512;
            default:
                throw new IllegalArgumentException("Unknown digest: " + keymasterDigest);
        }
    }

    public static boolean isKeymasterBlockModeIndCpaCompatible(int keymasterBlockMode) {
        switch (keymasterBlockMode) {
            case KeymasterDefs.KM_MODE_ECB:
                return false;
            case KeymasterDefs.KM_MODE_CBC:
            case KeymasterDefs.KM_MODE_CTR:
            case KeymasterDefs.KM_MODE_GCM:
                return true;
            default:
                throw new IllegalArgumentException("Unsupported block mode: " + keymasterBlockMode);
        }
    }

    /**
     * Adds keymaster arguments to express the key's authorization policy supported by user
     * authentication.
     *
     * @param userAuthenticationRequired whether user authentication is required to authorize the
     *        use of the key.
     * @param userAuthenticationValidityDurationSeconds duration of time (seconds) for which user
     *        authentication is valid as authorization for using the key or {@code -1} if every
     *        use of the key needs authorization.
     */
    public static void addUserAuthArgs(KeymasterArguments args,
            boolean userAuthenticationRequired,
            int userAuthenticationValidityDurationSeconds) {
        if (!userAuthenticationRequired) {
            args.addBoolean(KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED);
            return;
        }

        if (userAuthenticationValidityDurationSeconds == -1) {
            // Every use of this key needs to be authorized by the user. This currently means
            // fingerprint-only auth.
            FingerprintManager fingerprintManager =
                    KeyStore.getApplicationContext().getSystemService(FingerprintManager.class);
            if ((fingerprintManager == null) || (!fingerprintManager.isHardwareDetected())) {
                throw new IllegalStateException(
                        "This device does not support keys which require authentication for every"
                        + " use -- this requires fingerprint authentication which is not"
                        + " available on this device");
            }
            long fingerprintOnlySid = fingerprintManager.getAuthenticatorId();
            if (fingerprintOnlySid == 0) {
                throw new IllegalStateException(
                        "At least one fingerprint must be enrolled to create keys requiring user"
                        + " authentication for every use");
            }
            args.addLong(KeymasterDefs.KM_TAG_USER_SECURE_ID, fingerprintOnlySid);
            args.addInt(KeymasterDefs.KM_TAG_USER_AUTH_TYPE, KeymasterDefs.HW_AUTH_FINGERPRINT);
        } else {
            // The key is authorized for use for the specified amount of time after the user has
            // authenticated. Whatever unlocks the secure lock screen should authorize this key.
            long rootSid = GateKeeper.getSecureUserId();
            if (rootSid == 0) {
                throw new IllegalStateException("Secure lock screen must be enabled"
                        + " to create keys requiring user authentication");
            }
            args.addLong(KeymasterDefs.KM_TAG_USER_SECURE_ID, rootSid);
            args.addInt(KeymasterDefs.KM_TAG_USER_AUTH_TYPE,
                    KeymasterDefs.HW_AUTH_PASSWORD | KeymasterDefs.HW_AUTH_FINGERPRINT);
            args.addInt(KeymasterDefs.KM_TAG_AUTH_TIMEOUT,
                    userAuthenticationValidityDurationSeconds);
        }
    }
}
