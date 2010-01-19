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

public class LinearGradient extends GradientShader {

    private java.awt.Paint mJavaPaint;

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
        super(colors, positions);
        mJavaPaint = new LinearGradientPaint(x0, y0, x1, y1, mColors, mPositions, tile);
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
    java.awt.Paint getJavaPaint() {
        return mJavaPaint;
    }

    /**
     * Linear Gradient (Java) Paint able to handle more than 2 points, as
     * {@link java.awt.GradientPaint} only supports 2 points and does not support Android's tile
     * modes.
     */
    private static class LinearGradientPaint extends GradientPaint {

        private final float mX0;
        private final float mY0;
        private final float mDx;
        private final float mDy;
        private final float mDSize2;

        public LinearGradientPaint(float x0, float y0, float x1, float y1, int colors[],
                float positions[], TileMode tile) {
            super(colors, positions, tile);
                mX0 = x0;
                mY0 = y0;
                mDx = x1 - x0;
                mDy = y1 - y0;
                mDSize2 = mDx * mDx + mDy * mDy;
        }

        public java.awt.PaintContext createContext(
                java.awt.image.ColorModel      colorModel,
                java.awt.Rectangle             deviceBounds,
                java.awt.geom.Rectangle2D      userBounds,
                java.awt.geom.AffineTransform  xform,
                java.awt.RenderingHints        hints) {
            precomputeGradientColors();
            return new LinearGradientPaintContext(colorModel);
        }

        private class LinearGradientPaintContext implements java.awt.PaintContext {

            private final java.awt.image.ColorModel mColorModel;

            public LinearGradientPaintContext(java.awt.image.ColorModel colorModel) {
                mColorModel = colorModel;
                // FIXME: so far all this is always the same rect gotten in getRaster with an indentity matrix?
            }

            public void dispose() {
            }

            public java.awt.image.ColorModel getColorModel() {
                return mColorModel;
            }

            public java.awt.image.Raster getRaster(int x, int y, int w, int h) {
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(w, h,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);

                int[] data = new int[w*h];

                if (mDx == 0) { // vertical gradient
                    // compute first column and copy to all other columns
                    int index = 0;
                    for (int iy = 0 ; iy < h ; iy++) {
                        int color = getColor(iy + y, mY0, mDy);
                        for (int ix = 0 ; ix < w ; ix++) {
                            data[index++] = color;
                        }
                    }
                } else if (mDy == 0) { // horizontal
                    // compute first line in a tmp array and copy to all lines
                    int[] line = new int[w];
                    for (int ix = 0 ; ix < w ; ix++) {
                        line[ix] = getColor(ix + x, mX0, mDx);
                    }

                    for (int iy = 0 ; iy < h ; iy++) {
                        System.arraycopy(line, 0, data, iy*w, line.length);
                    }
                } else {
                    int index = 0;
                    for (int iy = 0 ; iy < h ; iy++) {
                        for (int ix = 0 ; ix < w ; ix++) {
                            data[index++] = getColor(ix + x, iy + y);
                        }
                    }
                }

                image.setRGB(0 /*startX*/, 0 /*startY*/, w, h, data, 0 /*offset*/, w /*scansize*/);

                return image.getRaster();
            }
        }

        /** Returns a color for the easy vertical/horizontal mode */
        private int getColor(float absPos, float refPos, float refSize) {
            float pos = (absPos - refPos) / refSize;

            return getGradientColor(pos);
        }

        /**
         * Returns a color for an arbitrary point.
         */
        private int getColor(float x, float y) {
            // find the x position on the gradient vector.
            float _x = (mDx*mDy*(y-mY0) + mDy*mDy*mX0 + mDx*mDx*x) / mDSize2;
            // from it get the position relative to the vector
            float pos = (float) ((_x - mX0) / mDx);

            return getGradientColor(pos);
        }
    }
}
