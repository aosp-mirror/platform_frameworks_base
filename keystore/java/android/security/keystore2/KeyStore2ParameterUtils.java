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

package android.security.keystore2;

import android.annotation.NonNull;
import android.hardware.biometrics.BiometricManager;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.KeyParameterValue;
import android.hardware.security.keymint.SecurityLevel;
import android.hardware.security.keymint.Tag;
import android.security.GateKeeper;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserAuthArgs;
import android.system.keystore2.Authorization;

import java.math.BigInteger;
import java.security.ProviderException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * @hide
 */
public abstract class KeyStore2ParameterUtils {

    /**
     * This function constructs a {@link KeyParameter} expressing a boolean value.
     * @param tag Must be KeyMint tag with the associated type BOOL.
     * @return An instance of {@link KeyParameter}.
     * @hide
     */
    static @NonNull KeyParameter makeBool(int tag) {
        int type = KeymasterDefs.getTagType(tag);
        if (type != KeymasterDefs.KM_BOOL) {
            throw new IllegalArgumentException("Not a boolean tag: " + tag);
        }
        KeyParameter p = new KeyParameter();
        p.tag = tag;
        p.value = KeyParameterValue.boolValue(true);
        return p;
    }

    /**
     * This function constructs a {@link KeyParameter} expressing an enum value.
     * @param tag Must be KeyMint tag with the associated type ENUM or ENUM_REP.
     * @param v A 32bit integer.
     * @return An instance of {@link KeyParameter}.
     * @hide
     */
    static @NonNull KeyParameter makeEnum(int tag, int v) {
        KeyParameter kp = new KeyParameter();
        kp.tag = tag;
        switch (tag) {
            case Tag.PURPOSE:
                kp.value = KeyParameterValue.keyPurpose(v);
                break;
            case Tag.ALGORITHM:
                kp.value = KeyParameterValue.algorithm(v);
                break;
            case Tag.BLOCK_MODE:
                kp.value = KeyParameterValue.blockMode(v);
                break;
            case Tag.DIGEST:
                kp.value = KeyParameterValue.digest(v);
                break;
            case Tag.EC_CURVE:
                kp.value = KeyParameterValue.ecCurve(v);
                break;
            case Tag.ORIGIN:
                kp.value = KeyParameterValue.origin(v);
                break;
            case Tag.PADDING:
                kp.value = KeyParameterValue.paddingMode(v);
                break;
            case Tag.USER_AUTH_TYPE:
                kp.value = KeyParameterValue.hardwareAuthenticatorType(v);
                break;
            case Tag.HARDWARE_TYPE:
                kp.value = KeyParameterValue.securityLevel(v);
                break;
            default:
                throw new IllegalArgumentException("Not an enum or repeatable enum tag: " + tag);
        }
        return kp;
    }

    /**
     * This function constructs a {@link KeyParameter} expressing an integer value.
     * @param tag Must be KeyMint tag with the associated type UINT or UINT_REP.
     * @param v A 32bit integer.
     * @return An instance of {@link KeyParameter}.
     * @hide
     */
    static @NonNull KeyParameter makeInt(int tag, int v) {
        int type = KeymasterDefs.getTagType(tag);
        if (type != KeymasterDefs.KM_UINT && type != KeymasterDefs.KM_UINT_REP) {
            throw new IllegalArgumentException("Not an int or repeatable int tag: " + tag);
        }
        KeyParameter p = new KeyParameter();
        p.tag = tag;
        p.value = KeyParameterValue.integer(v);
        return p;
    }

    /**
     * This function constructs a {@link KeyParameter} expressing a long integer value.
     * @param tag Must be KeyMint tag with the associated type ULONG or ULONG_REP.
     * @param v A 64bit integer.
     * @return An instance of {@link KeyParameter}.
     * @hide
     */
    static @NonNull KeyParameter makeLong(int tag, long v) {
        int type = KeymasterDefs.getTagType(tag);
        if (type != KeymasterDefs.KM_ULONG && type != KeymasterDefs.KM_ULONG_REP) {
            throw new IllegalArgumentException("Not a long or repeatable long tag: " + tag);
        }
        KeyParameter p = new KeyParameter();
        p.tag = tag;
        p.value = KeyParameterValue.longInteger(v);
        return p;
    }

