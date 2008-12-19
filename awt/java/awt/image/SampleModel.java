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
 * The SampleModel class is abstract class for retrieving pixel's samples in the
 * data of an image. Each pixel contains several samples. A sample is the set of
 * values of the bands for single pixel. For example, each pixel in the RGB
 * model contains three samples and there are three corresponding bands in the
 * image data of such pixels representing red, green and blue components.
 * <p>
 * The image data is represented as a Raster with a DataBuffer and a
 * SampleModel. The SampleModel allows access to the samples in the DataBuffer.
 * 
 * @since Android 1.0
 */
public abstract class SampleModel {

    /**
     * The width of the image data which this SampleModel describes.
     */
    protected int width;

    /**
     * The height of the image data which this SampleModel describes.
     */
    protected int height;

    /**
     * The number of bands of image data which this SampleModel describes.
     */
    protected int numBands;

    /**
     * The data type of the image data which this SampleModel describes.
     */
    protected int dataType;

    /**
     * Instantiates a new SampleModel with the specified data type, width,
     * height and number of bands.
     * 
     * @param dataType
     *            the data type of the image data.
     * @param w
     *            the width of the image data.
     * @param h
     *            the height of the image data.
     * @param numBands
     *            the number of bands of the image data.
     */
    public SampleModel(int dataType, int w, int h, int numBands) {
        if (w <= 0 || h <= 0) {
            // awt.22E=w or h is less than or equal to zero
            throw new IllegalArgumentException(Messages.getString("awt.22E")); //$NON-NLS-1$
        }

        double squre = ((double)w) * ((double)h);
        if (squre >= Integer.MAX_VALUE) {
            // awt.22F=The product of w and h is greater than Integer.MAX_VALUE
            throw new IllegalArgumentException(Messages.getString("awt.22F")); //$NON-NLS-1$
        }

        if (dataType < DataBuffer.TYPE_BYTE || dataType > DataBuffer.TYPE_DOUBLE
                && dataType != DataBuffer.TYPE_UNDEFINED) {
            // awt.230=dataType is not one of the supported data types
            throw new IllegalArgumentException(Messages.getString("awt.230")); //$NON-NLS-1$
        }

        if (numBands < 1) {
            // awt.231=Number of bands must be more then 0
            throw new IllegalArgumentException(Messages.getString("awt.231")); //$NON-NLS-1$
        }

        this.dataType = dataType;
        this.width = w;
        this.height = h;
        this.numBands = numBands;

    }

    /**
     * Gets the data array for the specified pixel of the specified DataBuffer
     * with one of the following types: DataBuffer.TYPE_BYTE,
     * DataBuffer.TYPE_USHORT, DataBuffer.TYPE_INT, DataBuffer.TYPE_SHORT,
     * DataBuffer.TYPE_FLOAT, or DataBuffer.TYPE_DOUBLE.
     * 
     * @param x
     *            the X coordinate of pixel.
     * @param y
     *            the Y coordinate of pixel.
     * @param obj
     *            the Object is a data where the result will be stored.
     * @param data
     *            the image data.
     * @return the data array for the specified pixel of the specified
     *         DataBuffer.
     */
    public abstract Object getDataElements(int x, int y, Object obj, DataBuffer data);

