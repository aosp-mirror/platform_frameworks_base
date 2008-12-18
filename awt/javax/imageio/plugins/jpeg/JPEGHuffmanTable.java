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

package javax.imageio.plugins.jpeg;

/**
 * The JPEGHuffmanTable class represents a single JPEG Huffman table. It
 * contains the standard tables from the JPEG specification.
 * 
 * @since Android 1.0
 */
public class JPEGHuffmanTable {

    /**
     * The standard DC luminance Huffman table .
     */
    public static final JPEGHuffmanTable StdDCLuminance = new JPEGHuffmanTable(new short[] {
            0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0
    }, new short[] {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0x0A, 0x0B
    }, false);

    /**
     * The standard DC chrominance Huffman table.
     */
    public static final JPEGHuffmanTable StdDCChrominance = new JPEGHuffmanTable(new short[] {
            0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0
    }, new short[] {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0x0A, 0x0B
    }, false);

    /**
     * The standard AC luminance Huffman table.
     */
    public static final JPEGHuffmanTable StdACLuminance = new JPEGHuffmanTable(new short[] {
            0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D
    }, new short[] {
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51,
            0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08, 0x23, 0x42, 0xB1, 0xC1,
            0x15, 0x52, 0xD1, 0xF0, 0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16, 0x17, 0x18,
            0x19, 0x1A, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39,
            0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57,
            0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75,
            0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8A, 0x92,
            0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
            0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3,
            0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8,
            0xD9, 0xDA, 0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2,
            0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA
    }, false);

    /**
     * The standard AC chrominance Huffman table.
     */
    public static final JPEGHuffmanTable StdACChrominance = new JPEGHuffmanTable(new short[] {
            0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77
    }, new short[] {
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07,
            0x61, 0x71, 0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xA1, 0xB1, 0xC1, 0x09,
            0x23, 0x33, 0x52, 0xF0, 0x15, 0x62, 0x72, 0xD1, 0x0A, 0x16, 0x24, 0x34, 0xE1, 0x25,
            0xF1, 0x17, 0x18, 0x19, 0x1A, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38,
            0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56,
            0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74,
            0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5,
            0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA,
            0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6,
            0xD7, 0xD8, 0xD9, 0xDA, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF2,
            0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA
    }, false);

    /**
     * The lengths.
     */
    private short lengths[];

    /**
     * The values.
     */
    private short values[];

    /**
     * Instantiates a new jPEG huffman table.
     * 
     * @param lengths
     *            the lengths
     * @param values
     *            the values
     * @param copy
     *            the copy
     */
    JPEGHuffmanTable(short[] lengths, short[] values, boolean copy) {
        // Construction of standard tables without checks
        // The third param is dummy
        // Could be also used for copying of the existing tables
        this.lengths = lengths;
        this.values = values;
    }

    /**
     * Instantiates a new JPEGHuffmanTable.
     * 
     * @param lengths
     *            the array of shorts lengths.
     * @param values
     *            the array of shorts containing the values in order of
     *            increasing code length.
     */
    public JPEGHuffmanTable(short[] lengths, short[] values) {
        if (lengths == null) {
            throw new IllegalArgumentException("lengths array is null!");
        }
        if (values == null) {
            throw new IllegalArgumentException("values array is null!");
        }
        if (lengths.length > 16) { // According to the spec
            throw new IllegalArgumentException("lengths array is too long!");
        }
        if (values.length > 256) { // According to the spec
            throw new IllegalArgumentException("values array is too long");
        }
        for (short length : lengths) {
            if (length < 0) {
                throw new IllegalArgumentException("Values in lengths array must be non-negative.");
            }
        }
        for (short value : values) {
            if (value < 0) {
                throw new IllegalArgumentException("Values in values array must be non-negative.");
            }
        }

        checkHuffmanTable(lengths, values);

        this.lengths = new short[lengths.length];
        this.values = new short[values.length];
        System.arraycopy(lengths, 0, this.lengths, 0, lengths.length);
        System.arraycopy(values, 0, this.values, 0, values.length);
    }

    /**
     * Gets an array of lengths in the Huffman table.
     * 
     * @return the array of short values representing the length values in the
     *         Huffman table.
     */
    public short[] getLengths() {
        short newLengths[] = new short[lengths.length];
        System.arraycopy(lengths, 0, newLengths, 0, lengths.length);
        return newLengths;
    }

    /**
     * Gets an array of values represented by increasing length of their codes.
     * 
     * @return the array of values.
     */
    public short[] getValues() {
        short newValues[] = new short[values.length];
        System.arraycopy(values, 0, newValues, 0, values.length);
        return newValues;
    }

    /**
     * Check huffman table.
     * 
     * @param lengths
     *            the lengths.
     * @param values
     *            the values.
     */
    private static void checkHuffmanTable(short[] lengths, short[] values) {
        int numLeaves = 0;
        int possibleLeaves = 2;
        for (short length : lengths) {
            numLeaves += length;
            possibleLeaves -= length;
            if (possibleLeaves < 0) {
                throw new IllegalArgumentException(
                        "Invalid Huffman table provided, lengths are incorrect.");
            }
            possibleLeaves <<= 1;
        }

        if (values.length != numLeaves) {
            throw new IllegalArgumentException(
                    "Invalid Huffman table provided, sum of lengths != values.");
        }
    }

    /**
     * Returns the string representation of this JPEGHuffmanTable object.
     * 
     * @return the string representation of this JPEGHuffmanTable object.
     */
    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("JPEGHuffmanTable:\nlengths:");
        for (short length : lengths) {
            sb.append(' ').append(length);
        }

        sb.append("\nvalues:");
        for (short value : values) {
            sb.append(' ').append(value);
        }

        return sb.toString();
    }
}
