/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.util;

import android.annotation.NonNull;
import android.os.FileUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import libcore.io.IoUtils;

/**
 * Utility that rotates files over time, similar to {@code logrotate}. There is
 * a single "active" file, which is periodically rotated into historical files,
 * and eventually deleted entirely. Files are stored under a specific directory
 * with a well-known prefix.
 * <p>
 * Instead of manipulating files directly, users implement interfaces that
 * perform operations on {@link InputStream} and {@link OutputStream}. This
 * enables atomic rewriting of file contents in
 * {@link #rewriteActive(Rewriter, long)}.
 * <p>
 * Users must periodically call {@link #maybeRotate(long)} to perform actual
 * rotation. Not inherently thread safe.
 *
 * @hide
 */
public class FileRotator {
    private static final String TAG = "FileRotator";
    private static final boolean LOGD = false;

    private final File mBasePath;
    private final String mPrefix;
    private final long mRotateAgeMillis;
    private final long mDeleteAgeMillis;

    private static final String SUFFIX_BACKUP = ".backup";
    private static final String SUFFIX_NO_BACKUP = ".no_backup";

    // TODO: provide method to append to active file

    /**
     * External class that reads data from a given {@link InputStream}. May be
     * called multiple times when reading rotated data.
     */
    public interface Reader {
        public void read(InputStream in) throws IOException;
    }

    /**
     * External class that writes data to a given {@link OutputStream}.
     */
    public interface Writer {
        public void write(OutputStream out) throws IOException;
    }

    /**
     * External class that reads existing data from given {@link InputStream},
     * then writes any modified data to {@link OutputStream}.
     */
    public interface Rewriter extends Reader, Writer {
        public void reset();
        public boolean shouldWrite();
    }

    /**
     * Create a file rotator.
     *
     * @param basePath Directory under which all files will be placed.
     * @param prefix Filename prefix used to identify this rotator.
     * @param rotateAgeMillis Age in milliseconds beyond which an active file
     *            may be rotated into a historical file.
     * @param deleteAgeMillis Age in milliseconds beyond which a rotated file
     *            may be deleted.
     */
    public FileRotator(File basePath, String prefix, long rotateAgeMillis, long deleteAgeMillis) {
        mBasePath = Objects.requireNonNull(basePath);
        mPrefix = Objects.requireNonNull(prefix);
        mRotateAgeMillis = rotateAgeMillis;
        mDeleteAgeMillis = deleteAgeMillis;

        // ensure that base path exists
        mBasePath.mkdirs();

        // recover any backup files
        for (String name : mBasePath.list()) {
            if (!name.startsWith(mPrefix)) continue;

            if (name.endsWith(SUFFIX_BACKUP)) {
                if (LOGD) Log.d(TAG, "recovering " + name);

                final File backupFile = new File(mBasePath, name);
                final File file = new File(
                        mBasePath, name.substring(0, name.length() - SUFFIX_BACKUP.length()));

                // write failed with backup; recover last file
                backupFile.renameTo(file);

            } else if (name.endsWith(SUFFIX_NO_BACKUP)) {
                if (LOGD) Log.d(TAG, "recovering " + name);

                final File noBackupFile = new File(mBasePath, name);
                final File file = new File(
                        mBasePath, name.substring(0, name.length() - SUFFIX_NO_BACKUP.length()));

                // write failed without backup; delete both
                noBackupFile.delete();
                file.delete();
            }
        }
    }

    /**
     * Delete all files managed by this rotator.
     */
    public void deleteAll() {
        final FileInfo info = new FileInfo(mPrefix);
        for (String name : mBasePath.list()) {
            if (info.parse(name)) {
                // delete each file that matches parser
                new File(mBasePath, name).delete();
            }
        }
    }

    /**
     * Dump all files managed by this rotator for debugging purposes.
     */
    public void dumpAll(OutputStream os) throws IOException {
        final ZipOutputStream zos = new ZipOutputStream(os);
        try {
            final FileInfo info = new FileInfo(mPrefix);
            for (String name : mBasePath.list()) {
                if (info.parse(name)) {
                    final ZipEntry entry = new ZipEntry(name);
                    zos.putNextEntry(entry);

                    final File file = new File(mBasePath, name);
                    final FileInputStream is = new FileInputStream(file);
                    try {
                        FileUtils.copy(is, zos);
                    } finally {
                        IoUtils.closeQuietly(is);
                    }

                    zos.closeEntry();
                }
            }
        } finally {
            IoUtils.closeQuietly(zos);
        }
    }

