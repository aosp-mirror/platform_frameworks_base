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
 * @author Oleg V. Khaschansky
 * @version $Revision$
 *
 * @date: Oct 14, 2005
 */

package java.awt.image;

/**
 * The ByteLookupTable class provides functionality for lookup operations, and
 * is defined by an input byte array for bands or components of image and an
 * offset value. The offset value will be subtracted from the input values
 * before indexing the input arrays. The output of a lookup operation is
 * represented as an array of unsigned bytes.
 * 
 * @since Android 1.0
 */
public class ByteLookupTable extends LookupTable {

    /**
     * The data.
     */
    private byte data[][];

    /**
     * Instantiates a new ByteLookupTable with the specified offset value and
     * the specified byte array which represents the lookup table for all bands.
     * 
     * @param offset
     *            the offset value.
     * @param data
     *            the data array of bytes.
     */
    public ByteLookupTable(int offset, byte[] data) {
        super(offset, 1);
        if (data.length < 1)
            throw new IllegalArgumentException("Length of data should not be less then one");
        this.data = new byte[1][data.length];
        // The data array stored as a reference
        this.data[0] = data;
    }

    /**
     * Instantiates a new ByteLookupTable with the specified offset value and
     * the specified byte array of arrays which represents the lookup table for
     * each band.
     * 
     * @param offset
     *            the offset value.
     * @param data
     *            the data array of bytes array for each band.
     */
    public ByteLookupTable(int offset, byte[][] data) {
        super(offset, data.length);
        this.data = new byte[data.length][data[0].length];
        for (int i = 0; i < data.length; i++) {
            // The data array for each band stored as a reference
            this.data[i] = data[i];
        }
    }

    /**
     * Gets the lookup table of this ByteLookupTable object. If this
     * ByteLookupTable object has one byte array for all bands, the returned
     * array length is one.
     * 
     * @return the lookup table of this ByteLookupTable object.
     */
    public final byte[][] getTable() {
        // Returns data by reference
        return data;
    }

    @Override
    public int[] lookupPixel(int[] src, int[] dst) {
        if (dst == null) {
            dst = new int[src.length];
        }

        int offset = getOffset();
        if (getNumComponents() == 1) {
            for (int i = 0; i < src.length; i++) {
                dst[i] = data[0][src[i] - offset];
            }
        } else {
            for (int i = 0; i < getNumComponents(); i++) {
                dst[i] = data[i][src[i] - offset];
            }
        }

        return dst;
    }

    /**
     * Returns a byte array which contains samples of the specified pixel which
     * is translated with the lookup table of this ByteLookupTable object. The
     * resulted array is stored to the dst array.
     * 
     * @param src
     *            the source array.
     * @param dst
     *            the destination array where the result can be stored.
     * @return the byte array of translated samples of a pixel.
     */
    public byte[] lookupPixel(byte[] src, byte[] dst) {
        if (dst == null) {
            dst = new byte[src.length];
        }

        int offset = getOffset();
        if (getNumComponents() == 1) {
            for (int i = 0; i < src.length; i++) {
                dst[i] = data[0][src[i] - offset];
            }
        } else {
            for (int i = 0; i < getNumComponents(); i++) {
                dst[i] = data[i][src[i] - offset];
            }
        }

        return dst;
    }
}
