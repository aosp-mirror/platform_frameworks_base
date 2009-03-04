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

import java.awt.Point;
import java.awt.Rectangle;

import org.apache.harmony.awt.gl.image.OrdinaryWritableRaster;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The Raster class represents a rectangular area of pixels. This class is
 * defined by DataBuffer and SampleModel objects. The DataBuffer object stores
 * sample values and DSampleModel defines the location of sample in this
 * DataBuffer.
 * 
 * @since Android 1.0
 */
public class Raster {

    /**
     * The DataBuffer of this Raster.
     */
    protected DataBuffer dataBuffer;

    /**
     * The height of this Raster.
     */
    protected int height;

    /**
     * The X coordinate of the upper left pixel in this Raster.
     */
    protected int minX;

    /**
     * The Y coordinate of the upper left pixel in this Raster.
     */
    protected int minY;

    /**
     * The number of bands in this Raster.
     */
    protected int numBands;

    /**
     * The number of data elements.
     */
    protected int numDataElements;

    /**
     * The parent of this Raster.
     */
    protected Raster parent;

    /**
     * The SampleModel of this Raster.
     */
    protected SampleModel sampleModel;

    /**
     * The X translation from the coordinate space of the SampleModel of this
     * Raster.
     */
    protected int sampleModelTranslateX;

    /**
     * The Y translation from the coordinate space of the SampleModel of this
     * Raster.
     */
    protected int sampleModelTranslateY;

    /**
     * The width of this Raster.
     */
    protected int width;

    /**
     * Creates a Raster object with a BandedSampleModel and the specified
     * DataBuffer. The number of bands is defined by the length of bandOffsets
     * or bankIndices arrays.
     * 
     * @param dataBuffer
     *            the specified DataBuffer.
     * @param w
     *            the width of the image data.
     * @param h
     *            the height of the image data.
     * @param scanlineStride
     *            the scanline stride of the image data.
     * @param bankIndices
     *            the bank indices of bands.
     * @param bandOffsets
     *            the band offsets of bands.
     * @param location
     *            the location which defines the upper left corner of Raster.
     * @return the WritableRaster object.
     */
    public static WritableRaster createBandedRaster(DataBuffer dataBuffer, int w, int h,
            int scanlineStride, int bankIndices[], int bandOffsets[], Point location) {

        if (w <= 0 || h <= 0) {
            // awt.22E=w or h is less than or equal to zero
            throw new RasterFormatException(Messages.getString("awt.22E")); //$NON-NLS-1$
        }

        if (location == null) {
            location = new Point(0, 0);
        }

        if ((long)location.x + w > Integer.MAX_VALUE || (long)location.y + h > Integer.MAX_VALUE) {
            // awt.276=location.x + w or location.y + h results in integer
            // overflow
            throw new RasterFormatException(Messages.getString("awt.276")); //$NON-NLS-1$
        }

        if (bankIndices == null || bandOffsets == null) {
            // awt.277=bankIndices or bandOffsets is null
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.277")); //$NON-NLS-1$
        }

        if (dataBuffer == null) {
            // awt.278=dataBuffer is null
            throw new NullPointerException(Messages.getString("awt.278")); //$NON-NLS-1$
        }

        int dataType = dataBuffer.getDataType();

        if (dataType != DataBuffer.TYPE_BYTE && dataType != DataBuffer.TYPE_USHORT
                && dataType != DataBuffer.TYPE_INT) {
            // awt.230=dataType is not one of the supported data types
            throw new IllegalArgumentException(Messages.getString("awt.230")); //$NON-NLS-1$
        }

        BandedSampleModel sampleModel = new BandedSampleModel(dataType, w, h, scanlineStride,
                bankIndices, bandOffsets);

        return new OrdinaryWritableRaster(sampleModel, dataBuffer, location);
    }

    /**
     * Creates a Raster object with a BandedSampleModel and the specified data
     * type. The Data type can be one of the following values: TYPE_BYTE,
     * TYPE_USHORT, or TYPE_INT.
     * 
     * @param dataType
     *            the data type of the samples: TYPE_BYTE, TYPE_USHORT, or
     *            TYPE_INT.
     * @param w
     *            the width of the image data.
     * @param h
     *            the height of the image data.
     * @param scanlineStride
     *            the scanline stride of the image data.
     * @param bankIndices
     *            the bank indices of bands.
     * @param bandOffsets
     *            the band offsets of bands.
     * @param location
     *            the location which defines the upper left corner of the
     *            Raster.
     * @return the WritableRaster object.
     */
    public static WritableRaster createBandedRaster(int dataType, int w, int h, int scanlineStride,
            int bankIndices[], int bandOffsets[], Point location) {

        if (w <= 0 || h <= 0) {
            // awt.22E=w or h is less than or equal to zero
            throw new RasterFormatException(Messages.getString("awt.22E")); //$NON-NLS-1$
        }

        if (location == null) {
            location = new Point(0, 0);
        }

        if ((long)location.x + w > Integer.MAX_VALUE || (long)location.y + h > Integer.MAX_VALUE) {
            // awt.276=location.x + w or location.y + h results in integer
            // overflow
            throw new RasterFormatException(Messages.getString("awt.276")); //$NON-NLS-1$
        }

        if (bankIndices == null || bandOffsets == null) {
            // awt.277=bankIndices or bandOffsets is null
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.277")); //$NON-NLS-1$
        }

        if (dataType != DataBuffer.TYPE_BYTE && dataType != DataBuffer.TYPE_USHORT
                && dataType != DataBuffer.TYPE_INT) {
            // awt.230=dataType is not one of the supported data types
            throw new IllegalArgumentException(Messages.getString("awt.230")); //$NON-NLS-1$
        }

        int maxOffset = bandOffsets[0];
        int maxBank = bankIndices[0];

        for (int i = 0; i < bankIndices.length; i++) {
            if (bandOffsets[i] > maxOffset) {
                maxOffset = bandOffsets[i];
            }
            if (bankIndices[i] > maxBank) {
                maxBank = bankIndices[i];
            }
        }

        int numBanks = maxBank + 1;
        int dataSize = scanlineStride * (h - 1) + w + maxOffset;

        DataBuffer data = null;

        switch (dataType) {
            case DataBuffer.TYPE_BYTE:
                data = new DataBufferByte(dataSize, numBanks);
                break;
            case DataBuffer.TYPE_USHORT:
                data = new DataBufferUShort(dataSize, numBanks);
                break;
            case DataBuffer.TYPE_INT:
                data = new DataBufferInt(dataSize, numBanks);
                break;
        }
        return createBandedRaster(data, w, h, scanlineStride, bankIndices, bandOffsets, location);
    }

