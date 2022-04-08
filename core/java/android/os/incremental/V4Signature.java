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

package android.os.incremental;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * V4 signature fields.
 * Keep in sync with APKSig master copy.
 * @hide
 */
public class V4Signature {
    public static final String EXT = ".idsig";
    public static final int SUPPORTED_VERSION = 2;

    public static final int HASHING_ALGORITHM_SHA256 = 1;
    public static final byte LOG2_BLOCK_SIZE_4096_BYTES = 12;

    /**
     * IncFS hashing data.
     */
    public static class HashingInfo {
        public final int hashAlgorithm; // only 1 == SHA256 supported
        public final byte log2BlockSize; // only 12 (block size 4096) supported now
        @Nullable public final byte[] salt; // used exactly as in fs-verity, 32 bytes max
        @Nullable public final byte[] rawRootHash; // salted digest of the first Merkle tree page

        HashingInfo(int hashAlgorithm, byte log2BlockSize, byte[] salt, byte[] rawRootHash) {
            this.hashAlgorithm = hashAlgorithm;
            this.log2BlockSize = log2BlockSize;
            this.salt = salt;
            this.rawRootHash = rawRootHash;
        }

        /**
         * Constructs HashingInfo from byte array.
         */
        @NonNull
        public static HashingInfo fromByteArray(@NonNull byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            final int hashAlgorithm = buffer.getInt();
            final byte log2BlockSize = buffer.get();
            byte[] salt = readBytes(buffer);
            byte[] rawRootHash = readBytes(buffer);
            return new HashingInfo(hashAlgorithm, log2BlockSize, salt, rawRootHash);
        }
    }

    /**
     * V4 signature data.
     */
    public static class SigningInfo {
        public final byte[] apkDigest;  // used to match with the corresponding APK
        public final byte[] certificate; // ASN.1 DER form
        public final byte[] additionalData; // a free-form binary data blob
        public final byte[] publicKey; // ASN.1 DER, must match the certificate
        public final int signatureAlgorithmId; // see the APK v2 doc for the list
        public final byte[] signature;

        SigningInfo(byte[] apkDigest, byte[] certificate, byte[] additionalData,
                byte[] publicKey, int signatureAlgorithmId, byte[] signature) {
            this.apkDigest = apkDigest;
            this.certificate = certificate;
            this.additionalData = additionalData;
            this.publicKey = publicKey;
            this.signatureAlgorithmId = signatureAlgorithmId;
            this.signature = signature;
        }

        /**
         * Constructs SigningInfo from byte array.
         */
        public static SigningInfo fromByteArray(byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            byte[] apkDigest = readBytes(buffer);
            byte[] certificate = readBytes(buffer);
            byte[] additionalData = readBytes(buffer);
            byte[] publicKey = readBytes(buffer);
            int signatureAlgorithmId = buffer.getInt();
            byte[] signature = readBytes(buffer);
            return new SigningInfo(apkDigest, certificate, additionalData, publicKey,
                    signatureAlgorithmId, signature);
        }
    }

    public final int version; // Always 2 for now.
    /**
     * Raw byte array containing the IncFS hashing data.
     * @see HashingInfo#fromByteArray(byte[])
     */
    @Nullable public final byte[] hashingInfo;

    /**
     * Raw byte array containing the V4 signature data.
     * <p>Passed as-is to the kernel. Can be retrieved later.
     * @see SigningInfo#fromByteArray(byte[])
     */
    @Nullable public final byte[] signingInfo;

