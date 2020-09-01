/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption.chunking;

import static com.android.internal.util.Preconditions.checkState;

import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.backup.encryption.tasks.DecryptedChunkOutput;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Writes plaintext chunks to a file, building a digest of the plaintext of the resulting file. */
public class DecryptedChunkFileOutput implements DecryptedChunkOutput {
    @VisibleForTesting static final String DIGEST_ALGORITHM = "SHA-256";

    private final File mOutputFile;
    private final MessageDigest mMessageDigest;
    @Nullable private FileOutputStream mFileOutputStream;
    private boolean mClosed;
    @Nullable private byte[] mDigest;

    /**
     * Constructs a new instance which writes chunks to the given file and uses the default message
     * digest algorithm.
     */
    public DecryptedChunkFileOutput(File outputFile) {
        mOutputFile = outputFile;
        try {
            mMessageDigest = MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(
                    "Impossible condition: JCE thinks it does not support AES.", e);
        }
    }

    @Override
    public DecryptedChunkOutput open() throws IOException {
        checkState(mFileOutputStream == null, "Cannot open twice");
        mFileOutputStream = new FileOutputStream(mOutputFile);
        return this;
    }

    @Override
    public void processChunk(byte[] plaintextBuffer, int length) throws IOException {
        checkState(mFileOutputStream != null, "Must open before processing chunks");
        mFileOutputStream.write(plaintextBuffer, /*off=*/ 0, length);
        mMessageDigest.update(plaintextBuffer, /*offset=*/ 0, length);
    }

    @Override
    public byte[] getDigest() {
        checkState(mClosed, "Must close before getting mDigest");

        // After the first call to mDigest() the MessageDigest is reset, thus we must store the
        // result.
        if (mDigest == null) {
            mDigest = mMessageDigest.digest();
        }
        return mDigest;
    }

    @Override
    public void close() throws IOException {
        mFileOutputStream.close();
        mClosed = true;
    }
}
