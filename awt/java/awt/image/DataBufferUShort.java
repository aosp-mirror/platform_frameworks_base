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
 * @author Igor V. Stolyarov
 * @version $Revision$
 */

package java.awt.image;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The Class DataBufferUShort is the subclass of DataBuffer for the case where
 * the underlying data is unsigned short.
 * 
 * @since Android 1.0
 */
public final class DataBufferUShort extends DataBuffer {

    /**
     * The data.
     */
    short data[][];

    /**
     * Instantiates a new data buffer of type unsigned short.
     * 
     * @param dataArrays
     *            the data arrays to copy the data from.
     * @param size
     *            the length (number of elements) to use from the data arrays.
     * @param offsets
     *            the starting indices for reading the data from the internal
     *            data arrays.
     */
    public DataBufferUShort(short dataArrays[][], int size, int offsets[]) {
        super(TYPE_USHORT, size, dataArrays.length, offsets);
        for (int i = 0; i < dataArrays.length; i++) {
            if (dataArrays[i].length < offsets[i] + size) {
                // awt.28d=Length of dataArray[{0}] is less than size +
                // offset[{1}]
                throw new IllegalArgumentException(Messages.getString("awt.28D", i, i)); //$NON-NLS-1$
            }
        }
        data = dataArrays.clone();
    }

    /**
     * Instantiates a new data buffer of type unsigned short.
     * 
     * @param dataArrays
     *            the data arrays to copy the data from.
     * @param size
     *            the length (number of elements) to use from the data arrays.
     */
    public DataBufferUShort(short dataArrays[][], int size) {
        super(TYPE_USHORT, size, dataArrays.length);
        data = dataArrays.clone();
    }

    /**
     * Instantiates a new data buffer of type unsigned short with a single
     * underlying array of data.
     * 
     * @param dataArray
     *            the data array to copy the data from.
     * @param size
     *            the length (number of elements) to use.
     * @param offset
     *            the starting index to use when reading the data.
     */
    public DataBufferUShort(short dataArray[], int size, int offset) {
        super(TYPE_USHORT, size, 1, offset);
        if (dataArray.length < size + offset) {
            // awt.28E=Length of dataArray is less than size + offset
            throw new IllegalArgumentException(Messages.getString("awt.28E")); //$NON-NLS-1$
        }
        data = new short[1][];
        data[0] = dataArray;
    }

    /**
     * Instantiates a new data buffer of type unsigned short with a single
     * underlying array of data starting at index 0.
     * 
     * @param dataArray
     *            the data array to copy the data from.
     * @param size
     *            the length (number of elements) to use.
     */
    public DataBufferUShort(short dataArray[], int size) {
        super(TYPE_USHORT, size);
        data = new short[1][];
        data[0] = dataArray;
    }

    /**
     * Instantiates a new empty data buffer of type unsigned short with offsets
     * equal to zero.
     * 
     * @param size
     *            the length (number of elements) to use from the data arrays.
     * @param numBanks
     *            the number of data arrays to create.
     */
    public DataBufferUShort(int size, int numBanks) {
        super(TYPE_USHORT, size, numBanks);
        data = new short[numBanks][];
        int i = 0;
        while (i < numBanks) {
            data[i++] = new short[size];
        }
    }

    /**
     * Instantiates a new empty data buffer of type unsigned short with a single
     * underlying array of data starting at index 0.
     * 
     * @param size
     *            the length (number of elements) to use.
     */
    public DataBufferUShort(int size) {
        super(TYPE_USHORT, size);
        data = new short[1][];
        data[0] = new short[size];
    }

    @Override
    public void setElem(int bank, int i, int val) {
        data[bank][offsets[bank] + i] = (short)val;
        notifyChanged();
    }

    @Override
    public void setElem(int i, int val) {
        data[0][offset + i] = (short)val;
        notifyChanged();
    }

    @Override
    public int getElem(int bank, int i) {
        return (data[bank][offsets[bank] + i]) & 0xffff;
    }

    /**
     * Gets the data of the specified internal data array.
     * 
     * @param bank
     *            the index of the desired data array.
     * @return the data.
     */
    public short[] getData(int bank) {
        notifyTaken();
        return data[bank];
    }

    @Override
    public int getElem(int i) {
        return (data[0][offset + i]) & 0xffff;
    }

    /**
     * Gets the bank data.
     * 
     * @return the bank data.
     */
    public short[][] getBankData() {
        notifyTaken();
        return data.clone();
    }

    /**
     * Gets the data of the first data array.
     * 
     * @return the data.
     */
    public short[] getData() {
        notifyTaken();
        return data[0];
    }
}
