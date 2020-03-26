/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.utils.blob;

import static com.android.utils.blob.Utils.BUFFER_SIZE_BYTES;
import static com.android.utils.blob.Utils.copy;

import static com.google.common.truth.Truth.assertThat;

import android.app.blob.BlobHandle;
import android.app.blob.BlobStoreManager;
import android.content.Context;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class DummyBlobData {
    private static final long DEFAULT_SIZE_BYTES = 10 * 1024L * 1024L;

    private final Random mRandom;
    private final File mFile;
    private final long mFileSize;
    private final CharSequence mLabel;

    byte[] mFileDigest;
    long mExpiryTimeMs;

    private DummyBlobData(Builder builder) {
        mRandom = new Random(builder.getRandomSeed());
        mFile = new File(builder.getContext().getFilesDir(), builder.getFileName());
        mFileSize = builder.getFileSize();
        mLabel = builder.getLabel();
    }

    public static class Builder {
        private final Context mContext;
        private int mRandomSeed = 0;
        private long mFileSize = DEFAULT_SIZE_BYTES;
        private CharSequence mLabel = "Test label";
        private String mFileName = "blob_" + System.nanoTime();

        public Builder(Context context) {
            mContext = context;
        }

        public Context getContext() {
            return mContext;
        }

        public Builder setRandomSeed(int randomSeed) {
            mRandomSeed = randomSeed;
            return this;
        }

        public int getRandomSeed() {
            return mRandomSeed;
        }

        public Builder setFileSize(int fileSize) {
            mFileSize = fileSize;
            return this;
        }

        public long getFileSize() {
            return mFileSize;
        }

        public Builder setLabel(CharSequence label) {
            mLabel = label;
            return this;
        }

        public CharSequence getLabel() {
            return mLabel;
        }

        public Builder setFileName(String fileName) {
            mFileName = fileName;
            return this;
        }

        public String getFileName() {
            return mFileName;
        }

        public DummyBlobData build() {
            return new DummyBlobData(this);
        }
    }

    public void prepare() throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(mFile, "rw")) {
            writeRandomData(file, mFileSize);
        }
        mFileDigest = FileUtils.digest(mFile, "SHA-256");
        mExpiryTimeMs = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1);
    }

    public BlobHandle getBlobHandle() throws Exception {
        return BlobHandle.createWithSha256(mFileDigest, mLabel,
                mExpiryTimeMs, "test_tag");
    }

    public long getFileSize() throws Exception {
        return mFileSize;
    }

    public long getExpiryTimeMillis() {
        return mExpiryTimeMs;
    }

    public void delete() {
        mFile.delete();
    }

    public void writeToSession(BlobStoreManager.Session session) throws Exception {
        writeToSession(session, 0, mFileSize);
    }

    public void writeToSession(BlobStoreManager.Session session,
            long offsetBytes, long lengthBytes) throws Exception {
        try (FileInputStream in = new FileInputStream(mFile)) {
            Utils.writeToSession(session, in, offsetBytes, lengthBytes);
        }
    }

    public void writeToFd(FileDescriptor fd, long offsetBytes, long lengthBytes) throws Exception {
        try (FileInputStream in = new FileInputStream(mFile)) {
            in.getChannel().position(offsetBytes);
            try (FileOutputStream out = new FileOutputStream(fd)) {
                copy(in, out, lengthBytes);
            }
        }
    }

    public ParcelFileDescriptor openForRead() throws Exception {
        return ParcelFileDescriptor.open(mFile, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    public void readFromSessionAndVerifyBytes(BlobStoreManager.Session session,
            long offsetBytes, int lengthBytes) throws Exception {
        final byte[] expectedBytes = new byte[lengthBytes];
        try (FileInputStream in = new FileInputStream(mFile)) {
            read(in, expectedBytes, offsetBytes, lengthBytes);
        }

        final byte[] actualBytes = new byte[lengthBytes];
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                session.openRead())) {
            read(in, actualBytes, offsetBytes, lengthBytes);
        }

        assertThat(actualBytes).isEqualTo(expectedBytes);

    }

    private void read(FileInputStream in, byte[] buffer,
            long offsetBytes, int lengthBytes) throws Exception {
        in.getChannel().position(offsetBytes);
        in.read(buffer, 0, lengthBytes);
    }

    public void readFromSessionAndVerifyDigest(BlobStoreManager.Session session)
            throws Exception {
        readFromSessionAndVerifyDigest(session, 0, mFile.length());
    }

    public void readFromSessionAndVerifyDigest(BlobStoreManager.Session session,
            long offsetBytes, long lengthBytes) throws Exception {
        final byte[] actualDigest;
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                session.openRead())) {
            actualDigest = createSha256Digest(in, offsetBytes, lengthBytes);
        }

        assertThat(actualDigest).isEqualTo(mFileDigest);
    }

    public void verifyBlob(ParcelFileDescriptor pfd) throws Exception {
        final byte[] actualDigest;
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            actualDigest = FileUtils.digest(in, "SHA-256");
        }
        assertThat(actualDigest).isEqualTo(mFileDigest);
    }

    private byte[] createSha256Digest(FileInputStream in, long offsetBytes, long lengthBytes)
            throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        in.getChannel().position(offsetBytes);
        final byte[] buffer = new byte[BUFFER_SIZE_BYTES];
        long bytesRead = 0;
        while (bytesRead < lengthBytes) {
            int toRead = (bytesRead + buffer.length <= lengthBytes)
                    ? buffer.length : (int) (lengthBytes - bytesRead);
            toRead = in.read(buffer, 0, toRead);
            digest.update(buffer, 0, toRead);
            bytesRead += toRead;
        }
        return digest.digest();
    }

    private void writeRandomData(RandomAccessFile file, long fileSize)
            throws Exception {
        long bytesWritten = 0;
        final byte[] buffer = new byte[BUFFER_SIZE_BYTES];
        while (bytesWritten < fileSize) {
            mRandom.nextBytes(buffer);
            final int toWrite = (bytesWritten + buffer.length <= fileSize)
                    ? buffer.length : (int) (fileSize - bytesWritten);
            file.seek(bytesWritten);
            file.write(buffer, 0, toWrite);
            bytesWritten += toWrite;
        }
    }
}