    /**
     * Gets the array of pixel data for the specified rectangular area of pixels
     * of the specified DataBuffer with one of the following types:
     * DataBuffer.TYPE_BYTE, DataBuffer.TYPE_USHORT, DataBuffer.TYPE_INT,
     * DataBuffer.TYPE_SHORT, DataBuffer.TYPE_FLOAT, or DataBuffer.TYPE_DOUBLE.
     * 
     * @param x
     *            the X coordinate of the rectangular pixel area.
     * @param y
     *            the Y coordinate of the rectangular pixel area.
     * @param w
     *            the width of the rectangular pixel area.
     * @param h
     *            the height of the rectangular pixel area.
     * @param obj
     *            the Object is an array with the primitive type, where the
     *            result array will be stored.
     * @param data
     *            the image data.
     * @return the array of pixel data for the specified rectangular area of
     *         pixels of the specified DataBuffer object.
     */
    public Object getDataElements(int x, int y, int w, int h, Object obj, DataBuffer data) {
        int numDataElements = getNumDataElements();
        int idx = 0;

        switch (getTransferType()) {
            case DataBuffer.TYPE_BYTE:
                byte bdata[];
                byte bbuf[] = null;

                if (obj == null) {
                    bdata = new byte[numDataElements * w * h];
                } else {
                    bdata = (byte[])obj;
                }

                for (int i = y; i < y + h; i++) {
                    for (int j = x; j < x + w; j++) {
                        bbuf = (byte[])getDataElements(j, i, bbuf, data);
                        for (int n = 0; n < numDataElements; n++) {
                            bdata[idx++] = bbuf[n];
                        }
                    }
                }
                obj = bdata;
                break;

            case DataBuffer.TYPE_SHORT:
            case DataBuffer.TYPE_USHORT:
                short sdata[];
                short sbuf[] = null;

                if (obj == null) {
                    sdata = new short[numDataElements * w * h];
                } else {
                    sdata = (short[])obj;
                }

                for (int i = y; i < y + h; i++) {
                    for (int j = x; j < x + w; j++) {
                        sbuf = (short[])getDataElements(j, i, sbuf, data);
                        for (int n = 0; n < numDataElements; n++) {
                            sdata[idx++] = sbuf[n];
                        }
                    }
                }
                obj = sdata;
                break;

            case DataBuffer.TYPE_INT:
                int idata[];
                int ibuf[] = null;

                if (obj == null) {
                    idata = new int[numDataElements * w * h];
                } else {
                    idata = (int[])obj;
                }

                for (int i = y; i < y + h; i++) {
                    for (int j = x; j < x + w; j++) {
                        ibuf = (int[])getDataElements(j, i, ibuf, data);
                        for (int n = 0; n < numDataElements; n++) {
                            idata[idx++] = ibuf[n];
                        }
                    }
                }
                obj = idata;
                break;

            case DataBuffer.TYPE_FLOAT:
                float fdata[];
                float fbuf[] = null;

                if (obj == null) {
                    fdata = new float[numDataElements * w * h];
                } else {
                    fdata = (float[])obj;
                }

                for (int i = y; i < y + h; i++) {
                    for (int j = x; j < x + w; j++) {
                        fbuf = (float[])getDataElements(j, i, fbuf, data);
                        for (int n = 0; n < numDataElements; n++) {
                            fdata[idx++] = fbuf[n];
                        }
                    }
                }
                obj = fdata;
                break;

            case DataBuffer.TYPE_DOUBLE:
                double ddata[];
                double dbuf[] = null;

                if (obj == null) {
                    ddata = new double[numDataElements * w * h];
                } else {
                    ddata = (double[])obj;
                }

                for (int i = y; i < y + h; i++) {
                    for (int j = x; j < x + w; j++) {
                        dbuf = (double[])getDataElements(j, i, dbuf, data);
                        for (int n = 0; n < numDataElements; n++) {
                            ddata[idx++] = dbuf[n];
                        }
                    }
                }
                obj = ddata;
                break;

        }

        return obj;
    }

    /**
     * Sets the data for a single pixel in the specified DataBuffer from a
     * primitive array with one of the following types: DataBuffer.TYPE_BYTE,
     * DataBuffer.TYPE_USHORT, DataBuffer.TYPE_INT, DataBuffer.TYPE_SHORT,
     * DataBuffer.TYPE_FLOAT, or DataBuffer.TYPE_DOUBLE.
     * 
     * @param x
     *            the X coordinate of pixel.
     * @param y
     *            the Y coordinate of pixel.
     * @param obj
     *            the Object - the array of primitive pixel data to be set.
     * @param data
     *            the image data.
     */
    public abstract void setDataElements(int x, int y, Object obj, DataBuffer data);

