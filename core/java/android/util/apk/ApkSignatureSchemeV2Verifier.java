/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.util.Pair;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.security.DigestException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Principal;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * APK Signature Scheme v2 verifier.
 *
 * @hide for internal use only.
 */
public class ApkSignatureSchemeV2Verifier {

    /**
     * {@code .SF} file header section attribute indicating that the APK is signed not just with
     * JAR signature scheme but also with APK Signature Scheme v2 or newer. This attribute
     * facilitates v2 signature stripping detection.
     *
     * <p>The attribute contains a comma-separated set of signature scheme IDs.
     */
    public static final String SF_ATTRIBUTE_ANDROID_APK_SIGNED_NAME = "X-Android-APK-Signed";
    public static final int SF_ATTRIBUTE_ANDROID_APK_SIGNED_ID = 2;

    /**
     * Returns {@code true} if the provided APK contains an APK Signature Scheme V2
     * signature. The signature will not be verified.
     */
    public static boolean hasSignature(String apkFile) throws IOException {
        try (RandomAccessFile apk = new RandomAccessFile(apkFile, "r")) {
            long fileSize = apk.length();
            if (fileSize > Integer.MAX_VALUE) {
                return false;
            }
            MappedByteBuffer apkContents =
                    apk.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            // ZipUtils and APK Signature Scheme v2 verifier expect little-endian byte order.
            apkContents.order(ByteOrder.LITTLE_ENDIAN);

            final int centralDirOffset =
                    (int) getCentralDirOffset(apkContents, getEocdOffset(apkContents));
            // Find the APK Signing Block.
            int apkSigningBlockOffset = findApkSigningBlock(apkContents, centralDirOffset);
            ByteBuffer apkSigningBlock =
                    sliceFromTo(apkContents, apkSigningBlockOffset, centralDirOffset);

            // Find the APK Signature Scheme v2 Block inside the APK Signing Block.
            findApkSignatureSchemeV2Block(apkSigningBlock);
            return true;
        } catch (SignatureNotFoundException e) {
        }
        return false;
    }

    /**
     * Verifies APK Signature Scheme v2 signatures of the provided APK and returns the certificates
     * associated with each signer.
     *
     * @throws SignatureNotFoundException if the APK is not signed using APK Signature Scheme v2.
     * @throws SecurityException if a APK Signature Scheme v2 signature of this APK does not verify.
     * @throws IOException if an I/O error occurs while reading the APK file.
     */
    public static X509Certificate[][] verify(String apkFile)
            throws SignatureNotFoundException, SecurityException, IOException {
        try (RandomAccessFile apk = new RandomAccessFile(apkFile, "r")) {
            return verify(apk);
        }
    }

    /**
     * Verifies APK Signature Scheme v2 signatures of the provided APK and returns the certificates
     * associated with each signer.
     *
     * @throws SignatureNotFoundException if the APK is not signed using APK Signature Scheme v2.
     * @throws SecurityException if a APK Signature Scheme v2 signature of this APK does not verify.
     * @throws IOException if an I/O error occurs while reading the APK file.
     */
    public static X509Certificate[][] verify(RandomAccessFile apk)
            throws SignatureNotFoundException, SecurityException, IOException {

        long fileSize = apk.length();
        if (fileSize > Integer.MAX_VALUE) {
            throw new IOException("File too large: " + apk.length() + " bytes");
        }
        MappedByteBuffer apkContents =
                apk.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
        // Attempt to preload the contents into memory for faster overall verification (v2 and
        // older) at the expense of somewhat increased latency for rejecting malformed APKs.
        apkContents.load();
        return verify(apkContents);
    }

    /**
     * Verifies APK Signature Scheme v2 signatures of the provided APK and returns the certificates
     * associated with each signer.
     *
     * @param apkContents contents of the APK. The contents start at the current position and end
     *        at the limit of the buffer.
     *
     * @throws SignatureNotFoundException if the APK is not signed using APK Signature Scheme v2.
     * @throws SecurityException if a APK Signature Scheme v2 signature of this APK does not verify.
     */
    public static X509Certificate[][] verify(ByteBuffer apkContents)
            throws SignatureNotFoundException, SecurityException {
        // Avoid modifying byte order, position, limit, and mark of the original apkContents.
        apkContents = apkContents.slice();

        // ZipUtils and APK Signature Scheme v2 verifier expect little-endian byte order.
        apkContents.order(ByteOrder.LITTLE_ENDIAN);

        final int eocdOffset = getEocdOffset(apkContents);
        final int centralDirOffset = (int) getCentralDirOffset(apkContents, eocdOffset);

        // Find the APK Signing Block.
        int apkSigningBlockOffset = findApkSigningBlock(apkContents, centralDirOffset);
        ByteBuffer apkSigningBlock =
                sliceFromTo(apkContents, apkSigningBlockOffset, centralDirOffset);

        // Find the APK Signature Scheme v2 Block inside the APK Signing Block.
        ByteBuffer apkSignatureSchemeV2Block = findApkSignatureSchemeV2Block(apkSigningBlock);

        // Verify the contents of the APK outside of the APK Signing Block using the APK Signature
        // Scheme v2 Block.
        return verify(
                apkContents,
                apkSignatureSchemeV2Block,
                apkSigningBlockOffset,
                centralDirOffset,
                eocdOffset);
    }

