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

import org.apache.harmony.x.imageio.plugins.jpeg.JPEGConsts;

import javax.imageio.ImageWriteParam;
import java.util.Locale;

/**
 * The JPEGImageWriteParam class allows to set JPEG Huffman tables and
 * quantization when using the JPEG writer plug-in.
 * 
 * @since Android 1.0
 */
public class JPEGImageWriteParam extends ImageWriteParam {

    /**
     * The Constant COMP_QUALITY_VALUES.
     */
    private static final float[] COMP_QUALITY_VALUES = {
            0.05f, 0.75f, 0.95f
    };

    /**
     * The Constant COMP_QUALITY_DESCRIPTIONS.
     */
    private static final String[] COMP_QUALITY_DESCRIPTIONS = {
            "Minimum useful", "Visually lossless", "Maximum useful"
    };

    /**
     * The q tables.
     */
    private JPEGQTable[] qTables;

    /**
     * The dc huffman tables.
     */
    private JPEGHuffmanTable[] dcHuffmanTables;

    /**
     * The ac huffman tables.
     */
    private JPEGHuffmanTable[] acHuffmanTables;

    /**
     * The optimize huffman tables.
     */
    private boolean optimizeHuffmanTables;

    /**
     * Instantiates a new JPEGImageWriteParam object with the specified Locale.
     * 
     * @param locale
     *            the Locale.
     */
    public JPEGImageWriteParam(Locale locale) {
        super(locale);

        canWriteProgressive = true;
        progressiveMode = ImageWriteParam.MODE_DISABLED;

        canWriteCompressed = true;
        compressionTypes = new String[] {
            "JPEG"
        };
        compressionType = compressionTypes[0];
        compressionQuality = JPEGConsts.DEFAULT_JPEG_COMPRESSION_QUALITY;
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
     * Sets the quantization and Huffman tables for using in encoding streams.
     * 
     * @param qTables
     *            the quantization tables.
     * @param DCHuffmanTables
     *            the standart DC Huffman tables.
     * @param ACHuffmanTables
     *            the standart AC huffman tables.
     */
    public void setEncodeTables(JPEGQTable[] qTables, JPEGHuffmanTable[] DCHuffmanTables,
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
     * Unset all encoded tables.
     */
    public void unsetEncodeTables() {
        qTables = null;
        dcHuffmanTables = null;
        acHuffmanTables = null;
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

    /**
     * Gets the quantization tables.
     * 
     * @return the quantization tables, or null.
     */
    public JPEGQTable[] getQTables() {
        return qTables == null ? null : qTables.clone();
    }

    @Override
    public String[] getCompressionQualityDescriptions() {
        super.getCompressionQualityDescriptions();
        return COMP_QUALITY_DESCRIPTIONS.clone();
    }

    @Override
    public float[] getCompressionQualityValues() {
        super.getCompressionQualityValues();
        return COMP_QUALITY_VALUES.clone();
    }

    /**
     * Sets the flag indicated that the writer will generate optimized Huffman
     * tables for the image as part of the writing process.
     * 
     * @param optimize
     *            the flag of optimizing huffman tables.
     */
    public void setOptimizeHuffmanTables(boolean optimize) {
        optimizeHuffmanTables = optimize;
    }

    /**
     * Returns true if the writer generates optimized Huffman tables, false
     * otherwise.
     * 
     * @return true, if the writer generates optimized Huffman tables, false
     *         otherwise.
     */
    public boolean getOptimizeHuffmanTables() {
        return optimizeHuffmanTables;
    }

    @Override
    public boolean isCompressionLossless() {
        if (getCompressionMode() != MODE_EXPLICIT) {
            throw new IllegalStateException("Compression mode not MODE_EXPLICIT!");
        }
        return false;
    }

    @Override
    public void unsetCompression() {
        if (getCompressionMode() != MODE_EXPLICIT) {
            throw new IllegalStateException("Compression mode not MODE_EXPLICIT!");
        }
        compressionQuality = JPEGConsts.DEFAULT_JPEG_COMPRESSION_QUALITY;
    }
}
