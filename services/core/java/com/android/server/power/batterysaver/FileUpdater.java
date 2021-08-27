/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.power.batterysaver;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;
import com.android.server.IoThread;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

/**
 * Used by {@link BatterySaverController} to write values to /sys/ (and possibly /proc/ too) files
 * with retries. It also support restoring to the file original values.
 *
 * Retries are needed because writing to "/sys/.../scaling_max_freq" returns EIO when the current
 * frequency happens to be above the new max frequency.
 *
 * Test:
 atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server/power/batterysaver/FileUpdaterTest.java
 */
public class FileUpdater {
    private static final String TAG = BatterySaverController.TAG;

    private static final boolean DEBUG = BatterySaverController.DEBUG;

    /**
     * If this system property is set to 1, it'll skip all file writes. This can be used when
     * one needs to change max CPU frequency for benchmarking, for example.
     */
    private static final String PROP_SKIP_WRITE = "debug.batterysaver.no_write_files";

    private static final String TAG_DEFAULT_ROOT = "defaults";

    // Don't do disk access with this lock held.
    private final Object mLock = new Object();

    private final Context mContext;

    private final Handler mHandler;

    /**
     * Filename -> value map that holds pending writes.
     */
    @GuardedBy("mLock")
    private final ArrayMap<String, String> mPendingWrites = new ArrayMap<>();

    /**
     * Filename -> value that holds the original value of each file.
     */
    @GuardedBy("mLock")
    private final ArrayMap<String, String> mDefaultValues = new ArrayMap<>();

    /** Number of retries. We give up on writing after {@link #MAX_RETRIES} retries. */
    @GuardedBy("mLock")
    private int mRetries = 0;

    private final int MAX_RETRIES;

    private final long RETRY_INTERVAL_MS;

    /**
     * "Official" constructor. Don't use the other constructor in the production code.
     */
    public FileUpdater(Context context) {
        this(context, IoThread.get().getLooper(), 10, 5000);
    }

    /**
     * Constructor for test.
     */
    @VisibleForTesting
    FileUpdater(Context context, Looper looper, int maxRetries, int retryIntervalMs) {
        mContext = context;
        mHandler = new Handler(looper);

        MAX_RETRIES = maxRetries;
        RETRY_INTERVAL_MS = retryIntervalMs;
    }

    public void systemReady(boolean runtimeRestarted) {
        synchronized (mLock) {
            if (runtimeRestarted) {
                // If it runtime restarted, read the original values from the disk and apply.
                if (loadDefaultValuesLocked()) {
                    Slog.d(TAG, "Default values loaded after runtime restart; writing them...");
                    restoreDefault();
                }
            } else {
                // Delete it, without checking the result. (file-not-exist is not an exception.)
                injectDefaultValuesFilename().delete();
            }
        }
    }

    /**
     * Write values to files. (Note the actual writes happen ASAP but asynchronously.)
     */
    public void writeFiles(ArrayMap<String, String> fileValues) {
        synchronized (mLock) {
            for (int i = fileValues.size() - 1; i >= 0; i--) {
                final String file = fileValues.keyAt(i);
                final String value = fileValues.valueAt(i);

                if (DEBUG) {
                    Slog.d(TAG, "Scheduling write: '" + value + "' to '" + file + "'");
                }

                mPendingWrites.put(file, value);

            }
            mRetries = 0;

            mHandler.removeCallbacks(mHandleWriteOnHandlerRunnable);
            mHandler.post(mHandleWriteOnHandlerRunnable);
        }
    }

    /**
     * Restore the default values.
     */
    public void restoreDefault() {
        synchronized (mLock) {
            if (DEBUG) {
                Slog.d(TAG, "Resetting file default values.");
            }
            mPendingWrites.clear();

            writeFiles(mDefaultValues);
        }
    }

    private Runnable mHandleWriteOnHandlerRunnable = () -> handleWriteOnHandler();

    /** Convert map keys into a single string for debug messages. */
    private String getKeysString(Map<String, String> source) {
        return new ArrayList<>(source.keySet()).toString();
    }

    /** Clone an ArrayMap. */
    private ArrayMap<String, String> cloneMap(ArrayMap<String, String> source) {
        return new ArrayMap<>(source);
    }

    /**
     * Called on the handler and writes {@link #mPendingWrites} to the disk.
     *
     * When it about to write to each file for the first time, it'll read the file and store
     * the original value in {@link #mDefaultValues}.
     */
    private void handleWriteOnHandler() {
        // We don't want to access the disk with the lock held, so copy the pending writes to
        // a local map.
        final ArrayMap<String, String> writes;
        synchronized (mLock) {
            if (mPendingWrites.size() == 0) {
                return;
            }

            if (DEBUG) {
                Slog.d(TAG, "Writing files: (# retries=" + mRetries + ") " +
                        getKeysString(mPendingWrites));
            }

            writes = cloneMap(mPendingWrites);
        }

        // Then write.

        boolean needRetry = false;

        final int size = writes.size();
        for (int i = 0; i < size; i++) {
            final String file = writes.keyAt(i);
            final String value = writes.valueAt(i);

            // Make sure the default value is loaded.
            if (!ensureDefaultLoaded(file)) {
                continue;
            }

            // Write to the file. When succeeded, remove it from the pending list.
            // Otherwise, schedule a retry.
            try {
                injectWriteToFile(file, value);

                removePendingWrite(file);
            } catch (IOException e) {
                needRetry = true;
            }
        }
        if (needRetry) {
            scheduleRetry();
        }
    }

