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

public class SweepGradient extends GradientShader {

    private SweepGradientPaint mPaint;

    /**
     * A subclass of Shader that draws a sweep gradient around a center point.
     *
     * @param cx       The x-coordinate of the center
     * @param cy       The y-coordinate of the center
     * @param colors   The colors to be distributed between around the center.
     *                 There must be at least 2 colors in the array.
     * @param positions May be NULL. The relative position of
     *                 each corresponding color in the colors array, beginning
     *                 with 0 and ending with 1.0. If the values are not
     *                 monotonic, the drawing may produce unexpected results.
     *                 If positions is NULL, then the colors are automatically
     *                 spaced evenly.
     */
    public SweepGradient(float cx, float cy,
                         int colors[], float positions[]) {
        super(colors, positions);

        mPaint = new SweepGradientPaint(cx, cy, mColors, mPositions);
    }

    /**
     * A subclass of Shader that draws a sweep gradient around a center point.
     *
     * @param cx       The x-coordinate of the center
     * @param cy       The y-coordinate of the center
     * @param color0   The color to use at the start of the sweep
     * @param color1   The color to use at the end of the sweep
     */
    public SweepGradient(float cx, float cy, int color0, int color1) {
        this(cx, cy, new int[] { color0, color1}, null /*positions*/);
    }

    @Override
    java.awt.Paint getJavaPaint() {
        return mPaint;
    }

    private static class SweepGradientPaint extends GradientPaint {

        private final float mCx;
        private final float mCy;

        public SweepGradientPaint(float cx, float cy, int[] colors, float[] positions) {
            super(colors, positions, null /*tileMode*/);
            mCx = cx;
            mCy = cy;
        }

        public java.awt.PaintContext createContext(
                java.awt.image.ColorModel     colorModel,
                java.awt.Rectangle            deviceBounds,
                java.awt.geom.Rectangle2D     userBounds,
                java.awt.geom.AffineTransform xform,
                java.awt.RenderingHints       hints) {
            precomputeGradientColors();
            return new SweepGradientPaintContext(colorModel);
        }

        private class SweepGradientPaintContext implements java.awt.PaintContext {

            private final java.awt.image.ColorModel mColorModel;

            public SweepGradientPaintContext(java.awt.image.ColorModel colorModel) {
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

                // compute angle from each point to the center, and figure out the distance from
                // it.
                int index = 0;
                for (int iy = 0 ; iy < h ; iy++) {
                    for (int ix = 0 ; ix < w ; ix++) {
                        float dx = x + ix - mCx;
                        float dy = y + iy - mCy;
                        float angle;
                        if (dx == 0) {
                            angle = (float) (dy < 0 ? 3 * Math.PI / 2 : Math.PI / 2);
                        } else if (dy == 0) {
                            angle = (float) (dx < 0 ? Math.PI : 0);
                        } else {
                            angle = (float) Math.atan(dy / dx);
                            if (dx > 0) {
                                if (dy < 0) {
                                    angle += Math.PI * 2;
                                }
                            } else {
                                angle += Math.PI;
                            }
                        }

                        // convert to 0-1. value and get color
                        data[index++] = getGradientColor((float) (angle / (2 * Math.PI)));
                    }
                }

                image.setRGB(0 /*startX*/, 0 /*startY*/, w, h, data, 0 /*offset*/, w /*scansize*/);

                return image.getRaster();
            }

        }
    }

}

