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

import static android.util.apk.ApkSigningBlockUtils.compareSignatureAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.getLengthPrefixedSlice;
import static android.util.apk.ApkSigningBlockUtils.getSignatureAlgorithmContentDigestAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.getSignatureAlgorithmJcaSignatureAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.isSupportedSignatureAlgorithm;
import static android.util.apk.ApkSigningBlockUtils.readLengthPrefixedByteArray;

import android.util.Pair;
import android.util.Slog;
import android.util.jar.StrictJarFile;

import libcore.io.IoUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
    private static final int SOURCE_STAMP_BLOCK_ID = 0x2b09189e;

    /** Name of the SourceStamp certificate hash ZIP entry in APKs. */
    private static final String SOURCE_STAMP_CERTIFICATE_HASH_ZIP_ENTRY_NAME = "stamp-cert-sha256";

    /** Hidden constructor to prevent instantiation. */
    private SourceStampVerifier() {}

    /** Verifies SourceStamp present in a list of APKs. */
    public static SourceStampVerificationResult verify(List<String> apkFiles) {
        Certificate stampCertificate = null;
        for (String apkFile : apkFiles) {
            SourceStampVerificationResult sourceStampVerificationResult = verify(apkFile);
            if (!sourceStampVerificationResult.isPresent()
                    || !sourceStampVerificationResult.isVerified()) {
                return sourceStampVerificationResult;
            }
            if (stampCertificate != null
                    && !stampCertificate.equals(sourceStampVerificationResult.getCertificate())) {
                return SourceStampVerificationResult.notVerified();
            }
            stampCertificate = sourceStampVerificationResult.getCertificate();
        }
        return SourceStampVerificationResult.verified(stampCertificate);
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
            return verify(apk, sourceStampCertificateDigest);
        } catch (IOException e) {
            // Any exception in reading the APK returns a non-present SourceStamp outcome
            // without affecting the outcome of any of the other signature schemes.
            return SourceStampVerificationResult.notPresent();
        } finally {
            closeApkJar(apkJar);
        }
    }

    private static SourceStampVerificationResult verify(
            RandomAccessFile apk, byte[] sourceStampCertificateDigest) {
        try {
            SignatureInfo signatureInfo =
                    ApkSigningBlockUtils.findSignature(apk, SOURCE_STAMP_BLOCK_ID);
            Map<Integer, byte[]> apkContentDigests = getApkContentDigests(apk);
            return verify(signatureInfo, apkContentDigests, sourceStampCertificateDigest);
        } catch (IOException | SignatureNotFoundException e) {
            return SourceStampVerificationResult.notVerified();
        }
    }

    private static SourceStampVerificationResult verify(
            SignatureInfo signatureInfo,
            Map<Integer, byte[]> apkContentDigests,
            byte[] sourceStampCertificateDigest)
            throws SecurityException, IOException {
        CertificateFactory certFactory;
        try {
            certFactory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new RuntimeException("Failed to obtain X.509 CertificateFactory", e);
        }

        List<Pair<Integer, byte[]>> digests =
                apkContentDigests.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> Pair.create(e.getKey(), e.getValue()))
                        .collect(Collectors.toList());
        byte[] digestBytes = encodeApkContentDigests(digests);

        ByteBuffer sourceStampBlock = signatureInfo.signatureBlock;
        ByteBuffer sourceStampBlockData =
                ApkSigningBlockUtils.getLengthPrefixedSlice(sourceStampBlock);

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
        sourceStampCertificate =
                new VerbatimX509Certificate(sourceStampCertificate, sourceStampEncodedCertificate);

        // Verify the SourceStamp certificate found in the signing block is the same as the
        // SourceStamp certificate found in the APK.
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(sourceStampEncodedCertificate);
            byte[] sourceStampBlockCertificateDigest = messageDigest.digest();
            if (!Arrays.equals(sourceStampCertificateDigest, sourceStampBlockCertificateDigest)) {
                throw new SecurityException("Certificate mismatch between APK and signature block");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new SecurityException("Failed to find SHA-256", e);
        }

        // Parse the signatures block and identify supported signatures
        ByteBuffer signatures = ApkSigningBlockUtils.getLengthPrefixedSlice(sourceStampBlockData);
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
            sig.update(digestBytes);
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

        return SourceStampVerificationResult.verified(sourceStampCertificate);
    }

    private static Map<Integer, byte[]> getApkContentDigests(RandomAccessFile apk)
            throws IOException, SignatureNotFoundException {
        // Retrieve APK content digests in V3 signing block. If a V3 signature is not found, the APK
        // content digests would be re-tried from V2 signature.
        try {
            SignatureInfo v3SignatureInfo =
                    ApkSigningBlockUtils.findSignature(apk, APK_SIGNATURE_SCHEME_V3_BLOCK_ID);
            return getApkContentDigestsFromSignatureBlock(v3SignatureInfo.signatureBlock);
        } catch (SignatureNotFoundException e) {
            // It's fine not to find a V3 signature.
        }

        // Retrieve APK content digests in V2 signing block. If a V2 signature is not found, the
        // process of retrieving APK content digests stops, and the stamp is considered un-verified.
        SignatureInfo v2SignatureInfo =
                ApkSigningBlockUtils.findSignature(apk, APK_SIGNATURE_SCHEME_V2_BLOCK_ID);
        return getApkContentDigestsFromSignatureBlock(v2SignatureInfo.signatureBlock);
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

    private static byte[] getSourceStampCertificateDigest(StrictJarFile apkJar) throws IOException {
        InputStream inputStream = null;
        try {
            ZipEntry zipEntry = apkJar.findEntry(SOURCE_STAMP_CERTIFICATE_HASH_ZIP_ENTRY_NAME);
            if (zipEntry == null) {
                // SourceStamp certificate hash file not found, which means that there is not
                // SourceStamp present.
                return null;
            }
            inputStream = apkJar.getInputStream(zipEntry);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

            // Trying to read the certificate digest, which should be less than 1024 bytes.
            byte[] buffer = new byte[1024];
            int count = inputStream.read(buffer, 0, buffer.length);
            byteArrayOutputStream.write(buffer, 0, count);

            return byteArrayOutputStream.toByteArray();
        } finally {
            IoUtils.closeQuietly(inputStream);
        }
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
