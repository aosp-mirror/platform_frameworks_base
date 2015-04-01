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

import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterDefs;

import java.security.InvalidKeyException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Date;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactorySpi;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link SecretKeyFactorySpi} backed by Android KeyStore.
 *
 * @hide
 */
public class KeyStoreSecretKeyFactorySpi extends SecretKeyFactorySpi {

    private final KeyStore mKeyStore = KeyStore.getInstance();

    @Override
    protected KeySpec engineGetKeySpec(SecretKey key,
            @SuppressWarnings("rawtypes") Class keySpecClass) throws InvalidKeySpecException {
        if (keySpecClass == null) {
            throw new InvalidKeySpecException("keySpecClass == null");
        }
        if (!(key instanceof KeyStoreSecretKey)) {
            throw new InvalidKeySpecException("Only Android KeyStore secret keys supported: " +
                    ((key != null) ? key.getClass().getName() : "null"));
        }
        if (SecretKeySpec.class.isAssignableFrom(keySpecClass)) {
            throw new InvalidKeySpecException(
                    "Key material export of Android KeyStore keys is not supported");
        }
        if (!KeyStoreKeySpec.class.equals(keySpecClass)) {
            throw new InvalidKeySpecException("Unsupported key spec: " + keySpecClass.getName());
        }
        String keyAliasInKeystore = ((KeyStoreSecretKey) key).getAlias();
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

        @KeyStoreKeyCharacteristics.OriginEnum Integer origin;
        int keySize;
        @KeyStoreKeyConstraints.PurposeEnum int purposes;
        @KeyStoreKeyConstraints.AlgorithmEnum int algorithm;
        @KeyStoreKeyConstraints.PaddingEnum Integer padding;
        @KeyStoreKeyConstraints.DigestEnum Integer digest;
        @KeyStoreKeyConstraints.BlockModeEnum Integer blockMode;
        try {
            origin = KeymasterUtils.getInt(keyCharacteristics, KeymasterDefs.KM_TAG_ORIGIN);
            if (origin == null) {
                throw new InvalidKeySpecException("Key origin not available");
            }
            origin = KeyStoreKeyCharacteristics.Origin.fromKeymaster(origin);
            Integer keySizeInteger =
                    KeymasterUtils.getInt(keyCharacteristics, KeymasterDefs.KM_TAG_KEY_SIZE);
            if (keySizeInteger == null) {
                throw new InvalidKeySpecException("Key size not available");
            }
            keySize = keySizeInteger;
            purposes = KeyStoreKeyConstraints.Purpose.allFromKeymaster(
                    KeymasterUtils.getInts(keyCharacteristics, KeymasterDefs.KM_TAG_PURPOSE));
            Integer alg = KeymasterUtils.getInt(keyCharacteristics, KeymasterDefs.KM_TAG_ALGORITHM);
            if (alg == null) {
                throw new InvalidKeySpecException("Key algorithm not available");
            }
            algorithm = KeyStoreKeyConstraints.Algorithm.fromKeymaster(alg);
            padding = KeymasterUtils.getInt(keyCharacteristics, KeymasterDefs.KM_TAG_PADDING);
            if (padding != null) {
                padding = KeyStoreKeyConstraints.Padding.fromKeymaster(padding);
            }
            digest = KeymasterUtils.getInt(keyCharacteristics, KeymasterDefs.KM_TAG_DIGEST);
            if (digest != null) {
                digest = KeyStoreKeyConstraints.Digest.fromKeymaster(digest);
            }
            blockMode = KeymasterUtils.getInt(keyCharacteristics, KeymasterDefs.KM_TAG_BLOCK_MODE);
            if (blockMode != null) {
                blockMode = KeyStoreKeyConstraints.BlockMode.fromKeymaster(blockMode);
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidKeySpecException("Unsupported key characteristic", e);
        }

        Date keyValidityStart =
                KeymasterUtils.getDate(keyCharacteristics, KeymasterDefs.KM_TAG_ACTIVE_DATETIME);
        if ((keyValidityStart != null) && (keyValidityStart.getTime() <= 0)) {
            keyValidityStart = null;
        }
        Date keyValidityForOriginationEnd = KeymasterUtils.getDate(keyCharacteristics,
                KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME);
        if ((keyValidityForOriginationEnd != null)
                && (keyValidityForOriginationEnd.getTime() == Long.MAX_VALUE)) {
            keyValidityForOriginationEnd = null;
        }
        Date keyValidityForConsumptionEnd = KeymasterUtils.getDate(keyCharacteristics,
                KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME);
        if ((keyValidityForConsumptionEnd != null)
                && (keyValidityForConsumptionEnd.getTime() == Long.MAX_VALUE)) {
            keyValidityForConsumptionEnd = null;
        }

        int swEnforcedUserAuthenticatorIds =
                keyCharacteristics.swEnforced.getInt(KeymasterDefs.KM_TAG_USER_AUTH_TYPE, 0);
        int hwEnforcedUserAuthenticatorIds =
                keyCharacteristics.hwEnforced.getInt(KeymasterDefs.KM_TAG_USER_AUTH_TYPE, 0);
        int userAuthenticatorIds = swEnforcedUserAuthenticatorIds | hwEnforcedUserAuthenticatorIds;
        Set<Integer> userAuthenticators =
                KeyStoreKeyConstraints.UserAuthenticator.allFromKeymaster(userAuthenticatorIds);
        Set<Integer> teeBackedUserAuthenticators =
                KeyStoreKeyConstraints.UserAuthenticator.allFromKeymaster(
                        hwEnforcedUserAuthenticatorIds);

        return new KeyStoreKeySpec(entryAlias,
                origin,
                keySize,
                keyValidityStart,
                keyValidityForOriginationEnd,
                keyValidityForConsumptionEnd,
                purposes,
                algorithm,
                padding,
                digest,
                blockMode,
                KeymasterUtils.getInt(keyCharacteristics,
                        KeymasterDefs.KM_TAG_MIN_SECONDS_BETWEEN_OPS),
                KeymasterUtils.getInt(keyCharacteristics, KeymasterDefs.KM_TAG_MAX_USES_PER_BOOT),
                userAuthenticators,
                teeBackedUserAuthenticators,
                KeymasterUtils.getInt(keyCharacteristics, KeymasterDefs.KM_TAG_AUTH_TIMEOUT));
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
