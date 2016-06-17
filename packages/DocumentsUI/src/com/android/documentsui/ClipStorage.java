/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui;

import android.net.Uri;
import android.support.annotation.VisibleForTesting;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides support for storing lists of documents identified by Uri.
 *
 * <li>Access to this object *must* be synchronized externally.
 * <li>All calls to this class are I/O intensive and must be wrapped in an AsyncTask.
 */
public final class ClipStorage {

    private static final String PRIMARY_SELECTION = "primary-selection.txt";
    private static final byte[] LINE_SEPARATOR = System.lineSeparator().getBytes();
    private static final int NO_SELECTION_TAG = -1;

    private final File mOutDir;

    /**
     * @param outDir see {@link #prepareStorage(File)}.
     */
    public ClipStorage(File outDir) {
        assert(outDir.isDirectory());
        mOutDir = outDir;
    }

    /**
     * Returns a writer. Callers must...
     *
     * <li>synchronize on the {@link ClipStorage} instance while writing to this writer.
     * <li>closed the write when finished.
     */
    public Writer createWriter() throws IOException {
        File primary = new File(mOutDir, PRIMARY_SELECTION);
        return new Writer(new FileOutputStream(primary));
    }

    /**
     * Saves primary uri list to persistent storage.
     * @return tag identifying the saved set.
     */
    @VisibleForTesting
    public long savePrimary() throws IOException {
        File primary = new File(mOutDir, PRIMARY_SELECTION);

        if (!primary.exists()) {
            return NO_SELECTION_TAG;
        }

        long tag = System.currentTimeMillis();
        File dest = toTagFile(tag);
        primary.renameTo(dest);

        return tag;
    }

    @VisibleForTesting
    public List<Uri> read(long tag) throws IOException {
        List<Uri> uris = new ArrayList<>();
        File tagFile = toTagFile(tag);
        try (BufferedReader in = new BufferedReader(new FileReader(tagFile))) {
            String line = null;
            while ((line = in.readLine()) != null) {
                uris.add(Uri.parse(line));
            }
        }
        return uris;
    }

    @VisibleForTesting
    public void delete(long tag) throws IOException {
        toTagFile(tag).delete();
    }

    private File toTagFile(long tag) {
        return new File(mOutDir, String.valueOf(tag));
    }

    public static final class Writer implements Closeable {

        private final FileOutputStream mOut;

        public Writer(FileOutputStream out) {
            mOut = out;
        }

        public void write(Uri uri) throws IOException {
            mOut.write(uri.toString().getBytes());
            mOut.write(LINE_SEPARATOR);
        }

        @Override
        public void close() throws IOException {
            mOut.close();
        }
    }

    /**
     * Provides initialization and cleanup of the clip data storage directory.
     */
    static File prepareStorage(File cacheDir) {
        File clipDir = new File(cacheDir, "clippings");
        if (clipDir.exists()) {
            Files.deleteRecursively(clipDir);
        }
        assert(!clipDir.exists());
        clipDir.mkdir();
        return clipDir;
    }
}
