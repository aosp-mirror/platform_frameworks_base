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
import com.android.layoutlib.bridge.impl.GcSnapshot;
import com.android.layoutlib.bridge.impl.PorterDuffUtility;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.annotation.Nullable;
import android.graphics.Bitmap.Config;
import android.text.TextUtils;

import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;


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
            new DelegateManager<Canvas_Delegate>(Canvas_Delegate.class);


    // ---- delegate helper data ----

    private final static boolean[] sBoolOut = new boolean[1];


    // ---- delegate data ----
    private Bitmap_Delegate mBitmap;
    private GcSnapshot mSnapshot;

    private DrawFilter_Delegate mDrawFilter = null;


    // ---- Public Helper methods ----

    /**
     * Returns the native delegate associated to a given {@link Canvas} object.
     */
    public static Canvas_Delegate getDelegate(Canvas canvas) {
        return sManager.getDelegate(canvas.getNativeCanvasWrapper());
    }

    /**
     * Returns the native delegate associated to a given an int referencing a {@link Canvas} object.
     */
    public static Canvas_Delegate getDelegate(long native_canvas) {
        return sManager.getDelegate(native_canvas);
    }

    /**
     * Returns the current {@link Graphics2D} used to draw.
     */
    public GcSnapshot getSnapshot() {
        return mSnapshot;
    }

    /**
     * Returns the {@link DrawFilter} delegate or null if none have been set.
     *
     * @return the delegate or null.
     */
    public DrawFilter_Delegate getDrawFilter() {
        return mDrawFilter;
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static void freeCaches() {
        // nothing to be done here.
    }

    @LayoutlibDelegate
    /*package*/ static void freeTextLayoutCaches() {
        // nothing to be done here yet.
    }

    @LayoutlibDelegate
    /*package*/ static long initRaster(@Nullable Bitmap bitmap) {
        long nativeBitmapOrZero = 0;
        if (bitmap != null) {
            nativeBitmapOrZero = bitmap.refSkPixelRef();
        }
        if (nativeBitmapOrZero > 0) {
            // get the Bitmap from the int
            Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(nativeBitmapOrZero);

            // create a new Canvas_Delegate with the given bitmap and return its new native int.
            Canvas_Delegate newDelegate = new Canvas_Delegate(bitmapDelegate);

            return sManager.addNewDelegate(newDelegate);
        }

        // create a new Canvas_Delegate and return its new native int.
        Canvas_Delegate newDelegate = new Canvas_Delegate();

        return sManager.addNewDelegate(newDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static void native_setBitmap(long canvas, Bitmap bitmap) {
        Canvas_Delegate canvasDelegate = sManager.getDelegate(canvas);
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (canvasDelegate == null || bitmapDelegate==null) {
            return;
        }
        canvasDelegate.mBitmap = bitmapDelegate;
        canvasDelegate.mSnapshot = GcSnapshot.createDefaultSnapshot(bitmapDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_isOpaque(long nativeCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return false;
        }

        return canvasDelegate.mBitmap.getConfig() == Config.RGB_565;
    }

    @LayoutlibDelegate
    /*package*/ static int native_getWidth(long nativeCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.mBitmap.getImage().getWidth();
    }

    @LayoutlibDelegate
    /*package*/ static int native_getHeight(long nativeCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.mBitmap.getImage().getHeight();
    }

    @LayoutlibDelegate
    /*package*/ static int native_save(long nativeCanvas, int saveFlags) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.save(saveFlags);
    }

    @LayoutlibDelegate
    /*package*/ static int native_saveLayer(long nativeCanvas, float l,
                                               float t, float r, float b,
                                               long paint, int layerFlags) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(paint);
        if (paintDelegate == null) {
            return 0;
        }

        return canvasDelegate.saveLayer(new RectF(l, t, r, b),
                paintDelegate, layerFlags);
    }

    @LayoutlibDelegate
    /*package*/ static int native_saveLayerAlpha(long nativeCanvas, float l,
                                                    float t, float r, float b,
                                                    int alpha, int layerFlags) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.saveLayerAlpha(new RectF(l, t, r, b), alpha, layerFlags);
    }

    @LayoutlibDelegate
    /*package*/ static void native_restore(long nativeCanvas, boolean throwOnUnderflow) {
        // FIXME: implement throwOnUnderflow.
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.restore();
    }

    @LayoutlibDelegate
    /*package*/ static void native_restoreToCount(long nativeCanvas, int saveCount,
            boolean throwOnUnderflow) {
        // FIXME: implement throwOnUnderflow.
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.restoreTo(saveCount);
    }

    @LayoutlibDelegate
    /*package*/ static int native_getSaveCount(long nativeCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.getSnapshot().size();
    }

    @LayoutlibDelegate
   /*package*/ static void native_translate(long nativeCanvas, float dx, float dy) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.getSnapshot().translate(dx, dy);
    }

    @LayoutlibDelegate
       /*package*/ static void native_scale(long nativeCanvas, float sx, float sy) {
            // get the delegate from the native int.
            Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
            if (canvasDelegate == null) {
                return;
            }

            canvasDelegate.getSnapshot().scale(sx, sy);
        }

    @LayoutlibDelegate
    /*package*/ static void native_rotate(long nativeCanvas, float degrees) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.getSnapshot().rotate(Math.toRadians(degrees));
    }

    @LayoutlibDelegate
   /*package*/ static void native_skew(long nativeCanvas, float kx, float ky) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        // get the current top graphics2D object.
        GcSnapshot g = canvasDelegate.getSnapshot();

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

    @LayoutlibDelegate
    /*package*/ static void native_concat(long nCanvas, long nMatrix) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            return;
        }

        Matrix_Delegate matrixDelegate = Matrix_Delegate.getDelegate(nMatrix);
        if (matrixDelegate == null) {
            return;
        }

        // get the current top graphics2D object.
        GcSnapshot snapshot = canvasDelegate.getSnapshot();

        // get its current matrix
        AffineTransform currentTx = snapshot.getTransform();
        // get the AffineTransform of the given matrix
        AffineTransform matrixTx = matrixDelegate.getAffineTransform();

        // combine them so that the given matrix is applied after.
        currentTx.concatenate(matrixTx);

        // give it to the graphics2D as a new matrix replacing all previous transform
        snapshot.setTransform(currentTx);
    }

    @LayoutlibDelegate
    /*package*/ static void native_setMatrix(long nCanvas, long nMatrix) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            return;
        }

        Matrix_Delegate matrixDelegate = Matrix_Delegate.getDelegate(nMatrix);
        if (matrixDelegate == null) {
            return;
        }

        // get the current top graphics2D object.
        GcSnapshot snapshot = canvasDelegate.getSnapshot();

        // get the AffineTransform of the given matrix
        AffineTransform matrixTx = matrixDelegate.getAffineTransform();

        // give it to the graphics2D as a new matrix replacing all previous transform
        snapshot.setTransform(matrixTx);

        if (matrixDelegate.hasPerspective()) {
            assert false;
            Bridge.getLog().fidelityWarning(LayoutLog.TAG_MATRIX_AFFINE,
                    "android.graphics.Canvas#setMatrix(android.graphics.Matrix) only " +
                    "supports affine transformations.", null, null /*data*/);
        }
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_clipRect(long nCanvas,
                                                  float left, float top,
                                                  float right, float bottom,
                                                  int regionOp) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            return false;
        }

        return canvasDelegate.clipRect(left, top, right, bottom, regionOp);
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_clipPath(long nativeCanvas,
                                                  long nativePath,
                                                  int regionOp) {
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return true;
        }

        Path_Delegate pathDelegate = Path_Delegate.getDelegate(nativePath);
        if (pathDelegate == null) {
            return true;
        }

        return canvasDelegate.mSnapshot.clip(pathDelegate.getJavaShape(), regionOp);
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_clipRegion(long nativeCanvas,
                                                    long nativeRegion,
                                                    int regionOp) {
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return true;
        }

        Region_Delegate region = Region_Delegate.getDelegate(nativeRegion);
        if (region == null) {
            return true;
        }

        return canvasDelegate.mSnapshot.clip(region.getJavaArea(), regionOp);
    }

    @LayoutlibDelegate
    /*package*/ static void nativeSetDrawFilter(long nativeCanvas, long nativeFilter) {
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.mDrawFilter = DrawFilter_Delegate.getDelegate(nativeFilter);

        if (canvasDelegate.mDrawFilter != null && !canvasDelegate.mDrawFilter.isSupported()) {
            Bridge.getLog().fidelityWarning(LayoutLog.TAG_DRAWFILTER,
                    canvasDelegate.mDrawFilter.getSupportMessage(), null, null /*data*/);
        }
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_getClipBounds(long nativeCanvas,
                                                       Rect bounds) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return false;
        }

        Rectangle rect = canvasDelegate.getSnapshot().getClip().getBounds();
        if (rect != null && !rect.isEmpty()) {
            bounds.left = rect.x;
            bounds.top = rect.y;
            bounds.right = rect.x + rect.width;
            bounds.bottom = rect.y + rect.height;
            return true;
        }

        return false;
    }

    @LayoutlibDelegate
    /*package*/ static void native_getCTM(long canvas, long matrix) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(canvas);
        if (canvasDelegate == null) {
            return;
        }

        Matrix_Delegate matrixDelegate = Matrix_Delegate.getDelegate(matrix);
        if (matrixDelegate == null) {
            return;
        }

        AffineTransform transform = canvasDelegate.getSnapshot().getTransform();
        matrixDelegate.set(Matrix_Delegate.makeValues(transform));
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_quickReject(long nativeCanvas, long path) {
        // FIXME properly implement quickReject
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_quickReject(long nativeCanvas,
                                                     float left, float top,
                                                     float right, float bottom) {
        // FIXME properly implement quickReject
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawColor(long nativeCanvas, final int color, final int mode) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        final int w = canvasDelegate.mBitmap.getImage().getWidth();
        final int h = canvasDelegate.mBitmap.getImage().getHeight();
        draw(nativeCanvas, new GcSnapshot.Drawable() {

            @Override
            public void draw(Graphics2D graphics, Paint_Delegate paint) {
                // reset its transform just in case
                graphics.setTransform(new AffineTransform());

                // set the color
                graphics.setColor(new Color(color, true /*alpha*/));

                Composite composite = PorterDuffUtility.getComposite(
                        PorterDuffUtility.getPorterDuffMode(mode), 0xFF);
                if (composite != null) {
                    graphics.setComposite(composite);
                }

                graphics.fillRect(0, 0, w, h);
            }
        });
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawPaint(long nativeCanvas, long paint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawPaint is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawPoint(long nativeCanvas, float x, float y,
            long nativePaint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawPoint is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawPoints(long nativeCanvas, float[] pts, int offset, int count,
            long nativePaint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawPoint is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawLine(long nativeCanvas,
            final float startX, final float startY, final float stopX, final float stopY,
            long paint) {
        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                new GcSnapshot.Drawable() {
                    @Override
                    public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                        graphics.drawLine((int)startX, (int)startY, (int)stopX, (int)stopY);
                    }
        });
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawLines(long nativeCanvas,
            final float[] pts, final int offset, final int count,
            long nativePaint) {
        draw(nativeCanvas, nativePaint, false /*compositeOnly*/,
                false /*forceSrcMode*/, new GcSnapshot.Drawable() {
                    @Override
                    public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                        for (int i = 0; i < count; i += 4) {
                            graphics.drawLine((int) pts[i + offset], (int) pts[i + offset + 1],
                                    (int) pts[i + offset + 2], (int) pts[i + offset + 3]);
                        }
                    }
                });
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawRect(long nativeCanvas,
            final float left, final float top, final float right, final float bottom, long paint) {

        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                new GcSnapshot.Drawable() {
                    @Override
                    public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                        int style = paintDelegate.getStyle();

                        // draw
                        if (style == Paint.Style.FILL.nativeInt ||
                                style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                            graphics.fillRect((int)left, (int)top,
                                    (int)(right-left), (int)(bottom-top));
                        }

                        if (style == Paint.Style.STROKE.nativeInt ||
                                style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                            graphics.drawRect((int)left, (int)top,
                                    (int)(right-left), (int)(bottom-top));
                        }
                    }
        });
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawOval(long nativeCanvas, final float left,
            final float top, final float right, final float bottom, long paint) {
        if (right > left && bottom > top) {
            draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                    new GcSnapshot.Drawable() {
                        @Override
                        public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                            int style = paintDelegate.getStyle();

                            // draw
                            if (style == Paint.Style.FILL.nativeInt ||
                                    style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                                graphics.fillOval((int)left, (int)top,
                                        (int)(right - left), (int)(bottom - top));
                            }

                            if (style == Paint.Style.STROKE.nativeInt ||
                                    style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                                graphics.drawOval((int)left, (int)top,
                                        (int)(right - left), (int)(bottom - top));
                            }
                        }
            });
        }
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawCircle(long nativeCanvas,
            float cx, float cy, float radius, long paint) {
        native_drawOval(nativeCanvas,
                cx - radius, cy - radius, cx + radius, cy + radius,
                paint);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawArc(long nativeCanvas,
            final float left, final float top, final float right, final float bottom,
            final float startAngle, final float sweep,
            final boolean useCenter, long paint) {
        if (right > left && bottom > top) {
            draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                    new GcSnapshot.Drawable() {
                        @Override
                        public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                            int style = paintDelegate.getStyle();

                            Arc2D.Float arc = new Arc2D.Float(
                                    left, top, right - left, bottom - top,
                                    -startAngle, -sweep,
                                    useCenter ? Arc2D.PIE : Arc2D.OPEN);

                            // draw
                            if (style == Paint.Style.FILL.nativeInt ||
                                    style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                                graphics.fill(arc);
                            }

                            if (style == Paint.Style.STROKE.nativeInt ||
                                    style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                                graphics.draw(arc);
                            }
                        }
            });
        }
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawRoundRect(long nativeCanvas,
            final float left, final float top, final float right, final float bottom,
            final float rx, final float ry, long paint) {
        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                new GcSnapshot.Drawable() {
                    @Override
                    public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                        int style = paintDelegate.getStyle();

                        // draw
                        if (style == Paint.Style.FILL.nativeInt ||
                                style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                            graphics.fillRoundRect(
                                    (int)left, (int)top,
                                    (int)(right - left), (int)(bottom - top),
                                    2 * (int)rx, 2 * (int)ry);
                        }

                        if (style == Paint.Style.STROKE.nativeInt ||
                                style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                            graphics.drawRoundRect(
                                    (int)left, (int)top,
                                    (int)(right - left), (int)(bottom - top),
                                    2 * (int)rx, 2 * (int)ry);
                        }
                    }
        });
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawPath(long nativeCanvas, long path, long paint) {
        final Path_Delegate pathDelegate = Path_Delegate.getDelegate(path);
        if (pathDelegate == null) {
            return;
        }

        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                new GcSnapshot.Drawable() {
                    @Override
                    public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                        Shape shape = pathDelegate.getJavaShape();
                        Rectangle2D bounds = shape.getBounds2D();
                        if (bounds.isEmpty()) {
                            // Apple JRE 1.6 doesn't like drawing empty shapes.
                            // http://b.android.com/178278
                            return;
                        }
                        int style = paintDelegate.getStyle();

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

    @LayoutlibDelegate
    /*package*/ static void native_drawBitmap(Canvas thisCanvas, long nativeCanvas, Bitmap bitmap,
                                                 float left, float top,
                                                 long nativePaintOrZero,
                                                 int canvasDensity,
                                                 int screenDensity,
                                                 int bitmapDensity) {
        // get the delegate from the native int.
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (bitmapDelegate == null) {
            return;
        }

        BufferedImage image = bitmapDelegate.getImage();
        float right = left + image.getWidth();
        float bottom = top + image.getHeight();

        drawBitmap(nativeCanvas, bitmapDelegate, nativePaintOrZero,
                0, 0, image.getWidth(), image.getHeight(),
                (int)left, (int)top, (int)right, (int)bottom);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawBitmap(Canvas thisCanvas, long nativeCanvas, Bitmap bitmap,
                                 float srcLeft, float srcTop, float srcRight, float srcBottom,
                                 float dstLeft, float dstTop, float dstRight, float dstBottom,
                                 long nativePaintOrZero, int screenDensity, int bitmapDensity) {
        // get the delegate from the native int.
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (bitmapDelegate == null) {
            return;
        }

        drawBitmap(nativeCanvas, bitmapDelegate, nativePaintOrZero,
                (int)srcLeft, (int)srcTop, (int)srcRight, (int)srcBottom,
                (int)dstLeft, (int)dstTop, (int)dstRight, (int)dstBottom);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawBitmap(long nativeCanvas, int[] colors,
                                                int offset, int stride, final float x,
                                                 final float y, int width, int height,
                                                 boolean hasAlpha,
                                                 long nativePaintOrZero) {
        // create a temp BufferedImage containing the content.
        final BufferedImage image = new BufferedImage(width, height,
                hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, colors, offset, stride);

        draw(nativeCanvas, nativePaintOrZero, true /*compositeOnly*/, false /*forceSrcMode*/,
                new GcSnapshot.Drawable() {
                    @Override
                    public void draw(Graphics2D graphics, Paint_Delegate paint) {
                        if (paint != null && paint.isFilterBitmap()) {
                            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        }

                        graphics.drawImage(image, (int) x, (int) y, null);
                    }
        });
    }

    @LayoutlibDelegate
    /*package*/ static void nativeDrawBitmapMatrix(long nCanvas, Bitmap bitmap,
                                                      long nMatrix, long nPaint) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            return;
        }

        // get the delegate from the native int, which can be null
        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(nPaint);

        // get the delegate from the native int.
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (bitmapDelegate == null) {
            return;
        }

        final BufferedImage image = getImageToDraw(bitmapDelegate, paintDelegate, sBoolOut);

        Matrix_Delegate matrixDelegate = Matrix_Delegate.getDelegate(nMatrix);
        if (matrixDelegate == null) {
            return;
        }

        final AffineTransform mtx = matrixDelegate.getAffineTransform();

        canvasDelegate.getSnapshot().draw(new GcSnapshot.Drawable() {
                @Override
                public void draw(Graphics2D graphics, Paint_Delegate paint) {
                    if (paint != null && paint.isFilterBitmap()) {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    }

                    //FIXME add support for canvas, screen and bitmap densities.
                    graphics.drawImage(image, mtx, null);
                }
        }, paintDelegate, true /*compositeOnly*/, false /*forceSrcMode*/);
    }

    @LayoutlibDelegate
    /*package*/ static void nativeDrawBitmapMesh(long nCanvas, Bitmap bitmap,
            int meshWidth, int meshHeight, float[] verts, int vertOffset, int[] colors,
            int colorOffset, long nPaint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawBitmapMesh is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void nativeDrawVertices(long nCanvas, int mode, int n,
            float[] verts, int vertOffset,
            float[] texs, int texOffset,
            int[] colors, int colorOffset,
            short[] indices, int indexOffset,
            int indexCount, long nPaint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawVertices is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawText(long nativeCanvas, char[] text, int index, int count,
            float startX, float startY, int flags, long paint, long typeface) {
        drawText(nativeCanvas, text, index, count, startX, startY, (flags & 1) != 0,
                paint, typeface);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawText(long nativeCanvas, String text,
            int start, int end, float x, float y, final int flags, long paint,
            long typeface) {
        int count = end - start;
        char[] buffer = TemporaryBuffer.obtain(count);
        TextUtils.getChars(text, start, end, buffer, 0);

        native_drawText(nativeCanvas, buffer, 0, count, x, y, flags, paint, typeface);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawTextRun(long nativeCanvas, String text,
            int start, int end, int contextStart, int contextEnd,
            float x, float y, boolean isRtl, long paint, long typeface) {
        int count = end - start;
        char[] buffer = TemporaryBuffer.obtain(count);
        TextUtils.getChars(text, start, end, buffer, 0);

        drawText(nativeCanvas, buffer, 0, count, x, y, isRtl, paint, typeface);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawTextRun(long nativeCanvas, char[] text,
            int start, int count, int contextStart, int contextCount,
            float x, float y, boolean isRtl, long paint, long typeface) {
        drawText(nativeCanvas, text, start, count, x, y, isRtl, paint, typeface);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawTextOnPath(long nativeCanvas,
                                                     char[] text, int index,
                                                     int count, long path,
                                                     float hOffset,
                                                     float vOffset, int bidiFlags,
                                                     long paint, long typeface) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawTextOnPath is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawTextOnPath(long nativeCanvas,
                                                     String text, long path,
                                                     float hOffset,
                                                     float vOffset,
                                                     int bidiFlags, long paint,
                                                     long typeface) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawTextOnPath is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void finalizer(long nativeCanvas) {
        // get the delegate from the native int so that it can be disposed.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.dispose();

        // remove it from the manager.
        sManager.removeJavaReferenceFor(nativeCanvas);
    }


    // ---- Private delegate/helper methods ----

    /**
     * Executes a {@link GcSnapshot.Drawable} with a given canvas and paint.
     * <p>Note that the drawable may actually be executed several times if there are
     * layers involved (see {@link #saveLayer(RectF, Paint_Delegate, int)}.
     */
    private static void draw(long nCanvas, long nPaint, boolean compositeOnly, boolean forceSrcMode,
            GcSnapshot.Drawable drawable) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            return;
        }

        // get the paint which can be null if nPaint is 0;
        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(nPaint);

        canvasDelegate.getSnapshot().draw(drawable, paintDelegate, compositeOnly, forceSrcMode);
    }

    /**
     * Executes a {@link GcSnapshot.Drawable} with a given canvas. No paint object will be provided
     * to {@link GcSnapshot.Drawable#draw(Graphics2D, Paint_Delegate)}.
     * <p>Note that the drawable may actually be executed several times if there are
     * layers involved (see {@link #saveLayer(RectF, Paint_Delegate, int)}.
     */
    private static void draw(long nCanvas, GcSnapshot.Drawable drawable) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.mSnapshot.draw(drawable);
    }

    private static void drawText(long nativeCanvas, final char[] text, final int index,
            final int count, final float startX, final float startY, final boolean isRtl,
            long paint, final long typeface) {

        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                new GcSnapshot.Drawable() {
            @Override
            public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                // WARNING: the logic in this method is similar to Paint_Delegate.measureText.
                // Any change to this method should be reflected in Paint.measureText

                // assert that the typeface passed is actually the one stored in paint.
                assert (typeface == paintDelegate.mNativeTypeface);

                // Paint.TextAlign indicates how the text is positioned relative to X.
                // LEFT is the default and there's nothing to do.
                float x = startX;
                int limit = index + count;
                if (paintDelegate.getTextAlign() != Paint.Align.LEFT.nativeInt) {
                    RectF bounds = paintDelegate.measureText(text, index, count, null, 0,
                            isRtl);
                    float m = bounds.right - bounds.left;
                    if (paintDelegate.getTextAlign() == Paint.Align.CENTER.nativeInt) {
                        x -= m / 2;
                    } else if (paintDelegate.getTextAlign() == Paint.Align.RIGHT.nativeInt) {
                        x -= m;
                    }
                }

                new BidiRenderer(graphics, paintDelegate, text).setRenderLocation(x, startY)
                        .renderText(index, limit, isRtl, null, 0, true);
            }
        });
    }

    private Canvas_Delegate(Bitmap_Delegate bitmap) {
        mSnapshot = GcSnapshot.createDefaultSnapshot(mBitmap = bitmap);
    }

    private Canvas_Delegate() {
        mSnapshot = GcSnapshot.createDefaultSnapshot(null /*image*/);
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

        mSnapshot = mSnapshot.save(saveFlags);

        // return the old save count
        return count;
    }

    private int saveLayerAlpha(RectF rect, int alpha, int saveFlags) {
        Paint_Delegate paint = new Paint_Delegate();
        paint.setAlpha(alpha);
        return saveLayer(rect, paint, saveFlags);
    }

    private int saveLayer(RectF rect, Paint_Delegate paint, int saveFlags) {
        // get the current save count
        int count = mSnapshot.size();

        mSnapshot = mSnapshot.saveLayer(rect, paint, saveFlags);

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
     * Restores the top {@link GcSnapshot}
     */
    private void restore() {
        mSnapshot = mSnapshot.restore();
    }

    private boolean clipRect(float left, float top, float right, float bottom, int regionOp) {
        return mSnapshot.clipRect(left, top, right, bottom, regionOp);
    }

    private static void drawBitmap(
            long nativeCanvas,
            Bitmap_Delegate bitmap,
            long nativePaintOrZero,
            final int sleft, final int stop, final int sright, final int sbottom,
            final int dleft, final int dtop, final int dright, final int dbottom) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        // get the paint, which could be null if the int is 0
        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(nativePaintOrZero);

        final BufferedImage image = getImageToDraw(bitmap, paintDelegate, sBoolOut);

        draw(nativeCanvas, nativePaintOrZero, true /*compositeOnly*/, sBoolOut[0],
                new GcSnapshot.Drawable() {
                    @Override
                    public void draw(Graphics2D graphics, Paint_Delegate paint) {
                        if (paint != null && paint.isFilterBitmap()) {
                            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        }

                        //FIXME add support for canvas, screen and bitmap densities.
                        graphics.drawImage(image, dleft, dtop, dright, dbottom,
                                sleft, stop, sright, sbottom, null);
                    }
        });
    }


    /**
     * Returns a BufferedImage ready for drawing, based on the bitmap and paint delegate.
     * The image returns, through a 1-size boolean array, whether the drawing code should
     * use a SRC composite no matter what the paint says.
     *
     * @param bitmap the bitmap
     * @param paint the paint that will be used to draw
     * @param forceSrcMode whether the composite will have to be SRC
     * @return the image to draw
     */
    private static BufferedImage getImageToDraw(Bitmap_Delegate bitmap, Paint_Delegate paint,
            boolean[] forceSrcMode) {
        BufferedImage image = bitmap.getImage();
        forceSrcMode[0] = false;

        // if the bitmap config is alpha_8, then we erase all color value from it
        // before drawing it.
        if (bitmap.getConfig() == Bitmap.Config.ALPHA_8) {
            fixAlpha8Bitmap(image);
        } else if (!bitmap.hasAlpha()) {
            // hasAlpha is merely a rendering hint. There can in fact be alpha values
            // in the bitmap but it should be ignored at drawing time.
            // There is two ways to do this:
            // - override the composite to be SRC. This can only be used if the composite
            //   was going to be SRC or SRC_OVER in the first place
            // - Create a different bitmap to draw in which all the alpha channel values is set
            //   to 0xFF.
            if (paint != null) {
                Xfermode_Delegate xfermodeDelegate = paint.getXfermode();
                if (xfermodeDelegate instanceof PorterDuffXfermode_Delegate) {
                    PorterDuff.Mode mode =
                        ((PorterDuffXfermode_Delegate)xfermodeDelegate).getMode();

                    forceSrcMode[0] = mode == PorterDuff.Mode.SRC_OVER ||
                            mode == PorterDuff.Mode.SRC;
                }
            }

            // if we can't force SRC mode, then create a temp bitmap of TYPE_RGB
            if (!forceSrcMode[0]) {
                image = Bitmap_Delegate.createCopy(image, BufferedImage.TYPE_INT_RGB, 0xFF);
            }
        }

        return image;
    }

    private static void fixAlpha8Bitmap(final BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] argb = new int[w * h];
        image.getRGB(0, 0, image.getWidth(), image.getHeight(), argb, 0, image.getWidth());

        final int length = argb.length;
        for (int i = 0 ; i < length; i++) {
            argb[i] &= 0xFF000000;
        }
        image.setRGB(0, 0, w, h, argb, 0, w);
    }
}