    /**
     * Creates a Raster object with a BandedSampleModel and the specified data
     * type. The Data type can be one of the following values: TYPE_BYTE,
     * TYPE_USHORT, or TYPE_INT.
     * 
     * @param dataType
     *            the data type of the samples: TYPE_BYTE, TYPE_USHORT, or
     *            TYPE_INT.
     * @param w
     *            the width of the image data.
     * @param h
     *            the height of the image data.
     * @param bands
     *            the number of bands.
     * @param location
     *            the location which defines the upper left corner of the
     *            Raster.
     * @return the WritableRaster object.
     */
    public static WritableRaster createBandedRaster(int dataType, int w, int h, int bands,
            Point location) {

        if (w <= 0 || h <= 0) {
            // awt.22E=w or h is less than or equal to zero
            throw new RasterFormatException(Messages.getString("awt.22E")); //$NON-NLS-1$
        }

        if (location == null) {
            location = new Point(0, 0);
        }

        if ((long)location.x + w > Integer.MAX_VALUE || (long)location.y + h > Integer.MAX_VALUE) {
            // awt.276=location.x + w or location.y + h results in integer
            // overflow
            throw new RasterFormatException(Messages.getString("awt.276")); //$NON-NLS-1$
        }

        if (bands < 1) {
            // awt.279=bands is less than 1
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.279")); //$NON-NLS-1$
        }

        int bandOffsets[] = new int[bands];
        int bankIndices[] = new int[bands];

        for (int i = 0; i < bands; i++) {
            bandOffsets[i] = 0;
            bankIndices[i] = i;
        }
        return createBandedRaster(dataType, w, h, w, bankIndices, bandOffsets, location);
    }

    /**
     * Creates a Raster object with a PixelInterleavedSampleModel and the
     * specified DataBuffer.
     * 
     * @param dataBuffer
     *            the DataBuffer.
     * @param w
     *            the width of image data.
     * @param h
     *            the height of image data.
     * @param scanlineStride
     *            the scanline stride of the image data.
     * @param pixelStride
     *            the pixel stride of image data.
     * @param bandOffsets
     *            the band offsets of bands.
     * @param location
     *            the location which defines the upper left corner of the
     *            Raster.
     * @return the WritableRaster object.
     */
    public static WritableRaster createInterleavedRaster(DataBuffer dataBuffer, int w, int h,
            int scanlineStride, int pixelStride, int bandOffsets[], Point location) {

        if (w <= 0 || h <= 0) {
            // awt.22E=w or h is less than or equal to zero
            throw new RasterFormatException(Messages.getString("awt.22E")); //$NON-NLS-1$
        }

        if (location == null) {
            location = new Point(0, 0);
        }

        if ((long)location.x + w > Integer.MAX_VALUE || (long)location.y + h > Integer.MAX_VALUE) {
            // awt.276=location.x + w or location.y + h results in integer
            // overflow
            throw new RasterFormatException(Messages.getString("awt.276")); //$NON-NLS-1$
        }

        if (dataBuffer == null) {
            // awt.278=dataBuffer is null
            throw new NullPointerException(Messages.getString("awt.278")); //$NON-NLS-1$
        }

        int dataType = dataBuffer.getDataType();
        if (dataType != DataBuffer.TYPE_BYTE && dataType != DataBuffer.TYPE_USHORT) {
            // awt.230=dataType is not one of the supported data types
            throw new IllegalArgumentException(Messages.getString("awt.230")); //$NON-NLS-1$
        }

        if (dataBuffer.getNumBanks() > 1) {
            // awt.27A=dataBuffer has more than one bank
            throw new RasterFormatException(Messages.getString("awt.27A")); //$NON-NLS-1$
        }

        if (bandOffsets == null) {
            // awt.27B=bandOffsets is null
            throw new NullPointerException(Messages.getString("awt.27B")); //$NON-NLS-1$
        }

        PixelInterleavedSampleModel sampleModel = new PixelInterleavedSampleModel(dataType, w, h,
                pixelStride, scanlineStride, bandOffsets);

        return new OrdinaryWritableRaster(sampleModel, dataBuffer, location);

    }

    /**
     * Creates a Raster object with a PixelInterleavedSampleModel and the
     * specified data type. The Data type can be one of the following values:
     * TYPE_BYTE, TYPE_USHORT, or TYPE_INT.
     * 
     * @param dataType
     *            the data type of the samples: TYPE_BYTE, TYPE_USHORT, or
     *            TYPE_INT.
     * @param w
     *            the width of image data.
     * @param h
     *            the height of image data.
     * @param scanlineStride
     *            the scanline stride of the image data.
     * @param pixelStride
     *            the pixel stride of image data.
     * @param bandOffsets
     *            the band offsets of bands.
     * @param location
     *            the location which defines the upper left corner of the
     *            Raster.
     * @return the WritableRaster object.
     */
    public static WritableRaster createInterleavedRaster(int dataType, int w, int h,
            int scanlineStride, int pixelStride, int bandOffsets[], Point location) {

        if (w <= 0 || h <= 0) {
            // awt.22E=w or h is less than or equal to zero
            throw new RasterFormatException(Messages.getString("awt.22E")); //$NON-NLS-1$
        }

        if (location == null) {
            location = new Point(0, 0);
        }

        if ((long)location.x + w > Integer.MAX_VALUE || (long)location.y + h > Integer.MAX_VALUE) {
            // awt.276=location.x + w or location.y + h results in integer
            // overflow
            throw new RasterFormatException(Messages.getString("awt.276")); //$NON-NLS-1$
        }

        if (dataType != DataBuffer.TYPE_BYTE && dataType != DataBuffer.TYPE_USHORT) {
            // awt.230=dataType is not one of the supported data types
            throw new IllegalArgumentException(Messages.getString("awt.230")); //$NON-NLS-1$
        }

        if (bandOffsets == null) {
            // awt.27B=bandOffsets is null
            throw new NullPointerException(Messages.getString("awt.27B")); //$NON-NLS-1$
        }

        int minOffset = bandOffsets[0];
        for (int i = 1; i < bandOffsets.length; i++) {
            if (bandOffsets[i] < minOffset) {
                minOffset = bandOffsets[i];
            }
        }
        int size = (h - 1) * scanlineStride + w * pixelStride + minOffset;
        DataBuffer data = null;

        switch (dataType) {
            case DataBuffer.TYPE_BYTE:
                data = new DataBufferByte(size);
                break;
            case DataBuffer.TYPE_USHORT:
                data = new DataBufferUShort(size);
                break;
        }

        return createInterleavedRaster(data, w, h, scanlineStride, pixelStride, bandOffsets,
                location);
    }

