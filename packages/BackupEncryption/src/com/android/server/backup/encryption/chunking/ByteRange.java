/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup.encryption.chunking;

import com.android.internal.util.Preconditions;

/** Representation of a range of bytes to be downloaded. */
final class ByteRange {
    private final long mStart;
    private final long mEnd;

    /** Creates a range of bytes which includes {@code mStart} and {@code mEnd}. */
    ByteRange(long start, long end) {
        Preconditions.checkArgument(start >= 0);
        Preconditions.checkArgument(end >= start);
        mStart = start;
        mEnd = end;
    }

    /** Returns the start of the {@code ByteRange}. The start is included in the range. */
    long getStart() {
        return mStart;
    }

    /** Returns the end of the {@code ByteRange}. The end is included in the range. */
    long getEnd() {
        return mEnd;
    }

    /** Returns the number of bytes included in the {@code ByteRange}. */
    int getLength() {
        return (int) (mEnd - mStart + 1);
    }

    /** Creates a new {@link ByteRange} from {@code mStart} to {@code mEnd + length}. */
    ByteRange extend(long length) {
        Preconditions.checkArgument(length > 0);
        return new ByteRange(mStart, mEnd + length);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ByteRange)) {
            return false;
        }

        ByteRange byteRange = (ByteRange) o;
        return (mEnd == byteRange.mEnd && mStart == byteRange.mStart);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (int) (mStart ^ (mStart >>> 32));
        result = 31 * result + (int) (mEnd ^ (mEnd >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return String.format("ByteRange{mStart=%d, mEnd=%d}", mStart, mEnd);
    }
}
