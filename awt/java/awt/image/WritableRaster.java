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

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The WritableRaster class provides functionality for writing samples and pixel
 * capabilities to the Raster.
 * 
 * @since Android 1.0
 */
public class WritableRaster extends Raster {

    /**
     * Instantiates a new WritableRaster object with the specified SampleModel,
     * DataBuffer, rectangular region and parent WritableRaster.
     * 
     * @param sampleModel
     *            the specified SampleModel.
     * @param dataBuffer
     *            the specified DataBuffer.
     * @param aRegion
     *            the rectangular region which defines the new image bounds.
     * @param sampleModelTranslate
     *            this point defines the translation point from the SampleModel
     *            to the new WritableRaster coordinates.
     * @param parent
     *            the parent of this WritableRaster.
     */
    protected WritableRaster(SampleModel sampleModel, DataBuffer dataBuffer, Rectangle aRegion,
            Point sampleModelTranslate, WritableRaster parent) {
        super(sampleModel, dataBuffer, aRegion, sampleModelTranslate, parent);
    }

    /**
     * Instantiates a new WritableRaster object with the specified SampleModel
     * which defines a layout of this WritableRaster and DataBuffer objects
     * which defines the image data.
     * 
     * @param sampleModel
     *            the specified SampleModel.
     * @param dataBuffer
     *            the specified DataBuffer.
     * @param origin
     *            the point of origin.
     */
    protected WritableRaster(SampleModel sampleModel, DataBuffer dataBuffer, Point origin) {
        this(sampleModel, dataBuffer, new Rectangle(origin.x, origin.y, sampleModel.width,
                sampleModel.height), origin, null);
    }

    /**
     * Instantiates a new WritableRaster with the specified SampleModel.
     * 
     * @param sampleModel
     *            the specified SampleModel.
     * @param origin
     *            the origin.
     */
    protected WritableRaster(SampleModel sampleModel, Point origin) {
        this(sampleModel, sampleModel.createDataBuffer(), new Rectangle(origin.x, origin.y,
                sampleModel.width, sampleModel.height), origin, null);
    }

    /**
     * Sets the data for a single pixel from an input Object which represents an
     * array of primitive types: DataBuffer.TYPE_BYTE, DataBuffer.TYPE_USHORT,
     * DataBuffer.TYPE_INT, DataBuffer.TYPE_SHORT, DataBuffer.TYPE_FLOAT, or
     * DataBuffer.TYPE_DOUBLE.
     * 
     * @param x
     *            the X coordinate of the pixel.
     * @param y
     *            the Y coordinate of the pixel.
     * @param inData
     *            the input data.
     */
    public void setDataElements(int x, int y, Object inData) {
        sampleModel.setDataElements(x - sampleModelTranslateX, y - sampleModelTranslateY, inData,
                dataBuffer);
    }

    /**
     * Sets the data elements which represent pixel data to the specified
     * rectangle area as a primitive array. The following image data types are
     * supported: DataBuffer.TYPE_BYTE, DataBuffer.TYPE_USHORT,
     * DataBuffer.TYPE_INT, DataBuffer.TYPE_SHORT, DataBuffer.TYPE_FLOAT, or
     * DataBuffer.TYPE_DOUBLE.
     * 
     * @param x
     *            the X coordinate of the rectangle of pixels.
     * @param y
     *            the Y coordinate of the rectangle of pixels.
     * @param w
     *            the width of the rectangle of pixels.
     * @param h
     *            the height of the rectangle of pixels.
     * @param inData
     *            the array of primitive type data to be set to the specified
     *            area.
     */
    public void setDataElements(int x, int y, int w, int h, Object inData) {
        sampleModel.setDataElements(x - sampleModelTranslateX, y - sampleModelTranslateY, w, h,
                inData, dataBuffer);
    }

