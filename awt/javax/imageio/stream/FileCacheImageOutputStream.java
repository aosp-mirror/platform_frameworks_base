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

import java.io.IOException;
import java.io.File;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * The FileCacheImageOutputStream class is an implementation of
 * ImageOutputStream that writes to its OutputStream using a temporary file as a
 * cache.
 * 
 * @since Android 1.0
 */
public class FileCacheImageOutputStream extends ImageOutputStreamImpl {

    /**
     * The Constant IIO_TEMP_FILE_PREFIX.
     */
    static final String IIO_TEMP_FILE_PREFIX = "iioCache";

    /**
     * The Constant MAX_BUFFER_LEN.
     */
    static final int MAX_BUFFER_LEN = 1048575; // 1 MB - is it not too much?

    /**
     * The os.
     */
    private OutputStream os;

    /**
     * The file.
     */
    private File file;

    /**
     * The raf.
     */
    private RandomAccessFile raf;

    /**
     * Instantiates a FileCacheImageOutputStream.
     * 
     * @param stream
     *            the OutputStream for writing.
     * @param cacheDir
     *            the cache directory where the cache file will be created.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public FileCacheImageOutputStream(OutputStream stream, File cacheDir) throws IOException {
        if (stream == null) {
            throw new IllegalArgumentException("stream == null!");
        }
        os = stream;

        if (cacheDir == null || cacheDir.isDirectory()) {
            file = File.createTempFile(IIO_TEMP_FILE_PREFIX, null, cacheDir);
            file.deleteOnExit();
        } else {
            throw new IllegalArgumentException("Not a directory!");
        }

        raf = new RandomAccessFile(file, "rw");
    }

    @Override
    public void close() throws IOException {
        flushBefore(raf.length());
        super.close();
        raf.close();
        file.delete();
    }

    @Override
    public boolean isCached() {
        return true;
    }

    @Override
    public boolean isCachedFile() {
        return true;
    }

    @Override
    public boolean isCachedMemory() {
        return false;
    }

    @Override
    public void write(int b) throws IOException {
        flushBits(); // See the flushBits method description

        raf.write(b);
        streamPos++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        flushBits(); // See the flushBits method description

        raf.write(b, off, len);
        streamPos += len;
    }

    @Override
    public int read() throws IOException {
        bitOffset = 0; // Should reset

        int res = raf.read();
        if (res >= 0) {
            streamPos++;
        }

        return res;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        bitOffset = 0;

        int numRead = raf.read(b, off, len);
        if (numRead > 0) {
            streamPos += numRead;
        }

        return numRead;
    }

    @Override
    public void flushBefore(long pos) throws IOException {
        long readFromPos = flushedPos;
        super.flushBefore(pos);

        long bytesToRead = pos - readFromPos;
        raf.seek(readFromPos);

        if (bytesToRead < MAX_BUFFER_LEN) {
            byte buffer[] = new byte[(int)bytesToRead];
            raf.readFully(buffer);
            os.write(buffer);
        } else {
            byte buffer[] = new byte[MAX_BUFFER_LEN];
            while (bytesToRead > 0) {
                int count = (int)Math.min(MAX_BUFFER_LEN, bytesToRead);
                raf.readFully(buffer, 0, count);
                os.write(buffer, 0, count);
                bytesToRead -= count;
            }
        }

        os.flush();

        if (pos != streamPos) {
            raf.seek(streamPos); // Reset the position
        }
    }

    @Override
    public void seek(long pos) throws IOException {
        if (pos < flushedPos) {
            throw new IndexOutOfBoundsException();
        }

        raf.seek(pos);
        streamPos = raf.getFilePointer();
        bitOffset = 0;
    }

    @Override
    public long length() {
        try {
            return raf.length();
        } catch (IOException e) {
            return -1L;
        }
    }
}
