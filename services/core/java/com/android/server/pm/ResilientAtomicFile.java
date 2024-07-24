/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Slog;

import com.android.server.security.FileIntegrity;

import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

final class ResilientAtomicFile implements Closeable {
    private static final String LOG_TAG = "ResilientAtomicFile";

    private final File mFile;

    private final File mTemporaryBackup;

    private final File mReserveCopy;

    private final int mFileMode;

    private final String mDebugName;

    private final ReadEventLogger mReadEventLogger;

    // Write state.
    private FileOutputStream mMainOutStream = null;
    private FileInputStream mMainInStream = null;
    private FileOutputStream mReserveOutStream = null;
    private FileInputStream mReserveInStream = null;

    // Read state.
    private File mCurrentFile = null;
    private FileInputStream mCurrentInStream = null;

    private void finalizeOutStream(FileOutputStream str) throws IOException {
        // Flash/sync + set permissions.
        str.flush();
        FileUtils.sync(str);
        FileUtils.setPermissions(str.getFD(), mFileMode, -1, -1);
    }

    ResilientAtomicFile(@NonNull File file, @NonNull File temporaryBackup,
            @NonNull File reserveCopy, int fileMode, String debugName,
            @Nullable ReadEventLogger readEventLogger) {
        mFile = file;
        mTemporaryBackup = temporaryBackup;
        mReserveCopy = reserveCopy;
        mFileMode = fileMode;
        mDebugName = debugName;
        mReadEventLogger = readEventLogger;
    }

    public File getBaseFile() {
        return mFile;
    }

    public FileOutputStream startWrite() throws IOException {
        if (mMainOutStream != null) {
            throw new IllegalStateException("Duplicate startWrite call?");
        }

        new File(mFile.getParent()).mkdirs();

        if (mFile.exists()) {
            // Presence of backup settings file indicates that we failed
            // to persist packages earlier. So preserve the older
            // backup for future reference since the current packages
            // might have been corrupted.
            if (!mTemporaryBackup.exists()) {
                if (!mFile.renameTo(mTemporaryBackup)) {
                    throw new IOException("Unable to backup " + mDebugName
                            + " file, current changes will be lost at reboot");
                }
            } else {
                mFile.delete();
                Slog.w(LOG_TAG, "Preserving older " + mDebugName + " backup");
            }
        }
        // Reserve copy is not valid anymore.
        mReserveCopy.delete();

        // In case of MT access, it's possible the files get overwritten during write.
        // Let's open all FDs we need now.
        try {
            mMainOutStream = new FileOutputStream(mFile);
            mMainInStream = new FileInputStream(mFile);
            mReserveOutStream = new FileOutputStream(mReserveCopy);
            mReserveInStream = new FileInputStream(mReserveCopy);
        } catch (IOException e) {
            close();
            throw e;
        }

        return mMainOutStream;
    }

    public void finishWrite(FileOutputStream str) throws IOException {
        if (mMainOutStream != str) {
            throw new IllegalStateException("Invalid incoming stream.");
        }

        // Flush and set permissions.
        try (FileOutputStream mainOutStream = mMainOutStream) {
            mMainOutStream = null;
            finalizeOutStream(mainOutStream);
        }
        // New file successfully written, old one are no longer needed.
        mTemporaryBackup.delete();

        try (FileInputStream mainInStream = mMainInStream;
             FileInputStream reserveInStream = mReserveInStream) {
            mMainInStream = null;
            mReserveInStream = null;

            // Copy main file to reserve.
            try (FileOutputStream reserveOutStream = mReserveOutStream) {
                mReserveOutStream = null;
                FileUtils.copy(mainInStream, reserveOutStream);
                finalizeOutStream(reserveOutStream);
            }

            // Protect both main and reserve using fs-verity.
            try (ParcelFileDescriptor mainPfd = ParcelFileDescriptor.dup(mainInStream.getFD());
                 ParcelFileDescriptor copyPfd = ParcelFileDescriptor.dup(reserveInStream.getFD())) {
                FileIntegrity.setUpFsVerity(mainPfd);
                FileIntegrity.setUpFsVerity(copyPfd);
            } catch (IOException e) {
                Slog.e(LOG_TAG, "Failed to verity-protect " + mDebugName, e);
            }
        } catch (IOException e) {
            Slog.e(LOG_TAG, "Failed to write reserve copy " + mDebugName + ": " + mReserveCopy, e);
        }
    }

