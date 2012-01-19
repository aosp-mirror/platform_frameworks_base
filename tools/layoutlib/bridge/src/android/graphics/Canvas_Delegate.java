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
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.graphics.Bitmap.Config;
import android.graphics.Paint_Delegate.FontInfo;
import android.text.TextUtils;

import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
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
    /*package*/ static boolean isOpaque(Canvas thisCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            return false;
        }

        return canvasDelegate.mBitmap.getConfig() == Config.RGB_565;
    }

    @LayoutlibDelegate
    /*package*/ static int getWidth(Canvas thisCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.mBitmap.getImage().getWidth();
    }

    @LayoutlibDelegate
    /*package*/ static int getHeight(Canvas thisCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.mBitmap.getImage().getHeight();
    }

    @LayoutlibDelegate
   /*package*/ static void translate(Canvas thisCanvas, float dx, float dy) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.getSnapshot().translate(dx, dy);
    }

    @LayoutlibDelegate
    /*package*/ static void rotate(Canvas thisCanvas, float degrees) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.getSnapshot().rotate(Math.toRadians(degrees));
    }

    @LayoutlibDelegate
   /*package*/ static void scale(Canvas thisCanvas, float sx, float sy) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.getSnapshot().scale(sx, sy);
    }

    @LayoutlibDelegate
   /*package*/ static void skew(Canvas thisCanvas, float kx, float ky) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
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
    /*package*/ static boolean clipRect(Canvas thisCanvas, RectF rect) {
        return clipRect(thisCanvas, rect.left, rect.top, rect.right, rect.bottom);
    }

    @LayoutlibDelegate
    /*package*/ static boolean clipRect(Canvas thisCanvas, Rect rect) {
        return clipRect(thisCanvas, (float) rect.left, (float) rect.top,
                (float) rect.right, (float) rect.bottom);
    }

    @LayoutlibDelegate
    /*package*/ static boolean clipRect(Canvas thisCanvas, float left, float top, float right,
            float bottom) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            return false;
        }

        return canvasDelegate.clipRect(left, top, right, bottom, Region.Op.INTERSECT.nativeInt);
    }

    @LayoutlibDelegate
    /*package*/ static boolean clipRect(Canvas thisCanvas, int left, int top, int right,
            int bottom) {

        return clipRect(thisCanvas, (float) left, (float) top, (float) right, (float) bottom);
    }

    @LayoutlibDelegate
    /*package*/ static int save(Canvas thisCanvas) {
        return save(thisCanvas, Canvas.MATRIX_SAVE_FLAG | Canvas.CLIP_SAVE_FLAG);
    }

    @LayoutlibDelegate
    /*package*/ static int save(Canvas thisCanvas, int saveFlags) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.save(saveFlags);
    }

    @LayoutlibDelegate
    /*package*/ static void restore(Canvas thisCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.restore();
    }

    @LayoutlibDelegate
    /*package*/ static int getSaveCount(Canvas thisCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.getSnapshot().size();
    }

    @LayoutlibDelegate
    /*package*/ static void restoreToCount(Canvas thisCanvas, int saveCount) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(thisCanvas.mNativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.restoreTo(saveCount);
    }

    @LayoutlibDelegate
    /*package*/ static void drawPoints(Canvas thisCanvas, float[] pts, int offset, int count,
            Paint paint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawPoint is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void drawPoint(Canvas thisCanvas, float x, float y, Paint paint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawPoint is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void drawLines(Canvas thisCanvas,
            final float[] pts, final int offset, final int count,
            Paint paint) {
        draw(thisCanvas.mNativeCanvas, paint.mNativePaint, false /*compositeOnly*/,
                false /*forceSrcMode*/, new GcSnapshot.Drawable() {
                    public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                        for (int i = 0 ; i < count ; i += 4) {
                            graphics.drawLine((int)pts[i + offset], (int)pts[i + offset + 1],
                                    (int)pts[i + offset + 2], (int)pts[i + offset + 3]);
                        }
                    }
                });
    }

    @LayoutlibDelegate
    /*package*/ static void freeCaches() {
        // nothing to be done here.
    }

    @LayoutlibDelegate
    /*package*/ static int initRaster(int nativeBitmapOrZero) {
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
    /*package*/ static void native_setBitmap(int nativeCanvas, int bitmap) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        // get the delegate from the native int.
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (bitmapDelegate == null) {
            return;
        }

        canvasDelegate.setBitmap(bitmapDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static int native_saveLayer(int nativeCanvas, RectF bounds,
                                               int paint, int layerFlags) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(paint);
        if (paintDelegate == null) {
            return 0;
        }

        return canvasDelegate.saveLayer(bounds, paintDelegate, layerFlags);
    }

    @LayoutlibDelegate
    /*package*/ static int native_saveLayer(int nativeCanvas, float l,
                                               float t, float r, float b,
                                               int paint, int layerFlags) {
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
    /*package*/ static int native_saveLayerAlpha(int nativeCanvas,
                                                    RectF bounds, int alpha,
                                                    int layerFlags) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.saveLayerAlpha(bounds, alpha, layerFlags);
    }

    @LayoutlibDelegate
    /*package*/ static int native_saveLayerAlpha(int nativeCanvas, float l,
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
    /*package*/ static void native_concat(int nCanvas, int nMatrix) {
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
    /*package*/ static void native_setMatrix(int nCanvas, int nMatrix) {
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
    /*package*/ static boolean native_clipRect(int nCanvas,
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
    /*package*/ static boolean native_clipPath(int nativeCanvas,
                                                  int nativePath,
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
    /*package*/ static boolean native_clipRegion(int nativeCanvas,
                                                    int nativeRegion,
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
    /*package*/ static void nativeSetDrawFilter(int nativeCanvas, int nativeFilter) {
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.mDrawFilter = DrawFilter_Delegate.getDelegate(nativeFilter);

        if (canvasDelegate.mDrawFilter != null &&
                canvasDelegate.mDrawFilter.isSupported() == false) {
            Bridge.getLog().fidelityWarning(LayoutLog.TAG_DRAWFILTER,
                    canvasDelegate.mDrawFilter.getSupportMessage(), null, null /*data*/);
        }
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_getClipBounds(int nativeCanvas,
                                                       Rect bounds) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return false;
        }

        Rectangle rect = canvasDelegate.getSnapshot().getClip().getBounds();
        if (rect != null && rect.isEmpty() == false) {
            bounds.left = rect.x;
            bounds.top = rect.y;
            bounds.right = rect.x + rect.width;
            bounds.bottom = rect.y + rect.height;
            return true;
        }

        return false;
    }

    @LayoutlibDelegate
    /*package*/ static void native_getCTM(int canvas, int matrix) {
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
    /*package*/ static boolean native_quickReject(int nativeCanvas,
                                                     RectF rect,
                                                     int native_edgeType) {
        // FIXME properly implement quickReject
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_quickReject(int nativeCanvas,
                                                     int path,
                                                     int native_edgeType) {
        // FIXME properly implement quickReject
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_quickReject(int nativeCanvas,
                                                     float left, float top,
                                                     float right, float bottom,
                                                     int native_edgeType) {
        // FIXME properly implement quickReject
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawRGB(int nativeCanvas, int r, int g, int b) {
        native_drawColor(nativeCanvas, 0xFF000000 | r << 16 | (g&0xFF) << 8 | (b&0xFF),
                PorterDuff.Mode.SRC_OVER.nativeInt);

    }

    @LayoutlibDelegate
    /*package*/ static void native_drawARGB(int nativeCanvas, int a, int r, int g, int b) {
        native_drawColor(nativeCanvas, a << 24 | (r&0xFF) << 16 | (g&0xFF) << 8 | (b&0xFF),
                PorterDuff.Mode.SRC_OVER.nativeInt);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawColor(int nativeCanvas, int color) {
        native_drawColor(nativeCanvas, color, PorterDuff.Mode.SRC_OVER.nativeInt);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawColor(int nativeCanvas, final int color, final int mode) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        final int w = canvasDelegate.mBitmap.getImage().getWidth();
        final int h = canvasDelegate.mBitmap.getImage().getHeight();
        draw(nativeCanvas, new GcSnapshot.Drawable() {

            public void draw(Graphics2D graphics, Paint_Delegate paint) {
                // reset its transform just in case
                graphics.setTransform(new AffineTransform());

                // set the color
                graphics.setColor(new Color(color, true /*alpha*/));

                Composite composite = PorterDuffXfermode_Delegate.getComposite(
                        PorterDuffXfermode_Delegate.getPorterDuffMode(mode), 0xFF);
                if (composite != null) {
                    graphics.setComposite(composite);
                }

                graphics.fillRect(0, 0, w, h);
            }
        });
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawPaint(int nativeCanvas, int paint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawPaint is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawLine(int nativeCanvas,
            final float startX, final float startY, final float stopX, final float stopY,
            int paint) {

        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                new GcSnapshot.Drawable() {
                    public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                        graphics.drawLine((int)startX, (int)startY, (int)stopX, (int)stopY);
                    }
        });
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawRect(int nativeCanvas, RectF rect,
                                               int paint) {
        native_drawRect(nativeCanvas, rect.left, rect.top, rect.right, rect.bottom, paint);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawRect(int nativeCanvas,
            final float left, final float top, final float right, final float bottom, int paint) {

        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                new GcSnapshot.Drawable() {
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
    /*package*/ static void native_drawOval(int nativeCanvas, final RectF oval, int paint) {
        if (oval.right > oval.left && oval.bottom > oval.top) {
            draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                    new GcSnapshot.Drawable() {
                        public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                            int style = paintDelegate.getStyle();

                            // draw
                            if (style == Paint.Style.FILL.nativeInt ||
                                    style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                                graphics.fillOval((int)oval.left, (int)oval.top,
                                        (int)oval.width(), (int)oval.height());
                            }

                            if (style == Paint.Style.STROKE.nativeInt ||
                                    style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                                graphics.drawOval((int)oval.left, (int)oval.top,
                                        (int)oval.width(), (int)oval.height());
                            }
                        }
            });
        }
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawCircle(int nativeCanvas,
            float cx, float cy, float radius, int paint) {
        native_drawOval(nativeCanvas,
                new RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                paint);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawArc(int nativeCanvas,
            final RectF oval, final float startAngle, final float sweep,
            final boolean useCenter, int paint) {
        if (oval.right > oval.left && oval.bottom > oval.top) {
            draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                    new GcSnapshot.Drawable() {
                        public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                            int style = paintDelegate.getStyle();

                            Arc2D.Float arc = new Arc2D.Float(
                                    oval.left, oval.top, oval.width(), oval.height(),
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
    /*package*/ static void native_drawRoundRect(int nativeCanvas,
            final RectF rect, final float rx, final float ry, int paint) {

        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                new GcSnapshot.Drawable() {
                    public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                        int style = paintDelegate.getStyle();

                        // draw
                        if (style == Paint.Style.FILL.nativeInt ||
                                style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                            graphics.fillRoundRect(
                                    (int)rect.left, (int)rect.top,
                                    (int)rect.width(), (int)rect.height(),
                                    (int)rx, (int)ry);
                        }

                        if (style == Paint.Style.STROKE.nativeInt ||
                                style == Paint.Style.FILL_AND_STROKE.nativeInt) {
                            graphics.drawRoundRect(
                                    (int)rect.left, (int)rect.top,
                                    (int)rect.width(), (int)rect.height(),
                                    (int)rx, (int)ry);
                        }
                    }
        });
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawPath(int nativeCanvas, int path, int paint) {
        final Path_Delegate pathDelegate = Path_Delegate.getDelegate(path);
        if (pathDelegate == null) {
            return;
        }

        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                new GcSnapshot.Drawable() {
                    public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                        Shape shape = pathDelegate.getJavaShape();
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
    /*package*/ static void native_drawBitmap(Canvas thisCanvas, int nativeCanvas, int bitmap,
                                                 float left, float top,
                                                 int nativePaintOrZero,
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
    /*package*/ static void native_drawBitmap(Canvas thisCanvas, int nativeCanvas, int bitmap,
                                                 Rect src, RectF dst,
                                                 int nativePaintOrZero,
                                                 int screenDensity,
                                                 int bitmapDensity) {
        // get the delegate from the native int.
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (bitmapDelegate == null) {
            return;
        }

        BufferedImage image = bitmapDelegate.getImage();

        if (src == null) {
            drawBitmap(nativeCanvas, bitmapDelegate, nativePaintOrZero,
                    0, 0, image.getWidth(), image.getHeight(),
                    (int)dst.left, (int)dst.top, (int)dst.right, (int)dst.bottom);
        } else {
            drawBitmap(nativeCanvas, bitmapDelegate, nativePaintOrZero,
                    src.left, src.top, src.width(), src.height(),
                    (int)dst.left, (int)dst.top, (int)dst.right, (int)dst.bottom);
        }
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawBitmap(int nativeCanvas, int bitmap,
                                                 Rect src, Rect dst,
                                                 int nativePaintOrZero,
                                                 int screenDensity,
                                                 int bitmapDensity) {
        // get the delegate from the native int.
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (bitmapDelegate == null) {
            return;
        }

        BufferedImage image = bitmapDelegate.getImage();

        if (src == null) {
            drawBitmap(nativeCanvas, bitmapDelegate, nativePaintOrZero,
                    0, 0, image.getWidth(), image.getHeight(),
                    dst.left, dst.top, dst.right, dst.bottom);
        } else {
            drawBitmap(nativeCanvas, bitmapDelegate, nativePaintOrZero,
                    src.left, src.top, src.width(), src.height(),
                    dst.left, dst.top, dst.right, dst.bottom);
        }
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawBitmap(int nativeCanvas, int[] colors,
                                                int offset, int stride, final float x,
                                                 final float y, int width, int height,
                                                 boolean hasAlpha,
                                                 int nativePaintOrZero) {

        // create a temp BufferedImage containing the content.
        final BufferedImage image = new BufferedImage(width, height,
                hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, colors, offset, stride);

        draw(nativeCanvas, nativePaintOrZero, true /*compositeOnly*/, false /*forceSrcMode*/,
                new GcSnapshot.Drawable() {
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
    /*package*/ static void nativeDrawBitmapMatrix(int nCanvas, int nBitmap,
                                                      int nMatrix, int nPaint) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            return;
        }

        // get the delegate from the native int, which can be null
        Paint_Delegate paintDelegate = Paint_Delegate.getDelegate(nPaint);

        // get the delegate from the native int.
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(nBitmap);
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
    /*package*/ static void nativeDrawBitmapMesh(int nCanvas, int nBitmap,
            int meshWidth, int meshHeight, float[] verts, int vertOffset, int[] colors,
            int colorOffset, int nPaint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawBitmapMesh is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void nativeDrawVertices(int nCanvas, int mode, int n,
            float[] verts, int vertOffset,
            float[] texs, int texOffset,
            int[] colors, int colorOffset,
            short[] indices, int indexOffset,
            int indexCount, int nPaint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawVertices is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawText(int nativeCanvas,
            final char[] text, final int index, final int count,
            final float startX, final float startY, int flags, int paint) {
        draw(nativeCanvas, paint, false /*compositeOnly*/, false /*forceSrcMode*/,
                new GcSnapshot.Drawable() {
            public void draw(Graphics2D graphics, Paint_Delegate paintDelegate) {
                // WARNING: the logic in this method is similar to Paint_Delegate.measureText.
                // Any change to this method should be reflected in Paint.measureText
                // Paint.TextAlign indicates how the text is positioned relative to X.
                // LEFT is the default and there's nothing to do.
                float x = startX;
                float y = startY;
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
                            graphics.setFont(mainFont.mFont);
                            graphics.drawChars(text, i, lastIndex - i, (int)x, (int)y);
                            return;
                        } else if (upTo > 0) {
                            // draw what's possible
                            graphics.setFont(mainFont.mFont);
                            graphics.drawChars(text, i, upTo - i, (int)x, (int)y);

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
                                graphics.setFont(fontInfo.mFont);
                                graphics.drawChars(text, i, charCount, (int)x, (int)y);

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

                            graphics.setFont(mainFont.mFont);
                            graphics.drawChars(text, i, charCount, (int)x, (int)y);

                            // measure it to advance x
                            x += mainFont.mMetrics.charsWidth(text, i, charCount);

                            // and move to the next chars.
                            i += charCount;
                        }
                    }
                }
            }
        });
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawText(int nativeCanvas, String text,
            int start, int end, float x, float y, int flags, int paint) {
        int count = end - start;
        char[] buffer = TemporaryBuffer.obtain(count);
        TextUtils.getChars(text, start, end, buffer, 0);

        native_drawText(nativeCanvas, buffer, 0, count, x, y, flags, paint);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawTextRun(int nativeCanvas, String text,
            int start, int end, int contextStart, int contextEnd,
            float x, float y, int flags, int paint) {
        int count = end - start;
        char[] buffer = TemporaryBuffer.obtain(count);
        TextUtils.getChars(text, start, end, buffer, 0);

        native_drawText(nativeCanvas, buffer, 0, count, x, y, flags, paint);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawTextRun(int nativeCanvas, char[] text,
            int start, int count, int contextStart, int contextCount,
            float x, float y, int flags, int paint) {
        native_drawText(nativeCanvas, text, start, count, x, y, flags, paint);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawPosText(int nativeCanvas,
                                                  char[] text, int index,
                                                  int count, float[] pos,
                                                  int paint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawPosText is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawPosText(int nativeCanvas,
                                                  String text, float[] pos,
                                                  int paint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawPosText is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawTextOnPath(int nativeCanvas,
                                                     char[] text, int index,
                                                     int count, int path,
                                                     float hOffset,
                                                     float vOffset, int bidiFlags,
                                                     int paint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawTextOnPath is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawTextOnPath(int nativeCanvas,
                                                     String text, int path,
                                                     float hOffset,
                                                     float vOffset,
                                                     int flags, int paint) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawTextOnPath is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void native_drawPicture(int nativeCanvas,
                                                  int nativePicture) {
        // FIXME
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Canvas.drawPicture is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void finalizer(int nativeCanvas) {
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
     * layers involved (see {@link #saveLayer(RectF, int, int)}.
     */
    private static void draw(int nCanvas, int nPaint, boolean compositeOnly, boolean forceSrcMode,
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
     * layers involved (see {@link #saveLayer(RectF, int, int)}.
     */
    private static void draw(int nCanvas, GcSnapshot.Drawable drawable) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = sManager.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.mSnapshot.draw(drawable);
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
     * Restores the {@link GcSnapshot} to <var>saveCount</var>
     * @param saveCount the saveCount
     */
    private void restore() {
        mSnapshot = mSnapshot.restore();
    }

    private boolean clipRect(float left, float top, float right, float bottom, int regionOp) {
        return mSnapshot.clipRect(left, top, right, bottom, regionOp);
    }

    private void setBitmap(Bitmap_Delegate bitmap) {
        mBitmap = bitmap;
        assert mSnapshot.size() == 1;
        mSnapshot.setBitmap(mBitmap);
    }

    private static void drawBitmap(
            int nativeCanvas,
            Bitmap_Delegate bitmap,
            int nativePaintOrZero,
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
        } else if (bitmap.hasAlpha() == false) {
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
            if (forceSrcMode[0] == false) {
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

