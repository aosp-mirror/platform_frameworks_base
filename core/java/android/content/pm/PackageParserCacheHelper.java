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

    /**
     * Parcel read helper with a string pool.
     */
    public static class ReadHelper extends Parcel.ReadWriteHelper {
        private final ArrayList<String> mStrings = new ArrayList<>();

        public ReadHelper() {
        }

        /**
         * Prepare a parcel.
         */
        public void start(Parcel p) {
            mStrings.clear();

            final int poolPosition = p.readInt();
            final int startPosition = p.dataPosition();

            // The pool is at the end of the parcel.
            p.setDataPosition(poolPosition);
            p.readStringList(mStrings);

            // Then move back.
            p.setDataPosition(startPosition);
        }

        /**
         * Read an string index from a parcel, and returns the corresponding string from the pool.
         */
        @Override
        public String readString(Parcel p) {
            return mStrings.get(p.readInt());
        }
    }

    /**
     * Parcel write helper with a string pool.
     */
    public static class WriteHelper extends Parcel.ReadWriteHelper {
        private final ArrayList<String> mStrings = new ArrayList<>();

        private final HashMap<String, Integer> mIndexes = new HashMap<>();

        public WriteHelper() {
        }

        /**
         * Instead of writing a string directly to a parcel, this method adds it to the pool,
         * and write the index in the pool to the parcel.
         */
        @Override
        public void writeString(Parcel p, String s) {
            final Integer cur = mIndexes.get(s);
            if (cur != null) {
                // String already in the pool. Just write the index.
                p.writeInt(cur); // Already in the pool.
            } else {
                // Note in the pool. Add to the pool, and write the index.
                final int index = mStrings.size();
                mIndexes.put(s, index);
                mStrings.add(s);

                p.writeInt(index);
            }
        }

        /**
         * Closes a parcel by appending the string pool at the end and updating the pool offset,
         * which it assumes is at the first byte.
         */
        public void finish(Parcel p) {
            final int poolPosition = p.readInt();
            p.writeStringList(mStrings);

            p.setDataPosition(0);
            p.writeInt(poolPosition);

            // Move back to the end.
            p.setDataPosition(p.dataSize());
        }
    }
}
