/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.testing;

import static com.android.internal.util.Preconditions.checkArgument;

import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

/** {@link InputStream} that generates random bytes up to a given length. For testing purposes. */
public class RandomInputStream extends InputStream {
    private static final int BYTE_MAX_VALUE = 255;

    private final Random mRandom;
    private final int mSizeBytes;
    private int mBytesRead;

    /**
     * A new instance, generating {@code sizeBytes} from {@code random} as a source.
     *
     * @param random Source of random bytes.
     * @param sizeBytes The number of bytes to generate before closing the stream.
     */
    public RandomInputStream(Random random, int sizeBytes) {
        mRandom = random;
        mSizeBytes = sizeBytes;
        mBytesRead = 0;
    }

    @Override
    public int read() throws IOException {
        if (isFinished()) {
            return -1;
        }
        mBytesRead++;
        return mRandom.nextInt(BYTE_MAX_VALUE);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        checkArgument(off + len <= b.length);
        if (isFinished()) {
            return -1;
        }
        int length = Math.min(len, mSizeBytes - mBytesRead);
        int end = off + length;

        for (int i = off; i < end; ) {
            for (int rnd = mRandom.nextInt(), n = Math.min(end - i, Integer.SIZE / Byte.SIZE);
                    n-- > 0;
                    rnd >>= Byte.SIZE) {
                b[i++] = (byte) rnd;
            }
        }

        mBytesRead += length;
        return length;
    }

    private boolean isFinished() {
        return mBytesRead >= mSizeBytes;
    }
}
