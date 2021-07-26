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

package android.content.pm;

import android.os.Parcel;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Helper classes to read from and write to Parcel with pooled strings.
 *
 * @hide
 */
public class PackageParserCacheHelper {
    private PackageParserCacheHelper() {
    }

    private static final String TAG = "PackageParserCacheHelper";
    private static final boolean DEBUG = false;

    /**
     * Parcel read helper with a string pool.
     */
    public static class ReadHelper extends Parcel.ReadWriteHelper {
        private final ArrayList<String> mStrings = new ArrayList<>();

        private final Parcel mParcel;

        public ReadHelper(Parcel p) {
            mParcel = p;
        }

        /**
         * Prepare to read from a parcel, and install itself as a read-write helper.
         *
         * (We don't do it in the constructor to avoid calling methods before the constructor
         * finishes.)
         */
        public void startAndInstall() {
            mStrings.clear();

            final int poolPosition = mParcel.readInt();
            if (poolPosition < 0) {
                throw new IllegalStateException("Invalid string pool position: " + poolPosition);
            }
            final int startPosition = mParcel.dataPosition();

            // The pool is at the end of the parcel.
            mParcel.setDataPosition(poolPosition);
            mParcel.readStringList(mStrings);

            // Then move back.
            mParcel.setDataPosition(startPosition);

            if (DEBUG) {
                Log.i(TAG, "Read " + mStrings.size() + " strings");
                for (int i = 0; i < mStrings.size(); i++) {
                    Log.i(TAG, "  " + i + ": \"" + mStrings.get(i) + "\"");
                }
            }

            mParcel.setReadWriteHelper(this);
        }

        /**
         * Read an string index from a parcel, and returns the corresponding string from the pool.
         */
        public String readString(Parcel p) {
            return mStrings.get(p.readInt());
        }

        @Override
        public String readString8(Parcel p) {
            return readString(p);
        }

        @Override
        public String readString16(Parcel p) {
            return readString(p);
        }
    }

    /**
     * Parcel write helper with a string pool.
     */
    public static class WriteHelper extends Parcel.ReadWriteHelper {
        private final ArrayList<String> mStrings = new ArrayList<>();

        private final HashMap<String, Integer> mIndexes = new HashMap<>();

        private final Parcel mParcel;
        private final int mStartPos;

        /**
         * Constructor.  Prepare a parcel, and install it self as a read-write helper.
         */
        public WriteHelper(Parcel p) {
            mParcel = p;
            mStartPos = p.dataPosition();
            mParcel.writeInt(0); // We come back later here and write the pool position.

            mParcel.setReadWriteHelper(this);
        }

        /**
         * Instead of writing a string directly to a parcel, this method adds it to the pool,
         * and write the index in the pool to the parcel.
         */
        public void writeString(Parcel p, String s) {
            final Integer cur = mIndexes.get(s);
            if (cur != null) {
                // String already in the pool. Just write the index.
                p.writeInt(cur); // Already in the pool.
                if (DEBUG) {
                    Log.i(TAG, "Duplicate '" + s + "' at " + cur);
                }
            } else {
                // Not in the pool. Add to the pool, and write the index.
                final int index = mStrings.size();
                mIndexes.put(s, index);
                mStrings.add(s);

                if (DEBUG) {
                    Log.i(TAG, "New '" + s + "' at " + index);
                }

                p.writeInt(index);
            }
        }

        @Override
        public void writeString8(Parcel p, String s) {
            writeString(p, s);
        }

        @Override
        public void writeString16(Parcel p, String s) {
            writeString(p, s);
        }

        /**
         * Closes a parcel by appending the string pool at the end and updating the pool offset,
         * which it assumes is at the first byte.  It also uninstalls itself as a read-write helper.
         */
        public void finishAndUninstall() {
            // Uninstall first, so that writeStringList() uses the native writeString.
            mParcel.setReadWriteHelper(null);

            final int poolPosition = mParcel.dataPosition();
            mParcel.writeStringList(mStrings);

            mParcel.setDataPosition(mStartPos);
            mParcel.writeInt(poolPosition);

            // Move back to the end.
            mParcel.setDataPosition(mParcel.dataSize());
            if (DEBUG) {
                Log.i(TAG, "Wrote " + mStrings.size() + " strings");
            }
        }
    }
}
