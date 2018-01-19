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
        assertSigningBlockAlignedAndHasFullPages(signatureInfo);

        long signingBlockSize =
                signatureInfo.centralDirOffset - signatureInfo.apkSigningBlockOffset;
        long dataSize = apk.length() - signingBlockSize - ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_SIZE;
        int[] levelOffset = calculateVerityLevelOffset(dataSize);
        ByteBuffer output = bufferFactory.create(
                CHUNK_SIZE_BYTES +  // fsverity header + extensions + padding
                levelOffset[levelOffset.length - 1] +  // Merkle tree size
                FSVERITY_HEADER_SIZE_BYTES);  // second fsverity header (verbatim copy)

        // Start generating the tree from the block boundary as the kernel will expect.
        ByteBuffer treeOutput = slice(output, CHUNK_SIZE_BYTES,
                output.limit() - FSVERITY_HEADER_SIZE_BYTES);
        byte[] rootHash = generateApkVerityTree(apk, signatureInfo, DEFAULT_SALT, levelOffset,
                treeOutput);

        ByteBuffer integrityHeader = generateFsverityHeader(apk.length(), DEFAULT_SALT);
        output.put(integrityHeader);
        output.put(generateFsverityExtensions());

        integrityHeader.rewind();
        output.put(integrityHeader);
        output.rewind();
        return new ApkVerityResult(output, rootHash);
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

        /** Finish the current digestion if any. */
        @Override
        public void finish() throws DigestException {
            if (mBytesDigestedSinceReset == 0) {
                return;
            }
            mMd.digest(mDigestBuffer, 0, mDigestBuffer.length);
            mOutput.put(mDigestBuffer);
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

        // 3. Fill up the rest of buffer with 0s.
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
        digester.finish();

        // 5. Fill up the rest of buffer with 0s.
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
            digester.finish();

            digester.fillUpLastOutputChunk();
        }

        // 3. Digest the first block (i.e. first level) to generate the root hash.
        byte[] rootHash = new byte[DIGEST_SIZE_BYTES];
        BufferedDigester digester = new BufferedDigester(salt, ByteBuffer.wrap(rootHash));
        digester.consume(slice(output, 0, CHUNK_SIZE_BYTES));
        digester.finish();
        return rootHash;
    }

    private static ByteBuffer generateFsverityHeader(long fileSize, byte[] salt) {
        if (salt.length != 8) {
            throw new IllegalArgumentException("salt is not 8 bytes long");
        }

        ByteBuffer buffer = ByteBuffer.allocate(FSVERITY_HEADER_SIZE_BYTES);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // TODO(b/30972906): insert a reference when there is a public one.
        buffer.put("TrueBrew".getBytes());  // magic
        buffer.put((byte) 1);        // major version
        buffer.put((byte) 0);        // minor version
        buffer.put((byte) 12);       // log2(block-size) == log2(4096)
        buffer.put((byte) 7);        // log2(leaves-per-node) == log2(block-size / digest-size)
                                     //                       == log2(4096 / 32)
        buffer.putShort((short) 1);  // meta algorithm, 1: SHA-256 FIXME finalize constant
        buffer.putShort((short) 1);  // data algorithm, 1: SHA-256 FIXME finalize constant
        buffer.putInt(0x1);          // flags, 0x1: has extension, FIXME also hide it
        buffer.putInt(0);            // reserved
        buffer.putLong(fileSize);    // original i_size
        buffer.put(salt);            // salt (8 bytes)

        // TODO(b/30972906): Add extension.

        buffer.rewind();
        return buffer;
    }

    private static ByteBuffer generateFsverityExtensions() {
        return ByteBuffer.allocate(64); // TODO(b/30972906): implement this.
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

    /** Divides a number and round up to the closest integer. */
    private static long divideRoundup(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
