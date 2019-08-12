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

package com.android.internal.os;

import android.annotation.NonNull;
import android.os.FileUtils;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Helper class for performing atomic operations on a directory, by creating a
 * backup directory until a write has successfully completed.
 * <p>
 * Atomic directory guarantees directory integrity by ensuring that a directory has
 * been completely written and sync'd to disk before removing its backup.
 * As long as the backup directory exists, the original directory is considered
 * to be invalid (leftover from a previous attempt to write).
 * <p>
 * Atomic directory does not confer any file locking semantics. Do not use this
 * class when the directory may be accessed or modified concurrently
 * by multiple threads or processes. The caller is responsible for ensuring
 * appropriate mutual exclusion invariants whenever it accesses the directory.
 * <p>
 * To ensure atomicity you must always use this class to interact with the
 * backing directory when checking existence, making changes, and deleting.
 */
public final class AtomicDirectory {

    private static final String LOG_TAG = AtomicDirectory.class.getSimpleName();

    private final @NonNull File mBaseDirectory;
    private final @NonNull File mBackupDirectory;

    private final @NonNull ArrayMap<File, FileOutputStream> mOpenFiles = new ArrayMap<>();

    /**
     * Creates a new instance.
     *
     * @param baseDirectory The base directory to treat atomically.
     */
    public AtomicDirectory(@NonNull File baseDirectory) {
        Preconditions.checkNotNull(baseDirectory, "baseDirectory cannot be null");
        mBaseDirectory = baseDirectory;
        mBackupDirectory = new File(baseDirectory.getPath() + "_bak");
    }

    /**
     * Gets the backup directory which may or may not exist. This could be
     * useful if you are writing new state to the directory but need to access
     * the last persisted state at the same time. This means that this call is
     * useful in between {@link #startWrite()} and {@link #finishWrite()} or
     * {@link #failWrite()}. You should not modify the content returned by this
     * method.
     *
     * @see #startRead()
     */
    public @NonNull File getBackupDirectory() {
        return mBackupDirectory;
    }

    /**
     * Starts reading this directory. After calling this method you should
     * not make any changes to its contents.
     *
     * @throws IOException If an error occurs.
     *
     * @see #finishRead()
     * @see #startWrite()
     */
    public @NonNull File startRead() throws IOException {
        restore();
        ensureBaseDirectory();
        return mBaseDirectory;
    }

    /**
     * Finishes reading this directory.
     *
     * @see #startRead()
     * @see #startWrite()
     */
    public void finishRead() {}

    /**
     * Starts editing this directory. After calling this method you should
     * add content to the directory only via the APIs on this class. To open a
     * file for writing in this directory you should use {@link #openWrite(File)}
     * and to close the file {@link #closeWrite(FileOutputStream)}. Once all
     * content has been written and all files closed you should commit via a
     * call to {@link #finishWrite()} or discard via a call to {@link #failWrite()}.
     *
     * @throws IOException If an error occurs.
     *
     * @see #startRead()
     * @see #openWrite(File)
     * @see #finishWrite()
     * @see #failWrite()
     */
    public @NonNull File startWrite() throws IOException {
        backup();
        ensureBaseDirectory();
        return mBaseDirectory;
    }

    /**
     * Opens a file in this directory for writing.
     *
     * @param file The file to open. Must be a file in the base directory.
     * @return An input stream for reading.
     *
     * @throws IOException If an I/O error occurs.
     *
     * @see #closeWrite(FileOutputStream)
     */
    public @NonNull FileOutputStream openWrite(@NonNull File file) throws IOException {
        if (file.isDirectory() || !file.getParentFile().equals(mBaseDirectory)) {
            throw new IllegalArgumentException("Must be a file in " + mBaseDirectory);
        }
        if (mOpenFiles.containsKey(file)) {
            throw new IllegalArgumentException("Already open file " + file.getAbsolutePath());
        }
        final FileOutputStream destination = new FileOutputStream(file);
        mOpenFiles.put(file, destination);
        return destination;
    }

