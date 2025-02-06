/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.server.power.stats;

import static android.os.Trace.TRACE_TAG_SYSTEM_SERVER;

import android.annotation.NonNull;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.BatteryStatsHistory.BatteryHistoryFragment;

import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;

public class BatteryHistoryDirectory implements BatteryStatsHistory.BatteryHistoryStore {
    public static final String TAG = "BatteryHistoryDirectory";
    private static final boolean DEBUG = false;

    private static final String FILE_SUFFIX = ".bh";

    // Size of the magic number written at the start of each history file
    private static final int FILE_FORMAT_BYTES = 4;
    private static final byte[] FILE_FORMAT_PARCEL = {0x50, 0x52, 0x43, 0x4c}; // PRCL
    private static final byte[] FILE_FORMAT_COMPRESSED_PARCEL = {0x47, 0x5a, 0x49, 0x50}; // GZIP

    static class BatteryHistoryFile extends BatteryHistoryFragment {
        public final AtomicFile atomicFile;

        BatteryHistoryFile(File directory, long monotonicTimeMs) {
            super(monotonicTimeMs);
            atomicFile = new AtomicFile(new File(directory, monotonicTimeMs + FILE_SUFFIX));
        }

        @Override
        public String toString() {
            return atomicFile.getBaseFile().toString();
        }
    }

    interface Compressor {
        void compress(OutputStream stream, byte[] data) throws IOException;
        void uncompress(byte[] data, InputStream stream) throws IOException;

        default void readFully(byte[] data, InputStream stream) throws IOException {
            int pos = 0;
            while (pos < data.length) {
                int count = stream.read(data, pos, data.length - pos);
                if (count == -1) {
                    throw new IOException("Invalid battery history file format");
                }
                pos += count;
            }
        }
    }

    static final Compressor DEFAULT_COMPRESSOR = new Compressor() {
        @Override
        public void compress(OutputStream stream, byte[] data) throws IOException {
            // With the BEST_SPEED hint, we see ~4x improvement in write latency over
            // GZIPOutputStream.
            GzipParameters parameters = new GzipParameters();
            parameters.setCompressionLevel(Deflater.BEST_SPEED);
            GzipCompressorOutputStream os = new GzipCompressorOutputStream(stream, parameters);
            os.write(data);
            os.finish();
            os.flush();
        }

        @Override
        public void uncompress(byte[] data, InputStream stream) throws IOException {
            readFully(data, new GZIPInputStream(stream));
        }
    };

    private final File mDirectory;
    private int mMaxHistorySize;
    private boolean mInitialized;
    private final List<BatteryHistoryFile> mHistoryFiles = new ArrayList<>();
    private final ReentrantLock mLock = new ReentrantLock();
    private final Compressor mCompressor;
    private boolean mWaitForDirectoryLock = false;
    private boolean mFileCompressionEnabled;

    public BatteryHistoryDirectory(@NonNull File directory, int maxHistorySize) {
        this(directory, maxHistorySize, DEFAULT_COMPRESSOR);
    }

    public BatteryHistoryDirectory(@NonNull File directory, int maxHistorySize,
            Compressor compressor) {
        mDirectory = directory;
        mMaxHistorySize = maxHistorySize;
        if (mMaxHistorySize == 0) {
            Slog.w(TAG, "maxHistorySize should not be zero");
        }
        mCompressor = compressor;
    }

    public void setFileCompressionEnabled(boolean enabled) {
        mFileCompressionEnabled = enabled;
    }

    void setMaxHistorySize(int maxHistorySize) {
        mMaxHistorySize = maxHistorySize;
        trim();
    }

    /**
     * Returns the maximum storage size allocated to battery history.
     */
    public int getMaxHistorySize() {
        return mMaxHistorySize;
    }

    @Override
    public void lock() {
        mLock.lock();
    }

    /**
     * Turns "tryLock" into "lock" to prevent flaky unit tests.
     * Should only be called from unit tests.
     */
    @VisibleForTesting
    void makeDirectoryLockUnconditional() {
        mWaitForDirectoryLock = true;
    }

