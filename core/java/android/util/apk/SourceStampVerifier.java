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

import static android.util.apk.ApkSigningBlockUtils.CONTENT_DIGEST_SHA256;
import static android.util.apk.ApkSigningBlockUtils.compareSignatureAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.getLengthPrefixedSlice;
import static android.util.apk.ApkSigningBlockUtils.getSignatureAlgorithmContentDigestAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.getSignatureAlgorithmJcaSignatureAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.isSupportedSignatureAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.readLengthPrefixedByteArray;
import static android.util.apk.ApkSigningBlockUtils.verifyProofOfRotationStruct;

import android.util.Pair;
import android.util.Slog;
import android.util.jar.StrictJarFile;

import libcore.io.Streams;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Source Stamp verifier.
 *
 * <p>SourceStamp improves traceability of apps with respect to unauthorized distribution.
 *
 * <p>The stamp is part of the APK that is protected by the signing block.
 *
 * <p>The APK contents hash is signed using the stamp key, and is saved as part of the signing
 * block.
 *
 * @hide for internal use only.
 */
public abstract class SourceStampVerifier {

    private static final String TAG = "SourceStampVerifier";

    private static final int APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871a;
    private static final int APK_SIGNATURE_SCHEME_V3_BLOCK_ID = 0xf05368c0;
    private static final int SOURCE_STAMP_BLOCK_ID = 0x6dff800d;
    private static final int PROOF_OF_ROTATION_ATTR_ID = 0x9d6303f7;

    private static final int VERSION_JAR_SIGNATURE_SCHEME = 1;
    private static final int VERSION_APK_SIGNATURE_SCHEME_V2 = 2;
    private static final int VERSION_APK_SIGNATURE_SCHEME_V3 = 3;

    /** Name of the SourceStamp certificate hash ZIP entry in APKs. */
    private static final String SOURCE_STAMP_CERTIFICATE_HASH_ZIP_ENTRY_NAME = "stamp-cert-sha256";

    /** Hidden constructor to prevent instantiation. */
    private SourceStampVerifier() {
    }

    /** Verifies SourceStamp present in a list of (split) APKs for the same app. */
    public static SourceStampVerificationResult verify(List<String> apkFiles) {
        Certificate stampCertificate = null;
        List<? extends Certificate> stampCertificateLineage = Collections.emptyList();
        for (String apkFile : apkFiles) {
            SourceStampVerificationResult sourceStampVerificationResult = verify(apkFile);
            if (!sourceStampVerificationResult.isPresent()
                    || !sourceStampVerificationResult.isVerified()) {
                return sourceStampVerificationResult;
            }
            if (stampCertificate != null
                    && (!stampCertificate.equals(sourceStampVerificationResult.getCertificate())
                    || !stampCertificateLineage.equals(
                            sourceStampVerificationResult.getCertificateLineage()))) {
                return SourceStampVerificationResult.notVerified();
            }
            stampCertificate = sourceStampVerificationResult.getCertificate();
            stampCertificateLineage = sourceStampVerificationResult.getCertificateLineage();
        }
        return SourceStampVerificationResult.verified(stampCertificate, stampCertificateLineage);
    }

    /** Verifies SourceStamp present in the provided APK. */
    public static SourceStampVerificationResult verify(String apkFile) {
        StrictJarFile apkJar = null;
        try (RandomAccessFile apk = new RandomAccessFile(apkFile, "r")) {
            apkJar =
                    new StrictJarFile(
                            apkFile,
                            /* verify= */ false,
                            /* signatureSchemeRollbackProtectionsEnforced= */ false);
            byte[] sourceStampCertificateDigest = getSourceStampCertificateDigest(apkJar);
            if (sourceStampCertificateDigest == null) {
                // SourceStamp certificate hash file not found, which means that there is not
                // SourceStamp present.
                return SourceStampVerificationResult.notPresent();
            }
            byte[] manifestBytes = getManifestBytes(apkJar);
            return verify(apk, sourceStampCertificateDigest, manifestBytes);
        } catch (IOException e) {
            // Any exception in reading the APK returns a non-present SourceStamp outcome
            // without affecting the outcome of any of the other signature schemes.
            return SourceStampVerificationResult.notPresent();
        } finally {
            closeApkJar(apkJar);
        }
    }