    /**
     * Process currently active file, first reading any existing data, then
     * writing modified data. Maintains a backup during write, which is restored
     * if the write fails.
     */
    public void rewriteActive(Rewriter rewriter, long currentTimeMillis)
            throws IOException {
        final String activeName = getActiveName(currentTimeMillis);
        rewriteSingle(rewriter, activeName);
    }

    @Deprecated
    public void combineActive(final Reader reader, final Writer writer, long currentTimeMillis)
            throws IOException {
        rewriteActive(new Rewriter() {
            @Override
            public void reset() {
                // ignored
            }

            @Override
            public void read(InputStream in) throws IOException {
                reader.read(in);
            }

            @Override
            public boolean shouldWrite() {
                return true;
            }

            @Override
            public void write(OutputStream out) throws IOException {
                writer.write(out);
            }
        }, currentTimeMillis);
    }

    /**
     * Process all files managed by this rotator, usually to rewrite historical
     * data. Each file is processed atomically.
     */
    public void rewriteAll(Rewriter rewriter) throws IOException {
        final FileInfo info = new FileInfo(mPrefix);
        for (String name : mBasePath.list()) {
            if (!info.parse(name)) continue;

            // process each file that matches parser
            rewriteSingle(rewriter, name);
        }
    }

    /**
     * Process a single file atomically, first reading any existing data, then
     * writing modified data. Maintains a backup during write, which is restored
     * if the write fails.
     */
    private void rewriteSingle(Rewriter rewriter, String name) throws IOException {
        if (LOGD) Log.d(TAG, "rewriting " + name);

        final File file = new File(mBasePath, name);
        final File backupFile;

        rewriter.reset();

        if (file.exists()) {
            // read existing data
            readFile(file, rewriter);

            // skip when rewriter has nothing to write
            if (!rewriter.shouldWrite()) return;

            // backup existing data during write
            backupFile = new File(mBasePath, name + SUFFIX_BACKUP);
            file.renameTo(backupFile);

            try {
                writeFile(file, rewriter);

                // write success, delete backup
                backupFile.delete();
            } catch (Throwable t) {
                // write failed, delete file and restore backup
                file.delete();
                backupFile.renameTo(file);
                throw rethrowAsIoException(t);
            }

        } else {
            // create empty backup during write
            backupFile = new File(mBasePath, name + SUFFIX_NO_BACKUP);
            backupFile.createNewFile();

            try {
                writeFile(file, rewriter);

                // write success, delete empty backup
                backupFile.delete();
            } catch (Throwable t) {
                // write failed, delete file and empty backup
                file.delete();
                backupFile.delete();
                throw rethrowAsIoException(t);
            }
        }
    }

    /**
     * Process a single file atomically, with the given start and end timestamps.
     * If a file with these exact start and end timestamps does not exist, a new
     * empty file will be written.
     */
    public void rewriteSingle(@NonNull Rewriter rewriter, long startTimeMillis, long endTimeMillis)
            throws IOException {
        final FileInfo info = new FileInfo(mPrefix);

        info.startMillis = startTimeMillis;
        info.endMillis = endTimeMillis;
        rewriteSingle(rewriter, info.build());
    }

    /**
     * Read any rotated data that overlap the requested time range.
     */
    public void readMatching(Reader reader, long matchStartMillis, long matchEndMillis)
            throws IOException {
        final FileInfo info = new FileInfo(mPrefix);
        for (String name : mBasePath.list()) {
            if (!info.parse(name)) continue;

            // read file when it overlaps
            if (info.startMillis <= matchEndMillis && matchStartMillis <= info.endMillis) {
                if (LOGD) Log.d(TAG, "reading matching " + name);

                final File file = new File(mBasePath, name);
                readFile(file, reader);
            }
        }
    }

