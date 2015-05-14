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

import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;

/**
 * Delegate implementing the native methods of android.graphics.BitmapShader
 *
 * Through the layoutlib_create tool, the original native methods of BitmapShader have been
 * replaced by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original BitmapShader class.
 *
 * Because this extends {@link Shader_Delegate}, there's no need to use a {@link DelegateManager},
 * as all the Shader classes will be added to the manager owned by {@link Shader_Delegate}.
 *
 * @see Shader_Delegate
 *
 */
public class BitmapShader_Delegate extends Shader_Delegate {

    // ---- delegate data ----
    private java.awt.Paint mJavaPaint;

    // ---- Public Helper methods ----

    @Override
    public java.awt.Paint getJavaPaint() {
        return mJavaPaint;
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public String getSupportMessage() {
        // no message since isSupported returns true;
        return null;
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static long nativeCreate(Bitmap androidBitmap, int shaderTileModeX,
            int shaderTileModeY) {
        Bitmap_Delegate bitmap = Bitmap_Delegate.getDelegate(androidBitmap);
        if (bitmap == null) {
            return 0;
        }

        BitmapShader_Delegate newDelegate = new BitmapShader_Delegate(
                bitmap.getImage(),
                Shader_Delegate.getTileMode(shaderTileModeX),
                Shader_Delegate.getTileMode(shaderTileModeY));
        return sManager.addNewDelegate(newDelegate);
    }

    // ---- Private delegate/helper methods ----

    private BitmapShader_Delegate(BufferedImage image,
            TileMode tileModeX, TileMode tileModeY) {
        mJavaPaint = new BitmapShaderPaint(image, tileModeX, tileModeY);
    }

    private class BitmapShaderPaint implements java.awt.Paint {
        private final BufferedImage mImage;
        private final TileMode mTileModeX;
        private final TileMode mTileModeY;

        BitmapShaderPaint(BufferedImage image,
                TileMode tileModeX, TileMode tileModeY) {
            mImage = image;
            mTileModeX = tileModeX;
            mTileModeY = tileModeY;
        }

        @Override
        public PaintContext createContext(ColorModel colorModel, Rectangle deviceBounds,
                Rectangle2D userBounds, AffineTransform xform, RenderingHints hints) {
            AffineTransform canvasMatrix;
            try {
                canvasMatrix = xform.createInverse();
            } catch (NoninvertibleTransformException e) {
                Bridge.getLog().fidelityWarning(LayoutLog.TAG_MATRIX_INVERSE,
                        "Unable to inverse matrix in BitmapShader", e, null /*data*/);
                canvasMatrix = new AffineTransform();
            }

            AffineTransform localMatrix = getLocalMatrix();
            try {
                localMatrix = localMatrix.createInverse();
            } catch (NoninvertibleTransformException e) {
                Bridge.getLog().fidelityWarning(LayoutLog.TAG_MATRIX_INVERSE,
                        "Unable to inverse matrix in BitmapShader", e, null /*data*/);
                localMatrix = new AffineTransform();
            }

            if (!colorModel.isCompatibleRaster(mImage.getRaster())) {
                // Fallback to the default ARGB color model
                colorModel = ColorModel.getRGBdefault();
            }

            return new BitmapShaderContext(canvasMatrix, localMatrix, colorModel);
        }

        private class BitmapShaderContext implements PaintContext {

            private final AffineTransform mCanvasMatrix;
            private final AffineTransform mLocalMatrix;
            private final ColorModel mColorModel;

            public BitmapShaderContext(
                    AffineTransform canvasMatrix,
                    AffineTransform localMatrix,
                    ColorModel colorModel) {
                mCanvasMatrix = canvasMatrix;
                mLocalMatrix = localMatrix;
                mColorModel = colorModel;
            }

            @Override
            public void dispose() {
            }

            @Override
            public ColorModel getColorModel() {
                return mColorModel;
            }

            @Override
            public Raster getRaster(int x, int y, int w, int h) {
                BufferedImage image = new BufferedImage(
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
        private int getColor(float fx, float fy) {
            int x = getCoordinate(Math.round(fx), mImage.getWidth(), mTileModeX);
            int y = getCoordinate(Math.round(fy), mImage.getHeight(), mTileModeY);

            return mImage.getRGB(x, y);
        }

        private int getCoordinate(int i, int size, TileMode mode) {
            if (i < 0) {
                switch (mode) {
                    case CLAMP:
                        i = 0;
                        break;
                    case REPEAT:
                        i = size - 1 - (-i % size);
                        break;
                    case MIRROR:
                        // this is the same as the positive side, just make the value positive
                        // first.
                        i = -i;
                        int count = i / size;
                        i = i % size;

                        if ((count % 2) == 1) {
                            i = size - 1 - i;
                        }
                        break;
                }
            } else if (i >= size) {
                switch (mode) {
                    case CLAMP:
                        i = size - 1;
                        break;
                    case REPEAT:
                        i = i % size;
                        break;
                    case MIRROR:
                        int count = i / size;
                        i = i % size;

                        if ((count % 2) == 1) {
                            i = size - 1 - i;
                        }
                        break;
                }
            }

            return i;
        }


        @Override
        public int getTransparency() {
            return java.awt.Paint.TRANSLUCENT;
        }
    }
}