    /**
     * Creates a Raster object with a PixelInterleavedSampleModel and the
     * specified data type. The Data type can be one of the following values:
     * TYPE_BYTE, TYPE_USHORT, or TYPE_INT.
     * 
     * @param dataType
     *            the data type of samples: TYPE_BYTE, TYPE_USHORT, or TYPE_INT.
     * @param w
     *            the width of image data.
     * @param h
     *            the height of image data.
     * @param bands
     *            the number of bands.
     * @param location
     *            the location which defines the upper left corner of the
     *            Raster.
     * @return the WritableRaster.
     */
    public static WritableRaster createInterleavedRaster(int dataType, int w, int h, int bands,
            Point location) {

        if (w <= 0 || h <= 0) {
            // awt.22E=w or h is less than or equal to zero
            throw new RasterFormatException(Messages.getString("awt.22E")); //$NON-NLS-1$
        }

        if (location == null) {
            location = new Point(0, 0);
        }

        if ((long)location.x + w > Integer.MAX_VALUE || (long)location.y + h > Integer.MAX_VALUE) {
            // awt.276=location.x + w or location.y + h results in integer
            // overflow
            throw new RasterFormatException(Messages.getString("awt.276")); //$NON-NLS-1$
        }

        if (dataType != DataBuffer.TYPE_BYTE && dataType != DataBuffer.TYPE_USHORT) {
            // awt.230=dataType is not one of the supported data types
            throw new IllegalArgumentException(Messages.getString("awt.230")); //$NON-NLS-1$
        }

        int bandOffsets[] = new int[bands];
        for (int i = 0; i < bands; i++) {
            bandOffsets[i] = i;
        }

        return createInterleavedRaster(dataType, w, h, w * bands, bands, bandOffsets, location);
    }

    /**
     * Creates a Raster object with a SinglePixelPackedSampleModel and the
     * specified DataBuffer.
     * 
     * @param dataBuffer
     *            the DataBuffer.
     * @param w
     *            the width of the image data.
     * @param h
     *            the height of the image data.
     * @param scanlineStride
     *            the scanline stride of the image data.
     * @param bandMasks
     *            the band masks.
     * @param location
     *            the location which defines the upper left corner of the
     *            Raster.
     * @return the WritableRaster.
     */
    public static WritableRaster createPackedRaster(DataBuffer dataBuffer, int w, int h,
            int scanlineStride, int bandMasks[], Point location) {
        if (dataBuffer == null) {
            // awt.278=dataBuffer is null
            throw new NullPointerException(Messages.getString("awt.278")); //$NON-NLS-1$
        }

        if (w <= 0 || h <= 0) {
            // awt.22E=w or h is less than or equal to zero
            throw new RasterFormatException(Messages.getString("awt.22E")); //$NON-NLS-1$
        }

        if (location == null) {
            location = new Point(0, 0);
        }

        if ((long)location.x + w > Integer.MAX_VALUE || (long)location.y + h > Integer.MAX_VALUE) {
            // awt.276=location.x + w or location.y + h results in integer
            // overflow
            throw new RasterFormatException(Messages.getString("awt.276")); //$NON-NLS-1$
        }

        if (bandMasks == null) {
            // awt.27C=bandMasks is null
            throw new RasterFormatException(Messages.getString("awt.27C")); //$NON-NLS-1$
        }

        if (dataBuffer.getNumBanks() > 1) {
            // awt.27A=dataBuffer has more than one bank
            throw new RasterFormatException(Messages.getString("awt.27A")); //$NON-NLS-1$
        }

        int dataType = dataBuffer.getDataType();
        if (dataType != DataBuffer.TYPE_BYTE && dataType != DataBuffer.TYPE_USHORT
                && dataType != DataBuffer.TYPE_INT) {
            // awt.230=dataType is not one of the supported data types
            throw new IllegalArgumentException(Messages.getString("awt.230")); //$NON-NLS-1$
        }

        SinglePixelPackedSampleModel sampleModel = new SinglePixelPackedSampleModel(dataType, w, h,
                scanlineStride, bandMasks);

        return new OrdinaryWritableRaster(sampleModel, dataBuffer, location);
    }

