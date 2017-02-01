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

import android.annotation.Nullable;
import android.graphics.Bitmap.Config;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;

import libcore.util.NativeAllocationRegistry_Delegate;


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
public final class Canvas_Delegate extends BaseCanvas_Delegate {

    // ---- delegate manager ----
    private static long sFinalizer = -1;

    private DrawFilter_Delegate mDrawFilter = null;

    // ---- Public Helper methods ----

    /**
     * Returns the native delegate associated to a given {@link Canvas} object.
     */
    public static Canvas_Delegate getDelegate(Canvas canvas) {
        return (Canvas_Delegate) sManager.getDelegate(canvas.getNativeCanvasWrapper());
    }

    /**
     * Returns the native delegate associated to a given an int referencing a {@link Canvas} object.
     */
    public static Canvas_Delegate getDelegate(long native_canvas) {
        return (Canvas_Delegate) sManager.getDelegate(native_canvas);
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
    /*package*/ static void nFreeCaches() {
        // nothing to be done here.
    }

    @LayoutlibDelegate
    /*package*/ static void nFreeTextLayoutCaches() {
        // nothing to be done here yet.
    }

    @LayoutlibDelegate
    /*package*/ static long nInitRaster(@Nullable Bitmap bitmap) {
        long nativeBitmapOrZero = 0;
        if (bitmap != null) {
            nativeBitmapOrZero = bitmap.getNativeInstance();
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
    public static void nSetBitmap(long canvas, Bitmap bitmap) {
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(canvas);
        Bitmap_Delegate bitmapDelegate = Bitmap_Delegate.getDelegate(bitmap);
        if (canvasDelegate == null || bitmapDelegate==null) {
            return;
        }
        canvasDelegate.mBitmap = bitmapDelegate;
        canvasDelegate.mSnapshot = GcSnapshot.createDefaultSnapshot(bitmapDelegate);
    }

    @LayoutlibDelegate
    public static boolean nIsOpaque(long nativeCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return false;
        }

        return canvasDelegate.mBitmap.getConfig() == Config.RGB_565;
    }

    @LayoutlibDelegate
    public static void nSetHighContrastText(long nativeCanvas, boolean highContrastText){}

    @LayoutlibDelegate
    public static int nGetWidth(long nativeCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.mBitmap.getImage().getWidth();
    }

    @LayoutlibDelegate
    public static int nGetHeight(long nativeCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.mBitmap.getImage().getHeight();
    }

    @LayoutlibDelegate
    public static int nSave(long nativeCanvas, int saveFlags) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.save(saveFlags);
    }

    @LayoutlibDelegate
    public static int nSaveLayer(long nativeCanvas, float l,
                                               float t, float r, float b,
                                               long paint, int layerFlags) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
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
    public static int nSaveLayerAlpha(long nativeCanvas, float l,
                                                    float t, float r, float b,
                                                    int alpha, int layerFlags) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.saveLayerAlpha(new RectF(l, t, r, b), alpha, layerFlags);
    }

    @LayoutlibDelegate
    public static boolean nRestore(long nativeCanvas) {
        // FIXME: implement throwOnUnderflow.
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return false;
        }

        canvasDelegate.restore();
        return true;
    }

    @LayoutlibDelegate
    public static void nRestoreToCount(long nativeCanvas, int saveCount) {
        // FIXME: implement throwOnUnderflow.
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.restoreTo(saveCount);
    }

    @LayoutlibDelegate
    public static int nGetSaveCount(long nativeCanvas) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return 0;
        }

        return canvasDelegate.getSnapshot().size();
    }

    @LayoutlibDelegate
   public static void nTranslate(long nativeCanvas, float dx, float dy) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.getSnapshot().translate(dx, dy);
    }

    @LayoutlibDelegate
       public static void nScale(long nativeCanvas, float sx, float sy) {
            // get the delegate from the native int.
            Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
            if (canvasDelegate == null) {
                return;
            }

            canvasDelegate.getSnapshot().scale(sx, sy);
        }

    @LayoutlibDelegate
    public static void nRotate(long nativeCanvas, float degrees) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
        if (canvasDelegate == null) {
            return;
        }

        canvasDelegate.getSnapshot().rotate(Math.toRadians(degrees));
    }

    @LayoutlibDelegate
   public static void nSkew(long nativeCanvas, float kx, float ky) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
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
    public static void nConcat(long nCanvas, long nMatrix) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nCanvas);
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
    public static void nSetMatrix(long nCanvas, long nMatrix) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nCanvas);
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
    public static boolean nClipRect(long nCanvas,
                                                  float left, float top,
                                                  float right, float bottom,
                                                  int regionOp) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nCanvas);
        if (canvasDelegate == null) {
            return false;
        }

        return canvasDelegate.clipRect(left, top, right, bottom, regionOp);
    }

    @LayoutlibDelegate
    public static boolean nClipPath(long nativeCanvas,
                                                  long nativePath,
                                                  int regionOp) {
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
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
    public static void nSetDrawFilter(long nativeCanvas, long nativeFilter) {
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
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
    public static boolean nGetClipBounds(long nativeCanvas,
                                                       Rect bounds) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(nativeCanvas);
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
    public static void nGetMatrix(long canvas, long matrix) {
        // get the delegate from the native int.
        Canvas_Delegate canvasDelegate = Canvas_Delegate.getDelegate(canvas);
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
    public static boolean nQuickReject(long nativeCanvas, long path) {
        // FIXME properly implement quickReject
        return false;
    }

    @LayoutlibDelegate
    public static boolean nQuickReject(long nativeCanvas,
                                                     float left, float top,
                                                     float right, float bottom) {
        // FIXME properly implement quickReject
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static long nGetNativeFinalizer() {
        synchronized (Canvas_Delegate.class) {
            if (sFinalizer == -1) {
                sFinalizer = NativeAllocationRegistry_Delegate.createFinalizer(nativePtr -> {
                    Canvas_Delegate delegate = Canvas_Delegate.getDelegate(nativePtr);
                    if (delegate != null) {
                        delegate.dispose();
                    }
                    sManager.removeJavaReferenceFor(nativePtr);
                });
            }
        }
        return sFinalizer;
    }

    private Canvas_Delegate(Bitmap_Delegate bitmap) {
        super(bitmap);
    }

    private Canvas_Delegate() {
        super();
    }
}

