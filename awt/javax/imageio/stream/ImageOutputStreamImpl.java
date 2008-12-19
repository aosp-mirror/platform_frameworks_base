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

import java.io.IOException;
import java.nio.ByteOrder;

/* 
 * @author Rustem V. Rafikov
 * @version $Revision: 1.3 $
 */

/**
 * The ImageOutputStreamImpl abstract class implements the ImageOutputStream
 * interface.
 * 
 * @since Android 1.0
 */
public abstract class ImageOutputStreamImpl extends ImageInputStreamImpl implements
        ImageOutputStream {

    /**
     * Instantiates a new ImageOutputStreamImpl.
     */
    public ImageOutputStreamImpl() {
    }

    public abstract void write(int b) throws IOException;

    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    public abstract void write(byte[] b, int off, int len) throws IOException;

    public void writeBoolean(boolean v) throws IOException {
        write(v ? 1 : 0);
    }

    public void writeByte(int v) throws IOException {
        write(v);
    }

    public void writeShort(int v) throws IOException {
        if (byteOrder == ByteOrder.BIG_ENDIAN) {

        } else {

        }
        // -- TODO implement
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void writeChar(int v) throws IOException {
        writeShort(v);
    }

    public void writeInt(int v) throws IOException {
        if (byteOrder == ByteOrder.BIG_ENDIAN) {

        } else {

        }
        // -- TODO implement
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void writeLong(long v) throws IOException {
        if (byteOrder == ByteOrder.BIG_ENDIAN) {

        } else {

        }
        // -- TODO implement
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void writeFloat(float v) throws IOException {
        writeInt(Float.floatToIntBits(v));
    }

    public void writeDouble(double v) throws IOException {
        writeLong(Double.doubleToLongBits(v));
    }

    public void writeBytes(String s) throws IOException {
        write(s.getBytes());
    }

    public void writeChars(String s) throws IOException {
        char[] chs = s.toCharArray();
        writeChars(chs, 0, chs.length);
    }

    public void writeUTF(String s) throws IOException {
        // -- TODO implement
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void writeShorts(short[] s, int off, int len) throws IOException {
        // -- TODO implement
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void writeChars(char[] c, int off, int len) throws IOException {
        // -- TODO implement
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void writeInts(int[] i, int off, int len) throws IOException {
        // -- TODO implement
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void writeLongs(long[] l, int off, int len) throws IOException {
        // -- TODO implement
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void writeFloats(float[] f, int off, int len) throws IOException {
        // -- TODO implement
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void writeDoubles(double[] d, int off, int len) throws IOException {
        // -- TODO implement
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void writeBit(int bit) throws IOException {
        // -- TODO implement
        throw new UnsupportedOperationException("Not implemented yet");
    }

    public void writeBits(long bits, int numBits) throws IOException {
        // -- TODO implement
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Flushes the bits. This method should be called in the write methods by
     * subclasses.
     * 
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    protected final void flushBits() throws IOException {
        if (bitOffset == 0) {
            return;
        }

        // -- TODO implement
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