    /**
     * Closes a previously opened file.
     *
     * @param destination The stream to the file returned by {@link #openWrite(File)}.
     *
     * @see #openWrite(File)
     */
    public void closeWrite(@NonNull FileOutputStream destination) {
        final int indexOfValue = mOpenFiles.indexOfValue(destination);
        if (indexOfValue < 0) {
            throw new IllegalArgumentException("Unknown file stream " + destination);
        }
        mOpenFiles.removeAt(indexOfValue);
        FileUtils.sync(destination);
        FileUtils.closeQuietly(destination);
    }

    public void failWrite(@NonNull FileOutputStream destination) {
        final int indexOfValue = mOpenFiles.indexOfValue(destination);
        if (indexOfValue < 0) {
            throw new IllegalArgumentException("Unknown file stream " + destination);
        }
        mOpenFiles.removeAt(indexOfValue);
        FileUtils.closeQuietly(destination);
    }

    /**
     * Finishes the edit and commits all changes.
     *
     * @see #startWrite()
     *
     * @throws IllegalStateException if some files are not closed.
     */
    public void finishWrite() {
        throwIfSomeFilesOpen();

        syncDirectory(mBaseDirectory);
        syncParentDirectory();
        deleteDirectory(mBackupDirectory);
        syncParentDirectory();
    }

    /**
     * Finishes the edit and discards all changes.
     *
     * @see #startWrite()
     */
    public void failWrite() {
        throwIfSomeFilesOpen();

        try{
            restore();
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to restore in failWrite()", e);
        }
    }

    /**
     * @return Whether this directory exists.
     */
    public boolean exists() {
        return mBaseDirectory.exists() || mBackupDirectory.exists();
    }

    /**
     * Deletes this directory.
     */
    public void delete() {
        boolean deleted = false;
        if (mBaseDirectory.exists()) {
            deleted |= deleteDirectory(mBaseDirectory);
        }
        if (mBackupDirectory.exists()) {
            deleted |= deleteDirectory(mBackupDirectory);
        }
        if (deleted) {
            syncParentDirectory();
        }
    }

    private void ensureBaseDirectory() throws IOException {
        if (mBaseDirectory.exists()) {
            return;
        }

        if (!mBaseDirectory.mkdirs()) {
            throw new IOException("Failed to create directory " + mBaseDirectory);
        }
        FileUtils.setPermissions(mBaseDirectory.getPath(),
                FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH, -1, -1);
    }

    private void throwIfSomeFilesOpen() {
        if (!mOpenFiles.isEmpty()) {
            throw new IllegalStateException("Unclosed files: "
                    + Arrays.toString(mOpenFiles.keySet().toArray()));
        }
    }

    private void backup() throws IOException {
        if (!mBaseDirectory.exists()) {
            return;
        }

        if (mBackupDirectory.exists()) {
            deleteDirectory(mBackupDirectory);
        }
        if (!mBaseDirectory.renameTo(mBackupDirectory)) {
            throw new IOException("Failed to backup " + mBaseDirectory + " to " + mBackupDirectory);
        }
        syncParentDirectory();
    }

    private void restore() throws IOException {
        if (!mBackupDirectory.exists()) {
            return;
        }

        if (mBaseDirectory.exists()) {
            deleteDirectory(mBaseDirectory);
        }
        if (!mBackupDirectory.renameTo(mBaseDirectory)) {
            throw new IOException("Failed to restore " + mBackupDirectory + " to "
                    + mBaseDirectory);
        }
        syncParentDirectory();
    }

    private static boolean deleteDirectory(@NonNull File directory) {
        return FileUtils.deleteContentsAndDir(directory);
    }

    private void syncParentDirectory() {
        syncDirectory(mBaseDirectory.getParentFile());
    }

    // Standard Java IO doesn't allow opening a directory (will throw a FileNotFoundException
    // instead), so we have to do it manually.
    private static void syncDirectory(@NonNull File directory) {
        String path = directory.getAbsolutePath();
        FileDescriptor fd;
        try {
            fd = Os.open(path, OsConstants.O_RDONLY, 0);
        } catch (ErrnoException e) {
            Log.e(LOG_TAG, "Failed to open " + path, e);
            return;
        }
        try {
            Os.fsync(fd);
        } catch (ErrnoException e) {
            Log.e(LOG_TAG, "Failed to fsync " + path, e);
        } finally {
            FileUtils.closeQuietly(fd);
        }
    }
}
