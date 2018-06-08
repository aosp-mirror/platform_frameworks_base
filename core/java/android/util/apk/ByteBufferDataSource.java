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

package android.util.apk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.DigestException;

/**
 * {@link DataSource} which provides data from a {@link ByteBuffer}.
 */
class ByteBufferDataSource implements DataSource {
    /**
     * Underlying buffer. The data is stored between position 0 and the buffer's capacity.
     * The buffer's position is 0 and limit is equal to capacity.
     */
    private final ByteBuffer mBuf;

    ByteBufferDataSource(ByteBuffer buf) {
        // Defensive copy, to avoid changes to mBuf being visible in buf, and to ensure position is
        // 0 and limit == capacity.
        mBuf = buf.slice();
    }

    @Override
    public long size() {
        return mBuf.capacity();
    }

    @Override
    public void feedIntoDataDigester(DataDigester md, long offset, int size)
            throws IOException, DigestException {
        // There's no way to tell MessageDigest to read data from ByteBuffer from a position
        // other than the buffer's current position. We thus need to change the buffer's
        // position to match the requested offset.
        //
        // In the future, it may be necessary to compute digests of multiple regions in
        // parallel. Given that digest computation is a slow operation, we enable multiple
        // such requests to be fulfilled by this instance. This is achieved by serially
        // creating a new ByteBuffer corresponding to the requested data range and then,
        // potentially concurrently, feeding these buffers into MessageDigest instances.
        ByteBuffer region;
        synchronized (mBuf) {
            mBuf.position(0);
            mBuf.limit((int) offset + size);
            mBuf.position((int) offset);
            region = mBuf.slice();
        }

        md.consume(region);
    }
}
