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

package android.security.keystore2;

import android.annotation.NonNull;
import android.security.GateKeeper;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.system.keystore2.Authorization;

import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.ProviderException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactorySpi;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link SecretKeyFactorySpi} backed by Android Keystore.
 *
 * @hide
 */
public class AndroidKeyStoreSecretKeyFactorySpi extends SecretKeyFactorySpi {

    @Override
    protected KeySpec engineGetKeySpec(SecretKey key,
            @SuppressWarnings("rawtypes") Class keySpecClass) throws InvalidKeySpecException {
        if (keySpecClass == null) {
            throw new InvalidKeySpecException("keySpecClass == null");
        }
        if (!(key instanceof AndroidKeyStoreSecretKey)) {
            throw new InvalidKeySpecException("Only Android KeyStore secret keys supported: " +
                    ((key != null) ? key.getClass().getName() : "null"));
        }
        if (SecretKeySpec.class.isAssignableFrom(keySpecClass)) {
            throw new InvalidKeySpecException(
                    "Key material export of Android KeyStore keys is not supported");
        }
        if (!KeyInfo.class.equals(keySpecClass)) {
            throw new InvalidKeySpecException("Unsupported key spec: " + keySpecClass.getName());
        }
        AndroidKeyStoreKey keystoreKey = (AndroidKeyStoreKey) key;

        return getKeyInfo(keystoreKey);
    }