    public void failWrite(FileOutputStream str) {
        if (mMainOutStream != str) {
            throw new IllegalStateException("Invalid incoming stream.");
        }

        // Close all FDs.
        close();

        // Clean up partially written files
        if (mFile.exists()) {
            if (!mFile.delete()) {
                Slog.i(LOG_TAG, "Failed to clean up mangled file: " + mFile);
            }
        }
    }

    public FileInputStream openRead() throws IOException {
        if (mTemporaryBackup.exists()) {
            try {
                mCurrentFile = mTemporaryBackup;
                mCurrentInStream = new FileInputStream(mCurrentFile);
                if (mReadEventLogger != null) {
                    mReadEventLogger.logEvent(Log.INFO,
                            "Need to read from backup " + mDebugName + " file");
                }
                if (mFile.exists()) {
                    // If both the backup and normal file exist, we
                    // ignore the normal one since it might have been
                    // corrupted.
                    Slog.w(LOG_TAG, "Cleaning up " + mDebugName + " file " + mFile);
                    mFile.delete();
                }
                // Ignore reserve copy as well.
                mReserveCopy.delete();
            } catch (java.io.IOException e) {
                // We'll try for the normal settings file.
            }
        }

        if (mCurrentInStream != null) {
            return mCurrentInStream;
        }

        if (mFile.exists()) {
            mCurrentFile = mFile;
            mCurrentInStream = new FileInputStream(mCurrentFile);
        } else if (mReserveCopy.exists()) {
            mCurrentFile = mReserveCopy;
            mCurrentInStream = new FileInputStream(mCurrentFile);
            if (mReadEventLogger != null) {
                mReadEventLogger.logEvent(Log.INFO,
                        "Need to read from reserve copy " + mDebugName + " file");
            }
        }

        if (mCurrentInStream == null) {
            if (mReadEventLogger != null) {
                mReadEventLogger.logEvent(Log.INFO, "No " + mDebugName + " file");
            }
        }

        return mCurrentInStream;
    }

    public void failRead(FileInputStream str, Exception e) {
        if (mCurrentInStream != str) {
            throw new IllegalStateException("Invalid incoming stream.");
        }
        mCurrentInStream = null;
        IoUtils.closeQuietly(str);

        if (mReadEventLogger != null) {
            mReadEventLogger.logEvent(Log.ERROR,
                    "Error reading " + mDebugName + ", removing " + mCurrentFile + '\n'
                            + Log.getStackTraceString(e));
        }

        if (!mCurrentFile.delete()) {
            throw new IllegalStateException("Failed to remove " + mCurrentFile);
        }
        mCurrentFile = null;
    }

    public void delete() {
        mFile.delete();
        mTemporaryBackup.delete();
        mReserveCopy.delete();
    }

    @Override
    public void close() {
        IoUtils.closeQuietly(mMainOutStream);
        IoUtils.closeQuietly(mMainInStream);
        IoUtils.closeQuietly(mReserveOutStream);
        IoUtils.closeQuietly(mReserveInStream);
        IoUtils.closeQuietly(mCurrentInStream);
        mMainOutStream = null;
        mMainInStream = null;
        mReserveOutStream = null;
        mReserveInStream = null;
        mCurrentInStream = null;
        mCurrentFile = null;
    }

    public String toString() {
        return mFile.getPath();
    }

    interface ReadEventLogger {
        void logEvent(int priority, String msg);
    }
}
