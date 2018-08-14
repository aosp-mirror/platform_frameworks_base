/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.util.ArrayMap;
import android.util.Pair;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Arrays;
import java.util.Map;

/**
 * Utility class for an APK Signature Scheme using the APK Signing Block.
 *
 * @hide for internal use only.
 */
final class ApkSigningBlockUtils {

    private ApkSigningBlockUtils() {
    }

    /**
     * Returns the APK Signature Scheme block contained in the provided APK file and the
     * additional information relevant for verifying the block against the file.
     *
     * @param blockId the ID value in the APK Signing Block's sequence of ID-value pairs
     *                identifying the appropriate block to find, e.g. the APK Signature Scheme v2
     *                block ID.
     *
     * @throws SignatureNotFoundException if the APK is not signed using this scheme.
     * @throws IOException if an I/O error occurs while reading the APK file.
     */
    static SignatureInfo findSignature(RandomAccessFile apk, int blockId)
            throws IOException, SignatureNotFoundException {
        // Find the ZIP End of Central Directory (EoCD) record.
        Pair<ByteBuffer, Long> eocdAndOffsetInFile = getEocd(apk);
        ByteBuffer eocd = eocdAndOffsetInFile.first;
        long eocdOffset = eocdAndOffsetInFile.second;
        if (ZipUtils.isZip64EndOfCentralDirectoryLocatorPresent(apk, eocdOffset)) {
            throw new SignatureNotFoundException("ZIP64 APK not supported");
        }

        // Find the APK Signing Block. The block immediately precedes the Central Directory.
        long centralDirOffset = getCentralDirOffset(eocd, eocdOffset);
        Pair<ByteBuffer, Long> apkSigningBlockAndOffsetInFile =
                findApkSigningBlock(apk, centralDirOffset);
        ByteBuffer apkSigningBlock = apkSigningBlockAndOffsetInFile.first;
        long apkSigningBlockOffset = apkSigningBlockAndOffsetInFile.second;

        // Find the APK Signature Scheme Block inside the APK Signing Block.
        ByteBuffer apkSignatureSchemeBlock = findApkSignatureSchemeBlock(apkSigningBlock,
                blockId);

        return new SignatureInfo(
                apkSignatureSchemeBlock,
                apkSigningBlockOffset,
                centralDirOffset,
                eocdOffset,
                eocd);
    }

    static void verifyIntegrity(
            Map<Integer, byte[]> expectedDigests,
            RandomAccessFile apk,
            SignatureInfo signatureInfo) throws SecurityException {
        if (expectedDigests.isEmpty()) {
            throw new SecurityException("No digests provided");
        }

        boolean neverVerified = true;

        Map<Integer, byte[]> expected1MbChunkDigests = new ArrayMap<>();
        if (expectedDigests.containsKey(CONTENT_DIGEST_CHUNKED_SHA256)) {
            expected1MbChunkDigests.put(CONTENT_DIGEST_CHUNKED_SHA256,
                    expectedDigests.get(CONTENT_DIGEST_CHUNKED_SHA256));
        }
        if (expectedDigests.containsKey(CONTENT_DIGEST_CHUNKED_SHA512)) {
            expected1MbChunkDigests.put(CONTENT_DIGEST_CHUNKED_SHA512,
                    expectedDigests.get(CONTENT_DIGEST_CHUNKED_SHA512));
        }
        if (!expected1MbChunkDigests.isEmpty()) {
            try {
                verifyIntegrityFor1MbChunkBasedAlgorithm(expected1MbChunkDigests, apk.getFD(),
                        signatureInfo);
                neverVerified = false;
            } catch (IOException e) {
                throw new SecurityException("Cannot get FD", e);
            }
        }

        if (expectedDigests.containsKey(CONTENT_DIGEST_VERITY_CHUNKED_SHA256)) {
            verifyIntegrityForVerityBasedAlgorithm(
                    expectedDigests.get(CONTENT_DIGEST_VERITY_CHUNKED_SHA256), apk, signatureInfo);
            neverVerified = false;
        }

        if (neverVerified) {
            throw new SecurityException("No known digest exists for integrity check");
        }
    }