    /**
     * Creates the child of this WritableRaster by sharing the specified
     * rectangular area in this WritableRaster. The parentX, parentY, width and
     * height parameters specify rectangular area to be shared.
     * 
     * @param parentX
     *            the X coordinate of the upper left corner of the shared
     *            rectangle with respect to this WritableRaster' coordinates.
     * @param parentY
     *            the Y coordinate of the upper left corner of the shared
     *            rectangle with respect to this WritableRaster' coordinates.
     * @param w
     *            the width of the child area.
     * @param h
     *            the height of the child area.
     * @param childMinX
     *            the X coordinate of child area mapped to the parentX
     *            coordinate.
     * @param childMinY
     *            the Y coordinate of child area mapped to the parentY
     *            coordinate.
     * @param bandList
     *            the array of band indices.
     * @return the child WritableRaster.
     */
    public WritableRaster createWritableChild(int parentX, int parentY, int w, int h,
            int childMinX, int childMinY, int bandList[]) {
        if (w <= 0 || h <= 0) {
            // awt.244=Width or Height of child Raster is less than or equal to
            // zero
            throw new RasterFormatException(Messages.getString("awt.244")); //$NON-NLS-1$
        }

        if (parentX < this.minX || parentX + w > this.minX + this.width) {
            // awt.245=parentX disposes outside Raster
            throw new RasterFormatException(Messages.getString("awt.245")); //$NON-NLS-1$
        }

        if (parentY < this.minY || parentY + h > this.minY + this.height) {
            // awt.246=parentY disposes outside Raster
            throw new RasterFormatException(Messages.getString("awt.246")); //$NON-NLS-1$
        }

        if ((long)parentX + w > Integer.MAX_VALUE) {
            // awt.247=parentX + w results in integer overflow
            throw new RasterFormatException(Messages.getString("awt.247")); //$NON-NLS-1$
        }

        if ((long)parentY + h > Integer.MAX_VALUE) {
            // awt.248=parentY + h results in integer overflow
            throw new RasterFormatException(Messages.getString("awt.248")); //$NON-NLS-1$
        }

        if ((long)childMinX + w > Integer.MAX_VALUE) {
            // awt.249=childMinX + w results in integer overflow
            throw new RasterFormatException(Messages.getString("awt.249")); //$NON-NLS-1$
        }

        if ((long)childMinY + h > Integer.MAX_VALUE) {
            // awt.24A=childMinY + h results in integer overflow
            throw new RasterFormatException(Messages.getString("awt.24A")); //$NON-NLS-1$
        }

        SampleModel childModel;

        if (bandList == null) {
            childModel = sampleModel;
        } else {
            childModel = sampleModel.createSubsetSampleModel(bandList);
        }

        int childTranslateX = childMinX - parentX;
        int childTranslateY = childMinY - parentY;

        return new WritableRaster(childModel, dataBuffer,
                new Rectangle(childMinX, childMinY, w, h), new Point(childTranslateX
                        + sampleModelTranslateX, childTranslateY + sampleModelTranslateY), this);
    }

    /**
     * Creates the translated child of this WritableRaster. New WritableRaster
     * object is a reference to the this WritableRaster and with different
     * location.
     * 
     * @param childMinX
     *            the X coordinate of the new WritableRaster.
     * @param childMinY
     *            the Y coordinate of the new WritableRaster.
     * @return the WritableRaster.
     */
    public WritableRaster createWritableTranslatedChild(int childMinX, int childMinY) {
        return createWritableChild(minX, minY, width, height, childMinX, childMinY, null);
    }

    /**
     * Gets the parent WritableRaster for this WritableRaster object.
     * 
     * @return the parent WritableRaster for this WritableRaster object.
     */
    public WritableRaster getWritableParent() {
        return (WritableRaster)parent;
    }

    /**
     * Sets pixels from the specified source Raster srcRaster to this
     * WritableRaster.
     * 
     * @param srcRaster
     *            the source Raster.
     */
    public void setRect(Raster srcRaster) {
        setRect(0, 0, srcRaster);
    }

    /**
     * Sets pixels from the specified source Raster srcRaster to this
     * WritableRaster. Each pixel with (x, y) coordinates from the source Raster
     * is copied to pixel with (x+dx, y+dy) coordinates in this WritableRaster.
     * The pixels with (x+dx, y+dy) coordinates which are out the bounds of this
     * raster are ignored.
     * 
     * @param dx
     *            the distance the pixel's X coordinate in the source Raster is
     *            translated when writtien to this WritableRaster.
     * @param dy
     *            the distance the pixel's Y coordinate in the source Raster is
     *            translated when writtien to this WritableRaster.
     * @param srcRaster
     *            the source Raster.
     */
    public void setRect(int dx, int dy, Raster srcRaster) {
        int w = srcRaster.getWidth();
        int h = srcRaster.getHeight();

        int srcX = srcRaster.getMinX();
        int srcY = srcRaster.getMinY();

        int dstX = srcX + dx;
        int dstY = srcY + dy;

        if (dstX < this.minX) {
            int minOffX = this.minX - dstX;
            w -= minOffX;
            dstX = this.minX;
            srcX += minOffX;
        }

        if (dstY < this.minY) {
            int minOffY = this.minY - dstY;
            h -= minOffY;
            dstY = this.minY;
            srcY += minOffY;
        }

        if (dstX + w > this.minX + this.width) {
            int maxOffX = (dstX + w) - (this.minX + this.width);
            w -= maxOffX;
        }

        if (dstY + h > this.minY + this.height) {
            int maxOffY = (dstY + h) - (this.minY + this.height);
            h -= maxOffY;
        }

        if (w <= 0 || h <= 0) {
            return;
        }

        switch (sampleModel.getDataType()) {
            case DataBuffer.TYPE_BYTE:
            case DataBuffer.TYPE_SHORT:
            case DataBuffer.TYPE_USHORT:
            case DataBuffer.TYPE_INT:
                int iPixelsLine[] = null;
                for (int i = 0; i < h; i++) {
                    iPixelsLine = srcRaster.getPixels(srcX, srcY + i, w, 1, iPixelsLine);
                    setPixels(dstX, dstY + i, w, 1, iPixelsLine);
                }
                break;

            case DataBuffer.TYPE_FLOAT:
                float fPixelsLine[] = null;
                for (int i = 0; i < h; i++) {
                    fPixelsLine = srcRaster.getPixels(srcX, srcY + i, w, 1, fPixelsLine);
                    setPixels(dstX, dstY + i, w, 1, fPixelsLine);
                }
                break;

            case DataBuffer.TYPE_DOUBLE:
                double dPixelsLine[] = null;
                for (int i = 0; i < h; i++) {
                    dPixelsLine = srcRaster.getPixels(srcX, srcY + i, w, 1, dPixelsLine);
                    setPixels(dstX, dstY + i, w, 1, dPixelsLine);
                }
                break;
        }
    }