    @Override
    public boolean tryLock() {
        if (mWaitForDirectoryLock) {
            mLock.lock();
            return true;
        }
        return mLock.tryLock();
    }

    @Override
    public void writeFragment(BatteryHistoryFragment fragment,
            @NonNull byte[] data, boolean fragmentComplete) {
        AtomicFile file = ((BatteryHistoryFile) fragment).atomicFile;
        FileOutputStream fos = null;
        try {
            final long startTimeMs = SystemClock.uptimeMillis();
            fos = file.startWrite();
            fos.write(FILE_FORMAT_PARCEL);
            writeInt(fos, data.length);
            fos.write(data);
            fos.flush();
            file.finishWrite(fos);
            if (DEBUG) {
                Slog.d(TAG, "writeHistoryFragment file:" + file.getBaseFile().getPath()
                        + " duration ms:" + (SystemClock.uptimeMillis() - startTimeMs)
                        + " bytes:" + data.length);
            }
            if (fragmentComplete) {
                if (mFileCompressionEnabled) {
                    BackgroundThread.getHandler().post(
                            () -> writeHistoryFragmentCompressed(file, data));
                }
                BackgroundThread.getHandler().post(()-> trim());
            }
        } catch (IOException e) {
            Slog.w(TAG, "Error writing battery history fragment", e);
            file.failWrite(fos);
        }
    }

    private void writeHistoryFragmentCompressed(AtomicFile file, byte[] data) {
        long uncompressedSize = data.length;
        if (uncompressedSize == 0) {
            return;
        }

        Trace.traceBegin(TRACE_TAG_SYSTEM_SERVER, "BatteryStatsHistory.compressHistoryFile");
        lock();
        FileOutputStream fos = null;
        try {
            long startTimeNs = System.nanoTime();
            fos = file.startWrite();
            fos.write(FILE_FORMAT_COMPRESSED_PARCEL);
            writeInt(fos, data.length);

            mCompressor.compress(fos, data);
            file.finishWrite(fos);

            if (DEBUG) {
                long endTimeNs = System.nanoTime();
                long compressedSize = file.getBaseFile().length();
                Slog.i(TAG, String.format(Locale.ENGLISH,
                        "Compressed battery history file %s original size: %d compressed: %d "
                                + "(%.1f%%) elapsed: %.2f ms",
                        file.getBaseFile(), uncompressedSize, compressedSize,
                        (uncompressedSize - compressedSize) * 100.0 / uncompressedSize,
                        (endTimeNs - startTimeNs) / 1000000.0));
            }
        } catch (Exception e) {
            Slog.w(TAG, "Error compressing battery history chunk " + file, e);
            file.failWrite(fos);
        } finally {
            unlock();
            Trace.traceEnd(TRACE_TAG_SYSTEM_SERVER);
        }
    }

    @Override
    public byte[] readFragment(BatteryHistoryFragment fragment) {
        AtomicFile file = ((BatteryHistoryFile) fragment).atomicFile;
        if (!file.exists()) {
            deleteFragment(fragment);
            return null;
        }
        final long start = SystemClock.uptimeMillis();
        Trace.traceBegin(TRACE_TAG_SYSTEM_SERVER, "BatteryStatsHistory.read");
        try (FileInputStream stream = file.openRead()) {
            byte[] header = new byte[FILE_FORMAT_BYTES];
            if (stream.read(header, 0, FILE_FORMAT_BYTES) == -1) {
                Slog.e(TAG, "Invalid battery history file format " + file.getBaseFile());
                deleteFragment(fragment);
                return null;
            }

            boolean isCompressed;
            if (Arrays.equals(header, FILE_FORMAT_COMPRESSED_PARCEL)) {
                isCompressed = true;
            } else if (Arrays.equals(header, FILE_FORMAT_PARCEL)) {
                isCompressed = false;
            } else {
                Slog.e(TAG, "Invalid battery history file format " + file.getBaseFile());
                deleteFragment(fragment);
                return null;
            }

            int size = readInt(stream);
            if (size < 0 || size > 10000000) {      // Validity check to avoid a crash
                Slog.e(TAG, "Invalid battery history file format " + file.getBaseFile());
                deleteFragment(fragment);
                return null;
            }

            byte[] data = new byte[size];
            if (isCompressed) {
                mCompressor.uncompress(data, stream);
            } else {
                int pos = 0;
                while (pos < data.length) {
                    int count = stream.read(data, pos, data.length - pos);
                    if (count == -1) {
                        throw new IOException("Invalid battery history file format");
                    }
                    pos += count;
                }
            }
            if (DEBUG) {
                Slog.d(TAG, "readHistoryFragment:" + file.getBaseFile().getPath()
                        + " duration ms:" + (SystemClock.uptimeMillis() - start));
            }
            return data;
        } catch (Exception e) {
            Slog.e(TAG, "Error reading file " + file.getBaseFile().getPath(), e);
            deleteFragment(fragment);
            return null;
        } finally {
            Trace.traceEnd(TRACE_TAG_SYSTEM_SERVER);
        }
    }

