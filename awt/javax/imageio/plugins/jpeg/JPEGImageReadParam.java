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

import javax.imageio.ImageReadParam;

/**
 * The JPEGImageReadParam class provides functionality to set Huffman tables and
 * quantization tables when using the JPEG reader plug-in.
 * 
 * @since Android 1.0
 */
public class JPEGImageReadParam extends ImageReadParam {

    /**
     * The q tables.
     */
    private JPEGQTable qTables[];

    /**
     * The dc huffman tables.
     */
    private JPEGHuffmanTable dcHuffmanTables[];

    /**
     * The ac huffman tables.
     */
    private JPEGHuffmanTable acHuffmanTables[];

    /**
     * Instantiates a new JPEGImageReadParam.
     */
    public JPEGImageReadParam() {
    }

    /**
     * Returns true if tables are set, false otherwise.
     * 
     * @return true, if tables are set, false otherwise.
     */
    public boolean areTablesSet() {
        return qTables != null;
    }

    /**
     * Sets the quantization and Huffman tables for using in decoding streams.
     * 
     * @param qTables
     *            the quantization tables.
     * @param DCHuffmanTables
     *            the standart DC Huffman tables.
     * @param ACHuffmanTables
     *            the standart AC huffman tables.
     */
    public void setDecodeTables(JPEGQTable[] qTables, JPEGHuffmanTable[] DCHuffmanTables,
            JPEGHuffmanTable[] ACHuffmanTables) {
        if (qTables == null || DCHuffmanTables == null || ACHuffmanTables == null) {
            throw new IllegalArgumentException("Invalid JPEG table arrays");
        }
        if (DCHuffmanTables.length != ACHuffmanTables.length) {
            throw new IllegalArgumentException("Invalid JPEG table arrays");
        }
        if (qTables.length > 4 || DCHuffmanTables.length > 4) {
            throw new IllegalArgumentException("Invalid JPEG table arrays");
        }

        // Do the shallow copy, it should be enough
        this.qTables = qTables.clone();
        dcHuffmanTables = DCHuffmanTables.clone();
        acHuffmanTables = ACHuffmanTables.clone();
    }

    /**
     * Unset all decoded tables.
     */
    public void unsetDecodeTables() {
        qTables = null;
        dcHuffmanTables = null;
        acHuffmanTables = null;
    }

    /**
     * Gets the quantization tables.
     * 
     * @return the quantization tables, or null.
     */
    public JPEGQTable[] getQTables() {
        return qTables == null ? null : qTables.clone();
    }

    /**
     * Gets the DC Huffman tables.
     * 
     * @return the DC Huffman tables which are set, or null.
     */
    public JPEGHuffmanTable[] getDCHuffmanTables() {
        return dcHuffmanTables == null ? null : dcHuffmanTables.clone();
    }

    /**
     * Gets the AC Huffman tables.
     * 
     * @return the AC Huffman tables which are set, or null.
     */
    public JPEGHuffmanTable[] getACHuffmanTables() {
        return acHuffmanTables == null ? null : acHuffmanTables.clone();
    }
}
