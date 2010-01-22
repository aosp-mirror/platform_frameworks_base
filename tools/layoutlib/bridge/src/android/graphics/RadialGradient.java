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

public class RadialGradient extends GradientShader {

    private RadialGradientPaint mPaint;

    /**
     * Create a shader that draws a radial gradient given the center and radius.
     *
     * @param x The x-coordinate of the center of the radius
     * @param y The y-coordinate of the center of the radius
     * @param radius Must be positive. The radius of the circle for this
     *            gradient
     * @param colors The colors to be distributed between the center and edge of
     *            the circle
     * @param positions May be NULL. The relative position of each corresponding
     *            color in the colors array. If this is NULL, the the colors are
     *            distributed evenly between the center and edge of the circle.
     * @param tile The Shader tiling mode
     */
    public RadialGradient(float x, float y, float radius, int colors[], float positions[],
            TileMode tile) {
        super(colors, positions);
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be > 0");
        }

        mPaint = new RadialGradientPaint(x, y, radius, mColors, mPositions, tile);
    }

    /**
     * Create a shader that draws a radial gradient given the center and radius.
     *
     * @param x The x-coordinate of the center of the radius
     * @param y The y-coordinate of the center of the radius
     * @param radius Must be positive. The radius of the circle for this
     *            gradient
     * @param color0 The color at the center of the circle.
     * @param color1 The color at the edge of the circle.
     * @param tile The Shader tiling mode
     */
    public RadialGradient(float x, float y, float radius, int color0, int color1, TileMode tile) {
        this(x, y, radius, new int[] { color0, color1 }, null /* positions */, tile);
    }

    @Override
    java.awt.Paint getJavaPaint() {
        return mPaint;
    }

    private static class RadialGradientPaint extends GradientPaint {

        private final float mX;
        private final float mY;
        private final float mRadius;

        public RadialGradientPaint(float x, float y, float radius, int[] colors, float[] positions, TileMode mode) {
            super(colors, positions, mode);
            mX = x;
            mY = y;
            mRadius = radius;
        }

        public java.awt.PaintContext createContext(
                java.awt.image.ColorModel     colorModel,
                java.awt.Rectangle            deviceBounds,
                java.awt.geom.Rectangle2D     userBounds,
                java.awt.geom.AffineTransform xform,
                java.awt.RenderingHints       hints) {
            precomputeGradientColors();
            return new RadialGradientPaintContext(colorModel);
        }

        private class RadialGradientPaintContext implements java.awt.PaintContext {

            private final java.awt.image.ColorModel mColorModel;

            public RadialGradientPaintContext(java.awt.image.ColorModel colorModel) {
                mColorModel = colorModel;
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

                // compute distance from each point to the center, and figure out the distance from
                // it.
                int index = 0;
                for (int iy = 0 ; iy < h ; iy++) {
                    for (int ix = 0 ; ix < w ; ix++) {
                        float _x = x + ix - mX;
                        float _y = y + iy - mY;
                        float distance = (float) Math.sqrt(_x * _x + _y * _y);

                        data[index++] = getGradientColor(distance / mRadius);
                    }
                }

                image.setRGB(0 /*startX*/, 0 /*startY*/, w, h, data, 0 /*offset*/, w /*scansize*/);

                return image.getRaster();
            }

        }
    }

}
