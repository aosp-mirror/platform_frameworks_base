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

import android.net.Uri;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * Reader class used to read uris from clip files stored in {@link ClipStorage}. It provides
 * synchronization within a single process as an addition to {@link FileLock} which is for
 * cross-process synchronization.
 */
class ClipStorageReader implements Iterable<Uri>, Closeable {

    /**
     * FileLock can't be held multiple times in a single JVM, but it's possible to have multiple
     * readers reading the same clip file. Share the FileLock here so that it can be released
     * when it's not needed.
     */
    private static final Map<String, FileLockEntry> sLocks = new HashMap<>();

    private final String mCanonicalPath;
    private final Scanner mScanner;

    ClipStorageReader(File file) throws IOException {
        FileInputStream inStream = new FileInputStream(file);
        mScanner = new Scanner(inStream);

        mCanonicalPath = file.getCanonicalPath(); // Resolve symlink
        synchronized (sLocks) {
            if (sLocks.containsKey(mCanonicalPath)) {
                // Read lock is already held by someone in this JVM, just increment the ref
                // count.
                sLocks.get(mCanonicalPath).mCount++;
            } else {
                // No map entry, need to lock the file so it won't pass this line until the
                // corresponding writer is done writing.
                FileLock lock = inStream.getChannel().lock(0L, Long.MAX_VALUE, true);
                sLocks.put(mCanonicalPath, new FileLockEntry(1, lock, mScanner));
            }
        }
    }

    @Override
    public Iterator iterator() {
        return new Iterator(mScanner);
    }

    @Override
    public void close() throws IOException {
        FileLockEntry ref;
        synchronized (sLocks) {
            ref = sLocks.get(mCanonicalPath);

            assert(ref.mCount > 0);
            if (--ref.mCount == 0) {
                // If ref count is 0 now, then there is no one who needs to hold the read lock.
                // Release the lock, and remove the entry.
                ref.mLock.release();
                ref.mScanner.close();
                sLocks.remove(mCanonicalPath);
            }
        }

        if (mScanner != ref.mScanner) {
            mScanner.close();
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

    private static final class FileLockEntry {
        private final FileLock mLock;
        // We need to keep this scanner here because if the scanner is closed, the file lock is
        // closed too.
        private final Scanner mScanner;

        private int mCount;

        private FileLockEntry(int count, FileLock lock, Scanner scanner) {
            mCount = count;
            mLock = lock;
            mScanner = scanner;
        }
    }
}
