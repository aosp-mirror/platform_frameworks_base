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

/**
 * The Class DataBufferDouble is the subclass of DataBuffer for the case where
 * the underlying data is of type double.
 * 
 * @since Android 1.0
 */
public final class DataBufferDouble extends DataBuffer {

    /**
     * The data.
     */
    double data[][];

    /**
     * Instantiates a new data buffer of type double.
     * 
     * @param dataArrays
     *            the data arrays to copy the data from.
     * @param size
     *            the length (number of elements) to use from the data arrays.
     * @param offsets
     *            the starting indices for reading the data from the internal
     *            data arrays.
     */
    public DataBufferDouble(double dataArrays[][], int size, int offsets[]) {
        super(TYPE_DOUBLE, size, dataArrays.length, offsets);
        data = dataArrays.clone();
    }

    /**
     * Instantiates a new data buffer of type double.
     * 
     * @param dataArrays
     *            the data arrays to copy the data from.
     * @param size
     *            the length (number of elements) to use from the data arrays.
     */
    public DataBufferDouble(double dataArrays[][], int size) {
        super(TYPE_DOUBLE, size, dataArrays.length);
        data = dataArrays.clone();
    }

    /**
     * Instantiates a new data buffer of type double with a single underlying
     * array of data.
     * 
     * @param dataArray
     *            the data array to copy the data from.
     * @param size
     *            the length (number of elements) to use.
     * @param offset
     *            the starting index to use when reading the data.
     */
    public DataBufferDouble(double dataArray[], int size, int offset) {
        super(TYPE_DOUBLE, size, 1, offset);
        data = new double[1][];
        data[0] = dataArray;
    }

    /**
     * Instantiates a new data buffer of type double with a single underlying
     * array of data starting at index 0.
     * 
     * @param dataArray
     *            the data array to copy the data from.
     * @param size
     *            the length (number of elements) to use.
     */
    public DataBufferDouble(double dataArray[], int size) {
        super(TYPE_DOUBLE, size);
        data = new double[1][];
        data[0] = dataArray;
    }

    /**
     * Instantiates a new empty data buffer of type double with offsets equal to
     * zero.
     * 
     * @param size
     *            the length (number of elements) to use from the data arrays.
     * @param numBanks
     *            the number of data arrays to create.
     */
    public DataBufferDouble(int size, int numBanks) {
        super(TYPE_DOUBLE, size, numBanks);
        data = new double[numBanks][];
        int i = 0;
        while (i < numBanks) {
            data[i++] = new double[size];
        }
    }

    /**
     * Instantiates a new empty data buffer of type double with a single
     * underlying array of data starting at index 0.
     * 
     * @param size
     *            the length (number of elements) to use.
     */
    public DataBufferDouble(int size) {
        super(TYPE_DOUBLE, size);
        data = new double[1][];
        data[0] = new double[size];
    }

    @Override
    public void setElem(int bank, int i, int val) {
        data[bank][offsets[bank] + i] = val;
        notifyChanged();
    }

    @Override
    public void setElemFloat(int bank, int i, float val) {
        data[bank][offsets[bank] + i] = val;
        notifyChanged();
    }

    @Override
    public void setElemDouble(int bank, int i, double val) {
        data[bank][offsets[bank] + i] = val;
        notifyChanged();
    }

    @Override
    public void setElem(int i, int val) {
        data[0][offset + i] = val;
        notifyChanged();
    }

    @Override
    public int getElem(int bank, int i) {
        return (int)(data[bank][offsets[bank] + i]);
    }

    @Override
    public float getElemFloat(int bank, int i) {
        return (float)(data[bank][offsets[bank] + i]);
    }

    @Override
    public double getElemDouble(int bank, int i) {
        return data[bank][offsets[bank] + i];
    }

    @Override
    public void setElemFloat(int i, float val) {
        data[0][offset + i] = val;
        notifyChanged();
    }

    @Override
    public void setElemDouble(int i, double val) {
        data[0][offset + i] = val;
        notifyChanged();
    }

    /**
     * Gets the data of the specified internal data array.
     * 
     * @param bank
     *            the index of the desired data array.
     * @return the data.
     */
    public double[] getData(int bank) {
        notifyTaken();
        return data[bank];
    }

    @Override
    public int getElem(int i) {
        return (int)(data[0][offset + i]);
    }

    @Override
    public float getElemFloat(int i) {
        return (float)(data[0][offset + i]);
    }

    @Override
    public double getElemDouble(int i) {
        return data[0][offset + i];
    }

    /**
     * Gets the bank data.
     * 
     * @return the bank data.
     */
    public double[][] getBankData() {
        notifyTaken();
        return data.clone();
    }

    /**
     * Gets the data of the first data array.
     * 
     * @return the data.
     */
    public double[] getData() {
        notifyTaken();
        return data[0];
    }
}