    /**
     * This function constructs a {@link KeyParameter} expressing a blob.
     * @param tag Must be KeyMint tag with the associated type BYTES.
     * @param b A byte array to be stored in the new key parameter.
     * @return An instance of {@link KeyParameter}.
     * @hide
     */
    static @NonNull KeyParameter makeBytes(int tag, @NonNull byte[] b) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_BYTES) {
            throw new IllegalArgumentException("Not a bytes tag: " + tag);
        }
        KeyParameter p = new KeyParameter();
        p.tag = tag;
        p.value = KeyParameterValue.blob(b);
        return p;
    }

    /**
     * This function constructs a {@link KeyParameter} expressing a Bignum.
     * @param tag Must be KeyMint tag with the associated type BIGNUM.
     * @param b A BitInteger to be stored in the new key parameter.
     * @return An instance of {@link KeyParameter}.
     * @hide
     */
    static @NonNull KeyParameter makeBignum(int tag, @NonNull BigInteger b) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_BIGNUM) {
            throw new IllegalArgumentException("Not a bignum tag: " + tag);
        }
        KeyParameter p = new KeyParameter();
        p.tag = tag;
        p.value = KeyParameterValue.blob(b.toByteArray());
        return p;
    }

    /**
     * This function constructs a {@link KeyParameter} expressing date.
     * @param tag Must be KeyMint tag with the associated type DATE.
     * @param date A date
     * @return An instance of {@link KeyParameter}.
     * @hide
     */
    static @NonNull KeyParameter makeDate(int tag, @NonNull Date date) {
        if (KeymasterDefs.getTagType(tag) != KeymasterDefs.KM_DATE) {
            throw new IllegalArgumentException("Not a date tag: " + tag);
        }
        KeyParameter p = new KeyParameter();
        p.tag = tag;
        p.value = KeyParameterValue.dateTime(date.getTime());
        return p;
    }
    /**
     * Returns true if the given security level is TEE or Strongbox.
     *
     * @param securityLevel the security level to query
     * @return truw if the given security level is TEE or Strongbox.
     */
    static boolean isSecureHardware(@SecurityLevel int securityLevel) {
        return securityLevel == SecurityLevel.TRUSTED_ENVIRONMENT
                || securityLevel == SecurityLevel.STRONGBOX;
    }

    static long getUnsignedInt(@NonNull Authorization param) {
        if (KeymasterDefs.getTagType(param.keyParameter.tag) != KeymasterDefs.KM_UINT) {
            throw new IllegalArgumentException("Not an int tag: " + param.keyParameter.tag);
        }
        // KM_UINT is 32 bits wide so we must suppress sign extension.
        return ((long) param.keyParameter.value.getInteger()) & 0xffffffffL;
    }

    static @NonNull Date getDate(@NonNull Authorization param) {
        if (KeymasterDefs.getTagType(param.keyParameter.tag) != KeymasterDefs.KM_DATE) {
            throw new IllegalArgumentException("Not a date tag: " + param.keyParameter.tag);
        }
        if (param.keyParameter.value.getDateTime() < 0) {
            throw new IllegalArgumentException("Date Value too large: "
                    + param.keyParameter.value.getDateTime());
        }
        return new Date(param.keyParameter.value.getDateTime());
    }

    static void forEachSetFlag(int flags, Consumer<Integer> consumer) {
        int offset = 0;
        while (flags != 0) {
            if ((flags & 1) == 1) {
                consumer.accept(1 << offset);
            }
            offset += 1;
            flags >>>= 1;
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

    private static void addSids(@NonNull List<KeyParameter> params, @NonNull UserAuthArgs spec) {
        // If both biometric and credential are accepted, then just use the root sid from gatekeeper
        if (spec.getUserAuthenticationType() == (KeyProperties.AUTH_BIOMETRIC_STRONG
                | KeyProperties.AUTH_DEVICE_CREDENTIAL)) {
            if (spec.getBoundToSpecificSecureUserId() != GateKeeper.INVALID_SECURE_USER_ID) {
                params.add(makeLong(
                        KeymasterDefs.KM_TAG_USER_SECURE_ID,
                        spec.getBoundToSpecificSecureUserId()
                ));
            } else {
                // The key is authorized for use for the specified amount of time after the user has
                // authenticated. Whatever unlocks the secure lock screen should authorize this key.
                params.add(makeLong(KeymasterDefs.KM_TAG_USER_SECURE_ID, getRootSid()));
            }
        } else {
            List<Long> sids = new ArrayList<>();
            if ((spec.getUserAuthenticationType() & KeyProperties.AUTH_BIOMETRIC_STRONG) != 0) {
                final BiometricManager bm = android.app.AppGlobals.getInitialApplication()
                        .getSystemService(BiometricManager.class);

                // TODO: Restore permission check in getAuthenticatorIds once the ID is no longer
                //       needed here.

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
                params.add(makeLong(KeymasterDefs.KM_TAG_USER_SECURE_ID, sids.get(i)));
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
    static void addUserAuthArgs(@NonNull List<KeyParameter> args,
            @NonNull UserAuthArgs spec) {

        if (spec.isUserConfirmationRequired()) {
            args.add(KeyStore2ParameterUtils.makeBool(
                    KeymasterDefs.KM_TAG_TRUSTED_CONFIRMATION_REQUIRED));
        }
        if (spec.isUserPresenceRequired()) {
            args.add(KeyStore2ParameterUtils.makeBool(
                    KeymasterDefs.KM_TAG_TRUSTED_USER_PRESENCE_REQUIRED));
        }
        if (spec.isUnlockedDeviceRequired()) {
            args.add(KeyStore2ParameterUtils.makeBool(
                    KeymasterDefs.KM_TAG_UNLOCKED_DEVICE_REQUIRED));
        }
        if (!spec.isUserAuthenticationRequired()) {
            args.add(KeyStore2ParameterUtils.makeBool(
                    KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED));
        } else {
            if (spec.getUserAuthenticationValidityDurationSeconds() == 0) {
                // Every use of this key needs to be authorized by the user.
                addSids(args, spec);
                args.add(KeyStore2ParameterUtils.makeEnum(
                        KeymasterDefs.KM_TAG_USER_AUTH_TYPE, spec.getUserAuthenticationType()
                ));

                if (spec.isUserAuthenticationValidWhileOnBody()) {
                    throw new ProviderException(
                            "Key validity extension while device is on-body is not "
                                    + "supported for keys requiring fingerprint authentication");
                }
            } else {
                addSids(args, spec);
                args.add(KeyStore2ParameterUtils.makeEnum(
                        KeymasterDefs.KM_TAG_USER_AUTH_TYPE, spec.getUserAuthenticationType()
                ));
                args.add(KeyStore2ParameterUtils.makeInt(
                        KeymasterDefs.KM_TAG_AUTH_TIMEOUT,
                        spec.getUserAuthenticationValidityDurationSeconds()
                ));
                if (spec.isUserAuthenticationValidWhileOnBody()) {
                    args.add(KeyStore2ParameterUtils.makeBool(
                            KeymasterDefs.KM_TAG_ALLOW_WHILE_ON_BODY
                    ));
                }
            }
        }
    }
}
