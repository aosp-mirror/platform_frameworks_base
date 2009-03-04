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
 */
package org.apache.harmony.awt.gl.color;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * This class combines ColorScaler, ICC_Transform and NativeImageFormat functionality
 * in the workflows for different types of input/output pixel data.
 */
public class ColorConverter {
    private ColorScaler scaler = new ColorScaler();

    public void loadScalingData(ColorSpace cs) {
        scaler.loadScalingData(cs);
    }

    /**
     * Translates pixels, stored in source buffered image and writes the data
     * to the destination image.
     * @param t - ICC transform
     * @param src - source image
     * @param dst - destination image
     */
    public void translateColor(ICC_Transform t,
            BufferedImage src, BufferedImage dst) {
      NativeImageFormat srcIF = NativeImageFormat.createNativeImageFormat(src);
      NativeImageFormat dstIF = NativeImageFormat.createNativeImageFormat(dst);

      if (srcIF != null && dstIF != null) {
          t.translateColors(srcIF, dstIF);
          return;
      }

        srcIF = createImageFormat(src);
        dstIF = createImageFormat(dst);

        short srcChanData[] = (short[]) srcIF.getChannelData();
        short dstChanData[] = (short[]) dstIF.getChannelData();

        ColorModel srcCM = src.getColorModel();
        int nColorChannels = srcCM.getNumColorComponents();
        scaler.loadScalingData(srcCM.getColorSpace()); // input scaling data
        ColorModel dstCM = dst.getColorModel();

        // Prepare array for alpha channel
        float alpha[] = null;
        boolean saveAlpha = srcCM.hasAlpha() && dstCM.hasAlpha();
        if (saveAlpha) {
            alpha = new float[src.getWidth()*src.getHeight()];
        }

        WritableRaster wr = src.getRaster();
        int srcDataPos = 0, alphaPos = 0;
        float normalizedVal[];
        for (int row=0, nRows = srcIF.getNumRows(); row<nRows; row++) {
            for (int col=0, nCols = srcIF.getNumCols(); col<nCols; col++) {
                normalizedVal = srcCM.getNormalizedComponents(
                    wr.getDataElements(col, row, null),
                    null, 0);
                // Save alpha channel
                if (saveAlpha) {
                    // We need nColorChannels'th element cause it's nChannels - 1
                    alpha[alphaPos++] = normalizedVal[nColorChannels];
                }
                scaler.scale(normalizedVal, srcChanData, srcDataPos);
                srcDataPos += nColorChannels;
            }
        }

        t.translateColors(srcIF, dstIF);

        nColorChannels = dstCM.getNumColorComponents();
        boolean fillAlpha = dstCM.hasAlpha();
        scaler.loadScalingData(dstCM.getColorSpace()); // output scaling data
        float dstPixel[] = new float[dstCM.getNumComponents()];
        int dstDataPos = 0;
        alphaPos = 0;
        wr = dst.getRaster();

        for (int row=0, nRows = dstIF.getNumRows(); row<nRows; row++) {
            for (int col=0, nCols = dstIF.getNumCols(); col<nCols; col++) {
                scaler.unscale(dstPixel, dstChanData, dstDataPos);
                dstDataPos += nColorChannels;
                if (fillAlpha) {
                    if (saveAlpha) {
                        dstPixel[nColorChannels] = alpha[alphaPos++];
                    } else {
                        dstPixel[nColorChannels] = 1f;
                    }
                }
                wr.setDataElements(col, row,
                        dstCM.getDataElements(dstPixel, 0 , null));
            }
        }
    }

