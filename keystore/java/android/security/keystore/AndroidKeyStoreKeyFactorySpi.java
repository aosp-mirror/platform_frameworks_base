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

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactorySpi;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * {@link KeyFactorySpi} backed by Android KeyStore.
 *
 * @hide
 */
public class AndroidKeyStoreKeyFactorySpi extends KeyFactorySpi {

    private final KeyStore mKeyStore = KeyStore.getInstance();

    @Override
    protected <T extends KeySpec> T engineGetKeySpec(Key key, Class<T> keySpecClass)
            throws InvalidKeySpecException {
        if (keySpecClass == null) {
            throw new InvalidKeySpecException("keySpecClass == null");
        }
        if (!(key instanceof AndroidKeyStorePrivateKey)) {
            throw new InvalidKeySpecException("Only Android KeyStore private keys supported: " +
                    ((key != null) ? key.getClass().getName() : "null"));
        }
        if (PKCS8EncodedKeySpec.class.isAssignableFrom(keySpecClass)) {
            throw new InvalidKeySpecException(
                    "Key material export of Android KeyStore keys is not supported");
        }
        if (!KeyInfo.class.equals(keySpecClass)) {
            throw new InvalidKeySpecException("Unsupported key spec: " + keySpecClass.getName());
        }
        String keyAliasInKeystore = ((AndroidKeyStoreKey) key).getAlias();
        String entryAlias;
        if (keyAliasInKeystore.startsWith(Credentials.USER_PRIVATE_KEY)) {
            entryAlias = keyAliasInKeystore.substring(Credentials.USER_PRIVATE_KEY.length());
        } else {
            throw new InvalidKeySpecException("Invalid key alias: " + keyAliasInKeystore);
        }

        @SuppressWarnings("unchecked")
        T result = (T) AndroidKeyStoreSecretKeyFactorySpi.getKeyInfo(
                mKeyStore, entryAlias, keyAliasInKeystore);
        return result;
    }

    @Override
    protected PrivateKey engineGeneratePrivate(KeySpec spec) throws InvalidKeySpecException {
        throw new UnsupportedOperationException(
                "To generate a key pair in Android KeyStore, use KeyPairGenerator initialized with"
                + " " + KeyGenParameterSpec.class.getName());
    }

    @Override
    protected PublicKey engineGeneratePublic(KeySpec spec) throws InvalidKeySpecException {
        throw new UnsupportedOperationException(
                "To generate a key pair in Android KeyStore, use KeyPairGenerator initialized with"
                + " " + KeyGenParameterSpec.class.getName());
    }

    @Override
    protected Key engineTranslateKey(Key arg0) throws InvalidKeyException {
        throw new UnsupportedOperationException(
                "To import a key into Android KeyStore, use KeyStore.setEntry with "
                + KeyProtection.class.getName());
    }
}
