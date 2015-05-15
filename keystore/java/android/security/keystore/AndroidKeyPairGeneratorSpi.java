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

import android.annotation.NonNull;
import android.security.Credentials;
import android.security.KeyPairGeneratorSpec;
import android.security.KeyStore;

import com.android.org.bouncycastle.x509.X509V3CertificateGenerator;
import com.android.org.conscrypt.NativeConstants;
import com.android.org.conscrypt.OpenSSLEngine;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyPairGeneratorSpi;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;

/**
 * Provides a way to create instances of a KeyPair which will be placed in the
 * Android keystore service usable only by the application that called it. This
 * can be used in conjunction with
 * {@link java.security.KeyStore#getInstance(String)} using the
 * {@code "AndroidKeyStore"} type.
 * <p>
 * This class can not be directly instantiated and must instead be used via the
 * {@link KeyPairGenerator#getInstance(String)
 * KeyPairGenerator.getInstance("AndroidKeyPairGenerator")} API.
 *
 * {@hide}
 */
public abstract class AndroidKeyPairGeneratorSpi extends KeyPairGeneratorSpi {

    public static class RSA extends AndroidKeyPairGeneratorSpi {
        public RSA() {
            super(KeyProperties.KEY_ALGORITHM_RSA);
        }
    }

    public static class EC extends AndroidKeyPairGeneratorSpi {
        public EC() {
            super(KeyProperties.KEY_ALGORITHM_EC);
        }
    }

    /*
     * These must be kept in sync with system/security/keystore/defaults.h
     */

    /* EC */
    private static final int EC_DEFAULT_KEY_SIZE = 256;
    private static final int EC_MIN_KEY_SIZE = 192;
    private static final int EC_MAX_KEY_SIZE = 521;

    /* RSA */
    private static final int RSA_DEFAULT_KEY_SIZE = 2048;
    private static final int RSA_MIN_KEY_SIZE = 512;
    private static final int RSA_MAX_KEY_SIZE = 8192;

    private final String mAlgorithm;

    private KeyStore mKeyStore;

    private KeyGenParameterSpec mSpec;
    private boolean mEncryptionAtRestRequired;
    private @KeyProperties.KeyAlgorithmEnum String mKeyAlgorithm;
    private int mKeyType;
    private int mKeySize;

    protected AndroidKeyPairGeneratorSpi(@KeyProperties.KeyAlgorithmEnum String algorithm) {
        mAlgorithm = algorithm;
    }

    @KeyProperties.KeyAlgorithmEnum String getAlgorithm() {
        return mAlgorithm;
    }