    /**
     * Translates pixels, stored in the float data buffer.
     * Each pixel occupies separate array. Input pixels passed in the buffer
     * are replaced by output pixels and then the buffer is returned
     * @param t - ICC transform
     * @param buffer - data buffer
     * @param srcCS - source color space
     * @param dstCS - destination color space
     * @param nPixels - number of pixels
     * @return translated pixels
     */
    public float[][] translateColor(ICC_Transform t,
            float buffer[][],
            ColorSpace srcCS,
            ColorSpace dstCS,
            int nPixels) {
        // Scale source data
        if (srcCS != null) { // if it is null use old scaling data
            scaler.loadScalingData(srcCS);
        }
        int nSrcChannels = t.getNumInputChannels();
        short srcShortData[] = new short[nPixels*nSrcChannels];
        for (int i=0, srcDataPos = 0; i<nPixels; i++) {
            scaler.scale(buffer[i], srcShortData, srcDataPos);
            srcDataPos += nSrcChannels;
        }

        // Apply transform
        short dstShortData[] = this.translateColor(t, srcShortData, null);

        int nDstChannels = t.getNumOutputChannels();
        int bufferSize = buffer[0].length;
        if (bufferSize < nDstChannels + 1) { // Re-allocate buffer if needed
            for (int i=0; i<nPixels; i++) {
                // One extra element reserved for alpha
                buffer[i] = new float[nDstChannels + 1];
            }
        }

        // Unscale destination data
        if (dstCS != null) { // if it is null use old scaling data
            scaler.loadScalingData(dstCS);
        }
        for (int i=0, dstDataPos = 0; i<nPixels; i++) {
            scaler.unscale(buffer[i], dstShortData, dstDataPos);
            dstDataPos += nDstChannels;
        }

        return buffer;
    }

    /**
     * Translates pixels stored in a raster.
     * All data types are supported
     * @param t - ICC transform
     * @param src - source pixels
     * @param dst - destination pixels
     */
   public void translateColor(ICC_Transform t, Raster src, WritableRaster dst) {
        try{
            NativeImageFormat srcFmt = NativeImageFormat.createNativeImageFormat(src);
            NativeImageFormat dstFmt = NativeImageFormat.createNativeImageFormat(dst);

          if (srcFmt != null && dstFmt != null) {
              t.translateColors(srcFmt, dstFmt);
              return;
          }
        } catch (IllegalArgumentException e) {
      }

        // Go ahead and rescale the source image
        scaler.loadScalingData(src, t.getSrc());
        short srcData[] = scaler.scale(src);

        short dstData[] = translateColor(t, srcData, null);

        scaler.loadScalingData(dst, t.getDst());
        scaler.unscale(dstData, dst);
   }

    /**
     * Translates pixels stored in an array of shorts.
     * Samples are stored one-by-one, i.e. array structure is like following: RGBRGBRGB...
     * The number of pixels is (size of the array) / (number of components).
     * @param t - ICC transform
     * @param src - source pixels
     * @param dst - destination pixels
     * @return destination pixels, stored in the array, passed in dst
     */
    public short[] translateColor(ICC_Transform t, short src[], short dst[]) {
        NativeImageFormat srcFmt = createImageFormat(t, src, 0, true);
        NativeImageFormat dstFmt = createImageFormat(t, dst, srcFmt.getNumCols(), false);

        t.translateColors(srcFmt, dstFmt);

        return (short[]) dstFmt.getChannelData();
    }


    /**
     * Creates NativeImageFormat from buffered image.
     * @param bi - buffered image
     * @return created NativeImageFormat
     */
    private NativeImageFormat createImageFormat(BufferedImage bi) {
        int nRows = bi.getHeight();
        int nCols = bi.getWidth();
        int nComps = bi.getColorModel().getNumColorComponents();
        short imgData[] = new short[nRows*nCols*nComps];
        return new NativeImageFormat(
                imgData, nComps, nRows, nCols);
    }

    /**
     * Creates one-row NativeImageFormat, using either nCols if it is positive,
     * or arr.length to determine the number of pixels
     *
     * @param t - transform
     * @param arr - short array or null if nCols is positive
     * @param nCols - number of pixels in the array or 0 if array is not null
     * @param in - is it an input or output array
     * @return one-row NativeImageFormat
     */
    private NativeImageFormat createImageFormat(
            ICC_Transform t, short arr[], int nCols, boolean in
    ) {
        int nComponents = in ? t.getNumInputChannels() : t.getNumOutputChannels();

        if (arr == null || arr.length < nCols*nComponents) {
            arr = new short[nCols*nComponents];
        }

        if (nCols == 0)
            nCols = arr.length / nComponents;

        return new NativeImageFormat(arr, nComponents, 1, nCols);
    }
}