    /**
     * Return the currently active file, which may not exist yet.
     */
    private String getActiveName(long currentTimeMillis) {
        String oldestActiveName = null;
        long oldestActiveStart = Long.MAX_VALUE;

        final FileInfo info = new FileInfo(mPrefix);
        for (String name : mBasePath.list()) {
            if (!info.parse(name)) continue;

            // pick the oldest active file which covers current time
            if (info.isActive() && info.startMillis < currentTimeMillis
                    && info.startMillis < oldestActiveStart) {
                oldestActiveName = name;
                oldestActiveStart = info.startMillis;
            }
        }

        if (oldestActiveName != null) {
            return oldestActiveName;
        } else {
            // no active file found above; create one starting now
            info.startMillis = currentTimeMillis;
            info.endMillis = Long.MAX_VALUE;
            return info.build();
        }
    }

    /**
     * Examine all files managed by this rotator, renaming or deleting if their
     * age matches the configured thresholds.
     */
    public void maybeRotate(long currentTimeMillis) {
        final long rotateBefore = currentTimeMillis - mRotateAgeMillis;
        final long deleteBefore = currentTimeMillis - mDeleteAgeMillis;

        final FileInfo info = new FileInfo(mPrefix);
        String[] baseFiles = mBasePath.list();
        if (baseFiles == null) {
            return;
        }

        for (String name : baseFiles) {
            if (!info.parse(name)) continue;

            if (info.isActive()) {
                if (info.startMillis <= rotateBefore) {
                    // found active file; rotate if old enough
                    if (LOGD) Log.d(TAG, "rotating " + name);

                    info.endMillis = currentTimeMillis;

                    final File file = new File(mBasePath, name);
                    final File destFile = new File(mBasePath, info.build());
                    file.renameTo(destFile);
                }
            } else if (info.endMillis <= deleteBefore) {
                // found rotated file; delete if old enough
                if (LOGD) Log.d(TAG, "deleting " + name);

                final File file = new File(mBasePath, name);
                file.delete();
            }
        }
    }

    private static void readFile(File file, Reader reader) throws IOException {
        final FileInputStream fis = new FileInputStream(file);
        final BufferedInputStream bis = new BufferedInputStream(fis);
        try {
            reader.read(bis);
        } finally {
            IoUtils.closeQuietly(bis);
        }
    }

    private static void writeFile(File file, Writer writer) throws IOException {
        final FileOutputStream fos = new FileOutputStream(file);
        final BufferedOutputStream bos = new BufferedOutputStream(fos);
        try {
            writer.write(bos);
            bos.flush();
        } finally {
            try {
                fos.getFD().sync();
            } catch (IOException e) {
            }
            IoUtils.closeQuietly(bos);
        }
    }

    private static IOException rethrowAsIoException(Throwable t) throws IOException {
        if (t instanceof IOException) {
            throw (IOException) t;
        } else {
            throw new IOException(t.getMessage(), t);
        }
    }

    /**
     * Details for a rotated file, either parsed from an existing filename, or
     * ready to be built into a new filename.
     */
    private static class FileInfo {
        public final String prefix;

        public long startMillis;
        public long endMillis;

        public FileInfo(String prefix) {
            this.prefix = Objects.requireNonNull(prefix);
        }

        /**
         * Attempt parsing the given filename.
         *
         * @return Whether parsing was successful.
         */
        public boolean parse(String name) {
            startMillis = endMillis = -1;

            final int dotIndex = name.lastIndexOf('.');
            final int dashIndex = name.lastIndexOf('-');

            // skip when missing time section
            if (dotIndex == -1 || dashIndex == -1) return false;

            // skip when prefix doesn't match
            if (!prefix.equals(name.substring(0, dotIndex))) return false;

            try {
                startMillis = Long.parseLong(name.substring(dotIndex + 1, dashIndex));

                if (name.length() - dashIndex == 1) {
                    endMillis = Long.MAX_VALUE;
                } else {
                    endMillis = Long.parseLong(name.substring(dashIndex + 1));
                }

                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        /**
         * Build current state into filename.
         */
        public String build() {
            final StringBuilder name = new StringBuilder();
            name.append(prefix).append('.').append(startMillis).append('-');
            if (endMillis != Long.MAX_VALUE) {
                name.append(endMillis);
            }
            return name.toString();
        }

        /**
         * Test if current file is active (no end timestamp).
         */
        public boolean isActive() {
            return endMillis == Long.MAX_VALUE;
        }
    }
}
