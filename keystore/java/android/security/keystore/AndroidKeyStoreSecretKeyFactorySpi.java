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

import android.security.Credentials;
import android.security.GateKeeper;
import android.security.KeyStore;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterDefs;

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

    private final KeyStore mKeyStore = KeyStore.getInstance();

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
        String keyAliasInKeystore = keystoreKey.getAlias();
        String entryAlias;
        if (keyAliasInKeystore.startsWith(Credentials.USER_PRIVATE_KEY)) {
            entryAlias = keyAliasInKeystore.substring(Credentials.USER_PRIVATE_KEY.length());
        } else if (keyAliasInKeystore.startsWith(Credentials.USER_SECRET_KEY)){
            // key has legacy prefix
            entryAlias = keyAliasInKeystore.substring(Credentials.USER_SECRET_KEY.length());
        } else {
            throw new InvalidKeySpecException("Invalid key alias: " + keyAliasInKeystore);
        }

        return getKeyInfo(mKeyStore, entryAlias, keyAliasInKeystore, keystoreKey.getUid());
    }

    static KeyInfo getKeyInfo(KeyStore keyStore, String entryAlias, String keyAliasInKeystore,
            int keyUid) {
        KeyCharacteristics keyCharacteristics = new KeyCharacteristics();
        int errorCode = keyStore.getKeyCharacteristics(
                keyAliasInKeystore, null, null, keyUid, keyCharacteristics);
        if (errorCode != KeyStore.NO_ERROR) {
            throw new ProviderException("Failed to obtain information about key."
                    + " Keystore error: " + errorCode);
        }

        boolean insideSecureHardware;
        @KeyProperties.OriginEnum int origin;
        int keySize;
        @KeyProperties.PurposeEnum int purposes;
        String[] encryptionPaddings;
        String[] signaturePaddings;
        @KeyProperties.DigestEnum String[] digests;
        @KeyProperties.BlockModeEnum String[] blockModes;
        int keymasterSwEnforcedUserAuthenticators;
        int keymasterHwEnforcedUserAuthenticators;
        List<BigInteger> keymasterSecureUserIds;
        try {
            if (keyCharacteristics.hwEnforced.containsTag(KeymasterDefs.KM_TAG_ORIGIN)) {
                insideSecureHardware = true;
                origin = KeyProperties.Origin.fromKeymaster(
                        keyCharacteristics.hwEnforced.getEnum(KeymasterDefs.KM_TAG_ORIGIN, -1));
            } else if (keyCharacteristics.swEnforced.containsTag(KeymasterDefs.KM_TAG_ORIGIN)) {
                insideSecureHardware = false;
                origin = KeyProperties.Origin.fromKeymaster(
                        keyCharacteristics.swEnforced.getEnum(KeymasterDefs.KM_TAG_ORIGIN, -1));
            } else {
                throw new ProviderException("Key origin not available");
            }
            long keySizeUnsigned =
                    keyCharacteristics.getUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, -1);
            if (keySizeUnsigned == -1) {
                throw new ProviderException("Key size not available");
            } else if (keySizeUnsigned > Integer.MAX_VALUE) {
                throw new ProviderException("Key too large: " + keySizeUnsigned + " bits");
            }
            keySize = (int) keySizeUnsigned;
            purposes = KeyProperties.Purpose.allFromKeymaster(
                    keyCharacteristics.getEnums(KeymasterDefs.KM_TAG_PURPOSE));

            List<String> encryptionPaddingsList = new ArrayList<String>();
            List<String> signaturePaddingsList = new ArrayList<String>();
            // Keymaster stores both types of paddings in the same array -- we split it into two.
            for (int keymasterPadding : keyCharacteristics.getEnums(KeymasterDefs.KM_TAG_PADDING)) {
                try {
                    @KeyProperties.EncryptionPaddingEnum String jcaPadding =
                            KeyProperties.EncryptionPadding.fromKeymaster(keymasterPadding);
                    encryptionPaddingsList.add(jcaPadding);
                } catch (IllegalArgumentException e) {
                    try {
                        @KeyProperties.SignaturePaddingEnum String padding =
                                KeyProperties.SignaturePadding.fromKeymaster(keymasterPadding);
                        signaturePaddingsList.add(padding);
                    } catch (IllegalArgumentException e2) {
                        throw new ProviderException(
                                "Unsupported encryption padding: " + keymasterPadding);
                    }
                }

            }
            encryptionPaddings =
                    encryptionPaddingsList.toArray(new String[encryptionPaddingsList.size()]);
            signaturePaddings =
                    signaturePaddingsList.toArray(new String[signaturePaddingsList.size()]);

            digests = KeyProperties.Digest.allFromKeymaster(
                    keyCharacteristics.getEnums(KeymasterDefs.KM_TAG_DIGEST));
            blockModes = KeyProperties.BlockMode.allFromKeymaster(
                    keyCharacteristics.getEnums(KeymasterDefs.KM_TAG_BLOCK_MODE));
            keymasterSwEnforcedUserAuthenticators =
                    keyCharacteristics.swEnforced.getEnum(KeymasterDefs.KM_TAG_USER_AUTH_TYPE, 0);
            keymasterHwEnforcedUserAuthenticators =
                    keyCharacteristics.hwEnforced.getEnum(KeymasterDefs.KM_TAG_USER_AUTH_TYPE, 0);
            keymasterSecureUserIds =
                keyCharacteristics.getUnsignedLongs(KeymasterDefs.KM_TAG_USER_SECURE_ID);
        } catch (IllegalArgumentException e) {
            throw new ProviderException("Unsupported key characteristic", e);
        }

        Date keyValidityStart = keyCharacteristics.getDate(KeymasterDefs.KM_TAG_ACTIVE_DATETIME);
        Date keyValidityForOriginationEnd =
                keyCharacteristics.getDate(KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME);
        Date keyValidityForConsumptionEnd =
                keyCharacteristics.getDate(KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME);
        boolean userAuthenticationRequired =
                !keyCharacteristics.getBoolean(KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED);
        long userAuthenticationValidityDurationSeconds =
                keyCharacteristics.getUnsignedInt(KeymasterDefs.KM_TAG_AUTH_TIMEOUT, -1);
        if (userAuthenticationValidityDurationSeconds > Integer.MAX_VALUE) {
            throw new ProviderException("User authentication timeout validity too long: "
                    + userAuthenticationValidityDurationSeconds + " seconds");
        }
        boolean userAuthenticationRequirementEnforcedBySecureHardware = (userAuthenticationRequired)
                && (keymasterHwEnforcedUserAuthenticators != 0)
                && (keymasterSwEnforcedUserAuthenticators == 0);
        boolean userAuthenticationValidWhileOnBody =
                keyCharacteristics.hwEnforced.getBoolean(KeymasterDefs.KM_TAG_ALLOW_WHILE_ON_BODY);
        boolean trustedUserPresenceRequred =
                keyCharacteristics.hwEnforced.getBoolean(
                    KeymasterDefs.KM_TAG_TRUSTED_USER_PRESENCE_REQUIRED);

        boolean invalidatedByBiometricEnrollment = false;
        if (keymasterSwEnforcedUserAuthenticators == KeymasterDefs.HW_AUTH_BIOMETRIC
            || keymasterHwEnforcedUserAuthenticators == KeymasterDefs.HW_AUTH_BIOMETRIC) {
            // Fingerprint-only key; will be invalidated if the root SID isn't in the SID list.
            invalidatedByBiometricEnrollment = keymasterSecureUserIds != null
                    && !keymasterSecureUserIds.isEmpty()
                    && !keymasterSecureUserIds.contains(getGateKeeperSecureUserId());
        }

        boolean userConfirmationRequired = keyCharacteristics.hwEnforced.getBoolean(KeymasterDefs.KM_TAG_TRUSTED_CONFIRMATION_REQUIRED);

        return new KeyInfo(entryAlias,
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
                userAuthenticationRequirementEnforcedBySecureHardware,
                userAuthenticationValidWhileOnBody,
                trustedUserPresenceRequred,
                invalidatedByBiometricEnrollment,
                userConfirmationRequired);
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
