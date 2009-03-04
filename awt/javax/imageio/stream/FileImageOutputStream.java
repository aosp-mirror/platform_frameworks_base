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
/**
 * @author Rustem V. Rafikov
 * @version $Revision: 1.3 $
 */

package javax.imageio.stream;

import java.io.*;

/**
 * The FileImageOutputStream class implements ImageOutputStream and writes the
 * output data to a File or RandomAccessFile.
 * 
 * @since Android 1.0
 */
public class FileImageOutputStream extends ImageOutputStreamImpl {

    /**
     * The file.
     */
    RandomAccessFile file;

    /**
     * Instantiates a new FileImageOutputStream with the specified File.
     * 
     * @param f
     *            the output File.
     * @throws FileNotFoundException
     *             if the file not found.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    public FileImageOutputStream(File f) throws FileNotFoundException, IOException {
        this(f != null ? new RandomAccessFile(f, "rw") : null);
    }

    /**
     * Instantiates a new FileImageOutputStream with the specified
     * RandomAccessFile.
     * 
     * @param raf
     *            the output RandomAccessFile.
     */
    public FileImageOutputStream(RandomAccessFile raf) {
        if (raf == null) {
            throw new IllegalArgumentException("file should not be NULL");
        }
        file = raf;
    }

    @Override
    public void write(int b) throws IOException {
        checkClosed();
        // according to the spec for ImageOutputStreamImpl#flushBits()
        flushBits();
        file.write(b);
        streamPos++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        checkClosed();
        // according to the spec for ImageOutputStreamImpl#flushBits()
        flushBits();
        file.write(b, off, len);
        streamPos += len;
    }

    @Override
    public int read() throws IOException {
        checkClosed();
        int rt = file.read();
        if (rt != -1) {
            streamPos++;
        }
        return rt;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        checkClosed();
        int rt = file.read(b, off, len);
        if (rt != -1) {
            streamPos += rt;
        }
        return rt;
    }

    @Override
    public long length() {
        try {
            checkClosed();
            return file.length();
        } catch (IOException e) {
            return super.length(); // -1L
        }
    }

    @Override
    public void seek(long pos) throws IOException {
        // -- checkClosed() is performed in super.seek()
        super.seek(pos);
        file.seek(pos);
        streamPos = file.getFilePointer();
    }

    @Override
    public void close() throws IOException {
        super.close();
        file.close();
    }
}