    /**
     * Sets the data for a rectangle of pixels from an input Raster to this
     * WritableRaster.
     * 
     * @param x
     *            the X coordinate of the point where the data of the input
     *            Raster is to be written.
     * @param y
     *            the Y coordinate of the point where the data of the input
     *            Raster is to be written.
     * @param inRaster
     *            the input Raster.
     */
    public void setDataElements(int x, int y, Raster inRaster) {
        int dstX = x + inRaster.getMinX();
        int dstY = y + inRaster.getMinY();

        int w = inRaster.getWidth();
        int h = inRaster.getHeight();

        if (dstX < this.minX || dstX + w > this.minX + this.width || dstY < this.minY
                || dstY + h > this.minY + this.height) {
            // awt.63=Coordinates are not in bounds
            throw new ArrayIndexOutOfBoundsException(Messages.getString("awt.63")); //$NON-NLS-1$
        }

        int srcX = inRaster.getMinX();
        int srcY = inRaster.getMinY();
        Object line = null;

        for (int i = 0; i < h; i++) {
            line = inRaster.getDataElements(srcX, srcY + i, w, 1, line);
            setDataElements(dstX, dstY + i, w, 1, line);
        }
    }

    /**
     * Sets an integer array of samples for the specified pixel in this
     * WritableRaster.
     * 
     * @param x
     *            the pixel's X coordinate.
     * @param y
     *            the pixel's Y coordinate.
     * @param iArray
     *            the integer array of samples.
     */
    public void setPixel(int x, int y, int iArray[]) {
        sampleModel.setPixel(x - sampleModelTranslateX, y - sampleModelTranslateY, iArray,
                dataBuffer);
    }

    /**
     * Sets a float array of samples for the specified pixel in this
     * WritableRaster.
     * 
     * @param x
     *            the pixel's X coordinate.
     * @param y
     *            the pixel's Y coordinate.
     * @param fArray
     *            the float array of samples.
     */
    public void setPixel(int x, int y, float fArray[]) {
        sampleModel.setPixel(x - sampleModelTranslateX, y - sampleModelTranslateY, fArray,
                dataBuffer);
    }

    /**
     * Sets a double array of samples for the specified pixel in this
     * WritableRaster.
     * 
     * @param x
     *            the pixel's X coordinate.
     * @param y
     *            the pixel's Y coordinate.
     * @param dArray
     *            the double array of samples.
     */
    public void setPixel(int x, int y, double dArray[]) {
        sampleModel.setPixel(x - sampleModelTranslateX, y - sampleModelTranslateY, dArray,
                dataBuffer);
    }

    /**
     * Sets a integer array of samples for the specified rectangular area of
     * pixels in this WritableRaster.
     * 
     * @param x
     *            the X coordinate of rectangular area.
     * @param y
     *            the Y coordinate of rectangular area.
     * @param w
     *            the width of rectangular area.
     * @param h
     *            the height of rectangular area.
     * @param iArray
     *            the integer array of samples.
     */
    public void setPixels(int x, int y, int w, int h, int iArray[]) {
        sampleModel.setPixels(x - sampleModelTranslateX, y - sampleModelTranslateY, w, h, iArray,
                dataBuffer);
    }

