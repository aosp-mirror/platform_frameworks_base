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

import com.android.org.bouncycastle.x509.X509V3CertificateGenerator;

import org.apache.harmony.xnet.provider.jsse.OpenSSLEngine;

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
import java.security.spec.X509EncodedKeySpec;

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
@SuppressWarnings("deprecation")
public class AndroidKeyPairGenerator extends KeyPairGeneratorSpi {
    public static final String NAME = "AndroidKeyPairGenerator";

    private android.security.KeyStore mKeyStore;

    private AndroidKeyPairGeneratorSpec mSpec;

    /**
     * Generate a KeyPair which is backed by the Android keystore service. You
     * must call {@link KeyPairGenerator#initialize(AlgorithmParameterSpec)}
     * with an {@link AndroidKeyPairGeneratorSpec} as the {@code params}
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
            throw new IllegalStateException(
                    "Must call initialize with an AndroidKeyPairGeneratorSpec first");
        }

        final String alias = mSpec.getKeystoreAlias();

        Credentials.deleteAllTypesForAlias(mKeyStore, alias);

        final String privateKeyAlias = Credentials.USER_PRIVATE_KEY + alias;
        mKeyStore.generate(privateKeyAlias);

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
            final KeyFactory keyFact = KeyFactory.getInstance("RSA");
            pubKey = keyFact.generatePublic(new X509EncodedKeySpec(pubKeyBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Can't instantiate RSA key generator", e);
        } catch (InvalidKeySpecException e) {
            throw new IllegalStateException("keystore returned invalid key encoding", e);
        }

        final X509V3CertificateGenerator certGen = new X509V3CertificateGenerator();
        certGen.setPublicKey(pubKey);
        certGen.setSerialNumber(mSpec.getSerialNumber());
        certGen.setSubjectDN(mSpec.getSubjectDN());
        certGen.setIssuerDN(mSpec.getSubjectDN());
        certGen.setNotBefore(mSpec.getStartDate());
        certGen.setNotAfter(mSpec.getEndDate());
        certGen.setSignatureAlgorithm("sha1WithRSA");

        final X509Certificate cert;
        try {
            cert = certGen.generate(privKey);
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

        if (!mKeyStore.put(Credentials.USER_CERTIFICATE + alias, certBytes)) {
            Credentials.deleteAllTypesForAlias(mKeyStore, alias);
            throw new IllegalStateException("Can't store certificate in AndroidKeyStore");
        }

        return new KeyPair(pubKey, privKey);
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
                    "must supply params of type AndroidKeyPairGenericSpec");
        } else if (!(params instanceof AndroidKeyPairGeneratorSpec)) {
            throw new InvalidAlgorithmParameterException(
                    "params must be of type AndroidKeyPairGeneratorSpec");
        }

        AndroidKeyPairGeneratorSpec spec = (AndroidKeyPairGeneratorSpec) params;

        mSpec = spec;
        mKeyStore = android.security.KeyStore.getInstance();
    }
}
