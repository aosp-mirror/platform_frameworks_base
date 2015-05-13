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
import android.security.KeyStore;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterDefs;

import libcore.util.EmptyArray;

import java.security.InvalidKeyException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactorySpi;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link SecretKeyFactorySpi} backed by Android KeyStore.
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
        String keyAliasInKeystore = ((AndroidKeyStoreSecretKey) key).getAlias();
        String entryAlias;
        if (keyAliasInKeystore.startsWith(Credentials.USER_SECRET_KEY)) {
            entryAlias = keyAliasInKeystore.substring(Credentials.USER_SECRET_KEY.length());
        } else {
            throw new InvalidKeySpecException("Invalid key alias: " + keyAliasInKeystore);
        }

        KeyCharacteristics keyCharacteristics = new KeyCharacteristics();
        int errorCode =
                mKeyStore.getKeyCharacteristics(keyAliasInKeystore, null, null, keyCharacteristics);
        if (errorCode != KeyStore.NO_ERROR) {
            throw new InvalidKeySpecException("Failed to obtain information about key."
                    + " Keystore error: " + errorCode);
        }

        boolean insideSecureHardware;
        @KeyProperties.OriginEnum int origin;
        int keySize;
        @KeyProperties.PurposeEnum int purposes;
        String[] encryptionPaddings;
        @KeyProperties.DigestEnum String[] digests;
        @KeyProperties.BlockModeEnum String[] blockModes;
        int keymasterSwEnforcedUserAuthenticators;
        int keymasterHwEnforcedUserAuthenticators;
        try {
            if (keyCharacteristics.hwEnforced.containsTag(KeymasterDefs.KM_TAG_ORIGIN)) {
                insideSecureHardware = true;
                origin = KeyProperties.Origin.fromKeymaster(
                        keyCharacteristics.hwEnforced.getInt(KeymasterDefs.KM_TAG_ORIGIN, -1));
            } else if (keyCharacteristics.swEnforced.containsTag(KeymasterDefs.KM_TAG_ORIGIN)) {
                insideSecureHardware = false;
                origin = KeyProperties.Origin.fromKeymaster(
                        keyCharacteristics.swEnforced.getInt(KeymasterDefs.KM_TAG_ORIGIN, -1));
            } else {
                throw new InvalidKeySpecException("Key origin not available");
            }
            Integer keySizeInteger = keyCharacteristics.getInteger(KeymasterDefs.KM_TAG_KEY_SIZE);
            if (keySizeInteger == null) {
                throw new InvalidKeySpecException("Key size not available");
            }
            keySize = keySizeInteger;
            purposes = KeyProperties.Purpose.allFromKeymaster(
                    keyCharacteristics.getInts(KeymasterDefs.KM_TAG_PURPOSE));

            List<String> encryptionPaddingsList = new ArrayList<String>();
            for (int keymasterPadding : keyCharacteristics.getInts(KeymasterDefs.KM_TAG_PADDING)) {
                @KeyProperties.EncryptionPaddingEnum String jcaPadding;
                try {
                    jcaPadding = KeyProperties.EncryptionPadding.fromKeymaster(keymasterPadding);
                } catch (IllegalArgumentException e) {
                    throw new InvalidKeySpecException(
                            "Unsupported encryption padding: " + keymasterPadding);
                }
                encryptionPaddingsList.add(jcaPadding);
            }
            encryptionPaddings =
                    encryptionPaddingsList.toArray(new String[encryptionPaddingsList.size()]);

            digests = KeyProperties.Digest.allFromKeymaster(
                    keyCharacteristics.getInts(KeymasterDefs.KM_TAG_DIGEST));
            blockModes = KeyProperties.BlockMode.allFromKeymaster(
                    keyCharacteristics.getInts(KeymasterDefs.KM_TAG_BLOCK_MODE));
            keymasterSwEnforcedUserAuthenticators =
                    keyCharacteristics.swEnforced.getInt(KeymasterDefs.KM_TAG_USER_AUTH_TYPE, 0);
            keymasterHwEnforcedUserAuthenticators =
                    keyCharacteristics.hwEnforced.getInt(KeymasterDefs.KM_TAG_USER_AUTH_TYPE, 0);
        } catch (IllegalArgumentException e) {
            throw new InvalidKeySpecException("Unsupported key characteristic", e);
        }

        Date keyValidityStart = keyCharacteristics.getDate(KeymasterDefs.KM_TAG_ACTIVE_DATETIME);
        if ((keyValidityStart != null) && (keyValidityStart.getTime() <= 0)) {
            keyValidityStart = null;
        }
        Date keyValidityForOriginationEnd =
                keyCharacteristics.getDate(KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME);
        if ((keyValidityForOriginationEnd != null)
                && (keyValidityForOriginationEnd.getTime() == Long.MAX_VALUE)) {
            keyValidityForOriginationEnd = null;
        }
        Date keyValidityForConsumptionEnd =
                keyCharacteristics.getDate(KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME);
        if ((keyValidityForConsumptionEnd != null)
                && (keyValidityForConsumptionEnd.getTime() == Long.MAX_VALUE)) {
            keyValidityForConsumptionEnd = null;
        }
        boolean userAuthenticationRequired =
                !keyCharacteristics.getBoolean(KeymasterDefs.KM_TAG_NO_AUTH_REQUIRED);
        int userAuthenticationValidityDurationSeconds =
                keyCharacteristics.getInt(KeymasterDefs.KM_TAG_AUTH_TIMEOUT, -1);
        boolean userAuthenticationRequirementEnforcedBySecureHardware = (userAuthenticationRequired)
                && (keymasterHwEnforcedUserAuthenticators != 0)
                && (keymasterSwEnforcedUserAuthenticators == 0);

        return new KeyInfo(entryAlias,
                insideSecureHardware,
                origin,
                keySize,
                keyValidityStart,
                keyValidityForOriginationEnd,
                keyValidityForConsumptionEnd,
                purposes,
                encryptionPaddings,
                EmptyArray.STRING, // no signature paddings -- this is symmetric crypto
                digests,
                blockModes,
                userAuthenticationRequired,
                userAuthenticationValidityDurationSeconds,
                userAuthenticationRequirementEnforcedBySecureHardware);
    }

    @Override
    protected SecretKey engineGenerateSecret(KeySpec keySpec) throws InvalidKeySpecException {
        throw new UnsupportedOperationException(
                "Key import into Android KeyStore is not supported");
    }

    @Override
    protected SecretKey engineTranslateKey(SecretKey key) throws InvalidKeyException {
        throw new UnsupportedOperationException(
                "Key import into Android KeyStore is not supported");
    }
}
