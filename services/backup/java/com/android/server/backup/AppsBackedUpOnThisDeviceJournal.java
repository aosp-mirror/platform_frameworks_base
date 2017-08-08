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
 * limitations under the License
 */

package com.android.server.backup;

import static com.android.server.backup.RefactoredBackupManagerService.DEBUG;

import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;

/**
 * Records which apps have been backed up on this device, persisting it to disk so that it can be
 * read at subsequent boots. This class is threadsafe.
 *
 * <p>This is used to decide, when restoring a package at install time, whether it has been
 * previously backed up on the current device. If it has been previously backed up it should
 * restore from the same restore set that the current device has been backing up to. If it has not
 * been previously backed up, it should restore from the ancestral restore set (i.e., the restore
 * set that the user's previous device was backing up to).
 *
 * <p>NB: this is always backed by the same files within the state directory supplied at
 * construction.
 */
final class AppsBackedUpOnThisDeviceJournal {
    private static final String TAG = "AppsBackedUpOnThisDeviceJournal";
    private static final String JOURNAL_FILE_NAME = "processed";

    @GuardedBy("this")
    private final HashSet<String> mProcessedPackages = new HashSet<>();
    private final File mStateDirectory;

    /**
     * Constructs a new journal, loading state from disk if it has been previously persisted.
     *
     * @param stateDirectory The directory in which backup state (including journals) is stored.
     */
    AppsBackedUpOnThisDeviceJournal(File stateDirectory) {
        mStateDirectory = stateDirectory;
        loadFromDisk();
    }

    /**
     * Returns {@code true} if {@code packageName} has previously been backed up.
     */
    synchronized boolean hasBeenProcessed(String packageName) {
        return mProcessedPackages.contains(packageName);
    }

    synchronized void addPackage(String packageName) {
        if (!mProcessedPackages.add(packageName)) {
            // This package has already been processed - no need to add it to the journal.
            return;
        }

        File journalFile = new File(mStateDirectory, JOURNAL_FILE_NAME);

        try (RandomAccessFile out = new RandomAccessFile(journalFile, "rws")) {
            out.seek(out.length());
            out.writeUTF(packageName);
        } catch (IOException e) {
            Slog.e(TAG, "Can't log backup of " + packageName + " to " + journalFile);
        }
    }

    /**
     * A copy of the current state of the journal.
     *
     * <p>Used only for dumping out information for logging. {@link #hasBeenProcessed(String)}
     * should be used for efficiently checking whether a package has been backed up before by this
     * device.
     *
     * @return The current set of packages that have been backed up previously.
     */
    synchronized HashSet<String> getPackagesCopy() {
        return new HashSet<>(mProcessedPackages);
    }

    synchronized void reset() {
        mProcessedPackages.clear();
        File journalFile = new File(mStateDirectory, JOURNAL_FILE_NAME);
        journalFile.delete();
    }

    private void loadFromDisk() {
        File journalFile = new File(mStateDirectory, JOURNAL_FILE_NAME);

        if (!journalFile.exists()) {
            return;
        }

        try (RandomAccessFile oldJournal = new RandomAccessFile(journalFile, "r")) {
            while (true) {
                String packageName = oldJournal.readUTF();
                if (DEBUG) {
                    Slog.v(TAG, "   + " + packageName);
                }
                mProcessedPackages.add(packageName);
            }
        } catch (EOFException e) {
            // Successfully loaded journal file
        } catch (IOException e) {
            Slog.e(TAG, "Error reading processed packages journal", e);
        }
    }
}
