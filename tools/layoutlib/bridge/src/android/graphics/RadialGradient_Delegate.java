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
 * Delegate implementing the native methods of android.graphics.RadialGradient
 *
 * Through the layoutlib_create tool, the original native methods of RadialGradient have been
 * replaced by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original RadialGradient class.
 *
 * Because this extends {@link Shader_Delegate}, there's no need to use a {@link DelegateManager},
 * as all the Shader classes will be added to the manager owned by {@link Shader_Delegate}.
 *
 * @see Shader_Delegate
 *
 */
public class RadialGradient_Delegate extends Gradient_Delegate {

    // ---- delegate data ----
    private java.awt.Paint mJavaPaint;

    // ---- Public Helper methods ----

    @Override
    public java.awt.Paint getJavaPaint() {
        return mJavaPaint;
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static long nativeCreate1(float x, float y, float radius,
            int colors[], float positions[], int tileMode) {
        RadialGradient_Delegate newDelegate = new RadialGradient_Delegate(x, y, radius,
                colors, positions, Shader_Delegate.getTileMode(tileMode));
        return sManager.addNewDelegate(newDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static long nativeCreate2(float x, float y, float radius,
            int color0, int color1, int tileMode) {
        return nativeCreate1(x, y, radius, new int[] { color0, color1 }, null /*positions*/,
                tileMode);
    }

    // ---- Private delegate/helper methods ----

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
    private RadialGradient_Delegate(float x, float y, float radius, int colors[], float positions[],
            TileMode tile) {
        super(colors, positions);
        mJavaPaint = new RadialGradientPaint(x, y, radius, mColors, mPositions, tile);
    }

    private class RadialGradientPaint extends GradientPaint {

        private final float mX;
        private final float mY;
        private final float mRadius;

        public RadialGradientPaint(float x, float y, float radius,
                int[] colors, float[] positions, TileMode mode) {
            super(colors, positions, mode);
            mX = x;
            mY = y;
            mRadius = radius;
        }

        @Override
        public java.awt.PaintContext createContext(
                java.awt.image.ColorModel     colorModel,
                java.awt.Rectangle            deviceBounds,
                java.awt.geom.Rectangle2D     userBounds,
                java.awt.geom.AffineTransform xform,
                java.awt.RenderingHints       hints) {
            precomputeGradientColors();

            java.awt.geom.AffineTransform canvasMatrix;
            try {
                canvasMatrix = xform.createInverse();
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                Bridge.getLog().fidelityWarning(LayoutLog.TAG_MATRIX_INVERSE,
                        "Unable to inverse matrix in RadialGradient", e, null /*data*/);
                canvasMatrix = new java.awt.geom.AffineTransform();
            }

            java.awt.geom.AffineTransform localMatrix = getLocalMatrix();
            try {
                localMatrix = localMatrix.createInverse();
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                Bridge.getLog().fidelityWarning(LayoutLog.TAG_MATRIX_INVERSE,
                        "Unable to inverse matrix in RadialGradient", e, null /*data*/);
                localMatrix = new java.awt.geom.AffineTransform();
            }

            return new RadialGradientPaintContext(canvasMatrix, localMatrix, colorModel);
        }

        private class RadialGradientPaintContext implements java.awt.PaintContext {

            private final java.awt.geom.AffineTransform mCanvasMatrix;
            private final java.awt.geom.AffineTransform mLocalMatrix;
            private final java.awt.image.ColorModel mColorModel;

            public RadialGradientPaintContext(
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

                // compute distance from each point to the center, and figure out the distance from
                // it.
                int index = 0;
                float[] pt1 = new float[2];
                float[] pt2 = new float[2];
                for (int iy = 0 ; iy < h ; iy++) {
                    for (int ix = 0 ; ix < w ; ix++) {
                        // handle the canvas transform
                        pt1[0] = x + ix;
                        pt1[1] = y + iy;
                        mCanvasMatrix.transform(pt1, 0, pt2, 0, 1);

                        // handle the local matrix
                        pt1[0] = pt2[0] - mX;
                        pt1[1] = pt2[1] - mY;
                        mLocalMatrix.transform(pt1, 0, pt2, 0, 1);

                        float _x = pt2[0];
                        float _y = pt2[1];
                        float distance = (float) Math.hypot(_x, _y);

                        data[index++] = getGradientColor(distance / mRadius);
                    }
                }

                image.setRGB(0 /*startX*/, 0 /*startY*/, w, h, data, 0 /*offset*/, w /*scansize*/);

                return image.getRaster();
            }

        }
    }

}
