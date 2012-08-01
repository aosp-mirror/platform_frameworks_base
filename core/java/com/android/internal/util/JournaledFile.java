/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.util;

import java.io.File;
import java.io.IOException;

/**
 * @Deprecated Use {@link com.android.internal.os.AtomicFile} instead.  It would
 * be nice to update all existing uses of this to switch to AtomicFile, but since
 * their on-file semantics are slightly different that would run the risk of losing
 * data if at the point of the platform upgrade to the new code it would need to
 * roll back to the backup file.  This can be solved...  but is it worth it and
 * all of the testing needed to make sure it is correct?
 */
@Deprecated
public class JournaledFile {
    File mReal;
    File mTemp;
    boolean mWriting;

    public JournaledFile(File real, File temp) {
        mReal = real;
        mTemp = temp;
    }

    /** Returns the file for you to read.
     * @more
     * Prefers the real file.  If it doesn't exist, uses the temp one, and then copies
     * it to the real one.  If there is both a real file and a temp one, assumes that the
     * temp one isn't fully written and deletes it.
     */
    public File chooseForRead() {
        File result;
        if (mReal.exists()) {
            result = mReal;
            if (mTemp.exists()) {
                mTemp.delete();
            }
        } else if (mTemp.exists()) {
            result = mTemp;
            mTemp.renameTo(mReal);
        } else {
            return mReal;
        }
        return result;
    }

    /**
     * Returns a file for you to write.
     * @more
     * If a write is already happening, throws.  In other words, you must provide your
     * own locking.
     * <p>
     * Call {@link #commit} to commit the changes, or {@link #rollback} to forget the changes.
     */
    public File chooseForWrite() {
        if (mWriting) {
            throw new IllegalStateException("uncommitted write already in progress");
        }
        if (!mReal.exists()) {
            // If the real one doesn't exist, it's either because this is the first time
            // or because something went wrong while copying them.  In this case, we can't
            // trust anything that's in temp.  In order to have the chooseForRead code not
            // use the temporary one until it's fully written, create an empty file
            // for real, which will we'll shortly delete.
            try {
                mReal.createNewFile();
            } catch (IOException e) {
                // Ignore
            }
        }

        if (mTemp.exists()) {
            mTemp.delete();
        }
        mWriting = true;
        return mTemp;
    }

    /**
     * Commit changes.
     */
    public void commit() {
        if (!mWriting) {
            throw new IllegalStateException("no file to commit");
        }
        mWriting = false;
        mTemp.renameTo(mReal);
    }

    /**
     * Roll back changes.
     */
    public void rollback() {
        if (!mWriting) {
            throw new IllegalStateException("no file to roll back");
        }
        mWriting = false;
        mTemp.delete();
    }
}
