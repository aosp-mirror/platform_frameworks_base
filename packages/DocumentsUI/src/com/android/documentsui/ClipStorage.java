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
import android.os.AsyncTask;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.util.Scanner;

/**
 * Provides support for storing lists of documents identified by Uri.
 *
 * <li>Access to this object *must* be synchronized externally.
 * <li>All calls to this class are I/O intensive and must be wrapped in an AsyncTask.
 */
public final class ClipStorage {

    private static final String TAG = "ClipStorage";

    private static final byte[] LINE_SEPARATOR = System.lineSeparator().getBytes();
    public static final long NO_SELECTION_TAG = -1;

    private final File mOutDir;

    /**
     * @param outDir see {@link #prepareStorage(File)}.
     */
    public ClipStorage(File outDir) {
        assert(outDir.isDirectory());
        mOutDir = outDir;
    }

    /**
     * Creates a clip tag.
     *
     * NOTE: this tag doesn't guarantee perfect uniqueness, but should work well unless user creates
     * clips more than hundreds of times per second.
     */
    public long createTag() {
        return System.currentTimeMillis();
    }

    /**
     * Returns a writer. Callers must close the writer when finished.
     */
    public Writer createWriter(long tag) throws IOException {
        File file = toTagFile(tag);
        return new Writer(file);
    }

    @VisibleForTesting
    public Reader createReader(long tag) throws IOException {
        File file = toTagFile(tag);
        return new Reader(file);
    }

    @VisibleForTesting
    public void delete(long tag) throws IOException {
        toTagFile(tag).delete();
    }

    private File toTagFile(long tag) {
        return new File(mOutDir, String.valueOf(tag));
    }

    /**
     * Provides initialization of the clip data storage directory.
     */
    static File prepareStorage(File cacheDir) {
        File clipDir = getClipDir(cacheDir);
        clipDir.mkdir();

        assert(clipDir.isDirectory());
        return clipDir;
    }

    public static boolean hasDocList(long tag) {
        return tag != NO_SELECTION_TAG;
    }

    private static File getClipDir(File cacheDir) {
        return new File(cacheDir, "clippings");
    }

    static final class Reader implements Iterable<Uri>, Closeable {

        private final Scanner mScanner;
        private final FileLock mLock;

        private Reader(File file) throws IOException {
            FileInputStream inStream = new FileInputStream(file);

            // Lock the file here so it won't pass this line until the corresponding writer is done
            // writing.
            mLock = inStream.getChannel().lock(0L, Long.MAX_VALUE, true);

            mScanner = new Scanner(inStream);
        }

        @Override
        public Iterator iterator() {
            return new Iterator(mScanner);
        }

        @Override
        public void close() throws IOException {
            if (mLock != null) {
                mLock.release();
            }

            if (mScanner != null) {
                mScanner.close();
            }
        }
    }

    private static final class Iterator implements java.util.Iterator {
        private final Scanner mScanner;

        private Iterator(Scanner scanner) {
            mScanner = scanner;
        }

        @Override
        public boolean hasNext() {
            return mScanner.hasNextLine();
        }

        @Override
        public Uri next() {
            String line = mScanner.nextLine();
            return Uri.parse(line);
        }
    }

    private static final class Writer implements Closeable {

        private final FileOutputStream mOut;
        private final FileLock mLock;

        private Writer(File file) throws IOException {
            mOut = new FileOutputStream(file);

            // Lock the file here so copy tasks would wait until everything is flushed to disk
            // before start to run.
            mLock = mOut.getChannel().lock();
        }

        public void write(Uri uri) throws IOException {
            mOut.write(uri.toString().getBytes());
            mOut.write(LINE_SEPARATOR);
        }

        @Override
        public void close() throws IOException {
            if (mLock != null) {
                mLock.release();
            }

            if (mOut != null) {
                mOut.close();
            }
        }
    }

    /**
     * An {@link AsyncTask} that persists doc uris in {@link ClipStorage}.
     */
    static final class PersistTask extends AsyncTask<Void, Void, Void> {

        private final ClipStorage mClipStorage;
        private final Iterable<Uri> mUris;
        private final long mTag;

        PersistTask(ClipStorage clipStorage, Iterable<Uri> uris, long tag) {
            mClipStorage = clipStorage;
            mUris = uris;
            mTag = tag;
        }

        @Override
        protected Void doInBackground(Void... params) {
            try (ClipStorage.Writer writer = mClipStorage.createWriter(mTag)) {
                for (Uri uri: mUris) {
                    assert(uri != null);
                    writer.write(uri);
                }
            } catch (IOException e) {
                Log.e(TAG, "Caught exception trying to write jumbo clip to disk.", e);
            }

            return null;
        }
    }
}
