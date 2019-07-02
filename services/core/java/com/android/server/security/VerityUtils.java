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

package com.android.server.security;

import android.annotation.NonNull;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Pair;
import android.util.Slog;
import android.util.apk.ApkSignatureVerifier;
import android.util.apk.ByteBufferFactory;
import android.util.apk.SignatureNotFoundException;
import android.util.apk.VerityBuilder;

import libcore.util.HexEncoding;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import sun.security.pkcs.PKCS7;

/** Provides fsverity related operations. */
abstract public class VerityUtils {
    private static final String TAG = "VerityUtils";

    /**
     * File extension of the signature file. For example, foo.apk.fsv_sig is the signature file of
     * foo.apk.
     */
    public static final String FSVERITY_SIGNATURE_FILE_EXTENSION = ".fsv_sig";

    /** The maximum size of signature file.  This is just to avoid potential abuse. */
    private static final int MAX_SIGNATURE_FILE_SIZE_BYTES = 8192;

    private static final int COMMON_LINUX_PAGE_SIZE_IN_BYTES = 4096;

    private static final boolean DEBUG = false;

    /** Returns true if the given file looks like containing an fs-verity signature. */
    public static boolean isFsveritySignatureFile(File file) {
        return file.getName().endsWith(FSVERITY_SIGNATURE_FILE_EXTENSION);
    }

    /** Returns the fs-verity signature file path of the given file. */
    public static String getFsveritySignatureFilePath(String filePath) {
        return filePath + FSVERITY_SIGNATURE_FILE_EXTENSION;
    }

