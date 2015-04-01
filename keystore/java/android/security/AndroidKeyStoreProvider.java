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

import java.lang.reflect.Method;
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

    public AndroidKeyStoreProvider() {
        super(PROVIDER_NAME, 1.0, "Android KeyStore security provider");

        // java.security.KeyStore
        put("KeyStore." + AndroidKeyStore.NAME, AndroidKeyStore.class.getName());

        // java.security.KeyPairGenerator
        put("KeyPairGenerator.EC", AndroidKeyPairGenerator.EC.class.getName());
        put("KeyPairGenerator.RSA", AndroidKeyPairGenerator.RSA.class.getName());

        // javax.crypto.KeyGenerator
        put("KeyGenerator.AES", KeyStoreKeyGeneratorSpi.AES.class.getName());
        put("KeyGenerator.HmacSHA256", KeyStoreKeyGeneratorSpi.HmacSHA256.class.getName());

        // java.security.SecretKeyFactory
        put("SecretKeyFactory.AES", KeyStoreSecretKeyFactorySpi.class.getName());
        put("SecretKeyFactory.HmacSHA256", KeyStoreSecretKeyFactorySpi.class.getName());

        // javax.crypto.Mac
        putMacImpl("HmacSHA256", KeyStoreHmacSpi.HmacSHA256.class.getName());

        // javax.crypto.Cipher
        putSymmetricCipherImpl("AES/ECB/NoPadding",
                KeyStoreCipherSpi.AES.ECB.NoPadding.class.getName());
        putSymmetricCipherImpl("AES/ECB/PKCS7Padding",
                KeyStoreCipherSpi.AES.ECB.PKCS7Padding.class.getName());

        putSymmetricCipherImpl("AES/CBC/NoPadding",
                KeyStoreCipherSpi.AES.CBC.NoPadding.class.getName());
        putSymmetricCipherImpl("AES/CBC/PKCS7Padding",
                KeyStoreCipherSpi.AES.CBC.PKCS7Padding.class.getName());

        putSymmetricCipherImpl("AES/CTR/NoPadding",
                KeyStoreCipherSpi.AES.CTR.NoPadding.class.getName());
    }

    private void putMacImpl(String algorithm, String implClass) {
        put("Mac." + algorithm, implClass);
        put("Mac." + algorithm + " SupportedKeyClasses", KeyStoreSecretKey.class.getName());
    }

    private void putSymmetricCipherImpl(String transformation, String implClass) {
        put("Cipher." + transformation, implClass);
        put("Cipher." + transformation + " SupportedKeyClasses", KeyStoreSecretKey.class.getName());
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
        if ((!(cryptoPrimitive instanceof Mac)) && (!(cryptoPrimitive instanceof Cipher))) {
            throw new IllegalArgumentException("Unsupported crypto primitive: " + cryptoPrimitive);
        }
        Object spi;
        // TODO: Replace this Reflection based codewith direct invocations once the libcore changes
        // are in.
        try {
            Method getSpiMethod = cryptoPrimitive.getClass().getDeclaredMethod("getSpi");
            getSpiMethod.setAccessible(true);
            spi = getSpiMethod.invoke(cryptoPrimitive);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    "Unsupported crypto primitive: " + cryptoPrimitive, e);
        }
        if (!(spi instanceof KeyStoreCryptoOperation)) {
            throw new IllegalArgumentException(
                    "Crypto primitive not backed by Android KeyStore: " + cryptoPrimitive
                    + ", spi: " + spi);
        }
        return ((KeyStoreCryptoOperation) spi).getOperationHandle();
    }
}