    /**
     * Construct a V4Signature from .idsig file.
     */
    public static V4Signature readFrom(ParcelFileDescriptor pfd) throws IOException {
        try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(pfd.dup())) {
            return readFrom(stream);
        }
    }

    /**
     * Construct a V4Signature from a byte array.
     */
    @NonNull
    public static V4Signature readFrom(@NonNull byte[] bytes) throws IOException {
        try (InputStream stream = new ByteArrayInputStream(bytes)) {
            return readFrom(stream);
        }
    }

    /**
     * Store the V4Signature to a byte-array.
     */
    public byte[] toByteArray() {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            this.writeTo(stream);
            return stream.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Combines necessary data to a signed data blob.
     * The blob can be validated against signingInfo.signature.
     *
     * @param fileSize - size of the signed file (APK)
     */
    public static byte[] getSigningData(long fileSize, HashingInfo hashingInfo,
            SigningInfo signingInfo) {
        final int size =
                4/*size*/ + 8/*fileSize*/ + 4/*hash_algorithm*/ + 1/*log2_blocksize*/ + bytesSize(
                        hashingInfo.salt) + bytesSize(hashingInfo.rawRootHash) + bytesSize(
                        signingInfo.apkDigest) + bytesSize(signingInfo.certificate) + bytesSize(
                        signingInfo.additionalData);
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(size);
        buffer.putLong(fileSize);
        buffer.putInt(hashingInfo.hashAlgorithm);
        buffer.put(hashingInfo.log2BlockSize);
        writeBytes(buffer, hashingInfo.salt);
        writeBytes(buffer, hashingInfo.rawRootHash);
        writeBytes(buffer, signingInfo.apkDigest);
        writeBytes(buffer, signingInfo.certificate);
        writeBytes(buffer, signingInfo.additionalData);
        return buffer.array();
    }

    public boolean isVersionSupported() {
        return this.version == SUPPORTED_VERSION;
    }

    private V4Signature(int version, @Nullable byte[] hashingInfo, @Nullable byte[] signingInfo) {
        this.version = version;
        this.hashingInfo = hashingInfo;
        this.signingInfo = signingInfo;
    }

    private static V4Signature readFrom(InputStream stream) throws IOException {
        final int version = readIntLE(stream);
        final byte[] hashingInfo = readBytes(stream);
        final byte[] signingInfo = readBytes(stream);
        return new V4Signature(version, hashingInfo, signingInfo);
    }

    private void writeTo(OutputStream stream) throws IOException {
        writeIntLE(stream, this.version);
        writeBytes(stream, this.hashingInfo);
        writeBytes(stream, this.signingInfo);
    }

    // Utility methods.
    private static int bytesSize(byte[] bytes) {
        return 4/*length*/ + (bytes == null ? 0 : bytes.length);
    }

    private static void readFully(InputStream stream, byte[] buffer) throws IOException {
        int len = buffer.length;
        int n = 0;
        while (n < len) {
            int count = stream.read(buffer, n, len - n);
            if (count < 0) {
                throw new EOFException();
            }
            n += count;
        }
    }

    private static int readIntLE(InputStream stream) throws IOException {
        final byte[] buffer = new byte[4];
        readFully(stream, buffer);
        return ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void writeIntLE(OutputStream stream, int v) throws IOException {
        final byte[] buffer = ByteBuffer.wrap(new byte[4]).order(ByteOrder.LITTLE_ENDIAN).putInt(
                v).array();
        stream.write(buffer);
    }

    private static byte[] readBytes(InputStream stream) throws IOException {
        try {
            final int size = readIntLE(stream);
            final byte[] bytes = new byte[size];
            readFully(stream, bytes);
            return bytes;
        } catch (EOFException ignored) {
            return null;
        }
    }

    private static byte[] readBytes(ByteBuffer buffer) throws IOException {
        if (buffer.remaining() < 4) {
            throw new EOFException();
        }
        final int size = buffer.getInt();
        if (buffer.remaining() < size) {
            throw new EOFException();
        }
        final byte[] bytes = new byte[size];
        buffer.get(bytes);
        return bytes;
    }

    private static void writeBytes(OutputStream stream, byte[] bytes) throws IOException {
        if (bytes == null) {
            writeIntLE(stream, 0);
            return;
        }
        writeIntLE(stream, bytes.length);
        stream.write(bytes);
    }

    private static void writeBytes(ByteBuffer buffer, byte[] bytes) {
        if (bytes == null) {
            buffer.putInt(0);
            return;
        }
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }
}
