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

import libcore.util.HexEncoding;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.DigestException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

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

    private static final boolean DEBUG = false;

    /** Returns true if the given file looks like containing an fs-verity signature. */
    public static boolean isFsveritySignatureFile(File file) {
        return file.getName().endsWith(FSVERITY_SIGNATURE_FILE_EXTENSION);
    }

    /** Returns the fs-verity signature file path of the given file. */
    public static String getFsveritySignatureFilePath(String filePath) {
        return filePath + FSVERITY_SIGNATURE_FILE_EXTENSION;
    }

    /** Enables fs-verity for the file with a PKCS#7 detached signature file. */
    public static void setUpFsverity(@NonNull String filePath, @NonNull String signaturePath)
            throws IOException {
        if (Files.size(Paths.get(signaturePath)) > MAX_SIGNATURE_FILE_SIZE_BYTES) {
            throw new SecurityException("Signature file is unexpectedly large: " + signaturePath);
        }
        byte[] pkcs7Signature = Files.readAllBytes(Paths.get(signaturePath));
        // This will fail if the public key is not already in .fs-verity kernel keyring.
        int errno = enableFsverityNative(filePath, pkcs7Signature);
        if (errno != 0) {
            throw new IOException("Failed to enable fs-verity on " + filePath + ": "
                    + Os.strerror(errno));
        }
    }

    /** Returns whether the file has fs-verity enabled. */
    public static boolean hasFsverity(@NonNull String filePath) {
        int retval = statxForFsverityNative(filePath);
        if (retval < 0) {
            Slog.e(TAG, "Failed to check whether fs-verity is enabled, errno " + -retval + ": "
                    + filePath);
            return false;
        }
        return (retval == 1);
    }

    private static native int enableFsverityNative(@NonNull String filePath,
            @NonNull byte[] pkcs7Signature);
    private static native int statxForFsverityNative(@NonNull String filePath);

    /**
     * Generates legacy Merkle tree and fs-verity metadata with Signing Block skipped.
     *
     * @deprecated This is only used for previous fs-verity implementation, and should never be used
     *             on new devices.
     * @return {@code SetupResult} that contains the result code, and when success, the
     *         {@code FileDescriptor} to read all the data from.
     */
    @Deprecated
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
     * @deprecated This is only used for previous fs-verity implementation, and should never be used
     *             on new devices.
     */
    @Deprecated
    public static byte[] generateApkVerityRootHash(@NonNull String apkPath)
            throws NoSuchAlgorithmException, DigestException, IOException {
        return ApkSignatureVerifier.generateApkVerityRootHash(apkPath);
    }

    /**
     * {@see ApkSignatureVerifier#getVerityRootHash(String)}.
     * @deprecated This is only used for previous fs-verity implementation, and should never be used
     *             on new devices.
     */
    @Deprecated
    public static byte[] getVerityRootHash(@NonNull String apkPath)
            throws IOException, SignatureNotFoundException {
        return ApkSignatureVerifier.getVerityRootHash(apkPath);
    }

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

    /**
     * @deprecated This is only used for previous fs-verity implementation, and should never be used
     *             on new devices.
     */
    @Deprecated
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
}
