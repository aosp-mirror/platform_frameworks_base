/*
 * Copyright (C) 2017 The Android Open Source Project
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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

/**
 * ApkVerityBuilder builds the APK verity tree and the verity header, which will be used by the
 * kernel to verity the APK content on access.
 *
 * <p>Unlike a regular Merkle tree, APK verity tree does not cover the content fully. Due to
 * the existing APK format, it has to skip APK Signing Block and also has some special treatment for
 * the "Central Directory offset" field of ZIP End of Central Directory.
 *
 * @hide
 */
abstract class ApkVerityBuilder {
    private ApkVerityBuilder() {}

    private static final int CHUNK_SIZE_BYTES = 4096;  // Typical Linux block size
    private static final int DIGEST_SIZE_BYTES = 32;  // SHA-256 size
    private static final int FSVERITY_HEADER_SIZE_BYTES = 64;
    private static final int ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_SIZE = 4;
    private static final int ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET = 16;
    private static final String JCA_DIGEST_ALGORITHM = "SHA-256";
    private static final byte[] DEFAULT_SALT = new byte[8];

    static class ApkVerityResult {
        public final ByteBuffer fsverityData;
        public final byte[] rootHash;

        ApkVerityResult(ByteBuffer fsverityData, byte[] rootHash) {
            this.fsverityData = fsverityData;
            this.rootHash = rootHash;
        }
    }

    /**
     * Generates fsverity metadata and the Merkle tree into the {@link ByteBuffer} created by the
     * {@link ByteBufferFactory}. The bytes layout in the buffer will be used by the kernel and is
     * ready to be appended to the target file to set up fsverity. For fsverity to work, this data
     * must be placed at the next page boundary, and the caller must add additional padding in that
     * case.
     *
     * @return ApkVerityResult containing the fsverity data and the root hash of the Merkle tree.
     */
    static ApkVerityResult generateApkVerity(RandomAccessFile apk,
            SignatureInfo signatureInfo, ByteBufferFactory bufferFactory)
            throws IOException, SecurityException, NoSuchAlgorithmException, DigestException {
        long signingBlockSize =
                signatureInfo.centralDirOffset - signatureInfo.apkSigningBlockOffset;
        long dataSize = apk.length() - signingBlockSize;
        int[] levelOffset = calculateVerityLevelOffset(dataSize);
        int merkleTreeSize = levelOffset[levelOffset.length - 1];

        ByteBuffer output = bufferFactory.create(
                merkleTreeSize
                + CHUNK_SIZE_BYTES);  // maximum size of fsverity metadata
        output.order(ByteOrder.LITTLE_ENDIAN);

        ByteBuffer tree = slice(output, 0, merkleTreeSize);
        ByteBuffer header = slice(output, merkleTreeSize,
                merkleTreeSize + FSVERITY_HEADER_SIZE_BYTES);
        ByteBuffer extensions = slice(output, merkleTreeSize + FSVERITY_HEADER_SIZE_BYTES,
                merkleTreeSize + CHUNK_SIZE_BYTES);
        byte[] apkDigestBytes = new byte[DIGEST_SIZE_BYTES];
        ByteBuffer apkDigest = ByteBuffer.wrap(apkDigestBytes);
        apkDigest.order(ByteOrder.LITTLE_ENDIAN);

        // NB: Buffer limit is set inside once finished.
        calculateFsveritySignatureInternal(apk, signatureInfo, tree, apkDigest, header, extensions);

        // Put the reverse offset to fs-verity header at the end.
        output.position(merkleTreeSize + FSVERITY_HEADER_SIZE_BYTES + extensions.limit());
        output.putInt(FSVERITY_HEADER_SIZE_BYTES + extensions.limit()
                + 4);  // size of this integer right before EOF
        output.flip();

        return new ApkVerityResult(output, apkDigestBytes);
    }

