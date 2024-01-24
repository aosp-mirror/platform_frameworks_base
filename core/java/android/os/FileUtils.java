/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os;

import static android.os.ParcelFileDescriptor.MODE_APPEND;
import static android.os.ParcelFileDescriptor.MODE_CREATE;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.ParcelFileDescriptor.MODE_TRUNCATE;
import static android.os.ParcelFileDescriptor.MODE_WRITE_ONLY;
import static android.system.OsConstants.EINVAL;
import static android.system.OsConstants.ENOSYS;
import static android.system.OsConstants.F_OK;
import static android.system.OsConstants.EIO;
import static android.system.OsConstants.O_ACCMODE;
import static android.system.OsConstants.O_APPEND;
import static android.system.OsConstants.O_CREAT;
import static android.system.OsConstants.O_RDONLY;
import static android.system.OsConstants.O_RDWR;
import static android.system.OsConstants.O_TRUNC;
import static android.system.OsConstants.O_WRONLY;
import static android.system.OsConstants.R_OK;
import static android.system.OsConstants.SPLICE_F_MORE;
import static android.system.OsConstants.SPLICE_F_MOVE;
import static android.system.OsConstants.S_ISFIFO;
import static android.system.OsConstants.S_ISREG;
import static android.system.OsConstants.S_ISSOCK;
import static android.system.OsConstants.W_OK;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.AppGlobals;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.provider.DocumentsContract.Document;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.text.TextUtils;
import android.util.DataUnit;
import android.util.Log;
import android.util.Slog;
import android.webkit.MimeTypeMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.SizedInputStream;

import libcore.io.IoUtils;
import libcore.util.EmptyArray;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

/**
 * Utility methods useful for working with files.
 */
public final class FileUtils {
    private static final String TAG = "FileUtils";

    /** {@hide} */ public static final int S_IRWXU = 00700;
    /** {@hide} */ public static final int S_IRUSR = 00400;
    /** {@hide} */ public static final int S_IWUSR = 00200;
    /** {@hide} */ public static final int S_IXUSR = 00100;

    /** {@hide} */ public static final int S_IRWXG = 00070;
    /** {@hide} */ public static final int S_IRGRP = 00040;
    /** {@hide} */ public static final int S_IWGRP = 00020;
    /** {@hide} */ public static final int S_IXGRP = 00010;

    /** {@hide} */ public static final int S_IRWXO = 00007;
    /** {@hide} */ public static final int S_IROTH = 00004;
    /** {@hide} */ public static final int S_IWOTH = 00002;
    /** {@hide} */ public static final int S_IXOTH = 00001;

    @UnsupportedAppUsage
    private FileUtils() {
    }

    private static final String CAMERA_DIR_LOWER_CASE = "/storage/emulated/" + UserHandle.myUserId()
            + "/dcim/camera";

    /** Regular expression for safe filenames: no spaces or metacharacters.
      *
      * Use a preload holder so that FileUtils can be compile-time initialized.
      */
    private static class NoImagePreloadHolder {
        public static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("[\\w%+,./=_-]+");
    }

    // non-final so it can be toggled by Robolectric's ShadowFileUtils
    private static boolean sEnableCopyOptimizations = true;
    private static volatile int sMediaProviderAppId = -1;

    private static final long COPY_CHECKPOINT_BYTES = 524288;

    /**
     * Listener that is called periodically as progress is made.
     */
    public interface ProgressListener {
        public void onProgress(long progress);
    }

    /**
     * Set owner and mode of of given {@link File}.
     *
     * @param mode to apply through {@code chmod}
     * @param uid to apply through {@code chown}, or -1 to leave unchanged
     * @param gid to apply through {@code chown}, or -1 to leave unchanged
     * @return 0 on success, otherwise errno.
     * @hide
     */
    @UnsupportedAppUsage
    public static int setPermissions(File path, int mode, int uid, int gid) {
        return setPermissions(path.getAbsolutePath(), mode, uid, gid);
    }

    /**
     * Set owner and mode of of given path.
     *
     * @param mode to apply through {@code chmod}
     * @param uid to apply through {@code chown}, or -1 to leave unchanged
     * @param gid to apply through {@code chown}, or -1 to leave unchanged
     * @return 0 on success, otherwise errno.
     * @hide
     */
    @UnsupportedAppUsage
    public static int setPermissions(String path, int mode, int uid, int gid) {
        try {
            Os.chmod(path, mode);
        } catch (ErrnoException e) {
            Slog.w(TAG, "Failed to chmod(" + path + "): " + e);
            return e.errno;
        }

        if (uid >= 0 || gid >= 0) {
            try {
                Os.chown(path, uid, gid);
            } catch (ErrnoException e) {
                Slog.w(TAG, "Failed to chown(" + path + "): " + e);
                return e.errno;
            }
        }

        return 0;
    }

