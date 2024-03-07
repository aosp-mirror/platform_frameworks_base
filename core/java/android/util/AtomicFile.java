/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.util;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.FileUtils;
import android.os.SystemClock;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Helper class for performing atomic operations on a file by writing to a new file and renaming it
 * into the place of the original file after the write has successfully completed. If you need this
 * on older versions of the platform you can use {@link androidx.core.util.AtomicFile} in AndroidX.
 * <p>
 * Atomic file guarantees file integrity by ensuring that a file has been completely written and
 * sync'd to disk before renaming it to the original file. Previously this is done by renaming the
 * original file to a backup file beforehand, but this approach couldn't handle the case where the
 * file is created for the first time. This class will also handle the backup file created by the
 * old implementation properly.
 * <p>
 * Atomic file does not confer any file locking semantics. Do not use this class when the file may
 * be accessed or modified concurrently by multiple threads or processes. The caller is responsible
 * for ensuring appropriate mutual exclusion invariants whenever it accesses the file.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class AtomicFile {
    private static final String LOG_TAG = "AtomicFile";

    private final File mBaseName;
    private final File mNewName;
    private final File mLegacyBackupName;
    private SystemConfigFileCommitEventLogger mCommitEventLogger;

    /**
     * Create a new AtomicFile for a file located at the given File path.
     * The new file created when writing will be the same file path with ".new" appended.
     */
    public AtomicFile(File baseName) {
        this(baseName, (SystemConfigFileCommitEventLogger) null);
    }

    /**
     * @hide Internal constructor that also allows you to have the class
     * automatically log commit events.
     */
    @android.ravenwood.annotation.RavenwoodThrow(blockedBy =
            SystemConfigFileCommitEventLogger.class)
    public AtomicFile(File baseName, String commitTag) {
        this(baseName, new SystemConfigFileCommitEventLogger(commitTag));
    }

    /**
     * Internal constructor that also allows you to have the class
     * automatically log commit events.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @SuppressLint("StreamFiles")
    public AtomicFile(@NonNull File baseName,
            @Nullable SystemConfigFileCommitEventLogger commitEventLogger) {
        mBaseName = baseName;
        mNewName = new File(baseName.getPath() + ".new");
        mLegacyBackupName = new File(baseName.getPath() + ".bak");
        mCommitEventLogger = commitEventLogger;
    }

    /**
     * Return the path to the base file.  You should not generally use this,
     * as the data at that path may not be valid.
     */
    public File getBaseFile() {
        return mBaseName;
    }

    /**
     * Delete the atomic file.  This deletes both the base and new files.
     */
    public void delete() {
        mBaseName.delete();
        mNewName.delete();
        mLegacyBackupName.delete();
    }

    /**
     * Start a new write operation on the file.  This returns a FileOutputStream
     * to which you can write the new file data.  The existing file is replaced
     * with the new data.  You <em>must not</em> directly close the given
     * FileOutputStream; instead call either {@link #finishWrite(FileOutputStream)}
     * or {@link #failWrite(FileOutputStream)}.
     *
     * <p>Note that if another thread is currently performing
     * a write, this will simply replace whatever that thread is writing
     * with the new file being written by this thread, and when the other
     * thread finishes the write the new write operation will no longer be
     * safe (or will be lost).  You must do your own threading protection for
     * access to AtomicFile.
     */
    public FileOutputStream startWrite() throws IOException {
        return startWrite(0);
    }

    /**
     * @hide Internal version of {@link #startWrite()} that allows you to specify an earlier
     * start time of the operation to adjust how the commit is logged.
     * @param startTime The effective start time of the operation, in the time
     * base of {@link SystemClock#uptimeMillis()}.
     *
     * @deprecated Use {@link SystemConfigFileCommitEventLogger#setStartTime} followed
     * by {@link #startWrite()}
     */
    @Deprecated
    public FileOutputStream startWrite(long startTime) throws IOException {
        if (mCommitEventLogger != null) {
            mCommitEventLogger.setStartTime(startTime);
            mCommitEventLogger.onStartWrite();
        }

        if (mLegacyBackupName.exists()) {
            rename(mLegacyBackupName, mBaseName);
        }

        try {
            return new FileOutputStream(mNewName);
        } catch (FileNotFoundException e) {
            File parent = mNewName.getParentFile();
            if (!parent.mkdirs()) {
                throw new IOException("Failed to create directory for " + mNewName);
            }
            FileUtils.setPermissions(parent.getPath(), FileUtils.S_IRWXU | FileUtils.S_IRWXG
                    | FileUtils.S_IXOTH, -1, -1);
            try {
                return new FileOutputStream(mNewName);
            } catch (FileNotFoundException e2) {
                throw new IOException("Failed to create new file " + mNewName, e2);
            }
        }
    }

    /**
     * Call when you have successfully finished writing to the stream
     * returned by {@link #startWrite()}.  This will close, sync, and
     * commit the new data.  The next attempt to read the atomic file
     * will return the new file stream.
     */
    public void finishWrite(FileOutputStream str) {
        if (str == null) {
            return;
        }
        if (!FileUtils.sync(str)) {
            Log.e(LOG_TAG, "Failed to sync file output stream");
        }
        try {
            str.close();
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to close file output stream", e);
        }
        rename(mNewName, mBaseName);
        if (mCommitEventLogger != null) {
            mCommitEventLogger.onFinishWrite();
        }
    }

    /**
     * Call when you have failed for some reason at writing to the stream
     * returned by {@link #startWrite()}.  This will close the current
     * write stream, and delete the new file.
     */
    public void failWrite(FileOutputStream str) {
        if (str == null) {
            return;
        }
        if (!FileUtils.sync(str)) {
            Log.e(LOG_TAG, "Failed to sync file output stream");
        }
        try {
            str.close();
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to close file output stream", e);
        }
        if (!mNewName.delete()) {
            Log.e(LOG_TAG, "Failed to delete new file " + mNewName);
        }
    }

    /** @hide
     * @deprecated This is not safe.
     */
    @Deprecated public void truncate() throws IOException {
        try {
            FileOutputStream fos = new FileOutputStream(mBaseName);
            FileUtils.sync(fos);
            fos.close();
        } catch (FileNotFoundException e) {
            throw new IOException("Couldn't append " + mBaseName);
        } catch (IOException e) {
        }
    }

    /** @hide
     * @deprecated This is not safe.
     */
    @Deprecated public FileOutputStream openAppend() throws IOException {
        try {
            return new FileOutputStream(mBaseName, true);
        } catch (FileNotFoundException e) {
            throw new IOException("Couldn't append " + mBaseName);
        }
    }

    /**
     * Open the atomic file for reading. You should call close() on the FileInputStream when you are
     * done reading from it.
     * <p>
     * You must do your own threading protection for access to AtomicFile.
     */
    public FileInputStream openRead() throws FileNotFoundException {
        if (mLegacyBackupName.exists()) {
            rename(mLegacyBackupName, mBaseName);
        }

        // It was okay to call openRead() between startWrite() and finishWrite() for the first time
        // (because there is no backup file), where openRead() would open the file being written,
        // which makes no sense, but finishWrite() would still persist the write properly. For all
        // subsequent writes, if openRead() was called in between, it would see a backup file and
        // delete the file being written, the same behavior as our new implementation. So we only
        // need a special case for the first write, and don't delete the new file in this case so
        // that finishWrite() can still work.
        if (mNewName.exists() && mBaseName.exists()) {
            if (!mNewName.delete()) {
                Log.e(LOG_TAG, "Failed to delete outdated new file " + mNewName);
            }
        }
        return new FileInputStream(mBaseName);
    }

    /**
     * @hide
     * Checks if the original or legacy backup file exists.
     * @return whether the original or legacy backup file exists.
     */
    public boolean exists() {
        return mBaseName.exists() || mLegacyBackupName.exists();
    }

    /**
     * Gets the last modified time of the atomic file.
     *
     * @return last modified time in milliseconds since epoch.  Returns zero if
     *     the file does not exist or an I/O error is encountered.
     */
    @CurrentTimeMillisLong
    public long getLastModifiedTime() {
        if (mLegacyBackupName.exists()) {
            return mLegacyBackupName.lastModified();
        }
        return mBaseName.lastModified();
    }

    /**
     * A convenience for {@link #openRead()} that also reads all of the
     * file contents into a byte array which is returned.
     */
    public byte[] readFully() throws IOException {
        FileInputStream stream = openRead();
        try {
            int pos = 0;
            int avail = stream.available();
            byte[] data = new byte[avail];
            while (true) {
                int amt = stream.read(data, pos, data.length-pos);
                //Log.i("foo", "Read " + amt + " bytes at " + pos
                //        + " of avail " + data.length);
                if (amt <= 0) {
                    //Log.i("foo", "**** FINISHED READING: pos=" + pos
                    //        + " len=" + data.length);
                    return data;
                }
                pos += amt;
                avail = stream.available();
                if (avail > data.length-pos) {
                    byte[] newData = new byte[pos+avail];
                    System.arraycopy(data, 0, newData, 0, pos);
                    data = newData;
                }
            }
        } finally {
            stream.close();
        }
    }

    /** @hide */
    public void write(Consumer<FileOutputStream> writeContent) {
        FileOutputStream out = null;
        try {
            out = startWrite();
            writeContent.accept(out);
            finishWrite(out);
        } catch (Throwable t) {
            failWrite(out);
            throw ExceptionUtils.propagate(t);
        } finally {
            IoUtils.closeQuietly(out);
        }
    }

    @Override
    public String toString() {
        return "AtomicFile[" + mBaseName + "]";
    }

    private static void rename(File source, File target) {
        // We used to delete the target file before rename, but that isn't atomic, and the rename()
        // syscall should atomically replace the target file. However in the case where the target
        // file is a directory, a simple rename() won't work. We need to delete the file in this
        // case because there are callers who erroneously called mBaseName.mkdirs() (instead of
        // mBaseName.getParentFile().mkdirs()) before creating the AtomicFile, and it worked
        // regardless, so this deletion became some kind of API.
        if (target.isDirectory()) {
            if (!target.delete()) {
                Log.e(LOG_TAG, "Failed to delete file which is a directory " + target);
            }
        }
        if (!source.renameTo(target)) {
            Log.e(LOG_TAG, "Failed to rename " + source + " to " + target);
        }
    }
}
