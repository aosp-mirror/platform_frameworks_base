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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Slog;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A journal of packages that have indicated that their data has changed (and therefore should be
 * backed up in the next scheduled K/V backup pass).
 *
 * <p>This information is persisted to the filesystem so that it is not lost in the event of a
 * reboot.
 */
public class DataChangedJournal {
    private static final String TAG = "DataChangedJournal";
    private static final String FILE_NAME_PREFIX = "journal";

    /**
     * Journals tend to be on the order of a few kilobytes, hence setting the buffer size to 8kb.
     */
    private static final int BUFFER_SIZE_BYTES = 8 * 1024;

    private final File mFile;

    /**
     * Constructs an instance that reads from and writes to the given file.
     */
    DataChangedJournal(@NonNull File file) {
        mFile = Objects.requireNonNull(file);
    }


    /**
     * Adds the given package to the journal.
     *
     * @param packageName The name of the package whose data has changed.
     * @throws IOException if there is an IO error writing to the journal file.
     */
    public void addPackage(String packageName) throws IOException {
        try (RandomAccessFile out = new RandomAccessFile(mFile, "rws")) {
            out.seek(out.length());
            out.writeUTF(packageName);
        }
    }

    /**
     * Invokes {@link Consumer#accept(Object)} with every package name in the journal file.
     *
     * @param consumer The callback.
     * @throws IOException If there is an IO error reading from the file.
     */
    public void forEach(Consumer<String> consumer) throws IOException {
        try (
            InputStream in = new FileInputStream(mFile);
            InputStream bufferedIn = new BufferedInputStream(in, BUFFER_SIZE_BYTES);
            DataInputStream dataInputStream = new DataInputStream(bufferedIn)
        ) {
            while (true) {
                String packageName = dataInputStream.readUTF();
                consumer.accept(packageName);
            }
        } catch (EOFException tolerated) {
            // no more data; we're done
        } // other kinds of IOExceptions are error conditions and handled in the caller
    }

    /**
     * Returns a list with the packages in this journal.
     *
     * @throws IOException If there is an IO error reading from the file.
     */
    public List<String> getPackages() throws IOException {
        List<String> packages = new ArrayList<>();
        forEach(packages::add);
        return packages;
    }

    /**
     * Deletes the journal from the filesystem.
     *
     * @return {@code true} if successfully deleted journal.
     */
    public boolean delete() {
        return mFile.delete();
    }

    @Override
    public int hashCode() {
        return mFile.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object object) {
        if (object instanceof DataChangedJournal) {
            DataChangedJournal that = (DataChangedJournal) object;
            return mFile.equals(that.mFile);
        }
        return false;
    }

    @Override
    public String toString() {
        return mFile.toString();
    }

    /**
     * Creates a new journal with a random file name in the given journal directory.
     *
     * @param journalDirectory The directory where journals are kept.
     * @return The journal.
     * @throws IOException if there is an IO error creating the file.
     */
    static DataChangedJournal newJournal(@NonNull File journalDirectory) throws IOException {
        Objects.requireNonNull(journalDirectory);
        File file = File.createTempFile(FILE_NAME_PREFIX, null, journalDirectory);
        return new DataChangedJournal(file);
    }

    /**
     * Returns a list of journals in the given journal directory.
     */
    static ArrayList<DataChangedJournal> listJournals(File journalDirectory) {
        ArrayList<DataChangedJournal> journals = new ArrayList<>();
        File[] journalFiles = journalDirectory.listFiles();
        if (journalFiles == null) {
            Slog.w(TAG, "Failed to read journal files");
            return journals;
        }
        for (File file : journalFiles) {
            journals.add(new DataChangedJournal(file));
        }
        return journals;
    }
}
