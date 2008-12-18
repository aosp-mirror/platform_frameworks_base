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

import java.util.Arrays;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The SinglePixelPackedSampleModel class represents pixel data where several
 * samples combine to create a single pixel and are stored in a single data
 * array element. This class supports TYPE_BYTE, TYPE_USHORT, TYPE_INT data
 * types.
 * 
 * @since Android 1.0
 */
public class SinglePixelPackedSampleModel extends SampleModel {

    /**
     * The bit masks.
     */
    private int bitMasks[];

    /**
     * The bit offsets.
     */
    private int bitOffsets[];

    /**
     * The bit sizes.
     */
    private int bitSizes[];

    /**
     * The scanline stride.
     */
    private int scanlineStride;

    /**
     * The max bit size.
     */
    private int maxBitSize;

    /**
     * Instantiates a new SinglePixelPackedSampleModel with the specified
     * parameters.
     * 
     * @param dataType
     *            the data type of samples.
     * @param w
     *            the width of the image data.
     * @param h
     *            the height of the image data.
     * @param bitMasks
     *            the bit masks for all the bands.
     */
    public SinglePixelPackedSampleModel(int dataType, int w, int h, int bitMasks[]) {
        this(dataType, w, h, w, bitMasks);
    }

    /**
     * Instantiates a new SinglePixelPackedSampleModel with the specified
     * parameters.
     * 
     * @param dataType
     *            the data type of the samples.
     * @param w
     *            the width of the image data.
     * @param h
     *            the height of the image data.
     * @param scanlineStride
     *            the scanline stride of the image data.
     * @param bitMasks
     *            the bit masks for all the bands.
     */
    public SinglePixelPackedSampleModel(int dataType, int w, int h, int scanlineStride,
            int bitMasks[]) {

        super(dataType, w, h, bitMasks.length);

        if (dataType != DataBuffer.TYPE_BYTE && dataType != DataBuffer.TYPE_USHORT
                && dataType != DataBuffer.TYPE_INT) {
            // awt.61=Unsupported data type: {0}
            throw new IllegalArgumentException(Messages.getString("awt.61", //$NON-NLS-1$
                    dataType));
        }

        this.scanlineStride = scanlineStride;
        this.bitMasks = bitMasks.clone();
        this.bitOffsets = new int[this.numBands];
        this.bitSizes = new int[this.numBands];

        this.maxBitSize = 0;

        for (int i = 0; i < this.numBands; i++) {
            int offset = 0;
            int size = 0;
            int mask = bitMasks[i];

            if (mask != 0) {
                while ((mask & 1) == 0) {
                    mask >>>= 1;
                    offset++;
                }

                while ((mask & 1) == 1) {
                    mask >>>= 1;
                    size++;
                }

                if (mask != 0) {
                    // awt.62=Wrong mask : {0}
                    throw new IllegalArgumentException(Messages.getString("awt.62", bitMasks[i])); //$NON-NLS-1$
                }
            }

            this.bitOffsets[i] = offset;
            this.bitSizes[i] = size;

            if (this.maxBitSize < size) {
                this.maxBitSize = size;
            }

        }

    }