    private static void verifyIntegrityFor1MbChunkBasedAlgorithm(
            Map<Integer, byte[]> expectedDigests,
            FileDescriptor apkFileDescriptor,
            SignatureInfo signatureInfo) throws SecurityException {
        // We need to verify the integrity of the following three sections of the file:
        // 1. Everything up to the start of the APK Signing Block.
        // 2. ZIP Central Directory.
        // 3. ZIP End of Central Directory (EoCD).
        // Each of these sections is represented as a separate DataSource instance below.

        // To handle large APKs, these sections are read in 1 MB chunks using memory-mapped I/O to
        // avoid wasting physical memory. In most APK verification scenarios, the contents of the
        // APK are already there in the OS's page cache and thus mmap does not use additional
        // physical memory.
        DataSource beforeApkSigningBlock =
                new MemoryMappedFileDataSource(apkFileDescriptor, 0,
                        signatureInfo.apkSigningBlockOffset);
        DataSource centralDir =
                new MemoryMappedFileDataSource(
                        apkFileDescriptor, signatureInfo.centralDirOffset,
                        signatureInfo.eocdOffset - signatureInfo.centralDirOffset);

        // For the purposes of integrity verification, ZIP End of Central Directory's field Start of
        // Central Directory must be considered to point to the offset of the APK Signing Block.
        ByteBuffer eocdBuf = signatureInfo.eocd.duplicate();
        eocdBuf.order(ByteOrder.LITTLE_ENDIAN);
        ZipUtils.setZipEocdCentralDirectoryOffset(eocdBuf, signatureInfo.apkSigningBlockOffset);
        DataSource eocd = new ByteBufferDataSource(eocdBuf);

        int[] digestAlgorithms = new int[expectedDigests.size()];
        int digestAlgorithmCount = 0;
        for (int digestAlgorithm : expectedDigests.keySet()) {
            digestAlgorithms[digestAlgorithmCount] = digestAlgorithm;
            digestAlgorithmCount++;
        }
        byte[][] actualDigests;
        try {
            actualDigests =
                    computeContentDigestsPer1MbChunk(
                            digestAlgorithms,
                            new DataSource[] {beforeApkSigningBlock, centralDir, eocd});
        } catch (DigestException e) {
            throw new SecurityException("Failed to compute digest(s) of contents", e);
        }
        for (int i = 0; i < digestAlgorithms.length; i++) {
            int digestAlgorithm = digestAlgorithms[i];
            byte[] expectedDigest = expectedDigests.get(digestAlgorithm);
            byte[] actualDigest = actualDigests[i];
            if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                throw new SecurityException(
                        getContentDigestAlgorithmJcaDigestAlgorithm(digestAlgorithm)
                                + " digest of contents did not verify");
            }
        }
    }

    private static byte[][] computeContentDigestsPer1MbChunk(
            int[] digestAlgorithms,
            DataSource[] contents) throws DigestException {
        // For each digest algorithm the result is computed as follows:
        // 1. Each segment of contents is split into consecutive chunks of 1 MB in size.
        //    The final chunk will be shorter iff the length of segment is not a multiple of 1 MB.
        //    No chunks are produced for empty (zero length) segments.
        // 2. The digest of each chunk is computed over the concatenation of byte 0xa5, the chunk's
        //    length in bytes (uint32 little-endian) and the chunk's contents.
        // 3. The output digest is computed over the concatenation of the byte 0x5a, the number of
        //    chunks (uint32 little-endian) and the concatenation of digests of chunks of all
        //    segments in-order.

        long totalChunkCountLong = 0;
        for (DataSource input : contents) {
            totalChunkCountLong += getChunkCount(input.size());
        }
        if (totalChunkCountLong >= Integer.MAX_VALUE / 1024) {
            throw new DigestException("Too many chunks: " + totalChunkCountLong);
        }
        int totalChunkCount = (int) totalChunkCountLong;

        byte[][] digestsOfChunks = new byte[digestAlgorithms.length][];
        for (int i = 0; i < digestAlgorithms.length; i++) {
            int digestAlgorithm = digestAlgorithms[i];
            int digestOutputSizeBytes = getContentDigestAlgorithmOutputSizeBytes(digestAlgorithm);
            byte[] concatenationOfChunkCountAndChunkDigests =
                    new byte[5 + totalChunkCount * digestOutputSizeBytes];
            concatenationOfChunkCountAndChunkDigests[0] = 0x5a;
            setUnsignedInt32LittleEndian(
                    totalChunkCount,
                    concatenationOfChunkCountAndChunkDigests,
                    1);
            digestsOfChunks[i] = concatenationOfChunkCountAndChunkDigests;
        }

        byte[] chunkContentPrefix = new byte[5];
        chunkContentPrefix[0] = (byte) 0xa5;
        int chunkIndex = 0;
        MessageDigest[] mds = new MessageDigest[digestAlgorithms.length];
        for (int i = 0; i < digestAlgorithms.length; i++) {
            String jcaAlgorithmName =
                    getContentDigestAlgorithmJcaDigestAlgorithm(digestAlgorithms[i]);
            try {
                mds[i] = MessageDigest.getInstance(jcaAlgorithmName);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(jcaAlgorithmName + " digest not supported", e);
            }
        }
        // TODO: Compute digests of chunks in parallel when beneficial. This requires some research
        // into how to parallelize (if at all) based on the capabilities of the hardware on which
        // this code is running and based on the size of input.
        DataDigester digester = new MultipleDigestDataDigester(mds);
        int dataSourceIndex = 0;
        for (DataSource input : contents) {
            long inputOffset = 0;
            long inputRemaining = input.size();
            while (inputRemaining > 0) {
                int chunkSize = (int) Math.min(inputRemaining, CHUNK_SIZE_BYTES);
                setUnsignedInt32LittleEndian(chunkSize, chunkContentPrefix, 1);
                for (int i = 0; i < mds.length; i++) {
                    mds[i].update(chunkContentPrefix);
                }
                try {
                    input.feedIntoDataDigester(digester, inputOffset, chunkSize);
                } catch (IOException e) {
                    throw new DigestException(
                            "Failed to digest chunk #" + chunkIndex + " of section #"
                                    + dataSourceIndex,
                            e);
                }
                for (int i = 0; i < digestAlgorithms.length; i++) {
                    int digestAlgorithm = digestAlgorithms[i];
                    byte[] concatenationOfChunkCountAndChunkDigests = digestsOfChunks[i];
                    int expectedDigestSizeBytes =
                            getContentDigestAlgorithmOutputSizeBytes(digestAlgorithm);
                    MessageDigest md = mds[i];
                    int actualDigestSizeBytes =
                            md.digest(
                                    concatenationOfChunkCountAndChunkDigests,
                                    5 + chunkIndex * expectedDigestSizeBytes,
                                    expectedDigestSizeBytes);
                    if (actualDigestSizeBytes != expectedDigestSizeBytes) {
                        throw new RuntimeException(
                                "Unexpected output size of " + md.getAlgorithm() + " digest: "
                                        + actualDigestSizeBytes);
                    }
                }
                inputOffset += chunkSize;
                inputRemaining -= chunkSize;
                chunkIndex++;
            }
            dataSourceIndex++;
        }

        byte[][] result = new byte[digestAlgorithms.length][];
        for (int i = 0; i < digestAlgorithms.length; i++) {
            int digestAlgorithm = digestAlgorithms[i];
            byte[] input = digestsOfChunks[i];
            String jcaAlgorithmName = getContentDigestAlgorithmJcaDigestAlgorithm(digestAlgorithm);
            MessageDigest md;
            try {
                md = MessageDigest.getInstance(jcaAlgorithmName);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(jcaAlgorithmName + " digest not supported", e);
            }
            byte[] output = md.digest(input);
            result[i] = output;
        }
        return result;
    }

    /**
     * Return the verity digest only if the length of digest content looks correct.
     * When verity digest is generated, the last incomplete 4k chunk is padded with 0s before
     * hashing. This means two almost identical APKs with different number of 0 at the end will have
     * the same verity digest. To avoid this problem, the length of the source content (excluding
     * Signing Block) is appended to the verity digest, and the digest is returned only if the
     * length is consistent to the current APK.
     */
    static byte[] parseVerityDigestAndVerifySourceLength(
            byte[] data, long fileSize, SignatureInfo signatureInfo) throws SecurityException {
        // FORMAT:
        // OFFSET       DATA TYPE  DESCRIPTION
        // * @+0  bytes uint8[32]  Merkle tree root hash of SHA-256
        // * @+32 bytes int64      Length of source data
        int kRootHashSize = 32;
        int kSourceLengthSize = 8;

        if (data.length != kRootHashSize + kSourceLengthSize) {
            throw new SecurityException("Verity digest size is wrong: " + data.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(kRootHashSize);
        long expectedSourceLength = buffer.getLong();

        long signingBlockSize = signatureInfo.centralDirOffset
                - signatureInfo.apkSigningBlockOffset;
        if (expectedSourceLength != fileSize - signingBlockSize) {
            throw new SecurityException("APK content size did not verify");
        }

        return Arrays.copyOfRange(data, 0, kRootHashSize);
    }

    private static void verifyIntegrityForVerityBasedAlgorithm(
            byte[] expectedDigest,
            RandomAccessFile apk,
            SignatureInfo signatureInfo) throws SecurityException {
        try {
            byte[] expectedRootHash = parseVerityDigestAndVerifySourceLength(expectedDigest,
                    apk.length(), signatureInfo);
            ApkVerityBuilder.ApkVerityResult verity = ApkVerityBuilder.generateApkVerity(apk,
                    signatureInfo, new ByteBufferFactory() {
                        @Override
                        public ByteBuffer create(int capacity) {
                            return ByteBuffer.allocate(capacity);
                        }
                    });
            if (!Arrays.equals(expectedRootHash, verity.rootHash)) {
                throw new SecurityException("APK verity digest of contents did not verify");
            }
        } catch (DigestException | IOException | NoSuchAlgorithmException e) {
            throw new SecurityException("Error during verification", e);
        }
    }

    /**
     * Generates the fsverity header and hash tree to be used by kernel for the given apk. This
     * method does not check whether the root hash exists in the Signing Block or not.
     *
     * <p>The output is stored in the {@link ByteBuffer} created by the given {@link
     * ByteBufferFactory}.
     *
     * @return the root hash of the generated hash tree.
     */
    public static byte[] generateApkVerity(String apkPath, ByteBufferFactory bufferFactory,
            SignatureInfo signatureInfo)
            throws IOException, SignatureNotFoundException, SecurityException, DigestException,
                   NoSuchAlgorithmException {
        try (RandomAccessFile apk = new RandomAccessFile(apkPath, "r")) {
            ApkVerityBuilder.ApkVerityResult result = ApkVerityBuilder.generateApkVerity(apk,
                    signatureInfo, bufferFactory);
            return result.rootHash;
        }
    }

    /**
     * Returns the ZIP End of Central Directory (EoCD) and its offset in the file.
     *
     * @throws IOException if an I/O error occurs while reading the file.
     * @throws SignatureNotFoundException if the EoCD could not be found.
     */
    static Pair<ByteBuffer, Long> getEocd(RandomAccessFile apk)
            throws IOException, SignatureNotFoundException {
        Pair<ByteBuffer, Long> eocdAndOffsetInFile =
                ZipUtils.findZipEndOfCentralDirectoryRecord(apk);
        if (eocdAndOffsetInFile == null) {
            throw new SignatureNotFoundException(
                    "Not an APK file: ZIP End of Central Directory record not found");
        }
        return eocdAndOffsetInFile;
    }

    static long getCentralDirOffset(ByteBuffer eocd, long eocdOffset)
            throws SignatureNotFoundException {
        // Look up the offset of ZIP Central Directory.
        long centralDirOffset = ZipUtils.getZipEocdCentralDirectoryOffset(eocd);
        if (centralDirOffset > eocdOffset) {
            throw new SignatureNotFoundException(
                    "ZIP Central Directory offset out of range: " + centralDirOffset
                    + ". ZIP End of Central Directory offset: " + eocdOffset);
        }
        long centralDirSize = ZipUtils.getZipEocdCentralDirectorySizeBytes(eocd);
        if (centralDirOffset + centralDirSize != eocdOffset) {
            throw new SignatureNotFoundException(
                    "ZIP Central Directory is not immediately followed by End of Central"
                    + " Directory");
        }
        return centralDirOffset;
    }

    private static long getChunkCount(long inputSizeBytes) {
        return (inputSizeBytes + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES;
    }

    private static final int CHUNK_SIZE_BYTES = 1024 * 1024;

    static final int SIGNATURE_RSA_PSS_WITH_SHA256 = 0x0101;
    static final int SIGNATURE_RSA_PSS_WITH_SHA512 = 0x0102;
    static final int SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256 = 0x0103;
    static final int SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512 = 0x0104;
    static final int SIGNATURE_ECDSA_WITH_SHA256 = 0x0201;
    static final int SIGNATURE_ECDSA_WITH_SHA512 = 0x0202;
    static final int SIGNATURE_DSA_WITH_SHA256 = 0x0301;
    static final int SIGNATURE_VERITY_RSA_PKCS1_V1_5_WITH_SHA256 = 0x0421;
    static final int SIGNATURE_VERITY_ECDSA_WITH_SHA256 = 0x0423;
    static final int SIGNATURE_VERITY_DSA_WITH_SHA256 = 0x0425;

    static final int CONTENT_DIGEST_CHUNKED_SHA256 = 1;
    static final int CONTENT_DIGEST_CHUNKED_SHA512 = 2;
    static final int CONTENT_DIGEST_VERITY_CHUNKED_SHA256 = 3;

    static int compareSignatureAlgorithm(int sigAlgorithm1, int sigAlgorithm2) {
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
                    case CONTENT_DIGEST_VERITY_CHUNKED_SHA256:
                        return -1;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown digestAlgorithm2: " + digestAlgorithm2);
                }
            case CONTENT_DIGEST_CHUNKED_SHA512:
                switch (digestAlgorithm2) {
                    case CONTENT_DIGEST_CHUNKED_SHA256:
                    case CONTENT_DIGEST_VERITY_CHUNKED_SHA256:
                        return 1;
                    case CONTENT_DIGEST_CHUNKED_SHA512:
                        return 0;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown digestAlgorithm2: " + digestAlgorithm2);
                }
            case CONTENT_DIGEST_VERITY_CHUNKED_SHA256:
                switch (digestAlgorithm2) {
                    case CONTENT_DIGEST_CHUNKED_SHA512:
                        return -1;
                    case CONTENT_DIGEST_VERITY_CHUNKED_SHA256:
                        return 0;
                    case CONTENT_DIGEST_CHUNKED_SHA256:
                        return 1;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown digestAlgorithm2: " + digestAlgorithm2);
                }
            default:
                throw new IllegalArgumentException("Unknown digestAlgorithm1: " + digestAlgorithm1);
        }
    }

    static int getSignatureAlgorithmContentDigestAlgorithm(int sigAlgorithm) {
        switch (sigAlgorithm) {
            case SIGNATURE_RSA_PSS_WITH_SHA256:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256:
            case SIGNATURE_ECDSA_WITH_SHA256:
            case SIGNATURE_DSA_WITH_SHA256:
                return CONTENT_DIGEST_CHUNKED_SHA256;
            case SIGNATURE_RSA_PSS_WITH_SHA512:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512:
            case SIGNATURE_ECDSA_WITH_SHA512:
                return CONTENT_DIGEST_CHUNKED_SHA512;
            case SIGNATURE_VERITY_RSA_PKCS1_V1_5_WITH_SHA256:
            case SIGNATURE_VERITY_ECDSA_WITH_SHA256:
            case SIGNATURE_VERITY_DSA_WITH_SHA256:
                return CONTENT_DIGEST_VERITY_CHUNKED_SHA256;
            default:
                throw new IllegalArgumentException(
                        "Unknown signature algorithm: 0x"
                                + Long.toHexString(sigAlgorithm & 0xffffffff));
        }
    }

    static String getContentDigestAlgorithmJcaDigestAlgorithm(int digestAlgorithm) {
        switch (digestAlgorithm) {
            case CONTENT_DIGEST_CHUNKED_SHA256:
            case CONTENT_DIGEST_VERITY_CHUNKED_SHA256:
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
            case CONTENT_DIGEST_VERITY_CHUNKED_SHA256:
                return 256 / 8;
            case CONTENT_DIGEST_CHUNKED_SHA512:
                return 512 / 8;
            default:
                throw new IllegalArgumentException(
                        "Unknown content digest algorthm: " + digestAlgorithm);
        }
    }

    static String getSignatureAlgorithmJcaKeyAlgorithm(int sigAlgorithm) {
        switch (sigAlgorithm) {
            case SIGNATURE_RSA_PSS_WITH_SHA256:
            case SIGNATURE_RSA_PSS_WITH_SHA512:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256:
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512:
            case SIGNATURE_VERITY_RSA_PKCS1_V1_5_WITH_SHA256:
                return "RSA";
            case SIGNATURE_ECDSA_WITH_SHA256:
            case SIGNATURE_ECDSA_WITH_SHA512:
            case SIGNATURE_VERITY_ECDSA_WITH_SHA256:
                return "EC";
            case SIGNATURE_DSA_WITH_SHA256:
            case SIGNATURE_VERITY_DSA_WITH_SHA256:
                return "DSA";
            default:
                throw new IllegalArgumentException(
                        "Unknown signature algorithm: 0x"
                                + Long.toHexString(sigAlgorithm & 0xffffffff));
        }
    }

    static Pair<String, ? extends AlgorithmParameterSpec>
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
            case SIGNATURE_VERITY_RSA_PKCS1_V1_5_WITH_SHA256:
                return Pair.create("SHA256withRSA", null);
            case SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA512:
                return Pair.create("SHA512withRSA", null);
            case SIGNATURE_ECDSA_WITH_SHA256:
            case SIGNATURE_VERITY_ECDSA_WITH_SHA256:
                return Pair.create("SHA256withECDSA", null);
            case SIGNATURE_ECDSA_WITH_SHA512:
                return Pair.create("SHA512withECDSA", null);
            case SIGNATURE_DSA_WITH_SHA256:
            case SIGNATURE_VERITY_DSA_WITH_SHA256:
                return Pair.create("SHA256withDSA", null);
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
    static ByteBuffer sliceFromTo(ByteBuffer source, int start, int end) {
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
    static ByteBuffer getByteBuffer(ByteBuffer source, int size)
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

    static ByteBuffer getLengthPrefixedSlice(ByteBuffer source) throws IOException {
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

    static byte[] readLengthPrefixedByteArray(ByteBuffer buf) throws IOException {
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

    static void setUnsignedInt32LittleEndian(int value, byte[] result, int offset) {
        result[offset] = (byte) (value & 0xff);
        result[offset + 1] = (byte) ((value >>> 8) & 0xff);
        result[offset + 2] = (byte) ((value >>> 16) & 0xff);
        result[offset + 3] = (byte) ((value >>> 24) & 0xff);
    }

    private static final long APK_SIG_BLOCK_MAGIC_HI = 0x3234206b636f6c42L;
    private static final long APK_SIG_BLOCK_MAGIC_LO = 0x20676953204b5041L;
    private static final int APK_SIG_BLOCK_MIN_SIZE = 32;

    static Pair<ByteBuffer, Long> findApkSigningBlock(
            RandomAccessFile apk, long centralDirOffset)
                    throws IOException, SignatureNotFoundException {
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
        // Read the magic and offset in file from the footer section of the block:
        // * uint64:   size of block
        // * 16 bytes: magic
        ByteBuffer footer = ByteBuffer.allocate(24);
        footer.order(ByteOrder.LITTLE_ENDIAN);
        apk.seek(centralDirOffset - footer.capacity());
        apk.readFully(footer.array(), footer.arrayOffset(), footer.capacity());
        if ((footer.getLong(8) != APK_SIG_BLOCK_MAGIC_LO)
                || (footer.getLong(16) != APK_SIG_BLOCK_MAGIC_HI)) {
            throw new SignatureNotFoundException(
                    "No APK Signing Block before ZIP Central Directory");
        }
        // Read and compare size fields
        long apkSigBlockSizeInFooter = footer.getLong(0);
        if ((apkSigBlockSizeInFooter < footer.capacity())
                || (apkSigBlockSizeInFooter > Integer.MAX_VALUE - 8)) {
            throw new SignatureNotFoundException(
                    "APK Signing Block size out of range: " + apkSigBlockSizeInFooter);
        }
        int totalSize = (int) (apkSigBlockSizeInFooter + 8);
        long apkSigBlockOffset = centralDirOffset - totalSize;
        if (apkSigBlockOffset < 0) {
            throw new SignatureNotFoundException(
                    "APK Signing Block offset out of range: " + apkSigBlockOffset);
        }
        ByteBuffer apkSigBlock = ByteBuffer.allocate(totalSize);
        apkSigBlock.order(ByteOrder.LITTLE_ENDIAN);
        apk.seek(apkSigBlockOffset);
        apk.readFully(apkSigBlock.array(), apkSigBlock.arrayOffset(), apkSigBlock.capacity());
        long apkSigBlockSizeInHeader = apkSigBlock.getLong(0);
        if (apkSigBlockSizeInHeader != apkSigBlockSizeInFooter) {
            throw new SignatureNotFoundException(
                    "APK Signing Block sizes in header and footer do not match: "
                            + apkSigBlockSizeInHeader + " vs " + apkSigBlockSizeInFooter);
        }
        return Pair.create(apkSigBlock, apkSigBlockOffset);
    }

    static ByteBuffer findApkSignatureSchemeBlock(ByteBuffer apkSigningBlock, int blockId)
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
            if (id == blockId) {
                return getByteBuffer(pairs, len - 4);
            }
            pairs.position(nextEntryPos);
        }

        throw new SignatureNotFoundException(
                "No block with ID " + blockId + " in APK Signing Block.");
    }

    private static void checkByteOrderLittleEndian(ByteBuffer buffer) {
        if (buffer.order() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("ByteBuffer byte order must be little endian");
        }
    }

    /**
     * {@link DataDigester} that updates multiple {@link MessageDigest}s whenever data is fed.
     */
    private static class MultipleDigestDataDigester implements DataDigester {
        private final MessageDigest[] mMds;

        MultipleDigestDataDigester(MessageDigest[] mds) {
            mMds = mds;
        }

        @Override
        public void consume(ByteBuffer buffer) {
            buffer = buffer.slice();
            for (MessageDigest md : mMds) {
                buffer.position(0);
                md.update(buffer);
            }
        }
    }

}