    /**
     * Calculates the fsverity root hash for integrity measurement.  This needs to be consistent to
     * what kernel returns.
     */
    static byte[] generateFsverityRootHash(RandomAccessFile apk, ByteBuffer apkDigest,
            SignatureInfo signatureInfo)
            throws NoSuchAlgorithmException, DigestException, IOException {
        ByteBuffer verityBlock = ByteBuffer.allocate(CHUNK_SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer header = slice(verityBlock, 0, FSVERITY_HEADER_SIZE_BYTES);
        ByteBuffer extensions = slice(verityBlock, FSVERITY_HEADER_SIZE_BYTES,
                CHUNK_SIZE_BYTES - FSVERITY_HEADER_SIZE_BYTES);

        calculateFsveritySignatureInternal(apk, signatureInfo, null, null, header, extensions);

        MessageDigest md = MessageDigest.getInstance(JCA_DIGEST_ALGORITHM);
        md.update(header);
        md.update(extensions);
        md.update(apkDigest);
        return md.digest();
    }

    /**
     * Internal method to generate various parts of FSVerity constructs, including the header,
     * extensions, Merkle tree, and the tree's root hash.  The output buffer is flipped to the
     * generated data size and is readey for consuming.
     */
    private static void calculateFsveritySignatureInternal(
            RandomAccessFile apk, SignatureInfo signatureInfo, ByteBuffer treeOutput,
            ByteBuffer rootHashOutput, ByteBuffer headerOutput, ByteBuffer extensionsOutput)
            throws IOException, NoSuchAlgorithmException, DigestException {
        assertSigningBlockAlignedAndHasFullPages(signatureInfo);
        long signingBlockSize =
                signatureInfo.centralDirOffset - signatureInfo.apkSigningBlockOffset;
        long dataSize = apk.length() - signingBlockSize;
        int[] levelOffset = calculateVerityLevelOffset(dataSize);

        if (treeOutput != null) {
            byte[] apkRootHash = generateApkVerityTree(apk, signatureInfo, DEFAULT_SALT,
                    levelOffset, treeOutput);
            if (rootHashOutput != null) {
                rootHashOutput.put(apkRootHash);
                rootHashOutput.flip();
            }
        }

        if (headerOutput != null) {
            headerOutput.order(ByteOrder.LITTLE_ENDIAN);
            generateFsverityHeader(headerOutput, apk.length(), levelOffset.length - 1,
                    DEFAULT_SALT);
        }

        if (extensionsOutput != null) {
            extensionsOutput.order(ByteOrder.LITTLE_ENDIAN);
            generateFsverityExtensions(extensionsOutput, signatureInfo.apkSigningBlockOffset,
                    signingBlockSize, signatureInfo.eocdOffset);
        }
    }

    /**
     * A helper class to consume and digest data by block continuously, and write into a buffer.
     */
    private static class BufferedDigester implements DataDigester {
        /** Amount of the data to digest in each cycle before writting out the digest. */
        private static final int BUFFER_SIZE = CHUNK_SIZE_BYTES;

        /**
         * Amount of data the {@link MessageDigest} has consumed since the last reset. This must be
         * always less than BUFFER_SIZE since {@link MessageDigest} is reset whenever it has
         * consumed BUFFER_SIZE of data.
         */
        private int mBytesDigestedSinceReset;

        /** The final output {@link ByteBuffer} to write the digest to sequentially. */
        private final ByteBuffer mOutput;

        private final MessageDigest mMd;
        private final byte[] mDigestBuffer = new byte[DIGEST_SIZE_BYTES];
        private final byte[] mSalt;

        private BufferedDigester(byte[] salt, ByteBuffer output) throws NoSuchAlgorithmException {
            mSalt = salt;
            mOutput = output.slice();
            mMd = MessageDigest.getInstance(JCA_DIGEST_ALGORITHM);
            mMd.update(mSalt);
            mBytesDigestedSinceReset = 0;
        }

        /**
         * Consumes and digests data up to BUFFER_SIZE (may continue from the previous remaining),
         * then writes the final digest to the output buffer.  Repeat until all data are consumed.
         * If the last consumption is not enough for BUFFER_SIZE, the state will stay and future
         * consumption will continuous from there.
         */
        @Override
        public void consume(ByteBuffer buffer) throws DigestException {
            int offset = buffer.position();
            int remaining = buffer.remaining();
            while (remaining > 0) {
                int allowance = (int) Math.min(remaining, BUFFER_SIZE - mBytesDigestedSinceReset);
                // Optimization: set the buffer limit to avoid allocating a new ByteBuffer object.
                buffer.limit(buffer.position() + allowance);
                mMd.update(buffer);
                offset += allowance;
                remaining -= allowance;
                mBytesDigestedSinceReset += allowance;

                if (mBytesDigestedSinceReset == BUFFER_SIZE) {
                    mMd.digest(mDigestBuffer, 0, mDigestBuffer.length);
                    mOutput.put(mDigestBuffer);
                    // After digest, MessageDigest resets automatically, so no need to reset again.
                    mMd.update(mSalt);
                    mBytesDigestedSinceReset = 0;
                }
            }
        }

        public void assertEmptyBuffer() throws DigestException {
            if (mBytesDigestedSinceReset != 0) {
                throw new IllegalStateException("Buffer is not empty: " + mBytesDigestedSinceReset);
            }
        }

        private void fillUpLastOutputChunk() {
            int lastBlockSize = (int) (mOutput.position() % BUFFER_SIZE);
            if (lastBlockSize == 0) {
                return;
            }
            mOutput.put(ByteBuffer.allocate(BUFFER_SIZE - lastBlockSize));
        }
    }

    /**
     * Digest the source by chunk in the given range.  If the last chunk is not a full chunk,
     * digest the remaining.
     */
    private static void consumeByChunk(DataDigester digester, DataSource source, int chunkSize)
            throws IOException, DigestException {
        long inputRemaining = source.size();
        long inputOffset = 0;
        while (inputRemaining > 0) {
            int size = (int) Math.min(inputRemaining, chunkSize);
            source.feedIntoDataDigester(digester, inputOffset, size);
            inputOffset += size;
            inputRemaining -= size;
        }
    }

    // Rationale: 1) 1 MB should fit in memory space on all devices. 2) It is not too granular
    // thus the syscall overhead is not too big.
    private static final int MMAP_REGION_SIZE_BYTES = 1024 * 1024;

    private static void generateApkVerityDigestAtLeafLevel(RandomAccessFile apk,
            SignatureInfo signatureInfo, byte[] salt, ByteBuffer output)
            throws IOException, NoSuchAlgorithmException, DigestException {
        BufferedDigester digester = new BufferedDigester(salt, output);

        // 1. Digest from the beginning of the file, until APK Signing Block is reached.
        consumeByChunk(digester,
                new MemoryMappedFileDataSource(apk.getFD(), 0, signatureInfo.apkSigningBlockOffset),
                MMAP_REGION_SIZE_BYTES);

        // 2. Skip APK Signing Block and continue digesting, until the Central Directory offset
        // field in EoCD is reached.
        long eocdCdOffsetFieldPosition =
                signatureInfo.eocdOffset + ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET;
        consumeByChunk(digester,
                new MemoryMappedFileDataSource(apk.getFD(), signatureInfo.centralDirOffset,
                    eocdCdOffsetFieldPosition - signatureInfo.centralDirOffset),
                MMAP_REGION_SIZE_BYTES);

        // 3. Consume offset of Signing Block as an alternative EoCD.
        ByteBuffer alternativeCentralDirOffset = ByteBuffer.allocate(
                ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        alternativeCentralDirOffset.putInt(Math.toIntExact(signatureInfo.apkSigningBlockOffset));
        alternativeCentralDirOffset.flip();
        digester.consume(alternativeCentralDirOffset);

        // 4. Read from end of the Central Directory offset field in EoCD to the end of the file.
        long offsetAfterEocdCdOffsetField =
                eocdCdOffsetFieldPosition + ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_SIZE;
        consumeByChunk(digester,
                new MemoryMappedFileDataSource(apk.getFD(), offsetAfterEocdCdOffsetField,
                    apk.length() - offsetAfterEocdCdOffsetField),
                MMAP_REGION_SIZE_BYTES);

        // 5. Pad 0s up to the nearest 4096-byte block before hashing.
        int lastIncompleteChunkSize = (int) (apk.length() % CHUNK_SIZE_BYTES);
        if (lastIncompleteChunkSize != 0) {
            digester.consume(ByteBuffer.allocate(CHUNK_SIZE_BYTES - lastIncompleteChunkSize));
        }
        digester.assertEmptyBuffer();

        // 6. Fill up the rest of buffer with 0s.
        digester.fillUpLastOutputChunk();
    }

    private static byte[] generateApkVerityTree(RandomAccessFile apk, SignatureInfo signatureInfo,
            byte[] salt, int[] levelOffset, ByteBuffer output)
            throws IOException, NoSuchAlgorithmException, DigestException {
        // 1. Digest the apk to generate the leaf level hashes.
        generateApkVerityDigestAtLeafLevel(apk, signatureInfo, salt, slice(output,
                    levelOffset[levelOffset.length - 2], levelOffset[levelOffset.length - 1]));

        // 2. Digest the lower level hashes bottom up.
        for (int level = levelOffset.length - 3; level >= 0; level--) {
            ByteBuffer inputBuffer = slice(output, levelOffset[level + 1], levelOffset[level + 2]);
            ByteBuffer outputBuffer = slice(output, levelOffset[level], levelOffset[level + 1]);

            DataSource source = new ByteBufferDataSource(inputBuffer);
            BufferedDigester digester = new BufferedDigester(salt, outputBuffer);
            consumeByChunk(digester, source, CHUNK_SIZE_BYTES);
            digester.assertEmptyBuffer();
            digester.fillUpLastOutputChunk();
        }

        // 3. Digest the first block (i.e. first level) to generate the root hash.
        byte[] rootHash = new byte[DIGEST_SIZE_BYTES];
        BufferedDigester digester = new BufferedDigester(salt, ByteBuffer.wrap(rootHash));
        digester.consume(slice(output, 0, CHUNK_SIZE_BYTES));
        digester.assertEmptyBuffer();
        return rootHash;
    }

    private static ByteBuffer generateFsverityHeader(ByteBuffer buffer, long fileSize, int depth,
            byte[] salt) {
        if (salt.length != 8) {
            throw new IllegalArgumentException("salt is not 8 bytes long");
        }

        // TODO(b/30972906): update the reference when there is a better one in public.
        buffer.put("TrueBrew".getBytes());  // magic

        buffer.put((byte) 1);               // major version
        buffer.put((byte) 0);               // minor version
        buffer.put((byte) 12);              // log2(block-size): log2(4096)
        buffer.put((byte) 7);               // log2(leaves-per-node): log2(4096 / 32)

        buffer.putShort((short) 1);         // meta algorithm, SHA256 == 1
        buffer.putShort((short) 1);         // data algorithm, SHA256 == 1

        buffer.putInt(0);                   // flags
        buffer.putInt(0);                   // reserved

        buffer.putLong(fileSize);           // original file size

        buffer.put((byte) 2);               // authenticated extension count
        buffer.put((byte) 0);               // unauthenticated extension count
        buffer.put(salt);                   // salt (8 bytes)
        skip(buffer, 22);                   // reserved

        buffer.flip();
        return buffer;
    }

    private static ByteBuffer generateFsverityExtensions(ByteBuffer buffer, long signingBlockOffset,
            long signingBlockSize, long eocdOffset) {
        // Snapshot of the FSVerity structs (subject to change once upstreamed).
        //
        // struct fsverity_extension_elide {
        //   __le64 offset;
        //   __le64 length;
        // }
        //
        // struct fsverity_extension_patch {
        //   __le64 offset;
        //   u8 databytes[];
        // };

        final int kSizeOfFsverityExtensionHeader = 8;
        final int kExtensionSizeAlignment = 8;

        {
            // struct fsverity_extension #1
            final int kSizeOfFsverityElidedExtension = 16;

            // First field is total size of extension, padded to 64-bit alignment
            buffer.putInt(kSizeOfFsverityExtensionHeader + kSizeOfFsverityElidedExtension);
            buffer.putShort((short) 1);  // ID of elide extension
            skip(buffer, 2);             // reserved

            // struct fsverity_extension_elide
            buffer.putLong(signingBlockOffset);
            buffer.putLong(signingBlockSize);
        }

        {
            // struct fsverity_extension #2
            final int kTotalSize = kSizeOfFsverityExtensionHeader
                    + 8 // offset size
                    + ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_SIZE;

            buffer.putInt(kTotalSize);   // Total size of extension, padded to 64-bit alignment
            buffer.putShort((short) 2);  // ID of patch extension
            skip(buffer, 2);             // reserved

            // struct fsverity_extension_patch
            buffer.putLong(eocdOffset + ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET);  // offset
            buffer.putInt(Math.toIntExact(signingBlockOffset));  // databytes

            // The extension needs to be 0-padded at the end, since the length may not be multiple
            // of 8.
            int kPadding = kExtensionSizeAlignment - kTotalSize % kExtensionSizeAlignment;
            if (kPadding == kExtensionSizeAlignment) {
                kPadding = 0;
            }
            skip(buffer, kPadding);      // padding
        }

        buffer.flip();
        return buffer;
    }

    /**
     * Returns an array of summed area table of level size in the verity tree.  In other words, the
     * returned array is offset of each level in the verity tree file format, plus an additional
     * offset of the next non-existing level (i.e. end of the last level + 1).  Thus the array size
     * is level + 1.  Thus, the returned array is guarantee to have at least 2 elements.
     */
    private static int[] calculateVerityLevelOffset(long fileSize) {
        ArrayList<Long> levelSize = new ArrayList<>();
        while (true) {
            long levelDigestSize = divideRoundup(fileSize, CHUNK_SIZE_BYTES) * DIGEST_SIZE_BYTES;
            long chunksSize = CHUNK_SIZE_BYTES * divideRoundup(levelDigestSize, CHUNK_SIZE_BYTES);
            levelSize.add(chunksSize);
            if (levelDigestSize <= CHUNK_SIZE_BYTES) {
                break;
            }
            fileSize = levelDigestSize;
        }

        // Reverse and convert to summed area table.
        int[] levelOffset = new int[levelSize.size() + 1];
        levelOffset[0] = 0;
        for (int i = 0; i < levelSize.size(); i++) {
            // We don't support verity tree if it is larger then Integer.MAX_VALUE.
            levelOffset[i + 1] = levelOffset[i]
                    + Math.toIntExact(levelSize.get(levelSize.size() - i - 1));
        }
        return levelOffset;
    }

    private static void assertSigningBlockAlignedAndHasFullPages(SignatureInfo signatureInfo) {
        if (signatureInfo.apkSigningBlockOffset % CHUNK_SIZE_BYTES != 0) {
            throw new IllegalArgumentException(
                    "APK Signing Block does not start at the page  boundary: "
                    + signatureInfo.apkSigningBlockOffset);
        }

        if ((signatureInfo.centralDirOffset - signatureInfo.apkSigningBlockOffset)
                % CHUNK_SIZE_BYTES != 0) {
            throw new IllegalArgumentException(
                    "Size of APK Signing Block is not a multiple of 4096: "
                    + (signatureInfo.centralDirOffset - signatureInfo.apkSigningBlockOffset));
        }
    }

    /** Returns a slice of the buffer which shares content with the provided buffer. */
    private static ByteBuffer slice(ByteBuffer buffer, int begin, int end) {
        ByteBuffer b = buffer.duplicate();
        b.position(0);  // to ensure position <= limit invariant.
        b.limit(end);
        b.position(begin);
        return b.slice();
    }

    /** Skip the {@code ByteBuffer} position by {@code bytes}. */
    private static void skip(ByteBuffer buffer, int bytes) {
        buffer.position(buffer.position() + bytes);
    }

    /** Divides a number and round up to the closest integer. */
    private static long divideRoundup(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
