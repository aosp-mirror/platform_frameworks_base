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

package android.security.keystore;

import android.security.KeyStore;

import java.security.Provider;
import java.security.Security;

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
    //
    // Crypto operations operating on the AndroidKeyStore keys must not be offered by this provider.
    // Instead, they need to be offered by AndroidKeyStoreBCWorkaroundProvider. See its Javadoc
    // for details.

    private static final String PACKAGE_NAME = "android.security.keystore";

    public AndroidKeyStoreProvider() {
        super(PROVIDER_NAME, 1.0, "Android KeyStore security provider");

        // java.security.KeyStore
        put("KeyStore.AndroidKeyStore", PACKAGE_NAME + ".AndroidKeyStoreSpi");

        // java.security.KeyPairGenerator
        put("KeyPairGenerator.EC", PACKAGE_NAME + ".AndroidKeyStoreKeyPairGeneratorSpi$EC");
        put("KeyPairGenerator.RSA", PACKAGE_NAME +  ".AndroidKeyStoreKeyPairGeneratorSpi$RSA");

        // javax.crypto.KeyGenerator
        put("KeyGenerator.AES", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$AES");
        put("KeyGenerator.HmacSHA1", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA1");
        put("KeyGenerator.HmacSHA224", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA224");
        put("KeyGenerator.HmacSHA256", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA256");
        put("KeyGenerator.HmacSHA384", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA384");
        put("KeyGenerator.HmacSHA512", PACKAGE_NAME + ".AndroidKeyStoreKeyGeneratorSpi$HmacSHA512");

        // java.security.SecretKeyFactory
        putSecretKeyFactoryImpl("AES");
        putSecretKeyFactoryImpl("HmacSHA1");
        putSecretKeyFactoryImpl("HmacSHA224");
        putSecretKeyFactoryImpl("HmacSHA256");
        putSecretKeyFactoryImpl("HmacSHA384");
        putSecretKeyFactoryImpl("HmacSHA512");
    }

    /**
     * Installs a new instance of this provider (and the
     * {@link AndroidKeyStoreBCWorkaroundProvider}).
     */
    public static void install() {
        Provider[] providers = Security.getProviders();
        int bcProviderPosition = -1;
        for (int position = 0; position < providers.length; position++) {
            Provider provider = providers[position];
            if ("BC".equals(provider.getName())) {
                bcProviderPosition = position;
                break;
            }
        }

        Security.addProvider(new AndroidKeyStoreProvider());
        Provider workaroundProvider = new AndroidKeyStoreBCWorkaroundProvider();
        if (bcProviderPosition != -1) {
            // Bouncy Castle provider found -- install the workaround provider above it.
            Security.insertProviderAt(workaroundProvider, bcProviderPosition);
        } else {
            // Bouncy Castle provider not found -- install the workaround provider at lowest
            // priority.
            Security.addProvider(workaroundProvider);
        }
    }

    private void putSecretKeyFactoryImpl(String algorithm) {
        put("SecretKeyFactory." + algorithm, PACKAGE_NAME + ".AndroidKeyStoreSecretKeyFactorySpi");
    }

    /**
     * Gets the {@link KeyStore} operation handle corresponding to the provided JCA crypto
     * primitive.
     *
     * <p>The following primitives are supported: {@link Cipher} and {@link Mac}.
     *
     * @return KeyStore operation handle or {@code 0} if the provided primitive's KeyStore operation
     *         is not in progress.
     *
     * @throws IllegalArgumentException if the provided primitive is not supported or is not backed
     *         by AndroidKeyStore provider.
     */
    public static long getKeyStoreOperationHandle(Object cryptoPrimitive) {
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
