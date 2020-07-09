/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.util.apk;

import static android.util.apk.ApkSigningBlockUtils.CONTENT_DIGEST_VERITY_CHUNKED_SHA256;
import static android.util.apk.ApkSigningBlockUtils.getSignatureAlgorithmJcaKeyAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.getSignatureAlgorithmJcaSignatureAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.isSupportedSignatureAlgorithm;

import android.os.incremental.IncrementalManager;
import android.os.incremental.V4Signature;
import android.util.ArrayMap;
import android.util.Pair;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Map;

/**
 * APK Signature Scheme v4 verifier.
 *
 * @hide for internal use only.
 */
public class ApkSignatureSchemeV4Verifier {
    /**
     * Extracts and verifies APK Signature Scheme v4 signatures of the provided APK and returns the
     * certificates associated with each signer.
     */
    public static VerifiedSigner extractCertificates(String apkFile)
            throws SignatureNotFoundException, SecurityException {
        final File apk = new File(apkFile);
        final byte[] signatureBytes = IncrementalManager.unsafeGetFileSignature(
                apk.getAbsolutePath());
        if (signatureBytes == null || signatureBytes.length == 0) {
            throw new SignatureNotFoundException("Failed to obtain signature bytes from IncFS.");
        }

        final V4Signature signature;
        final V4Signature.HashingInfo hashingInfo;
        final V4Signature.SigningInfo signingInfo;
        try {
            signature = V4Signature.readFrom(signatureBytes);

            if (!signature.isVersionSupported()) {
                throw new SecurityException(
                        "v4 signature version " + signature.version + " is not supported");
            }

            hashingInfo = V4Signature.HashingInfo.fromByteArray(signature.hashingInfo);
            signingInfo = V4Signature.SigningInfo.fromByteArray(signature.signingInfo);
        } catch (IOException e) {
            throw new SignatureNotFoundException("Failed to read V4 signature.", e);
        }

        // Verify signed data and extract certificates and apk digest.
        final byte[] signedData = V4Signature.getSignedData(apk.length(), hashingInfo,
                signingInfo);
        final Pair<Certificate, byte[]> result = verifySigner(signingInfo, signedData);

        // Populate digests enforced by IncFS driver.
        Map<Integer, byte[]> contentDigests = new ArrayMap<>();
        contentDigests.put(convertToContentDigestType(hashingInfo.hashAlgorithm),
                hashingInfo.rawRootHash);

        return new VerifiedSigner(new Certificate[]{result.first}, result.second, contentDigests);
    }

    private static Pair<Certificate, byte[]> verifySigner(V4Signature.SigningInfo signingInfo,
            final byte[] signedData) throws SecurityException {
        if (!isSupportedSignatureAlgorithm(signingInfo.signatureAlgorithmId)) {
            throw new SecurityException("No supported signatures found");
        }

        final int signatureAlgorithmId = signingInfo.signatureAlgorithmId;
        final byte[] signatureBytes = signingInfo.signature;
        final byte[] publicKeyBytes = signingInfo.publicKey;
        final byte[] encodedCert = signingInfo.certificate;

        String keyAlgorithm = getSignatureAlgorithmJcaKeyAlgorithm(signatureAlgorithmId);
        Pair<String, ? extends AlgorithmParameterSpec> signatureAlgorithmParams =
                getSignatureAlgorithmJcaSignatureAlgorithm(signatureAlgorithmId);
        String jcaSignatureAlgorithm = signatureAlgorithmParams.first;
        AlgorithmParameterSpec jcaSignatureAlgorithmParams = signatureAlgorithmParams.second;
        boolean sigVerified;
        try {
            PublicKey publicKey =
                    KeyFactory.getInstance(keyAlgorithm)
                            .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            Signature sig = Signature.getInstance(jcaSignatureAlgorithm);
            sig.initVerify(publicKey);
            if (jcaSignatureAlgorithmParams != null) {
                sig.setParameter(jcaSignatureAlgorithmParams);
            }
            sig.update(signedData);
            sigVerified = sig.verify(signatureBytes);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | InvalidKeyException
                | InvalidAlgorithmParameterException | SignatureException e) {
            throw new SecurityException(
                    "Failed to verify " + jcaSignatureAlgorithm + " signature", e);
        }
        if (!sigVerified) {
            throw new SecurityException(jcaSignatureAlgorithm + " signature did not verify");
        }

        // Signature over signedData has verified.
        CertificateFactory certFactory;
        try {
            certFactory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new RuntimeException("Failed to obtain X.509 CertificateFactory", e);
        }

        X509Certificate certificate;
        try {
            certificate = (X509Certificate)
                    certFactory.generateCertificate(new ByteArrayInputStream(encodedCert));
        } catch (CertificateException e) {
            throw new SecurityException("Failed to decode certificate", e);
        }
        certificate = new VerbatimX509Certificate(certificate, encodedCert);

        byte[] certificatePublicKeyBytes = certificate.getPublicKey().getEncoded();
        if (!Arrays.equals(publicKeyBytes, certificatePublicKeyBytes)) {
            throw new SecurityException(
                    "Public key mismatch between certificate and signature record");
        }

        return Pair.create(certificate, signingInfo.apkDigest);
    }

    private static int convertToContentDigestType(int hashAlgorithm) throws SecurityException {
        if (hashAlgorithm == V4Signature.HASHING_ALGORITHM_SHA256) {
            return CONTENT_DIGEST_VERITY_CHUNKED_SHA256;
        }
        throw new SecurityException("Unsupported hashAlgorithm: " + hashAlgorithm);
    }

    /**
     * Verified APK Signature Scheme v4 signer, including V2/V3 digest.
     *
     * @hide for internal use only.
     */
    public static class VerifiedSigner {
        public final Certificate[] certs;
        public final byte[] apkDigest;

        // Algorithm -> digest map of signed digests in the signature.
        // These are continuously enforced by the IncFS driver.
        public final Map<Integer, byte[]> contentDigests;

        public VerifiedSigner(Certificate[] certs, byte[] apkDigest,
                Map<Integer, byte[]> contentDigests) {
            this.certs = certs;
            this.apkDigest = apkDigest;
            this.contentDigests = contentDigests;
        }

    }
}