    private void deleteFragment(BatteryHistoryFragment fragment) {
        mHistoryFiles.remove(fragment);
        ((BatteryHistoryFile) fragment).atomicFile.delete();
    }

    @Override
    public void unlock() {
        mLock.unlock();
    }

    @Override
    public boolean isLocked() {
        return mLock.isLocked();
    }

    private void ensureInitialized() {
        if (mInitialized) {
            return;
        }

        Trace.asyncTraceBegin(TRACE_TAG_SYSTEM_SERVER, "BatteryStatsHistory.load", 0);
        mDirectory.mkdirs();
        if (!mDirectory.exists()) {
            Slog.wtf(TAG, "HistoryDir does not exist:" + mDirectory.getPath());
        }

        final List<File> toRemove = new ArrayList<>();
        final Set<BatteryHistoryFile> dedup = new ArraySet<>();
        mDirectory.listFiles((dir, name) -> {
            final int b = name.lastIndexOf(FILE_SUFFIX);
            if (b <= 0) {
                toRemove.add(new File(dir, name));
                return false;
            }
            try {
                long monotonicTime = Long.parseLong(name.substring(0, b));
                dedup.add(new BatteryHistoryFile(mDirectory, monotonicTime));
            } catch (NumberFormatException e) {
                toRemove.add(new File(dir, name));
                return false;
            }
            return true;
        });
        if (!dedup.isEmpty()) {
            mHistoryFiles.addAll(dedup);
            Collections.sort(mHistoryFiles);
        }
        mInitialized = true;
        if (!toRemove.isEmpty()) {
            // Clear out legacy history files, which did not follow the X-Y.bin naming format.
            BackgroundThread.getHandler().post(() -> {
                lock();
                try {
                    for (File file : toRemove) {
                        file.delete();
                    }
                } finally {
                    unlock();
                    Trace.asyncTraceEnd(TRACE_TAG_SYSTEM_SERVER, "BatteryStatsHistory.load", 0);
                }
            });
        } else {
            Trace.asyncTraceEnd(TRACE_TAG_SYSTEM_SERVER, "BatteryStatsHistory.load", 0);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<BatteryHistoryFragment> getFragments() {
        ensureInitialized();
        return (List<BatteryHistoryFragment>)
                (List<? extends BatteryHistoryFragment>) mHistoryFiles;
    }

    @VisibleForTesting
    List<String> getFileNames() {
        ensureInitialized();
        lock();
        try {
            List<String> names = new ArrayList<>();
            for (BatteryHistoryFile historyFile : mHistoryFiles) {
                names.add(historyFile.atomicFile.getBaseFile().getName());
            }
            return names;
        } finally {
            unlock();
        }
    }

    @Override
    public BatteryHistoryFragment getEarliestFragment() {
        ensureInitialized();
        lock();
        try {
            if (!mHistoryFiles.isEmpty()) {
                return mHistoryFiles.get(0);
            }
            return null;
        } finally {
            unlock();
        }
    }

    @Override
    public BatteryHistoryFragment getLatestFragment() {
        ensureInitialized();
        lock();
        try {
            if (!mHistoryFiles.isEmpty()) {
                return mHistoryFiles.get(mHistoryFiles.size() - 1);
            }
            return null;
        } finally {
            unlock();
        }
    }

    @Override
    public BatteryHistoryFragment createFragment(long monotonicStartTime) {
        ensureInitialized();

        BatteryHistoryFile file = new BatteryHistoryFile(mDirectory, monotonicStartTime);
        lock();
        try {
            try {
                file.atomicFile.getBaseFile().createNewFile();
            } catch (IOException e) {
                Slog.e(TAG, "Could not create history file: " + file);
            }
            mHistoryFiles.add(file);
        } finally {
            unlock();
        }

        return file;
    }

    @Override
    public BatteryHistoryFragment getNextFragment(BatteryHistoryFragment current, long startTimeMs,
            long endTimeMs) {
        ensureInitialized();

        if (!mLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Iterating battery history without a lock");
        }

        int nextFileIndex = 0;
        int firstFileIndex = 0;
        // skip the last file because its data is in history buffer.
        int lastFileIndex = mHistoryFiles.size() - 2;
        for (int i = lastFileIndex; i >= 0; i--) {
            BatteryHistoryFragment fragment = mHistoryFiles.get(i);
            if (current != null && fragment.monotonicTimeMs == current.monotonicTimeMs) {
                nextFileIndex = i + 1;
            }
            if (fragment.monotonicTimeMs > endTimeMs) {
                lastFileIndex = i - 1;
            }
            if (fragment.monotonicTimeMs <= startTimeMs) {
                firstFileIndex = i;
                break;
            }
        }

        if (nextFileIndex < firstFileIndex) {
            nextFileIndex = firstFileIndex;
        }

        if (nextFileIndex <= lastFileIndex) {
            return mHistoryFiles.get(nextFileIndex);
        }

        return null;
    }

    @Override
    public boolean hasCompletedFragments() {
        ensureInitialized();

        lock();
        try {
            // Active file is partial and does not count as "competed"
            return mHistoryFiles.size() > 1;
        } finally {
            unlock();
        }
    }

    @Override
    public int getSize() {
        ensureInitialized();

        lock();
        try {
            int ret = 0;
            for (int i = 0; i < mHistoryFiles.size() - 1; i++) {
                ret += (int) mHistoryFiles.get(i).atomicFile.getBaseFile().length();
            }
            return ret;
        } finally {
            unlock();
        }
    }

    @Override
    public void reset() {
        ensureInitialized();

        lock();
        try {
            if (DEBUG) {
                Slog.i(TAG, "********** CLEARING HISTORY!");
            }
            for (BatteryHistoryFile file : mHistoryFiles) {
                file.atomicFile.delete();
            }
            mHistoryFiles.clear();
        } finally {
            unlock();
        }
    }

    private void trim() {
        ensureInitialized();

        Trace.traceBegin(TRACE_TAG_SYSTEM_SERVER, "BatteryStatsHistory.trim");
        try {
            lock();
            try {
                // if there is more history stored than allowed, delete oldest history files.
                int size = 0;
                for (int i = 0; i < mHistoryFiles.size(); i++) {
                    size += (int) mHistoryFiles.get(i).atomicFile.getBaseFile().length();
                }
                while (size > mMaxHistorySize) {
                    BatteryHistoryFile oldest = mHistoryFiles.get(0);
                    int length = (int) oldest.atomicFile.getBaseFile().length();
                    oldest.atomicFile.delete();
                    mHistoryFiles.remove(0);
                    size -= length;
                }
            } finally {
                unlock();
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_SYSTEM_SERVER);
        }
    }

    private static void writeInt(OutputStream stream, int value) throws IOException {
        stream.write(value >> 24);
        stream.write(value >> 16);
        stream.write(value >> 8);
        stream.write(value >> 0);
    }

    private static int readInt(InputStream stream) throws IOException {
        return (readByte(stream) << 24)
                | (readByte(stream) << 16)
                | (readByte(stream) << 8)
                | (readByte(stream) << 0);
    }

    private static int readByte(InputStream stream) throws IOException {
        int out = stream.read();
        if (out == -1) {
            throw new IOException();
        }
        return out;
    }
}
