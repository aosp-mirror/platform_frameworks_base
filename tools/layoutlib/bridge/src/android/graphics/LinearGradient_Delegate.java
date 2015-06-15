/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.graphics.Shader.TileMode;

import java.awt.image.ColorModel;

/**
 * Delegate implementing the native methods of android.graphics.LinearGradient
 *
 * Through the layoutlib_create tool, the original native methods of LinearGradient have been
 * replaced by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original LinearGradient class.
 *
 * Because this extends {@link Shader_Delegate}, there's no need to use a {@link DelegateManager},
 * as all the Shader classes will be added to the manager owned by {@link Shader_Delegate}.
 *
 * @see Shader_Delegate
 *
 */
public final class LinearGradient_Delegate extends Gradient_Delegate {

    // ---- delegate data ----
    private java.awt.Paint mJavaPaint;

    // ---- Public Helper methods ----

    @Override
    public java.awt.Paint getJavaPaint() {
        return mJavaPaint;
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static long nativeCreate1(LinearGradient thisGradient,
            float x0, float y0, float x1, float y1,
            int colors[], float positions[], int tileMode) {
        LinearGradient_Delegate newDelegate = new LinearGradient_Delegate(x0, y0, x1, y1,
                colors, positions, Shader_Delegate.getTileMode(tileMode));
        return sManager.addNewDelegate(newDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static long nativeCreate2(LinearGradient thisGradient,
            float x0, float y0, float x1, float y1,
            int color0, int color1, int tileMode) {
        return nativeCreate1(thisGradient,
                x0, y0, x1, y1, new int[] { color0, color1}, null /*positions*/,
                tileMode);
    }

    // ---- Private delegate/helper methods ----

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
    private LinearGradient_Delegate(float x0, float y0, float x1, float y1,
            int colors[], float positions[], TileMode tile) {
        super(colors, positions);
        mJavaPaint = new LinearGradientPaint(x0, y0, x1, y1, mColors, mPositions, tile);
    }

    // ---- Custom Java Paint ----
    /**
     * Linear Gradient (Java) Paint able to handle more than 2 points, as
     * {@link java.awt.GradientPaint} only supports 2 points and does not support Android's tile
     * modes.
     */
    private class LinearGradientPaint extends GradientPaint {

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

        @Override
        public java.awt.PaintContext createContext(
                java.awt.image.ColorModel      colorModel,
                java.awt.Rectangle             deviceBounds,
                java.awt.geom.Rectangle2D      userBounds,
                java.awt.geom.AffineTransform  xform,
                java.awt.RenderingHints        hints) {
            precomputeGradientColors();

            java.awt.geom.AffineTransform canvasMatrix;
            try {
                canvasMatrix = xform.createInverse();
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                Bridge.getLog().fidelityWarning(LayoutLog.TAG_MATRIX_INVERSE,
                        "Unable to inverse matrix in LinearGradient", e, null /*data*/);
                canvasMatrix = new java.awt.geom.AffineTransform();
            }

            java.awt.geom.AffineTransform localMatrix = getLocalMatrix();
            try {
                localMatrix = localMatrix.createInverse();
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                Bridge.getLog().fidelityWarning(LayoutLog.TAG_MATRIX_INVERSE,
                        "Unable to inverse matrix in LinearGradient", e, null /*data*/);
                localMatrix = new java.awt.geom.AffineTransform();
            }

            return new LinearGradientPaintContext(canvasMatrix, localMatrix, colorModel);
        }

        private class LinearGradientPaintContext implements java.awt.PaintContext {

            private final java.awt.geom.AffineTransform mCanvasMatrix;
            private final java.awt.geom.AffineTransform mLocalMatrix;
            private final java.awt.image.ColorModel mColorModel;

            private LinearGradientPaintContext(
                    java.awt.geom.AffineTransform canvasMatrix,
                    java.awt.geom.AffineTransform localMatrix,
                    java.awt.image.ColorModel colorModel) {
                mCanvasMatrix = canvasMatrix;
                mLocalMatrix = localMatrix;
                mColorModel = colorModel.hasAlpha() ? colorModel : ColorModel.getRGBdefault();
            }

            @Override
            public void dispose() {
            }

            @Override
            public java.awt.image.ColorModel getColorModel() {
                return mColorModel;
            }

            @Override
            public java.awt.image.Raster getRaster(int x, int y, int w, int h) {
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                    mColorModel, mColorModel.createCompatibleWritableRaster(w, h),
                    mColorModel.isAlphaPremultiplied(), null);

                int[] data = new int[w*h];

                int index = 0;
                float[] pt1 = new float[2];
                float[] pt2 = new float[2];
                for (int iy = 0 ; iy < h ; iy++) {
                    for (int ix = 0 ; ix < w ; ix++) {
                        // handle the canvas transform
                        pt1[0] = x + ix;
                        pt1[1] = y + iy;
                        mCanvasMatrix.transform(pt1, 0, pt2, 0, 1);

                        // handle the local matrix.
                        pt1[0] = pt2[0];
                        pt1[1] = pt2[1];
                        mLocalMatrix.transform(pt1, 0, pt2, 0, 1);

                        data[index++] = getColor(pt2[0], pt2[1]);
                    }
                }

                image.setRGB(0 /*startX*/, 0 /*startY*/, w, h, data, 0 /*offset*/, w /*scansize*/);

                return image.getRaster();
            }
        }

        /**
         * Returns a color for an arbitrary point.
         */
        private int getColor(float x, float y) {
            float pos;
            if (mDx == 0) {
                pos = (y - mY0) / mDy;
            } else if (mDy == 0) {
                pos = (x - mX0) / mDx;
            } else {
                // find the x position on the gradient vector.
                float _x = (mDx*mDy*(y-mY0) + mDy*mDy*mX0 + mDx*mDx*x) / mDSize2;
                // from it get the position relative to the vector
                pos = (_x - mX0) / mDx;
            }

            return getGradientColor(pos);
        }
    }
}
