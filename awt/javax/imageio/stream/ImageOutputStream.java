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
 * @version $Revision: 1.2 $
 */

package javax.imageio.stream;

import java.io.DataOutput;
import java.io.IOException;

/**
 * The ImageOutputStream represents output stream interface that is used by
 * ImageWriters.
 * 
 * @since Android 1.0
 */
public interface ImageOutputStream extends DataOutput, ImageInputStream {

    /**
     * Writes a single byte to the stream at the current position.
     * 
     * @param b
     *            the integer value, of which the 8 lowest bits will be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void write(int b) throws IOException;

    /**
     * Writes the bytes array to the stream.
     * 
     * @param b
     *            the byte array to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void write(byte[] b) throws IOException;

    /**
     * Writes a number of bytes from the specified byte array beginning from the
     * specified offset.
     * 
     * @param b
     *            the byte array.
     * @param off
     *            the offset.
     * @param len
     *            the number of bytes to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void write(byte[] b, int off, int len) throws IOException;

    /**
     * Writes the specified boolean value to the stream, 1 if it is true, 0 if
     * it is false.
     * 
     * @param b
     *            the boolean value to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeBoolean(boolean b) throws IOException;

    /**
     * Writes the 8 lowest bits of the specified integer value to the stream.
     * 
     * @param b
     *            the specified integer value.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeByte(int b) throws IOException;

    /**
     * Writes a short value to the output stream.
     * 
     * @param v
     *            the short value to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeShort(int v) throws IOException;

    /**
     * Writes the 16 lowest bits of the specified integer value to the stream.
     * 
     * @param v
     *            the specified integer value.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeChar(int v) throws IOException;

    /**
     * Writes an integer value to the output stream.
     * 
     * @param v
     *            the integer value to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeInt(int v) throws IOException;

    /**
     * Write long.
     * 
     * @param v
     *            the long value.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeLong(long v) throws IOException;

    /**
     * Writes a float value to the output stream.
     * 
     * @param v
     *            the float which contains value to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeFloat(float v) throws IOException;

    /**
     * Writes a double value to the output stream.
     * 
     * @param v
     *            the double which contains value to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeDouble(double v) throws IOException;

    /**
     * Writes the specified string to the stream.
     * 
     * @param s
     *            the string to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeBytes(String s) throws IOException;

    /**
     * Writes the specified String to the output stream.
     * 
     * @param s
     *            the String to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeChars(String s) throws IOException;

    /**
     * Writes 2 bytes to the output stream in the modified UTF-8 representation
     * of every character of the specified string.
     * 
     * @param s
     *            the specified string to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeUTF(String s) throws IOException;

    /**
     * Flushes the initial position in this stream prior to the specified stream
     * position.
     * 
     * @param pos
     *            the position.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void flushBefore(long pos) throws IOException;

    /**
     * Writes a len number of short values from the specified array to the
     * stream.
     * 
     * @param s
     *            the shorts array to be written.
     * @param off
     *            the offset in the char array.
     * @param len
     *            the length of chars to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeShorts(short[] s, int off, int len) throws IOException;

    /**
     * Writes a len number of chars to the stream.
     * 
     * @param c
     *            the char array to be written.
     * @param off
     *            the offset in the char array.
     * @param len
     *            the length of chars to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeChars(char[] c, int off, int len) throws IOException;

    /**
     * Writes a len number of integer values from the specified array to the
     * stream.
     * 
     * @param i
     *            the integer array to be written.
     * @param off
     *            the offset in the char array.
     * @param len
     *            the length of chars to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeInts(int[] i, int off, int len) throws IOException;

    /**
     * Writes a len number of long values from the specified array to the
     * stream.
     * 
     * @param l
     *            the long array to be written.
     * @param off
     *            the offset in the char array.
     * @param len
     *            the length of chars to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeLongs(long[] l, int off, int len) throws IOException;

    /**
     * Writes a len number of float values from the specified array to the
     * stream.
     * 
     * @param f
     *            the float array to be written.
     * @param off
     *            the offset in the char array.
     * @param len
     *            the length of chars to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeFloats(float[] f, int off, int len) throws IOException;

    /**
     * Writes a len number of double values from the specified array to the
     * stream.
     * 
     * @param d
     *            the double array to be written.
     * @param off
     *            the offset in the char array.
     * @param len
     *            the length of chars to be written.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeDoubles(double[] d, int off, int len) throws IOException;

    /**
     * Writes a single bit at the current position.
     * 
     * @param bit
     *            the integer whose least significant bit is to be written to
     *            the stream.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeBit(int bit) throws IOException;

    /**
     * Writes a sequence of bits beginning from the current position.
     * 
     * @param bits
     *            the long value containing the bits to be written, starting
     *            with the bit in position numBits - 1 down to the least
     *            significant bit.
     * @param numBits
     *            the number of significant bit, it can be between 0 and 64.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void writeBits(long bits, int numBits) throws IOException;

}
