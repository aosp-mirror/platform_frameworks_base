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

import com.android.layoutlib.api.ILayoutLog;
import com.android.layoutlib.bridge.DelegateManager;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Stack;

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
public class Canvas_Delegate {

    // ---- delegate manager ----
    private static final DelegateManager<Canvas_Delegate> sManager =
            new DelegateManager<Canvas_Delegate>();

    // ---- delegate helper data ----

    // ---- delegate data ----
    private BufferedImage mBufferedImage;
    private final Stack<Graphics2D> mGraphicsStack = new Stack<Graphics2D>();
    private ILayoutLog mLogger;

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
     * Sets the layoutlib logger into the canvas.
     * @param logger
     */
    public void setLogger(ILayoutLog logger) {
        mLogger = logger;
    }

    /**
     * Returns the current {@link Graphics2D} used to draw.
     */
    public Graphics2D getGraphics2d() {
        return mGraphicsStack.peek();
    }

    /**
     * Disposes of the {@link Graphics2D} stack.
     */
    public void dispose() {

    }

    // ---- native methods ----

    /*package*/ static boolean isOpaque(Canvas thisCanvas) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int getWidth(Canvas thisCanvas) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int getHeight(Canvas thisCanvas) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void translate(Canvas thisCanvas, float dx, float dy) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void rotate(Canvas thisCanvas, float degrees) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void scale(Canvas thisCanvas, float sx, float sy) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void skew(Canvas thisCanvas, float sx, float sy) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static boolean clipRect(Canvas thisCanvas, RectF rect) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static boolean clipRect(Canvas thisCanvas, Rect rect) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static boolean clipRect(Canvas thisCanvas, float left, float top, float right,
            float bottom) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static boolean clipRect(Canvas thisCanvas, int left, int top, int right,
            int bottom) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int save(Canvas thisCanvas) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int save(Canvas thisCanvas, int saveFlags) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void restore(Canvas thisCanvas) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int getSaveCount(Canvas thisCanvas) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void restoreToCount(Canvas thisCanvas, int saveCount) {
        // FIXME
        throw new UnsupportedOperationException();
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
        // FIXME
        throw new UnsupportedOperationException();
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
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_setMatrix(int nCanvas, int nMatrix) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static boolean native_clipRect(int nCanvas,
                                                  float left, float top,
                                                  float right, float bottom,
                                                  int regionOp) {
        // FIXME
        throw new UnsupportedOperationException();
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
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_getCTM(int canvas, int matrix) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static boolean native_quickReject(int nativeCanvas,
                                                     RectF rect,
                                                     int native_edgeType) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static boolean native_quickReject(int nativeCanvas,
                                                     int path,
                                                     int native_edgeType) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static boolean native_quickReject(int nativeCanvas,
                                                     float left, float top,
                                                     float right, float bottom,
                                                     int native_edgeType) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawRGB(int nativeCanvas, int r, int g,
                                              int b) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawARGB(int nativeCanvas, int a, int r,
                                               int g, int b) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawColor(int nativeCanvas, int color) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawColor(int nativeCanvas, int color,
                                                int mode) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawPaint(int nativeCanvas, int paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawLine(int nativeCanvas, float startX,
                                               float startY, float stopX,
                                               float stopY, int paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawRect(int nativeCanvas, RectF rect,
                                               int paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawRect(int nativeCanvas, float left,
                                               float top, float right,
                                               float bottom, int paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawOval(int nativeCanvas, RectF oval,
                                               int paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawCircle(int nativeCanvas, float cx,
                                                 float cy, float radius,
                                                 int paint) {
        // FIXME
        throw new UnsupportedOperationException();
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
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawPath(int nativeCanvas, int path,
                                               int paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawBitmap(Canvas thisCanvas, int nativeCanvas, int bitmap,
                                                 float left, float top,
                                                 int nativePaintOrZero,
                                                 int canvasDensity,
                                                 int screenDensity,
                                                 int bitmapDensity) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawBitmap(Canvas thisCanvas, int nativeCanvas, int bitmap,
                                                 Rect src, RectF dst,
                                                 int nativePaintOrZero,
                                                 int screenDensity,
                                                 int bitmapDensity) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawBitmap(int nativeCanvas, int bitmap,
                                                 Rect src, Rect dst,
                                                 int nativePaintOrZero,
                                                 int screenDensity,
                                                 int bitmapDensity) {
        // FIXME
        throw new UnsupportedOperationException();
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
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_drawText(int nativeCanvas, String text,
                                               int start, int end, float x,
                                               float y, int flags, int paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }


    /*package*/ static void native_drawTextRun(int nativeCanvas, String text,
            int start, int end, int contextStart, int contextEnd,
            float x, float y, int flags, int paint) {
        // FIXME
        throw new UnsupportedOperationException();
    }


    /*package*/ static void native_drawTextRun(int nativeCanvas, char[] text,
            int start, int count, int contextStart, int contextCount,
            float x, float y, int flags, int paint) {
        // FIXME
        throw new UnsupportedOperationException();
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
        sManager.removeDelegate(nativeCanvas);
    }

    // ---- Private delegate/helper methods ----

    private Canvas_Delegate(BufferedImage image) {
        setBitmap(image);
    }

    private Canvas_Delegate() {
    }

    private void setBitmap(BufferedImage image) {
        mBufferedImage = image;
        mGraphicsStack.push(mBufferedImage.createGraphics());
    }
}