    /**
     * Generate a KeyPair which is backed by the Android keystore service. You
     * must call {@link KeyPairGenerator#initialize(AlgorithmParameterSpec)}
     * with an {@link KeyPairGeneratorSpec} as the {@code params}
     * argument before calling this otherwise an {@code IllegalStateException}
     * will be thrown.
     * <p>
     * This will create an entry in the Android keystore service with a
     * self-signed certificate using the {@code params} specified in the
     * {@code initialize(params)} call.
     *
     * @throws IllegalStateException when called before calling
     *             {@link KeyPairGenerator#initialize(AlgorithmParameterSpec)}
     * @see java.security.KeyPairGeneratorSpi#generateKeyPair()
     */
    @Override
    public KeyPair generateKeyPair() {
        if (mKeyStore == null || mSpec == null) {
            throw new IllegalStateException("Not initialized");

        }

        final int flags = (mEncryptionAtRestRequired) ? KeyStore.FLAG_ENCRYPTED : 0;
        if (((flags & KeyStore.FLAG_ENCRYPTED) != 0)
                && (mKeyStore.state() != KeyStore.State.UNLOCKED)) {
            throw new IllegalStateException(
                    "Encryption at rest using secure lock screen credential requested for key pair"
                    + ", but the user has not yet entered the credential");
        }

        final String alias = mSpec.getKeystoreAlias();

        Credentials.deleteAllTypesForAlias(mKeyStore, alias);

        byte[][] args = getArgsForKeyType(mKeyType, mSpec.getAlgorithmParameterSpec());

        final String privateKeyAlias = Credentials.USER_PRIVATE_KEY + alias;

        if (!mKeyStore.generate(privateKeyAlias, KeyStore.UID_SELF, mKeyType, mKeySize,
                flags, args)) {
            throw new IllegalStateException("could not generate key in keystore");
        }

        Credentials.deleteSecretKeyTypeForAlias(mKeyStore, alias);

        final PrivateKey privKey;
        final OpenSSLEngine engine = OpenSSLEngine.getInstance("keystore");
        try {
            privKey = engine.getPrivateKeyById(privateKeyAlias);
        } catch (InvalidKeyException e) {
            throw new RuntimeException("Can't get key", e);
        }

        final byte[] pubKeyBytes = mKeyStore.getPubkey(privateKeyAlias);

        final PublicKey pubKey;
        try {
            final KeyFactory keyFact = KeyFactory.getInstance(mKeyAlgorithm);
            pubKey = keyFact.generatePublic(new X509EncodedKeySpec(pubKeyBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Can't instantiate key generator", e);
        } catch (InvalidKeySpecException e) {
            throw new IllegalStateException("keystore returned invalid key encoding", e);
        }

        final X509Certificate cert;
        try {
            cert = generateCertificate(privKey, pubKey);
        } catch (Exception e) {
            Credentials.deleteAllTypesForAlias(mKeyStore, alias);
            throw new IllegalStateException("Can't generate certificate", e);
        }

        byte[] certBytes;
        try {
            certBytes = cert.getEncoded();
        } catch (CertificateEncodingException e) {
            Credentials.deleteAllTypesForAlias(mKeyStore, alias);
            throw new IllegalStateException("Can't get encoding of certificate", e);
        }

        if (!mKeyStore.put(Credentials.USER_CERTIFICATE + alias, certBytes, KeyStore.UID_SELF,
                flags)) {
            Credentials.deleteAllTypesForAlias(mKeyStore, alias);
            throw new IllegalStateException("Can't store certificate in AndroidKeyStore");
        }

        return new KeyPair(pubKey, privKey);
    }

    @SuppressWarnings("deprecation")
    private X509Certificate generateCertificate(PrivateKey privateKey, PublicKey publicKey)
            throws Exception {
        final X509V3CertificateGenerator certGen = new X509V3CertificateGenerator();
        certGen.setPublicKey(publicKey);
        certGen.setSerialNumber(mSpec.getCertificateSerialNumber());
        certGen.setSubjectDN(mSpec.getCertificateSubject());
        certGen.setIssuerDN(mSpec.getCertificateSubject());
        certGen.setNotBefore(mSpec.getCertificateNotBefore());
        certGen.setNotAfter(mSpec.getCertificateNotAfter());
        certGen.setSignatureAlgorithm(getDefaultSignatureAlgorithmForKeyAlgorithm(mKeyAlgorithm));
        return certGen.generate(privateKey);
    }

    @NonNull
    private @KeyProperties.KeyAlgorithmEnum String getKeyAlgorithm(KeyPairGeneratorSpec spec) {
        String result = spec.getKeyType();
        if (result != null) {
            return result;
        }
        return getAlgorithm();
    }

    private static int getDefaultKeySize(int keyType) {
        if (keyType == NativeConstants.EVP_PKEY_EC) {
            return EC_DEFAULT_KEY_SIZE;
        } else if (keyType == NativeConstants.EVP_PKEY_RSA) {
            return RSA_DEFAULT_KEY_SIZE;
        }
        return -1;
    }

    private static void checkValidKeySize(String keyAlgorithm, int keyType, int keySize)
            throws InvalidAlgorithmParameterException {
        if (keyType == NativeConstants.EVP_PKEY_EC) {
            if (keySize < EC_MIN_KEY_SIZE || keySize > EC_MAX_KEY_SIZE) {
                throw new InvalidAlgorithmParameterException("EC keys must be >= "
                        + EC_MIN_KEY_SIZE + " and <= " + EC_MAX_KEY_SIZE);
            }
        } else if (keyType == NativeConstants.EVP_PKEY_RSA) {
            if (keySize < RSA_MIN_KEY_SIZE || keySize > RSA_MAX_KEY_SIZE) {
                throw new InvalidAlgorithmParameterException("RSA keys must be >= "
                        + RSA_MIN_KEY_SIZE + " and <= " + RSA_MAX_KEY_SIZE);
            }
        } else {
            throw new InvalidAlgorithmParameterException(
                "Unsupported key algorithm: " + keyAlgorithm);
        }
    }

    private static void checkCorrectParametersSpec(int keyType, int keySize,
            AlgorithmParameterSpec spec) throws InvalidAlgorithmParameterException {
        if (keyType == NativeConstants.EVP_PKEY_RSA && spec != null) {
            if (spec instanceof RSAKeyGenParameterSpec) {
                RSAKeyGenParameterSpec rsaSpec = (RSAKeyGenParameterSpec) spec;
                if (keySize != -1 && keySize != rsaSpec.getKeysize()) {
                    throw new InvalidAlgorithmParameterException("RSA key size must match: "
                            + keySize + " vs " + rsaSpec.getKeysize());
                }
            } else {
                throw new InvalidAlgorithmParameterException(
                    "RSA may only use RSAKeyGenParameterSpec");
            }
        }
    }

    private static String getDefaultSignatureAlgorithmForKeyAlgorithm(
            @KeyProperties.KeyAlgorithmEnum String algorithm) {
        if (KeyProperties.KEY_ALGORITHM_RSA.equalsIgnoreCase(algorithm)) {
            return "sha256WithRSA";
        } else if (KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(algorithm)) {
            return "sha256WithECDSA";
        } else {
            throw new IllegalArgumentException("Unsupported key type " + algorithm);
        }
    }

    private static byte[][] getArgsForKeyType(int keyType, AlgorithmParameterSpec spec) {
        switch (keyType) {
            case NativeConstants.EVP_PKEY_RSA:
                if (spec instanceof RSAKeyGenParameterSpec) {
                    RSAKeyGenParameterSpec rsaSpec = (RSAKeyGenParameterSpec) spec;
                    return new byte[][] { rsaSpec.getPublicExponent().toByteArray() };
                }
                break;
        }
        return null;
    }

    @Override
    public void initialize(int keysize, SecureRandom random) {
        throw new IllegalArgumentException("cannot specify keysize with AndroidKeyPairGenerator");
    }

    @Override
    public void initialize(AlgorithmParameterSpec params, SecureRandom random)
            throws InvalidAlgorithmParameterException {
        if (params == null) {
            throw new InvalidAlgorithmParameterException(
                    "Must supply params of type " + KeyGenParameterSpec.class.getName()
                    + " or " + KeyPairGeneratorSpec.class.getName());
        }

        String keyAlgorithm;
        KeyGenParameterSpec spec;
        boolean encryptionAtRestRequired = false;
        if (params instanceof KeyPairGeneratorSpec) {
            KeyPairGeneratorSpec legacySpec = (KeyPairGeneratorSpec) params;
            try {
                KeyGenParameterSpec.Builder specBuilder;
                keyAlgorithm = getKeyAlgorithm(legacySpec).toUpperCase(Locale.US);
                if (KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(keyAlgorithm)) {
                    specBuilder = new KeyGenParameterSpec.Builder(
                            legacySpec.getKeystoreAlias(),
                            KeyProperties.PURPOSE_SIGN
                            | KeyProperties.PURPOSE_VERIFY);
                    specBuilder.setDigests(
                            KeyProperties.DIGEST_NONE,
                            KeyProperties.DIGEST_MD5,
                            KeyProperties.DIGEST_SHA1,
                            KeyProperties.DIGEST_SHA224,
                            KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384,
                            KeyProperties.DIGEST_SHA512);
                } else if (KeyProperties.KEY_ALGORITHM_RSA.equalsIgnoreCase(keyAlgorithm)) {
                    specBuilder = new KeyGenParameterSpec.Builder(
                            legacySpec.getKeystoreAlias(),
                            KeyProperties.PURPOSE_ENCRYPT
                            | KeyProperties.PURPOSE_DECRYPT
                            | KeyProperties.PURPOSE_SIGN
                            | KeyProperties.PURPOSE_VERIFY);
                    specBuilder.setDigests(
                            KeyProperties.DIGEST_NONE,
                            KeyProperties.DIGEST_MD5,
                            KeyProperties.DIGEST_SHA1,
                            KeyProperties.DIGEST_SHA224,
                            KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384,
                            KeyProperties.DIGEST_SHA512);
                    specBuilder.setSignaturePaddings(
                            KeyProperties.SIGNATURE_PADDING_RSA_PKCS1);
                    specBuilder.setBlockModes(KeyProperties.BLOCK_MODE_ECB);
                    specBuilder.setEncryptionPaddings(
                            KeyProperties.ENCRYPTION_PADDING_NONE,
                            KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1);
                    // Disable randomized encryption requirement to support encryption padding NONE
                    // above.
                    specBuilder.setRandomizedEncryptionRequired(false);
                } else {
                    throw new InvalidAlgorithmParameterException(
                            "Unsupported key algorithm: " + keyAlgorithm);
                }

                if (legacySpec.getKeySize() != -1) {
                    specBuilder.setKeySize(legacySpec.getKeySize());
                }
                if (legacySpec.getAlgorithmParameterSpec() != null) {
                    specBuilder.setAlgorithmParameterSpec(legacySpec.getAlgorithmParameterSpec());
                }
                specBuilder.setCertificateSubject(legacySpec.getSubjectDN());
                specBuilder.setCertificateSerialNumber(legacySpec.getSerialNumber());
                specBuilder.setCertificateNotBefore(legacySpec.getStartDate());
                specBuilder.setCertificateNotAfter(legacySpec.getEndDate());
                encryptionAtRestRequired = legacySpec.isEncryptionRequired();
                specBuilder.setUserAuthenticationRequired(false);

                spec = specBuilder.build();
            } catch (NullPointerException | IllegalArgumentException e) {
                throw new InvalidAlgorithmParameterException(e);
            }
        } else if (params instanceof KeyGenParameterSpec) {
            spec = (KeyGenParameterSpec) params;
            keyAlgorithm = getAlgorithm();
        } else {
            throw new InvalidAlgorithmParameterException(
                    "Unsupported params class: " + params.getClass().getName()
                    + ". Supported: " + KeyGenParameterSpec.class.getName()
                    + ", " + KeyPairGeneratorSpec.class);
        }

        int keyType = KeyStore.getKeyTypeForAlgorithm(keyAlgorithm);
        if (keyType == -1) {
            throw new InvalidAlgorithmParameterException(
                    "Unsupported key algorithm: " + keyAlgorithm);
        }
        int keySize = spec.getKeySize();
        if (keySize == -1) {
            keySize = getDefaultKeySize(keyType);
            if (keySize == -1) {
                throw new InvalidAlgorithmParameterException(
                        "Unsupported key algorithm: " + keyAlgorithm);
            }
        }
        checkCorrectParametersSpec(keyType, keySize, spec.getAlgorithmParameterSpec());
        checkValidKeySize(keyAlgorithm, keyType, keySize);

        mKeyAlgorithm = keyAlgorithm;
        mKeyType = keyType;
        mKeySize = keySize;
        mSpec = spec;
        mEncryptionAtRestRequired = encryptionAtRestRequired;
        mKeyStore = KeyStore.getInstance();
    }
}
