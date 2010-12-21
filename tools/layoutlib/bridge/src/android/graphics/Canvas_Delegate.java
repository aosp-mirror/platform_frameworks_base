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

import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.layoutlib.bridge.impl.GcSnapshot;

import android.graphics.Paint_Delegate.FontInfo;
import android.text.TextUtils;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;


/**
 * Delegate implementing the native methods of android.graphics.Canvas
 *
 * Through the layoutlib_create tool, the original native methods of Canvas have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original Canvas class.
 *
 * @see DelegateManager
 *
 */
public final class Canvas_Delegate {

    // ---- delegate manager ----
    private static final DelegateManager<Canvas_Delegate> sManager =
            new DelegateManager<Canvas_Delegate>();

    // ---- delegate helper data ----

    private interface Drawable {
        void draw(Graphics2D graphics, Paint_Delegate paint);
    }

    // ---- delegate data ----
    private BufferedImage mBufferedImage;
    private GcSnapshot mSnapshot = new GcSnapshot();

    // ---- Public Helper methods ----

    /**
     * Returns the native delegate associated to a given {@link Canvas} object.
     */
    public static Canvas_Delegate getDelegate(Canvas canvas) {
        return sManager.getDelegate(canvas.mNativeCanvas);
    }

    /**
     * Returns the native delegate associated to a given an int referencing a {@link Canvas} object.
     */
    public static Canvas_Delegate getDelegate(int native_canvas) {
        return sManager.getDelegate(native_canvas);
    }

    /**
     * Returns the current {@link Graphics2D} used to draw.
     */
    public GcSnapshot getGcSnapshot() {
        return mSnapshot;
    }

    // ---- native methods ----

    /*package*/ static boolean isOpaque(Canvas thisCanvas) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int getWidth(Canvas thisCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return 0;
        }