    /**
     * Creates a Raster object with a MultiPixelPackedSampleModel and the
     * specified DataBuffer.
     * 
     * @param dataBuffer
     *            the DataBuffer.
     * @param w
     *            the width of the image data.
     * @param h
     *            the height of the image data.
     * @param bitsPerPixel
     *            the number of bits per pixel.
     * @param location
     *            the location which defines the upper left corner of the
     *            Raster.
     * @return the WritableRaster.
     */
    public static WritableRaster createPackedRaster(DataBuffer dataBuffer, int w, int h,
            int bitsPerPixel, Point location) {

        if (w <= 0 || h <= 0) {
            // awt.22E=w or h is less than or equal to zero
            throw new RasterFormatException(Messages.getString("awt.22E")); //$NON-NLS-1$
        }

        if (location == null) {
            location = new Point(0, 0);
        }

        if ((long)location.x + w > Integer.MAX_VALUE || (long)location.y + h > Integer.MAX_VALUE) {
            // awt.276=location.x + w or location.y + h results in integer
            // overflow
            throw new RasterFormatException(Messages.getString("awt.276")); //$NON-NLS-1$
        }

        if (dataBuffer == null) {
            // awt.278=dataBuffer is null
            throw new NullPointerException(Messages.getString("awt.278")); //$NON-NLS-1$
        }

        if (dataBuffer.getNumBanks() > 1) {
            // awt.27A=dataBuffer has more than one bank
            throw new RasterFormatException(Messages.getString("awt.27A")); //$NON-NLS-1$
        }

        int dataType = dataBuffer.getDataType();
        if (dataType != DataBuffer.TYPE_BYTE && dataType != DataBuffer.TYPE_USHORT
                && dataType != DataBuffer.TYPE_INT) {
            // awt.230=dataType is not one of the supported data types
            throw new IllegalArgumentException(Messages.getString("awt.230")); //$NON-NLS-1$
        }

        MultiPixelPackedSampleModel sampleModel = new MultiPixelPackedSampleModel(dataType, w, h,
                bitsPerPixel);

        return new OrdinaryWritableRaster(sampleModel, dataBuffer, location);

    }

