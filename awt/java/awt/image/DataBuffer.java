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

import org.apache.harmony.awt.gl.image.DataBufferListener;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The Class DataBuffer is a wrapper class for a data array to be used for the
 * situation where a suite of functionality acts on a set of data in a
 * consistent way even though the primitive type of the data may vary from one
 * use to the next.
 * 
 * @since Android 1.0
 */
public abstract class DataBuffer {

    /**
     * The Constant TYPE_BYTE.
     */
    public static final int TYPE_BYTE = 0;

    /**
     * The Constant TYPE_USHORT.
     */
    public static final int TYPE_USHORT = 1;

    /**
     * The Constant TYPE_SHORT.
     */
    public static final int TYPE_SHORT = 2;

    /**
     * The Constant TYPE_INT.
     */
    public static final int TYPE_INT = 3;

    /**
     * The Constant TYPE_FLOAT.
     */
    public static final int TYPE_FLOAT = 4;

    /**
     * The Constant TYPE_DOUBLE.
     */
    public static final int TYPE_DOUBLE = 5;

    /**
     * The Constant TYPE_UNDEFINED.
     */
    public static final int TYPE_UNDEFINED = 32;

    /**
     * The data type indicates the primitive type of the data in this
     * DataBuffer.
     */
    protected int dataType;

    /**
     * The number of data arrays in this DataBuffer.
     */
    protected int banks;

    /**
     * The starting index for reading the data from the first (or only) internal
     * data array.
     */
    protected int offset;

    /**
     * The length (number of elements) of the data arrays.
     */
    protected int size;

    /**
     * The starting indices for reading the data from the internal data arrays.
     */
    protected int offsets[];

    /**
     * The data changed.
     */
    boolean dataChanged = true;

    /**
     * The data taken.
     */
    boolean dataTaken = false;

    /**
     * The listener.
     */
    DataBufferListener listener;

    static {
        AwtImageBackdoorAccessorImpl.init();
    }

    /**
     * Instantiates a new data buffer.
     * 
     * @param dataType
     *            the data type.
     * @param size
     *            the length (number of elements) of the data arrays.
     * @param numBanks
     *            the number of data arrays to create.
     * @param offsets
     *            the starting indices for reading the data from the internal
     *            data arrays.
     */
    protected DataBuffer(int dataType, int size, int numBanks, int[] offsets) {
        this.dataType = dataType;
        this.size = size;
        this.banks = numBanks;
        this.offsets = offsets.clone();
        this.offset = offsets[0];
    }

    /**
     * Instantiates a new data buffer with all of the data arrays starting at
     * the same index.
     * 
     * @param dataType
     *            the data type.
     * @param size
     *            the length (number of elements) of the data arrays.
     * @param numBanks
     *            the number of data arrays to create.
     * @param offset
     *            the offset to use for all of the data arrays.
     */
    protected DataBuffer(int dataType, int size, int numBanks, int offset) {
        this.dataType = dataType;
        this.size = size;
        this.banks = numBanks;
        this.offset = offset;
        this.offsets = new int[numBanks];
        int i = 0;
        while (i < numBanks) {
            offsets[i++] = offset;
        }
    }

    /**
     * Instantiates a new data buffer with all of the data arrays read from the
     * beginning (at offset zero).
     * 
     * @param dataType
     *            the data type.
     * @param size
     *            the length (number of elements) of the data arrays.
     * @param numBanks
     *            the number of data arrays to create.
     */
    protected DataBuffer(int dataType, int size, int numBanks) {
        this.dataType = dataType;
        this.size = size;
        this.banks = numBanks;
        this.offset = 0;
        this.offsets = new int[numBanks];
    }

    /**
     * Instantiates a new data buffer with one internal data array read from the
     * beginning (at offset zero).
     * 
     * @param dataType
     *            the data type.
     * @param size
     *            the length (number of elements) of the data arrays.
     */
    protected DataBuffer(int dataType, int size) {
        this.dataType = dataType;
        this.size = size;
        this.banks = 1;
        this.offset = 0;
        this.offsets = new int[1];
    }

    /**
     * Sets the data value in the specified array at the specified index.
     * 
     * @param bank
     *            the internal array to the data to.
     * @param i
     *            the index within the array where the data should be written.
     * @param val
     *            the value to write into the array.
     */
    public abstract void setElem(int bank, int i, int val);

    /**
     * Sets the float data value in the specified array at the specified index.
     * 
     * @param bank
     *            the internal array to the data to.
     * @param i
     *            the index within the array where the data should be written.
     * @param val
     *            the value to write into the array.
     */
    public void setElemFloat(int bank, int i, float val) {
        setElem(bank, i, (int)val);
    }

    /**
     * Sets the double data value in the specified array at the specified index.
     * 
     * @param bank
     *            the internal array to the data to.
     * @param i
     *            the index within the array where the data should be written.
     * @param val
     *            the value to write into the array.
     */
    public void setElemDouble(int bank, int i, double val) {
        setElem(bank, i, (int)val);
    }

