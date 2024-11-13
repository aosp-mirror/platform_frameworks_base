/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.frameworks.coretests.R;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class VerityUtilsTest {
    private static final byte[] SAMPLE_DIGEST = "12345678901234567890123456789012".getBytes();
    private static final byte[] FORMATTED_SAMPLE_DIGEST = toFormattedDigest(SAMPLE_DIGEST);

    KeyPair mKeyPair;
    ContentSigner mContentSigner;
    X509CertificateHolder mCertificateHolder;
    byte[] mCertificateDerEncoded;

    @Before
    public void setUp() throws Exception {
        mKeyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        mContentSigner = newFsverityContentSigner(mKeyPair.getPrivate());
        mCertificateHolder =
                newX509CertificateHolder(mContentSigner, mKeyPair.getPublic(), "Someone");
        mCertificateDerEncoded = mCertificateHolder.getEncoded();
    }

    @Test
    public void testOnlyAcceptCorrectDigest() throws Exception {
        byte[] pkcs7Signature =
                generatePkcs7Signature(mContentSigner, mCertificateHolder, FORMATTED_SAMPLE_DIGEST);

        byte[] anotherDigest = Arrays.copyOf(SAMPLE_DIGEST, SAMPLE_DIGEST.length);
        anotherDigest[0] ^= (byte) 1;

        assertTrue(verifySignature(pkcs7Signature, SAMPLE_DIGEST, mCertificateDerEncoded));
        assertFalse(verifySignature(pkcs7Signature, anotherDigest, mCertificateDerEncoded));
    }

    @Test
    public void testDigestWithWrongSize() throws Exception {
        byte[] pkcs7Signature =
                generatePkcs7Signature(mContentSigner, mCertificateHolder, FORMATTED_SAMPLE_DIGEST);
        assertTrue(verifySignature(pkcs7Signature, SAMPLE_DIGEST, mCertificateDerEncoded));

        byte[] digestTooShort = Arrays.copyOfRange(SAMPLE_DIGEST, 0, SAMPLE_DIGEST.length - 1);
        assertFalse(verifySignature(pkcs7Signature, digestTooShort, mCertificateDerEncoded));

        byte[] digestTooLong = Arrays.copyOfRange(SAMPLE_DIGEST, 0, SAMPLE_DIGEST.length + 1);
        assertFalse(verifySignature(pkcs7Signature, digestTooLong, mCertificateDerEncoded));
    }

    @Test
    public void testOnlyAcceptGoodSignature() throws Exception {
        byte[] pkcs7Signature =
                generatePkcs7Signature(mContentSigner, mCertificateHolder, FORMATTED_SAMPLE_DIGEST);

        byte[] anotherDigest = Arrays.copyOf(SAMPLE_DIGEST, SAMPLE_DIGEST.length);
        anotherDigest[0] ^= (byte) 1;
        byte[] anotherPkcs7Signature =
                generatePkcs7Signature(
                        mContentSigner, mCertificateHolder, toFormattedDigest(anotherDigest));

        assertTrue(verifySignature(pkcs7Signature, SAMPLE_DIGEST, mCertificateDerEncoded));
        assertFalse(verifySignature(anotherPkcs7Signature, SAMPLE_DIGEST, mCertificateDerEncoded));
    }

    @Test
    public void testOnlyValidCertCanVerify() throws Exception {
        byte[] pkcs7Signature =
                generatePkcs7Signature(mContentSigner, mCertificateHolder, FORMATTED_SAMPLE_DIGEST);

        var wrongKeyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var wrongContentSigner = newFsverityContentSigner(wrongKeyPair.getPrivate());
        var wrongCertificateHolder =
                newX509CertificateHolder(wrongContentSigner, wrongKeyPair.getPublic(), "Not Me");
        byte[] wrongCertificateDerEncoded = wrongCertificateHolder.getEncoded();

        assertFalse(verifySignature(pkcs7Signature, SAMPLE_DIGEST, wrongCertificateDerEncoded));
    }

    @Test
    public void testRejectSignatureWithContent() throws Exception {
        CMSSignedDataGenerator generator =
                newFsveritySignedDataGenerator(mContentSigner, mCertificateHolder);
        byte[] pkcs7SignatureNonDetached =
                generatePkcs7SignatureInternal(
                        generator, FORMATTED_SAMPLE_DIGEST, /* encapsulate */ true);

        assertFalse(
                verifySignature(pkcs7SignatureNonDetached, SAMPLE_DIGEST, mCertificateDerEncoded));
    }

    @Test
    public void testRejectSignatureWithCertificate() throws Exception {
        CMSSignedDataGenerator generator =
                newFsveritySignedDataGenerator(mContentSigner, mCertificateHolder);
        generator.addCertificate(mCertificateHolder);
        byte[] pkcs7Signature =
                generatePkcs7SignatureInternal(
                        generator, FORMATTED_SAMPLE_DIGEST, /* encapsulate */ false);

        assertFalse(
                verifySignature(pkcs7Signature, SAMPLE_DIGEST, mCertificateDerEncoded));
    }

    @Ignore("No easy way to construct test data")
    @Test
    public void testRejectSignatureWithCRL() throws Exception {
        CMSSignedDataGenerator generator =
                newFsveritySignedDataGenerator(mContentSigner, mCertificateHolder);

        // The current bouncycastle version does not have an easy way to generate a CRL.
        // TODO: enable the test once this is doable, e.g. with X509v2CRLBuilder.
        // generator.addCRL(new X509CRLHolder(CertificateList.getInstance(new DERSequence(...))));
        byte[] pkcs7Signature =
                generatePkcs7SignatureInternal(
                        generator, FORMATTED_SAMPLE_DIGEST, /* encapsulate */ false);

        assertFalse(
                verifySignature(pkcs7Signature, SAMPLE_DIGEST, mCertificateDerEncoded));
    }

    @Test
    public void testRejectUnsupportedSignatureAlgorithms() throws Exception {
        var contentSigner = newFsverityContentSigner(mKeyPair.getPrivate(), "MD5withRSA", null);
        var certificateHolder =
                newX509CertificateHolder(contentSigner, mKeyPair.getPublic(), "Someone");
        byte[] pkcs7Signature =
                generatePkcs7Signature(contentSigner, certificateHolder, FORMATTED_SAMPLE_DIGEST);

        assertFalse(verifySignature(pkcs7Signature, SAMPLE_DIGEST, certificateHolder.getEncoded()));
    }

    @Test
    public void testRejectUnsupportedDigestAlgorithm() throws Exception {
        CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
        generator.addSignerInfoGenerator(
                newSignerInfoGenerator(
                        mContentSigner,
                        mCertificateHolder,
                        OIWObjectIdentifiers.idSHA1,
                        true)); // directSignature
        byte[] pkcs7Signature =
                generatePkcs7SignatureInternal(
                        generator, FORMATTED_SAMPLE_DIGEST, /* encapsulate */ false);

        assertFalse(verifySignature(pkcs7Signature, SAMPLE_DIGEST, mCertificateDerEncoded));
    }

    @Test
    public void testRejectAnySignerInfoAttributes() throws Exception {
        var generator = new CMSSignedDataGenerator();
        generator.addSignerInfoGenerator(
                newSignerInfoGenerator(
                        mContentSigner,
                        mCertificateHolder,
                        NISTObjectIdentifiers.id_sha256,
                        false)); // directSignature
        byte[] pkcs7Signature =
                generatePkcs7SignatureInternal(
                        generator, FORMATTED_SAMPLE_DIGEST, /* encapsulate */ false);

        assertFalse(verifySignature(pkcs7Signature, SAMPLE_DIGEST, mCertificateDerEncoded));
    }

    @Test
    public void testSignatureGeneratedExternally() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getContext();
        byte[] cert = getClass().getClassLoader().getResourceAsStream("unit_test.der")
                .readAllBytes();
        // The signature is generated by:
        //   openssl pkcs8 -topk8 -nocrypt -in certs/unit_test.pk8 -out certs/unit_test.key.pem
        //   fsverity sign <(echo -n fs-verity) fsverity_sig --key=certs/unit_test.key.pem \
        //   --cert=certs/unit_test.x509.pem
        byte[] sig = context.getResources().openRawResource(R.raw.fsverity_sig).readAllBytes();
        // The fs-verity digest is generated by:
        //   fsverity digest --compact <(echo -n fs-verity)
        byte[] digest = HexFormat.of().parseHex(
                "3d248ca542a24fc62d1c43b916eae5016878e2533c88238480b26128a1f1af95");

        assertTrue(verifySignature(sig, digest, cert));
    }

    private static boolean verifySignature(
            byte[] pkcs7Signature, byte[] fsverityDigest, byte[] certificateDerEncoded) {
        return VerityUtils.verifyPkcs7DetachedSignature(
                pkcs7Signature, fsverityDigest, new ByteArrayInputStream(certificateDerEncoded));
    }

    private static byte[] toFormattedDigest(byte[] digest) {
        return VerityUtils.toFormattedDigest(digest);
    }

    private static byte[] generatePkcs7Signature(
            ContentSigner contentSigner, X509CertificateHolder certificateHolder, byte[] signedData)
            throws IOException, CMSException, OperatorCreationException {
        CMSSignedDataGenerator generator =
                newFsveritySignedDataGenerator(contentSigner, certificateHolder);
        return generatePkcs7SignatureInternal(generator, signedData, /* encapsulate */ false);
    }

    private static byte[] generatePkcs7SignatureInternal(
            CMSSignedDataGenerator generator, byte[] signedData, boolean encapsulate)
            throws IOException, CMSException, OperatorCreationException {
        CMSSignedData cmsSignedData =
                generator.generate(new CMSProcessableByteArray(signedData), encapsulate);
        return cmsSignedData.toASN1Structure().getEncoded(ASN1Encoding.DL);
    }

    private static CMSSignedDataGenerator newFsveritySignedDataGenerator(
            ContentSigner contentSigner, X509CertificateHolder certificateHolder)
            throws IOException, CMSException, OperatorCreationException {
        var generator = new CMSSignedDataGenerator();
        generator.addSignerInfoGenerator(
                newSignerInfoGenerator(
                        contentSigner,
                        certificateHolder,
                        NISTObjectIdentifiers.id_sha256,
                        true)); // directSignature
        return generator;
    }

    private static SignerInfoGenerator newSignerInfoGenerator(
            ContentSigner contentSigner,
            X509CertificateHolder certificateHolder,
            ASN1ObjectIdentifier digestAlgorithmId,
            boolean directSignature)
            throws IOException, CMSException, OperatorCreationException {
        var provider =
                new BcDigestCalculatorProvider() {
                    /**
                     * Allow the caller to override the digest algorithm, especially when the
                     * default does not work (i.e. BcDigestCalculatorProvider could return null).
                     *
                     * <p>For example, the current fs-verity signature has to use rsaEncryption for
                     * the signature algorithm, but BcDigestCalculatorProvider will return null,
                     * thus we need a way to override.
                     *
                     * <p>TODO: After bouncycastle 1.70, we can remove this override and just use
                     * {@code JcaSignerInfoGeneratorBuilder#setContentDigest}.
                     */
                    @Override
                    public DigestCalculator get(AlgorithmIdentifier algorithm)
                            throws OperatorCreationException {
                        return super.get(new AlgorithmIdentifier(digestAlgorithmId));
                    }
                };
        var builder =
                new JcaSignerInfoGeneratorBuilder(provider).setDirectSignature(directSignature);
        return builder.build(contentSigner, certificateHolder);
    }

    private static ContentSigner newFsverityContentSigner(PrivateKey privateKey)
            throws OperatorCreationException {
        // fs-verity expects the signature to have rsaEncryption as the exact algorithm, so
        // override the default.
        return newFsverityContentSigner(
                privateKey, "SHA256withRSA", PKCSObjectIdentifiers.rsaEncryption);
    }

    private static ContentSigner newFsverityContentSigner(
            PrivateKey privateKey,
            String signatureAlgorithm,
            ASN1ObjectIdentifier signatureAlgorithmIdOverride)
            throws OperatorCreationException {
        if (signatureAlgorithmIdOverride != null) {
            return new ContentSignerWrapper(
                    new JcaContentSignerBuilder(signatureAlgorithm).build(privateKey)) {
                @Override
                public AlgorithmIdentifier getAlgorithmIdentifier() {
                    return new AlgorithmIdentifier(signatureAlgorithmIdOverride);
                }
            };
        } else {
            return new JcaContentSignerBuilder(signatureAlgorithm).build(privateKey);
        }
    }

    private static X509CertificateHolder newX509CertificateHolder(
            ContentSigner contentSigner, PublicKey publicKey, String name) {
        // Time doesn't really matter, as we only care about the key.
        Instant now = Instant.now();

        return new X509v3CertificateBuilder(
                        new X500Name("CN=Issuer " + name),
                        /* serial= */ BigInteger.valueOf(now.getEpochSecond()),
                        new Date(now.minus(Duration.ofDays(1)).toEpochMilli()),
                        new Date(now.plus(Duration.ofDays(1)).toEpochMilli()),
                        new X500Name("CN=Subject " + name),
                        SubjectPublicKeyInfo.getInstance(publicKey.getEncoded()))
                .build(contentSigner);
    }
}