    /**
     * Sets the data elements for a rectangular area of pixels in the specified
     * DataBuffer from a primitive array with one of the following types:
     * DataBuffer.TYPE_BYTE, DataBuffer.TYPE_USHORT, DataBuffer.TYPE_INT,
     * DataBuffer.TYPE_SHORT, DataBuffer.TYPE_FLOAT, or DataBuffer.TYPE_DOUBLE.
     * 
     * @param x
     *            the X coordinate of the specified rectangular area.
     * @param y
     *            the Y coordinate of the specified rectangular area.
     * @param w
     *            the width of rectangle.
     * @param h
     *            the height of rectangle.
     * @param obj
     *            the Object - the array of primitive pixel data to be set.
     * @param data
     *            the image data.
     */
    public void setDataElements(int x, int y, int w, int h, Object obj, DataBuffer data) {
        int numDataElements = getNumDataElements();
        int idx = 0;

        switch (getTransferType()) {
            case DataBuffer.TYPE_BYTE:
                byte bbuf[] = new byte[numDataElements];
                for (int i = y; i < y + h; i++) {
                    for (int j = x; j < x + w; j++) {
                        for (int n = 0; n < numDataElements; n++) {
                            bbuf[n] = ((byte[])obj)[idx++];
                        }
                        setDataElements(j, i, bbuf, data);
                    }
                }

                break;

            case DataBuffer.TYPE_SHORT:
            case DataBuffer.TYPE_USHORT:
                short sbuf[] = new short[numDataElements];
                for (int i = y; i < y + h; i++) {
                    for (int j = x; j < x + w; j++) {
                        for (int n = 0; n < numDataElements; n++) {
                            sbuf[n] = ((short[])obj)[idx++];
                        }
                        setDataElements(j, i, sbuf, data);
                    }
                }
                break;

            case DataBuffer.TYPE_INT:
                int ibuf[] = new int[numDataElements];
                for (int i = y; i < y + h; i++) {
                    for (int j = x; j < x + w; j++) {
                        for (int n = 0; n < numDataElements; n++) {
                            ibuf[n] = ((int[])obj)[idx++];
                        }
                        setDataElements(j, i, ibuf, data);
                    }
                }
                break;

            case DataBuffer.TYPE_FLOAT:
                float fbuf[] = new float[numDataElements];
                for (int i = y; i < y + h; i++) {
                    for (int j = x; j < x + w; j++) {
                        for (int n = 0; n < numDataElements; n++) {
                            fbuf[n] = ((float[])obj)[idx++];
                        }
                        setDataElements(j, i, fbuf, data);
                    }
                }
                break;

            case DataBuffer.TYPE_DOUBLE:
                double dbuf[] = new double[numDataElements];
                for (int i = y; i < y + h; i++) {
                    for (int j = x; j < x + w; j++) {
                        for (int n = 0; n < numDataElements; n++) {
                            dbuf[n] = ((double[])obj)[idx++];
                        }
                        setDataElements(j, i, dbuf, data);
                    }
                }
                break;

        }
    }

    /**
     * Creates a new SampleModel with the specified bands of this SampleModel.
     * 
     * @param bands
     *            the array of bands from this SampleModel.
     * @return the SampleModel with the specified bands of this SampleModel.
     */
    public abstract SampleModel createSubsetSampleModel(int bands[]);

    /**
     * Creates the SampleModel which has the same data as in this SampleModel
     * with a different width and height.
     * 
     * @param a0
     *            the width of the image data.
     * @param a1
     *            the height of the image data.
     * @return the SampleModel which has the same data as in this SampleModel
     *         with a different width and height.
     */
    public abstract SampleModel createCompatibleSampleModel(int a0, int a1);

    /**
     * Gets the samples of the specified pixel as an integer array.
     * 
     * @param x
     *            the X coordinate of pixel.
     * @param y
     *            the Y coordinate of pixel.
     * @param iArray
     *            the integer array where result will be stored.
     * @param data
     *            the image data.
     * @return the integer array with the samples of the specified pixel.
     */
    public int[] getPixel(int x, int y, int iArray[], DataBuffer data) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        int pixel[];

        if (iArray == null) {
            pixel = new int[numBands];
        } else {
            pixel = iArray;
        }

        for (int i = 0; i < numBands; i++) {
            pixel[i] = getSample(x, y, i, data);
        }

