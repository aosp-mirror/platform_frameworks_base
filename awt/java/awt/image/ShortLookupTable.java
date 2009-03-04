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
 * The ShortLookupTable class provides provides functionality for lookup
 * operations, and is defined by an input short array for bands or components of
 * image and an offset value. The offset value will be subtracted from the input
 * values before indexing the input arrays. The output of a lookup operation is
 * represented as an unsigned short array.
 * 
 * @since Android 1.0
 */
public class ShortLookupTable extends LookupTable {

    /**
     * The data.
     */
    private short data[][];

    /**
     * Instantiates a new ShortLookupTable with the specified offset value and
     * the specified short array which represents lookup table for all bands.
     * 
     * @param offset
     *            the offset value.
     * @param data
     *            the data array.
     */
    public ShortLookupTable(int offset, short[] data) {
        super(offset, 1);
        this.data = new short[1][data.length];
        // The data array stored as a reference
        this.data[0] = data;
    }

    /**
     * Instantiates a new ShortLookupTable with the specified offset value and
     * the specified short array of arrays which represents lookup table for
     * each band.
     * 
     * @param offset
     *            the offset value.
     * @param data
     *            the data array of arrays for each band.
     */
    public ShortLookupTable(int offset, short[][] data) {
        super(offset, data.length);
        this.data = new short[data.length][data[0].length];
        for (int i = 0; i < data.length; i++) {
            // The data array for each band stored as a reference
            this.data[i] = data[i];
        }
    }

    /**
     * Gets the lookup table of this ShortLookupTable object. If this
     * ShortLookupTable object has one short array for all bands, the returned
     * array length is one.
     * 
     * @return the lookup table of this ShortLookupTable object.
     */
    public final short[][] getTable() {
        return data;
    }

    /**
     * Returns a short array which contains samples of the specified pixel which
     * is translated with the lookup table of this ShortLookupTable object. The
     * resulted array is stored to the dst array.
     * 
     * @param src
     *            the source array.
     * @param dst
     *            the destination array where the result can be stored.
     * @return the short array of translated samples of a pixel.
     */
    public short[] lookupPixel(short[] src, short[] dst) {
        if (dst == null) {
            dst = new short[src.length];
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
}