    /**
     * Creates a Raster object with a MultiPixelPackedSampleModel and the
     * specified DataBuffer.
     * 
     * @param dataType
     *            the data type of samples: TYPE_BYTE, TYPE_USHORT, or TYPE_INT.
     * @param w
     *            the width of the image data.
     * @param h
     *            the height of the image data.
     * @param bands
     *            the number of bands.
     * @param bitsPerBand
     *            the number of bits per band.
     * @param location
     *            the location which defines the upper left corner of the
     *            Raster.
     * @return the WritableRaster.
     */
    public static WritableRaster createPackedRaster(int dataType, int w, int h, int bands,
            int bitsPerBand, Point location) {

        if (w <= 0 || h <= 0) {
            // awt.22E=w or h is less than or equal to zero
            throw new RasterFormatException(Messages.getString("awt.22E")); //$NON-NLS-1$
        }

        if (location == null) {
            location = new Point(0, 0);
        }

        if ((long)location.x + w > Integer.MAX_VALUE || (long)location.y + h > Integer.MAX_VALUE) {
            // awt.276=location.x + w or location.y + h results in integer
            // overflow
            throw new RasterFormatException(Messages.getString("awt.276")); //$NON-NLS-1$
        }

        if (bands < 1 || bitsPerBand < 1) {
            // awt.27D=bitsPerBand or bands is not greater than zero
            throw new IllegalArgumentException(Messages.getString("awt.27D")); //$NON-NLS-1$
        }

        if (dataType != DataBuffer.TYPE_BYTE && dataType != DataBuffer.TYPE_USHORT
                && dataType != DataBuffer.TYPE_INT) {
            // awt.230=dataType is not one of the supported data types
            throw new IllegalArgumentException(Messages.getString("awt.230")); //$NON-NLS-1$
        }

        if (bitsPerBand * bands > DataBuffer.getDataTypeSize(dataType)) {
            // awt.27E=The product of bitsPerBand and bands is greater than the
            // number of bits held by dataType
            throw new IllegalArgumentException(Messages.getString("awt.27E")); //$NON-NLS-1$
        }

        if (bands > 1) {

            int bandMasks[] = new int[bands];
            int mask = (1 << bitsPerBand) - 1;

            for (int i = 0; i < bands; i++) {
                bandMasks[i] = mask << (bitsPerBand * (bands - 1 - i));
            }

            return createPackedRaster(dataType, w, h, bandMasks, location);
        }
        DataBuffer data = null;
        int size = ((bitsPerBand * w + DataBuffer.getDataTypeSize(dataType) - 1) / DataBuffer
                .getDataTypeSize(dataType))
                * h;

        switch (dataType) {
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
        return createPackedRaster(data, w, h, bitsPerBand, location);
    }

    /**
     * Creates a Raster object with a SinglePixelPackedSampleModel and the
     * specified DataBuffer.
     * 
     * @param dataType
     *            the data type of samples: TYPE_BYTE, TYPE_USHORT, or TYPE_INT.
     * @param w
     *            the width of the image data.
     * @param h
     *            the height of the image data.
     * @param bandMasks
     *            the band masks.
     * @param location
     *            the location which defines the upper left corner of the
     *            Raster.
     * @return the WritableRaster.
     */
    public static WritableRaster createPackedRaster(int dataType, int w, int h, int bandMasks[],
            Point location) {

        if (dataType != DataBuffer.TYPE_BYTE && dataType != DataBuffer.TYPE_USHORT
                && dataType != DataBuffer.TYPE_INT) {
            // awt.230=dataType is not one of the supported data types
            throw new IllegalArgumentException(Messages.getString("awt.230")); //$NON-NLS-1$
        }

        if (w <= 0 || h <= 0) {
            // awt.22E=w or h is less than or equal to zero
            throw new RasterFormatException(Messages.getString("awt.22E")); //$NON-NLS-1$
        }

        if (location == null) {
            location = new Point(0, 0);
        }

        if ((long)location.x + w > Integer.MAX_VALUE || (long)location.y + h > Integer.MAX_VALUE) {
            // awt.276=location.x + w or location.y + h results in integer
            // overflow
            throw new RasterFormatException(Messages.getString("awt.276")); //$NON-NLS-1$
        }

        if (bandMasks == null) {
            // awt.27C=bandMasks is null
            throw new NullPointerException(Messages.getString("awt.27C")); //$NON-NLS-1$
        }

        DataBuffer data = null;

        switch (dataType) {
            case DataBuffer.TYPE_BYTE:
                data = new DataBufferByte(w * h);
                break;
            case DataBuffer.TYPE_USHORT:
                data = new DataBufferUShort(w * h);
                break;
            case DataBuffer.TYPE_INT:
                data = new DataBufferInt(w * h);
                break;
        }

        return createPackedRaster(data, w, h, w, bandMasks, location);
    }

    /**
     * Creates a Raster object with the specified DataBuffer and SampleModel.
     * 
     * @param sm
     *            the specified SampleModel.
     * @param db
     *            the specified DataBuffer.
     * @param location
     *            the location which defines the upper left corner of the
     *            Raster.
     * @return the Raster.
     */
    public static Raster createRaster(SampleModel sm, DataBuffer db, Point location) {

        if (sm == null || db == null) {
            // awt.27F=SampleModel or DataBuffer is null
            throw new NullPointerException(Messages.getString("awt.27F")); //$NON-NLS-1$
        }

        if (location == null) {
            location = new Point(0, 0);
        }

        return new Raster(sm, db, location);
    }

    /**
     * Creates a WritableRaster with the specified SampleModel and DataBuffer.
     * 
     * @param sm
     *            the specified SampleModel.
     * @param db
     *            the specified DataBuffer.
     * @param location
     *            the location which defines the upper left corner of the
     *            Raster.
     * @return the WritableRaster.
     */
    public static WritableRaster createWritableRaster(SampleModel sm, DataBuffer db, Point location) {

        if (sm == null || db == null) {
            // awt.27F=SampleModel or DataBuffer is null
            throw new NullPointerException(Messages.getString("awt.27F")); //$NON-NLS-1$
        }

        if (location == null) {
            location = new Point(0, 0);
        }

        return new OrdinaryWritableRaster(sm, db, location);
    }

    /**
     * Creates a WritableRaster with the specified SampleModel.
     * 
     * @param sm
     *            the specified SampleModel.
     * @param location
     *            the location which defines the upper left corner of the
     *            Raster.
     * @return the WritableRaster.
     */
    public static WritableRaster createWritableRaster(SampleModel sm, Point location) {

        if (sm == null) {
            // awt.280=SampleModel is null
            throw new NullPointerException(Messages.getString("awt.280")); //$NON-NLS-1$
        }

        if (location == null) {
            location = new Point(0, 0);
        }

        return createWritableRaster(sm, sm.createDataBuffer(), location);
    }

    /**
     * Instantiates a new Raster object with the specified SampleModel and
     * DataBuffer.
     * 
     * @param sampleModel
     *            the specified SampleModel.
     * @param dataBuffer
     *            the specified DataBuffer.
     * @param origin
     *            the specified origin.
     */
    protected Raster(SampleModel sampleModel, DataBuffer dataBuffer, Point origin) {

        this(sampleModel, dataBuffer, new Rectangle(origin.x, origin.y, sampleModel.getWidth(),
                sampleModel.getHeight()), origin, null);
    }

    /**
     * Instantiates a new Raster object with the specified SampleModel,
     * DataBuffer, rectangular region and parent Raster.
     * 
     * @param sampleModel
     *            the specified SampleModel.
     * @param dataBuffer
     *            the specified DataBuffer.
     * @param aRegion
     *            the a rectangular region which defines the new image bounds.
     * @param sampleModelTranslate
     *            this point defines the translation point from the SampleModel
     *            coordinates to the new Raster coordinates.
     * @param parent
     *            the parent of this Raster.
     */
    protected Raster(SampleModel sampleModel, DataBuffer dataBuffer, Rectangle aRegion,
            Point sampleModelTranslate, Raster parent) {

        if (sampleModel == null || dataBuffer == null || aRegion == null
                || sampleModelTranslate == null) {
            // awt.281=sampleModel, dataBuffer, aRegion or sampleModelTranslate
            // is null
            throw new NullPointerException(Messages.getString("awt.281")); //$NON-NLS-1$
        }

        if (aRegion.width <= 0 || aRegion.height <= 0) {
            // awt.282=aRegion has width or height less than or equal to zero
            throw new RasterFormatException(Messages.getString("awt.282")); //$NON-NLS-1$
        }

        if ((long)aRegion.x + (long)aRegion.width > Integer.MAX_VALUE) {
            // awt.283=Overflow X coordinate of Raster
            throw new RasterFormatException(Messages.getString("awt.283")); //$NON-NLS-1$
        }

        if ((long)aRegion.y + (long)aRegion.height > Integer.MAX_VALUE) {
            // awt.284=Overflow Y coordinate of Raster
            throw new RasterFormatException(Messages.getString("awt.284")); //$NON-NLS-1$
        }

        if (sampleModel instanceof ComponentSampleModel) {
            validateDataBuffer(dataBuffer, aRegion.width, aRegion.height,
                    ((ComponentSampleModel)sampleModel).getScanlineStride());
        } else if (sampleModel instanceof MultiPixelPackedSampleModel) {
            validateDataBuffer(dataBuffer, aRegion.width, aRegion.height,
                    ((MultiPixelPackedSampleModel)sampleModel).getScanlineStride());
        } else if (sampleModel instanceof SinglePixelPackedSampleModel) {
            validateDataBuffer(dataBuffer, aRegion.width, aRegion.height,
                    ((SinglePixelPackedSampleModel)sampleModel).getScanlineStride());
        }

        this.sampleModel = sampleModel;
        this.dataBuffer = dataBuffer;
        this.minX = aRegion.x;
        this.minY = aRegion.y;
        this.width = aRegion.width;
        this.height = aRegion.height;
        this.sampleModelTranslateX = sampleModelTranslate.x;
        this.sampleModelTranslateY = sampleModelTranslate.y;
        this.parent = parent;
        this.numBands = sampleModel.getNumBands();
        this.numDataElements = sampleModel.getNumDataElements();

    }

    /**
     * Instantiates a new Raster with the specified SampleModel.
     * 
     * @param sampleModel
     *            the specified SampleModel.
     * @param origin
     *            the origin.
     */
    protected Raster(SampleModel sampleModel, Point origin) {
        this(sampleModel, sampleModel.createDataBuffer(), new Rectangle(origin.x, origin.y,
                sampleModel.getWidth(), sampleModel.getHeight()), origin, null);
    }

    /**
     * Creates the child of this Raster by sharing the specified rectangular
     * area in this raster. The parentX, parentY, width and height parameters
     * specify the rectangular area to be shared.
     * 
     * @param parentX
     *            the X coordinate of the upper left corner of this Raster.
     * @param parentY
     *            the Y coordinate of the upper left corner of this Raster.
     * @param width
     *            the width of the child area.
     * @param height
     *            the height of the child area.
     * @param childMinX
     *            the X coordinate of child area mapped to the parentX
     *            coordinate.
     * @param childMinY
     *            the Y coordinate of child area mapped to the parentY
     *            coordinate.
     * @param bandList
     *            the array of band indices.
     * @return the Raster.
     */
    public Raster createChild(int parentX, int parentY, int width, int height, int childMinX,
            int childMinY, int bandList[]) {
        if (width <= 0 || height <= 0) {
            // awt.285=Width or Height of child Raster is less than or equal to
            // zero
            throw new RasterFormatException(Messages.getString("awt.285")); //$NON-NLS-1$
        }

        if (parentX < this.minX || parentX + width > this.minX + this.width) {
            // awt.286=parentX disposes outside Raster
            throw new RasterFormatException(Messages.getString("awt.286")); //$NON-NLS-1$
        }

        if (parentY < this.minY || parentY + height > this.minY + this.height) {
            // awt.287=parentY disposes outside Raster
            throw new RasterFormatException(Messages.getString("awt.287")); //$NON-NLS-1$
        }

        if ((long)parentX + width > Integer.MAX_VALUE) {
            // awt.288=parentX + width results in integer overflow
            throw new RasterFormatException(Messages.getString("awt.288")); //$NON-NLS-1$
        }

        if ((long)parentY + height > Integer.MAX_VALUE) {
            // awt.289=parentY + height results in integer overflow
            throw new RasterFormatException(Messages.getString("awt.289")); //$NON-NLS-1$
        }

        if ((long)childMinX + width > Integer.MAX_VALUE) {
            // awt.28A=childMinX + width results in integer overflow
            throw new RasterFormatException(Messages.getString("awt.28A")); //$NON-NLS-1$
        }

        if ((long)childMinY + height > Integer.MAX_VALUE) {
            // awt.28B=childMinY + height results in integer overflow
            throw new RasterFormatException(Messages.getString("awt.28B")); //$NON-NLS-1$
        }

        SampleModel childModel;

        if (bandList == null) {
            childModel = sampleModel;
        } else {
            childModel = sampleModel.createSubsetSampleModel(bandList);
        }

        int childTranslateX = childMinX - parentX;
        int childTranslateY = childMinY - parentY;

        return new Raster(childModel, dataBuffer,
                new Rectangle(childMinX, childMinY, width, height), new Point(childTranslateX
                        + sampleModelTranslateX, childTranslateY + sampleModelTranslateY), this);
    }

    /**
     * Create a compatible WritableRaster with the same parameters as this
     * Raster.
     * 
     * @return the WritableRaster.
     */
    public WritableRaster createCompatibleWritableRaster() {
        return new OrdinaryWritableRaster(sampleModel, new Point(0, 0));
    }

    /**
     * Create a compatible WritableRaster with the same parameters as this
     * Raster and the specified size.
     * 
     * @param w
     *            the width of the new WritableRaster.
     * @param h
     *            the height of the new WritableRaster.
     * @return the WritableRaster.
     */
    public WritableRaster createCompatibleWritableRaster(int w, int h) {
        if (w <= 0 || h <= 0) {
            // awt.22E=w or h is less than or equal to zero
            throw new RasterFormatException(Messages.getString("awt.22E")); //$NON-NLS-1$
        }

        SampleModel sm = sampleModel.createCompatibleSampleModel(w, h);

        return new OrdinaryWritableRaster(sm, new Point(0, 0));
    }

    /**
     * Create a compatible WritableRaster with the same parameters as this
     * Raster and the specified size and location.
     * 
     * @param x
     *            the X coordinate of the new WritableRaster.
     * @param y
     *            the Y coordinate of the new WritableRaster.
     * @param w
     *            the width of the new WritableRaster.
     * @param h
     *            the height of the new WritableRaster.
     * @return the WritableRaster.
     */
    public WritableRaster createCompatibleWritableRaster(int x, int y, int w, int h) {

        WritableRaster raster = createCompatibleWritableRaster(w, h);

        return raster.createWritableChild(0, 0, w, h, x, y, null);
    }

    /**
     * Create a compatible WritableRaster with the same parameters as this
     * Raster and the specified rectangle which determines new WritableRaster's
     * location and size.
     * 
     * @param rect
     *            the specified Rectangle.
     * @return the WritableRaster.
     */
    public WritableRaster createCompatibleWritableRaster(Rectangle rect) {
        if (rect == null) {
            // awt.28C=Rect is null
            throw new NullPointerException(Messages.getString("awt.28C")); //$NON-NLS-1$
        }

        return createCompatibleWritableRaster(rect.x, rect.y, rect.width, rect.height);
    }

    /**
     * Creates the translated child of this Raster. The New Raster object is a
     * reference to the this Raster with a different location.
     * 
     * @param childMinX
     *            the X coordinate of the new Raster.
     * @param childMinY
     *            the Y coordinate of the new Raster.
     * @return the Raster.
     */
    public Raster createTranslatedChild(int childMinX, int childMinY) {
        return createChild(minX, minY, width, height, childMinX, childMinY, null);
    }

    /**
     * Gets the bounds of this Raster as a rectangle.
     * 
     * @return the bounds of this Raster.
     */
    public Rectangle getBounds() {
        return new Rectangle(minX, minY, width, height);
    }

    /**
     * Gets the DataBuffer associated with this Raster.
     * 
     * @return the DataBuffer associated with this Raster.
     */
    public DataBuffer getDataBuffer() {
        return dataBuffer;
    }

    /**
     * Gets the data elements which represent the pixel data of the specified
     * rectangle area as a primitive array. The following image data types are
     * supported: DataBuffer.TYPE_BYTE, DataBuffer.TYPE_USHORT,
     * DataBuffer.TYPE_INT, DataBuffer.TYPE_SHORT, DataBuffer.TYPE_FLOAT, or
     * DataBuffer.TYPE_DOUBLE.
     * 
     * @param x
     *            the X coordinate of the area of pixels.
     * @param y
     *            the Y coordinate of the area of pixels.
     * @param w
     *            the width of the area of pixels.
     * @param h
     *            the height of the area of pixels.
     * @param outData
     *            the resulting array.
     * @return the data elements of the specified area of this Raster.
     */
    public Object getDataElements(int x, int y, int w, int h, Object outData) {
        return sampleModel.getDataElements(x - sampleModelTranslateX, y - sampleModelTranslateY, w,
                h, outData, dataBuffer);
    }

    /**
     * Gets the data elements which represent the specified pixel of this Raster
     * as a primitive array. The following image data types are supported:
     * DataBuffer.TYPE_BYTE, DataBuffer.TYPE_USHORT, DataBuffer.TYPE_INT,
     * DataBuffer.TYPE_SHORT, DataBuffer.TYPE_FLOAT, or DataBuffer.TYPE_DOUBLE.
     * 
     * @param x
     *            the X coordinate of the pixel.
     * @param y
     *            the Y coordinate of the pixel.
     * @param outData
     *            the resulting data.
     * @return the data elements of the specified pixel of this Raster.
     */
    public Object getDataElements(int x, int y, Object outData) {
        return sampleModel.getDataElements(x - sampleModelTranslateX, y - sampleModelTranslateY,
                outData, dataBuffer);
    }

    /**
     * Gets the height of this Raster.
     * 
     * @return the height of this Raster.
     */
    public final int getHeight() {
        return height;
    }

    /**
     * Gets the minimum X coordinate of this Raster.
     * 
     * @return the minimum X coordinate of this Raster.
     */
    public final int getMinX() {
        return minX;
    }

    /**
     * Gets the minimum Y coordinate of this Raster.
     * 
     * @return the minimum Y coordinate of this Raster.
     */
    public final int getMinY() {
        return minY;
    }

    /**
     * Gets the number of bands in this Raster.
     * 
     * @return the number of bands in this Raster.
     */
    public final int getNumBands() {
        return numBands;
    }

    /**
     * Gets the number of data elements for one pixel.
     * 
     * @return the number of data elements for one pixel.
     */
    public final int getNumDataElements() {
        return numDataElements;
    }

    /**
     * Gets the parent Raster for this Raster object.
     * 
     * @return the parent Raster for this Raster object.
     */
    public Raster getParent() {
        return parent;
    }

    /**
     * Gets a double array of samples for the specified pixel in this Raster.
     * 
     * @param x
     *            the pixel's X coordinate.
     * @param y
     *            the pixel's Y coordinate.
     * @param dArray
     *            the double array where result array will be stored.
     * @return the double array of samples for the specified pixel in this
     *         Raster.
     */
    public double[] getPixel(int x, int y, double dArray[]) {
        return sampleModel.getPixel(x - sampleModelTranslateX, y - sampleModelTranslateY, dArray,
                dataBuffer);
    }

    /**
     * Gets a float array of samples for the specified pixel in this Raster.
     * 
     * @param x
     *            the pixel's X coordinate.
     * @param y
     *            the pixel's Y coordinate.
     * @param fArray
     *            the float array where the result array will be stored.
     * @return the float array of samples for the specified pixel in this
     *         Raster.
     */
    public float[] getPixel(int x, int y, float fArray[]) {
        return sampleModel.getPixel(x - sampleModelTranslateX, y - sampleModelTranslateY, fArray,
                dataBuffer);
    }

    /**
     * Gets an integer array of samples for the specified pixel in this Raster.
     * 
     * @param x
     *            the pixel's X coordinate.
     * @param y
     *            the pixel's Y coordinate.
     * @param iArray
     *            the integer array where the result array will be stored.
     * @return the integer array of samples for the specified pixel in this
     *         Raster.
     */
    public int[] getPixel(int x, int y, int iArray[]) {
        return sampleModel.getPixel(x - sampleModelTranslateX, y - sampleModelTranslateY, iArray,
                dataBuffer);
    }

    /**
     * Gets an double array of samples for the specified rectangular area of
     * pixels in this Raster.
     * 
     * @param x
     *            the X coordinate of the area of pixels.
     * @param y
     *            the Y coordinate of the area of pixels.
     * @param w
     *            the width of the area of pixels.
     * @param h
     *            the height of the area of pixels.
     * @param dArray
     *            the resulting array.
     * @return the double array of samples for the specified rectangular area of
     *         pixels in this Raster.
     */
    public double[] getPixels(int x, int y, int w, int h, double dArray[]) {
        return sampleModel.getPixels(x - sampleModelTranslateX, y - sampleModelTranslateY, w, h,
                dArray, dataBuffer);
    }

    /**
     * Gets an float array of samples for the specified rectangular area of
     * pixels in this Raster.
     * 
     * @param x
     *            the X coordinate of the area of pixels.
     * @param y
     *            the Y coordinate of the area of pixels.
     * @param w
     *            the width of the area of pixels.
     * @param h
     *            the height of the area of pixels.
     * @param fArray
     *            the resulting array.
     * @return the float array of samples for the specified rectangular area of
     *         pixels in this Raster.
     */
    public float[] getPixels(int x, int y, int w, int h, float fArray[]) {
        return sampleModel.getPixels(x - sampleModelTranslateX, y - sampleModelTranslateY, w, h,
                fArray, dataBuffer);
    }

    /**
     * Gets an integer array of samples for the specified rectangular area of
     * pixels in this raster.
     * 
     * @param x
     *            the X coordinate of the area of pixels.
     * @param y
     *            the Y coordinate of the area of pixels.
     * @param w
     *            the width of pixel's the area of pixels.
     * @param h
     *            the height of pixel's the area of pixels.
     * @param iArray
     *            the resulting array.
     * @return the integer array of samples for the specified rectangular area
     *         of pixels in this Raster.
     */
    public int[] getPixels(int x, int y, int w, int h, int iArray[]) {
        return sampleModel.getPixels(x - sampleModelTranslateX, y - sampleModelTranslateY, w, h,
                iArray, dataBuffer);
    }

    /**
     * Gets the sample for the specified band of the specified pixel as an
     * integer.
     * 
     * @param x
     *            the X coordinate of the pixel.
     * @param y
     *            the Y coordinate of the pixel.
     * @param b
     *            the band.
     * @return the sample for the specified band of the specified pixel as an
     *         integer.
     */
    public int getSample(int x, int y, int b) {
        return sampleModel.getSample(x - sampleModelTranslateX, y - sampleModelTranslateY, b,
                dataBuffer);
    }

    /**
     * Gets the sample for the specified band of the specified pixel as a
     * double.
     * 
     * @param x
     *            the X coordinate of the pixel.
     * @param y
     *            the Y coordinate of the pixel.
     * @param b
     *            the band.
     * @return the sample for the specified band of the specified pixel as a
     *         double.
     */
    public double getSampleDouble(int x, int y, int b) {
        return sampleModel.getSampleDouble(x - sampleModelTranslateX, y - sampleModelTranslateY, b,
                dataBuffer);
    }

    /**
     * Gets the sample for the specified band of the specified pixel as a float.
     * 
     * @param x
     *            the X coordinate of the pixel.
     * @param y
     *            the Y coordinate of the pixel.
     * @param b
     *            the band.
     * @return the sample for the specified band of the specified pixel as a
     *         float.
     */
    public float getSampleFloat(int x, int y, int b) {
        return sampleModel.getSampleFloat(x - sampleModelTranslateX, y - sampleModelTranslateY, b,
                dataBuffer);
    }

    /**
     * Gets the SampleModel associated with this Raster.
     * 
     * @return the SampleModel associated with this Raster.
     */
    public SampleModel getSampleModel() {
        return sampleModel;
    }

    /**
     * Gets the translation of the X coordinate from the SampleModel coordinate
     * system to the Rasters's coordinate system.
     * 
     * @return the value of the translation of the X coordinate from the
     *         SampleModel coordinate system to the Rasters's coordinate system.
     */
    public final int getSampleModelTranslateX() {
        return sampleModelTranslateX;
    }

    /**
     * Gets the translation of the Y coordinate from the SampleModel coordinate
     * system to the Rasters's coordinate system.
     * 
     * @return the value of the translation of the Y coordinate from the
     *         SampleModel coordinate system to the Rasters's coordinate system.
     */
    public final int getSampleModelTranslateY() {
        return sampleModelTranslateY;
    }

    /**
     * Gets the double array of samples for the specified band of the specified
     * rectangular area of pixels in this Raster as a double array.
     * 
     * @param x
     *            the X coordinate of the rectangular area of pixels.
     * @param y
     *            the Y coordinate of the rectangular area of pixels.
     * @param w
     *            the width of the rectangular area of pixels.
     * @param h
     *            the height of the rectangular area of pixels.
     * @param b
     *            the band.
     * @param dArray
     *            the resulting double array.
     * @return the double array of samples for the specified band of the
     *         specified rectangular area of pixels.
     */
    public double[] getSamples(int x, int y, int w, int h, int b, double dArray[]) {

        return sampleModel.getSamples(x - sampleModelTranslateX, y - sampleModelTranslateY, w, h,
                b, dArray, dataBuffer);
    }

    /**
     * Gets the float array of samples for the specified band of the specified
     * rectangular area of pixels in this Raster as a float array.
     * 
     * @param x
     *            the X coordinate of the rectangular area of pixels.
     * @param y
     *            the Y coordinate of the rectangular area of pixels.
     * @param w
     *            the width of the rectangular area of pixels.
     * @param h
     *            the height of the rectangular area of pixels.
     * @param b
     *            the band.
     * @param fArray
     *            the resulting float array.
     * @return the float array of samples for the specified band of the
     *         specified rectangular area of pixels.
     */
    public float[] getSamples(int x, int y, int w, int h, int b, float fArray[]) {

        return sampleModel.getSamples(x - sampleModelTranslateX, y - sampleModelTranslateY, w, h,
                b, fArray, dataBuffer);
    }

    /**
     * Gets the integer array of samples for the specified band of the specified
     * rectangular area of pixels in this Raster as a integer array.
     * 
     * @param x
     *            the X coordinate of the rectangular area of pixels.
     * @param y
     *            the Y coordinate of the rectangular area of pixels.
     * @param w
     *            the width of the rectangular area of pixels.
     * @param h
     *            the height of the rectangular area of pixels.
     * @param b
     *            the band.
     * @param iArray
     *            the resulting integer array.
     * @return the integer array of samples for the specified band of the
     *         specified rectangular area of pixels.
     */
    public int[] getSamples(int x, int y, int w, int h, int b, int iArray[]) {
        return sampleModel.getSamples(x - sampleModelTranslateX, y - sampleModelTranslateY, w, h,
                b, iArray, dataBuffer);
    }

    /**
     * Gets the transfer type for pixels of this Raster.
     * 
     * @see SampleModel#getTransferType()
     * @return the transfer type for pixels of this Raster.
     */
    public final int getTransferType() {
        return sampleModel.getTransferType();
    }

    /**
     * Gets the width of this Raster.
     * 
     * @return the width of this Raster.
     */
    public final int getWidth() {
        return width;
    }

    /**
     * Validate data buffer.
     * 
     * @param dataBuffer
     *            the data buffer.
     * @param w
     *            the w.
     * @param h
     *            the h.
     * @param scanlineStride
     *            the scanline stride.
     */
    private static void validateDataBuffer(final DataBuffer dataBuffer, final int w, final int h,
            final int scanlineStride) {
        if (dataBuffer.getSize() < (scanlineStride * (h - 1) + w - 1)) {
            // awt.298=dataBuffer is too small
            throw new RasterFormatException(Messages.getString("awt.298")); //$NON-NLS-1$
        }
    }
}