    private static SourceStampVerificationResult verify(
            RandomAccessFile apk, byte[] sourceStampCertificateDigest, byte[] manifestBytes) {
        SignatureInfo signatureInfo;
        try {
            signatureInfo =
                    ApkSigningBlockUtils.findSignature(apk, SOURCE_STAMP_BLOCK_ID);
        } catch (IOException | SignatureNotFoundException | RuntimeException e) {
            return SourceStampVerificationResult.notPresent();
        }
        try {
            Map<Integer, Map<Integer, byte[]>> signatureSchemeApkContentDigests =
                    getSignatureSchemeApkContentDigests(apk, manifestBytes);
            return verify(
                    signatureInfo,
                    getSignatureSchemeDigests(signatureSchemeApkContentDigests),
                    sourceStampCertificateDigest);
        } catch (IOException | RuntimeException e) {
            return SourceStampVerificationResult.notVerified();
        }
    }

    private static SourceStampVerificationResult verify(
            SignatureInfo signatureInfo,
            Map<Integer, byte[]> signatureSchemeDigests,
            byte[] sourceStampCertificateDigest)
            throws SecurityException, IOException {
        ByteBuffer sourceStampBlock = signatureInfo.signatureBlock;
        ByteBuffer sourceStampBlockData =
                ApkSigningBlockUtils.getLengthPrefixedSlice(sourceStampBlock);

        X509Certificate sourceStampCertificate =
                verifySourceStampCertificate(sourceStampBlockData, sourceStampCertificateDigest);

        // Parse signed signature schemes block.
        ByteBuffer signedSignatureSchemes =
                ApkSigningBlockUtils.getLengthPrefixedSlice(sourceStampBlockData);
        Map<Integer, ByteBuffer> signedSignatureSchemeData = new HashMap<>();
        while (signedSignatureSchemes.hasRemaining()) {
            ByteBuffer signedSignatureScheme =
                    ApkSigningBlockUtils.getLengthPrefixedSlice(signedSignatureSchemes);
            int signatureSchemeId = signedSignatureScheme.getInt();
            signedSignatureSchemeData.put(signatureSchemeId, signedSignatureScheme);
        }

        for (Map.Entry<Integer, byte[]> signatureSchemeDigest : signatureSchemeDigests.entrySet()) {
            if (!signedSignatureSchemeData.containsKey(signatureSchemeDigest.getKey())) {
                throw new SecurityException(
                        String.format(
                                "No signatures found for signature scheme %d",
                                signatureSchemeDigest.getKey()));
            }
            ByteBuffer signatures = ApkSigningBlockUtils.getLengthPrefixedSlice(
                    signedSignatureSchemeData.get(signatureSchemeDigest.getKey()));
            verifySourceStampSignature(
                    signatureSchemeDigest.getValue(),
                    sourceStampCertificate,
                    signatures);
        }

        List<? extends Certificate> sourceStampCertificateLineage = Collections.emptyList();
        if (sourceStampBlockData.hasRemaining()) {
            // The stamp block contains some additional attributes.
            ByteBuffer stampAttributeData = getLengthPrefixedSlice(sourceStampBlockData);
            ByteBuffer stampAttributeDataSignatures = getLengthPrefixedSlice(sourceStampBlockData);

            byte[] stampAttributeBytes = new byte[stampAttributeData.remaining()];
            stampAttributeData.get(stampAttributeBytes);
            stampAttributeData.flip();

            verifySourceStampSignature(stampAttributeBytes, sourceStampCertificate,
                    stampAttributeDataSignatures);
            ApkSigningBlockUtils.VerifiedProofOfRotation verifiedProofOfRotation =
                    verifySourceStampAttributes(stampAttributeData, sourceStampCertificate);
            if (verifiedProofOfRotation != null) {
                sourceStampCertificateLineage = verifiedProofOfRotation.certs;
            }
        }

        return SourceStampVerificationResult.verified(sourceStampCertificate,
                sourceStampCertificateLineage);
    }