    @Override
    public Object getDataElements(int x, int y, Object obj, DataBuffer data) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        switch (getTransferType()) {
            case DataBuffer.TYPE_BYTE:
                byte bdata[];
                if (obj == null) {
                    bdata = new byte[1];
                } else {
                    bdata = (byte[])obj;
                }

                bdata[0] = (byte)data.getElem(y * scanlineStride + x);
                obj = bdata;
                break;
            case DataBuffer.TYPE_USHORT:
                short sdata[];
                if (obj == null) {
                    sdata = new short[1];
                } else {
                    sdata = (short[])obj;
                }

                sdata[0] = (short)data.getElem(y * scanlineStride + x);
                obj = sdata;
                break;
            case DataBuffer.TYPE_INT:
                int idata[];
                if (obj == null) {
                    idata = new int[1];
                } else {
                    idata = (int[])obj;
                }

                idata[0] = data.getElem(y * scanlineStride + x);
                obj = idata;
                break;
        }
        return obj;
    }

    @Override
    public void setDataElements(int x, int y, Object obj, DataBuffer data) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        switch (getTransferType()) {
            case DataBuffer.TYPE_BYTE:
                data.setElem(y * scanlineStride + x, ((byte[])obj)[0] & 0xff);
                break;
            case DataBuffer.TYPE_USHORT:
                data.setElem(y * scanlineStride + x, ((short[])obj)[0] & 0xffff);
                break;
            case DataBuffer.TYPE_INT:
                data.setElem(y * scanlineStride + x, ((int[])obj)[0]);
                break;
        }
    }

    /**
     * Compares this SinglePixelPackedSampleModel object with the specified
     * object.
     * 
     * @param o
     *            the Object to be compared.
     * @return true, if this SinglePixelPackedSampleModel object is equal to the
     *         specified object, false otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if ((o == null) || !(o instanceof SinglePixelPackedSampleModel)) {
            return false;
        }

        SinglePixelPackedSampleModel model = (SinglePixelPackedSampleModel)o;
        return this.width == model.width && this.height == model.height
                && this.numBands == model.numBands && this.dataType == model.dataType
                && Arrays.equals(this.bitMasks, model.bitMasks)
                && Arrays.equals(this.bitOffsets, model.bitOffsets)
                && Arrays.equals(this.bitSizes, model.bitSizes)
                && this.scanlineStride == model.scanlineStride;
    }

    @Override
    public SampleModel createSubsetSampleModel(int bands[]) {
        if (bands.length > this.numBands) {
            // awt.64=The number of the bands in the subset is greater than the
            // number of bands in the sample model
            throw new RasterFormatException(Messages.getString("awt.64")); //$NON-NLS-1$
        }

        int masks[] = new int[bands.length];
        for (int i = 0; i < bands.length; i++) {
            masks[i] = this.bitMasks[bands[i]];
        }
        return new SinglePixelPackedSampleModel(this.dataType, this.width, this.height,
                this.scanlineStride, masks);
    }

    @Override
    public SampleModel createCompatibleSampleModel(int w, int h) {
        return new SinglePixelPackedSampleModel(this.dataType, w, h, this.bitMasks);
    }

    @Override
    public int[] getPixel(int x, int y, int iArray[], DataBuffer data) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        int pixel[];
        if (iArray == null) {
            pixel = new int[this.numBands];
        } else {
            pixel = iArray;
        }

        for (int i = 0; i < this.numBands; i++) {
            pixel[i] = getSample(x, y, i, data);
        }

        return pixel;
    }

    @Override
    public void setPixel(int x, int y, int iArray[], DataBuffer data) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        for (int i = 0; i < this.numBands; i++) {
            setSample(x, y, i, iArray[i], data);
        }
    }

    @Override
    public int getSample(int x, int y, int b, DataBuffer data) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        int sample = data.getElem(y * scanlineStride + x);
        return ((sample & this.bitMasks[b]) >>> this.bitOffsets[b]);
    }

    @Override
    public int[] getPixels(int x, int y, int w, int h, int iArray[], DataBuffer data) {
        if ((x < 0) || (y < 0) || ((long)x + (long)w > this.width)
                || ((long)y + (long)h > this.height)) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }

        int pixels[];

        if (iArray == null) {
            pixels = new int[w * h * this.numBands];
        } else {
            pixels = iArray;
        }

        int idx = 0;

        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                for (int n = 0; n < this.numBands; n++) {
                    pixels[idx++] = getSample(j, i, n, data);
                }
            }
        }
        return pixels;
    }

    @Override
    public void setPixels(int x, int y, int w, int h, int iArray[], DataBuffer data) {
        if ((x < 0) || (y < 0) || ((long)x + (long)w > this.width)
                || ((long)y + (long)h > this.height)) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }

        int idx = 0;

        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                for (int n = 0; n < this.numBands; n++) {
                    setSample(j, i, n, iArray[idx++], data);
                }
            }
        }
    }

    @Override
    public void setSample(int x, int y, int b, int s, DataBuffer data) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        int tmp = data.getElem(y * scanlineStride + x);
        tmp &= ~this.bitMasks[b];
        tmp |= (s << this.bitOffsets[b]) & this.bitMasks[b];
        data.setElem(y * scanlineStride + x, tmp);
    }

    @Override
    public int[] getSamples(int x, int y, int w, int h, int b, int iArray[], DataBuffer data) {
        if ((x < 0) || (y < 0) || ((long)x + (long)w > this.width)
                || ((long)y + (long)h > this.height)) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }

        int samples[];
        int idx = 0;

        if (iArray == null) {
            samples = new int[w * h];
        } else {
            samples = iArray;
        }

        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                samples[idx++] = getSample(j, i, b, data);
            }
        }

        return samples;
    }

    @Override
    public void setSamples(int x, int y, int w, int h, int b, int iArray[], DataBuffer data) {
        if ((x < 0) || (y < 0) || ((long)x + (long)w > this.width)
                || ((long)y + (long)h > this.height)) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }

        int idx = 0;
        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                setSample(x + j, y + i, b, iArray[idx++], data);
            }
        }
    }

    @Override
    public DataBuffer createDataBuffer() {
        DataBuffer data = null;
        int size = (this.height - 1) * scanlineStride + width;

        switch (this.dataType) {
            case DataBuffer.TYPE_BYTE:
                data = new DataBufferByte(size);
                break;
            case DataBuffer.TYPE_USHORT:
                data = new DataBufferUShort(size);
                break;
            case DataBuffer.TYPE_INT:
                data = new DataBufferInt(size);
                break;
        }
        return data;
    }

    /**
     * Gets the offset of the specified pixel in the data array.
     * 
     * @param x
     *            the X coordinate of the specified pixel.
     * @param y
     *            the Y coordinate of the specified pixel.
     * @return the offset of the specified pixel.
     */
    public int getOffset(int x, int y) {
        return (y * scanlineStride + x);
    }

    @Override
    public int getSampleSize(int band) {
        return bitSizes[band];
    }

    @Override
    public int[] getSampleSize() {
        return bitSizes.clone();
    }

    /**
     * Gets an array of the bit offsets of the data array elements.
     * 
     * @return an array of the bit offsets.
     */
    public int[] getBitOffsets() {
        return bitOffsets.clone();
    }

    /**
     * Gets an array of the bit masks for all bands.
     * 
     * @return an array of the bit masks for all bands.
     */
    public int[] getBitMasks() {
        return bitMasks.clone();
    }

    /**
     * Returns a hash code of this MultiPixelPackedSampleModel class.
     * 
     * @return the hash code of this MultiPixelPackedSampleModel class.
     */
    @Override
    public int hashCode() {
        int hash = 0;
        int tmp = 0;

        hash = width;
        tmp = hash >>> 24;
        hash <<= 8;
        hash |= tmp;
        hash ^= height;
        tmp = hash >>> 24;
        hash <<= 8;
        hash |= tmp;
        hash ^= numBands;
        tmp = hash >>> 24;
        hash <<= 8;
        hash |= tmp;
        hash ^= dataType;
        tmp = hash >>> 24;
        hash <<= 8;
        hash |= tmp;
        for (int element : bitMasks) {
            hash ^= element;
            tmp = hash >>> 24;
            hash <<= 8;
            hash |= tmp;
        }
        for (int element : bitOffsets) {
            hash ^= element;
            tmp = hash >>> 24;
            hash <<= 8;
            hash |= tmp;
        }
        for (int element : bitSizes) {
            hash ^= element;
            tmp = hash >>> 24;
            hash <<= 8;
            hash |= tmp;
        }
        hash ^= scanlineStride;
        return hash;
    }

    /**
     * Gets the scanline stride.
     * 
     * @return the scanline stride
     */
    public int getScanlineStride() {
        return this.scanlineStride;
    }

    @Override
    public int getNumDataElements() {
        return 1;
    }

}
