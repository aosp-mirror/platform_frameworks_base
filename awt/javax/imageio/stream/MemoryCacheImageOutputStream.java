/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package javax.imageio.stream;

import org.apache.harmony.x.imageio.stream.RandomAccessMemoryCache;

import java.io.OutputStream;
import java.io.IOException;

/**
 * The MemoryCacheImageOutputStream class implements ImageOutputStream using a
 * memory buffer for caching the data.
 * 
 * @since Android 1.0
 */
public class MemoryCacheImageOutputStream extends ImageOutputStreamImpl {

    /**
     * The os.
     */
    OutputStream os;

    /**
     * The ramc.
     */
    RandomAccessMemoryCache ramc = new RandomAccessMemoryCache();

    /**
     * Instantiates a new MemoryCacheImageOutputStream which writes to the
     * specified OutputStream.
     * 
     * @param stream
     *            the OutputStream.
     */
    public MemoryCacheImageOutputStream(OutputStream stream) {
        if (stream == null) {
            throw new IllegalArgumentException("stream == null!");
        }
        os = stream;
    }

    @Override
    public void write(int b) throws IOException {
        flushBits(); // See the flushBits method description

        ramc.putData(b, streamPos);
        streamPos++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        flushBits(); // See the flushBits method description

        ramc.putData(b, off, len, streamPos);
        streamPos += len;
    }

    @Override
    public int read() throws IOException {
        bitOffset = 0;

        int res = ramc.getData(streamPos);
        if (res >= 0) {
            streamPos++;
        }
        return res;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        bitOffset = 0;

        int res = ramc.getData(b, off, len, streamPos);
        if (res > 0) {
            streamPos += res;
        }
        return res;
    }

    @Override
    public long length() {
        return ramc.length();
    }

    @Override
    public boolean isCached() {
        return true;
    }

    @Override
    public boolean isCachedMemory() {
        return true;
    }

    @Override
    public boolean isCachedFile() {
        return false;
    }

    @Override
    public void close() throws IOException {
        flushBefore(length());
        super.close();
        ramc.close();
    }

    @Override
    public void flushBefore(long pos) throws IOException {
        long flushedPosition = getFlushedPosition();
        super.flushBefore(pos);

        long newFlushedPosition = getFlushedPosition();
        int nBytes = (int)(newFlushedPosition - flushedPosition);

        ramc.getData(os, nBytes, flushedPosition);
        ramc.freeBefore(newFlushedPosition);

        os.flush();
    }
}