    private void removePendingWrite(String file) {
        synchronized (mLock) {
            mPendingWrites.remove(file);
        }
    }

    private void scheduleRetry() {
        synchronized (mLock) {
            if (mPendingWrites.size() == 0) {
                return; // Shouldn't happen but just in case.
            }

            mRetries++;
            if (mRetries > MAX_RETRIES) {
                doWtf("Gave up writing files: " + getKeysString(mPendingWrites));
                return;
            }

            mHandler.removeCallbacks(mHandleWriteOnHandlerRunnable);
            mHandler.postDelayed(mHandleWriteOnHandlerRunnable, RETRY_INTERVAL_MS);
        }
    }

    /**
     * Make sure {@link #mDefaultValues} has the default value loaded for {@code file}.
     *
     * @return true if the default value is loaded. false if the file cannot be read.
     */
    private boolean ensureDefaultLoaded(String file) {
        // Has the default already?
        synchronized (mLock) {
            if (mDefaultValues.containsKey(file)) {
                return true;
            }
        }
        final String originalValue;
        try {
            originalValue = injectReadFromFileTrimmed(file);
        } catch (IOException e) {
            // If the file is not readable, assume can't write too.
            injectWtf("Unable to read from file", e);

            removePendingWrite(file);
            return false;
        }
        synchronized (mLock) {
            mDefaultValues.put(file, originalValue);
            saveDefaultValuesLocked();
        }
        return true;
    }

    @VisibleForTesting
    String injectReadFromFileTrimmed(String file) throws IOException {
        return IoUtils.readFileAsString(file).trim();
    }

    @VisibleForTesting
    void injectWriteToFile(String file, String value) throws IOException {
        if (injectShouldSkipWrite()) {
            Slog.i(TAG, "Skipped writing to '" + file + "'");
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "Writing: '" + value + "' to '" + file + "'");
        }
        try (FileWriter out = new FileWriter(file)) {
            out.write(value);
        } catch (IOException | RuntimeException e) {
            Slog.w(TAG, "Failed writing '" + value + "' to '" + file + "': " + e.getMessage());
            throw e;
        }
    }

    @GuardedBy("mLock")
    private void saveDefaultValuesLocked() {
        final AtomicFile file = new AtomicFile(injectDefaultValuesFilename());

        FileOutputStream outs = null;
        try {
            file.getBaseFile().getParentFile().mkdirs();
            outs = file.startWrite();

            // Write to XML
            TypedXmlSerializer out = Xml.resolveSerializer(outs);
            out.startDocument(null, true);
            out.startTag(null, TAG_DEFAULT_ROOT);

            XmlUtils.writeMapXml(mDefaultValues, out, null);

            // Epilogue.
            out.endTag(null, TAG_DEFAULT_ROOT);
            out.endDocument();

            // Close.
            file.finishWrite(outs);
        } catch (IOException | XmlPullParserException | RuntimeException e) {
            Slog.e(TAG, "Failed to write to file " + file.getBaseFile(), e);
            file.failWrite(outs);
        }
    }

    @GuardedBy("mLock")
    @VisibleForTesting
    boolean loadDefaultValuesLocked() {
        final AtomicFile file = new AtomicFile(injectDefaultValuesFilename());
        if (DEBUG) {
            Slog.d(TAG, "Loading from " + file.getBaseFile());
        }
        Map<String, String> read = null;
        try (FileInputStream in = file.openRead()) {
            TypedXmlPullParser parser = Xml.resolvePullParser(in);

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                final int depth = parser.getDepth();
                // Check the root tag
                final String tag = parser.getName();
                if (depth == 1) {
                    if (!TAG_DEFAULT_ROOT.equals(tag)) {
                        Slog.e(TAG, "Invalid root tag: " + tag);
                        return false;
                    }
                    continue;
                }
                final String[] tagName = new String[1];
                read = (ArrayMap<String, String>) XmlUtils.readThisArrayMapXml(parser,
                        TAG_DEFAULT_ROOT, tagName, null);
            }
        } catch (FileNotFoundException e) {
            read = null;
        } catch (IOException | XmlPullParserException | RuntimeException e) {
            Slog.e(TAG, "Failed to read file " + file.getBaseFile(), e);
        }
        if (read != null) {
            mDefaultValues.clear();
            mDefaultValues.putAll(read);
            return true;
        }
        return false;
    }

    private void doWtf(String message) {
        injectWtf(message, null);
    }

    @VisibleForTesting
    void injectWtf(String message, Throwable e) {
        Slog.wtf(TAG, message, e);
    }

    File injectDefaultValuesFilename() {
        final File dir = new File(Environment.getDataSystemDirectory(), "battery-saver");
        dir.mkdirs();
        return new File(dir, "default-values.xml");
    }

    @VisibleForTesting
    boolean injectShouldSkipWrite() {
        return SystemProperties.getBoolean(PROP_SKIP_WRITE, false);
    }

    @VisibleForTesting
    ArrayMap<String, String> getDefaultValuesForTest() {
        return mDefaultValues;
    }
}
