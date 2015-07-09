/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.os;

import java.util.HashMap;

/**
 * Helper class for writing pooled strings into a Parcel.  It must be used
 * in conjunction with {@link android.os.PooledStringReader}.  This really needs
 * to be pushed in to Parcel itself, but doing that is...  complicated.
 * @hide
 */
public class PooledStringWriter {
    private final Parcel mOut;

    /**
     * Book-keeping for writing pooled string objects, mapping strings we have
     * written so far to their index in the pool.  We deliberately use HashMap
     * here since performance is critical, we expect to be doing lots of adds to
     * it, and it is only a temporary object so its overall memory footprint is
     * not a signifciant issue.
     */
    private final HashMap<String, Integer> mPool;

    /**
     * Book-keeping for writing pooling string objects, indicating where we
     * started writing the pool, which is where we need to ultimately store
     * how many strings are in the pool.
     */
    private int mStart;

    /**
     * Next available index in the pool.
     */
    private int mNext;

    public PooledStringWriter(Parcel out) {
        mOut = out;
        mPool = new HashMap<>();
        mStart = out.dataPosition();
        out.writeInt(0); // reserve space for final pool size.
    }

    public void writeString(String str) {
        final Integer cur = mPool.get(str);
        if (cur != null) {
            mOut.writeInt(cur);
        } else {
            mPool.put(str, mNext);
            mOut.writeInt(-(mNext+1));
            mOut.writeString(str);
            mNext++;
        }
    }

    public int getStringCount() {
        return mPool.size();
    }

    public void finish() {
        final int pos = mOut.dataPosition();
        mOut.setDataPosition(mStart);
        mOut.writeInt(mNext);
        mOut.setDataPosition(pos);
    }
}
