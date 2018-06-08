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
import android.util.apk.ApkSignatureVerifier;
import android.util.apk.ByteBufferFactory;
import android.util.apk.SignatureNotFoundException;
import android.util.Pair;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.DigestException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Provides fsverity related operations. */
abstract public class VerityUtils {
    private static final String TAG = "VerityUtils";

    private static final boolean DEBUG = false;

    /**
     * Generates Merkle tree and fsverity metadata.
     *
     * @return {@code SetupResult} that contains the {@code EsetupResultCode}, and when success, the
     *         {@code FileDescriptor} to read all the data from.
     */
    public static SetupResult generateApkVeritySetupData(@NonNull String apkPath) {
        if (DEBUG) Slog.d(TAG, "Trying to install apk verity to " + apkPath);
        SharedMemory shm = null;
        try {
            byte[] signedRootHash = ApkSignatureVerifier.getVerityRootHash(apkPath);
            if (signedRootHash == null) {
                if (DEBUG) {
                    Slog.d(TAG, "Skip verity tree generation since there is no root hash");
                }
                return SetupResult.skipped();
            }

            Pair<SharedMemory, Integer> result = generateApkVerityIntoSharedMemory(apkPath,
                    signedRootHash);
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
     * {@see ApkSignatureVerifier#generateFsverityRootHash(String)}.
     */
    public static byte[] generateFsverityRootHash(@NonNull String apkPath)
            throws NoSuchAlgorithmException, DigestException, IOException {
        return ApkSignatureVerifier.generateFsverityRootHash(apkPath);
    }

    /**
     * {@see ApkSignatureVerifier#getVerityRootHash(String)}.
     */
    public static byte[] getVerityRootHash(@NonNull String apkPath)
            throws IOException, SignatureNotFoundException, SecurityException {
        return ApkSignatureVerifier.getVerityRootHash(apkPath);
    }

    /**
     * Returns a pair of {@code SharedMemory} and {@code Integer}. The {@code SharedMemory} contains
     * Merkle tree and fsverity headers for the given apk, in the form that can immediately be used
     * for fsverity setup. The data is aligned to the beginning of {@code SharedMemory}, and has
     * length equals to the returned {@code Integer}.
     */
    private static Pair<SharedMemory, Integer> generateApkVerityIntoSharedMemory(
            String apkPath, byte[] expectedRootHash)
            throws IOException, SecurityException, DigestException, NoSuchAlgorithmException,
                   SignatureNotFoundException {
        TrackedShmBufferFactory shmBufferFactory = new TrackedShmBufferFactory();
        byte[] generatedRootHash = ApkSignatureVerifier.generateApkVerity(apkPath,
                shmBufferFactory);
        // We only generate Merkle tree once here, so it's important to make sure the root hash
        // matches the signed one in the apk.
        if (!Arrays.equals(expectedRootHash, generatedRootHash)) {
            throw new SecurityException("Locally generated verity root hash does not match");
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

    public static class SetupResult {
        /** Result code if verity is set up correctly. */
        private static final int RESULT_OK = 1;

        /** Result code if the apk does not contain a verity root hash. */
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
