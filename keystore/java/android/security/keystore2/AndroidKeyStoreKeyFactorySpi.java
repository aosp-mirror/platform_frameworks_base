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

import android.security.KeyStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactorySpi;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;

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
        if (key == null) {
            throw new InvalidKeySpecException("key == null");
        } else if ((!(key instanceof AndroidKeyStorePrivateKey))
            && (!(key instanceof AndroidKeyStorePublicKey))) {
            throw new InvalidKeySpecException(
                    "Unsupported key type: " + key.getClass().getName()
                    + ". This KeyFactory supports only Android Keystore asymmetric keys");
        }

        // key is an Android Keystore private or public key

        if (keySpecClass == null) {
            throw new InvalidKeySpecException("keySpecClass == null");
        } else if (KeyInfo.class.equals(keySpecClass)) {
            if (!(key instanceof AndroidKeyStorePrivateKey)) {
                throw new InvalidKeySpecException(
                        "Unsupported key type: " + key.getClass().getName()
                        + ". KeyInfo can be obtained only for Android Keystore private keys");
            }
            AndroidKeyStorePrivateKey keystorePrivateKey = (AndroidKeyStorePrivateKey) key;
            @SuppressWarnings("unchecked")
            T result = (T) AndroidKeyStoreSecretKeyFactorySpi.getKeyInfo(keystorePrivateKey);
            return result;
        } else if (X509EncodedKeySpec.class.equals(keySpecClass)) {
            if (!(key instanceof AndroidKeyStorePublicKey)) {
                throw new InvalidKeySpecException(
                        "Unsupported key type: " + key.getClass().getName()
                        + ". X509EncodedKeySpec can be obtained only for Android Keystore public"
                        + " keys");
            }
            @SuppressWarnings("unchecked")
            T result = (T) new X509EncodedKeySpec(((AndroidKeyStorePublicKey) key).getEncoded());
            return result;
        } else if (PKCS8EncodedKeySpec.class.equals(keySpecClass)) {
            if (key instanceof AndroidKeyStorePrivateKey) {
                throw new InvalidKeySpecException(
                        "Key material export of Android Keystore private keys is not supported");
            } else {
                throw new InvalidKeySpecException(
                        "Cannot export key material of public key in PKCS#8 format."
                        + " Only X.509 format (X509EncodedKeySpec) supported for public keys.");
            }
        } else if (RSAPublicKeySpec.class.equals(keySpecClass)) {
            if (key instanceof AndroidKeyStoreRSAPublicKey) {
                AndroidKeyStoreRSAPublicKey rsaKey = (AndroidKeyStoreRSAPublicKey) key;
                @SuppressWarnings("unchecked")
                T result =
                        (T) new RSAPublicKeySpec(rsaKey.getModulus(), rsaKey.getPublicExponent());
                return result;
            } else {
                throw new InvalidKeySpecException(
                        "Obtaining RSAPublicKeySpec not supported for " + key.getAlgorithm() + " "
                        + ((key instanceof AndroidKeyStorePrivateKey) ? "private" : "public")
                        + " key");
            }
        } else if (ECPublicKeySpec.class.equals(keySpecClass)) {
            if (key instanceof AndroidKeyStoreECPublicKey) {
                AndroidKeyStoreECPublicKey ecKey = (AndroidKeyStoreECPublicKey) key;
                @SuppressWarnings("unchecked")
                T result = (T) new ECPublicKeySpec(ecKey.getW(), ecKey.getParams());
                return result;
            } else {
                throw new InvalidKeySpecException(
                        "Obtaining ECPublicKeySpec not supported for " + key.getAlgorithm() + " "
                        + ((key instanceof AndroidKeyStorePrivateKey) ? "private" : "public")
                        + " key");
            }
        } else {
            throw new InvalidKeySpecException("Unsupported key spec: " + keySpecClass.getName());
        }
    }

    @Override
    protected PrivateKey engineGeneratePrivate(KeySpec spec) throws InvalidKeySpecException {
        throw new InvalidKeySpecException(
                "To generate a key pair in Android Keystore, use KeyPairGenerator initialized with"
                + " " + KeyGenParameterSpec.class.getName());
    }

    @Override
    protected PublicKey engineGeneratePublic(KeySpec spec) throws InvalidKeySpecException {
        throw new InvalidKeySpecException(
                "To generate a key pair in Android Keystore, use KeyPairGenerator initialized with"
                + " " + KeyGenParameterSpec.class.getName());
    }

    @Override
    protected Key engineTranslateKey(Key key) throws InvalidKeyException {
        if (key == null) {
            throw new InvalidKeyException("key == null");
        } else if ((!(key instanceof AndroidKeyStorePrivateKey))
                && (!(key instanceof AndroidKeyStorePublicKey))) {
            throw new InvalidKeyException(
                    "To import a key into Android Keystore, use KeyStore.setEntry");
        }
        return key;
    }
}