    static @NonNull KeyInfo getKeyInfo(@NonNull AndroidKeyStoreKey key) {

        @KeyProperties.SecurityLevelEnum int securityLevel =
                KeyProperties.SECURITY_LEVEL_SOFTWARE;
        boolean insideSecureHardware = false;
        @KeyProperties.OriginEnum int origin = -1;
        int keySize = -1;
        @KeyProperties.PurposeEnum int purposes = 0;
        String[] encryptionPaddings;
        String[] signaturePaddings;
        List<String> digestsList = new ArrayList<>();
        List<String> blockModesList = new ArrayList<>();
        int keymasterSwEnforcedUserAuthenticators = 0;
        int keymasterHwEnforcedUserAuthenticators = 0;
        List<BigInteger> keymasterSecureUserIds = new ArrayList<BigInteger>();
        List<String> encryptionPaddingsList = new ArrayList<String>();
        List<String> signaturePaddingsList = new ArrayList<String>();
        Date keyValidityStart = null;
        Date keyValidityForOriginationEnd = null;
        Date keyValidityForConsumptionEnd = null;
        long userAuthenticationValidityDurationSeconds = 0;
        boolean userAuthenticationRequired = true;
        boolean userAuthenticationValidWhileOnBody = false;
        boolean unlockedDeviceRequired = false;
        boolean trustedUserPresenceRequired = false;
        boolean trustedUserConfirmationRequired = false;
        int remainingUsageCount = KeyProperties.UNRESTRICTED_USAGE_COUNT;
        try {
            for (Authorization a : key.getAuthorizations()) {
                switch (a.keyParameter.tag) {
                    case KeymasterDefs.KM_TAG_ORIGIN:
                        insideSecureHardware =
                                KeyStore2ParameterUtils.isSecureHardware(a.securityLevel);
                        securityLevel = a.securityLevel;
                        origin = KeyProperties.Origin.fromKeymaster(
                                a.keyParameter.value.getOrigin());
                        break;
                    case KeymasterDefs.KM_TAG_KEY_SIZE:
                        long keySizeUnsigned = KeyStore2ParameterUtils.getUnsignedInt(a);
                        if (keySizeUnsigned > Integer.MAX_VALUE) {
                            throw new ProviderException(
                                    "Key too large: " + keySizeUnsigned + " bits");
                        }
                        keySize = (int) keySizeUnsigned;
                        break;
                    case KeymasterDefs.KM_TAG_PURPOSE:
                        purposes |= KeyProperties.Purpose.fromKeymaster(
                                a.keyParameter.value.getKeyPurpose());
                        break;
                    case KeymasterDefs.KM_TAG_PADDING:
                        int paddingMode = a.keyParameter.value.getPaddingMode();
                        try {
                            if (paddingMode == KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_SIGN
                                    || paddingMode == KeymasterDefs.KM_PAD_RSA_PSS) {
                                @KeyProperties.SignaturePaddingEnum String padding =
                                        KeyProperties.SignaturePadding.fromKeymaster(
                                                paddingMode);
                                signaturePaddingsList.add(padding);
                            } else {
                                @KeyProperties.EncryptionPaddingEnum String jcaPadding =
                                        KeyProperties.EncryptionPadding.fromKeymaster(
                                                paddingMode);
                                encryptionPaddingsList.add(jcaPadding);
                            }
                        } catch (IllegalArgumentException e) {
                            throw new ProviderException("Unsupported padding: "
                                                + paddingMode);
                        }
                        break;
                    case KeymasterDefs.KM_TAG_DIGEST:
                        digestsList.add(KeyProperties.Digest.fromKeymaster(
                                a.keyParameter.value.getDigest()));
                        break;
                    case KeymasterDefs.KM_TAG_BLOCK_MODE:
                        blockModesList.add(
                                KeyProperties.BlockMode.fromKeymaster(
                                        a.keyParameter.value.getBlockMode())
                        );
                        break;
                    case KeymasterDefs.KM_TAG_USER_AUTH_TYPE:
                        int authenticatorType = a.keyParameter.value.getHardwareAuthenticatorType();
                        if (KeyStore2ParameterUtils.isSecureHardware(a.securityLevel)) {
                            keymasterHwEnforcedUserAuthenticators = authenticatorType;
                        } else {
                            keymasterSwEnforcedUserAuthenticators = authenticatorType;
                        }
                        break;
                    case KeymasterDefs.KM_TAG_USER_SECURE_ID:
                        keymasterSecureUserIds.add(
                                KeymasterArguments.toUint64(
                                        a.keyParameter.value.getLongInteger()));
                        break;
                    case KeymasterDefs.KM_TAG_ACTIVE_DATETIME:
                        keyValidityStart = KeyStore2ParameterUtils.getDate(a);
                        break;
                    case KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME:
                        keyValidityForOriginationEnd =
                                KeyStore2ParameterUtils.getDate(a);
                        break;
                    case KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME:
                        keyValidityForConsumptionEnd =
                                KeyStore2ParameterUtils.getDate(a);
                        break;
                    case KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED:
                        userAuthenticationRequired = false;
                        break;
                    case KeymasterDefs.KM_TAG_AUTH_TIMEOUT:
                        userAuthenticationValidityDurationSeconds =
                                KeyStore2ParameterUtils.getUnsignedInt(a);
                        if (userAuthenticationValidityDurationSeconds > Integer.MAX_VALUE) {
                            throw new ProviderException(
                                    "User authentication timeout validity too long: "
                                    + userAuthenticationValidityDurationSeconds + " seconds");
                        }
                        break;
                    case KeymasterDefs.KM_TAG_UNLOCKED_DEVICE_REQUIRED:
                        unlockedDeviceRequired = true;
                        break;
                    case KeymasterDefs.KM_TAG_ALLOW_WHILE_ON_BODY:
                        userAuthenticationValidWhileOnBody =
                                KeyStore2ParameterUtils.isSecureHardware(a.securityLevel);
                        break;
                    case KeymasterDefs.KM_TAG_TRUSTED_USER_PRESENCE_REQUIRED:
                        trustedUserPresenceRequired =
                                KeyStore2ParameterUtils.isSecureHardware(a.securityLevel);
                        break;
                    case KeymasterDefs.KM_TAG_TRUSTED_CONFIRMATION_REQUIRED:
                        trustedUserConfirmationRequired =
                                KeyStore2ParameterUtils.isSecureHardware(a.securityLevel);
                        break;
                    case KeymasterDefs.KM_TAG_USAGE_COUNT_LIMIT:
                        long remainingUsageCountUnsigned =
                                KeyStore2ParameterUtils.getUnsignedInt(a);
                        if (remainingUsageCountUnsigned > Integer.MAX_VALUE) {
                            throw new ProviderException(
                                    "Usage count of limited use key too long: "
                                     + remainingUsageCountUnsigned);
                        }
                        remainingUsageCount = (int) remainingUsageCountUnsigned;
                        break;
                }
            }
        } catch (IllegalArgumentException e) {
            throw new ProviderException("Unsupported key characteristic", e);
        }
        if (keySize == -1) {
            throw new ProviderException("Key size not available");
        }
        if (origin == -1) {
            throw new ProviderException("Key origin not available");
        }

        encryptionPaddings =
                encryptionPaddingsList.toArray(new String[0]);
        signaturePaddings =
                signaturePaddingsList.toArray(new String[0]);

        boolean userAuthenticationRequirementEnforcedBySecureHardware = (userAuthenticationRequired)
                && (keymasterHwEnforcedUserAuthenticators != 0)
                && (keymasterSwEnforcedUserAuthenticators == 0);

        String[] digests = digestsList.toArray(new String[0]);
        String[] blockModes = blockModesList.toArray(new String[0]);

        boolean invalidatedByBiometricEnrollment = false;
        if (keymasterSwEnforcedUserAuthenticators == KeymasterDefs.HW_AUTH_BIOMETRIC
            || keymasterHwEnforcedUserAuthenticators == KeymasterDefs.HW_AUTH_BIOMETRIC) {
            // Fingerprint-only key; will be invalidated if the root SID isn't in the SID list.
            invalidatedByBiometricEnrollment = !keymasterSecureUserIds.isEmpty()
                    && !keymasterSecureUserIds.contains(getGateKeeperSecureUserId());
        }

        return new KeyInfo(key.getUserKeyDescriptor().alias,
                insideSecureHardware,
                origin,
                keySize,
                keyValidityStart,
                keyValidityForOriginationEnd,
                keyValidityForConsumptionEnd,
                purposes,
                encryptionPaddings,
                signaturePaddings,
                digests,
                blockModes,
                userAuthenticationRequired,
                (int) userAuthenticationValidityDurationSeconds,
                userAuthenticationRequirementEnforcedBySecureHardware
                        ? keymasterHwEnforcedUserAuthenticators
                        : keymasterSwEnforcedUserAuthenticators,
                userAuthenticationRequirementEnforcedBySecureHardware,
                userAuthenticationValidWhileOnBody,
                unlockedDeviceRequired,
                trustedUserPresenceRequired,
                invalidatedByBiometricEnrollment,
                trustedUserConfirmationRequired,
                securityLevel,
                remainingUsageCount);
    }

    private static BigInteger getGateKeeperSecureUserId() throws ProviderException {
    	try {
    		return BigInteger.valueOf(GateKeeper.getSecureUserId());
    	} catch (IllegalStateException e) {
    		throw new ProviderException("Failed to get GateKeeper secure user ID", e);
    	}
    }

    @Override
    protected SecretKey engineGenerateSecret(KeySpec keySpec) throws InvalidKeySpecException {
        throw new InvalidKeySpecException(
                "To generate secret key in Android Keystore, use KeyGenerator initialized with "
                        + KeyGenParameterSpec.class.getName());
    }

    @Override
    protected SecretKey engineTranslateKey(SecretKey key) throws InvalidKeyException {
        if (key == null) {
            throw new InvalidKeyException("key == null");
        } else if (!(key instanceof AndroidKeyStoreSecretKey)) {
            throw new InvalidKeyException(
                    "To import a secret key into Android Keystore, use KeyStore.setEntry");
        }

        return key;
    }
}
