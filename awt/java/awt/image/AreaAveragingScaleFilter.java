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

/**
 * The AreaAveragingScaleFilter class scales the source image using area
 * averaging algorithm. This algorithm provides a source image with a new image
 * containing the resampled image.
 * 
 * @since Android 1.0
 */
public class AreaAveragingScaleFilter extends ReplicateScaleFilter {

    /**
     * The Constant rgbCM.
     */
    private static final ColorModel rgbCM = ColorModel.getRGBdefault();

    /**
     * The Constant averagingFlags.
     */
    private static final int averagingFlags = (ImageConsumer.TOPDOWNLEFTRIGHT | ImageConsumer.COMPLETESCANLINES);

    /**
     * The reset.
     */
    private boolean reset = true; // Flag for used superclass filter

    /**
     * The inited.
     */
    private boolean inited = false; // All data inited

    /**
     * The sum_r.
     */
    private int sum_r[]; // Array for average Red samples

    /**
     * The sum_g.
     */
    private int sum_g[]; // Array for average Green samples

    /**
     * The sum_b.
     */
    private int sum_b[]; // Array for average Blue samples

    /**
     * The sum_a.
     */
    private int sum_a[]; // Array for average Alpha samples

    /**
     * The buff.
     */
    private int buff[]; // Stride buffer

    /**
     * The avg factor.
     */
    private int avgFactor; // Global averaging factor

    /**
     * The cached dy.
     */
    private int cachedDY; // Cached number of the destination scanline

    /**
     * The cached dv rest.
     */
    private int cachedDVRest; // Cached value of rest src scanlines for sum

    // pixel samples
    // Because data if transferring by whole scanlines
    // we are caching only Y coordinate values

    /**
     * Instantiates a new AreaAveragingScaleFilter object which scales a source
     * image with the specified width and height.
     * 
     * @param width
     *            the scaled width of the image.
     * @param height
     *            the scaled height of the image.
     */
    public AreaAveragingScaleFilter(int width, int height) {
        super(width, height);
    }

    @Override
    public void setPixels(int x, int y, int w, int h, ColorModel model, int[] pixels, int off,
            int scansize) {
        if (reset) {
            super.setPixels(x, y, w, h, model, pixels, off, scansize);
        } else {
            setFilteredPixels(x, y, w, h, model, pixels, off, scansize);
        }
    }

    @Override
    public void setPixels(int x, int y, int w, int h, ColorModel model, byte[] pixels, int off,
            int scansize) {
        if (reset) {
            super.setPixels(x, y, w, h, model, pixels, off, scansize);
        } else {
            setFilteredPixels(x, y, w, h, model, pixels, off, scansize);
        }
    }

    @Override
    public void setHints(int hints) {
        super.setHints(hints);
        reset = ((hints & averagingFlags) != averagingFlags);
    }

    /**
     * This method implements the Area Averaging Scale filter. The description
     * of algorithm is presented in Java API Specification. Arrays sum_r, sum_g,
     * sum_b, sum_a have length equals width of destination image. In each
     * array's element is accumulating pixel's component values, proportional to
     * the area which source pixels will occupy in destination image. Then that
     * values will divide by Global averaging factor (area of the destination
     * image) for receiving average values of destination pixels.
     * 
     * @param x
     *            the source pixels X coordinate.
     * @param y
     *            the source pixels Y coordinate.
     * @param w
     *            the width of the area of the source pixels.
     * @param h
     *            the height of the area of the source pixels.
     * @param model
     *            the color model of the source pixels.
     * @param pixels
     *            the array of source pixels.
     * @param off
     *            the offset into the source pixels array.
     * @param scansize
     *            the length of scanline in the pixels array.
     */
    private void setFilteredPixels(int x, int y, int w, int h, ColorModel model, Object pixels,
            int off, int scansize) {
        if (!inited) {
            initialize();
        }

        int srcX, srcY, dx, dy;
        int svRest, dvRest, shRest, dhRest, vDif, hDif;

        if (y == 0) {
            dy = 0;
            dvRest = srcHeight;
        } else {
            dy = cachedDY;
            dvRest = cachedDVRest;
        }

        srcY = y;
        svRest = destHeight;

        int srcOff = off;
        while (srcY < y + h) {
            if (svRest < dvRest) {
                vDif = svRest;
            } else {
                vDif = dvRest;
            }

            srcX = 0;
            dx = 0;
            shRest = destWidth;
            dhRest = srcWidth;
            while (srcX < w) {
                if (shRest < dhRest) {
                    hDif = shRest;
                } else {
                    hDif = dhRest;
                }
                int avg = hDif * vDif; // calculation of contribution factor

                int rgb, pix;
                if (pixels instanceof int[]) {
                    pix = ((int[])pixels)[srcOff + srcX];
                } else {
                    pix = ((byte[])pixels)[srcOff + srcX] & 0xff;
                }

                rgb = model.getRGB(pix);
                int a = rgb >>> 24;
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;

                // accumulating pixel's component values
                sum_a[dx] += a * avg;
                sum_r[dx] += r * avg;
                sum_g[dx] += g * avg;
                sum_b[dx] += b * avg;

                shRest -= hDif;
                dhRest -= hDif;

                if (shRest == 0) {
                    srcX++;
                    shRest = destWidth;
                }

                if (dhRest == 0) {
                    dx++;
                    dhRest = srcWidth;
                }
            }

            svRest -= vDif;
            dvRest -= vDif;

            if (svRest == 0) {
                svRest = destHeight;
                srcY++;
                srcOff += scansize;
            }

            if (dvRest == 0) {
                // averaging destination pixel's values
                for (int i = 0; i < destWidth; i++) {
                    int a = (sum_a[i] / avgFactor) & 0xff;
                    int r = (sum_r[i] / avgFactor) & 0xff;
                    int g = (sum_g[i] / avgFactor) & 0xff;
                    int b = (sum_b[i] / avgFactor) & 0xff;
                    int frgb = (a << 24) | (r << 16) | (g << 8) | b;
                    buff[i] = frgb;
                }
                consumer.setPixels(0, dy, destWidth, 1, rgbCM, buff, 0, destWidth);
                dy++;
                dvRest = srcHeight;
                Arrays.fill(sum_a, 0);
                Arrays.fill(sum_r, 0);
                Arrays.fill(sum_g, 0);
                Arrays.fill(sum_b, 0);
            }

        }

        cachedDY = dy;
        cachedDVRest = dvRest;

    }

    /**
     * Initialization of the auxiliary data.
     */
    private void initialize() {

        sum_a = new int[destWidth];
        sum_r = new int[destWidth];
        sum_g = new int[destWidth];
        sum_b = new int[destWidth];

        buff = new int[destWidth];
        outpixbuf = buff;
        avgFactor = srcWidth * srcHeight;

        inited = true;
    }
}