    /**
     * Verify the SourceStamp certificate found in the signing block is the same as the SourceStamp
     * certificate found in the APK. It returns the verified certificate.
     *
     * @param sourceStampBlockData         the source stamp block in the APK signing block which
     *                                     contains
     *                                     the certificate used to sign the stamp digests.
     * @param sourceStampCertificateDigest the source stamp certificate digest found in the APK.
     */
    private static X509Certificate verifySourceStampCertificate(
            ByteBuffer sourceStampBlockData, byte[] sourceStampCertificateDigest)
            throws IOException {
        CertificateFactory certFactory;
        try {
            certFactory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new RuntimeException("Failed to obtain X.509 CertificateFactory", e);
        }

        // Parse the SourceStamp certificate.
        byte[] sourceStampEncodedCertificate =
                ApkSigningBlockUtils.readLengthPrefixedByteArray(sourceStampBlockData);
        X509Certificate sourceStampCertificate;
        try {
            sourceStampCertificate =
                    (X509Certificate)
                            certFactory.generateCertificate(
                                    new ByteArrayInputStream(sourceStampEncodedCertificate));
        } catch (CertificateException e) {
            throw new SecurityException("Failed to decode certificate", e);
        }

        byte[] sourceStampBlockCertificateDigest =
                computeSha256Digest(sourceStampEncodedCertificate);
        if (!Arrays.equals(sourceStampCertificateDigest, sourceStampBlockCertificateDigest)) {
            throw new SecurityException("Certificate mismatch between APK and signature block");
        }

        return new VerbatimX509Certificate(sourceStampCertificate, sourceStampEncodedCertificate);
    }

    /**
     * Verify the SourceStamp signature found in the signing block is signed by the SourceStamp
     * certificate found in the APK.
     *
     * @param data                   the digest to be verified being signed by the source stamp
     *                               certificate.
     * @param sourceStampCertificate the source stamp certificate used to sign the stamp digests.
     * @param signatures             the source stamp block in the APK signing block which contains
     *                               the stamp signed digests.
     */
    private static void verifySourceStampSignature(byte[] data,
            X509Certificate sourceStampCertificate, ByteBuffer signatures)
            throws IOException {
        // Parse the signatures block and identify supported signatures
        int signatureCount = 0;
        int bestSigAlgorithm = -1;
        byte[] bestSigAlgorithmSignatureBytes = null;
        while (signatures.hasRemaining()) {
            signatureCount++;
            try {
                ByteBuffer signature = getLengthPrefixedSlice(signatures);
                if (signature.remaining() < 8) {
                    throw new SecurityException("Signature record too short");
                }
                int sigAlgorithm = signature.getInt();
                if (!isSupportedSignatureAlgorithm(sigAlgorithm)) {
                    continue;
                }
                if ((bestSigAlgorithm == -1)
                        || (compareSignatureAlgorithm(sigAlgorithm, bestSigAlgorithm) > 0)) {
                    bestSigAlgorithm = sigAlgorithm;
                    bestSigAlgorithmSignatureBytes = readLengthPrefixedByteArray(signature);
                }
            } catch (IOException | BufferUnderflowException e) {
                throw new SecurityException(
                        "Failed to parse signature record #" + signatureCount, e);
            }
        }
        if (bestSigAlgorithm == -1) {
            if (signatureCount == 0) {
                throw new SecurityException("No signatures found");
            } else {
                throw new SecurityException("No supported signatures found");
            }
        }

        // Verify signatures over digests using the SourceStamp's certificate.
        Pair<String, ? extends AlgorithmParameterSpec> signatureAlgorithmParams =
                getSignatureAlgorithmJcaSignatureAlgorithm(bestSigAlgorithm);
        String jcaSignatureAlgorithm = signatureAlgorithmParams.first;
        AlgorithmParameterSpec jcaSignatureAlgorithmParams = signatureAlgorithmParams.second;
        PublicKey publicKey = sourceStampCertificate.getPublicKey();
        boolean sigVerified;
        try {
            Signature sig = Signature.getInstance(jcaSignatureAlgorithm);
            sig.initVerify(publicKey);
            if (jcaSignatureAlgorithmParams != null) {
                sig.setParameter(jcaSignatureAlgorithmParams);
            }
            sig.update(data);
            sigVerified = sig.verify(bestSigAlgorithmSignatureBytes);
        } catch (InvalidKeyException
                | InvalidAlgorithmParameterException
                | SignatureException
                | NoSuchAlgorithmException e) {
            throw new SecurityException(
                    "Failed to verify " + jcaSignatureAlgorithm + " signature", e);
        }
        if (!sigVerified) {
            throw new SecurityException(jcaSignatureAlgorithm + " signature did not verify");
        }
    }

