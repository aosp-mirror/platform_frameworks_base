/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.graphics;

import java.awt.Paint;
import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;

public class LinearGradient extends Shader {

    private Paint mJavaPaint;

    /**
     * Create a shader that draws a linear gradient along a line.
     *
     * @param x0 The x-coordinate for the start of the gradient line
     * @param y0 The y-coordinate for the start of the gradient line
     * @param x1 The x-coordinate for the end of the gradient line
     * @param y1 The y-coordinate for the end of the gradient line
     * @param colors The colors to be distributed along the gradient line
     * @param positions May be null. The relative positions [0..1] of each
     *            corresponding color in the colors array. If this is null, the
     *            the colors are distributed evenly along the gradient line.
     * @param tile The Shader tiling mode
     */
    public LinearGradient(float x0, float y0, float x1, float y1, int colors[], float positions[],
            TileMode tile) {
        if (colors.length < 2) {
            throw new IllegalArgumentException("needs >= 2 number of colors");
        }
        if (positions != null && colors.length != positions.length) {
            throw new IllegalArgumentException("color and position arrays must be of equal length");
        }

        if (positions == null) {
            float spacing = 1.f / (colors.length - 1);
            positions = new float[colors.length];
            positions[0] = 0.f;
            positions[colors.length-1] = 1.f;
            for (int i = 1; i < colors.length - 1 ; i++) {
                positions[i] = spacing * i;
            }
        }

        mJavaPaint = new MultiPointLinearGradientPaint(x0, y0, x1, y1, colors, positions, tile);
    }

    /**
     * Create a shader that draws a linear gradient along a line.
     *
     * @param x0 The x-coordinate for the start of the gradient line
     * @param y0 The y-coordinate for the start of the gradient line
     * @param x1 The x-coordinate for the end of the gradient line
     * @param y1 The y-coordinate for the end of the gradient line
     * @param color0 The color at the start of the gradient line.
     * @param color1 The color at the end of the gradient line.
     * @param tile The Shader tiling mode
     */
    public LinearGradient(float x0, float y0, float x1, float y1, int color0, int color1,
            TileMode tile) {
        this(x0, y0, x1, y1, new int[] { color0, color1}, null /*positions*/, tile);
    }

    // ---------- Custom Methods

    @Override
    public Paint getJavaPaint() {
        return mJavaPaint;
    }

    private static class MultiPointLinearGradientPaint implements Paint {
        private final static int GRADIENT_SIZE = 100;

        private final float mX0;
        private final float mY0;
        private final float mDx;
        private final float mDy;
        private final float mDSize2;
        private final int[] mColors;
        private final float[] mPositions;
        private final TileMode mTile;
        private int[] mGradient;

        public MultiPointLinearGradientPaint(float x0, float y0, float x1, float y1, int colors[],
                float positions[], TileMode tile) {
                mX0 = x0;
                mY0 = y0;
                mDx = x1 - x0;
                mDy = y1 - y0;
                mDSize2 = mDx * mDx + mDy * mDy;

                mColors = colors;
                mPositions = positions;
                mTile = tile;
        }

        public PaintContext createContext(ColorModel cm, Rectangle deviceBounds,
                Rectangle2D userBounds, AffineTransform xform, RenderingHints hints) {
            prepareColors();
            return new MultiPointLinearGradientPaintContext(cm, deviceBounds,
                    userBounds, xform, hints);
        }

        public int getTransparency() {
            return TRANSLUCENT;
        }

        private synchronized void prepareColors() {
            if (mGradient == null) {
                // actually create an array with an extra size, so that we can really go
                // from 0 to SIZE (100%), or currentPos in the loop below will never equal 1.0
                mGradient = new int[GRADIENT_SIZE+1];

                int prevPos = 0;
                int nextPos = 1;
                for (int i  = 0 ; i <= GRADIENT_SIZE ; i++) {
                    // compute current position
                    float currentPos = (float)i/GRADIENT_SIZE;
                    while (currentPos > mPositions[nextPos]) {
                        prevPos = nextPos++;
                    }

                    float percent = (currentPos - mPositions[prevPos]) /
                            (mPositions[nextPos] - mPositions[prevPos]);

                    mGradient[i] = getColor(mColors[prevPos], mColors[nextPos], percent);
                }
            }
        }

        /**
         * Returns the color between c1, and c2, based on the percent of the distance
         * between c1 and c2.
         */
        private int getColor(int c1, int c2, float percent) {
            int a = getChannel((c1 >> 24) & 0xFF, (c2 >> 24) & 0xFF, percent);
            int r = getChannel((c1 >> 16) & 0xFF, (c2 >> 16) & 0xFF, percent);
            int g = getChannel((c1 >>  8) & 0xFF, (c2 >>  8) & 0xFF, percent);
            int b = getChannel((c1      ) & 0xFF, (c2      ) & 0xFF, percent);
            return a << 24 | r << 16 | g << 8 | b;
        }