        return canvasDelegate.mBufferedImage.getWidth();
    }

    /*package*/ static int getHeight(Canvas thisCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return 0;
        }

        return canvasDelegate.mBufferedImage.getHeight();
    }

    /*package*/ static void translate(Canvas thisCanvas, float dx, float dy) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        canvasDelegate.getGcSnapshot().translate(dx, dy);
    }

    /*package*/ static void rotate(Canvas thisCanvas, float degrees) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        canvasDelegate.getGcSnapshot().rotate(Math.toRadians(degrees));
    }

    /*package*/ static void scale(Canvas thisCanvas, float sx, float sy) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        canvasDelegate.getGcSnapshot().scale(sx, sy);
    }

    /*package*/ static void skew(Canvas thisCanvas, float kx, float ky) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        // get the current top graphics2D object.
        GcSnapshot g = canvasDelegate.getGcSnapshot();

        // get its current matrix
        AffineTransform currentTx = g.getTransform();
        // get the AffineTransform for the given skew.
        float[] mtx = Matrix_Delegate.getSkew(kx, ky);
        AffineTransform matrixTx = Matrix_Delegate.getAffineTransform(mtx);

        // combine them so that the given matrix is applied after.
        currentTx.preConcatenate(matrixTx);

        // give it to the graphics2D as a new matrix replacing all previous transform
        g.setTransform(currentTx);
    }

    /*package*/ static boolean clipRect(Canvas thisCanvas, RectF rect) {
        return clipRect(thisCanvas, rect.left, rect.top, rect.right, rect.bottom);
    }

    /*package*/ static boolean clipRect(Canvas thisCanvas, Rect rect) {
        return clipRect(thisCanvas, (float) rect.left, (float) rect.top,
                (float) rect.right, (float) rect.bottom);
    }

    /*package*/ static boolean clipRect(Canvas thisCanvas, float left, float top, float right,
            float bottom) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return false;
        }

        return canvasDelegate.clipRect(left, top, right, bottom, Region.Op.INTERSECT.nativeInt);
    }

    /*package*/ static boolean clipRect(Canvas thisCanvas, int left, int top, int right,
            int bottom) {

        return clipRect(thisCanvas, (float) left, (float) top, (float) right, (float) bottom);
    }

    /*package*/ static int save(Canvas thisCanvas) {
        return save(thisCanvas, Canvas.MATRIX_SAVE_FLAG | Canvas.CLIP_SAVE_FLAG);
    }

    /*package*/ static int save(Canvas thisCanvas, int saveFlags) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return 0;
        }

        return canvasDelegate.save(saveFlags);
    }

    /*package*/ static void restore(Canvas thisCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        canvasDelegate.restore();
    }

    /*package*/ static int getSaveCount(Canvas thisCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return 0;
        }

        return canvasDelegate.getGcSnapshot().size();
    }

    /*package*/ static void restoreToCount(Canvas thisCanvas, int saveCount) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        canvasDelegate.restoreTo(saveCount);
    }

    /*package*/ static void drawPoints(Canvas thisCanvas, float[] pts, int offset, int count,
            Paint paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void drawPoint(Canvas thisCanvas, float x, float y, Paint paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void drawLines(Canvas thisCanvas, float[] pts, int offset, int count,
            Paint paint) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(paint.mNativePaint);
        if (paintDelegate == null) {
            assert false;
            return;
        }

        // get a Graphics2D object configured with the drawing parameters.
        Graphics2D g = canvasDelegate.createCustomGraphics(paintDelegate);

        try {
            for (int i = 0 ; i < count ; i += 4) {
                g.drawLine((int)pts[i + offset], (int)pts[i + offset + 1],
                        (int)pts[i + offset + 2], (int)pts[i + offset + 3]);
            }
        } finally {
            // dispose Graphics2D object
            g.dispose();
        }
    }

    /*package*/ static void freeCaches() {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int initRaster(int nativeBitmapOrZero) {
        if (nativeBitmapOrZero > 0) {
            // get the Bitmap from the int
            Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(nativeBitmapOrZero);

            // create a new Canvas_Delegate with the given bitmap and return its new native int.
            Canvas_Delegate newDelegate = new Canvas_Delegate(bitmapDelegate.getImage());

            return sManager.addDelegate(newDelegate);
        } else {
            // create a new Canvas_Delegate and return its new native int.
            Canvas_Delegate newDelegate = new Canvas_Delegate();

            return sManager.addDelegate(newDelegate);
        }
    }

    /*package*/ static void native_setBitmap(int nativeCanvas, int bitmap) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        // get the delegate from the native int.
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (bitmapDelegate == null) {
            assert false;
            return;
        }

        canvasDelegate.setBitmap(bitmapDelegate.getImage());
    }

    /*package*/ static int native_saveLayer(int nativeCanvas, RectF bounds,
                                               int paint, int layerFlags) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int native_saveLayer(int nativeCanvas, float l,
                                               float t, float r, float b,
                                               int paint, int layerFlags) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int native_saveLayerAlpha(int nativeCanvas,
                                                    RectF bounds, int alpha,
                                                    int layerFlags) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int native_saveLayerAlpha(int nativeCanvas, float l,
                                                    float t, float r, float b,
                                                    int alpha, int layerFlags) {
        // FIXME
        throw new UnsupportedOperationException();
    }


    /*package*/ static void native_concat(int nCanvas, int nMatrix) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        Matrix_Delegate matrixDelegate = Matrix_Delegate.getDelegate(nMatrix);
        if (matrixDelegate == null) {
            assert false;
            return;
        }

        // get the current top graphics2D object.
        GcSnapshot snapshot = canvasDelegate.getGcSnapshot();

        // get its current matrix
        AffineTransform currentTx = snapshot.getTransform();
        // get the AffineTransform of the given matrix
        AffineTransform matrixTx = matrixDelegate.getAffineTransform();

        // combine them so that the given matrix is applied after.
        currentTx.preConcatenate(matrixTx);

        // give it to the graphics2D as a new matrix replacing all previous transform
        snapshot.setTransform(currentTx);
    }

    /*package*/ static void native_setMatrix(int nCanvas, int nMatrix) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            assert false;
        }

        Matrix_Delegate matrixDelegate = Matrix_Delegate.getDelegate(nMatrix);
        if (matrixDelegate == null) {
            assert false;
        }

        // get the current top graphics2D object.
        GcSnapshot snapshot = canvasDelegate.getGcSnapshot();

        // get the AffineTransform of the given matrix
        AffineTransform matrixTx = matrixDelegate.getAffineTransform();

        // give it to the graphics2D as a new matrix replacing all previous transform
        snapshot.setTransform(matrixTx);

        if (matrixDelegate.hasPerspective()) {
            assert false;
            Bridge.getLog().fidelityWarning(null,
                    "android.graphics.Canvas#setMatrix(android.graphics.Matrix) only " +
                    "supports affine transformations in the Layout Preview.", null);
        }
    }

    /*package*/ static boolean native_clipRect(int nCanvas,
                                                  float left, float top,
                                                  float right, float bottom,
                                                  int regionOp) {

        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            assert false;
        }

        return canvasDelegate.clipRect(left, top, right, bottom, regionOp);
    }

    /*package*/ static boolean native_clipPath(int nativeCanvas,
                                                  int nativePath,
                                                  int regionOp) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static boolean native_clipRegion(int nativeCanvas,
                                                    int nativeRegion,
                                                    int regionOp) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void nativeSetDrawFilter(int nativeCanvas,
                                                   int nativeFilter) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static boolean native_getClipBounds(int nativeCanvas,
                                                       Rect bounds) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return false;
        }

        Rectangle rect = canvasDelegate.getGcSnapshot().getClip().getBounds();
        if (rect != null) {
            bounds.left = rect.x;
            bounds.top = rect.y;
            bounds.right = rect.x + rect.width;
            bounds.bottom = rect.y + rect.height;
            return true;
        }
        return false;
    }

    /*package*/ static void native_getCTM(int canvas, int matrix) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static boolean native_quickReject(int nativeCanvas,
                                                     RectF rect,
                                                     int native_edgeType) {
        // FIXME properly implement quickReject
        return false;
    }

    /*package*/ static boolean native_quickReject(int nativeCanvas,
                                                     int path,
                                                     int native_edgeType) {
        // FIXME properly implement quickReject
        return false;
    }

    /*package*/ static boolean native_quickReject(int nativeCanvas,
                                                     float left, float top,
                                                     float right, float bottom,
                                                     int native_edgeType) {
        // FIXME properly implement quickReject
        return false;
    }

    /*package*/ static void native_drawRGB(int nativeCanvas, int r, int g, int b) {
        native_drawColor(nativeCanvas, 0xFF000000 | r << 16 | (g&0xFF) << 8 | (b&0xFF),
                PorterDuff.Mode.SRC_OVER.nativeInt);

    }

    /*package*/ static void native_drawARGB(int nativeCanvas, int a, int r, int g, int b) {
        native_drawColor(nativeCanvas, a << 24 | (r&0xFF) << 16 | (g&0xFF) << 8 | (b&0xFF),
                PorterDuff.Mode.SRC_OVER.nativeInt);
    }

    /*package*/ static void native_drawColor(int nativeCanvas, int color) {
        native_drawColor(nativeCanvas, color, PorterDuff.Mode.SRC_OVER.nativeInt);
    }

    /*package*/ static void native_drawColor(int nativeCanvas, int color, int mode) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        // get a new graphics context.
        Graphics2D graphics = (Graphics2D)canvasDelegate.getGcSnapshot().create();
        try {
            // reset its transform just in case
            graphics.setTransform(new AffineTransform());

            // set the color
            graphics.setColor(new Color(color, true /*alpha*/));

            Composite composite = PorterDuffXfermode_Delegate.getComposite(
                    PorterDuffXfermode_Delegate.getPorterDuffMode(mode), 0xFF);
            if (composite != null) {
                graphics.setComposite(composite);
            }

            graphics.fillRect(0, 0, canvasDelegate.mBufferedImage.getWidth(),
                    canvasDelegate.mBufferedImage.getHeight());
        } finally {
            // dispose Graphics2D object
            graphics.dispose();
        }
    }

    /*package*/ static void native_drawPaint(int nativeCanvas, int paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawLine(int nativeCanvas, float startX,
                                               float startY, float stopX,
                                               float stopY, int paint) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        // get the delegate from the native int.
        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(paint);
        if (paintDelegate == null) {
            assert false;
            return;
        }

        // get a Graphics2D object configured with the drawing parameters.
        Graphics2D g = canvasDelegate.createCustomGraphics(paintDelegate);

        try {
            g.drawLine((int)startX, (int)startY, (int)stopX, (int)stopY);
        } finally {
            // dispose Graphics2D object
            g.dispose();
        }
    }

    /*package*/ static void native_drawRect(int nativeCanvas, RectF rect,
                                               int paint) {
        native_drawRect(nativeCanvas, rect.left, rect.top, rect.right, rect.bottom, paint);
    }

    /*package*/ static void native_drawRect(int nativeCanvas, float left,
                                               float top, float right,
                                               float bottom, int paint) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        // get the delegate from the native int.
        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(paint);
        if (paintDelegate == null) {
            assert false;
            return;
        }

        if (right > left && bottom > top) {
            // get a Graphics2D object configured with the drawing parameters.
            Graphics2D g = canvasDelegate.createCustomGraphics(paintDelegate);

            try {
                int style = paintDelegate.getStyle();

                // draw
                if (style == Paint.Style.FILL.nativeInt ||
                        style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                    g.fillRect((int)left, (int)top, (int)(right-left), (int)(bottom-top));
                }

                if (style == Paint.Style.STROKE.nativeInt ||
                        style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                    g.drawRect((int)left, (int)top, (int)(right-left), (int)(bottom-top));
                }
            } finally {
                // dispose Graphics2D object
                g.dispose();
            }
        }
    }

    /*package*/ static void native_drawOval(int nativeCanvas, RectF oval,
                                               int paint) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        // get the delegate from the native int.
        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(paint);
        if (paintDelegate == null) {
            assert false;
            return;
        }

        if (oval.right > oval.left && oval.bottom > oval.top) {
            // get a Graphics2D object configured with the drawing parameters.
            Graphics2D g = canvasDelegate.createCustomGraphics(paintDelegate);

            int style = paintDelegate.getStyle();

            // draw
            if (style == Paint.Style.FILL.nativeInt ||
                    style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                g.fillOval((int)oval.left, (int)oval.top, (int)oval.width(), (int)oval.height());
            }

            if (style == Paint.Style.STROKE.nativeInt ||
                    style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                g.drawOval((int)oval.left, (int)oval.top, (int)oval.width(), (int)oval.height());
            }

            // dispose Graphics2D object
            g.dispose();
        }
    }

    /*package*/ static void native_drawCircle(int nativeCanvas, float cx,
                                                 float cy, float radius,
                                                 int paint) {
        native_drawOval(nativeCanvas,
                new RectF(cx - radius, cy - radius, radius*2, radius*2),
                paint);
    }

    /*package*/ static void native_drawArc(int nativeCanvas, RectF oval,
                                              float startAngle, float sweep,
                                              boolean useCenter, int paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawRoundRect(int nativeCanvas,
                                                    RectF rect, float rx,
                                                    float ry, int paint) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        // get the delegate from the native int.
        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(paint);
        if (paintDelegate == null) {
            assert false;
            return;
        }

        if (rect.right > rect.left && rect.bottom > rect.top) {
            // get a Graphics2D object configured with the drawing parameters.
            Graphics2D g = canvasDelegate.createCustomGraphics(paintDelegate);

            int style = paintDelegate.getStyle();

            // draw
            if (style == Paint.Style.FILL.nativeInt ||
                    style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                g.fillRoundRect(
                        (int)rect.left, (int)rect.top, (int)rect.width(), (int)rect.height(),
                        (int)rx, (int)ry);
            }

            if (style == Paint.Style.STROKE.nativeInt ||
                    style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                g.drawRoundRect(
                        (int)rect.left, (int)rect.top, (int)rect.width(), (int)rect.height(),
                        (int)rx, (int)ry);
            }

            // dispose Graphics2D object
            g.dispose();
        }
    }

    /*package*/ static void native_drawPath(int nativeCanvas, int path,
                                               int paint) {
        final Path_Delegate pathDelegate = Path_Delegate.getDelegate(path);
        if (pathDelegate == null) {
            assert false;
            return;
        }

        draw(nativeCanvas, paint, new Drawable() {
            public void draw(Graphics2D graphics, Paint_Delegate paint) {
                Shape shape = pathDelegate.getJavaShape();
                int style = paint.getStyle();

                if (style == Paint.Style.FILL.nativeInt ||
                        style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                    graphics.fill(shape);
                }

                if (style == Paint.Style.STROKE.nativeInt ||
                        style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                    graphics.draw(shape);
                }
            }
        });
    }

    /*package*/ static void native_drawBitmap(Canvas thisCanvas, int nativeCanvas, int bitmap,
                                                 float left, float top,
                                                 int nativePaintOrZero,
                                                 int canvasDensity,
                                                 int screenDensity,
                                                 int bitmapDensity) {
        // get the delegate from the native int.
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (bitmapDelegate == null) {
            assert false;
            return;
        }

        BufferedImage image = bitmapDelegate.getImage();
        float right = left + image.getWidth();
        float bottom = top + image.getHeight();

        drawBitmap(nativeCanvas, image, bitmapDelegate.getConfig(), nativePaintOrZero,
                0, 0, image.getWidth(), image.getHeight(),
                (int)left, (int)top, (int)right, (int)bottom);
    }

    /*package*/ static void native_drawBitmap(Canvas thisCanvas, int nativeCanvas, int bitmap,
                                                 Rect src, RectF dst,
                                                 int nativePaintOrZero,
                                                 int screenDensity,
                                                 int bitmapDensity) {
        // get the delegate from the native int.
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (bitmapDelegate == null) {
            assert false;
            return;
        }

        BufferedImage image = bitmapDelegate.getImage();

        if (src == null) {
            drawBitmap(nativeCanvas, image, bitmapDelegate.getConfig(), nativePaintOrZero,
                    0, 0, image.getWidth(), image.getHeight(),
                    (int)dst.left, (int)dst.top, (int)dst.right, (int)dst.bottom);
        } else {
            drawBitmap(nativeCanvas, image, bitmapDelegate.getConfig(), nativePaintOrZero,
                    src.left, src.top, src.width(), src.height(),
                    (int)dst.left, (int)dst.top, (int)dst.right, (int)dst.bottom);
        }
    }

    /*package*/ static void native_drawBitmap(int nativeCanvas, int bitmap,
                                                 Rect src, Rect dst,
                                                 int nativePaintOrZero,
                                                 int screenDensity,
                                                 int bitmapDensity) {
        // get the delegate from the native int.
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (bitmapDelegate == null) {
            assert false;
            return;
        }

        BufferedImage image = bitmapDelegate.getImage();

        if (src == null) {
            drawBitmap(nativeCanvas, image, bitmapDelegate.getConfig(), nativePaintOrZero,
                    0, 0, image.getWidth(), image.getHeight(),
                    dst.left, dst.top, dst.right, dst.bottom);
        } else {
            drawBitmap(nativeCanvas, image, bitmapDelegate.getConfig(), nativePaintOrZero,
                    src.left, src.top, src.width(), src.height(),
                    dst.left, dst.top, dst.right, dst.bottom);
        }
    }

    /*package*/ static void native_drawBitmap(int nativeCanvas, int[] colors,
                                                int offset, int stride, float x,
                                                 float y, int width, int height,
                                                 boolean hasAlpha,
                                                 int nativePaintOrZero) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void nativeDrawBitmapMatrix(int nCanvas, int nBitmap,
                                                      int nMatrix, int nPaint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void nativeDrawBitmapMesh(int nCanvas, int nBitmap,
                                                    int meshWidth, int meshHeight,
                                                    float[] verts, int vertOffset,
                                                    int[] colors, int colorOffset, int nPaint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void nativeDrawVertices(int nCanvas, int mode, int n,
                   float[] verts, int vertOffset, float[] texs, int texOffset,
                   int[] colors, int colorOffset, short[] indices,
                   int indexOffset, int indexCount, int nPaint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawText(int nativeCanvas, char[] text,
                                               int index, int count, float x,
                                               float y, int flags, int paint) {
        // WARNING: the logic in this method is similar to Paint.measureText.
        // Any change to this method should be reflected in Paint.measureText

        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        // get the delegate from the native int.
        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(paint);
        if (paintDelegate == null) {
            assert false;
            return;
        }

        Graphics2D g = (Graphics2D) canvasDelegate.createCustomGraphics(paintDelegate);
        try {
            // Paint.TextAlign indicates how the text is positioned relative to X.
            // LEFT is the default and there's nothing to do.
            if (paintDelegate.getTextAlign() != Paint.Align.LEFT.nativeInt) {
                float m = paintDelegate.measureText(text, index, count);
                if (paintDelegate.getTextAlign() == Paint.Align.CENTER.nativeInt) {
                    x -= m / 2;
                } else if (paintDelegate.getTextAlign() == Paint.Align.RIGHT.nativeInt) {
                    x -= m;
                }
            }

            List<FontInfo> fonts = paintDelegate.getFonts();

            if (fonts.size() > 0) {
                FontInfo mainFont = fonts.get(0);
                int i = index;
                int lastIndex = index + count;
                while (i < lastIndex) {
                    // always start with the main font.
                    int upTo = mainFont.mFont.canDisplayUpTo(text, i, lastIndex);
                    if (upTo == -1) {
                        // draw all the rest and exit.
                        g.setFont(mainFont.mFont);
                        g.drawChars(text, i, lastIndex - i, (int)x, (int)y);
                        return;
                    } else if (upTo > 0) {
                        // draw what's possible
                        g.setFont(mainFont.mFont);
                        g.drawChars(text, i, upTo - i, (int)x, (int)y);

                        // compute the width that was drawn to increase x
                        x += mainFont.mMetrics.charsWidth(text, i, upTo - i);

                        // move index to the first non displayed char.
                        i = upTo;

                        // don't call continue at this point. Since it is certain the main font
                        // cannot display the font a index upTo (now ==i), we move on to the
                        // fallback fonts directly.
                    }

                    // no char supported, attempt to read the next char(s) with the
                    // fallback font. In this case we only test the first character
                    // and then go back to test with the main font.
                    // Special test for 2-char characters.
                    boolean foundFont = false;
                    for (int f = 1 ; f < fonts.size() ; f++) {
                        FontInfo fontInfo = fonts.get(f);

                        // need to check that the font can display the character. We test
                        // differently if the char is a high surrogate.
                        int charCount = Character.isHighSurrogate(text[i]) ? 2 : 1;
                        upTo = fontInfo.mFont.canDisplayUpTo(text, i, i + charCount);
                        if (upTo == -1) {
                            // draw that char
                            g.setFont(fontInfo.mFont);
                            g.drawChars(text, i, charCount, (int)x, (int)y);

                            // update x
                            x += fontInfo.mMetrics.charsWidth(text, i, charCount);

                            // update the index in the text, and move on
                            i += charCount;
                            foundFont = true;
                            break;

                        }
                    }

                    // in case no font can display the char, display it with the main font.
                    // (it'll put a square probably)
                    if (foundFont == false) {
                        int charCount = Character.isHighSurrogate(text[i]) ? 2 : 1;

                        g.setFont(mainFont.mFont);
                        g.drawChars(text, i, charCount, (int)x, (int)y);

                        // measure it to advance x
                        x += mainFont.mMetrics.charsWidth(text, i, charCount);

                        // and move to the next chars.
                        i += charCount;
                    }
                }
            }
        } finally {
            g.dispose();
        }
    }

    /*package*/ static void native_drawText(int nativeCanvas, String text,
                                               int start, int end, float x,
                                               float y, int flags, int paint) {
        int count = end - start;
        char[] buffer = TemporaryBuffer.obtain(count);
        TextUtils.getChars(text, start, end, buffer, 0);

        native_drawText(nativeCanvas, buffer, 0, count, x, y, flags, paint);
    }

    /*package*/ static void native_drawTextRun(int nativeCanvas, String text,
            int start, int end, int contextStart, int contextEnd,
            float x, float y, int flags, int paint) {
        int count = end - start;
        char[] buffer = TemporaryBuffer.obtain(count);
        TextUtils.getChars(text, start, end, buffer, 0);

        native_drawText(nativeCanvas, buffer, start, end, x, y, flags, paint);
    }

    /*package*/ static void native_drawTextRun(int nativeCanvas, char[] text,
            int start, int count, int contextStart, int contextCount,
            float x, float y, int flags, int paint) {
        native_drawText(nativeCanvas, text, 0, count, x, y, flags, paint);
    }

    /*package*/ static void native_drawPosText(int nativeCanvas,
                                                  char[] text, int index,
                                                  int count, float[] pos,
                                                  int paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawPosText(int nativeCanvas,
                                                  String text, float[] pos,
                                                  int paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawTextOnPath(int nativeCanvas,
                                                     char[] text, int index,
                                                     int count, int path,
                                                     float hOffset,
                                                     float vOffset, int bidiFlags,
                                                     int paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawTextOnPath(int nativeCanvas,
                                                     String text, int path,
                                                     float hOffset,
                                                     float vOffset,
                                                     int flags, int paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawPicture(int nativeCanvas,
                                                  int nativePicture) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void finalizer(int nativeCanvas) {
        // get the delegate from the native int so that it can be disposed.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        canvasDelegate.dispose();

        // remove it from the manager.
        sManager.removeDelegate(nativeCanvas);
    }

    // ---- Private delegate/helper methods ----

    /**
     * Executes a {@link Drawable} with a given canvas and paint.
     */
    private static void draw(int nCanvas, int nPaint, Drawable drawable) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(nPaint);
        if (paintDelegate == null) {
            assert false;
            return;
        }

        // get a Graphics2D object configured with the drawing parameters.
        Graphics2D g = canvasDelegate.createCustomGraphics(paintDelegate);

        try {
            drawable.draw(g, paintDelegate);
        } finally {
            // dispose Graphics2D object
            g.dispose();
        }
    }

    private Canvas_Delegate(BufferedImage image) {
        setBitmap(image);
    }

    private Canvas_Delegate() {
    }

    /**
     * Disposes of the {@link Graphics2D} stack.
     */
    private void dispose() {
        mSnapshot.dispose();
    }

    private int save(int saveFlags) {
        // get the current save count
        int count = mSnapshot.size();

        // create a new snapshot and add it to the stack
        mSnapshot = new GcSnapshot(mSnapshot, saveFlags);

        // return the old save count
        return count;
    }

    /**
     * Restores the {@link GcSnapshot} to <var>saveCount</var>
     * @param saveCount the saveCount
     */
    private void restoreTo(int saveCount) {
        mSnapshot = mSnapshot.restoreTo(saveCount);
    }

    /**
     * Restores the {@link GcSnapshot} to <var>saveCount</var>
     * @param saveCount the saveCount
     */
    private void restore() {
        mSnapshot = mSnapshot.restore();
    }

    private boolean clipRect(float left, float top, float right, float bottom, int regionOp) {
        return mSnapshot.clipRect(left, top, right, bottom, regionOp);
    }

    private void setBitmap(BufferedImage image) {
        mBufferedImage = image;
        assert mSnapshot.size() == 1;
        mSnapshot.setGraphics2D(mBufferedImage.createGraphics());
    }

    /**
     * Creates a new {@link Graphics2D} based on the {@link Paint} parameters.
     * <p/>The object must be disposed ({@link Graphics2D#dispose()}) after being used.
     */
    /*package*/ Graphics2D createCustomGraphics(Paint_Delegate paint) {
        // make new one
        Graphics2D g = getGcSnapshot().create();

        // configure it

        if (paint.isAntiAliased()) {
            g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        }

        // get the shader first, as it'll replace the color if it can be used it.
        boolean customShader = false;
        int nativeShader = paint.getShader();
        if (nativeShader > 0) {
            Shader_Delegate shaderDelegate = Shader_Delegate.getDelegate(nativeShader);
            assert shaderDelegate != null;
            if (shaderDelegate != null) {
                if (shaderDelegate.isSupported()) {
                    java.awt.Paint shaderPaint = shaderDelegate.getJavaPaint();
                    assert shaderPaint != null;
                    if (shaderPaint != null) {
                        g.setPaint(shaderPaint);
                        customShader = true;
                    }
                } else {
                    Bridge.getLog().fidelityWarning(null,
                            shaderDelegate.getSupportMessage(),
                            null);
                }
            }
        }

        // if no shader, use the paint color
        if (customShader == false) {
            g.setColor(new Color(paint.getColor(), true /*hasAlpha*/));
        }

        boolean customStroke = false;
        int pathEffect = paint.getPathEffect();
        if (pathEffect > 0) {
            PathEffect_Delegate effectDelegate = PathEffect_Delegate.getDelegate(pathEffect);
            assert effectDelegate != null;
            if (effectDelegate != null) {
                if (effectDelegate.isSupported()) {
                    Stroke stroke = effectDelegate.getStroke(paint);
                    assert stroke != null;
                    if (stroke != null) {
                        g.setStroke(stroke);
                        customStroke = true;
                    }
                } else {
                    Bridge.getLog().fidelityWarning(null,
                            effectDelegate.getSupportMessage(),
                            null);
                }
            }
        }

        // if no custom stroke as been set, set the default one.
        if (customStroke == false) {
            g.setStroke(new BasicStroke(
                    paint.getStrokeWidth(),
                    paint.getJavaCap(),
                    paint.getJavaJoin(),
                    paint.getJavaStrokeMiter()));
        }

        // the alpha for the composite. Always opaque if the normal paint color is used since
        // it contains the alpha
        int alpha = customShader ? paint.getAlpha() : 0xFF;

        boolean customXfermode = false;
        int xfermode = paint.getXfermode();
        if (xfermode > 0) {
            Xfermode_Delegate xfermodeDelegate = Xfermode_Delegate.getDelegate(paint.getXfermode());
            assert xfermodeDelegate != null;
            if (xfermodeDelegate != null) {
                if (xfermodeDelegate.isSupported()) {
                    Composite composite = xfermodeDelegate.getComposite(alpha);
                    assert composite != null;
                    if (composite != null) {
                        g.setComposite(composite);
                        customXfermode = true;
                    }
                } else {
                    Bridge.getLog().fidelityWarning(null,
                            xfermodeDelegate.getSupportMessage(),
                            null);
                }
            }
        }

        // if there was no custom xfermode, but we have alpha (due to a shader and a non
        // opaque alpha channel in the paint color), then we create an AlphaComposite anyway
        // that will handle the alpha.
        if (customXfermode == false && alpha != 0xFF) {
            g.setComposite(PorterDuffXfermode_Delegate.getComposite(
                    PorterDuff.Mode.SRC_OVER, alpha));
        }

        return g;
    }

    private static void drawBitmap(
            int nativeCanvas,
            BufferedImage image,
            Bitmap.Config mBitmapConfig,
            int nativePaintOrZero,
            int sleft, int stop, int sright, int sbottom,
            int dleft, int dtop, int dright, int dbottom) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            assert false;
            return;
        }

        // get the delegate from the native int.
        Paint_Delegate paintDelegate = null;
        if (nativePaintOrZero > 0) {
            paintDelegate = Paint_Delegate.getDelegate(nativePaintOrZero);
            if (paintDelegate == null) {
                assert false;
                return;
            }
        }

        drawBitmap(canvasDelegate, image, mBitmapConfig, paintDelegate,
                sleft, stop, sright, sbottom,
                dleft, dtop, dright, dbottom);
    }

    private static void drawBitmap(
            Canvas_Delegate canvasDelegate,
            BufferedImage image,
            Bitmap.Config mBitmapConfig,
            Paint_Delegate paintDelegate,
            int sleft, int stop, int sright, int sbottom,
            int dleft, int dtop, int dright, int dbottom) {
        //FIXME add support for canvas, screen and bitmap densities.

        Graphics2D g = canvasDelegate.getGcSnapshot().create();
        try {
            if (paintDelegate != null && paintDelegate.isFilterBitmap()) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            }

            // if the bitmap config is alpha_8, then we erase all color value from it
            // before drawing it.
            if (mBitmapConfig == Bitmap.Config.ALPHA_8) {
                int w = image.getWidth();
                int h = image.getHeight();
                int[] argb = new int[w*h];
                image.getRGB(0, 0, image.getWidth(), image.getHeight(), argb, 0, image.getWidth());

                final int length = argb.length;
                for (int i = 0 ; i < length; i++) {
                    argb[i] &= 0xFF000000;
                }
                image.setRGB(0, 0, w, h, argb, 0, w);
            }

            g.drawImage(image, dleft, dtop, dright, dbottom,
                    sleft, stop, sright, sbottom, null);
        } finally {
            g.dispose();
        }
    }
}