    private static Map<Integer, Map<Integer, byte[]>> getSignatureSchemeApkContentDigests(
            RandomAccessFile apk, byte[] manifestBytes) throws IOException {
        Map<Integer, Map<Integer, byte[]>> signatureSchemeApkContentDigests = new HashMap<>();

        // Retrieve APK content digests in V3 signing block.
        try {
            SignatureInfo v3SignatureInfo =
                    ApkSigningBlockUtils.findSignature(apk, APK_SIGNATURE_SCHEME_V3_BLOCK_ID);
            signatureSchemeApkContentDigests.put(
                    VERSION_APK_SIGNATURE_SCHEME_V3,
                    getApkContentDigestsFromSignatureBlock(v3SignatureInfo.signatureBlock));
        } catch (SignatureNotFoundException e) {
            // It's fine not to find a V3 signature.
        }

        // Retrieve APK content digests in V2 signing block.
        try {
            SignatureInfo v2SignatureInfo =
                    ApkSigningBlockUtils.findSignature(apk, APK_SIGNATURE_SCHEME_V2_BLOCK_ID);
            signatureSchemeApkContentDigests.put(
                    VERSION_APK_SIGNATURE_SCHEME_V2,
                    getApkContentDigestsFromSignatureBlock(v2SignatureInfo.signatureBlock));
        } catch (SignatureNotFoundException e) {
            // It's fine not to find a V2 signature.
        }

        // Retrieve manifest digest.
        if (manifestBytes != null) {
            Map<Integer, byte[]> jarSignatureSchemeApkContentDigests = new HashMap<>();
            jarSignatureSchemeApkContentDigests.put(
                    CONTENT_DIGEST_SHA256, computeSha256Digest(manifestBytes));
            signatureSchemeApkContentDigests.put(
                    VERSION_JAR_SIGNATURE_SCHEME, jarSignatureSchemeApkContentDigests);
        }

        return signatureSchemeApkContentDigests;
    }

    private static Map<Integer, byte[]> getApkContentDigestsFromSignatureBlock(
            ByteBuffer signatureBlock) throws IOException {
        Map<Integer, byte[]> apkContentDigests = new HashMap<>();
        ByteBuffer signers = getLengthPrefixedSlice(signatureBlock);
        while (signers.hasRemaining()) {
            ByteBuffer signer = getLengthPrefixedSlice(signers);
            ByteBuffer signedData = getLengthPrefixedSlice(signer);
            ByteBuffer digests = getLengthPrefixedSlice(signedData);
            while (digests.hasRemaining()) {
                ByteBuffer digest = getLengthPrefixedSlice(digests);
                int sigAlgorithm = digest.getInt();
                byte[] contentDigest = readLengthPrefixedByteArray(digest);
                int digestAlgorithm = getSignatureAlgorithmContentDigestAlgorithm(sigAlgorithm);
                apkContentDigests.put(digestAlgorithm, contentDigest);
            }
        }
        return apkContentDigests;
    }

    private static Map<Integer, byte[]> getSignatureSchemeDigests(
            Map<Integer, Map<Integer, byte[]>> signatureSchemeApkContentDigests) {
        Map<Integer, byte[]> digests = new HashMap<>();
        for (Map.Entry<Integer, Map<Integer, byte[]>> signatureSchemeApkContentDigest :
                signatureSchemeApkContentDigests.entrySet()) {
            List<Pair<Integer, byte[]>> apkDigests =
                    getApkDigests(signatureSchemeApkContentDigest.getValue());
            digests.put(
                    signatureSchemeApkContentDigest.getKey(), encodeApkContentDigests(apkDigests));
        }
        return digests;
    }