    /**
     * Set owner and mode of of given {@link FileDescriptor}.
     *
     * @param mode to apply through {@code chmod}
     * @param uid to apply through {@code chown}, or -1 to leave unchanged
     * @param gid to apply through {@code chown}, or -1 to leave unchanged
     * @return 0 on success, otherwise errno.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static int setPermissions(FileDescriptor fd, int mode, int uid, int gid) {
        try {
            Os.fchmod(fd, mode);
        } catch (ErrnoException e) {
            Slog.w(TAG, "Failed to fchmod(): " + e);
            return e.errno;
        }

        if (uid >= 0 || gid >= 0) {
            try {
                Os.fchown(fd, uid, gid);
            } catch (ErrnoException e) {
                Slog.w(TAG, "Failed to fchown(): " + e);
                return e.errno;
            }
        }

        return 0;
    }

    /**
     * Copy the owner UID, owner GID, and mode bits from one file to another.
     *
     * @param from File where attributes should be copied from.
     * @param to File where attributes should be copied to.
     * @hide
     */
    public static void copyPermissions(@NonNull File from, @NonNull File to) throws IOException {
        try {
            final StructStat stat = Os.stat(from.getAbsolutePath());
            Os.chmod(to.getAbsolutePath(), stat.st_mode);
            Os.chown(to.getAbsolutePath(), stat.st_uid, stat.st_gid);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * @deprecated use {@link Os#stat(String)} instead.
     * @hide
     */
    @Deprecated
    public static int getUid(String path) {
        try {
            return Os.stat(path).st_uid;
        } catch (ErrnoException e) {
            return -1;
        }
    }

    /**
     * Perform an fsync on the given FileOutputStream.  The stream at this
     * point must be flushed but not yet closed.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static boolean sync(FileOutputStream stream) {
        try {
            if (stream != null) {
                stream.getFD().sync();
            }
            return true;
        } catch (IOException e) {
        }
        return false;
    }

    /**
     * @deprecated use {@link #copy(File, File)} instead.
     * @hide
     */
    @UnsupportedAppUsage
    @Deprecated
    public static boolean copyFile(File srcFile, File destFile) {
        try {
            copyFileOrThrow(srcFile, destFile);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * @deprecated use {@link #copy(File, File)} instead.
     * @hide
     */
    @Deprecated
    public static void copyFileOrThrow(File srcFile, File destFile) throws IOException {
        try (InputStream in = new FileInputStream(srcFile)) {
            copyToFileOrThrow(in, destFile);
        }
    }

    /**
     * @deprecated use {@link #copy(InputStream, OutputStream)} instead.
     * @hide
     */
    @UnsupportedAppUsage
    @Deprecated
    public static boolean copyToFile(InputStream inputStream, File destFile) {
        try {
            copyToFileOrThrow(inputStream, destFile);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * @deprecated use {@link #copy(InputStream, OutputStream)} instead.
     * @hide
     */
    @Deprecated
    public static void copyToFileOrThrow(InputStream in, File destFile) throws IOException {
        if (destFile.exists()) {
            destFile.delete();
        }
        try (FileOutputStream out = new FileOutputStream(destFile)) {
            copy(in, out);
            try {
                Os.fsync(out.getFD());
            } catch (ErrnoException e) {
                throw e.rethrowAsIOException();
            }
        }
    }

    /**
     * Copy the contents of one file to another, replacing any existing content.
     * <p>
     * Attempts to use several optimization strategies to copy the data in the
     * kernel before falling back to a userspace copy as a last resort.
     *
     * @return number of bytes copied.
     * @hide
     */
    public static long copy(@NonNull File from, @NonNull File to) throws IOException {
        return copy(from, to, null, null, null);
    }

    /**
     * Copy the contents of one file to another, replacing any existing content.
     * <p>
     * Attempts to use several optimization strategies to copy the data in the
     * kernel before falling back to a userspace copy as a last resort.
     *
     * @param signal to signal if the copy should be cancelled early.
     * @param executor that listener events should be delivered via.
     * @param listener to be periodically notified as the copy progresses.
     * @return number of bytes copied.
     * @hide
     */
    public static long copy(@NonNull File from, @NonNull File to,
            @Nullable CancellationSignal signal, @Nullable Executor executor,
            @Nullable ProgressListener listener) throws IOException {
        try (FileInputStream in = new FileInputStream(from);
                FileOutputStream out = new FileOutputStream(to)) {
            return copy(in, out, signal, executor, listener);
        }
    }

    /**
     * Copy the contents of one stream to another.
     * <p>
     * Attempts to use several optimization strategies to copy the data in the
     * kernel before falling back to a userspace copy as a last resort.
     *
     * @return number of bytes copied.
     */
    public static long copy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        return copy(in, out, null, null, null);
    }

    /**
     * Copy the contents of one stream to another.
     * <p>
     * Attempts to use several optimization strategies to copy the data in the
     * kernel before falling back to a userspace copy as a last resort.
     *
     * @param signal to signal if the copy should be cancelled early.
     * @param executor that listener events should be delivered via.
     * @param listener to be periodically notified as the copy progresses.
     * @return number of bytes copied.
     */
    public static long copy(@NonNull InputStream in, @NonNull OutputStream out,
            @Nullable CancellationSignal signal, @Nullable Executor executor,
            @Nullable ProgressListener listener) throws IOException {
        if (sEnableCopyOptimizations) {
            if (in instanceof FileInputStream && out instanceof FileOutputStream) {
                return copy(((FileInputStream) in).getFD(), ((FileOutputStream) out).getFD(),
                        signal, executor, listener);
            }
        }

        // Worse case fallback to userspace
        return copyInternalUserspace(in, out, signal, executor, listener);
    }

    /**
     * Copy the contents of one FD to another.
     * <p>
     * Attempts to use several optimization strategies to copy the data in the
     * kernel before falling back to a userspace copy as a last resort.
     *
     * @return number of bytes copied.
     */
    public static long copy(@NonNull FileDescriptor in, @NonNull FileDescriptor out)
            throws IOException {
        return copy(in, out, null, null, null);
    }

    /**
     * Copy the contents of one FD to another.
     * <p>
     * Attempts to use several optimization strategies to copy the data in the
     * kernel before falling back to a userspace copy as a last resort.
     *
     * @param signal to signal if the copy should be cancelled early.
     * @param executor that listener events should be delivered via.
     * @param listener to be periodically notified as the copy progresses.
     * @return number of bytes copied.
     */
    public static long copy(@NonNull FileDescriptor in, @NonNull FileDescriptor out,
            @Nullable CancellationSignal signal, @Nullable Executor executor,
            @Nullable ProgressListener listener) throws IOException {
        return copy(in, out, Long.MAX_VALUE, signal, executor, listener);
    }

    /**
     * Copy the contents of one FD to another.
     * <p>
     * Attempts to use several optimization strategies to copy the data in the
     * kernel before falling back to a userspace copy as a last resort.
     *
     * @param count the number of bytes to copy.
     * @param signal to signal if the copy should be cancelled early.
     * @param executor that listener events should be delivered via.
     * @param listener to be periodically notified as the copy progresses.
     * @return number of bytes copied.
     * @hide
     */
    public static long copy(@NonNull FileDescriptor in, @NonNull FileDescriptor out, long count,
            @Nullable CancellationSignal signal, @Nullable Executor executor,
            @Nullable ProgressListener listener) throws IOException {
        if (sEnableCopyOptimizations) {
            try {
                final StructStat st_in = Os.fstat(in);
                final StructStat st_out = Os.fstat(out);
                if (S_ISREG(st_in.st_mode) && S_ISREG(st_out.st_mode)) {
                    try {
                        return copyInternalSendfile(in, out, count, signal, executor, listener);
                    } catch (ErrnoException e) {
                        if (e.errno == EINVAL || e.errno == ENOSYS) {
                            // sendfile(2) will fail in at least any of the following conditions:
                            // 1. |in| doesn't support mmap(2)
                            // 2. |out| was opened with O_APPEND
                            // We fallback to userspace copy if that fails
                            return copyInternalUserspace(in, out, count, signal, executor,
                                    listener);
                        }
                        throw e;
                    }
                } else if (S_ISFIFO(st_in.st_mode) || S_ISFIFO(st_out.st_mode)) {
                    return copyInternalSplice(in, out, count, signal, executor, listener);
                } else if (S_ISSOCK(st_in.st_mode) || S_ISSOCK(st_out.st_mode)) {
                    return copyInternalSpliceSocket(in, out, count, signal, executor, listener);
                }
            } catch (ErrnoException e) {
                throw e.rethrowAsIOException();
            }
        }

        // Worse case fallback to userspace
        return copyInternalUserspace(in, out, count, signal, executor, listener);
    }

    /**
     * Requires one of input or output to be a pipe.
     *
     * @hide
     */
    @VisibleForTesting
    public static long copyInternalSplice(FileDescriptor in, FileDescriptor out, long count,
            CancellationSignal signal, Executor executor, ProgressListener listener)
            throws ErrnoException {
        long progress = 0;
        long checkpoint = 0;

        long t;
        while ((t = Os.splice(in, null, out, null, Math.min(count, COPY_CHECKPOINT_BYTES),
                SPLICE_F_MOVE | SPLICE_F_MORE)) != 0) {
            progress += t;
            checkpoint += t;
            count -= t;

            if (checkpoint >= COPY_CHECKPOINT_BYTES) {
                if (signal != null) {
                    signal.throwIfCanceled();
                }
                if (executor != null && listener != null) {
                    final long progressSnapshot = progress;
                    executor.execute(() -> {
                        listener.onProgress(progressSnapshot);
                    });
                }
                checkpoint = 0;
            }
        }
        if (executor != null && listener != null) {
            final long progressSnapshot = progress;
            executor.execute(() -> {
                listener.onProgress(progressSnapshot);
            });
        }
        return progress;
    }
    /**
     * Requires one of input or output to be a socket file.
     *
     * @hide
     */
    @VisibleForTesting
    public static long copyInternalSpliceSocket(FileDescriptor in, FileDescriptor out, long count,
            CancellationSignal signal, Executor executor, ProgressListener listener)
            throws ErrnoException {
        long progress = 0;
        long checkpoint = 0;
        long countToRead = count;
        long countInPipe = 0;
        long t;

        FileDescriptor[] pipes = Os.pipe();

        while (countToRead > 0 || countInPipe > 0) {
            if (countToRead > 0) {
                t = Os.splice(in, null, pipes[1], null, Math.min(countToRead, COPY_CHECKPOINT_BYTES),
                              SPLICE_F_MOVE | SPLICE_F_MORE);
                if (t < 0) {
                    // splice error
                    Slog.e(TAG, "splice error, fdIn --> pipe, copy size:" + count +
                           ", copied:" + progress +
                           ", read:" + (count - countToRead) +
                           ", in pipe:" + countInPipe);
                    break;
                } else if (t == 0) {
                    // end of input, input count larger than real size
                    Slog.w(TAG, "Reached the end of the input file. The size to be copied exceeds the actual size, copy size:" + count +
                           ", copied:" + progress +
                           ", read:" + (count - countToRead) +
                           ", in pipe:" + countInPipe);
                    countToRead = 0;
                } else {
                    countInPipe += t;
                    countToRead -= t;
                }
            }

            if (countInPipe > 0) {
                t = Os.splice(pipes[0], null, out, null, Math.min(countInPipe, COPY_CHECKPOINT_BYTES),
                              SPLICE_F_MOVE | SPLICE_F_MORE);
                // The data is already in the pipeline, so the return value will not be zero.
                // If it is 0, it means an error has occurred. So here use t<=0.
                if (t <= 0) {
                    Slog.e(TAG, "splice error, pipe --> fdOut, copy size:" + count +
                           ", copied:" + progress +
                           ", read:" + (count - countToRead) +
                           ", in pipe: " + countInPipe);
                    throw new ErrnoException("splice, pipe --> fdOut", EIO);
                } else {
                    progress += t;
                    checkpoint += t;
                    countInPipe -= t;
                }
            }

            if (checkpoint >= COPY_CHECKPOINT_BYTES) {
                if (signal != null) {
                    signal.throwIfCanceled();
                }
                if (executor != null && listener != null) {
                    final long progressSnapshot = progress;
                    executor.execute(() -> {
                        listener.onProgress(progressSnapshot);
                    });
                }
                checkpoint = 0;
            }
        }
        if (executor != null && listener != null) {
            final long progressSnapshot = progress;
            executor.execute(() -> {
                listener.onProgress(progressSnapshot);
            });
        }
        return progress;
    }

    /**
     * Requires both input and output to be a regular file.
     *
     * @hide
     */
    @VisibleForTesting
    public static long copyInternalSendfile(FileDescriptor in, FileDescriptor out, long count,
            CancellationSignal signal, Executor executor, ProgressListener listener)
            throws ErrnoException {
        long progress = 0;
        long checkpoint = 0;

        long t;
        while ((t = Os.sendfile(out, in, null, Math.min(count, COPY_CHECKPOINT_BYTES))) != 0) {
            progress += t;
            checkpoint += t;
            count -= t;

            if (checkpoint >= COPY_CHECKPOINT_BYTES) {
                if (signal != null) {
                    signal.throwIfCanceled();
                }
                if (executor != null && listener != null) {
                    final long progressSnapshot = progress;
                    executor.execute(() -> {
                        listener.onProgress(progressSnapshot);
                    });
                }
                checkpoint = 0;
            }
        }
        if (executor != null && listener != null) {
            final long progressSnapshot = progress;
            executor.execute(() -> {
                listener.onProgress(progressSnapshot);
            });
        }
        return progress;
    }

    /** {@hide} */
    @Deprecated
    @VisibleForTesting
    public static long copyInternalUserspace(FileDescriptor in, FileDescriptor out,
            ProgressListener listener, CancellationSignal signal, long count)
            throws IOException {
        return copyInternalUserspace(in, out, count, signal, Runnable::run, listener);
    }

    /** {@hide} */
    @VisibleForTesting
    public static long copyInternalUserspace(FileDescriptor in, FileDescriptor out, long count,
            CancellationSignal signal, Executor executor, ProgressListener listener)
            throws IOException {
        if (count != Long.MAX_VALUE) {
            return copyInternalUserspace(new SizedInputStream(new FileInputStream(in), count),
                    new FileOutputStream(out), signal, executor, listener);
        } else {
            return copyInternalUserspace(new FileInputStream(in),
                    new FileOutputStream(out), signal, executor, listener);
        }
    }

    /** {@hide} */
    @VisibleForTesting
    public static long copyInternalUserspace(InputStream in, OutputStream out,
            CancellationSignal signal, Executor executor, ProgressListener listener)
            throws IOException {
        long progress = 0;
        long checkpoint = 0;
        byte[] buffer = new byte[8192];

        int t;
        while ((t = in.read(buffer)) != -1) {
            out.write(buffer, 0, t);

            progress += t;
            checkpoint += t;

            if (checkpoint >= COPY_CHECKPOINT_BYTES) {
                if (signal != null) {
                    signal.throwIfCanceled();
                }
                if (executor != null && listener != null) {
                    final long progressSnapshot = progress;
                    executor.execute(() -> {
                        listener.onProgress(progressSnapshot);
                    });
                }
                checkpoint = 0;
            }
        }
        if (executor != null && listener != null) {
            final long progressSnapshot = progress;
            executor.execute(() -> {
                listener.onProgress(progressSnapshot);
            });
        }
        return progress;
    }

    /**
     * Check if a filename is "safe" (no metacharacters or spaces).
     * @param file  The file to check
     * @hide
     */
    @UnsupportedAppUsage
    public static boolean isFilenameSafe(File file) {
        // Note, we check whether it matches what's known to be safe,
        // rather than what's known to be unsafe.  Non-ASCII, control
        // characters, etc. are all unsafe by default.
        return NoImagePreloadHolder.SAFE_FILENAME_PATTERN.matcher(file.getPath()).matches();
    }

    /**
     * Read a text file into a String, optionally limiting the length.
     * @param file to read (will not seek, so things like /proc files are OK)
     * @param max length (positive for head, negative of tail, 0 for no limit)
     * @param ellipsis to add of the file was truncated (can be null)
     * @return the contents of the file, possibly truncated
     * @throws IOException if something goes wrong reading the file
     * @hide
     */
    @UnsupportedAppUsage
    public static String readTextFile(File file, int max, String ellipsis) throws IOException {
        InputStream input = new FileInputStream(file);
        // wrapping a BufferedInputStream around it because when reading /proc with unbuffered
        // input stream, bytes read not equal to buffer size is not necessarily the correct
        // indication for EOF; but it is true for BufferedInputStream due to its implementation.
        BufferedInputStream bis = new BufferedInputStream(input);
        try {
            long size = file.length();
            if (max > 0 || (size > 0 && max == 0)) {  // "head" mode: read the first N bytes
                if (size > 0 && (max == 0 || size < max)) max = (int) size;
                byte[] data = new byte[max + 1];
                int length = bis.read(data);
                if (length <= 0) return "";
                if (length <= max) return new String(data, 0, length);
                if (ellipsis == null) return new String(data, 0, max);
                return new String(data, 0, max) + ellipsis;
            } else if (max < 0) {  // "tail" mode: keep the last N
                int len;
                boolean rolled = false;
                byte[] last = null;
                byte[] data = null;
                do {
                    if (last != null) rolled = true;
                    byte[] tmp = last; last = data; data = tmp;
                    if (data == null) data = new byte[-max];
                    len = bis.read(data);
                } while (len == data.length);

                if (last == null && len <= 0) return "";
                if (last == null) return new String(data, 0, len);
                if (len > 0) {
                    rolled = true;
                    System.arraycopy(last, len, last, 0, last.length - len);
                    System.arraycopy(data, 0, last, last.length - len, len);
                }
                if (ellipsis == null || !rolled) return new String(last);
                return ellipsis + new String(last);
            } else {  // "cat" mode: size unknown, read it all in streaming fashion
                ByteArrayOutputStream contents = new ByteArrayOutputStream();
                int len;
                byte[] data = new byte[1024];
                do {
                    len = bis.read(data);
                    if (len > 0) contents.write(data, 0, len);
                } while (len == data.length);
                return contents.toString();
            }
        } finally {
            bis.close();
            input.close();
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static void stringToFile(File file, String string) throws IOException {
        stringToFile(file.getAbsolutePath(), string);
    }

    /**
     * Writes the bytes given in {@code content} to the file whose absolute path
     * is {@code filename}.
     *
     * @hide
     */
    public static void bytesToFile(String filename, byte[] content) throws IOException {
        if (filename.startsWith("/proc/")) {
            final int oldMask = StrictMode.allowThreadDiskWritesMask();
            try (FileOutputStream fos = new FileOutputStream(filename)) {
                fos.write(content);
            } finally {
                StrictMode.setThreadPolicyMask(oldMask);
            }
        } else {
            try (FileOutputStream fos = new FileOutputStream(filename)) {
                fos.write(content);
            }
        }
    }

    /**
     * Writes string to file. Basically same as "echo -n $string > $filename"
     *
     * @param filename
     * @param string
     * @throws IOException
     * @hide
     */
    @UnsupportedAppUsage
    public static void stringToFile(String filename, String string) throws IOException {
        bytesToFile(filename, string.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Computes the checksum of a file using the CRC32 checksum routine. The
     * value of the checksum is returned.
     *
     * @param file the file to checksum, must not be null
     * @return the checksum value or an exception is thrown.
     * @deprecated this is a weak hashing algorithm, and should not be used due
     *             to its potential for collision.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Deprecated
    public static long checksumCrc32(File file) throws FileNotFoundException, IOException {
        CRC32 checkSummer = new CRC32();
        CheckedInputStream cis = null;

        try {
            cis = new CheckedInputStream( new FileInputStream(file), checkSummer);
            byte[] buf = new byte[128];
            while(cis.read(buf) >= 0) {
                // Just read for checksum to get calculated.
            }
            return checkSummer.getValue();
        } finally {
            if (cis != null) {
                try {
                    cis.close();
                } catch (IOException e) {
                }
            }
        }
    }

    /**
     * Compute the digest of the given file using the requested algorithm.
     *
     * @param algorithm Any valid algorithm accepted by
     *            {@link MessageDigest#getInstance(String)}.
     * @hide
     */
    @TestApi
    @NonNull
    public static byte[] digest(@NonNull File file, @NonNull String algorithm)
            throws IOException, NoSuchAlgorithmException {
        try (FileInputStream in = new FileInputStream(file)) {
            return digest(in, algorithm);
        }
    }

    /**
     * Compute the digest of the given file using the requested algorithm.
     *
     * @param algorithm Any valid algorithm accepted by
     *            {@link MessageDigest#getInstance(String)}.
     * @hide
     */
    @TestApi
    @NonNull
    public static byte[] digest(@NonNull InputStream in, @NonNull String algorithm)
            throws IOException, NoSuchAlgorithmException {
        // TODO: implement kernel optimizations
        return digestInternalUserspace(in, algorithm);
    }

    /**
     * Compute the digest of the given file using the requested algorithm.
     *
     * @param algorithm Any valid algorithm accepted by
     *            {@link MessageDigest#getInstance(String)}.
     * @hide
     */
    public static byte[] digest(FileDescriptor fd, String algorithm)
            throws IOException, NoSuchAlgorithmException {
        // TODO: implement kernel optimizations
        return digestInternalUserspace(new FileInputStream(fd), algorithm);
    }

    private static byte[] digestInternalUserspace(InputStream in, String algorithm)
            throws IOException, NoSuchAlgorithmException {
        final MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (DigestInputStream digestStream = new DigestInputStream(in, digest)) {
            final byte[] buffer = new byte[8192];
            while (digestStream.read(buffer) != -1) {
            }
        }
        return digest.digest();
    }

    /**
     * Delete older files in a directory until only those matching the given
     * constraints remain.
     *
     * @param minCount Always keep at least this many files.
     * @param minAgeMs Always keep files younger than this age, in milliseconds.
     * @return if any files were deleted.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static boolean deleteOlderFiles(File dir, int minCount, long minAgeMs) {
        if (minCount < 0 || minAgeMs < 0) {
            throw new IllegalArgumentException("Constraints must be positive or 0");
        }

        final File[] files = dir.listFiles();
        if (files == null) return false;

        // Sort with newest files first
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File lhs, File rhs) {
                return Long.compare(rhs.lastModified(), lhs.lastModified());
            }
        });

        // Keep at least minCount files
        boolean deleted = false;
        for (int i = minCount; i < files.length; i++) {
            final File file = files[i];

            // Keep files newer than minAgeMs
            final long age = System.currentTimeMillis() - file.lastModified();
            if (age > minAgeMs) {
                if (file.delete()) {
                    Log.d(TAG, "Deleted old file " + file);
                    deleted = true;
                }
            }
        }
        return deleted;
    }

    /**
     * Test if a file lives under the given directory, either as a direct child
     * or a distant grandchild.
     * <p>
     * Both files <em>must</em> have been resolved using
     * {@link File#getCanonicalFile()} to avoid symlink or path traversal
     * attacks.
     *
     * @hide
     */
    public static boolean contains(File[] dirs, File file) {
        for (File dir : dirs) {
            if (contains(dir, file)) {
                return true;
            }
        }
        return false;
    }

    /** {@hide} */
    public static boolean contains(Collection<File> dirs, File file) {
        for (File dir : dirs) {
            if (contains(dir, file)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Test if a file lives under the given directory, either as a direct child
     * or a distant grandchild.
     * <p>
     * Both files <em>must</em> have been resolved using
     * {@link File#getCanonicalFile()} to avoid symlink or path traversal
     * attacks.
     *
     * @hide
     */
    @TestApi
    public static boolean contains(File dir, File file) {
        if (dir == null || file == null) return false;
        return contains(dir.getAbsolutePath(), file.getAbsolutePath());
    }

    /**
     * Test if a file lives under the given directory, either as a direct child
     * or a distant grandchild.
     * <p>
     * Both files <em>must</em> have been resolved using
     * {@link File#getCanonicalFile()} to avoid symlink or path traversal
     * attacks.
     *
     * @hide
     */
    public static boolean contains(String dirPath, String filePath) {
        if (dirPath.equals(filePath)) {
            return true;
        }
        if (!dirPath.endsWith("/")) {
            dirPath += "/";
        }
        return filePath.startsWith(dirPath);
    }

    /** {@hide} */
    public static boolean deleteContentsAndDir(File dir) {
        if (deleteContents(dir)) {
            return dir.delete();
        } else {
            return false;
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static boolean deleteContents(File dir) {
        File[] files = dir.listFiles();
        boolean success = true;
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    success &= deleteContents(file);
                }
                if (!file.delete()) {
                    Log.w(TAG, "Failed to delete " + file);
                    success = false;
                }
            }
        }
        return success;
    }

    private static boolean isValidExtFilenameChar(char c) {
        switch (c) {
            case '\0':
            case '/':
                return false;
            default:
                return true;
        }
    }

    /**
     * Check if given filename is valid for an ext4 filesystem.
     *
     * @hide
     */
    public static boolean isValidExtFilename(String name) {
        return (name != null) && name.equals(buildValidExtFilename(name));
    }

    /**
     * Mutate the given filename to make it valid for an ext4 filesystem,
     * replacing any invalid characters with "_".
     *
     * @hide
     */
    public static String buildValidExtFilename(String name) {
        if (TextUtils.isEmpty(name) || ".".equals(name) || "..".equals(name)) {
            return "(invalid)";
        }
        final StringBuilder res = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (isValidExtFilenameChar(c)) {
                res.append(c);
            } else {
                res.append('_');
            }
        }
        trimFilename(res, 255);
        return res.toString();
    }

    private static boolean isValidFatFilenameChar(char c) {
        if ((0x00 <= c && c <= 0x1f)) {
            return false;
        }
        switch (c) {
            case '"':
            case '*':
            case '/':
            case ':':
            case '<':
            case '>':
            case '?':
            case '\\':
            case '|':
            case 0x7F:
                return false;
            default:
                return true;
        }
    }

    /**
     * Check if given filename is valid for a FAT filesystem.
     *
     * @hide
     */
    public static boolean isValidFatFilename(String name) {
        return (name != null) && name.equals(buildValidFatFilename(name));
    }

    /**
     * Mutate the given filename to make it valid for a FAT filesystem,
     * replacing any invalid characters with "_".
     *
     * @hide
     */
    public static String buildValidFatFilename(String name) {
        if (TextUtils.isEmpty(name) || ".".equals(name) || "..".equals(name)) {
            return "(invalid)";
        }
        final StringBuilder res = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (isValidFatFilenameChar(c)) {
                res.append(c);
            } else {
                res.append('_');
            }
        }
        // Even though vfat allows 255 UCS-2 chars, we might eventually write to
        // ext4 through a FUSE layer, so use that limit.
        trimFilename(res, 255);
        return res.toString();
    }

    /** {@hide} */
    @VisibleForTesting
    public static String trimFilename(String str, int maxBytes) {
        final StringBuilder res = new StringBuilder(str);
        trimFilename(res, maxBytes);
        return res.toString();
    }

    /** {@hide} */
    private static void trimFilename(StringBuilder res, int maxBytes) {
        byte[] raw = res.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length > maxBytes) {
            maxBytes -= 3;
            while (raw.length > maxBytes) {
                res.deleteCharAt(res.length() / 2);
                raw = res.toString().getBytes(StandardCharsets.UTF_8);
            }
            res.insert(res.length() / 2, "...");
        }
    }

    /** {@hide} */
    public static String rewriteAfterRename(File beforeDir, File afterDir, String path) {
        if (path == null) return null;
        final File result = rewriteAfterRename(beforeDir, afterDir, new File(path));
        return (result != null) ? result.getAbsolutePath() : null;
    }

    /** {@hide} */
    public static String[] rewriteAfterRename(File beforeDir, File afterDir, String[] paths) {
        if (paths == null) return null;
        final String[] result = new String[paths.length];
        for (int i = 0; i < paths.length; i++) {
            result[i] = rewriteAfterRename(beforeDir, afterDir, paths[i]);
        }
        return result;
    }

    /**
     * Given a path under the "before" directory, rewrite it to live under the
     * "after" directory. For example, {@code /before/foo/bar.txt} would become
     * {@code /after/foo/bar.txt}.
     *
     * @hide
     */
    public static File rewriteAfterRename(File beforeDir, File afterDir, File file) {
        if (file == null || beforeDir == null || afterDir == null) return null;
        if (contains(beforeDir, file)) {
            final String splice = file.getAbsolutePath().substring(
                    beforeDir.getAbsolutePath().length());
            return new File(afterDir, splice);
        }
        return null;
    }

    /** {@hide} */
    private static File buildUniqueFileWithExtension(File parent, String name, String ext)
            throws FileNotFoundException {
        File file = buildFile(parent, name, ext);

        // If conflicting file, try adding counter suffix
        int n = 0;
        while (file.exists()) {
            if (n++ >= 32) {
                throw new FileNotFoundException("Failed to create unique file");
            }
            file = buildFile(parent, name + " (" + n + ")", ext);
        }

        return file;
    }

    /**
     * Generates a unique file name under the given parent directory. If the display name doesn't
     * have an extension that matches the requested MIME type, the default extension for that MIME
     * type is appended. If a file already exists, the name is appended with a numerical value to
     * make it unique.
     *
     * For example, the display name 'example' with 'text/plain' MIME might produce
     * 'example.txt' or 'example (1).txt', etc.
     *
     * @throws FileNotFoundException
     * @hide
     */
    public static File buildUniqueFile(File parent, String mimeType, String displayName)
            throws FileNotFoundException {
        final String[] parts = splitFileName(mimeType, displayName);
        return buildUniqueFileWithExtension(parent, parts[0], parts[1]);
    }

    /** {@hide} */
    public static File buildNonUniqueFile(File parent, String mimeType, String displayName) {
        final String[] parts = splitFileName(mimeType, displayName);
        return buildFile(parent, parts[0], parts[1]);
    }

    /**
     * Generates a unique file name under the given parent directory, keeping
     * any extension intact.
     *
     * @hide
     */
    public static File buildUniqueFile(File parent, String displayName)
            throws FileNotFoundException {
        final String name;
        final String ext;

        // Extract requested extension from display name
        final int lastDot = displayName.lastIndexOf('.');
        if (lastDot >= 0) {
            name = displayName.substring(0, lastDot);
            ext = displayName.substring(lastDot + 1);
        } else {
            name = displayName;
            ext = null;
        }

        return buildUniqueFileWithExtension(parent, name, ext);
    }

    /**
     * Splits file name into base name and extension.
     * If the display name doesn't have an extension that matches the requested MIME type, the
     * extension is regarded as a part of filename and default extension for that MIME type is
     * appended.
     *
     * @hide
     */
    public static String[] splitFileName(String mimeType, String displayName) {
        String name;
        String ext;

        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            name = displayName;
            ext = null;
        } else {
            String mimeTypeFromExt;

            // Extract requested extension from display name
            final int lastDot = displayName.lastIndexOf('.');
            if (lastDot >= 0) {
                name = displayName.substring(0, lastDot);
                ext = displayName.substring(lastDot + 1);
                mimeTypeFromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        ext.toLowerCase());
            } else {
                name = displayName;
                ext = null;
                mimeTypeFromExt = null;
            }

            if (mimeTypeFromExt == null) {
                mimeTypeFromExt = ContentResolver.MIME_TYPE_DEFAULT;
            }

            final String extFromMimeType;
            if (ContentResolver.MIME_TYPE_DEFAULT.equals(mimeType)) {
                extFromMimeType = null;
            } else {
                extFromMimeType = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            }

            if (Objects.equals(mimeType, mimeTypeFromExt) || Objects.equals(ext, extFromMimeType)) {
                // Extension maps back to requested MIME type; allow it
            } else {
                // No match; insist that create file matches requested MIME
                name = displayName;
                ext = extFromMimeType;
            }
        }

        if (ext == null) {
            ext = "";
        }

        return new String[] { name, ext };
    }

    /** {@hide} */
    private static File buildFile(File parent, String name, String ext) {
        if (TextUtils.isEmpty(ext)) {
            return new File(parent, name);
        } else {
            return new File(parent, name + "." + ext);
        }
    }

    /** {@hide} */
    public static @NonNull String[] listOrEmpty(@Nullable File dir) {
        return (dir != null) ? ArrayUtils.defeatNullable(dir.list())
                : EmptyArray.STRING;
    }

    /** {@hide} */
    public static @NonNull File[] listFilesOrEmpty(@Nullable File dir) {
        return (dir != null) ? ArrayUtils.defeatNullable(dir.listFiles())
                : ArrayUtils.EMPTY_FILE;
    }

    /** {@hide} */
    public static @NonNull File[] listFilesOrEmpty(@Nullable File dir, FilenameFilter filter) {
        return (dir != null) ? ArrayUtils.defeatNullable(dir.listFiles(filter))
                : ArrayUtils.EMPTY_FILE;
    }

    /** {@hide} */
    public static @Nullable File newFileOrNull(@Nullable String path) {
        return (path != null) ? new File(path) : null;
    }

    /**
     * Creates a directory with name {@code name} under an existing directory {@code baseDir} if it
     * doesn't exist already. Returns a {@code File} object representing the directory if it exists
     * and {@code null} if not.
     *
     * @hide
     */
    public static @Nullable File createDir(File baseDir, String name) {
        final File dir = new File(baseDir, name);

        return createDir(dir) ? dir : null;
    }

    /**
     * Ensure the given directory exists, creating it if needed. This method is threadsafe.
     *
     * @return false if the directory doesn't exist and couldn't be created
     *
     * @hide
     */
    public static boolean createDir(File dir) {
        if (dir.mkdir()) {
            return true;
        }

        if (dir.exists()) {
            return dir.isDirectory();
        }

        return false;
    }

    /**
     * Round the given size of a storage device to a nice round power-of-two
     * value, such as 256MB or 32GB. This avoids showing weird values like
     * "29.5GB" in UI.
     * Round ranges:
     * ...
     * (128 GB; 256 GB]   -> 256 GB
     * (256 GB; 512 GB]   -> 512 GB
     * (512 GB; 1000 GB]  -> 1000 GB
     * (1000 GB; 2000 GB] -> 2000 GB
     * ...
     * etc
     *
     * @hide
     */
    public static long roundStorageSize(long size) {
        long val = 1;
        long pow = 1;
        while ((val * pow) < size) {
            val <<= 1;
            if (val > 512) {
                val = 1;
                pow *= 1000;
            }
        }

        Log.d(TAG, String.format("Rounded bytes from %d to %d", size, val * pow));
        return val * pow;
    }

    private static long toBytes(long value, String unit) {
        unit = unit.toUpperCase();

        if ("B".equals(unit)) {
            return value;
        }

        if ("K".equals(unit) || "KB".equals(unit)) {
            return DataUnit.KILOBYTES.toBytes(value);
        }

        if ("M".equals(unit) || "MB".equals(unit)) {
            return DataUnit.MEGABYTES.toBytes(value);
        }

        if ("G".equals(unit) || "GB".equals(unit)) {
            return DataUnit.GIGABYTES.toBytes(value);
        }

        if ("KI".equals(unit) || "KIB".equals(unit)) {
            return DataUnit.KIBIBYTES.toBytes(value);
        }

        if ("MI".equals(unit) || "MIB".equals(unit)) {
            return DataUnit.MEBIBYTES.toBytes(value);
        }

        if ("GI".equals(unit) || "GIB".equals(unit)) {
            return DataUnit.GIBIBYTES.toBytes(value);
        }

        return Long.MIN_VALUE;
    }

    /**
     * @param fmtSize The string that contains the size to be parsed. The
     *   expected format is:
     *
     *   <p>"^((\\s*[-+]?[0-9]+)\\s*(B|K|KB|M|MB|G|GB|Ki|KiB|Mi|MiB|Gi|GiB)\\s*)$"
     *
     *   <p>For example: 10Kb, 500GiB, 100mb. The unit is not case sensitive.
     *
     * @return the size in bytes. If {@code fmtSize} has invalid format, it
     *   returns {@link Long#MIN_VALUE}.
     * @hide
     */
    public static long parseSize(@Nullable String fmtSize) {
        if (fmtSize == null || fmtSize.isBlank()) {
            return Long.MIN_VALUE;
        }

        int sign = 1;
        fmtSize = fmtSize.trim();
        char first = fmtSize.charAt(0);
        if (first == '-' ||  first == '+') {
            if (first == '-') {
                sign = -1;
            }

            fmtSize = fmtSize.substring(1);
        }

        int index = 0;
        // Find the last index of the value in fmtSize.
        while (index < fmtSize.length() && Character.isDigit(fmtSize.charAt(index))) {
            index++;
        }

        // Check if number and units are present.
        if (index == 0 || index == fmtSize.length()) {
            return Long.MIN_VALUE;
        }

        long value = sign * Long.valueOf(fmtSize.substring(0, index));
        String unit = fmtSize.substring(index).trim();

        return toBytes(value, unit);
    }

    /**
     * Closes the given object quietly, ignoring any checked exceptions. Does
     * nothing if the given object is {@code null}.
     *
     * @deprecated This method may suppress potentially significant exceptions, particularly when
     *   closing writable resources. With a writable resource, a failure thrown from {@code close()}
     *   should be considered as significant as a failure thrown from a write method because it may
     *   indicate a failure to flush bytes to the underlying resource.
     */
    @Deprecated
    public static void closeQuietly(@Nullable AutoCloseable closeable) {
        IoUtils.closeQuietly(closeable);
    }

    /**
     * Closes the given object quietly, ignoring any checked exceptions. Does
     * nothing if the given object is {@code null}.
     *
     * @deprecated This method may suppress potentially significant exceptions, particularly when
     *   closing writable resources. With a writable resource, a failure thrown from {@code close()}
     *   should be considered as significant as a failure thrown from a write method because it may
     *   indicate a failure to flush bytes to the underlying resource.
     */
    @Deprecated
    public static void closeQuietly(@Nullable FileDescriptor fd) {
        IoUtils.closeQuietly(fd);
    }

    /** {@hide} */
    public static int translateModeStringToPosix(String mode) {
        // Quick check for invalid chars
        for (int i = 0; i < mode.length(); i++) {
            switch (mode.charAt(i)) {
                case 'r':
                case 'w':
                case 't':
                case 'a':
                    break;
                default:
                    throw new IllegalArgumentException("Bad mode: " + mode);
            }
        }

        int res = 0;
        if (mode.startsWith("rw")) {
            res = O_RDWR | O_CREAT;
        } else if (mode.startsWith("w")) {
            res = O_WRONLY | O_CREAT;
        } else if (mode.startsWith("r")) {
            res = O_RDONLY;
        } else {
            throw new IllegalArgumentException("Bad mode: " + mode);
        }
        if (mode.indexOf('t') != -1) {
            res |= O_TRUNC;
        }
        if (mode.indexOf('a') != -1) {
            res |= O_APPEND;
        }
        return res;
    }

    /** {@hide} */
    public static String translateModePosixToString(int mode) {
        String res = "";
        if ((mode & O_ACCMODE) == O_RDWR) {
            res = "rw";
        } else if ((mode & O_ACCMODE) == O_WRONLY) {
            res = "w";
        } else if ((mode & O_ACCMODE) == O_RDONLY) {
            res = "r";
        } else {
            throw new IllegalArgumentException("Bad mode: " + mode);
        }
        if ((mode & O_TRUNC) == O_TRUNC) {
            res += "t";
        }
        if ((mode & O_APPEND) == O_APPEND) {
            res += "a";
        }
        return res;
    }

    /** {@hide} */
    public static int translateModePosixToPfd(int mode) {
        int res = 0;
        if ((mode & O_ACCMODE) == O_RDWR) {
            res = MODE_READ_WRITE;
        } else if ((mode & O_ACCMODE) == O_WRONLY) {
            res = MODE_WRITE_ONLY;
        } else if ((mode & O_ACCMODE) == O_RDONLY) {
            res = MODE_READ_ONLY;
        } else {
            throw new IllegalArgumentException("Bad mode: " + mode);
        }
        if ((mode & O_CREAT) == O_CREAT) {
            res |= MODE_CREATE;
        }
        if ((mode & O_TRUNC) == O_TRUNC) {
            res |= MODE_TRUNCATE;
        }
        if ((mode & O_APPEND) == O_APPEND) {
            res |= MODE_APPEND;
        }
        return res;
    }

    /** {@hide} */
    public static int translateModePfdToPosix(int mode) {
        int res = 0;
        if ((mode & MODE_READ_WRITE) == MODE_READ_WRITE) {
            res = O_RDWR;
        } else if ((mode & MODE_WRITE_ONLY) == MODE_WRITE_ONLY) {
            res = O_WRONLY;
        } else if ((mode & MODE_READ_ONLY) == MODE_READ_ONLY) {
            res = O_RDONLY;
        } else {
            throw new IllegalArgumentException("Bad mode: " + mode);
        }
        if ((mode & MODE_CREATE) == MODE_CREATE) {
            res |= O_CREAT;
        }
        if ((mode & MODE_TRUNCATE) == MODE_TRUNCATE) {
            res |= O_TRUNC;
        }
        if ((mode & MODE_APPEND) == MODE_APPEND) {
            res |= O_APPEND;
        }
        return res;
    }

    /** {@hide} */
    public static int translateModeAccessToPosix(int mode) {
        if (mode == F_OK) {
            // There's not an exact mapping, so we attempt a read-only open to
            // determine if a file exists
            return O_RDONLY;
        } else if ((mode & (R_OK | W_OK)) == (R_OK | W_OK)) {
            return O_RDWR;
        } else if ((mode & R_OK) == R_OK) {
            return O_RDONLY;
        } else if ((mode & W_OK) == W_OK) {
            return O_WRONLY;
        } else {
            throw new IllegalArgumentException("Bad mode: " + mode);
        }
    }

    /** {@hide} */
    @VisibleForTesting
    public static ParcelFileDescriptor convertToModernFd(FileDescriptor fd) {
        Context context = AppGlobals.getInitialApplication();
        if (UserHandle.getAppId(Process.myUid()) == getMediaProviderAppId(context)) {
            // Never convert modern fd for MediaProvider, because this requires
            // MediaStore#scanFile and can cause infinite loops when MediaProvider scans
            return null;
        }

        try (ParcelFileDescriptor dupFd = ParcelFileDescriptor.dup(fd)) {
            return MediaStore.getOriginalMediaFormatFileDescriptor(context, dupFd);
        } catch (Exception e) {
            // Ignore error
            return null;
        }
    }

    private static int getMediaProviderAppId(Context context) {
        if (sMediaProviderAppId != -1) {
            return sMediaProviderAppId;
        }

        PackageManager pm = context.getPackageManager();
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(
                MediaStore.AUTHORITY, PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                | PackageManager.MATCH_SYSTEM_ONLY);
        if (provider == null) {
            return -1;
        }

        sMediaProviderAppId = UserHandle.getAppId(provider.applicationInfo.uid);
        return sMediaProviderAppId;
    }

    /** {@hide} */
    @VisibleForTesting
    public static class MemoryPipe extends Thread implements AutoCloseable {
        private final FileDescriptor[] pipe;
        private final byte[] data;
        private final boolean sink;

        private MemoryPipe(byte[] data, boolean sink) throws IOException {
            try {
                this.pipe = Os.pipe();
            } catch (ErrnoException e) {
                throw e.rethrowAsIOException();
            }
            this.data = data;
            this.sink = sink;
        }

        private MemoryPipe startInternal() {
            super.start();
            return this;
        }

        public static MemoryPipe createSource(byte[] data) throws IOException {
            return new MemoryPipe(data, false).startInternal();
        }

        public static MemoryPipe createSink(byte[] data) throws IOException {
            return new MemoryPipe(data, true).startInternal();
        }

        public FileDescriptor getFD() {
            return sink ? pipe[1] : pipe[0];
        }

        public FileDescriptor getInternalFD() {
            return sink ? pipe[0] : pipe[1];
        }

        @Override
        public void run() {
            final FileDescriptor fd = getInternalFD();
            try {
                int i = 0;
                while (i < data.length) {
                    if (sink) {
                        i += Os.read(fd, data, i, data.length - i);
                    } else {
                        i += Os.write(fd, data, i, data.length - i);
                    }
                }
            } catch (IOException | ErrnoException e) {
                // Ignored
            } finally {
                if (sink) {
                    SystemClock.sleep(TimeUnit.SECONDS.toMillis(1));
                }
                IoUtils.closeQuietly(fd);
            }
        }

        @Override
        public void close() throws Exception {
            IoUtils.closeQuietly(getFD());
        }
    }
}
