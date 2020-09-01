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

package android.security.keystore;

import android.hardware.biometrics.BiometricManager;
import android.security.GateKeeper;
import android.security.KeyStore;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;

import java.security.ProviderException;
import java.util.ArrayList;
import java.util.List;

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

    public static boolean isKeymasterBlockModeIndCpaCompatibleWithSymmetricCrypto(
            int keymasterBlockMode) {
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

    public static boolean isKeymasterPaddingSchemeIndCpaCompatibleWithAsymmetricCrypto(
            int keymasterPadding) {
        switch (keymasterPadding) {
            case KeymasterDefs.KM_PAD_NONE:
                return false;
            case KeymasterDefs.KM_PAD_RSA_OAEP:
            case KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_ENCRYPT:
                return true;
            default:
                throw new IllegalArgumentException(
                        "Unsupported asymmetric encryption padding scheme: " + keymasterPadding);
        }
    }

    private static void addSids(KeymasterArguments args, UserAuthArgs spec) {
        // If both biometric and credential are accepted, then just use the root sid from gatekeeper
        if (spec.getUserAuthenticationType() == (KeyProperties.AUTH_BIOMETRIC_STRONG
                                                 | KeyProperties.AUTH_DEVICE_CREDENTIAL)) {
            if (spec.getBoundToSpecificSecureUserId() != GateKeeper.INVALID_SECURE_USER_ID) {
                args.addUnsignedLong(KeymasterDefs.KM_TAG_USER_SECURE_ID,
                        KeymasterArguments.toUint64(spec.getBoundToSpecificSecureUserId()));
            } else {
                // The key is authorized for use for the specified amount of time after the user has
                // authenticated. Whatever unlocks the secure lock screen should authorize this key.
                args.addUnsignedLong(KeymasterDefs.KM_TAG_USER_SECURE_ID,
                        KeymasterArguments.toUint64(getRootSid()));
            }
        } else {
            List<Long> sids = new ArrayList<>();
            if ((spec.getUserAuthenticationType() & KeyProperties.AUTH_BIOMETRIC_STRONG) != 0) {
                final BiometricManager bm = KeyStore.getApplicationContext()
                        .getSystemService(BiometricManager.class);

                // TODO: Restore permission check in getAuthenticatorIds once the ID is no longer
                // needed here.

                final long[] biometricSids = bm.getAuthenticatorIds();

                if (biometricSids.length == 0) {
                    throw new IllegalStateException(
                            "At least one biometric must be enrolled to create keys requiring user"
                            + " authentication for every use");
                }

                if (spec.getBoundToSpecificSecureUserId() != GateKeeper.INVALID_SECURE_USER_ID) {
                    sids.add(spec.getBoundToSpecificSecureUserId());
                } else if (spec.isInvalidatedByBiometricEnrollment()) {
                    // The biometric-only SIDs will change on biometric enrollment or removal of all
                    // enrolled templates, invalidating the key.
                    for (long sid : biometricSids) {
                        sids.add(sid);
                    }
                } else {
                    // The root SID will *not* change on fingerprint enrollment, or removal of all
                    // enrolled fingerprints, allowing the key to remain valid.
                    sids.add(getRootSid());
                }
            } else if ((spec.getUserAuthenticationType() & KeyProperties.AUTH_DEVICE_CREDENTIAL)
                            != 0) {
                sids.add(getRootSid());
            } else {
                throw new IllegalStateException("Invalid or no authentication type specified.");
            }

            for (int i = 0; i < sids.size(); i++) {
                args.addUnsignedLong(KeymasterDefs.KM_TAG_USER_SECURE_ID,
                        KeymasterArguments.toUint64(sids.get(i)));
            }
        }
    }

    /**
     * Adds keymaster arguments to express the key's authorization policy supported by user
     * authentication.
     *
     * @param args The arguments sent to keymaster that need to be populated from the spec
     * @param spec The user authentication relevant portions of the spec passed in from the caller.
     *        This spec will be translated into the relevant keymaster tags to be loaded into args.
     * @throws IllegalStateException if user authentication is required but the system is in a wrong
     *         state (e.g., secure lock screen not set up) for generating or importing keys that
     *         require user authentication.
     */
    public static void addUserAuthArgs(KeymasterArguments args, UserAuthArgs spec) {

        if (spec.isUserConfirmationRequired()) {
            args.addBoolean(KeymasterDefs.KM_TAG_TRUSTED_CONFIRMATION_REQUIRED);
        }

        if (spec.isUserPresenceRequired()) {
            args.addBoolean(KeymasterDefs.KM_TAG_TRUSTED_USER_PRESENCE_REQUIRED);
        }

        if (spec.isUnlockedDeviceRequired()) {
            args.addBoolean(KeymasterDefs.KM_TAG_UNLOCKED_DEVICE_REQUIRED);
        }

        if (!spec.isUserAuthenticationRequired()) {
            args.addBoolean(KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED);
            return;
        }

        if (spec.getUserAuthenticationValidityDurationSeconds() == 0) {
            // Every use of this key needs to be authorized by the user.
            addSids(args, spec);
            args.addEnum(KeymasterDefs.KM_TAG_USER_AUTH_TYPE, spec.getUserAuthenticationType());

            if (spec.isUserAuthenticationValidWhileOnBody()) {
                throw new ProviderException("Key validity extension while device is on-body is not "
                        + "supported for keys requiring fingerprint authentication");
            }
        } else {
            addSids(args, spec);
            args.addEnum(KeymasterDefs.KM_TAG_USER_AUTH_TYPE, spec.getUserAuthenticationType());
            args.addUnsignedInt(KeymasterDefs.KM_TAG_AUTH_TIMEOUT,
                    spec.getUserAuthenticationValidityDurationSeconds());
            if (spec.isUserAuthenticationValidWhileOnBody()) {
                args.addBoolean(KeymasterDefs.KM_TAG_ALLOW_WHILE_ON_BODY);
            }
        }
    }

    /**
     * Adds {@code KM_TAG_MIN_MAC_LENGTH} tag, if necessary, to the keymaster arguments for
     * generating or importing a key. This tag may only be needed for symmetric keys (e.g., HMAC,
     * AES-GCM).
     */
    public static void addMinMacLengthAuthorizationIfNecessary(KeymasterArguments args,
            int keymasterAlgorithm,
            int[] keymasterBlockModes,
            int[] keymasterDigests) {
        switch (keymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_AES:
                if (com.android.internal.util.ArrayUtils.contains(
                        keymasterBlockModes, KeymasterDefs.KM_MODE_GCM)) {
                    // AES GCM key needs the minimum length of AEAD tag specified.
                    args.addUnsignedInt(KeymasterDefs.KM_TAG_MIN_MAC_LENGTH,
                            AndroidKeyStoreAuthenticatedAESCipherSpi.GCM
                                    .MIN_SUPPORTED_TAG_LENGTH_BITS);
                }
                break;
            case KeymasterDefs.KM_ALGORITHM_HMAC:
                // HMAC key needs the minimum length of MAC set to the output size of the associated
                // digest. This is because we do not offer a way to generate shorter MACs and
                // don't offer a way to verify MACs (other than by generating them).
                if (keymasterDigests.length != 1) {
                    throw new ProviderException(
                            "Unsupported number of authorized digests for HMAC key: "
                                    + keymasterDigests.length
                                    + ". Exactly one digest must be authorized");
                }
                int keymasterDigest = keymasterDigests[0];
                int digestOutputSizeBits = getDigestOutputSizeBits(keymasterDigest);
                if (digestOutputSizeBits == -1) {
                    throw new ProviderException(
                            "HMAC key authorized for unsupported digest: "
                                    + KeyProperties.Digest.fromKeymaster(keymasterDigest));
                }
                args.addUnsignedInt(KeymasterDefs.KM_TAG_MIN_MAC_LENGTH, digestOutputSizeBits);
                break;
        }
    }

    private static long getRootSid() {
        long rootSid = GateKeeper.getSecureUserId();
        if (rootSid == 0) {
            throw new IllegalStateException("Secure lock screen must be enabled"
                    + " to create keys requiring user authentication");
        }
        return rootSid;
    }
}
