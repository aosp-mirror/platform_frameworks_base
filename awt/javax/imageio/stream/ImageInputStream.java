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

import java.io.DataInput;
import java.io.IOException;
import java.nio.ByteOrder;

/**
 * The ImageInputStream represents input stream interface that is used by
 * ImageReaders.
 * 
 * @since Android 1.0
 */
public interface ImageInputStream extends DataInput {

    /**
     * Sets the specified byte order for reading of data values from this
     * stream.
     * 
     * @param byteOrder
     *            the byte order.
     */
    void setByteOrder(ByteOrder byteOrder);

    /**
     * Gets the byte order.
     * 
     * @return the byte order.
     */
    ByteOrder getByteOrder();

    /**
     * Reads a byte from the stream.
     * 
     * @return the byte of the stream, or -1 for EOF indicating.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    int read() throws IOException;

    /**
     * Reads number of bytes which is equal to the specified array's length and
     * stores a result to this array.
     * 
     * @param b
     *            the byte array.
     * @return the number of read bytes, or -1 indicated EOF.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    int read(byte[] b) throws IOException;

    /**
     * Reads the number of bytes specified by len parameter from the stream and
     * stores a result to the specified array with the specified offset.
     * 
     * @param b
     *            the byte array.
     * @param off
     *            the offset.
     * @param len
     *            the number of bytes to be read.
     * @return the number of read bytes, or -1 indicated EOF.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    int read(byte[] b, int off, int len) throws IOException;

    /**
     * Reads the number of bytes specified by len parameter from the stream, and
     * modifies the specified IIOByteBuffer with the byte array, offset, and
     * length.
     * 
     * @param buf
     *            the IIOByteBuffer.
     * @param len
     *            the number of bytes to be read.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void readBytes(IIOByteBuffer buf, int len) throws IOException;

    /**
     * Reads a byte from the stream and returns a boolean true value if it is
     * non zero, false if it is zero.
     * 
     * @return the boolean value for read byte.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    boolean readBoolean() throws IOException;

    /**
     * Reads a byte from the stream and returns its value as signed byte.
     * 
     * @return the signed byte value for read byte.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    byte readByte() throws IOException;

    /**
     * Reads a byte from the stream and returns its value as an integer.
     * 
     * @return the unsigned byte value for read byte as an integer.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    int readUnsignedByte() throws IOException;

    /**
     * Reads 2 bytes from the stream, and returns the result as a short.
     * 
     * @return the signed short value from the stream.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    short readShort() throws IOException;

    /**
     * Reads 2 bytes from the stream and returns its value as an unsigned short.
     * 
     * @return a unsigned short value coded in an integer.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    int readUnsignedShort() throws IOException;

    /**
     * Reads 2 bytes from the stream and returns their unsigned char value.
     * 
     * @return the unsigned char value.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    char readChar() throws IOException;

    /**
     * Reads 4 bytes from the stream, and returns the result as an integer.
     * 
     * @return the signed integer value from the stream.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    int readInt() throws IOException;

    /**
     * Reads 4 bytes from the stream and returns its value as long.
     * 
     * @return the unsigned integer value as long.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    long readUnsignedInt() throws IOException;

    /**
     * Reads 8 bytes from the stream, and returns the result as a long.
     * 
     * @return the long value from the stream.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    long readLong() throws IOException;

    /**
     * Reads 4 bytes from the stream, and returns the result as a float.
     * 
     * @return the float value from the stream.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    float readFloat() throws IOException;

    /**
     * Reads 8 bytes from the stream, and returns the result as a double.
     * 
     * @return the double value from the stream.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    double readDouble() throws IOException;

    /**
     * Reads a line from the stream.
     * 
     * @return the string contained the line from the stream.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    String readLine() throws IOException;

    /**
     * Reads bytes from the stream in a string that has been encoded in a
     * modified UTF-8 format.
     * 
     * @return the string read from stream and modified UTF-8 format.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    String readUTF() throws IOException;

    /**
     * Reads the specified number of bytes from the stream, and stores the
     * result into the specified array starting at the specified index offset.
     * 
     * @param b
     *            the byte array.
     * @param off
     *            the offset.
     * @param len
     *            the number of bytes to be read.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void readFully(byte[] b, int off, int len) throws IOException;

    /**
     * Reads number of bytes from the stream which is equal to the specified
     * array's length, and stores them into this array.
     * 
     * @param b
     *            the byte array.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void readFully(byte[] b) throws IOException;

    /**
     * Reads the specified number of shorts from the stream, and stores the
     * result into the specified array starting at the specified index offset.
     * 
     * @param s
     *            the short array.
     * @param off
     *            the offset.
     * @param len
     *            the number of shorts to be read.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void readFully(short[] s, int off, int len) throws IOException;

    /**
     * Reads the specified number of chars from the stream, and stores the
     * result into the specified array starting at the specified index offset.
     * 
     * @param c
     *            the char array.
     * @param off
     *            the offset.
     * @param len
     *            the number of chars to be read.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void readFully(char[] c, int off, int len) throws IOException;

    /**
     * Reads the specified number of integer from the stream, and stores the
     * result into the specified array starting at the specified index offset.
     * 
     * @param i
     *            the integer array.
     * @param off
     *            the offset.
     * @param len
     *            the number of integer to be read.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void readFully(int[] i, int off, int len) throws IOException;

    /**
     * Reads the specified number of longs from the stream, and stores the
     * result into the specified array starting at the specified index offset.
     * 
     * @param l
     *            the long array.
     * @param off
     *            the offset.
     * @param len
     *            the number of longs to be read.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void readFully(long[] l, int off, int len) throws IOException;

    /**
     * Reads the specified number of floats from the stream, and stores the
     * result into the specified array starting at the specified index offset.
     * 
     * @param f
     *            the float array.
     * @param off
     *            the offset.
     * @param len
     *            the number of floats to be read.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void readFully(float[] f, int off, int len) throws IOException;

    /**
     * Reads the specified number of doubles from the stream, and stores the
     * result into the specified array starting at the specified index offset.
     * 
     * @param d
     *            the double array.
     * @param off
     *            the offset.
     * @param len
     *            the number of doubles to be read.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void readFully(double[] d, int off, int len) throws IOException;

    /**
     * Gets the stream position.
     * 
     * @return the stream position.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    long getStreamPosition() throws IOException;

    /**
     * Gets the bit offset.
     * 
     * @return the bit offset.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    int getBitOffset() throws IOException;

    /**
     * Sets the bit offset to an integer between 0 and 7.
     * 
     * @param bitOffset
     *            the bit offset.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void setBitOffset(int bitOffset) throws IOException;

    /**
     * Reads a bit from the stream and returns the value 0 or 1.
     * 
     * @return the value of single bit: 0 or 1.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    int readBit() throws IOException;

    /**
     * Read the specified number of bits and returns their values as long.
     * 
     * @param numBits
     *            the number of bits to be read.
     * @return the bit string as a long.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    long readBits(int numBits) throws IOException;

    /**
     * Returns the length of the stream.
     * 
     * @return the length of the stream, or -1 if unknown.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    long length() throws IOException;

    /**
     * Skips the specified number of bytes by moving stream position.
     * 
     * @param n
     *            the number of bytes.
     * @return the actual skipped number of bytes.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    int skipBytes(int n) throws IOException;

    /**
     * Skips the specified number of bytes by moving stream position.
     * 
     * @param n
     *            the number of bytes.
     * @return the actual skipped number of bytes.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    long skipBytes(long n) throws IOException;

    /**
     * Sets the current stream position to the specified location.
     * 
     * @param pos
     *            a file pointer position.
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void seek(long pos) throws IOException;

    /**
     * Marks a position in the stream to be returned to by a subsequent call to
     * reset.
     */
    void mark();

    /**
     * Returns the file pointer to its previous position.
     * 
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void reset() throws IOException;

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
     * Flushes the initial position in this stream prior to the current stream
     * position.
     * 
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void flush() throws IOException;

    /**
     * Gets the flushed position.
     * 
     * @return the flushed position.
     */
    long getFlushedPosition();

    /**
     * Returns true if this ImageInputStream caches data in order to allow
     * seeking backwards.
     * 
     * @return true, if this ImageInputStream caches data in order to allow
     *         seeking backwards, false otherwise.
     */
    boolean isCached();

    /**
     * Returns true if this ImageInputStream caches data in order to allow
     * seeking backwards, and keeps it in memory.
     * 
     * @return true, if this ImageInputStream caches data in order to allow
     *         seeking backwards, and keeps it in memory.
     */
    boolean isCachedMemory();

    /**
     * Returns true if this ImageInputStream caches data in order to allow
     * seeking backwards, and keeps it in a temporary file.
     * 
     * @return true, if this ImageInputStream caches data in order to allow
     *         seeking backwards, and keeps it in a temporary file.
     */
    boolean isCachedFile();

    /**
     * Closes this stream.
     * 
     * @throws IOException
     *             if an I/O exception has occurred.
     */
    void close() throws IOException;
}
