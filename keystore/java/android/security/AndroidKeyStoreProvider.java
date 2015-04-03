/*
 * Copyright (C) 2012 The Android Open Source Project
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

import java.security.Provider;

import javax.crypto.Cipher;
import javax.crypto.Mac;

/**
 * A provider focused on providing JCA interfaces for the Android KeyStore.
 *
 * @hide
 */
public class AndroidKeyStoreProvider extends Provider {
    public static final String PROVIDER_NAME = "AndroidKeyStore";

    // IMPLEMENTATION NOTE: Class names are hard-coded in this provider to avoid loading these
    // classes when this provider is instantiated and installed early on during each app's
    // initialization process.

    private static final String PACKAGE_NAME = "android.security";
    private static final String KEYSTORE_SECRET_KEY_CLASS_NAME =
            PACKAGE_NAME + ".KeyStoreSecretKey";

    public AndroidKeyStoreProvider() {
        super(PROVIDER_NAME, 1.0, "Android KeyStore security provider");

        // java.security.KeyStore
        put("KeyStore.AndroidKeyStore", PACKAGE_NAME + ".AndroidKeyStore");

        // java.security.KeyPairGenerator
        put("KeyPairGenerator.EC", PACKAGE_NAME + ".AndroidKeyPairGenerator$EC");
        put("KeyPairGenerator.RSA", PACKAGE_NAME +  ".AndroidKeyPairGenerator$RSA");

        // javax.crypto.KeyGenerator
        put("KeyGenerator.AES", PACKAGE_NAME + ".KeyStoreKeyGeneratorSpi$AES");
        put("KeyGenerator.HmacSHA256", PACKAGE_NAME + ".KeyStoreKeyGeneratorSpi$HmacSHA256");

        // java.security.SecretKeyFactory
        put("SecretKeyFactory.AES", PACKAGE_NAME + ".KeyStoreSecretKeyFactorySpi");
        put("SecretKeyFactory.HmacSHA256", PACKAGE_NAME + ".KeyStoreSecretKeyFactorySpi");

        // javax.crypto.Mac
        putMacImpl("HmacSHA256", PACKAGE_NAME + ".KeyStoreHmacSpi$HmacSHA256");

        // javax.crypto.Cipher
        putSymmetricCipherImpl("AES/ECB/NoPadding",
                PACKAGE_NAME + ".KeyStoreCipherSpi$AES$ECB$NoPadding");
        putSymmetricCipherImpl("AES/ECB/PKCS7Padding",
                PACKAGE_NAME + ".KeyStoreCipherSpi$AES$ECB$PKCS7Padding");

        putSymmetricCipherImpl("AES/CBC/NoPadding",
                PACKAGE_NAME + ".KeyStoreCipherSpi$AES$CBC$NoPadding");
        putSymmetricCipherImpl("AES/CBC/PKCS7Padding",
                PACKAGE_NAME + ".KeyStoreCipherSpi$AES$CBC$PKCS7Padding");

        putSymmetricCipherImpl("AES/CTR/NoPadding",
                PACKAGE_NAME + ".KeyStoreCipherSpi$AES$CTR$NoPadding");
    }

    private void putMacImpl(String algorithm, String implClass) {
        put("Mac." + algorithm, implClass);
        put("Mac." + algorithm + " SupportedKeyClasses", KEYSTORE_SECRET_KEY_CLASS_NAME);
    }

    private void putSymmetricCipherImpl(String transformation, String implClass) {
        put("Cipher." + transformation, implClass);
        put("Cipher." + transformation + " SupportedKeyClasses", KEYSTORE_SECRET_KEY_CLASS_NAME);
    }

    /**
     * Gets the {@link KeyStore} operation handle corresponding to the provided JCA crypto
     * primitive.
     *
     * <p>The following primitives are supported: {@link Cipher} and {@link Mac}.
     *
     * @return KeyStore operation handle or {@code null} if the provided primitive's KeyStore
     *         operation is not in progress.
     *
     * @throws IllegalArgumentException if the provided primitive is not supported or is not backed
     *         by AndroidKeyStore provider.
     */
    public static Long getKeyStoreOperationHandle(Object cryptoPrimitive) {
        if (cryptoPrimitive == null) {
            throw new NullPointerException();
        }
        Object spi;
        if (cryptoPrimitive instanceof Mac) {
            spi = ((Mac) cryptoPrimitive).getSpi();
        } else if (cryptoPrimitive instanceof Cipher) {
            spi = ((Cipher) cryptoPrimitive).getSpi();
        } else {
            throw new IllegalArgumentException("Unsupported crypto primitive: " + cryptoPrimitive);
        }
        if (!(spi instanceof KeyStoreCryptoOperation)) {
            throw new IllegalArgumentException(
                    "Crypto primitive not backed by AndroidKeyStore: " + cryptoPrimitive
                    + ", spi: " + spi);
        }
        return ((KeyStoreCryptoOperation) spi).getOperationHandle();
    }
}