        /**
         * Returns the channel value between 2 values based on the percent of the distance between
         * the 2 values..
         */
        private int getChannel(int c1, int c2, float percent) {
            return c1 + (int)((percent * (c2-c1)) + .5);
        }

        private class MultiPointLinearGradientPaintContext implements PaintContext {

            private ColorModel mColorModel;
            private final Rectangle mDeviceBounds;
            private final Rectangle2D mUserBounds;
            private final AffineTransform mXform;
            private final RenderingHints mHints;

            public MultiPointLinearGradientPaintContext(ColorModel cm, Rectangle deviceBounds,
                    Rectangle2D userBounds, AffineTransform xform, RenderingHints hints) {
                mColorModel = cm;
                // FIXME: so far all this is always the same rect gotten in getRaster with an indentity matrix?
                mDeviceBounds = deviceBounds;
                mUserBounds = userBounds;
                mXform = xform;
                mHints = hints;
            }

            public void dispose() {
            }

            public ColorModel getColorModel() {
                return mColorModel;
            }

            public Raster getRaster(int x, int y, int w, int h) {
                BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

                if (mDx == 0) { // vertical gradient
                    // compute first column and copy to all other columns
                    for (int iy = 0 ; iy < h ; iy++) {
                        int color = getColor(iy + y, mY0, mDy);
                        for (int ix = 0 ; ix < w ; ix++) {
                            image.setRGB(ix, iy, color);
                        }
                    }
                } else if (mDy == 0) { // horizontal
                    // compute first line in a tmp array and copy to all lines
                    int[] line = new int[w];
                    for (int ix = 0 ; ix < w ; ix++) {
                        line[ix] = getColor(ix + x, mX0, mDx);
                    }

                    for (int iy = 0 ; iy < h ; iy++) {
                        image.setRGB(0, iy, w, 1 /*h*/, line, 0 /* offset*/, w /*scansize*/);
                    }
                } else {
                    for (int iy = 0 ; iy < h ; iy++) {
                        for (int ix = 0 ; ix < w ; ix++) {
                            image.setRGB(ix, iy, getColor(ix + x, iy + y));
                        }
                    }
                }

                return image.getRaster();
            }
        }

        /** Returns a color for the easy vertical/horizontal mode */
        private int getColor(float absPos, float refPos, float refSize) {
            float pos = (absPos - refPos) / refSize;

            return getIndexFromPos(pos);
        }

        /**
         * Returns a color for an arbitrary point.
         */
        private int getColor(float x, float y) {
            // find the x position on the gradient vector.
            float _x = (mDx*mDy*(y-mY0) + mDy*mDy*mX0 + mDx*mDx*x) / mDSize2;
            // from it get the position relative to the vector
            float pos = (float) ((_x - mX0) / mDx);

            return getIndexFromPos(pos);
        }

        /**
         * Returns the color based on the position in the gradient.
         * <var>pos</var> can be anything, even &lt; 0 or &gt; > 1, as the gradient
         * will use {@link TileMode} value to convert it into a [0,1] value.
         */
        private int getIndexFromPos(float pos) {
            if (pos < 0.f) {
                switch (mTile) {
                    case CLAMP:
                        pos = 0.f;
                        break;
                    case REPEAT:
                        // remove the integer part to stay in the [0,1] range
                        // careful: this is a negative value, so use ceil instead of floor
                        pos = pos - (float)Math.ceil(pos);
                        break;
                    case MIRROR:
                        // get the integer and the decimal part
                        // careful: this is a negative value, so use ceil instead of floor
                        int intPart = (int)Math.ceil(pos);
                        pos = pos - intPart;
                        // 0  -> -1 : mirrored order
                        // -1 -> -2: normal order
                        // etc..
                        // this means if the intpart is even we invert
                        if ((intPart % 2) == 0) {
                            pos = 1.f - pos;
                        }
                        break;
                }
            } else if (pos > 1f) {
                switch (mTile) {
                    case CLAMP:
                        pos = 1.f;
                        break;
                    case REPEAT:
                        // remove the integer part to stay in the [0,1] range
                        pos = pos - (float)Math.floor(pos);
                        break;
                    case MIRROR:
                        // get the integer and the decimal part
                        int intPart = (int)Math.floor(pos);
                        pos = pos - intPart;
                        // 0 -> 1 : normal order
                        // 1 -> 2: mirrored
                        // etc..
                        // this means if the intpart is odd we invert
                        if ((intPart % 2) == 1) {
                            pos = 1.f - pos;
                        }
                        break;
                }
            }

            int index = (int)((pos * GRADIENT_SIZE) + .5);

            return mGradient[index];
        }
    }
}