    /**
     * Verifies the contents outside of the APK Signing Block using the provided APK Signature
     * Scheme v2 Block.
     */
    private static X509Certificate[][] verify(
            ByteBuffer apkContents,
            ByteBuffer v2Block,
            int apkSigningBlockOffset,
            int centralDirOffset,
            int eocdOffset) throws SecurityException {
        int signerCount = 0;
        Map<Integer, byte[]> contentDigests = new HashMap<>();
        List<X509Certificate[]> signerCerts = new ArrayList<>();
        CertificateFactory certFactory;
        try {
            certFactory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new RuntimeException("Failed to obtain X.509 CertificateFactory", e);
        }
        ByteBuffer signers;
        try {
            signers = getLengthPrefixedSlice(v2Block);
        } catch (IOException e) {
            throw new SecurityException("Failed to read list of signers", e);
        }
        while (signers.hasRemaining()) {
            signerCount++;
            try {
                ByteBuffer signer = getLengthPrefixedSlice(signers);
                X509Certificate[] certs = verifySigner(signer, contentDigests, certFactory);
                signerCerts.add(certs);
            } catch (IOException | BufferUnderflowException | SecurityException e) {
                throw new SecurityException(
                        "Failed to parse/verify signer #" + signerCount + " block",
                        e);
            }
        }

        if (signerCount < 1) {
            throw new SecurityException("No signers found");
        }

        if (contentDigests.isEmpty()) {
            throw new SecurityException("No content digests found");
        }

        verifyIntegrity(
                contentDigests,
                apkContents,
                apkSigningBlockOffset,
                centralDirOffset,
                eocdOffset);

        return signerCerts.toArray(new X509Certificate[signerCerts.size()][]);
    }

    private static X509Certificate[] verifySigner(
            ByteBuffer signerBlock,
            Map<Integer, byte[]> contentDigests,
            CertificateFactory certFactory) throws SecurityException, IOException {
        ByteBuffer signedData = getLengthPrefixedSlice(signerBlock);
        ByteBuffer signatures = getLengthPrefixedSlice(signerBlock);
        byte[] publicKeyBytes = readLengthPrefixedByteArray(signerBlock);

        int signatureCount = 0;
        int bestSigAlgorithm = -1;
        byte[] bestSigAlgorithmSignatureBytes = null;
        List<Integer> signaturesSigAlgorithms = new ArrayList<>();
        while (signatures.hasRemaining()) {
            signatureCount++;
            try {
                ByteBuffer signature = getLengthPrefixedSlice(signatures);
                if (signature.remaining() < 8) {
                    throw new SecurityException("Signature record too short");
                }
                int sigAlgorithm = signature.getInt();
                signaturesSigAlgorithms.add(sigAlgorithm);
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
                        "Failed to parse signature record #" + signatureCount,
                        e);
            }
        }
        if (bestSigAlgorithm == -1) {
            if (signatureCount == 0) {
                throw new SecurityException("No signatures found");
            } else {
                throw new SecurityException("No supported signatures found");
            }
        }

