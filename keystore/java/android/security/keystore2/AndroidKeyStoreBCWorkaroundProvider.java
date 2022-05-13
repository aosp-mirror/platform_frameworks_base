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

    private static final String PACKAGE_NAME = "android.security.keystore2";
    private static final String KEYSTORE_SECRET_KEY_CLASS_NAME =
            PACKAGE_NAME + ".AndroidKeyStoreSecretKey";
    private static final String KEYSTORE_PRIVATE_KEY_CLASS_NAME =
            PACKAGE_NAME + ".AndroidKeyStorePrivateKey";
    private static final String KEYSTORE_PUBLIC_KEY_CLASS_NAME =
            PACKAGE_NAME + ".AndroidKeyStorePublicKey";

    private static final String DESEDE_SYSTEM_PROPERTY = "ro.hardware.keystore_desede";

    AndroidKeyStoreBCWorkaroundProvider() {
        super("AndroidKeyStoreBCWorkaround",
                1.0,
                "Android KeyStore security provider to work around Bouncy Castle");

        // --------------------- javax.crypto.Mac
        putMacImpl("HmacSHA1", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA1");
        put("Alg.Alias.Mac.1.2.840.113549.2.7", "HmacSHA1");
        put("Alg.Alias.Mac.HMAC-SHA1", "HmacSHA1");
        put("Alg.Alias.Mac.HMAC/SHA1", "HmacSHA1");

        putMacImpl("HmacSHA224", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA224");
        put("Alg.Alias.Mac.1.2.840.113549.2.9", "HmacSHA224");
        put("Alg.Alias.Mac.HMAC-SHA224", "HmacSHA224");
        put("Alg.Alias.Mac.HMAC/SHA224", "HmacSHA224");

        putMacImpl("HmacSHA256", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA256");
        put("Alg.Alias.Mac.1.2.840.113549.2.9", "HmacSHA256");
        put("Alg.Alias.Mac.HMAC-SHA256", "HmacSHA256");
        put("Alg.Alias.Mac.HMAC/SHA256", "HmacSHA256");

        putMacImpl("HmacSHA384", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA384");
        put("Alg.Alias.Mac.1.2.840.113549.2.10", "HmacSHA384");
        put("Alg.Alias.Mac.HMAC-SHA384", "HmacSHA384");
        put("Alg.Alias.Mac.HMAC/SHA384", "HmacSHA384");

        putMacImpl("HmacSHA512", PACKAGE_NAME + ".AndroidKeyStoreHmacSpi$HmacSHA512");
        put("Alg.Alias.Mac.1.2.840.113549.2.11", "HmacSHA512");
        put("Alg.Alias.Mac.HMAC-SHA512", "HmacSHA512");
        put("Alg.Alias.Mac.HMAC/SHA512", "HmacSHA512");

        // --------------------- javax.crypto.Cipher
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

        if ("true".equals(android.os.SystemProperties.get(DESEDE_SYSTEM_PROPERTY))) {
            putSymmetricCipherImpl("DESede/CBC/NoPadding",
                PACKAGE_NAME + ".AndroidKeyStore3DESCipherSpi$CBC$NoPadding");
            putSymmetricCipherImpl("DESede/CBC/PKCS7Padding",
                PACKAGE_NAME + ".AndroidKeyStore3DESCipherSpi$CBC$PKCS7Padding");

            putSymmetricCipherImpl("DESede/ECB/NoPadding",
                PACKAGE_NAME + ".AndroidKeyStore3DESCipherSpi$ECB$NoPadding");
            putSymmetricCipherImpl("DESede/ECB/PKCS7Padding",
                PACKAGE_NAME + ".AndroidKeyStore3DESCipherSpi$ECB$PKCS7Padding");
        }

        putSymmetricCipherImpl("AES/GCM/NoPadding",
                PACKAGE_NAME + ".AndroidKeyStoreAuthenticatedAESCipherSpi$GCM$NoPadding");

        putAsymmetricCipherImpl("RSA/ECB/NoPadding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$NoPadding");
        put("Alg.Alias.Cipher.RSA/None/NoPadding", "RSA/ECB/NoPadding");
        putAsymmetricCipherImpl("RSA/ECB/PKCS1Padding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$PKCS1Padding");
        put("Alg.Alias.Cipher.RSA/None/PKCS1Padding", "RSA/ECB/PKCS1Padding");
        putAsymmetricCipherImpl("RSA/ECB/OAEPPadding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$OAEPWithSHA1AndMGF1Padding");
        put("Alg.Alias.Cipher.RSA/None/OAEPPadding", "RSA/ECB/OAEPPadding");
        putAsymmetricCipherImpl("RSA/ECB/OAEPWithSHA-1AndMGF1Padding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$OAEPWithSHA1AndMGF1Padding");
        put("Alg.Alias.Cipher.RSA/None/OAEPWithSHA-1AndMGF1Padding",
                "RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        putAsymmetricCipherImpl("RSA/ECB/OAEPWithSHA-224AndMGF1Padding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$OAEPWithSHA224AndMGF1Padding");
        put("Alg.Alias.Cipher.RSA/None/OAEPWithSHA-224AndMGF1Padding",
                "RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        putAsymmetricCipherImpl("RSA/ECB/OAEPWithSHA-256AndMGF1Padding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$OAEPWithSHA256AndMGF1Padding");
        put("Alg.Alias.Cipher.RSA/None/OAEPWithSHA-256AndMGF1Padding",
                "RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        putAsymmetricCipherImpl("RSA/ECB/OAEPWithSHA-384AndMGF1Padding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$OAEPWithSHA384AndMGF1Padding");
        put("Alg.Alias.Cipher.RSA/None/OAEPWithSHA-384AndMGF1Padding",
                "RSA/ECB/OAEPWithSHA-384AndMGF1Padding");
        putAsymmetricCipherImpl("RSA/ECB/OAEPWithSHA-512AndMGF1Padding",
                PACKAGE_NAME + ".AndroidKeyStoreRSACipherSpi$OAEPWithSHA512AndMGF1Padding");
        put("Alg.Alias.Cipher.RSA/None/OAEPWithSHA-512AndMGF1Padding",
                "RSA/ECB/OAEPWithSHA-512AndMGF1Padding");

        // --------------------- java.security.Signature
        putSignatureImpl("NONEwithRSA",
                PACKAGE_NAME + ".AndroidKeyStoreRSASignatureSpi$NONEWithPKCS1Padding");

        putSignatureImpl("MD5withRSA",
                PACKAGE_NAME + ".AndroidKeyStoreRSASignatureSpi$MD5WithPKCS1Padding");
        put("Alg.Alias.Signature.MD5WithRSAEncryption", "MD5withRSA");
        put("Alg.Alias.Signature.MD5/RSA", "MD5withRSA");
        put("Alg.Alias.Signature.1.2.840.113549.1.1.4", "MD5withRSA");
        put("Alg.Alias.Signature.1.2.840.113549.2.5with1.2.840.113549.1.1.1", "MD5withRSA");

        putSignatureImpl("SHA1withRSA",
                PACKAGE_NAME + ".AndroidKeyStoreRSASignatureSpi$SHA1WithPKCS1Padding");
        put("Alg.Alias.Signature.SHA1WithRSAEncryption", "SHA1withRSA");
        put("Alg.Alias.Signature.SHA1/RSA", "SHA1withRSA");
        put("Alg.Alias.Signature.SHA-1/RSA", "SHA1withRSA");
        put("Alg.Alias.Signature.1.2.840.113549.1.1.5", "SHA1withRSA");
        put("Alg.Alias.Signature.1.3.14.3.2.26with1.2.840.113549.1.1.1", "SHA1withRSA");
        put("Alg.Alias.Signature.1.3.14.3.2.26with1.2.840.113549.1.1.5", "SHA1withRSA");
        put("Alg.Alias.Signature.1.3.14.3.2.29", "SHA1withRSA");

        putSignatureImpl("SHA224withRSA",
                PACKAGE_NAME + ".AndroidKeyStoreRSASignatureSpi$SHA224WithPKCS1Padding");
        put("Alg.Alias.Signature.SHA224WithRSAEncryption", "SHA224withRSA");
        put("Alg.Alias.Signature.1.2.840.113549.1.1.11", "SHA224withRSA");
        put("Alg.Alias.Signature.2.16.840.1.101.3.4.2.4with1.2.840.113549.1.1.1",
                "SHA224withRSA");
        put("Alg.Alias.Signature.2.16.840.1.101.3.4.2.4with1.2.840.113549.1.1.11",
                "SHA224withRSA");

        putSignatureImpl("SHA256withRSA",
                PACKAGE_NAME + ".AndroidKeyStoreRSASignatureSpi$SHA256WithPKCS1Padding");
        put("Alg.Alias.Signature.SHA256WithRSAEncryption", "SHA256withRSA");
        put("Alg.Alias.Signature.1.2.840.113549.1.1.11", "SHA256withRSA");
        put("Alg.Alias.Signature.2.16.840.1.101.3.4.2.1with1.2.840.113549.1.1.1",
                "SHA256withRSA");
        put("Alg.Alias.Signature.2.16.840.1.101.3.4.2.1with1.2.840.113549.1.1.11",
                "SHA256withRSA");

        putSignatureImpl("SHA384withRSA",
                PACKAGE_NAME + ".AndroidKeyStoreRSASignatureSpi$SHA384WithPKCS1Padding");
        put("Alg.Alias.Signature.SHA384WithRSAEncryption", "SHA384withRSA");
        put("Alg.Alias.Signature.1.2.840.113549.1.1.12", "SHA384withRSA");
        put("Alg.Alias.Signature.2.16.840.1.101.3.4.2.2with1.2.840.113549.1.1.1",
                "SHA384withRSA");

        putSignatureImpl("SHA512withRSA",
                PACKAGE_NAME + ".AndroidKeyStoreRSASignatureSpi$SHA512WithPKCS1Padding");
        put("Alg.Alias.Signature.SHA512WithRSAEncryption", "SHA512withRSA");
        put("Alg.Alias.Signature.1.2.840.113549.1.1.13", "SHA512withRSA");
        put("Alg.Alias.Signature.2.16.840.1.101.3.4.2.3with1.2.840.113549.1.1.1",
                "SHA512withRSA");

        putSignatureImpl("SHA1withRSA/PSS",
                PACKAGE_NAME + ".AndroidKeyStoreRSASignatureSpi$SHA1WithPSSPadding");
        putSignatureImpl("SHA224withRSA/PSS",
                PACKAGE_NAME + ".AndroidKeyStoreRSASignatureSpi$SHA224WithPSSPadding");
        putSignatureImpl("SHA256withRSA/PSS",
                PACKAGE_NAME + ".AndroidKeyStoreRSASignatureSpi$SHA256WithPSSPadding");
        putSignatureImpl("SHA384withRSA/PSS",
                PACKAGE_NAME + ".AndroidKeyStoreRSASignatureSpi$SHA384WithPSSPadding");
        putSignatureImpl("SHA512withRSA/PSS",
                PACKAGE_NAME + ".AndroidKeyStoreRSASignatureSpi$SHA512WithPSSPadding");

        putSignatureImpl("NONEwithECDSA",
                PACKAGE_NAME + ".AndroidKeyStoreECDSASignatureSpi$NONE");
        putSignatureImpl("Ed25519",
                PACKAGE_NAME + ".AndroidKeyStoreECDSASignatureSpi$Ed25519");

        putSignatureImpl("SHA1withECDSA", PACKAGE_NAME + ".AndroidKeyStoreECDSASignatureSpi$SHA1");
        put("Alg.Alias.Signature.ECDSA", "SHA1withECDSA");
        put("Alg.Alias.Signature.ECDSAwithSHA1", "SHA1withECDSA");
        // iso(1) member-body(2) us(840) ansi-x962(10045) signatures(4) ecdsa-with-SHA1(1)
        put("Alg.Alias.Signature.1.2.840.10045.4.1", "SHA1withECDSA");
        put("Alg.Alias.Signature.1.3.14.3.2.26with1.2.840.10045.2.1", "SHA1withECDSA");

        // iso(1) member-body(2) us(840) ansi-x962(10045) signatures(4) ecdsa-with-SHA2(3)
        putSignatureImpl("SHA224withECDSA",
                PACKAGE_NAME + ".AndroidKeyStoreECDSASignatureSpi$SHA224");
        // ecdsa-with-SHA224(1)
        put("Alg.Alias.Signature.1.2.840.10045.4.3.1", "SHA224withECDSA");
        put("Alg.Alias.Signature.2.16.840.1.101.3.4.2.4with1.2.840.10045.2.1", "SHA224withECDSA");

        // iso(1) member-body(2) us(840) ansi-x962(10045) signatures(4) ecdsa-with-SHA2(3)
        putSignatureImpl("SHA256withECDSA",
                PACKAGE_NAME + ".AndroidKeyStoreECDSASignatureSpi$SHA256");
        // ecdsa-with-SHA256(2)
        put("Alg.Alias.Signature.1.2.840.10045.4.3.2", "SHA256withECDSA");
        put("Alg.Alias.Signature.2.16.840.1.101.3.4.2.1with1.2.840.10045.2.1", "SHA256withECDSA");

        putSignatureImpl("SHA384withECDSA",
                PACKAGE_NAME + ".AndroidKeyStoreECDSASignatureSpi$SHA384");
        // ecdsa-with-SHA384(3)
        put("Alg.Alias.Signature.1.2.840.10045.4.3.3", "SHA384withECDSA");
        put("Alg.Alias.Signature.2.16.840.1.101.3.4.2.2with1.2.840.10045.2.1", "SHA384withECDSA");

        putSignatureImpl("SHA512withECDSA",
                PACKAGE_NAME + ".AndroidKeyStoreECDSASignatureSpi$SHA512");
        // ecdsa-with-SHA512(4)
        put("Alg.Alias.Signature.1.2.840.10045.4.3.4", "SHA512withECDSA");
        put("Alg.Alias.Signature.2.16.840.1.101.3.4.2.3with1.2.840.10045.2.1", "SHA512withECDSA");
    }

    private void putMacImpl(String algorithm, String implClass) {
        put("Mac." + algorithm, implClass);
        put("Mac." + algorithm + " SupportedKeyClasses", KEYSTORE_SECRET_KEY_CLASS_NAME);
    }

    private void putSymmetricCipherImpl(String transformation, String implClass) {
        put("Cipher." + transformation, implClass);
        put("Cipher." + transformation + " SupportedKeyClasses", KEYSTORE_SECRET_KEY_CLASS_NAME);
    }

    private void putAsymmetricCipherImpl(String transformation, String implClass) {
        put("Cipher." + transformation, implClass);
        put("Cipher." + transformation + " SupportedKeyClasses",
                KEYSTORE_PRIVATE_KEY_CLASS_NAME);
    }

    private void putSignatureImpl(String algorithm, String implClass) {
        put("Signature." + algorithm, implClass);
        put("Signature." + algorithm + " SupportedKeyClasses",
                KEYSTORE_PRIVATE_KEY_CLASS_NAME);
    }

    public static String[] getSupportedEcdsaSignatureDigests() {
        return new String[] {"NONE", "SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512"};
    }

    public static String[] getSupportedRsaSignatureWithPkcs1PaddingDigests() {
        return new String[] {"NONE", "MD5", "SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512"};
    }
}