    /**
     * Sets the data value in the first array at the specified index.
     * 
     * @param i
     *            the index within the array where the data should be written.
     * @param val
     *            the value to write into the array.
     */
    public void setElem(int i, int val) {
        setElem(0, i, val);
    }

    /**
     * Gets the data value from the specified data array at the specified index.
     * 
     * @param bank
     *            the data array to read from.
     * @param i
     *            the index within the array where the data should be read.
     * @return the data element.
     */
    public abstract int getElem(int bank, int i);

    /**
     * Gets the float-type data value from the specified data array at the
     * specified index.
     * 
     * @param bank
     *            the data array to read from.
     * @param i
     *            the index within the array where the data should be read.
     * @return the data element.
     */
    public float getElemFloat(int bank, int i) {
        return getElem(bank, i);
    }

    /**
     * Gets the double-type data value from the specified data array at the
     * specified index.
     * 
     * @param bank
     *            the data array to read from.
     * @param i
     *            the index within the array where the data should be read.
     * @return the data element.
     */
    public double getElemDouble(int bank, int i) {
        return getElem(bank, i);
    }

    /**
     * Sets the float data value in the first array at the specified index.
     * 
     * @param i
     *            the index within the array where the data should be written.
     * @param val
     *            the value to write into the array.
     */
    public void setElemFloat(int i, float val) {
        setElemFloat(0, i, val);
    }

    /**
     * Sets the double data value in the first array at the specified index.
     * 
     * @param i
     *            the index within the array where the data should be written.
     * @param val
     *            the value to write into the array.
     */
    public void setElemDouble(int i, double val) {
        setElemDouble(0, i, val);
    }

    /**
     * Gets the data value from the first data array at the specified index and
     * returns it as an integer.
     * 
     * @param i
     *            the index within the array where the data should be read.
     * @return the data element.
     */
    public int getElem(int i) {
        return getElem(0, i);
    }

    /**
     * Gets the data value from the first data array at the specified index and
     * returns it as a float.
     * 
     * @param i
     *            the index within the array where the data should be read.
     * @return the data element.
     */
    public float getElemFloat(int i) {
        return getElem(0, i);
    }

    /**
     * Gets the data value from the first data array at the specified index and
     * returns it as a double.
     * 
     * @param i
     *            the index within the array where the data should be read.
     * @return the data element.
     */
    public double getElemDouble(int i) {
        return getElem(i);
    }

    /**
     * Gets the array giving the offsets corresponding to the internal data
     * arrays.
     * 
     * @return the array of offsets.
     */
    public int[] getOffsets() {
        return offsets;
    }

    /**
     * Gets the size in bits of the primitive data type.
     * 
     * @return the size in bits of the primitive data type.
     */
    public int getSize() {
        return size;
    }

    /**
     * Gets the offset corresponding to the first internal data array.
     * 
     * @return the offset.
     */
    public int getOffset() {
        return offset;
    }

    /**
     * Gets the number of data arrays in this DataBuffer.
     * 
     * @return the number of data arrays.
     */
    public int getNumBanks() {
        return banks;
    }

    /**
     * Gets the primitive type of this buffer's data.
     * 
     * @return the data type.
     */
    public int getDataType() {
        return this.dataType;
    }

    /**
     * Gets the size in bits of the primitive data type.
     * 
     * @param type
     *            the primitive type.
     * @return the size in bits of the primitive data type.
     */
    public static int getDataTypeSize(int type) {
        switch (type) {

            case TYPE_BYTE:
                return 8;

            case TYPE_USHORT:
            case TYPE_SHORT:
                return 16;

            case TYPE_INT:
            case TYPE_FLOAT:
                return 32;

            case TYPE_DOUBLE:
                return 64;

            default:
                // awt.22C=Unknown data type {0}
                throw new IllegalArgumentException(Messages.getString("awt.22C", type)); //$NON-NLS-1$
        }
    }

    /**
     * Notifies the listener that the data has changed.
     */
    void notifyChanged() {
        if (listener != null && !dataChanged) {
            dataChanged = true;
            listener.dataChanged();
        }
    }

    /**
     * Notifies the listener that the data has been released.
     */
    void notifyTaken() {
        if (listener != null && !dataTaken) {
            dataTaken = true;
            listener.dataTaken();
        }
    }

    /**
     * Release the data.
     */
    void releaseData() {
        if (listener != null && dataTaken) {
            dataTaken = false;
            listener.dataReleased();
        }
    }

    /**
     * Adds the data buffer listener.
     * 
     * @param listener
     *            the listener.
     */
    void addDataBufferListener(DataBufferListener listener) {
        this.listener = listener;
    }

    /**
     * Removes the data buffer listener.
     */
    void removeDataBufferListener() {
        listener = null;
    }

    /**
     * Validate.
     */
    void validate() {
        dataChanged = false;
    }

}