        String keyAlgorithm = getSignatureAlgorithmJcaKeyAlgorithm(bestSigAlgorithm);
        Pair<String, ? extends AlgorithmParameterSpec> signatureAlgorithmParams =
                getSignatureAlgorithmJcaSignatureAlgorithm(bestSigAlgorithm);
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
            sigVerified = sig.verify(bestSigAlgorithmSignatureBytes);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | InvalidKeyException
                | InvalidAlgorithmParameterException | SignatureException e) {
            throw new SecurityException(
                    "Failed to verify " + jcaSignatureAlgorithm + " signature", e);
        }
        if (!sigVerified) {
            throw new SecurityException(jcaSignatureAlgorithm + " signature did not verify");
        }

        // Signature over signedData has verified.

        byte[] contentDigest = null;
        signedData.clear();
        ByteBuffer digests = getLengthPrefixedSlice(signedData);
        List<Integer> digestsSigAlgorithms = new ArrayList<>();
        int digestCount = 0;
        while (digests.hasRemaining()) {
            digestCount++;
            try {
                ByteBuffer digest = getLengthPrefixedSlice(digests);
                if (digest.remaining() < 8) {
                    throw new IOException("Record too short");
                }
                int sigAlgorithm = digest.getInt();
                digestsSigAlgorithms.add(sigAlgorithm);
                if (sigAlgorithm == bestSigAlgorithm) {
                    contentDigest = readLengthPrefixedByteArray(digest);
                }
            } catch (IOException | BufferUnderflowException e) {
                throw new IOException("Failed to parse digest record #" + digestCount, e);
            }
        }

        if (!signaturesSigAlgorithms.equals(digestsSigAlgorithms)) {
            throw new SecurityException(
                    "Signature algorithms don't match between digests and signatures records");
        }
        int digestAlgorithm = getSignatureAlgorithmContentDigestAlgorithm(bestSigAlgorithm);
        byte[] previousSignerDigest = contentDigests.put(digestAlgorithm, contentDigest);
        if ((previousSignerDigest != null)
                && (!MessageDigest.isEqual(previousSignerDigest, contentDigest))) {
            throw new SecurityException(
                    getContentDigestAlgorithmJcaDigestAlgorithm(digestAlgorithm)
                    + " contents digest does not match the digest specified by a preceding signer");
        }

        ByteBuffer certificates = getLengthPrefixedSlice(signedData);
        List<X509Certificate> certs = new ArrayList<>();
        int certificateCount = 0;
        while (certificates.hasRemaining()) {
            certificateCount++;
            byte[] encodedCert = readLengthPrefixedByteArray(certificates);
            X509Certificate certificate;
            try {
                certificate = (X509Certificate)
                        certFactory.generateCertificate(new ByteArrayInputStream(encodedCert));
            } catch (CertificateException e) {
                throw new SecurityException("Failed to decode certificate #" + certificateCount, e);
            }
            certificate = new VerbatimX509Certificate(certificate, encodedCert);
            certs.add(certificate);
        }

        if (certs.isEmpty()) {
            throw new SecurityException("No certificates listed");
        }
        X509Certificate mainCertificate = certs.get(0);
        byte[] certificatePublicKeyBytes = mainCertificate.getPublicKey().getEncoded();
        if (!Arrays.equals(publicKeyBytes, certificatePublicKeyBytes)) {
            throw new SecurityException(
                    "Public key mismatch between certificate and signature record");
        }

        return certs.toArray(new X509Certificate[certs.size()]);
    }

    private static void verifyIntegrity(
            Map<Integer, byte[]> expectedDigests,
            ByteBuffer apkContents,
            int apkSigningBlockOffset,
            int centralDirOffset,
            int eocdOffset) throws SecurityException {

        if (expectedDigests.isEmpty()) {
            throw new SecurityException("No digests provided");
        }

        ByteBuffer beforeApkSigningBlock = sliceFromTo(apkContents, 0, apkSigningBlockOffset);
        ByteBuffer centralDir = sliceFromTo(apkContents, centralDirOffset, eocdOffset);
        // For the purposes of integrity verification, ZIP End of Central Directory's field Start of
        // Central Directory must be considered to point to the offset of the APK Signing Block.
        byte[] eocdBytes = new byte[apkContents.capacity() - eocdOffset];
        apkContents.position(eocdOffset);
        apkContents.get(eocdBytes);
        ByteBuffer eocd = ByteBuffer.wrap(eocdBytes);
        eocd.order(apkContents.order());
        ZipUtils.setZipEocdCentralDirectoryOffset(eocd, apkSigningBlockOffset);

        int[] digestAlgorithms = new int[expectedDigests.size()];
        int digestAlgorithmCount = 0;
        for (int digestAlgorithm : expectedDigests.keySet()) {
            digestAlgorithms[digestAlgorithmCount] = digestAlgorithm;
            digestAlgorithmCount++;
        }
        Map<Integer, byte[]> actualDigests;
        try {
            actualDigests =
                    computeContentDigests(
                            digestAlgorithms,
                            new ByteBuffer[] {beforeApkSigningBlock, centralDir, eocd});
        } catch (DigestException e) {
            throw new SecurityException("Failed to compute digest(s) of contents", e);
        }
        for (Map.Entry<Integer, byte[]> entry : expectedDigests.entrySet()) {
            int digestAlgorithm = entry.getKey();
            byte[] expectedDigest = entry.getValue();
            byte[] actualDigest = actualDigests.get(digestAlgorithm);
            if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                throw new SecurityException(
                        getContentDigestAlgorithmJcaDigestAlgorithm(digestAlgorithm)
                        + " digest of contents did not verify");
            }
        }
    }

    private static Map<Integer, byte[]> computeContentDigests(
            int[] digestAlgorithms,
            ByteBuffer[] contents) throws DigestException {
        // For each digest algorithm the result is computed as follows:
        // 1. Each segment of contents is split into consecutive chunks of 1 MB in size.
        //    The final chunk will be shorter iff the length of segment is not a multiple of 1 MB.
        //    No chunks are produced for empty (zero length) segments.
        // 2. The digest of each chunk is computed over the concatenation of byte 0xa5, the chunk's
        //    length in bytes (uint32 little-endian) and the chunk's contents.
        // 3. The output digest is computed over the concatenation of the byte 0x5a, the number of
        //    chunks (uint32 little-endian) and the concatenation of digests of chunks of all
        //    segments in-order.

        int totalChunkCount = 0;
        for (ByteBuffer input : contents) {
            totalChunkCount += getChunkCount(input.remaining());
        }

        Map<Integer, byte[]> digestsOfChunks = new HashMap<>(totalChunkCount);
        for (int digestAlgorithm : digestAlgorithms) {
            int digestOutputSizeBytes = getContentDigestAlgorithmOutputSizeBytes(digestAlgorithm);
            byte[] concatenationOfChunkCountAndChunkDigests =
                    new byte[5 + totalChunkCount * digestOutputSizeBytes];
            concatenationOfChunkCountAndChunkDigests[0] = 0x5a;
            setUnsignedInt32LittleEndian(
                    totalChunkCount,
                    concatenationOfChunkCountAndChunkDigests,
                    1);
            digestsOfChunks.put(digestAlgorithm, concatenationOfChunkCountAndChunkDigests);
        }

        byte[] chunkContentPrefix = new byte[5];
        chunkContentPrefix[0] = (byte) 0xa5;
        int chunkIndex = 0;
        for (ByteBuffer input : contents) {
            while (input.hasRemaining()) {
                int chunkSize = Math.min(input.remaining(), CHUNK_SIZE_BYTES);
                ByteBuffer chunk = getByteBuffer(input, chunkSize);
                for (int digestAlgorithm : digestAlgorithms) {
                    String jcaAlgorithmName =
                            getContentDigestAlgorithmJcaDigestAlgorithm(digestAlgorithm);
                    MessageDigest md;
                    try {
                        md = MessageDigest.getInstance(jcaAlgorithmName);
                    } catch (NoSuchAlgorithmException e) {
                        throw new RuntimeException(jcaAlgorithmName + " digest not supported", e);
                    }
                    chunk.clear();
                    setUnsignedInt32LittleEndian(chunk.remaining(), chunkContentPrefix, 1);
                    md.update(chunkContentPrefix);
                    md.update(chunk);
                    byte[] concatenationOfChunkCountAndChunkDigests =
                            digestsOfChunks.get(digestAlgorithm);
                    int expectedDigestSizeBytes =
                            getContentDigestAlgorithmOutputSizeBytes(digestAlgorithm);
                    int actualDigestSizeBytes = md.digest(concatenationOfChunkCountAndChunkDigests,
                            5 + chunkIndex * expectedDigestSizeBytes, expectedDigestSizeBytes);
                    if (actualDigestSizeBytes != expectedDigestSizeBytes) {
                        throw new RuntimeException(
                                "Unexpected output size of " + md.getAlgorithm() + " digest: "
                                        + actualDigestSizeBytes);
                    }
                }
                chunkIndex++;
            }
        }

        Map<Integer, byte[]> result = new HashMap<>(digestAlgorithms.length);
        for (Map.Entry<Integer, byte[]> entry : digestsOfChunks.entrySet()) {
            int digestAlgorithm = entry.getKey();
            byte[] input = entry.getValue();
            String jcaAlgorithmName = getContentDigestAlgorithmJcaDigestAlgorithm(digestAlgorithm);
            MessageDigest md;
            try {
                md = MessageDigest.getInstance(jcaAlgorithmName);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(jcaAlgorithmName + " digest not supported", e);
            }
            byte[] output = md.digest(input);
            result.put(digestAlgorithm, output);
        }
        return result;
    }

    /**
     * Finds the offset of ZIP End of Central Directory (EoCD).
     *
     * @throws SignatureNotFoundException If the EoCD could not be found
     */
    private static int getEocdOffset(ByteBuffer apkContents) throws SignatureNotFoundException {
        int eocdOffset = ZipUtils.findZipEndOfCentralDirectoryRecord(apkContents);
        if (eocdOffset == -1) {
            throw new SignatureNotFoundException(
                    "Not an APK file: ZIP End of Central Directory record not found");
        }
        return eocdOffset;
    }

    private static long getCentralDirOffset(ByteBuffer apkContents, int eocdOffset)
            throws SignatureNotFoundException {
        if (ZipUtils.isZip64EndOfCentralDirectoryLocatorPresent(apkContents, eocdOffset)) {
            throw new SignatureNotFoundException("ZIP64 APK not supported");
        }
        ByteBuffer eocd = sliceFromTo(apkContents, eocdOffset, apkContents.capacity());

        // Look up the offset of ZIP Central Directory.
        long centralDirOffsetLong = ZipUtils.getZipEocdCentralDirectoryOffset(eocd);
        if (centralDirOffsetLong >= eocdOffset) {
            throw new SignatureNotFoundException(
                    "ZIP Central Directory offset out of range: " + centralDirOffsetLong
                    + ". ZIP End of Central Directory offset: " + eocdOffset);
        }
        long centralDirSizeLong = ZipUtils.getZipEocdCentralDirectorySizeBytes(eocd);
        if (centralDirOffsetLong + centralDirSizeLong != eocdOffset) {
            throw new SignatureNotFoundException(
                    "ZIP Central Directory is not immediately followed by End of Central"
                    + " Directory");
        }
        return centralDirOffsetLong;
    }

    private static final int getChunkCount(int inputSizeBytes) {
        return (inputSizeBytes + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES;
    }

    private static final int CHUNK_SIZE_BYTES = 1024 * 1024;

    private static final int SIGNATURE_RSA_PSS_WITH_SHA256 = 0x0101;
    private static final int SIGNATURE_RSA_PSS_WITH_SHA512 = 0x0102;
    private static final int SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256 = 0x0103;
    private static final int SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512 = 0x0104;
    private static final int SIGNATURE_ECDSA_WITH_SHA256 = 0x0201;
    private static final int SIGNATURE_ECDSA_WITH_SHA512 = 0x0202;
    private static final int SIGNATURE_DSA_WITH_SHA256 = 0x0301;
    private static final int SIGNATURE_DSA_WITH_SHA512 = 0x0302;

    private static final int CONTENT_DIGEST_CHUNKED_SHA256 = 1;
    private static final int CONTENT_DIGEST_CHUNKED_SHA512 = 2;

    private static boolean isSupportedSignatureAlgorithm(int sigAlgorithm) {
        switch (sigAlgorithm) {
            case SIGNATURE_RSA_PSS_WITH_SHA256:
            case SIGNATURE_RSA_PSS_WITH_SHA512:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512:
            case SIGNATURE_ECDSA_WITH_SHA256:
            case SIGNATURE_ECDSA_WITH_SHA512:
            case SIGNATURE_DSA_WITH_SHA256:
            case SIGNATURE_DSA_WITH_SHA512:
                return true;
            default:
                return false;
        }
    }

    private static int compareSignatureAlgorithm(int sigAlgorithm1, int sigAlgorithm2) {
        int digestAlgorithm1 = getSignatureAlgorithmContentDigestAlgorithm(sigAlgorithm1);
        int digestAlgorithm2 = getSignatureAlgorithmContentDigestAlgorithm(sigAlgorithm2);
        return compareContentDigestAlgorithm(digestAlgorithm1, digestAlgorithm2);
    }

    private static int compareContentDigestAlgorithm(int digestAlgorithm1, int digestAlgorithm2) {
        switch (digestAlgorithm1) {
            case CONTENT_DIGEST_CHUNKED_SHA256:
                switch (digestAlgorithm2) {
                    case CONTENT_DIGEST_CHUNKED_SHA256:
                        return 0;
                    case CONTENT_DIGEST_CHUNKED_SHA512:
                        return -1;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown digestAlgorithm2: " + digestAlgorithm2);
                }
            case CONTENT_DIGEST_CHUNKED_SHA512:
                switch (digestAlgorithm2) {
                    case CONTENT_DIGEST_CHUNKED_SHA256:
                        return 1;
                    case CONTENT_DIGEST_CHUNKED_SHA512:
                        return 0;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown digestAlgorithm2: " + digestAlgorithm2);
                }
            default:
                throw new IllegalArgumentException("Unknown digestAlgorithm1: " + digestAlgorithm1);
        }
    }

    private static int getSignatureAlgorithmContentDigestAlgorithm(int sigAlgorithm) {
        switch (sigAlgorithm) {
            case SIGNATURE_RSA_PSS_WITH_SHA256:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256:
            case SIGNATURE_ECDSA_WITH_SHA256:
            case SIGNATURE_DSA_WITH_SHA256:
                return CONTENT_DIGEST_CHUNKED_SHA256;
            case SIGNATURE_RSA_PSS_WITH_SHA512:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512:
            case SIGNATURE_ECDSA_WITH_SHA512:
            case SIGNATURE_DSA_WITH_SHA512:
                return CONTENT_DIGEST_CHUNKED_SHA512;
            default:
                throw new IllegalArgumentException(
                        "Unknown signature algorithm: 0x"
                                + Long.toHexString(sigAlgorithm & 0xffffffff));
        }
    }

    private static String getContentDigestAlgorithmJcaDigestAlgorithm(int digestAlgorithm) {
        switch (digestAlgorithm) {
            case CONTENT_DIGEST_CHUNKED_SHA256:
                return "SHA-256";
            case CONTENT_DIGEST_CHUNKED_SHA512:
                return "SHA-512";
            default:
                throw new IllegalArgumentException(
                        "Unknown content digest algorthm: " + digestAlgorithm);
        }
    }

    private static int getContentDigestAlgorithmOutputSizeBytes(int digestAlgorithm) {
        switch (digestAlgorithm) {
            case CONTENT_DIGEST_CHUNKED_SHA256:
                return 256 / 8;
            case CONTENT_DIGEST_CHUNKED_SHA512:
                return 512 / 8;
            default:
                throw new IllegalArgumentException(
                        "Unknown content digest algorthm: " + digestAlgorithm);
        }
    }

    private static String getSignatureAlgorithmJcaKeyAlgorithm(int sigAlgorithm) {
        switch (sigAlgorithm) {
            case SIGNATURE_RSA_PSS_WITH_SHA256:
            case SIGNATURE_RSA_PSS_WITH_SHA512:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512:
                return "RSA";
            case SIGNATURE_ECDSA_WITH_SHA256:
            case SIGNATURE_ECDSA_WITH_SHA512:
                return "EC";
            case SIGNATURE_DSA_WITH_SHA256:
            case SIGNATURE_DSA_WITH_SHA512:
                return "DSA";
            default:
                throw new IllegalArgumentException(
                        "Unknown signature algorithm: 0x"
                                + Long.toHexString(sigAlgorithm & 0xffffffff));
        }
    }

    private static Pair<String, ? extends AlgorithmParameterSpec>
            getSignatureAlgorithmJcaSignatureAlgorithm(int sigAlgorithm) {
        switch (sigAlgorithm) {
            case SIGNATURE_RSA_PSS_WITH_SHA256:
                return Pair.create(
                        "SHA256withRSA/PSS",
                        new PSSParameterSpec(
                                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 256 / 8, 1));
            case SIGNATURE_RSA_PSS_WITH_SHA512:
                return Pair.create(
                        "SHA512withRSA/PSS",
                        new PSSParameterSpec(
                                "SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 512 / 8, 1));
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256:
                return Pair.create("SHA256withRSA", null);
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512:
                return Pair.create("SHA512withRSA", null);
            case SIGNATURE_ECDSA_WITH_SHA256:
                return Pair.create("SHA256withECDSA", null);
            case SIGNATURE_ECDSA_WITH_SHA512:
                return Pair.create("SHA512withECDSA", null);
            case SIGNATURE_DSA_WITH_SHA256:
                return Pair.create("SHA256withDSA", null);
            case SIGNATURE_DSA_WITH_SHA512:
                return Pair.create("SHA512withDSA", null);
            default:
                throw new IllegalArgumentException(
                        "Unknown signature algorithm: 0x"
                                + Long.toHexString(sigAlgorithm & 0xffffffff));
        }
    }

    /**
     * Returns new byte buffer whose content is a shared subsequence of this buffer's content
     * between the specified start (inclusive) and end (exclusive) positions. As opposed to
     * {@link ByteBuffer#slice()}, the returned buffer's byte order is the same as the source
     * buffer's byte order.
     */
    private static ByteBuffer sliceFromTo(ByteBuffer source, int start, int end) {
        if (start < 0) {
            throw new IllegalArgumentException("start: " + start);
        }
        if (end < start) {
            throw new IllegalArgumentException("end < start: " + end + " < " + start);
        }
        int capacity = source.capacity();
        if (end > source.capacity()) {
            throw new IllegalArgumentException("end > capacity: " + end + " > " + capacity);
        }
        int originalLimit = source.limit();
        int originalPosition = source.position();
        try {
            source.position(0);
            source.limit(end);
            source.position(start);
            ByteBuffer result = source.slice();
            result.order(source.order());
            return result;
        } finally {
            source.position(0);
            source.limit(originalLimit);
            source.position(originalPosition);
        }
    }

    /**
     * Relative <em>get</em> method for reading {@code size} number of bytes from the current
     * position of this buffer.
     *
     * <p>This method reads the next {@code size} bytes at this buffer's current position,
     * returning them as a {@code ByteBuffer} with start set to 0, limit and capacity set to
     * {@code size}, byte order set to this buffer's byte order; and then increments the position by
     * {@code size}.
     */
    private static ByteBuffer getByteBuffer(ByteBuffer source, int size)
            throws BufferUnderflowException {
        if (size < 0) {
            throw new IllegalArgumentException("size: " + size);
        }
        int originalLimit = source.limit();
        int position = source.position();
        int limit = position + size;
        if ((limit < position) || (limit > originalLimit)) {
            throw new BufferUnderflowException();
        }
        source.limit(limit);
        try {
            ByteBuffer result = source.slice();
            result.order(source.order());
            source.position(limit);
            return result;
        } finally {
            source.limit(originalLimit);
        }
    }

    private static ByteBuffer getLengthPrefixedSlice(ByteBuffer source) throws IOException {
        if (source.remaining() < 4) {
            throw new IOException(
                    "Remaining buffer too short to contain length of length-prefixed field."
                            + " Remaining: " + source.remaining());
        }
        int len = source.getInt();
        if (len < 0) {
            throw new IllegalArgumentException("Negative length");
        } else if (len > source.remaining()) {
            throw new IOException("Length-prefixed field longer than remaining buffer."
                    + " Field length: " + len + ", remaining: " + source.remaining());
        }
        return getByteBuffer(source, len);
    }

    private static byte[] readLengthPrefixedByteArray(ByteBuffer buf) throws IOException {
        int len = buf.getInt();
        if (len < 0) {
            throw new IOException("Negative length");
        } else if (len > buf.remaining()) {
            throw new IOException("Underflow while reading length-prefixed value. Length: " + len
                    + ", available: " + buf.remaining());
        }
        byte[] result = new byte[len];
        buf.get(result);
        return result;
    }

    private static void setUnsignedInt32LittleEndian(int value, byte[] result, int offset) {
        result[offset] = (byte) (value & 0xff);
        result[offset + 1] = (byte) ((value >>> 8) & 0xff);
        result[offset + 2] = (byte) ((value >>> 16) & 0xff);
        result[offset + 3] = (byte) ((value >>> 24) & 0xff);
    }

    private static final long APK_SIG_BLOCK_MAGIC_HI = 0x3234206b636f6c42L;
    private static final long APK_SIG_BLOCK_MAGIC_LO = 0x20676953204b5041L;
    private static final int APK_SIG_BLOCK_MIN_SIZE = 32;

    private static final int APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871a;

    private static int findApkSigningBlock(ByteBuffer apkContents, int centralDirOffset)
            throws SignatureNotFoundException {
        checkByteOrderLittleEndian(apkContents);

        // FORMAT:
        // OFFSET       DATA TYPE  DESCRIPTION
        // * @+0  bytes uint64:    size in bytes (excluding this field)
        // * @+8  bytes payload
        // * @-24 bytes uint64:    size in bytes (same as the one above)
        // * @-16 bytes uint128:   magic

        if (centralDirOffset < APK_SIG_BLOCK_MIN_SIZE) {
            throw new SignatureNotFoundException(
                    "APK too small for APK Signing Block. ZIP Central Directory offset: "
                            + centralDirOffset);
        }
        // Check magic field present
        if ((apkContents.getLong(centralDirOffset - 16) != APK_SIG_BLOCK_MAGIC_LO)
                || (apkContents.getLong(centralDirOffset - 8) != APK_SIG_BLOCK_MAGIC_HI)) {
            throw new SignatureNotFoundException(
                    "No APK Signing Block before ZIP Central Directory");
        }
        // Read and compare size fields
        long apkSigBlockSizeLong = apkContents.getLong(centralDirOffset - 24);
        if ((apkSigBlockSizeLong < 24) || (apkSigBlockSizeLong > Integer.MAX_VALUE - 8)) {
            throw new SignatureNotFoundException(
                    "APK Signing Block size out of range: " + apkSigBlockSizeLong);
        }
        int apkSigBlockSizeFromFooter = (int) apkSigBlockSizeLong;
        int totalSize = apkSigBlockSizeFromFooter + 8;
        int apkSigBlockOffset = centralDirOffset - totalSize;
        if (apkSigBlockOffset < 0) {
            throw new SignatureNotFoundException(
                    "APK Signing Block offset out of range: " + apkSigBlockOffset);
        }
        long apkSigBlockSizeFromHeader = apkContents.getLong(apkSigBlockOffset);
        if (apkSigBlockSizeFromHeader != apkSigBlockSizeFromFooter) {
            throw new SignatureNotFoundException(
                    "APK Signing Block sizes in header and footer do not match: "
                            + apkSigBlockSizeFromHeader + " vs " + apkSigBlockSizeFromFooter);
        }
        return apkSigBlockOffset;
    }

    private static ByteBuffer findApkSignatureSchemeV2Block(ByteBuffer apkSigningBlock)
            throws SignatureNotFoundException {
        checkByteOrderLittleEndian(apkSigningBlock);
        // FORMAT:
        // OFFSET       DATA TYPE  DESCRIPTION
        // * @+0  bytes uint64:    size in bytes (excluding this field)
        // * @+8  bytes pairs
        // * @-24 bytes uint64:    size in bytes (same as the one above)
        // * @-16 bytes uint128:   magic
        ByteBuffer pairs = sliceFromTo(apkSigningBlock, 8, apkSigningBlock.capacity() - 24);

        int entryCount = 0;
        while (pairs.hasRemaining()) {
            entryCount++;
            if (pairs.remaining() < 8) {
                throw new SignatureNotFoundException(
                        "Insufficient data to read size of APK Signing Block entry #" + entryCount);
            }
            long lenLong = pairs.getLong();
            if ((lenLong < 4) || (lenLong > Integer.MAX_VALUE)) {
                throw new SignatureNotFoundException(
                        "APK Signing Block entry #" + entryCount
                                + " size out of range: " + lenLong);
            }
            int len = (int) lenLong;
            int nextEntryPos = pairs.position() + len;
            if (len > pairs.remaining()) {
                throw new SignatureNotFoundException(
                        "APK Signing Block entry #" + entryCount + " size out of range: " + len
                                + ", available: " + pairs.remaining());
            }
            int id = pairs.getInt();
            if (id == APK_SIGNATURE_SCHEME_V2_BLOCK_ID) {
                return getByteBuffer(pairs, len - 4);
            }
            pairs.position(nextEntryPos);
        }

        throw new SignatureNotFoundException(
                "No APK Signature Scheme v2 block in APK Signing Block");
    }

    private static void checkByteOrderLittleEndian(ByteBuffer buffer) {
        if (buffer.order() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("ByteBuffer byte order must be little endian");
        }
    }

    public static class SignatureNotFoundException extends Exception {
        public SignatureNotFoundException(String message) {
            super(message);
        }

        public SignatureNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * For legacy reasons we need to return exactly the original encoded certificate bytes, instead
     * of letting the underlying implementation have a shot at re-encoding the data.
     */
    private static class VerbatimX509Certificate extends WrappedX509Certificate {
        private byte[] encodedVerbatim;

        public VerbatimX509Certificate(X509Certificate wrapped, byte[] encodedVerbatim) {
            super(wrapped);
            this.encodedVerbatim = encodedVerbatim;
        }

        @Override
        public byte[] getEncoded() throws CertificateEncodingException {
            return encodedVerbatim;
        }
    }

    private static class WrappedX509Certificate extends X509Certificate {
        private final X509Certificate wrapped;

        public WrappedX509Certificate(X509Certificate wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public Set<String> getCriticalExtensionOIDs() {
            return wrapped.getCriticalExtensionOIDs();
        }

        @Override
        public byte[] getExtensionValue(String oid) {
            return wrapped.getExtensionValue(oid);
        }

        @Override
        public Set<String> getNonCriticalExtensionOIDs() {
            return wrapped.getNonCriticalExtensionOIDs();
        }

        @Override
        public boolean hasUnsupportedCriticalExtension() {
            return wrapped.hasUnsupportedCriticalExtension();
        }

        @Override
        public void checkValidity()
                throws CertificateExpiredException, CertificateNotYetValidException {
            wrapped.checkValidity();
        }

        @Override
        public void checkValidity(Date date)
                throws CertificateExpiredException, CertificateNotYetValidException {
            wrapped.checkValidity(date);
        }

        @Override
        public int getVersion() {
            return wrapped.getVersion();
        }

        @Override
        public BigInteger getSerialNumber() {
            return wrapped.getSerialNumber();
        }

        @Override
        public Principal getIssuerDN() {
            return wrapped.getIssuerDN();
        }

        @Override
        public Principal getSubjectDN() {
            return wrapped.getSubjectDN();
        }

        @Override
        public Date getNotBefore() {
            return wrapped.getNotBefore();
        }

        @Override
        public Date getNotAfter() {
            return wrapped.getNotAfter();
        }

        @Override
        public byte[] getTBSCertificate() throws CertificateEncodingException {
            return wrapped.getTBSCertificate();
        }

        @Override
        public byte[] getSignature() {
            return wrapped.getSignature();
        }

        @Override
        public String getSigAlgName() {
            return wrapped.getSigAlgName();
        }

        @Override
        public String getSigAlgOID() {
            return wrapped.getSigAlgOID();
        }

        @Override
        public byte[] getSigAlgParams() {
            return wrapped.getSigAlgParams();
        }

        @Override
        public boolean[] getIssuerUniqueID() {
            return wrapped.getIssuerUniqueID();
        }

        @Override
        public boolean[] getSubjectUniqueID() {
            return wrapped.getSubjectUniqueID();
        }

        @Override
        public boolean[] getKeyUsage() {
            return wrapped.getKeyUsage();
        }

        @Override
        public int getBasicConstraints() {
            return wrapped.getBasicConstraints();
        }

        @Override
        public byte[] getEncoded() throws CertificateEncodingException {
            return wrapped.getEncoded();
        }

        @Override
        public void verify(PublicKey key) throws CertificateException, NoSuchAlgorithmException,
                InvalidKeyException, NoSuchProviderException, SignatureException {
            wrapped.verify(key);
        }

        @Override
        public void verify(PublicKey key, String sigProvider)
                throws CertificateException, NoSuchAlgorithmException, InvalidKeyException,
                NoSuchProviderException, SignatureException {
            wrapped.verify(key, sigProvider);
        }

        @Override
        public String toString() {
            return wrapped.toString();
        }

        @Override
        public PublicKey getPublicKey() {
            return wrapped.getPublicKey();
        }
    }
}