        return pixel;
    }

    /**
     * Sets a pixel of the DataBuffer from a integer array of samples.
     * 
     * @param x
     *            the X coordinate of pixel.
     * @param y
     *            the Y coordinate of pixel.
     * @param iArray
     *            the integer array.
     * @param data
     *            the image data.
     */
    public void setPixel(int x, int y, int iArray[], DataBuffer data) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        for (int i = 0; i < numBands; i++) {
            setSample(x, y, i, iArray[i], data);
        }
    }

    /**
     * Gets the samples of the specified pixel as a float array.
     * 
     * @param x
     *            the X coordinate of pixel.
     * @param y
     *            the Y coordinate of pixel.
     * @param fArray
     *            the float array where result will be stored.
     * @param data
     *            the image data.
     * @return the float array with the samples of the specified pixel.
     */
    public float[] getPixel(int x, int y, float fArray[], DataBuffer data) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        float pixel[];

        if (fArray == null) {
            pixel = new float[numBands];
        } else {
            pixel = fArray;
        }

        for (int i = 0; i < numBands; i++) {
            pixel[i] = getSampleFloat(x, y, i, data);
        }

        return pixel;
    }

    /**
     * Sets a pixel of the DataBuffer from a float array of samples.
     * 
     * @param x
     *            the X coordinate of pixel.
     * @param y
     *            the Y coordinate of pixel.
     * @param fArray
     *            the float array.
     * @param data
     *            the image data.
     */
    public void setPixel(int x, int y, float fArray[], DataBuffer data) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        for (int i = 0; i < numBands; i++) {
            setSample(x, y, i, fArray[i], data);
        }
    }

    /**
     * Gets the samples of the specified pixel as a double array.
     * 
     * @param x
     *            the X coordinate of pixel.
     * @param y
     *            the Y coordinate of pixel.
     * @param dArray
     *            the double array where result will be stored.
     * @param data
     *            the image data.
     * @return the double array with the samples of the specified pixel.
     */
    public double[] getPixel(int x, int y, double dArray[], DataBuffer data) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        double pixel[];

        if (dArray == null) {
            pixel = new double[numBands];
        } else {
            pixel = dArray;
        }

        for (int i = 0; i < numBands; i++) {
            pixel[i] = getSampleDouble(x, y, i, data);
        }

        return pixel;
    }

    /**
     * Sets a pixel of the DataBuffer from a double array of samples.
     * 
     * @param x
     *            the X coordinate of pixel.
     * @param y
     *            the Y coordinate of pixel.
     * @param dArray
     *            the double array.
     * @param data
     *            the image data.
     */
    public void setPixel(int x, int y, double dArray[], DataBuffer data) {
        if (x < 0 || y < 0 || x >= this.width || y >= this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        for (int i = 0; i < numBands; i++) {
            setSample(x, y, i, dArray[i], data);
        }
    }

    /**
     * Gets the sample of a specified band for the specified pixel as an
     * integer.
     * 
     * @param x
     *            the X coordinate of pixel.
     * @param y
     *            the Y coordinate of pixel.
     * @param b
     *            the specified band.
     * @param data
     *            the image data.
     * @return the sample of a specified band for the specified pixel.
     */
    public abstract int getSample(int x, int y, int b, DataBuffer data);

    /**
     * Gets the sample of a specified band for the specified pixel as a float.
     * 
     * @param x
     *            the X coordinate of pixel.
     * @param y
     *            the Y coordinate of pixel.
     * @param b
     *            the specified band.
     * @param data
     *            the image data.
     * @return the sample of a specified band for the specified pixel.
     */
    public float getSampleFloat(int x, int y, int b, DataBuffer data) {
        return getSample(x, y, b, data);
    }

    /**
     * Gets the sample of a specified band for the specified pixel as a double.
     * 
     * @param x
     *            the X coordinate of pixel.
     * @param y
     *            the Y coordinate of pixel.
     * @param b
     *            the specified band.
     * @param data
     *            the image data.
     * @return the sample of a specified band for the specified pixel.
     */
    public double getSampleDouble(int x, int y, int b, DataBuffer data) {
        return getSample(x, y, b, data);
    }

    /**
     * Gets the samples of the specified rectangular area of pixels as an
     * integer array.
     * 
     * @param x
     *            the X coordinate of the rectangle of pixels.
     * @param y
     *            the Y coordinate of the rectangle of pixels.
     * @param w
     *            the width of the rectangle of pixels.
     * @param h
     *            the height of the rectangle of pixels.
     * @param iArray
     *            the integer array where result will be stored.
     * @param data
     *            the image data.
     * @return the integer array with the samples of the specified rectangular
     *         area of pixels.
     */
    public int[] getPixels(int x, int y, int w, int h, int iArray[], DataBuffer data) {
        if (x < 0 || y < 0 || x + w > this.width || y + h > this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        int pixels[];
        int idx = 0;

        if (iArray == null) {
            pixels = new int[w * h * numBands];
        } else {
            pixels = iArray;
        }

        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                for (int n = 0; n < numBands; n++) {
                    pixels[idx++] = getSample(j, i, n, data);
                }
            }
        }
        return pixels;
    }

    /**
     * Sets all of the samples for a rectangular area of pixels of the
     * DataBuffer from an integer array.
     * 
     * @param x
     *            the X coordinate of the rectangle of pixels.
     * @param y
     *            the Y coordinate of the rectangle of pixels.
     * @param w
     *            the width of the rectangle of pixels.
     * @param h
     *            the height of the rectangle of pixels.
     * @param iArray
     *            the integer array.
     * @param data
     *            the image data.
     */
    public void setPixels(int x, int y, int w, int h, int iArray[], DataBuffer data) {
        if (x < 0 || y < 0 || x + w > this.width || y + h > this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        int idx = 0;
        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                for (int n = 0; n < numBands; n++) {
                    setSample(j, i, n, iArray[idx++], data);
                }
            }
        }
    }

    /**
     * Gets the samples of the specified rectangular area of pixels as a float
     * array.
     * 
     * @param x
     *            the X coordinate of the rectangle of pixels.
     * @param y
     *            the Y coordinate of the rectangle of pixels.
     * @param w
     *            the width of the rectangle of pixels.
     * @param h
     *            the height of the rectangle of pixels.
     * @param fArray
     *            the float array where result will be stored.
     * @param data
     *            the image data.
     * @return the float array with the samples of the specified rectangular
     *         area of pixels.
     */
    public float[] getPixels(int x, int y, int w, int h, float fArray[], DataBuffer data) {
        if (x < 0 || y < 0 || x + w > this.width || y + h > this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        float pixels[];
        int idx = 0;

        if (fArray == null) {
            pixels = new float[w * h * numBands];
        } else {
            pixels = fArray;
        }

        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                for (int n = 0; n < numBands; n++) {
                    pixels[idx++] = getSampleFloat(j, i, n, data);
                }
            }
        }
        return pixels;
    }

    /**
     * Sets all of the samples for a rectangular area of pixels of the
     * DataBuffer from a float array.
     * 
     * @param x
     *            the X coordinate of the rectangle of pixels.
     * @param y
     *            the Y coordinate of the rectangle of pixels.
     * @param w
     *            the width of the rectangle of pixels.
     * @param h
     *            the height of the rectangle of pixels.
     * @param fArray
     *            the float array.
     * @param data
     *            the image data.
     */
    public void setPixels(int x, int y, int w, int h, float fArray[], DataBuffer data) {
        if (x < 0 || y < 0 || x + w > this.width || y + h > this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        int idx = 0;
        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                for (int n = 0; n < numBands; n++) {
                    setSample(j, i, n, fArray[idx++], data);
                }
            }
        }
    }

    /**
     * Gets the samples of the specified rectangular area of pixels as a double
     * array.
     * 
     * @param x
     *            the X coordinate of the rectangle of pixels.
     * @param y
     *            the Y coordinate of the rectangle of pixels.
     * @param w
     *            the width of the rectangle of pixels.
     * @param h
     *            the height of the rectangle of pixels.
     * @param dArray
     *            the double array where result will be stored.
     * @param data
     *            the image data.
     * @return the double array with the samples of the specified rectangular
     *         area of pixels.
     */
    public double[] getPixels(int x, int y, int w, int h, double dArray[], DataBuffer data) {
        if (x < 0 || y < 0 || x + w > this.width || y + h > this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        double pixels[];
        int idx = 0;

        if (dArray == null) {
            pixels = new double[w * h * numBands];
        } else {
            pixels = dArray;
        }

        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                for (int n = 0; n < numBands; n++) {
                    pixels[idx++] = getSampleDouble(j, i, n, data);
                }
            }
        }
        return pixels;
    }

    /**
     * Sets all of the samples for a rectangular area of pixels of the
     * DataBuffer from a double array.
     * 
     * @param x
     *            the X coordinate of the rectangle of pixels.
     * @param y
     *            the Y coordinate of the rectangle of pixels.
     * @param w
     *            the width of the rectangle of pixels.
     * @param h
     *            the height of the rectangle of pixels.
     * @param dArray
     *            the double array.
     * @param data
     *            the image data.
     */
    public void setPixels(int x, int y, int w, int h, double dArray[], DataBuffer data) {
        if (x < 0 || y < 0 || x + w > this.width || y + h > this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }
        int idx = 0;
        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                for (int n = 0; n < numBands; n++) {
                    setSample(j, i, n, dArray[idx++], data);
                }
            }
        }
    }

    /**
     * Sets a sample of the specified band for the specified pixel in the
     * DataBuffer as integer value.
     * 
     * @param x
     *            the X coordinate of the pixel.
     * @param y
     *            the Y coordinate of the pixel.
     * @param b
     *            the specified band.
     * @param s
     *            the sample as an integer value.
     * @param data
     *            the image data.
     */
    public abstract void setSample(int x, int y, int b, int s, DataBuffer data);

    /**
     * Gets the samples of a specified band for a specified rectangular area of
     * pixels as a integer array.
     * 
     * @param x
     *            the X coordinate of the rectangle.
     * @param y
     *            the Y coordinate of the rectangle.
     * @param w
     *            the width of the rectangle.
     * @param h
     *            the height of the rectangle.
     * @param b
     *            the specified band.
     * @param iArray
     *            the integer array where result will be stored.
     * @param data
     *            the image data.
     * @return the samples of a specified band for a specified rectangular area
     *         of pixels.
     */
    public int[] getSamples(int x, int y, int w, int h, int b, int iArray[], DataBuffer data) {
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

    /**
     * Sets the samples from an integer array in the specified band for the
     * specified rectangle of pixels.
     * 
     * @param x
     *            the X coordinate of the rectangle.
     * @param y
     *            the Y coordinate of the rectangle.
     * @param w
     *            the width of the rectangle.
     * @param h
     *            the height of the rectangle.
     * @param b
     *            the specified band.
     * @param iArray
     *            the integer array.
     * @param data
     *            the image data.
     */
    public void setSamples(int x, int y, int w, int h, int b, int iArray[], DataBuffer data) {
        int idx = 0;
        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                setSample(j, i, b, iArray[idx++], data);
            }
        }
    }

    /**
     * Gets the samples of a specified band for a specified rectangular area of
     * pixels as a float array.
     * 
     * @param x
     *            the X coordinate of the rectangle.
     * @param y
     *            the Y coordinate of the rectangle.
     * @param w
     *            the width of the rectangle.
     * @param h
     *            the height of the rectangle.
     * @param b
     *            the specified band.
     * @param fArray
     *            the float array where result will be stored.
     * @param data
     *            the image data.
     * @return the samples of a specified band for a specified rectangular area
     *         of pixels.
     */
    public float[] getSamples(int x, int y, int w, int h, int b, float fArray[], DataBuffer data) {
        float samples[];
        int idx = 0;

        if (fArray == null) {
            samples = new float[w * h];
        } else {
            samples = fArray;
        }

        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                samples[idx++] = getSampleFloat(j, i, b, data);
            }
        }

        return samples;
    }

    /**
     * Sets the samples from an float array in the specified band for the
     * specified rectangle of pixels.
     * 
     * @param x
     *            the X coordinate of the rectangle.
     * @param y
     *            the Y coordinate of the rectangle.
     * @param w
     *            the width of the rectangle.
     * @param h
     *            the height of the rectangle.
     * @param b
     *            the specified band.
     * @param fArray
     *            the float array.
     * @param data
     *            the image data.
     */
    public void setSamples(int x, int y, int w, int h, int b, float fArray[], DataBuffer data) {
        int idx = 0;
        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                setSample(j, i, b, fArray[idx++], data);
            }
        }
    }

    /**
     * Gets the samples of a specified band for a specified rectangular area of
     * pixels as a double array.
     * 
     * @param x
     *            the X coordinate of the rectangle.
     * @param y
     *            the Y coordinate of the rectangle.
     * @param w
     *            the width of the rectangle.
     * @param h
     *            the height of the rectangle.
     * @param b
     *            the specified band.
     * @param dArray
     *            the double array where result will be stored.
     * @param data
     *            the image data.
     * @return the samples of a specified band for a specified rectangular area
     *         of pixels.
     */
    public double[] getSamples(int x, int y, int w, int h, int b, double dArray[], DataBuffer data) {
        double samples[];
        int idx = 0;

        if (dArray == null) {
            samples = new double[w * h];
        } else {
            samples = dArray;
        }

        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                samples[idx++] = getSampleDouble(j, i, b, data);
            }
        }

        return samples;
    }

    /**
     * Sets the samples from an double array in the specified band for the
     * specified rectangle of pixels.
     * 
     * @param x
     *            the X coordinate of the rectangle.
     * @param y
     *            the Y coordinate of the rectangle.
     * @param w
     *            the width of the rectangle.
     * @param h
     *            the height of the rectangle.
     * @param b
     *            the specified band.
     * @param dArray
     *            the double array.
     * @param data
     *            the image data.
     */
    public void setSamples(int x, int y, int w, int h, int b, double dArray[], DataBuffer data) {
        int idx = 0;
        for (int i = y; i < y + h; i++) {
            for (int j = x; j < x + w; j++) {
                setSample(j, i, b, dArray[idx++], data);
            }
        }
    }

    /**
     * Sets a sample of the specified band for the specified pixel in the
     * DataBuffer as float value.
     * 
     * @param x
     *            the X coordinate of the pixel.
     * @param y
     *            the Y coordinate of the pixel.
     * @param b
     *            the specified band.
     * @param s
     *            the sample as float value.
     * @param data
     *            the image data.
     */
    public void setSample(int x, int y, int b, float s, DataBuffer data) {
        setSample(x, y, b, (int)s, data);
    }

    /**
     * Sets a sample of the specified band for the specified pixel in the
     * DataBuffer as double value.
     * 
     * @param x
     *            the X coordinate of the pixel.
     * @param y
     *            the Y coordinate of the pixel.
     * @param b
     *            the specified band.
     * @param s
     *            the sample as double value.
     * @param data
     *            the image data.
     */
    public void setSample(int x, int y, int b, double s, DataBuffer data) {
        setSample(x, y, b, (int)s, data);
    }

    /**
     * Creates a DataBuffer object which corresponds to the SampleModel.
     * 
     * @return the DataBuffer object which corresponds to the SampleModel.
     */
    public abstract DataBuffer createDataBuffer();

    /**
     * Gets the sample size in bits for the specified band.
     * 
     * @param band
     *            the specified band.
     * @return the sample size in bits for the specified band.
     */
    public abstract int getSampleSize(int band);

    /**
     * Gets an array of the sample size in bits for all bands.
     * 
     * @return an array of the sample size in bits for all bands.
     */
    public abstract int[] getSampleSize();

    /**
     * Gets the width of the image data of this SampleModel object.
     * 
     * @return the width of the image data of this SampleModel object.
     */
    public final int getWidth() {
        return width;
    }

    /**
     * Gets the transfer type used to transfer pixels via the getDataElements
     * and setDataElements methods. Transfer type value can be one of the
     * predefined type from DataBuffer class or not.
     * 
     * @return the transfer type.
     */
    public int getTransferType() {
        return dataType;
    }

    /**
     * Returns the number of data elements for pixel transferring via the
     * getDataElements and setDataElements methods.
     * 
     * @return the number of data elements for pixel transferring via the
     *         getDataElements and setDataElements methods.
     */
    public abstract int getNumDataElements();

    /**
     * Gets the number of bands in the image data of this SampleModel object.
     * 
     * @return the number of bands in the image data of this SampleModel object.
     */
    public final int getNumBands() {
        return numBands;
    }

    /**
     * Gets the height of the image data of this SampleModel object.
     * 
     * @return the height of the image data of this SampleModel object.
     */
    public final int getHeight() {
        return height;
    }

    /**
     * Gets the data type of image data of this SampleModel object.
     * 
     * @return the data type of image data of this SampleModel object.
     */
    public final int getDataType() {
        return dataType;
    }

}