    private static List<Pair<Integer, byte[]>> getApkDigests(
            Map<Integer, byte[]> apkContentDigests) {
        List<Pair<Integer, byte[]>> digests = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> apkContentDigest : apkContentDigests.entrySet()) {
            digests.add(Pair.create(apkContentDigest.getKey(), apkContentDigest.getValue()));
        }
        digests.sort(Comparator.comparing(pair -> pair.first));
        return digests;
    }

    private static byte[] getSourceStampCertificateDigest(StrictJarFile apkJar) throws IOException {
        ZipEntry zipEntry = apkJar.findEntry(SOURCE_STAMP_CERTIFICATE_HASH_ZIP_ENTRY_NAME);
        if (zipEntry == null) {
            // SourceStamp certificate hash file not found, which means that there is not
            // SourceStamp present.
            return null;
        }
        return Streams.readFully(apkJar.getInputStream(zipEntry));
    }

    private static byte[] getManifestBytes(StrictJarFile apkJar) throws IOException {
        ZipEntry zipEntry = apkJar.findEntry(JarFile.MANIFEST_NAME);
        if (zipEntry == null) {
            return null;
        }
        return Streams.readFully(apkJar.getInputStream(zipEntry));
    }

    private static byte[] encodeApkContentDigests(List<Pair<Integer, byte[]>> apkContentDigests) {
        int resultSize = 0;
        for (Pair<Integer, byte[]> element : apkContentDigests) {
            resultSize += 12 + element.second.length;
        }
        ByteBuffer result = ByteBuffer.allocate(resultSize);
        result.order(ByteOrder.LITTLE_ENDIAN);
        for (Pair<Integer, byte[]> element : apkContentDigests) {
            byte[] second = element.second;
            result.putInt(8 + second.length);
            result.putInt(element.first);
            result.putInt(second.length);
            result.put(second);
        }
        return result.array();
    }

    private static ApkSigningBlockUtils.VerifiedProofOfRotation verifySourceStampAttributes(
            ByteBuffer stampAttributeData,
            X509Certificate sourceStampCertificate)
            throws IOException {
        CertificateFactory certFactory;
        try {
            certFactory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new RuntimeException("Failed to obtain X.509 CertificateFactory", e);
        }
        ByteBuffer stampAttributes = getLengthPrefixedSlice(stampAttributeData);
        ApkSigningBlockUtils.VerifiedProofOfRotation verifiedProofOfRotation = null;
        while (stampAttributes.hasRemaining()) {
            ByteBuffer attribute = getLengthPrefixedSlice(stampAttributes);
            int id = attribute.getInt();
            if (id == PROOF_OF_ROTATION_ATTR_ID) {
                if (verifiedProofOfRotation != null) {
                    throw new SecurityException("Encountered multiple Proof-of-rotation records"
                            + " when verifying source stamp signature");
                }
                verifiedProofOfRotation = verifyProofOfRotationStruct(attribute, certFactory);
                // Make sure that the last certificate in the Proof-of-rotation record matches
                // the one used to sign this APK.
                try {
                    if (verifiedProofOfRotation.certs.size() > 0
                            && !Arrays.equals(verifiedProofOfRotation.certs.get(
                            verifiedProofOfRotation.certs.size() - 1).getEncoded(),
                            sourceStampCertificate.getEncoded())) {
                        throw new SecurityException("Terminal certificate in Proof-of-rotation"
                                + " record does not match source stamp certificate");
                    }
                } catch (CertificateEncodingException e) {
                    throw new SecurityException("Failed to encode certificate when comparing"
                            + " Proof-of-rotation record and source stamp certificate", e);
                }
            }
        }
        return verifiedProofOfRotation;
    }

    private static byte[] computeSha256Digest(byte[] input) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(input);
            return messageDigest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to find SHA-256", e);
        }
    }

    private static void closeApkJar(StrictJarFile apkJar) {
        try {
            if (apkJar == null) {
                return;
            }
            apkJar.close();
        } catch (IOException e) {
            Slog.e(TAG, "Could not close APK jar", e);
        }
    }
}
