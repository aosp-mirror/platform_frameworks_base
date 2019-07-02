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
import android.annotation.Nullable;
import android.os.FileUtils;
import android.util.ArrayMap;

import com.android.internal.util.Preconditions;

import java.io.File;
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
    private final @NonNull ArrayMap<File, FileOutputStream> mOpenFiles = new ArrayMap<>();
    private final @NonNull File mBaseDirectory;
    private final @NonNull File mBackupDirectory;

    private int mBaseDirectoryFd = -1;
    private int mBackupDirectoryFd = -1;

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
     * Gets the backup directory if present. This could be useful if you are
     * writing new state to the dir but need to access the last persisted state
     * at the same time. This means that this call is useful in between
     * {@link #startWrite()} and {@link #finishWrite()} or {@link #failWrite()}.
     * You should not modify the content returned by this method.
     *
     * @see #startRead()
     */
    public @Nullable File getBackupDirectory() {
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
        return getOrCreateBaseDirectory();
    }

    /**
     * Finishes reading this directory.
     *
     * @see #startRead()
     * @see #startWrite()
     */
    public void finishRead() {
        mBaseDirectoryFd = -1;
        mBackupDirectoryFd = -1;
    }

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
        return getOrCreateBaseDirectory();
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
        if (file.isDirectory() || !file.getParentFile().equals(getOrCreateBaseDirectory())) {
            throw new IllegalArgumentException("Must be a file in " + getOrCreateBaseDirectory());
        }
        final FileOutputStream destination = new FileOutputStream(file);
        if (mOpenFiles.put(file, destination) != null) {
            throw new IllegalArgumentException("Already open file" + file.getCanonicalPath());
        }
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
        if (mOpenFiles.removeAt(indexOfValue) == null) {
            throw new IllegalArgumentException("Unknown file stream " + destination);
        }
        FileUtils.sync(destination);
        try {
            destination.close();
        } catch (IOException ignored) {}
    }

    public void failWrite(@NonNull FileOutputStream destination) {
        final int indexOfValue = mOpenFiles.indexOfValue(destination);
        if (indexOfValue >= 0) {
            mOpenFiles.removeAt(indexOfValue);
        }
    }

    /**
     * Finishes the edit and commits all changes.
     *
     * @see #startWrite()
     *
     * @throws IllegalStateException is some files are not closed.
     */
    public void finishWrite() {
        throwIfSomeFilesOpen();
        fsyncDirectoryFd(mBaseDirectoryFd);
        deleteDirectory(mBackupDirectory);
        fsyncDirectoryFd(mBackupDirectoryFd);
        mBaseDirectoryFd = -1;
        mBackupDirectoryFd = -1;
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
        } catch (IOException ignored) {}
        mBaseDirectoryFd = -1;
        mBackupDirectoryFd = -1;
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
        if (mBaseDirectory.exists()) {
            deleteDirectory(mBaseDirectory);
            fsyncDirectoryFd(mBaseDirectoryFd);
        }
        if (mBackupDirectory.exists()) {
            deleteDirectory(mBackupDirectory);
            fsyncDirectoryFd(mBackupDirectoryFd);
        }
    }

    private @NonNull File getOrCreateBaseDirectory() throws IOException {
        if (!mBaseDirectory.exists()) {
            if (!mBaseDirectory.mkdirs()) {
                throw new IOException("Couldn't create directory " + mBaseDirectory);
            }
            FileUtils.setPermissions(mBaseDirectory.getPath(),
                    FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH,
                    -1, -1);
        }
        if (mBaseDirectoryFd < 0) {
            mBaseDirectoryFd = getDirectoryFd(mBaseDirectory.getCanonicalPath());
        }
        return mBaseDirectory;
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
        if (mBaseDirectoryFd < 0) {
            mBaseDirectoryFd = getDirectoryFd(mBaseDirectory.getCanonicalPath());
        }
        if (mBackupDirectory.exists()) {
            deleteDirectory(mBackupDirectory);
        }
        if (!mBaseDirectory.renameTo(mBackupDirectory)) {
            throw new IOException("Couldn't backup " + mBaseDirectory
                    + " to " + mBackupDirectory);
        }
        mBackupDirectoryFd = mBaseDirectoryFd;
        mBaseDirectoryFd = -1;
        fsyncDirectoryFd(mBackupDirectoryFd);
    }

    private void restore() throws IOException {
        if (!mBackupDirectory.exists()) {
            return;
        }
        if (mBackupDirectoryFd == -1) {
            mBackupDirectoryFd = getDirectoryFd(mBackupDirectory.getCanonicalPath());
        }
        if (mBaseDirectory.exists()) {
            deleteDirectory(mBaseDirectory);
        }
        if (!mBackupDirectory.renameTo(mBaseDirectory)) {
            throw new IOException("Couldn't restore " + mBackupDirectory
                    + " to " + mBaseDirectory);
        }
        mBaseDirectoryFd = mBackupDirectoryFd;
        mBackupDirectoryFd = -1;
        fsyncDirectoryFd(mBaseDirectoryFd);
    }

    private static void deleteDirectory(@NonNull File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteDirectory(child);
            }
        }
        file.delete();
    }

    private static native int getDirectoryFd(String path);
    private static native void fsyncDirectoryFd(int fd);
}
