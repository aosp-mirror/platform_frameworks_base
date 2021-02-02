/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.graphics.fonts;

import android.annotation.NonNull;
import android.util.Slog;

import java.io.File;
import java.io.IOException;

/**
 * A class to detect font-related native crash.
 *
 * <p>If a fs-verity protected file is accessed through mmap and corrupted file block is detected,
 * SIGBUG signal is generated and the process will crash. To find corrupted files and remove them,
 * we use a marker file to detect crash.
 * <ol>
 *     <li>Create a marker file before reading fs-verity protected font files.
 *     <li>Delete the marker file after reading font files successfully.
 *     <li>If the marker file is found in the next process startup, it means that the process
 *         crashed before. We will delete font files to prevent crash loop.
 * </ol>
 *
 * <p>Example usage:
 * <pre>
 *     FontCrashDetector detector = new FontCrashDetector(new File("/path/to/marker_file"));
 *     if (detector.hasCrashed()) {
 *         // Do cleanup
 *     }
 *     try (FontCrashDetector.MonitoredBlock b = detector.start()) {
 *         // Read files
 *     }
 * </pre>
 *
 * <p>This class DOES NOT detect Java exceptions. If a Java exception is thrown while monitoring
 * crash, the marker file will be deleted. Creating and deleting marker files are not lightweight.
 * Please use this class sparingly with caution.
 */
/* package */ final class FontCrashDetector {

    private static final String TAG = "FontCrashDetector";

    @NonNull
    private final File mMarkerFile;

    /* package */ FontCrashDetector(@NonNull File markerFile) {
        mMarkerFile = markerFile;
    }

    /* package */ boolean hasCrashed() {
        return mMarkerFile.exists();
    }

    /* package */ void clear() {
        if (!mMarkerFile.delete()) {
            Slog.e(TAG, "Could not delete marker file: " + mMarkerFile);
        }
    }

    /** Starts crash monitoring. */
    /* package */ MonitoredBlock start() {
        try {
            mMarkerFile.createNewFile();
        } catch (IOException e) {
            Slog.e(TAG, "Could not create marker file: " + mMarkerFile, e);
        }
        return new MonitoredBlock();
    }

    /** A helper class to monitor crash with try-with-resources syntax. */
    /* package */ class MonitoredBlock implements AutoCloseable {
        /** Ends crash monitoring. */
        @Override
        public void close() {
            clear();
        }
    }
}
