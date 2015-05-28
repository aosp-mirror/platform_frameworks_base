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

import java.security.Provider;

/**
 * {@link Provider} of JCA crypto operations operating on Android KeyStore keys.
 *
 * <p>This provider was separated out of {@link AndroidKeyStoreProvider} to work around the issue
 * that Bouncy Castle provider incorrectly declares that it accepts arbitrary keys (incl. Android
 * KeyStore ones). This causes JCA to select the Bouncy Castle's implementation of JCA crypto
 * operations for Android KeyStore keys unless Android KeyStore's own implementations are installed
 * as higher-priority than Bouncy Castle ones. The purpose of this provider is to do just that: to
 * offer crypto operations operating on Android KeyStore keys and to be installed at higher priority
 * than the Bouncy Castle provider.
 *
 * <p>Once Bouncy Castle provider is fixed, this provider can be merged into the
 * {@code AndroidKeyStoreProvider}.
 *
 * @hide
 */
class AndroidKeyStoreBCWorkaroundProvider extends Provider {

    // IMPLEMENTATION NOTE: Class names are hard-coded in this provider to avoid loading these
    // classes when this provider is instantiated and installed early on during each app's
    // initialization process.

    private static final String PACKAGE_NAME = "android.security.keystore";
    private static final String KEYSTORE_SECRET_KEY_CLASS_NAME =
            PACKAGE_NAME + ".AndroidKeyStoreSecretKey";

    AndroidKeyStoreBCWorkaroundProvider() {
        super("AndroidKeyStoreBCWorkaround",
                1.0,
                "Android KeyStore security provider to work around Bouncy Castle");

        // javax.crypto.Mac
        putMacImpl("HmacSHA1", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA1");
        putMacImpl("HmacSHA224", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA224");
        putMacImpl("HmacSHA256", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA256");
        putMacImpl("HmacSHA384", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA384");
        putMacImpl("HmacSHA512", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA512");

        // javax.crypto.Cipher
        putSymmetricCipherImpl("AES/ECB/NoPadding",
                PACKAGE_NAME + ".AndroidKeyStoreUnauthenticatedAESCipherSpi$ECB$NoPadding");
        putSymmetricCipherImpl("AES/ECB/PKCS7Padding",
                PACKAGE_NAME + ".AndroidKeyStoreUnauthenticatedAESCipherSpi$ECB$PKCS7Padding");

        putSymmetricCipherImpl("AES/CBC/NoPadding",
                PACKAGE_NAME + ".AndroidKeyStoreUnauthenticatedAESCipherSpi$CBC$NoPadding");
        putSymmetricCipherImpl("AES/CBC/PKCS7Padding",
                PACKAGE_NAME + ".AndroidKeyStoreUnauthenticatedAESCipherSpi$CBC$PKCS7Padding");

        putSymmetricCipherImpl("AES/CTR/NoPadding",
                PACKAGE_NAME + ".AndroidKeyStoreUnauthenticatedAESCipherSpi$CTR$NoPadding");
    }

    private void putMacImpl(String algorithm, String implClass) {
        put("Mac." + algorithm, implClass);
        put("Mac." + algorithm + " SupportedKeyClasses", KEYSTORE_SECRET_KEY_CLASS_NAME);
    }

    private void putSymmetricCipherImpl(String transformation, String implClass) {
        put("Cipher." + transformation, implClass);
        put("Cipher." + transformation + " SupportedKeyClasses", KEYSTORE_SECRET_KEY_CLASS_NAME);
    }
}
