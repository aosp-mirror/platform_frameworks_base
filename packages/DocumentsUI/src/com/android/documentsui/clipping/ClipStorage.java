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

package com.android.documentsui.clipping;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.VisibleForTesting;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.android.documentsui.Files;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.util.concurrent.TimeUnit;

/**
 * Provides support for storing lists of documents identified by Uri.
 *
 * This class uses a ring buffer to recycle clip file slots, to mitigate the issue of clip file
 * deletions.
 */
public final class ClipStorage {

    public static final int NO_SELECTION_TAG = -1;

    public static final String PREF_NAME = "ClipStoragePref";

    @VisibleForTesting
    static final int NUM_OF_SLOTS = 20;

    private static final String TAG = "ClipStorage";

    private static final long STALENESS_THRESHOLD = TimeUnit.DAYS.toMillis(2);

    private static final String NEXT_POS_TAG = "NextPosTag";
    private static final String PRIMARY_DATA_FILE_NAME = "primary";

    private static final byte[] LINE_SEPARATOR = System.lineSeparator().getBytes();

    private final File mOutDir;
    private final SharedPreferences mPref;

    private final File[] mSlots = new File[NUM_OF_SLOTS];
    private int mNextPos;

    /**
     * @param outDir see {@link #prepareStorage(File)}.
     */
    public ClipStorage(File outDir, SharedPreferences pref) {
        assert(outDir.isDirectory());
        mOutDir = outDir;
        mPref = pref;

        mNextPos = mPref.getInt(NEXT_POS_TAG, 0);
    }

    /**
     * Tries to get the next available clip slot. It's guaranteed to return one. If none of
     * slots is available, it returns the next slot of the most recently returned slot by this
     * method.
     *
     * <p>This is not a perfect solution, but should be enough for most regular use. There are
     * several situations this method may not work:
     * <ul>
     *     <li>Making {@link #NUM_OF_SLOTS} - 1 times of large drag and drop or moveTo/copyTo/delete
     *     operations after cutting a primary clip, then the primary clip is overwritten.</li>
     *     <li>Having more than {@link #NUM_OF_SLOTS} queued jumbo file operations, one or more clip
     *     file may be overwritten.</li>
     * </ul>
     */
    synchronized int claimStorageSlot() {
        int curPos = mNextPos;
        for (int i = 0; i < NUM_OF_SLOTS; ++i, curPos = (curPos + 1) % NUM_OF_SLOTS) {
            createSlotFile(curPos);

            if (!mSlots[curPos].exists()) {
                break;
            }

            // No file or only primary file exists, we deem it available.
            if (mSlots[curPos].list().length <= 1) {
                break;
            }
            // This slot doesn't seem available, but still need to check if it's a legacy of
            // service being killed or a service crash etc. If it's stale, it's available.
            else if(checkStaleFiles(curPos)) {
                break;
            }
        }

        prepareSlot(curPos);

        mNextPos = (curPos + 1) % NUM_OF_SLOTS;
        mPref.edit().putInt(NEXT_POS_TAG, mNextPos).commit();
        return curPos;
    }

    private boolean checkStaleFiles(int pos) {
        File slotData = toSlotDataFile(pos);

        // No need to check if the file exists. File.lastModified() returns 0L if the file doesn't
        // exist.
        return slotData.lastModified() + STALENESS_THRESHOLD <= System.currentTimeMillis();
    }

    private void prepareSlot(int pos) {
        assert(mSlots[pos] != null);

        Files.deleteRecursively(mSlots[pos]);
        mSlots[pos].mkdir();
        assert(mSlots[pos].isDirectory());
    }

    /**
     * Returns a writer. Callers must close the writer when finished.
     */
    private Writer createWriter(int tag) throws IOException {
        File file = toSlotDataFile(tag);
        return new Writer(file);
    }

    /**
     * Gets a {@link File} instance given a tag.
     *
     * This method creates a symbolic link in the slot folder to the data file as a reference
     * counting method. When someone is done using this symlink, it's responsible to delete it.
     * Therefore we can have a neat way to track how many things are still using this slot.
     */
    public File getFile(int tag) throws IOException {
        createSlotFile(tag);

        File primary = toSlotDataFile(tag);

        String linkFileName = Integer.toString(mSlots[tag].list().length);
        File link = new File(mSlots[tag], linkFileName);

        try {
            Os.symlink(primary.getAbsolutePath(), link.getAbsolutePath());
        } catch (ErrnoException e) {
            e.rethrowAsIOException();
        }
        return link;
    }

    /**
     * Returns a Reader. Callers must close the reader when finished.
     */
    ClipStorageReader createReader(File file) throws IOException {
        assert(file.getParentFile().getParentFile().equals(mOutDir));
        return new ClipStorageReader(file);
    }

    private File toSlotDataFile(int pos) {
        assert(mSlots[pos] != null);
        return new File(mSlots[pos], PRIMARY_DATA_FILE_NAME);
    }

    private void createSlotFile(int pos) {
        if (mSlots[pos] == null) {
            mSlots[pos] = new File(mOutDir, Integer.toString(pos));
        }
    }

    /**
     * Provides initialization of the clip data storage directory.
     */
    public static File prepareStorage(File cacheDir) {
        File clipDir = getClipDir(cacheDir);
        clipDir.mkdir();

        assert(clipDir.isDirectory());
        return clipDir;
    }

    private static File getClipDir(File cacheDir) {
        return new File(cacheDir, "clippings");
    }

    private static final class Writer implements Closeable {

        private final FileOutputStream mOut;
        private final FileLock mLock;

        private Writer(File file) throws IOException {
            assert(!file.exists());

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
        private final int mTag;

        PersistTask(ClipStorage clipStorage, Iterable<Uri> uris, int tag) {
            mClipStorage = clipStorage;
            mUris = uris;
            mTag = tag;
        }

        @Override
        protected Void doInBackground(Void... params) {
            try(Writer writer = mClipStorage.createWriter(mTag)){
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
