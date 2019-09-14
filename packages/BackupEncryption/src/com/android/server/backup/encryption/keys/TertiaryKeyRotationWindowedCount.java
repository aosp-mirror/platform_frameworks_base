/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption.keys;

import android.content.Context;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Tracks (and commits to disk) how many key rotations have happened in the last 24 hours. This
 * allows us to limit (and therefore stagger) the number of key rotations in a given period of time.
 *
 * <p>Note to engineers thinking of replacing the below with fancier algorithms and data structures:
 * we expect the total size of this count at any time to be below however many rotations we allow in
 * the window, which is going to be in single digits. Any changes that mean we write to disk more
 * frequently, that the code is no longer resistant to clock changes, or that the code is more
 * difficult to understand are almost certainly not worthwhile.
 */
public class TertiaryKeyRotationWindowedCount {
    private static final String TAG = "TertiaryKeyRotCount";

    private static final int WINDOW_IN_HOURS = 24;
    private static final String LOG_FILE_NAME = "tertiary_key_rotation_windowed_count";

    private final Clock mClock;
    private final File mFile;
    private ArrayList<Long> mEvents;

    /** Returns a new instance, persisting state to the files dir of {@code context}. */
    public static TertiaryKeyRotationWindowedCount getInstance(Context context) {
        File logFile = new File(context.getFilesDir(), LOG_FILE_NAME);
        return new TertiaryKeyRotationWindowedCount(logFile, Clock.systemDefaultZone());
    }

    /** A new instance, committing state to {@code file}, and reading time from {@code clock}. */
    @VisibleForTesting
    TertiaryKeyRotationWindowedCount(File file, Clock clock) {
        mFile = file;
        mClock = clock;
        mEvents = new ArrayList<>();
        try {
            loadFromFile();
        } catch (IOException e) {
            Slog.e(TAG, "Error reading " + LOG_FILE_NAME, e);
        }
    }

    /** Records a key rotation at the current time. */
    public void record() {
        mEvents.add(mClock.millis());
        compact();
        try {
            saveToFile();
        } catch (IOException e) {
            Slog.e(TAG, "Error saving " + LOG_FILE_NAME, e);
        }
    }

    /** Returns the number of key rotation that have been recorded in the window. */
    public int getCount() {
        compact();
        return mEvents.size();
    }

    private void compact() {
        long minimumTimestamp = getMinimumTimestamp();
        long now = mClock.millis();
        ArrayList<Long> compacted = new ArrayList<>();
        for (long event : mEvents) {
            if (event >= minimumTimestamp && event <= now) {
                compacted.add(event);
            }
        }
        mEvents = compacted;
    }

    private long getMinimumTimestamp() {
        return mClock.millis() - TimeUnit.HOURS.toMillis(WINDOW_IN_HOURS) + 1;
    }

    private void loadFromFile() throws IOException {
        if (!mFile.exists()) {
            return;
        }
        try (FileInputStream fis = new FileInputStream(mFile);
                DataInputStream dis = new DataInputStream(fis)) {
            while (true) {
                mEvents.add(dis.readLong());
            }
        } catch (EOFException eof) {
            // expected
        }
    }

    private void saveToFile() throws IOException {
        // File size is maximum number of key rotations in window multiplied by 8 bytes, which is
        // why
        // we just overwrite it each time. We expect it will always be less than 100 bytes in size.
        try (FileOutputStream fos = new FileOutputStream(mFile);
                DataOutputStream dos = new DataOutputStream(fos)) {
            for (long event : mEvents) {
                dos.writeLong(event);
            }
        }
    }
}
