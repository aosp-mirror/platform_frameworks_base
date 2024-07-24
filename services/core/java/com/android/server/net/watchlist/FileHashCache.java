/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.net.watchlist;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.HexDump;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;

/**
 * @hide
 * Utility class that keeps file hashes in cache. This cache is persistent across reboots.
 * If requested hash does not exist in cache, it is calculated from the target file. Cache gets
 * persisted once it is changed in deferred mode to prevent multiple savings per many small updates.
 * Deleted files are detected and removed from the cache during the initial load. If file change is
 * detected, it is hash is calculated during the next request.
 * The synchronization is done using Handler. All requests for hashes must be done in context of
 * handler thread.
 */
public class FileHashCache {
    private static final String TAG = FileHashCache.class.getSimpleName();
    private static final boolean DEBUG = false;
    // Turns on the check that validates hash in cache matches one, calculated directly on the
    // target file. Not to be used in production.
    private static final boolean VERIFY = false;

    // Used for logging wtf only once during load, see logWtfOnce()
    private static boolean sLoggedWtf = false;

    @VisibleForTesting
    static String sPersistFileName = "/data/system/file_hash_cache";

    static long sSaveDeferredDelayMillis = TimeUnit.SECONDS.toMillis(5);

    private static class Entry {
        public final long mLastModified;
        public final byte[] mSha256Hash;

        Entry(long lastModified, @NonNull byte[] sha256Hash) {
            mLastModified = lastModified;
            mSha256Hash = sha256Hash;
        }
    }

    private Handler mHandler;
    private final Map<File, Entry> mEntries = new HashMap<>();

    private final Runnable mLoadTask = () -> {
        load();
    };
    private final Runnable mSaveTask = () -> {
        save();
    };

    /**
     * @hide
     */
    public FileHashCache(@NonNull Handler handler) {
        mHandler = handler;
        mHandler.post(mLoadTask);
    }

    /**
     * Requests sha256 for the provided file from the cache. If cache entry does not exist or
     * file was modified, then null is returned.
     * @hide
    **/
    @VisibleForTesting
    @Nullable
    byte[] getSha256HashFromCache(@NonNull File file) {
        if (!mHandler.getLooper().isCurrentThread()) {
            Slog.wtf(TAG, "Request from invalid thread", new Exception());
            return null;
        }

        final Entry entry = mEntries.get(file);
        if (entry == null) {
            return null;
        }

        try {
            if (entry.mLastModified == Os.stat(file.getAbsolutePath()).st_ctime) {
                if (VERIFY) {
                    try {
                        if (!Arrays.equals(entry.mSha256Hash, DigestUtils.getSha256Hash(file))) {
                            Slog.wtf(TAG, "Failed to verify entry for " + file);
                        }
                    } catch (NoSuchAlgorithmException | IOException e) { }
                }

                return entry.mSha256Hash;
            }
        } catch (ErrnoException e) { }

        if (DEBUG) Slog.v(TAG, "Found stale cached entry for " + file);
        mEntries.remove(file);
        return null;
    }

    /**
     * Requests sha256 for the provided file. If cache entry does not exist or file was modified,
     * hash is calculated from the requested file. Otherwise hash from cache is returned.
     * @hide
    **/
    @NonNull
    public byte[] getSha256Hash(@NonNull File file) throws NoSuchAlgorithmException, IOException {
        byte[] sha256Hash = getSha256HashFromCache(file);
        if (sha256Hash != null) {
            return sha256Hash;
        }

        try {
            sha256Hash = DigestUtils.getSha256Hash(file);
            mEntries.put(file, new Entry(Os.stat(file.getAbsolutePath()).st_ctime, sha256Hash));
            if (DEBUG) Slog.v(TAG, "New cache entry is created for " + file);
            scheduleSave();
            return sha256Hash;
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
    }

    private static void closeQuietly(@Nullable Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (IOException e) { }
    }

    /**
     * Log an error as wtf only the first instance, then log as warning.
     */
    private static void logWtfOnce(@NonNull final String s, final Exception e) {
        if (!sLoggedWtf) {
            Slog.wtf(TAG, s, e);
            sLoggedWtf = true;
        } else {
            Slog.w(TAG, s, e);
        }
    }

    private void load() {
        mEntries.clear();

        final long startTime = SystemClock.currentTimeMicro();
        final File file = new File(sPersistFileName);
        if (!file.exists()) {
            if (DEBUG) Slog.v(TAG, "Storage file does not exist. Starting from scratch.");
            return;
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            // forEach rethrows IOException as UncheckedIOException
            reader.lines().forEach((fileEntry)-> {
                try {
                    final StringTokenizer tokenizer = new StringTokenizer(fileEntry, ",");
                    final File testFile = new File(tokenizer.nextToken());
                    final long lastModified = Long.parseLong(tokenizer.nextToken());
                    final byte[] sha256 = HexDump.hexStringToByteArray(tokenizer.nextToken());
                    mEntries.put(testFile, new Entry(lastModified, sha256));
                    if (DEBUG) Slog.v(TAG, "Loaded entry for " + testFile);
                } catch (RuntimeException e) {
                    // hexStringToByteArray can throw raw RuntimeException on invalid input.  Avoid
                    // potentially reporting one error per line if the data is corrupt.
                    logWtfOnce("Invalid entry for " + fileEntry, e);
                    return;
                }
            });
            if (DEBUG) {
                Slog.i(TAG, "Loaded " + mEntries.size() + " entries in "
                        + (SystemClock.currentTimeMicro() - startTime) + " mcs.");
            }
        } catch (IOException | UncheckedIOException e) {
            Slog.e(TAG, "Failed to read storage file", e);
        } finally {
            closeQuietly(reader);
        }
    }

    private void scheduleSave() {
        mHandler.removeCallbacks(mSaveTask);
        mHandler.postDelayed(mSaveTask, sSaveDeferredDelayMillis);
    }

    private void save() {
        BufferedWriter writer = null;
        final long startTime = SystemClock.currentTimeMicro();
        try {
            writer = new BufferedWriter(new FileWriter(sPersistFileName));
            for (Map.Entry<File, Entry> entry : mEntries.entrySet()) {
                writer.write(entry.getKey() + ","
                             + entry.getValue().mLastModified + ","
                             + HexDump.toHexString(entry.getValue().mSha256Hash) + "\n");
            }
            if (DEBUG) {
                Slog.i(TAG, "Saved " + mEntries.size() + " entries in "
                        + (SystemClock.currentTimeMicro() - startTime) + " mcs.");
            }
        } catch (IOException e) {
            Slog.e(TAG, "Failed to save.", e);
        } finally {
            closeQuietly(writer);
        }
    }
}