    /** Generates Merkle tree and fs-verity metadata then enables fs-verity. */
    public static void setUpFsverity(@NonNull String filePath, String signaturePath)
            throws IOException, DigestException, NoSuchAlgorithmException {
        final PKCS7 pkcs7 = new PKCS7(Files.readAllBytes(Paths.get(signaturePath)));
        final byte[] expectedMeasurement = pkcs7.getContentInfo().getContentBytes();
        if (DEBUG) {
            Slog.d(TAG, "Enabling fs-verity with signed fs-verity measurement "
                    + bytesToString(expectedMeasurement));
            Slog.d(TAG, "PKCS#7 info: " + pkcs7);
        }

        final TrackedBufferFactory bufferFactory = new TrackedBufferFactory();
        final byte[] actualMeasurement = generateFsverityMetadata(filePath, signaturePath,
                bufferFactory);
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "rw")) {
            FileChannel ch = raf.getChannel();
            ch.position(roundUpToNextMultiple(ch.size(), COMMON_LINUX_PAGE_SIZE_IN_BYTES));
            ByteBuffer buffer = bufferFactory.getBuffer();

            long offset = buffer.position();
            long size = buffer.limit();
            while (offset < size) {
                long s = ch.write(buffer);
                offset += s;
                size -= s;
            }
        }

        if (!Arrays.equals(expectedMeasurement, actualMeasurement)) {
            throw new SecurityException("fs-verity measurement mismatch: "
                    + bytesToString(actualMeasurement) + " != "
                    + bytesToString(expectedMeasurement));
        }

        // This can fail if the public key is not already in .fs-verity kernel keyring.
        int errno = enableFsverityNative(filePath);
        if (errno != 0) {
            throw new IOException("Failed to enable fs-verity on " + filePath + ": "
                    + Os.strerror(errno));
        }
    }

    /** Returns whether the file has fs-verity enabled. */
    public static boolean hasFsverity(@NonNull String filePath) {
        // NB: only measure but not check the actual measurement here. As long as this succeeds,
        // the file is on readable if the measurement can be verified against a trusted key, and
        // this is good enough for installed apps.
        int errno = measureFsverityNative(filePath);
        if (errno != 0) {
            if (errno != OsConstants.ENODATA) {
                Slog.e(TAG, "Failed to measure fs-verity, errno " + errno + ": " + filePath);
            }
            return false;
        }
        return true;
    }

    /**
     * Generates legacy Merkle tree and fs-verity metadata with Signing Block skipped.
     *
     * @return {@code SetupResult} that contains the result code, and when success, the
     *         {@code FileDescriptor} to read all the data from.
     */
    public static SetupResult generateApkVeritySetupData(@NonNull String apkPath) {
        if (DEBUG) {
            Slog.d(TAG, "Trying to install legacy apk verity to " + apkPath);
        }
        SharedMemory shm = null;
        try {
            final byte[] signedVerityHash = ApkSignatureVerifier.getVerityRootHash(apkPath);
            if (signedVerityHash == null) {
                if (DEBUG) {
                    Slog.d(TAG, "Skip verity tree generation since there is no signed root hash");
                }
                return SetupResult.skipped();
            }

            Pair<SharedMemory, Integer> result =
                    generateFsVerityIntoSharedMemory(apkPath, signedVerityHash);
            shm = result.first;
            int contentSize = result.second;
            FileDescriptor rfd = shm.getFileDescriptor();
            if (rfd == null || !rfd.valid()) {
                return SetupResult.failed();
            }
            return SetupResult.ok(Os.dup(rfd), contentSize);
        } catch (IOException | SecurityException | DigestException | NoSuchAlgorithmException |
                SignatureNotFoundException | ErrnoException e) {
            Slog.e(TAG, "Failed to set up apk verity: ", e);
            return SetupResult.failed();
        } finally {
            if (shm != null) {
                shm.close();
            }
        }
    }

    /**
     * {@see ApkSignatureVerifier#generateApkVerityRootHash(String)}.
     */
    public static byte[] generateApkVerityRootHash(@NonNull String apkPath)
            throws NoSuchAlgorithmException, DigestException, IOException {
        return ApkSignatureVerifier.generateApkVerityRootHash(apkPath);
    }

    /**
     * {@see ApkSignatureVerifier#getVerityRootHash(String)}.
     */
    public static byte[] getVerityRootHash(@NonNull String apkPath)
            throws IOException, SignatureNotFoundException {
        return ApkSignatureVerifier.getVerityRootHash(apkPath);
    }

    /**
     * Generates fs-verity metadata for {@code filePath} in the buffer created by {@code
     * trackedBufferFactory}. The metadata contains the Merkle tree, fs-verity descriptor and
     * extensions, including a PKCS#7 signature provided in {@code signaturePath}.
     *
     * <p>It is worthy to note that {@code trackedBufferFactory} generates a "tracked" {@code
     * ByteBuffer}. The data will be used outside this method via the factory itself.
     *
     * @return fs-verity signed data (struct fsverity_digest_disk) of {@code filePath}, which
     *         includes SHA-256 of fs-verity descriptor and authenticated extensions.
     */
    private static byte[] generateFsverityMetadata(String filePath, String signaturePath,
            @NonNull ByteBufferFactory trackedBufferFactory)
            throws IOException, DigestException, NoSuchAlgorithmException {
        try (RandomAccessFile file = new RandomAccessFile(filePath, "r")) {
            VerityBuilder.VerityResult result = VerityBuilder.generateFsVerityTree(
                    file, trackedBufferFactory);

            ByteBuffer buffer = result.verityData;
            buffer.position(result.merkleTreeSize);

            final byte[] measurement = generateFsverityDescriptorAndMeasurement(file,
                    result.rootHash, signaturePath, buffer);
            buffer.flip();
            return constructFsveritySignedDataNative(measurement);
        }
    }

    /**
     * Generates fs-verity descriptor including the extensions to the {@code output} and returns the
     * fs-verity measurement.
     *
     * @return fs-verity measurement, which is a SHA-256 of fs-verity descriptor and authenticated
     *         extensions.
     */
    private static byte[] generateFsverityDescriptorAndMeasurement(
            @NonNull RandomAccessFile file, @NonNull byte[] rootHash,
            @NonNull String pkcs7SignaturePath, @NonNull ByteBuffer output)
            throws IOException, NoSuchAlgorithmException, DigestException {
        final short kRootHashExtensionId = 1;
        final short kPkcs7SignatureExtensionId = 3;
        final int origPosition = output.position();

        // For generating fs-verity file measurement, which consists of the descriptor and
        // authenticated extensions (but not unauthenticated extensions and the footer).
        MessageDigest md = MessageDigest.getInstance("SHA-256");

        // 1. Generate fs-verity descriptor.
        final byte[] desc = constructFsverityDescriptorNative(file.length());
        output.put(desc);
        md.update(desc);

        // 2. Generate authenticated extensions.
        final byte[] authExt =
                constructFsverityExtensionNative(kRootHashExtensionId, rootHash.length);
        output.put(authExt);
        output.put(rootHash);
        md.update(authExt);
        md.update(rootHash);

        // 3. Generate unauthenticated extensions.
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        output.putShort((short) 1);  // number of unauthenticated extensions below
        output.position(output.position() + 6);

        // Generate PKCS#7 extension. NB: We do not verify agaist trusted certificate (should be
        // done by the caller if needed).
        Path path = Paths.get(pkcs7SignaturePath);
        if (Files.size(path) > MAX_SIGNATURE_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("Signature size is unexpectedly large: "
                    + pkcs7SignaturePath);
        }
        final byte[] pkcs7Signature = Files.readAllBytes(path);
        output.put(constructFsverityExtensionNative(kPkcs7SignatureExtensionId,
                    pkcs7Signature.length));
        output.put(pkcs7Signature);

        // 4. Generate the footer.
        output.put(constructFsverityFooterNative(output.position() - origPosition));

        return md.digest();
    }

    private static native int enableFsverityNative(@NonNull String filePath);
    private static native int measureFsverityNative(@NonNull String filePath);
    private static native byte[] constructFsveritySignedDataNative(@NonNull byte[] measurement);
    private static native byte[] constructFsverityDescriptorNative(long fileSize);
    private static native byte[] constructFsverityExtensionNative(short extensionId,
            int extensionDataSize);
    private static native byte[] constructFsverityFooterNative(int offsetToDescriptorHead);

    /**
     * Returns a pair of {@code SharedMemory} and {@code Integer}. The {@code SharedMemory} contains
     * Merkle tree and fsverity headers for the given apk, in the form that can immediately be used
     * for fsverity setup. The data is aligned to the beginning of {@code SharedMemory}, and has
     * length equals to the returned {@code Integer}.
     */
    private static Pair<SharedMemory, Integer> generateFsVerityIntoSharedMemory(String apkPath,
            @NonNull byte[] expectedRootHash)
            throws IOException, DigestException, NoSuchAlgorithmException,
                   SignatureNotFoundException {
        TrackedShmBufferFactory shmBufferFactory = new TrackedShmBufferFactory();
        byte[] generatedRootHash =
                ApkSignatureVerifier.generateApkVerity(apkPath, shmBufferFactory);
        // We only generate Merkle tree once here, so it's important to make sure the root hash
        // matches the signed one in the apk.
        if (!Arrays.equals(expectedRootHash, generatedRootHash)) {
            throw new SecurityException("verity hash mismatch: "
                    + bytesToString(generatedRootHash) + " != " + bytesToString(expectedRootHash));
        }

        int contentSize = shmBufferFactory.getBufferLimit();
        SharedMemory shm = shmBufferFactory.releaseSharedMemory();
        if (shm == null) {
            throw new IllegalStateException("Failed to generate verity tree into shared memory");
        }
        if (!shm.setProtect(OsConstants.PROT_READ)) {
            throw new SecurityException("Failed to set up shared memory correctly");
        }
        return Pair.create(shm, contentSize);
    }

    private static String bytesToString(byte[] bytes) {
        return HexEncoding.encodeToString(bytes);
    }

    public static class SetupResult {
        /** Result code if verity is set up correctly. */
        private static final int RESULT_OK = 1;

        /** Result code if signature is not provided. */
        private static final int RESULT_SKIPPED = 2;

        /** Result code if the setup failed. */
        private static final int RESULT_FAILED = 3;

        private final int mCode;
        private final FileDescriptor mFileDescriptor;
        private final int mContentSize;

        public static SetupResult ok(@NonNull FileDescriptor fileDescriptor, int contentSize) {
            return new SetupResult(RESULT_OK, fileDescriptor, contentSize);
        }

        public static SetupResult skipped() {
            return new SetupResult(RESULT_SKIPPED, null, -1);
        }

        public static SetupResult failed() {
            return new SetupResult(RESULT_FAILED, null, -1);
        }

        private SetupResult(int code, FileDescriptor fileDescriptor, int contentSize) {
            this.mCode = code;
            this.mFileDescriptor = fileDescriptor;
            this.mContentSize = contentSize;
        }

        public boolean isFailed() {
            return mCode == RESULT_FAILED;
        }

        public boolean isOk() {
            return mCode == RESULT_OK;
        }

        public @NonNull FileDescriptor getUnownedFileDescriptor() {
            return mFileDescriptor;
        }

        public int getContentSize() {
            return mContentSize;
        }
    }

    /** A {@code ByteBufferFactory} that creates a shared memory backed {@code ByteBuffer}. */
    private static class TrackedShmBufferFactory implements ByteBufferFactory {
        private SharedMemory mShm;
        private ByteBuffer mBuffer;

        @Override
        public ByteBuffer create(int capacity) {
            try {
                if (DEBUG) Slog.d(TAG, "Creating shared memory for apk verity");
                // NB: This method is supposed to be called once according to the contract with
                // ApkSignatureSchemeV2Verifier.
                if (mBuffer != null) {
                    throw new IllegalStateException("Multiple instantiation from this factory");
                }
                mShm = SharedMemory.create("apkverity", capacity);
                if (!mShm.setProtect(OsConstants.PROT_READ | OsConstants.PROT_WRITE)) {
                    throw new SecurityException("Failed to set protection");
                }
                mBuffer = mShm.mapReadWrite();
                return mBuffer;
            } catch (ErrnoException e) {
                throw new SecurityException("Failed to set protection", e);
            }
        }

        public SharedMemory releaseSharedMemory() {
            if (mBuffer != null) {
                SharedMemory.unmap(mBuffer);
                mBuffer = null;
            }
            SharedMemory tmp = mShm;
            mShm = null;
            return tmp;
        }

        public int getBufferLimit() {
            return mBuffer == null ? -1 : mBuffer.limit();
        }
    }

    /** A {@code ByteBufferFactory} that tracks the {@code ByteBuffer} it creates. */
    private static class TrackedBufferFactory implements ByteBufferFactory {
        private ByteBuffer mBuffer;

        @Override
        public ByteBuffer create(int capacity) {
            if (mBuffer != null) {
                throw new IllegalStateException("Multiple instantiation from this factory");
            }
            mBuffer = ByteBuffer.allocate(capacity);
            return mBuffer;
        }

        public ByteBuffer getBuffer() {
            return mBuffer;
        }
    }

    /** Round up the number to the next multiple of the divisor. */
    private static long roundUpToNextMultiple(long number, long divisor) {
        if (number > (Long.MAX_VALUE - divisor)) {
            throw new IllegalArgumentException("arithmetic overflow");
        }
        return ((number + (divisor - 1)) / divisor) * divisor;
    }
}
