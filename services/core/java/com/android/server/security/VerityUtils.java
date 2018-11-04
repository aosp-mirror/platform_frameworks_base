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

import static android.system.OsConstants.PROT_READ;
import static android.system.OsConstants.PROT_WRITE;

import android.annotation.NonNull;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Pair;
import android.util.Slog;
import android.util.apk.ApkSignatureVerifier;
import android.util.apk.ByteBufferFactory;
import android.util.apk.SignatureNotFoundException;
import android.util.apk.VerityBuilder;

import libcore.util.HexEncoding;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    /** The maximum size of signature file.  This is just to avoid potential abuse. */
    private static final int MAX_SIGNATURE_FILE_SIZE_BYTES = 8192;

    private static final boolean DEBUG = false;

    /**
     * Generates Merkle tree and fs-verity metadata.
     *
     * @return {@code SetupResult} that contains the result code, and when success, the
     *         {@code FileDescriptor} to read all the data from.
     */
    public static SetupResult generateApkVeritySetupData(@NonNull String apkPath,
            String signaturePath, boolean skipSigningBlock) {
        if (DEBUG) {
            Slog.d(TAG, "Trying to install apk verity to " + apkPath + " with signature file "
                    + signaturePath);
        }
        SharedMemory shm = null;
        try {
            byte[] signedVerityHash;
            if (skipSigningBlock) {
                signedVerityHash = ApkSignatureVerifier.getVerityRootHash(apkPath);
            } else {
                Path path = Paths.get(signaturePath);
                if (Files.exists(path)) {
                    // TODO(112037636): fail early if the signing key is not in .fs-verity keyring.
                    PKCS7 pkcs7 = new PKCS7(Files.readAllBytes(path));
                    signedVerityHash = pkcs7.getContentInfo().getContentBytes();
                    if (DEBUG) {
                        Slog.d(TAG, "fs-verity measurement = " + bytesToString(signedVerityHash));
                    }
                } else {
                    signedVerityHash = null;
                }
            }

            if (signedVerityHash == null) {
                if (DEBUG) {
                    Slog.d(TAG, "Skip verity tree generation since there is no signed root hash");
                }
                return SetupResult.skipped();
            }

            Pair<SharedMemory, Integer> result = generateFsVerityIntoSharedMemory(apkPath,
                    signaturePath, signedVerityHash, skipSigningBlock);
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
            throws IOException, SignatureNotFoundException, SecurityException {
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
            @NonNull TrackedShmBufferFactory trackedBufferFactory)
            throws IOException, SignatureNotFoundException, SecurityException, DigestException,
                   NoSuchAlgorithmException {
        try (RandomAccessFile file = new RandomAccessFile(filePath, "r")) {
            VerityBuilder.VerityResult result = VerityBuilder.generateFsVerityTree(
                    file, trackedBufferFactory);

            ByteBuffer buffer = result.verityData;
            buffer.position(result.merkleTreeSize);

            final byte[] measurement = generateFsverityDescriptorAndMeasurement(file,
                    result.rootHash, signaturePath, buffer);
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
    private static Pair<SharedMemory, Integer> generateFsVerityIntoSharedMemory(
            String apkPath, String signaturePath, @NonNull byte[] expectedRootHash,
            boolean skipSigningBlock)
            throws IOException, SecurityException, DigestException, NoSuchAlgorithmException,
                   SignatureNotFoundException {
        TrackedShmBufferFactory shmBufferFactory = new TrackedShmBufferFactory();
        byte[] generatedRootHash;
        if (skipSigningBlock) {
            generatedRootHash = ApkSignatureVerifier.generateApkVerity(apkPath, shmBufferFactory);
        } else {
            generatedRootHash = generateFsverityMetadata(apkPath, signaturePath, shmBufferFactory);
        }
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
        if (!shm.setProtect(PROT_READ)) {
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
        public ByteBuffer create(int capacity) throws SecurityException {
            try {
                if (DEBUG) Slog.d(TAG, "Creating shared memory for apk verity");
                // NB: This method is supposed to be called once according to the contract with
                // ApkSignatureSchemeV2Verifier.
                if (mBuffer != null) {
                    throw new IllegalStateException("Multiple instantiation from this factory");
                }
                mShm = SharedMemory.create("apkverity", capacity);
                if (!mShm.setProtect(PROT_READ | PROT_WRITE)) {
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
}
