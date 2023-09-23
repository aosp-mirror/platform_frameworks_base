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

import static android.util.apk.ApkSignatureSchemeV3Verifier.APK_SIGNATURE_SCHEME_V3_BLOCK_ID;
import static android.util.apk.ApkSigningBlockUtils.CONTENT_DIGEST_VERITY_CHUNKED_SHA256;
import static android.util.apk.ApkSigningBlockUtils.getSignatureAlgorithmJcaKeyAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.getSignatureAlgorithmJcaSignatureAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.isSupportedSignatureAlgorithm;

import android.os.incremental.IncrementalManager;
import android.os.incremental.V4Signature;
import android.util.ArrayMap;
import android.util.Pair;

import com.android.internal.security.VerityUtils;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.DigestException;
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
    static final int APK_SIGNATURE_SCHEME_DEFAULT = 0xffffffff;

    /**
     * Extracts and verifies APK Signature Scheme v4 signature of the provided APK and returns the
     * certificates associated with each signer.
     */
    public static VerifiedSigner extractCertificates(String apkFile)
            throws SignatureNotFoundException, SignatureException, SecurityException {
        Pair<V4Signature.HashingInfo, V4Signature.SigningInfos> pair = extractSignature(apkFile);
        return verify(apkFile, pair.first, pair.second, APK_SIGNATURE_SCHEME_DEFAULT);
    }

    /**
     * Extracts APK Signature Scheme v4 signature of the provided APK.
     */
    public static Pair<V4Signature.HashingInfo, V4Signature.SigningInfos> extractSignature(
            String apkFile) throws SignatureNotFoundException, SignatureException {
        try {
            final File apk = new File(apkFile);
            boolean needsConsistencyCheck;

            // 1. Try IncFS first. IncFS verifies the file according to the integrity metadata
            // (including the root hash of Merkle tree) it keeps track of with signature check. No
            // further consistentcy check is needed.
            byte[] signatureBytes = IncrementalManager.unsafeGetFileSignature(
                    apk.getAbsolutePath());
            V4Signature signature;
            if (signatureBytes != null && signatureBytes.length > 0) {
                needsConsistencyCheck = false;
                signature = V4Signature.readFrom(signatureBytes);
            } else if (android.security.Flags.extendVbChainToUpdatedApk()) {
                // 2. Try fs-verity next. fs-verity checks against the Merkle tree, but the
                // v4 signature file (including a raw root hash) is managed separately. We need to
                // ensure the signed data from the file is consistent with the actual file.
                needsConsistencyCheck = true;

                final File idsig = new File(apk.getAbsolutePath() + V4Signature.EXT);
                try (var fis = new FileInputStream(idsig.getAbsolutePath())) {
                    signature = V4Signature.readFrom(fis);
                } catch (IOException e) {
                    throw new SignatureNotFoundException(
                            "Failed to obtain signature bytes from .idsig");
                }
            } else {
                throw new SignatureNotFoundException(
                        "Failed to obtain signature bytes from IncFS.");
            }
            if (!signature.isVersionSupported()) {
                throw new SecurityException(
                        "v4 signature version " + signature.version + " is not supported");
            }
            final V4Signature.HashingInfo hashingInfo = V4Signature.HashingInfo.fromByteArray(
                    signature.hashingInfo);
            final V4Signature.SigningInfos signingInfos = V4Signature.SigningInfos.fromByteArray(
                    signature.signingInfos);

            if (needsConsistencyCheck) {
                final byte[] actualDigest = VerityUtils.getFsverityDigest(apk.getAbsolutePath());
                if (actualDigest == null) {
                    throw new SecurityException("The APK does not have fs-verity");
                }
                final byte[] computedDigest =
                        VerityUtils.generateFsVerityDigest(apk.length(), hashingInfo);
                if (!Arrays.equals(computedDigest, actualDigest)) {
                    throw new SignatureException("Actual digest does not match the v4 signature");
                }
            }

            return Pair.create(hashingInfo, signingInfos);
        } catch (EOFException e) {
            throw new SignatureException("V4 signature is invalid.", e);
        } catch (IOException e) {
            throw new SignatureNotFoundException("Failed to read V4 signature.", e);
        } catch (DigestException | NoSuchAlgorithmException e) {
            throw new SecurityException("Failed to calculate the digest", e);
        }
    }

    /**
     * Verifies APK Signature Scheme v4 signature and returns the
     * certificates associated with each signer.
     */
    public static VerifiedSigner verify(String apkFile, final V4Signature.HashingInfo hashingInfo,
            final V4Signature.SigningInfos signingInfos, final int v3BlockId)
            throws SignatureNotFoundException, SecurityException {
        final V4Signature.SigningInfo signingInfo = findSigningInfoForBlockId(signingInfos,
                v3BlockId);

        // Verify signed data and extract certificates and apk digest.
        final byte[] signedData = V4Signature.getSignedData(new File(apkFile).length(), hashingInfo,
                signingInfo);
        final Pair<Certificate, byte[]> result = verifySigner(signingInfo, signedData);

        // Populate digests enforced by IncFS driver and fs-verity.
        Map<Integer, byte[]> contentDigests = new ArrayMap<>();
        contentDigests.put(convertToContentDigestType(hashingInfo.hashAlgorithm),
                hashingInfo.rawRootHash);

        return new VerifiedSigner(new Certificate[]{result.first}, result.second, contentDigests);
    }

    private static V4Signature.SigningInfo findSigningInfoForBlockId(
            final V4Signature.SigningInfos signingInfos, final int v3BlockId)
            throws SignatureNotFoundException {
        // Use default signingInfo for v3 block.
        if (v3BlockId == APK_SIGNATURE_SCHEME_DEFAULT
                || v3BlockId == APK_SIGNATURE_SCHEME_V3_BLOCK_ID) {
            return signingInfos.signingInfo;
        }
        for (V4Signature.SigningInfoBlock signingInfoBlock : signingInfos.signingInfoBlocks) {
            if (v3BlockId == signingInfoBlock.blockId) {
                try {
                    return V4Signature.SigningInfo.fromByteArray(signingInfoBlock.signingInfo);
                } catch (IOException e) {
                    throw new SecurityException(
                            "Failed to read V4 signature block: " + signingInfoBlock.blockId, e);
                }
            }
        }
        throw new SecurityException(
                "Failed to find V4 signature block corresponding to V3 blockId: " + v3BlockId);
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
        // These are continuously enforced by the IncFS driver and fs-verity.
        public final Map<Integer, byte[]> contentDigests;

        public VerifiedSigner(Certificate[] certs, byte[] apkDigest,
                Map<Integer, byte[]> contentDigests) {
            this.certs = certs;
            this.apkDigest = apkDigest;
            this.contentDigests = contentDigests;
        }

    }
}