    /**
     * Sets a float array of samples for the specified rectangular area of
     * pixels in this WritableRaster.
     * 
     * @param x
     *            the X coordinate of rectangular area.
     * @param y
     *            the Y coordinate of rectangular area.
     * @param w
     *            the width of rectangular area.
     * @param h
     *            the height of rectangular area.
     * @param fArray
     *            the float array of samples.
     */
    public void setPixels(int x, int y, int w, int h, float fArray[]) {
        sampleModel.setPixels(x - sampleModelTranslateX, y - sampleModelTranslateY, w, h, fArray,
                dataBuffer);
    }

    /**
     * Sets a double array of samples for the specified rectangular area of
     * pixels in this WritableRaster.
     * 
     * @param x
     *            the X coordinate of rectangular area.
     * @param y
     *            the Y coordinate of rectangular area.
     * @param w
     *            the width of rectangular area.
     * @param h
     *            the height of rectangular area.
     * @param dArray
     *            the double array of samples.
     */
    public void setPixels(int x, int y, int w, int h, double dArray[]) {
        sampleModel.setPixels(x - sampleModelTranslateX, y - sampleModelTranslateY, w, h, dArray,
                dataBuffer);
    }

    /**
     * Sets the samples for the specified band and the specified rectangular
     * area of pixels with an integer array of samples.
     * 
     * @param x
     *            the X coordinate of the area of pixels.
     * @param y
     *            the Y coordinate of the area of pixels.
     * @param w
     *            the width of the area of pixels.
     * @param h
     *            the height of the area of pixels.
     * @param b
     *            the specified band.
     * @param iArray
     *            the integer array of samples.
     */
    public void setSamples(int x, int y, int w, int h, int b, int iArray[]) {
        sampleModel.setSamples(x - sampleModelTranslateX, y - sampleModelTranslateY, w, h, b,
                iArray, dataBuffer);
    }

    /**
     * Sets the samples for the specified band and the specified rectangular
     * area of pixels with a float array of samples.
     * 
     * @param x
     *            the X coordinate of the area of pixels.
     * @param y
     *            the Y coordinate of the area of pixels.
     * @param w
     *            the width of the area of pixels.
     * @param h
     *            the height of the area of pixels.
     * @param b
     *            the specified band.
     * @param fArray
     *            the float array of samples.
     */
    public void setSamples(int x, int y, int w, int h, int b, float fArray[]) {
        sampleModel.setSamples(x - sampleModelTranslateX, y - sampleModelTranslateY, w, h, b,
                fArray, dataBuffer);
    }

    /**
     * Sets the samples for the specified band and the specified rectangular
     * area of pixels with a double array of samples.
     * 
     * @param x
     *            the X coordinate of the area of pixels.
     * @param y
     *            the Y coordinate of the area of pixels.
     * @param w
     *            the width of the area of pixels.
     * @param h
     *            the height of the area of pixels.
     * @param b
     *            the specified band.
     * @param dArray
     *            the double array of samples.
     */
    public void setSamples(int x, int y, int w, int h, int b, double dArray[]) {
        sampleModel.setSamples(x - sampleModelTranslateX, y - sampleModelTranslateY, w, h, b,
                dArray, dataBuffer);
    }

    /**
     * Sets the sample for the specified band and the specified pixel with an
     * integer sample.
     * 
     * @param x
     *            the X coordinate of the pixel.
     * @param y
     *            the Y coordinate of the pixel.
     * @param b
     *            the specified band.
     * @param s
     *            the sample to be set.
     */
    public void setSample(int x, int y, int b, int s) {
        sampleModel.setSample(x - sampleModelTranslateX, y - sampleModelTranslateY, b, s,
                dataBuffer);
    }

    /**
     * Sets the sample for the specified band and the specified pixel with a
     * float sample.
     * 
     * @param x
     *            the X coordinate of the pixel.
     * @param y
     *            the Y coordinate of the pixel.
     * @param b
     *            the specified band.
     * @param s
     *            the sample to be set.
     */
    public void setSample(int x, int y, int b, float s) {
        sampleModel.setSample(x - sampleModelTranslateX, y - sampleModelTranslateY, b, s,
                dataBuffer);
    }

    /**
     * Sets the sample for the specified band and the specified pixel with an
     * integer sample.
     * 
     * @param x
     *            the X coordinate of the pixel.
     * @param y
     *            the Y coordinate of the pixel.
     * @param b
     *            the specified band.
     * @param s
     *            the sample to be set.
     */
    public void setSample(int x, int y, int b, double s) {
        sampleModel.setSample(x - sampleModelTranslateX, y - sampleModelTranslateY, b, s,
                dataBuffer);
    }

}
