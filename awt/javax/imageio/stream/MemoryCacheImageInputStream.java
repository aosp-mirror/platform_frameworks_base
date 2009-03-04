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

import java.io.IOException;
import java.io.InputStream;

/**
 * The MemoryCacheImageInputStream class implements ImageInputStream using a
 * memory buffer for caching the data.
 * 
 * @since Android 1.0
 */
public class MemoryCacheImageInputStream extends ImageInputStreamImpl {

    /**
     * The is.
     */
    private InputStream is;

    /**
     * The ramc.
     */
    private RandomAccessMemoryCache ramc = new RandomAccessMemoryCache();

    /**
     * Instantiates a new MemoryCacheImageInputStream which reads from the
     * specified InputStream.
     * 
     * @param stream
     *            the InputStream to be read.
     */
    public MemoryCacheImageInputStream(InputStream stream) {
        if (stream == null) {
            throw new IllegalArgumentException("stream == null!");
        }
        is = stream;
    }

    @Override
    public int read() throws IOException {
        bitOffset = 0;

        if (streamPos >= ramc.length()) {
            int count = (int)(streamPos - ramc.length() + 1);
            int bytesAppended = ramc.appendData(is, count);

            if (bytesAppended < count) {
                return -1;
            }
        }

        int res = ramc.getData(streamPos);
        if (res >= 0) {
            streamPos++;
        }
        return res;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        bitOffset = 0;

        if (streamPos >= ramc.length()) {
            int count = (int)(streamPos - ramc.length() + len);
            ramc.appendData(is, count);
        }

        int res = ramc.getData(b, off, len, streamPos);
        if (res > 0) {
            streamPos += res;
        }
        return res;
    }

    @Override
    public boolean isCached() {
        return true;
    }

    @Override
    public boolean isCachedFile() {
        return false;
    }

    @Override
    public boolean isCachedMemory() {
        return true;
    }

    @Override
    public void close() throws IOException {
        super.close();
        ramc.close();
    }

    @Override
    public void flushBefore(long pos) throws IOException {
        super.flushBefore(pos);
        ramc.freeBefore(getFlushedPosition());
    }
}
