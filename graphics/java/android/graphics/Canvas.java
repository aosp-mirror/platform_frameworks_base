/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.text.GraphicsOperations;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.TextUtils;

import javax.microedition.khronos.opengles.GL;

/**
 * The Canvas class holds the "draw" calls. To draw something, you need
 * 4 basic components: A Bitmap to hold the pixels, a Canvas to host
 * the draw calls (writing into the bitmap), a drawing primitive (e.g. Rect,
 * Path, text, Bitmap), and a paint (to describe the colors and styles for the
 * drawing).
 */
public class Canvas {
    // assigned in constructors, freed in finalizer
    final int mNativeCanvas;
    
    /*  Our native canvas can be either a raster, gl, or picture canvas.
        If we are raster, then mGL will be null, and mBitmap may or may not be
        present (our default constructor creates a raster canvas but no
        java-bitmap is). If we are a gl-based, then mBitmap will be null, and
        mGL will not be null. Thus both cannot be non-null, but its possible
        for both to be null.
    */
    private Bitmap  mBitmap;    // if not null, mGL must be null
    
    // optional field set by the caller
    private DrawFilter mDrawFilter;

    /**
     * @hide
     */
    protected int mDensity = Bitmap.DENSITY_NONE;

    /**
     * Used to determine when compatibility scaling is in effect.
     * 
     * @hide
     */
    protected int mScreenDensity = Bitmap.DENSITY_NONE;
    
    // Used by native code
    @SuppressWarnings({"UnusedDeclaration"})
    private int         mSurfaceFormat;

    /**
     * Flag for drawTextRun indicating left-to-right run direction.
     * @hide
     */
    public static final int DIRECTION_LTR = 0;
    
    /**
     * Flag for drawTextRun indicating right-to-left run direction.
     * @hide
     */
    public static final int DIRECTION_RTL = 1;

    // Maximum bitmap size as defined in Skia's native code
    // (see SkCanvas.cpp, SkDraw.cpp)
    private static final int MAXMIMUM_BITMAP_SIZE = 32766;

    // This field is used to finalize the native Canvas properly
    @SuppressWarnings({"UnusedDeclaration"})
    private final CanvasFinalizer mFinalizer;

    private static class CanvasFinalizer {
        private final int mNativeCanvas;

        public CanvasFinalizer(int nativeCanvas) {
            mNativeCanvas = nativeCanvas;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                if (mNativeCanvas != 0) {
                    finalizer(mNativeCanvas);
                }
            } finally {
                super.finalize();
            }
        }
    }

    /**
     * Construct an empty raster canvas. Use setBitmap() to specify a bitmap to
     * draw into.  The initial target density is {@link Bitmap#DENSITY_NONE};
     * this will typically be replaced when a target bitmap is set for the
     * canvas.
     */
    public Canvas() {
        // 0 means no native bitmap
        mNativeCanvas = initRaster(0);
        mFinalizer = new CanvasFinalizer(mNativeCanvas);
    }

    /**
     * Construct a canvas with the specified bitmap to draw into. The bitmap
     * must be mutable.
     * 
     * <p>The initial target density of the canvas is the same as the given
     * bitmap's density.
     *
     * @param bitmap Specifies a mutable bitmap for the canvas to draw into.
     */
    public Canvas(Bitmap bitmap) {
        if (!bitmap.isMutable()) {
            throw new IllegalStateException(
                            "Immutable bitmap passed to Canvas constructor");
        }
        throwIfRecycled(bitmap);
        mNativeCanvas = initRaster(bitmap.ni());
        mFinalizer = new CanvasFinalizer(mNativeCanvas);
        mBitmap = bitmap;
        mDensity = bitmap.mDensity;
    }
    
    Canvas(int nativeCanvas) {
        if (nativeCanvas == 0) {
            throw new IllegalStateException();
        }
        mNativeCanvas = nativeCanvas;
        mFinalizer = new CanvasFinalizer(nativeCanvas);
        mDensity = Bitmap.getDefaultDensity();
    }

    /**
     * Returns null.
     * 
     * @deprecated This method is not supported and should not be invoked.
     * 
     * @hide
     */
    @Deprecated
    protected GL getGL() {
        return null;
    }

    /**
     * Indicates whether this Canvas uses hardware acceleration.
     * 
     * Note that this method does not define what type of hardware acceleration
     * may or may not be used.
     * 
     * @return True if drawing operations are hardware accelerated,
     *         false otherwise.
     */
    public boolean isHardwareAccelerated() {
        return false;
    }

    /**
     * Specify a bitmap for the canvas to draw into.  As a side-effect, also
     * updates the canvas's target density to match that of the bitmap.
     *
     * @param bitmap Specifies a mutable bitmap for the canvas to draw into.
     * 
     * @see #setDensity(int)
     * @see #getDensity()
     */
    public void setBitmap(Bitmap bitmap) {
        if (isHardwareAccelerated()) {
            throw new RuntimeException("Can't set a bitmap device on a GL canvas");
        }

        int pointer = 0;
        if (bitmap != null) {
            if (!bitmap.isMutable()) {
                throw new IllegalStateException();
            }
            throwIfRecycled(bitmap);
            mDensity = bitmap.mDensity;
            pointer = bitmap.ni();
        }

        native_setBitmap(mNativeCanvas, pointer);
        mBitmap = bitmap;
    }
    
    /**
     * Set the viewport dimensions if this canvas is GL based. If it is not,
     * this method is ignored and no exception is thrown.
     *
     * @param width The width of the viewport
     * @param height The height of the viewport
     * 
     * @hide
     */
    public void setViewport(int width, int height) {
    }

    /**
     * Return true if the device that the current layer draws into is opaque
     * (i.e. does not support per-pixel alpha).
     *
     * @return true if the device that the current layer draws into is opaque
     */
    public native boolean isOpaque();

    /**
     * Returns the width of the current drawing layer
     *
     * @return the width of the current drawing layer
     */
    public native int getWidth();

    /**
     * Returns the height of the current drawing layer
     *
     * @return the height of the current drawing layer
     */
    public native int getHeight();

    /**
     * <p>Returns the target density of the canvas.  The default density is
     * derived from the density of its backing bitmap, or
     * {@link Bitmap#DENSITY_NONE} if there is not one.</p>
     *
     * @return Returns the current target density of the canvas, which is used
     * to determine the scaling factor when drawing a bitmap into it.
     *
     * @see #setDensity(int)
     * @see Bitmap#getDensity() 
     */
    public int getDensity() {
        return mDensity;
    }

    /**
     * <p>Specifies the density for this Canvas' backing bitmap.  This modifies
     * the target density of the canvas itself, as well as the density of its
     * backing bitmap via {@link Bitmap#setDensity(int) Bitmap.setDensity(int)}.
     *
     * @param density The new target density of the canvas, which is used
     * to determine the scaling factor when drawing a bitmap into it.  Use
     * {@link Bitmap#DENSITY_NONE} to disable bitmap scaling.
     *
     * @see #getDensity()
     * @see Bitmap#setDensity(int) 
     */
    public void setDensity(int density) {
        if (mBitmap != null) {
            mBitmap.setDensity(density);
        }
        mDensity = density;
    }

    /** @hide */
    public void setScreenDensity(int density) {
        mScreenDensity = density;
    }

    /**
     * Returns the maximum allowed width for bitmaps drawn with this canvas.
     * Attempting to draw with a bitmap wider than this value will result
     * in an error.
     * 
     * @see #getMaximumBitmapHeight() 
     */
    public int getMaximumBitmapWidth() {
        return MAXMIMUM_BITMAP_SIZE;
    }
    
    /**
     * Returns the maximum allowed height for bitmaps drawn with this canvas.
     * Attempting to draw with a bitmap taller than this value will result
     * in an error.
     * 
     * @see #getMaximumBitmapWidth() 
     */
    public int getMaximumBitmapHeight() {
        return MAXMIMUM_BITMAP_SIZE;
    }

    // the SAVE_FLAG constants must match their native equivalents

    /** restore the current matrix when restore() is called */
    public static final int MATRIX_SAVE_FLAG = 0x01;
    /** restore the current clip when restore() is called */
    public static final int CLIP_SAVE_FLAG = 0x02;
    /** the layer needs to per-pixel alpha */
    public static final int HAS_ALPHA_LAYER_SAVE_FLAG = 0x04;
    /** the layer needs to 8-bits per color component */
    public static final int FULL_COLOR_LAYER_SAVE_FLAG = 0x08;
    /** clip against the layer's bounds */
    public static final int CLIP_TO_LAYER_SAVE_FLAG = 0x10;
    /** restore everything when restore() is called */
    public static final int ALL_SAVE_FLAG = 0x1F; 
    
    /**
     * Saves the current matrix and clip onto a private stack. Subsequent
     * calls to translate,scale,rotate,skew,concat or clipRect,clipPath
     * will all operate as usual, but when the balancing call to restore()
     * is made, those calls will be forgotten, and the settings that existed
     * before the save() will be reinstated.
     *
     * @return The value to pass to restoreToCount() to balance this save()
     */
    public native int save();
    
    /**
     * Based on saveFlags, can save the current matrix and clip onto a private
     * stack. Subsequent calls to translate,scale,rotate,skew,concat or
     * clipRect,clipPath will all operate as usual, but when the balancing
     * call to restore() is made, those calls will be forgotten, and the
     * settings that existed before the save() will be reinstated.
     *
     * @param saveFlags flag bits that specify which parts of the Canvas state
     *                  to save/restore
     * @return The value to pass to restoreToCount() to balance this save()
     */
    public native int save(int saveFlags);

    /**
     * This behaves the same as save(), but in addition it allocates an
     * offscreen bitmap. All drawing calls are directed there, and only when
     * the balancing call to restore() is made is that offscreen transfered to
     * the canvas (or the previous layer). Subsequent calls to translate,
     * scale, rotate, skew, concat or clipRect, clipPath all operate on this
     * copy. When the balancing call to restore() is made, this copy is
     * deleted and the previous matrix/clip state is restored.
     *
     * @param bounds May be null. The maximum size the offscreen bitmap
     *               needs to be (in local coordinates)
     * @param paint  This is copied, and is applied to the offscreen when
     *               restore() is called.
     * @param saveFlags  see _SAVE_FLAG constants
     * @return       value to pass to restoreToCount() to balance this save()
     */
    public int saveLayer(RectF bounds, Paint paint, int saveFlags) {
        return native_saveLayer(mNativeCanvas, bounds,
                                paint != null ? paint.mNativePaint : 0,
                                saveFlags);
    }
    
    /**
     * Helper version of saveLayer() that takes 4 values rather than a RectF.
     */
    public int saveLayer(float left, float top, float right, float bottom,
                         Paint paint, int saveFlags) {
        return native_saveLayer(mNativeCanvas, left, top, right, bottom,
                                paint != null ? paint.mNativePaint : 0,
                                saveFlags);
    }

    /**
     * This behaves the same as save(), but in addition it allocates an
     * offscreen bitmap. All drawing calls are directed there, and only when
     * the balancing call to restore() is made is that offscreen transfered to
     * the canvas (or the previous layer). Subsequent calls to translate,
     * scale, rotate, skew, concat or clipRect, clipPath all operate on this
     * copy. When the balancing call to restore() is made, this copy is
     * deleted and the previous matrix/clip state is restored.
     *
     * @param bounds    The maximum size the offscreen bitmap needs to be
     *                  (in local coordinates)
     * @param alpha     The alpha to apply to the offscreen when when it is
                        drawn during restore()
     * @param saveFlags see _SAVE_FLAG constants
     * @return          value to pass to restoreToCount() to balance this call
     */
    public int saveLayerAlpha(RectF bounds, int alpha, int saveFlags) {
        alpha = Math.min(255, Math.max(0, alpha));
        return native_saveLayerAlpha(mNativeCanvas, bounds, alpha, saveFlags);
    }
    
    /**
     * Helper for saveLayerAlpha() that takes 4 values instead of a RectF.
     */
    public int saveLayerAlpha(float left, float top, float right, float bottom,
                              int alpha, int saveFlags) {
        return native_saveLayerAlpha(mNativeCanvas, left, top, right, bottom,
                                     alpha, saveFlags);
    }

    /**
     * This call balances a previous call to save(), and is used to remove all
     * modifications to the matrix/clip state since the last save call. It is
     * an error to call restore() more times than save() was called.
     */
    public native void restore();

    /**
     * Returns the number of matrix/clip states on the Canvas' private stack.
     * This will equal # save() calls - # restore() calls.
     */
    public native int getSaveCount();

    /**
     * Efficient way to pop any calls to save() that happened after the save
     * count reached saveCount. It is an error for saveCount to be less than 1.
     *
     * Example:
     *    int count = canvas.save();
     *    ... // more calls potentially to save()
     *    canvas.restoreToCount(count);
     *    // now the canvas is back in the same state it was before the initial
     *    // call to save().
     *
     * @param saveCount The save level to restore to.
     */
    public native void restoreToCount(int saveCount);

    /**
     * Preconcat the current matrix with the specified translation
     *
     * @param dx The distance to translate in X
     * @param dy The distance to translate in Y
    */
    public native void translate(float dx, float dy);

    /**
     * Preconcat the current matrix with the specified scale.
     *
     * @param sx The amount to scale in X
     * @param sy The amount to scale in Y
     */
    public native void scale(float sx, float sy);

    /**
     * Preconcat the current matrix with the specified scale.
     *
     * @param sx The amount to scale in X
     * @param sy The amount to scale in Y
     * @param px The x-coord for the pivot point (unchanged by the scale)
     * @param py The y-coord for the pivot point (unchanged by the scale)
     */
    public final void scale(float sx, float sy, float px, float py) {
        translate(px, py);
        scale(sx, sy);
        translate(-px, -py);
    }

    /**
     * Preconcat the current matrix with the specified rotation.
     *
     * @param degrees The amount to rotate, in degrees
     */
    public native void rotate(float degrees);

    /**
     * Preconcat the current matrix with the specified rotation.
     *
     * @param degrees The amount to rotate, in degrees
     * @param px The x-coord for the pivot point (unchanged by the rotation)
     * @param py The y-coord for the pivot point (unchanged by the rotation)
     */
    public final void rotate(float degrees, float px, float py) {
        translate(px, py);
        rotate(degrees);
        translate(-px, -py);
    }

    /**
     * Preconcat the current matrix with the specified skew.
     *
     * @param sx The amount to skew in X
     * @param sy The amount to skew in Y
     */
    public native void skew(float sx, float sy);

    /**
     * Preconcat the current matrix with the specified matrix.
     *
     * @param matrix The matrix to preconcatenate with the current matrix
     */
    public void concat(Matrix matrix) {
        native_concat(mNativeCanvas, matrix.native_instance);
    }
    
    /**
     * Completely replace the current matrix with the specified matrix. If the
     * matrix parameter is null, then the current matrix is reset to identity.
     *
     * @param matrix The matrix to replace the current matrix with. If it is
     *               null, set the current matrix to identity.
     */
    public void setMatrix(Matrix matrix) {
        native_setMatrix(mNativeCanvas,
                         matrix == null ? 0 : matrix.native_instance);
    }
    
    /**
     * Return, in ctm, the current transformation matrix. This does not alter
     * the matrix in the canvas, but just returns a copy of it.
     */
    public void getMatrix(Matrix ctm) {
        native_getCTM(mNativeCanvas, ctm.native_instance);
    }

    /**
     * Return a new matrix with a copy of the canvas' current transformation
     * matrix.
     */
    public final Matrix getMatrix() {
        Matrix m = new Matrix();
        getMatrix(m);
        return m;
    }
    
    /**
     * Modify the current clip with the specified rectangle.
     *
     * @param rect The rect to intersect with the current clip
     * @param op How the clip is modified
     * @return true if the resulting clip is non-empty
     */
    public boolean clipRect(RectF rect, Region.Op op) {
        return native_clipRect(mNativeCanvas,
                               rect.left, rect.top, rect.right, rect.bottom,
                               op.nativeInt);
    }

    /**
     * Modify the current clip with the specified rectangle, which is
     * expressed in local coordinates.
     *
     * @param rect The rectangle to intersect with the current clip.
     * @param op How the clip is modified
     * @return true if the resulting clip is non-empty
     */
    public boolean clipRect(Rect rect, Region.Op op) {
        return native_clipRect(mNativeCanvas,
                               rect.left, rect.top, rect.right, rect.bottom,
                               op.nativeInt);
    }

    /**
     * Intersect the current clip with the specified rectangle, which is
     * expressed in local coordinates.
     *
     * @param rect The rectangle to intersect with the current clip.
     * @return true if the resulting clip is non-empty
     */
    public native boolean clipRect(RectF rect);
    
    /**
     * Intersect the current clip with the specified rectangle, which is
     * expressed in local coordinates.
     *
     * @param rect The rectangle to intersect with the current clip.
     * @return true if the resulting clip is non-empty
     */
    public native boolean clipRect(Rect rect);
    
    /**
     * Modify the current clip with the specified rectangle, which is
     * expressed in local coordinates.
     *
     * @param left   The left side of the rectangle to intersect with the
     *               current clip
     * @param top    The top of the rectangle to intersect with the current
     *               clip
     * @param right  The right side of the rectangle to intersect with the
     *               current clip
     * @param bottom The bottom of the rectangle to intersect with the current
     *               clip
     * @param op     How the clip is modified
     * @return       true if the resulting clip is non-empty
     */
    public boolean clipRect(float left, float top, float right, float bottom,
                            Region.Op op) {
        return native_clipRect(mNativeCanvas, left, top, right, bottom,
                               op.nativeInt);
    }

    /**
     * Intersect the current clip with the specified rectangle, which is
     * expressed in local coordinates.
     *
     * @param left   The left side of the rectangle to intersect with the
     *               current clip
     * @param top    The top of the rectangle to intersect with the current clip
     * @param right  The right side of the rectangle to intersect with the
     *               current clip
     * @param bottom The bottom of the rectangle to intersect with the current
     *               clip
     * @return       true if the resulting clip is non-empty
     */
    public native boolean clipRect(float left, float top,
                                   float right, float bottom);
    
    /**
     * Intersect the current clip with the specified rectangle, which is
     * expressed in local coordinates.
     *
     * @param left   The left side of the rectangle to intersect with the
     *               current clip
     * @param top    The top of the rectangle to intersect with the current clip
     * @param right  The right side of the rectangle to intersect with the
     *               current clip
     * @param bottom The bottom of the rectangle to intersect with the current
     *               clip
     * @return       true if the resulting clip is non-empty
     */
    public native boolean clipRect(int left, int top,
                                   int right, int bottom);
    
    /**
        * Modify the current clip with the specified path.
     *
     * @param path The path to operate on the current clip
     * @param op   How the clip is modified
     * @return     true if the resulting is non-empty
     */
    public boolean clipPath(Path path, Region.Op op) {
        return native_clipPath(mNativeCanvas, path.ni(), op.nativeInt);
    }
    
    /**
     * Intersect the current clip with the specified path.
     *
     * @param path The path to intersect with the current clip
     * @return     true if the resulting is non-empty
     */
    public boolean clipPath(Path path) {
        return clipPath(path, Region.Op.INTERSECT);
    }
    
    /**
     * Modify the current clip with the specified region. Note that unlike
     * clipRect() and clipPath() which transform their arguments by the
     * current matrix, clipRegion() assumes its argument is already in the
     * coordinate system of the current layer's bitmap, and so not
     * transformation is performed.
     *
     * @param region The region to operate on the current clip, based on op
     * @param op How the clip is modified
     * @return true if the resulting is non-empty
     */
    public boolean clipRegion(Region region, Region.Op op) {
        return native_clipRegion(mNativeCanvas, region.ni(), op.nativeInt);
    }

    /**
     * Intersect the current clip with the specified region. Note that unlike
     * clipRect() and clipPath() which transform their arguments by the
     * current matrix, clipRegion() assumes its argument is already in the
     * coordinate system of the current layer's bitmap, and so not
     * transformation is performed.
     *
     * @param region The region to operate on the current clip, based on op
     * @return true if the resulting is non-empty
     */
    public boolean clipRegion(Region region) {
        return clipRegion(region, Region.Op.INTERSECT);
    }
    
    public DrawFilter getDrawFilter() {
        return mDrawFilter;
    }
    
    public void setDrawFilter(DrawFilter filter) {
        int nativeFilter = 0;
        if (filter != null) {
            nativeFilter = filter.mNativeInt;
        }
        mDrawFilter = filter;
        nativeSetDrawFilter(mNativeCanvas, nativeFilter);
    }

    public enum EdgeType {
        BW(0),  //!< treat edges by just rounding to nearest pixel boundary
        AA(1);  //!< treat edges by rounding-out, since they may be antialiased
        
        EdgeType(int nativeInt) {
            this.nativeInt = nativeInt;
        }

        /**
         * @hide
         */
        public final int nativeInt;
    }

    /**
     * Return true if the specified rectangle, after being transformed by the
     * current matrix, would lie completely outside of the current clip. Call
     * this to check if an area you intend to draw into is clipped out (and
     * therefore you can skip making the draw calls).
     *
     * @param rect  the rect to compare with the current clip
     * @param type  specifies how to treat the edges (BW or antialiased)
     * @return      true if the rect (transformed by the canvas' matrix)
     *              does not intersect with the canvas' clip
     */
    public boolean quickReject(RectF rect, EdgeType type) {
        return native_quickReject(mNativeCanvas, rect, type.nativeInt);
    }

    /**
     * Return true if the specified path, after being transformed by the
     * current matrix, would lie completely outside of the current clip. Call
     * this to check if an area you intend to draw into is clipped out (and
     * therefore you can skip making the draw calls). Note: for speed it may
     * return false even if the path itself might not intersect the clip
     * (i.e. the bounds of the path intersects, but the path does not).
     *
     * @param path        The path to compare with the current clip
     * @param type        true if the path should be considered antialiased,
     *                    since that means it may
     *                    affect a larger area (more pixels) than
     *                    non-antialiased.
     * @return            true if the path (transformed by the canvas' matrix)
     *                    does not intersect with the canvas' clip
     */
    public boolean quickReject(Path path, EdgeType type) {
        return native_quickReject(mNativeCanvas, path.ni(), type.nativeInt);
    }

    /**
     * Return true if the specified rectangle, after being transformed by the
     * current matrix, would lie completely outside of the current clip. Call
     * this to check if an area you intend to draw into is clipped out (and
     * therefore you can skip making the draw calls).
     *
     * @param left        The left side of the rectangle to compare with the
     *                    current clip
     * @param top         The top of the rectangle to compare with the current
     *                    clip
     * @param right       The right side of the rectangle to compare with the
     *                    current clip
     * @param bottom      The bottom of the rectangle to compare with the
     *                    current clip
     * @param type        true if the rect should be considered antialiased,
     *                    since that means it may affect a larger area (more
     *                    pixels) than non-antialiased.
     * @return            true if the rect (transformed by the canvas' matrix)
     *                    does not intersect with the canvas' clip
     */
    public boolean quickReject(float left, float top, float right, float bottom,
                               EdgeType type) {
        return native_quickReject(mNativeCanvas, left, top, right, bottom,
                                  type.nativeInt);
    }

    /**
     * Retrieve the clip bounds, returning true if they are non-empty.
     *
     * @param bounds Return the clip bounds here. If it is null, ignore it but
     *               still return true if the current clip is non-empty.
     * @return true if the current clip is non-empty.
     */
    public boolean getClipBounds(Rect bounds) {
        return native_getClipBounds(mNativeCanvas, bounds);
    }
    
    /**
     * Retrieve the clip bounds.
     *
     * @return the clip bounds, or [0, 0, 0, 0] if the clip is empty.
     */
    public final Rect getClipBounds() {
        Rect r = new Rect();
        getClipBounds(r);
        return r;
    }
    
    /**
     * Fill the entire canvas' bitmap (restricted to the current clip) with the
     * specified RGB color, using srcover porterduff mode.
     *
     * @param r red component (0..255) of the color to draw onto the canvas
     * @param g green component (0..255) of the color to draw onto the canvas
     * @param b blue component (0..255) of the color to draw onto the canvas
     */
    public void drawRGB(int r, int g, int b) {
        native_drawRGB(mNativeCanvas, r, g, b);
    }

    /**
     * Fill the entire canvas' bitmap (restricted to the current clip) with the
     * specified ARGB color, using srcover porterduff mode.
     *
     * @param a alpha component (0..255) of the color to draw onto the canvas
     * @param r red component (0..255) of the color to draw onto the canvas
     * @param g green component (0..255) of the color to draw onto the canvas
     * @param b blue component (0..255) of the color to draw onto the canvas
     */
    public void drawARGB(int a, int r, int g, int b) {
        native_drawARGB(mNativeCanvas, a, r, g, b);
    }

    /**
     * Fill the entire canvas' bitmap (restricted to the current clip) with the
     * specified color, using srcover porterduff mode.
     *
     * @param color the color to draw onto the canvas
     */
    public void drawColor(int color) {
        native_drawColor(mNativeCanvas, color);
    }

    /**
     * Fill the entire canvas' bitmap (restricted to the current clip) with the
     * specified color and porter-duff xfermode.
     *
     * @param color the color to draw with
     * @param mode  the porter-duff mode to apply to the color
     */
    public void drawColor(int color, PorterDuff.Mode mode) {
        native_drawColor(mNativeCanvas, color, mode.nativeInt);
    }

    /**
     * Fill the entire canvas' bitmap (restricted to the current clip) with
     * the specified paint. This is equivalent (but faster) to drawing an
     * infinitely large rectangle with the specified paint.
     *
     * @param paint The paint used to draw onto the canvas
     */
    public void drawPaint(Paint paint) {
        native_drawPaint(mNativeCanvas, paint.mNativePaint);
    }
    
    /**
     * Draw a series of points. Each point is centered at the coordinate
     * specified by pts[], and its diameter is specified by the paint's stroke
     * width (as transformed by the canvas' CTM), with special treatment for
     * a stroke width of 0, which always draws exactly 1 pixel (or at most 4
     * if antialiasing is enabled). The shape of the point is controlled by
     * the paint's Cap type. The shape is a square, unless the cap type is
     * Round, in which case the shape is a circle.
     *
     * @param pts      Array of points to draw [x0 y0 x1 y1 x2 y2 ...]
     * @param offset   Number of values to skip before starting to draw.
     * @param count    The number of values to process, after skipping offset
     *                 of them. Since one point uses two values, the number of
     *                 "points" that are drawn is really (count >> 1).
     * @param paint    The paint used to draw the points
     */
    public native void drawPoints(float[] pts, int offset, int count,
                                  Paint paint);

    /**
     * Helper for drawPoints() that assumes you want to draw the entire array
     */
    public void drawPoints(float[] pts, Paint paint) {
        drawPoints(pts, 0, pts.length, paint);
    }

    /**
     * Helper for drawPoints() for drawing a single point.
     */
    public native void drawPoint(float x, float y, Paint paint);

    /**
     * Draw a line segment with the specified start and stop x,y coordinates,
     * using the specified paint. NOTE: since a line is always "framed", the
     * Style is ignored in the paint.
     *
     * @param startX The x-coordinate of the start point of the line
     * @param startY The y-coordinate of the start point of the line
     * @param paint  The paint used to draw the line
     */
    public void drawLine(float startX, float startY, float stopX, float stopY,
                         Paint paint) {
        native_drawLine(mNativeCanvas, startX, startY, stopX, stopY,
                        paint.mNativePaint);
    }

    /**
     * Draw a series of lines. Each line is taken from 4 consecutive values
     * in the pts array. Thus to draw 1 line, the array must contain at least 4
     * values. This is logically the same as drawing the array as follows:
     * drawLine(pts[0], pts[1], pts[2], pts[3]) followed by
     * drawLine(pts[4], pts[5], pts[6], pts[7]) and so on.
     *
     * @param pts      Array of points to draw [x0 y0 x1 y1 x2 y2 ...]
     * @param offset   Number of values in the array to skip before drawing.
     * @param count    The number of values in the array to process, after
     *                 skipping "offset" of them. Since each line uses 4 values,
     *                 the number of "lines" that are drawn is really
     *                 (count >> 2).
     * @param paint    The paint used to draw the points
     */
    public native void drawLines(float[] pts, int offset, int count,
                                 Paint paint);

    public void drawLines(float[] pts, Paint paint) {
        drawLines(pts, 0, pts.length, paint);
    }

    /**
     * Draw the specified Rect using the specified paint. The rectangle will
     * be filled or framed based on the Style in the paint.
     *
     * @param rect  The rect to be drawn
     * @param paint The paint used to draw the rect
     */
    public void drawRect(RectF rect, Paint paint) {
        native_drawRect(mNativeCanvas, rect, paint.mNativePaint);
    }

    /**
     * Draw the specified Rect using the specified Paint. The rectangle
     * will be filled or framed based on the Style in the paint.
     *
     * @param r        The rectangle to be drawn.
     * @param paint    The paint used to draw the rectangle
     */
    public void drawRect(Rect r, Paint paint) {
        drawRect(r.left, r.top, r.right, r.bottom, paint);
    }
    

    /**
     * Draw the specified Rect using the specified paint. The rectangle will
     * be filled or framed based on the Style in the paint.
     *
     * @param left   The left side of the rectangle to be drawn
     * @param top    The top side of the rectangle to be drawn
     * @param right  The right side of the rectangle to be drawn
     * @param bottom The bottom side of the rectangle to be drawn
     * @param paint  The paint used to draw the rect
     */
    public void drawRect(float left, float top, float right, float bottom,
                         Paint paint) {
        native_drawRect(mNativeCanvas, left, top, right, bottom,
                        paint.mNativePaint);
    }

    /**
     * Draw the specified oval using the specified paint. The oval will be
     * filled or framed based on the Style in the paint.
     *
     * @param oval The rectangle bounds of the oval to be drawn
     */
    public void drawOval(RectF oval, Paint paint) {
        if (oval == null) {
            throw new NullPointerException();
        }
        native_drawOval(mNativeCanvas, oval, paint.mNativePaint);
    }

    /**
     * Draw the specified circle using the specified paint. If radius is <= 0,
     * then nothing will be drawn. The circle will be filled or framed based
     * on the Style in the paint.
     *
     * @param cx     The x-coordinate of the center of the cirle to be drawn
     * @param cy     The y-coordinate of the center of the cirle to be drawn
     * @param radius The radius of the cirle to be drawn
     * @param paint  The paint used to draw the circle
     */
    public void drawCircle(float cx, float cy, float radius, Paint paint) {
        native_drawCircle(mNativeCanvas, cx, cy, radius,
                          paint.mNativePaint);
    }

    /**
     * <p>Draw the specified arc, which will be scaled to fit inside the
     * specified oval.</p>
     * 
     * <p>If the start angle is negative or >= 360, the start angle is treated
     * as start angle modulo 360.</p>
     * 
     * <p>If the sweep angle is >= 360, then the oval is drawn
     * completely. Note that this differs slightly from SkPath::arcTo, which
     * treats the sweep angle modulo 360. If the sweep angle is negative,
     * the sweep angle is treated as sweep angle modulo 360</p>
     * 
     * <p>The arc is drawn clockwise. An angle of 0 degrees correspond to the
     * geometric angle of 0 degrees (3 o'clock on a watch.)</p>
     *
     * @param oval       The bounds of oval used to define the shape and size
     *                   of the arc
     * @param startAngle Starting angle (in degrees) where the arc begins
     * @param sweepAngle Sweep angle (in degrees) measured clockwise
     * @param useCenter If true, include the center of the oval in the arc, and
                        close it if it is being stroked. This will draw a wedge
     * @param paint      The paint used to draw the arc
     */
    public void drawArc(RectF oval, float startAngle, float sweepAngle,
                        boolean useCenter, Paint paint) {
        if (oval == null) {
            throw new NullPointerException();
        }
        native_drawArc(mNativeCanvas, oval, startAngle, sweepAngle,
                       useCenter, paint.mNativePaint);
    }

    /**
     * Draw the specified round-rect using the specified paint. The roundrect
     * will be filled or framed based on the Style in the paint.
     *
     * @param rect  The rectangular bounds of the roundRect to be drawn
     * @param rx    The x-radius of the oval used to round the corners
     * @param ry    The y-radius of the oval used to round the corners
     * @param paint The paint used to draw the roundRect
     */
    public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
        if (rect == null) {
            throw new NullPointerException();
        }
        native_drawRoundRect(mNativeCanvas, rect, rx, ry,
                             paint.mNativePaint);
    }

    /**
     * Draw the specified path using the specified paint. The path will be
     * filled or framed based on the Style in the paint.
     *
     * @param path  The path to be drawn
     * @param paint The paint used to draw the path
     */
    public void drawPath(Path path, Paint paint) {
        native_drawPath(mNativeCanvas, path.ni(), paint.mNativePaint);
    }
    
    private static void throwIfRecycled(Bitmap bitmap) {
        if (bitmap.isRecycled()) {
            throw new RuntimeException(
                        "Canvas: trying to use a recycled bitmap " + bitmap);
        }
    }

    /**
     * Draws the specified bitmap as an N-patch (most often, a 9-patches.)
     *
     * Note: Only supported by hardware accelerated canvas at the moment.
     *
     * @param bitmap The bitmap to draw as an N-patch
     * @param chunks The patches information (matches the native struct Res_png_9patch)
     * @param dst The destination rectangle.
     * @param paint The paint to draw the bitmap with. may be null
     * 
     * @hide
     */
    public void drawPatch(Bitmap bitmap, byte[] chunks, RectF dst, Paint paint) {
    }    
    
    /**
     * Draw the specified bitmap, with its top/left corner at (x,y), using
     * the specified paint, transformed by the current matrix.
     * 
     * <p>Note: if the paint contains a maskfilter that generates a mask which
     * extends beyond the bitmap's original width/height (e.g. BlurMaskFilter),
     * then the bitmap will be drawn as if it were in a Shader with CLAMP mode.
     * Thus the color outside of the original width/height will be the edge
     * color replicated.
     *
     * <p>If the bitmap and canvas have different densities, this function
     * will take care of automatically scaling the bitmap to draw at the
     * same density as the canvas.
     * 
     * @param bitmap The bitmap to be drawn
     * @param left   The position of the left side of the bitmap being drawn
     * @param top    The position of the top side of the bitmap being drawn
     * @param paint  The paint used to draw the bitmap (may be null)
     */
    public void drawBitmap(Bitmap bitmap, float left, float top, Paint paint) {
        throwIfRecycled(bitmap);
        native_drawBitmap(mNativeCanvas, bitmap.ni(), left, top,
                paint != null ? paint.mNativePaint : 0, mDensity, mScreenDensity,
                bitmap.mDensity);
    }

    /**
     * Draw the specified bitmap, scaling/translating automatically to fill
     * the destination rectangle. If the source rectangle is not null, it
     * specifies the subset of the bitmap to draw.
     * 
     * <p>Note: if the paint contains a maskfilter that generates a mask which
     * extends beyond the bitmap's original width/height (e.g. BlurMaskFilter),
     * then the bitmap will be drawn as if it were in a Shader with CLAMP mode.
     * Thus the color outside of the original width/height will be the edge
     * color replicated.
     *
     * <p>This function <em>ignores the density associated with the bitmap</em>.
     * This is because the source and destination rectangle coordinate
     * spaces are in their respective densities, so must already have the
     * appropriate scaling factor applied.
     * 
     * @param bitmap The bitmap to be drawn
     * @param src    May be null. The subset of the bitmap to be drawn
     * @param dst    The rectangle that the bitmap will be scaled/translated
     *               to fit into
     * @param paint  May be null. The paint used to draw the bitmap
     */
    public void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) {
        if (dst == null) {
            throw new NullPointerException();
        }
        throwIfRecycled(bitmap);
        native_drawBitmap(mNativeCanvas, bitmap.ni(), src, dst,
                          paint != null ? paint.mNativePaint : 0,
                          mScreenDensity, bitmap.mDensity);
    }

    /**
     * Draw the specified bitmap, scaling/translating automatically to fill
     * the destination rectangle. If the source rectangle is not null, it
     * specifies the subset of the bitmap to draw.
     * 
     * <p>Note: if the paint contains a maskfilter that generates a mask which
     * extends beyond the bitmap's original width/height (e.g. BlurMaskFilter),
     * then the bitmap will be drawn as if it were in a Shader with CLAMP mode.
     * Thus the color outside of the original width/height will be the edge
     * color replicated.
     *
     * <p>This function <em>ignores the density associated with the bitmap</em>.
     * This is because the source and destination rectangle coordinate
     * spaces are in their respective densities, so must already have the
     * appropriate scaling factor applied.
     * 
     * @param bitmap The bitmap to be drawn
     * @param src    May be null. The subset of the bitmap to be drawn
     * @param dst    The rectangle that the bitmap will be scaled/translated
     *               to fit into
     * @param paint  May be null. The paint used to draw the bitmap
     */
    public void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) {
        if (dst == null) {
            throw new NullPointerException();
        }
        throwIfRecycled(bitmap);
        native_drawBitmap(mNativeCanvas, bitmap.ni(), src, dst,
                          paint != null ? paint.mNativePaint : 0,
                          mScreenDensity, bitmap.mDensity);
    }
    
    /**
     * Treat the specified array of colors as a bitmap, and draw it. This gives
     * the same result as first creating a bitmap from the array, and then
     * drawing it, but this method avoids explicitly creating a bitmap object
     * which can be more efficient if the colors are changing often.
     *
     * @param colors Array of colors representing the pixels of the bitmap
     * @param offset Offset into the array of colors for the first pixel
     * @param stride The number of colors in the array between rows (must be
     *               >= width or <= -width).
     * @param x The X coordinate for where to draw the bitmap
     * @param y The Y coordinate for where to draw the bitmap
     * @param width The width of the bitmap
     * @param height The height of the bitmap
     * @param hasAlpha True if the alpha channel of the colors contains valid
     *                 values. If false, the alpha byte is ignored (assumed to
     *                 be 0xFF for every pixel).
     * @param paint  May be null. The paint used to draw the bitmap
     */
    public void drawBitmap(int[] colors, int offset, int stride, float x,
                           float y, int width, int height, boolean hasAlpha,
                           Paint paint) {
        // check for valid input
        if (width < 0) {
            throw new IllegalArgumentException("width must be >= 0");
        }
        if (height < 0) {
            throw new IllegalArgumentException("height must be >= 0");
        }
        if (Math.abs(stride) < width) {
            throw new IllegalArgumentException("abs(stride) must be >= width");
        }
        int lastScanline = offset + (height - 1) * stride;
        int length = colors.length;
        if (offset < 0 || (offset + width > length) || lastScanline < 0
                || (lastScanline + width > length)) {
            throw new ArrayIndexOutOfBoundsException();
        }
        // quick escape if there's nothing to draw
        if (width == 0 || height == 0) {
            return;
        }
        // punch down to native for the actual draw
        native_drawBitmap(mNativeCanvas, colors, offset, stride, x, y, width, height, hasAlpha,
                paint != null ? paint.mNativePaint : 0);
    }
    
    /** Legacy version of drawBitmap(int[] colors, ...) that took ints for x,y
     */
    public void drawBitmap(int[] colors, int offset, int stride, int x, int y,
                           int width, int height, boolean hasAlpha,
                           Paint paint) {
        // call through to the common float version
        drawBitmap(colors, offset, stride, (float)x, (float)y, width, height,
                   hasAlpha, paint);
    }
        
    /**
     * Draw the bitmap using the specified matrix.
     *
     * @param bitmap The bitmap to draw
     * @param matrix The matrix used to transform the bitmap when it is drawn
     * @param paint  May be null. The paint used to draw the bitmap
     */
    public void drawBitmap(Bitmap bitmap, Matrix matrix, Paint paint) {
        nativeDrawBitmapMatrix(mNativeCanvas, bitmap.ni(), matrix.ni(),
                paint != null ? paint.mNativePaint : 0);
    }

    /**
     * @hide
     */
    protected static void checkRange(int length, int offset, int count) {
        if ((offset | count) < 0 || offset + count > length) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }
    
    /**
     * Draw the bitmap through the mesh, where mesh vertices are evenly
     * distributed across the bitmap. There are meshWidth+1 vertices across, and
     * meshHeight+1 vertices down. The verts array is accessed in row-major
     * order, so that the first meshWidth+1 vertices are distributed across the
     * top of the bitmap from left to right. A more general version of this
     * methid is drawVertices().
     *
     * @param bitmap The bitmap to draw using the mesh
     * @param meshWidth The number of columns in the mesh. Nothing is drawn if
     *                  this is 0
     * @param meshHeight The number of rows in the mesh. Nothing is drawn if
     *                   this is 0
     * @param verts Array of x,y pairs, specifying where the mesh should be
     *              drawn. There must be at least
     *              (meshWidth+1) * (meshHeight+1) * 2 + meshOffset values
     *              in the array
     * @param vertOffset Number of verts elements to skip before drawing
     * @param colors May be null. Specifies a color at each vertex, which is
     *               interpolated across the cell, and whose values are
     *               multiplied by the corresponding bitmap colors. If not null,
     *               there must be at least (meshWidth+1) * (meshHeight+1) +
     *               colorOffset values in the array.
     * @param colorOffset Number of color elements to skip before drawing
     * @param paint  May be null. The paint used to draw the bitmap
     */
    public void drawBitmapMesh(Bitmap bitmap, int meshWidth, int meshHeight,
                               float[] verts, int vertOffset,
                               int[] colors, int colorOffset, Paint paint) {
        if ((meshWidth | meshHeight | vertOffset | colorOffset) < 0) {
            throw new ArrayIndexOutOfBoundsException();
        }
        if (meshWidth == 0 || meshHeight == 0) {
            return;
        }
        int count = (meshWidth + 1) * (meshHeight + 1);
        // we mul by 2 since we need two floats per vertex
        checkRange(verts.length, vertOffset, count * 2);
        if (colors != null) {
            // no mul by 2, since we need only 1 color per vertex
            checkRange(colors.length, colorOffset, count);
        }
        nativeDrawBitmapMesh(mNativeCanvas, bitmap.ni(), meshWidth, meshHeight,
                             verts, vertOffset, colors, colorOffset,
                             paint != null ? paint.mNativePaint : 0);
    }
        
    public enum VertexMode {
        TRIANGLES(0),
        TRIANGLE_STRIP(1),
        TRIANGLE_FAN(2);
        
        VertexMode(int nativeInt) {
            this.nativeInt = nativeInt;
        }

        /**
         * @hide
         */
        public final int nativeInt;
    }
    
    /**
     * Draw the array of vertices, interpreted as triangles (based on mode). The
     * verts array is required, and specifies the x,y pairs for each vertex. If
     * texs is non-null, then it is used to specify the coordinate in shader
     * coordinates to use at each vertex (the paint must have a shader in this
     * case). If there is no texs array, but there is a color array, then each
     * color is interpolated across its corresponding triangle in a gradient. If
     * both texs and colors arrays are present, then they behave as before, but
     * the resulting color at each pixels is the result of multiplying the
     * colors from the shader and the color-gradient together. The indices array
     * is optional, but if it is present, then it is used to specify the index
     * of each triangle, rather than just walking through the arrays in order.
     *
     * @param mode How to interpret the array of vertices
     * @param vertexCount The number of values in the vertices array (and
     *      corresponding texs and colors arrays if non-null). Each logical
     *      vertex is two values (x, y), vertexCount must be a multiple of 2.
     * @param verts Array of vertices for the mesh
     * @param vertOffset Number of values in the verts to skip before drawing.
     * @param texs May be null. If not null, specifies the coordinates to sample
     *      into the current shader (e.g. bitmap tile or gradient)
     * @param texOffset Number of values in texs to skip before drawing.
     * @param colors May be null. If not null, specifies a color for each
     *      vertex, to be interpolated across the triangle.
     * @param colorOffset Number of values in colors to skip before drawing.
     * @param indices If not null, array of indices to reference into the
     *      vertex (texs, colors) array.
     * @param indexCount number of entries in the indices array (if not null).
     * @param paint Specifies the shader to use if the texs array is non-null. 
     */
    public void drawVertices(VertexMode mode, int vertexCount,
                             float[] verts, int vertOffset,
                             float[] texs, int texOffset,
                             int[] colors, int colorOffset,
                             short[] indices, int indexOffset,
                             int indexCount, Paint paint) {
        checkRange(verts.length, vertOffset, vertexCount);
        if (texs != null) {
            checkRange(texs.length, texOffset, vertexCount);
        }
        if (colors != null) {
            checkRange(colors.length, colorOffset, vertexCount / 2);
        }
        if (indices != null) {
            checkRange(indices.length, indexOffset, indexCount);
        }
        nativeDrawVertices(mNativeCanvas, mode.nativeInt, vertexCount, verts,
                           vertOffset, texs, texOffset, colors, colorOffset,
                          indices, indexOffset, indexCount, paint.mNativePaint);
    }
    
    /**
     * Draw the text, with origin at (x,y), using the specified paint. The
     * origin is interpreted based on the Align setting in the paint.
     *
     * @param text  The text to be drawn
     * @param x     The x-coordinate of the origin of the text being drawn
     * @param y     The y-coordinate of the origin of the text being drawn
     * @param paint The paint used for the text (e.g. color, size, style)
     */
    public void drawText(char[] text, int index, int count, float x, float y,
                         Paint paint) {
        if ((index | count | (index + count) |
            (text.length - index - count)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        native_drawText(mNativeCanvas, text, index, count, x, y, paint.mBidiFlags,
                paint.mNativePaint);
    }

    /**
     * Draw the text, with origin at (x,y), using the specified paint. The
     * origin is interpreted based on the Align setting in the paint.
     *
     * @param text  The text to be drawn
     * @param x     The x-coordinate of the origin of the text being drawn
     * @param y     The y-coordinate of the origin of the text being drawn
     * @param paint The paint used for the text (e.g. color, size, style)
     */
    public void drawText(String text, float x, float y, Paint paint) {
        native_drawText(mNativeCanvas, text, 0, text.length(), x, y, paint.mBidiFlags,
                paint.mNativePaint);
    }

    /**
     * Draw the text, with origin at (x,y), using the specified paint.
     * The origin is interpreted based on the Align setting in the paint.
     *
     * @param text  The text to be drawn
     * @param start The index of the first character in text to draw
     * @param end   (end - 1) is the index of the last character in text to draw
     * @param x     The x-coordinate of the origin of the text being drawn
     * @param y     The y-coordinate of the origin of the text being drawn
     * @param paint The paint used for the text (e.g. color, size, style)
     */
    public void drawText(String text, int start, int end, float x, float y,
                         Paint paint) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        native_drawText(mNativeCanvas, text, start, end, x, y, paint.mBidiFlags,
                paint.mNativePaint);
    }

    /**
     * Draw the specified range of text, specified by start/end, with its
     * origin at (x,y), in the specified Paint. The origin is interpreted
     * based on the Align setting in the Paint.
     *
     * @param text     The text to be drawn
     * @param start    The index of the first character in text to draw
     * @param end      (end - 1) is the index of the last character in text
     *                 to draw
     * @param x        The x-coordinate of origin for where to draw the text
     * @param y        The y-coordinate of origin for where to draw the text
     * @param paint The paint used for the text (e.g. color, size, style)
     */
    public void drawText(CharSequence text, int start, int end, float x,
                         float y, Paint paint) {
        if (text instanceof String || text instanceof SpannedString ||
            text instanceof SpannableString) {
            native_drawText(mNativeCanvas, text.toString(), start, end, x, y,
                            paint.mBidiFlags, paint.mNativePaint);
        } else if (text instanceof GraphicsOperations) {
            ((GraphicsOperations) text).drawText(this, start, end, x, y,
                                                     paint);
        } else {
            char[] buf = TemporaryBuffer.obtain(end - start);
            TextUtils.getChars(text, start, end, buf, 0);
            native_drawText(mNativeCanvas, buf, 0, end - start, x, y,
                    paint.mBidiFlags, paint.mNativePaint);
            TemporaryBuffer.recycle(buf);
        }
    }

    /**
     * Render a run of all LTR or all RTL text, with shaping. This does not run
     * bidi on the provided text, but renders it as a uniform right-to-left or
     * left-to-right run, as indicated by dir. Alignment of the text is as
     * determined by the Paint's TextAlign value.
     * 
     * @param text the text to render
     * @param index the start of the text to render
     * @param count the count of chars to render
     * @param contextIndex the start of the context for shaping.  Must be
     *         no greater than index.
     * @param contextCount the number of characters in the context for shaping.
     *         ContexIndex + contextCount must be no less than index
     *         + count.
     * @param x the x position at which to draw the text
     * @param y the y position at which to draw the text
     * @param dir the run direction, either {@link #DIRECTION_LTR} or
     *         {@link #DIRECTION_RTL}.
     * @param paint the paint
     * @hide
     */
    public void drawTextRun(char[] text, int index, int count,
            int contextIndex, int contextCount, float x, float y, int dir,
            Paint paint) {

        if (text == null) {
            throw new NullPointerException("text is null");
        }
        if (paint == null) {
            throw new NullPointerException("paint is null");
        }
        if ((index | count | text.length - index - count) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (dir != DIRECTION_LTR && dir != DIRECTION_RTL) {
            throw new IllegalArgumentException("unknown dir: " + dir);
        }

        native_drawTextRun(mNativeCanvas, text, index, count,
                contextIndex, contextCount, x, y, dir, paint.mNativePaint);
    }

    /**
     * Render a run of all LTR or all RTL text, with shaping. This does not run
     * bidi on the provided text, but renders it as a uniform right-to-left or
     * left-to-right run, as indicated by dir. Alignment of the text is as
     * determined by the Paint's TextAlign value.
     *
     * @param text the text to render
     * @param start the start of the text to render. Data before this position
     *            can be used for shaping context.
     * @param end the end of the text to render. Data at or after this
     *            position can be used for shaping context.
     * @param x the x position at which to draw the text
     * @param y the y position at which to draw the text
     * @param dir the run direction, either 0 for LTR or 1 for RTL.
     * @param paint the paint
     * @hide
     */
    public void drawTextRun(CharSequence text, int start, int end,
            int contextStart, int contextEnd, float x, float y, int dir,
            Paint paint) {

        if (text == null) {
            throw new NullPointerException("text is null");
        }
        if (paint == null) {
            throw new NullPointerException("paint is null");
        }
        if ((start | end | end - start | text.length() - end) < 0) {
            throw new IndexOutOfBoundsException();
        }

        int flags = dir == 0 ? 0 : 1;

        if (text instanceof String || text instanceof SpannedString ||
                text instanceof SpannableString) {
            native_drawTextRun(mNativeCanvas, text.toString(), start, end,
                    contextStart, contextEnd, x, y, flags, paint.mNativePaint);
        } else if (text instanceof GraphicsOperations) {
            ((GraphicsOperations) text).drawTextRun(this, start, end,
                    contextStart, contextEnd, x, y, flags, paint);
        } else {
            int contextLen = contextEnd - contextStart;
            int len = end - start;
            char[] buf = TemporaryBuffer.obtain(contextLen);
            TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
            native_drawTextRun(mNativeCanvas, buf, start - contextStart, len,
                    0, contextLen, x, y, flags, paint.mNativePaint);
            TemporaryBuffer.recycle(buf);
        }
    }

    /**
     * Draw the text in the array, with each character's origin specified by
     * the pos array.
     *
     * @param text     The text to be drawn
     * @param index    The index of the first character to draw
     * @param count    The number of characters to draw, starting from index.
     * @param pos      Array of [x,y] positions, used to position each
     *                 character
     * @param paint    The paint used for the text (e.g. color, size, style)
     */
    public void drawPosText(char[] text, int index, int count, float[] pos,
                            Paint paint) {
        if (index < 0 || index + count > text.length || count*2 > pos.length) {
            throw new IndexOutOfBoundsException();
        }
        native_drawPosText(mNativeCanvas, text, index, count, pos,
                           paint.mNativePaint);
    }

    /**
     * Draw the text in the array, with each character's origin specified by
     * the pos array.
     *
     * @param text  The text to be drawn
     * @param pos   Array of [x,y] positions, used to position each character
     * @param paint The paint used for the text (e.g. color, size, style)
     */
    public void drawPosText(String text, float[] pos, Paint paint) {
        if (text.length()*2 > pos.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        native_drawPosText(mNativeCanvas, text, pos, paint.mNativePaint);
    }

    /**
     * Draw the text, with origin at (x,y), using the specified paint, along
     * the specified path. The paint's Align setting determins where along the
     * path to start the text.
     *
     * @param text     The text to be drawn
     * @param path     The path the text should follow for its baseline
     * @param hOffset  The distance along the path to add to the text's
     *                 starting position
     * @param vOffset  The distance above(-) or below(+) the path to position
     *                 the text
     * @param paint    The paint used for the text (e.g. color, size, style)
     */
    public void drawTextOnPath(char[] text, int index, int count, Path path,
                               float hOffset, float vOffset, Paint paint) {
        if (index < 0 || index + count > text.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        native_drawTextOnPath(mNativeCanvas, text, index, count,
                              path.ni(), hOffset, vOffset,
                              paint.mBidiFlags, paint.mNativePaint);
    }

    /**
     * Draw the text, with origin at (x,y), using the specified paint, along
     * the specified path. The paint's Align setting determins where along the
     * path to start the text.
     *
     * @param text     The text to be drawn
     * @param path     The path the text should follow for its baseline
     * @param hOffset  The distance along the path to add to the text's
     *                 starting position
     * @param vOffset  The distance above(-) or below(+) the path to position
     *                 the text
     * @param paint    The paint used for the text (e.g. color, size, style)
     */
    public void drawTextOnPath(String text, Path path, float hOffset,
                               float vOffset, Paint paint) {
        if (text.length() > 0) {
            native_drawTextOnPath(mNativeCanvas, text, path.ni(),
                                  hOffset, vOffset, paint.mBidiFlags,
                                  paint.mNativePaint);
        }
    }

    /**
     * Save the canvas state, draw the picture, and restore the canvas state.
     * This differs from picture.draw(canvas), which does not perform any
     * save/restore.
     * 
     * @param picture  The picture to be drawn
     */
    public void drawPicture(Picture picture) {
        picture.endRecording();
        native_drawPicture(mNativeCanvas, picture.ni());
    }
    
    /**
     * Draw the picture, stretched to fit into the dst rectangle.
     */
    public void drawPicture(Picture picture, RectF dst) {
        save();
        translate(dst.left, dst.top);
        if (picture.getWidth() > 0 && picture.getHeight() > 0) {
            scale(dst.width() / picture.getWidth(),
                  dst.height() / picture.getHeight());
        }
        drawPicture(picture);
        restore();
    }
    
    /**
     * Draw the picture, stretched to fit into the dst rectangle.
     */
    public void drawPicture(Picture picture, Rect dst) {
        save();
        translate(dst.left, dst.top);
        if (picture.getWidth() > 0 && picture.getHeight() > 0) {
            scale((float)dst.width() / picture.getWidth(),
                  (float)dst.height() / picture.getHeight());
        }
        drawPicture(picture);
        restore();
    }

    /**
     * Free up as much memory as possible from private caches (e.g. fonts, images)
     *
     * @hide
     */
    public static native void freeCaches();

    private static native int initRaster(int nativeBitmapOrZero);
    private static native void native_setBitmap(int nativeCanvas, int bitmap);
    private static native int native_saveLayer(int nativeCanvas, RectF bounds,
                                               int paint, int layerFlags);
    private static native int native_saveLayer(int nativeCanvas, float l,
                                               float t, float r, float b,
                                               int paint, int layerFlags);
    private static native int native_saveLayerAlpha(int nativeCanvas,
                                                    RectF bounds, int alpha,
                                                    int layerFlags);
    private static native int native_saveLayerAlpha(int nativeCanvas, float l,
                                                    float t, float r, float b,
                                                    int alpha, int layerFlags);

    private static native void native_concat(int nCanvas, int nMatrix);
    private static native void native_setMatrix(int nCanvas, int nMatrix);
    private static native boolean native_clipRect(int nCanvas,
                                                  float left, float top,
                                                  float right, float bottom,
                                                  int regionOp);
    private static native boolean native_clipPath(int nativeCanvas,
                                                  int nativePath,
                                                  int regionOp);
    private static native boolean native_clipRegion(int nativeCanvas,
                                                    int nativeRegion,
                                                    int regionOp);
    private static native void nativeSetDrawFilter(int nativeCanvas,
                                                   int nativeFilter);
    private static native boolean native_getClipBounds(int nativeCanvas,
                                                       Rect bounds);
    private static native void native_getCTM(int canvas, int matrix);
    private static native boolean native_quickReject(int nativeCanvas,
                                                     RectF rect,
                                                     int native_edgeType);
    private static native boolean native_quickReject(int nativeCanvas,
                                                     int path,
                                                     int native_edgeType);
    private static native boolean native_quickReject(int nativeCanvas,
                                                     float left, float top,
                                                     float right, float bottom,
                                                     int native_edgeType);
    private static native void native_drawRGB(int nativeCanvas, int r, int g,
                                              int b);
    private static native void native_drawARGB(int nativeCanvas, int a, int r,
                                               int g, int b);
    private static native void native_drawColor(int nativeCanvas, int color);
    private static native void native_drawColor(int nativeCanvas, int color,
                                                int mode);
    private static native void native_drawPaint(int nativeCanvas, int paint);
    private static native void native_drawLine(int nativeCanvas, float startX,
                                               float startY, float stopX,
                                               float stopY, int paint);
    private static native void native_drawRect(int nativeCanvas, RectF rect,
                                               int paint);
    private static native void native_drawRect(int nativeCanvas, float left,
                                               float top, float right,
                                               float bottom, int paint);
    private static native void native_drawOval(int nativeCanvas, RectF oval,
                                               int paint);
    private static native void native_drawCircle(int nativeCanvas, float cx,
                                                 float cy, float radius,
                                                 int paint);
    private static native void native_drawArc(int nativeCanvas, RectF oval,
                                              float startAngle, float sweep,
                                              boolean useCenter, int paint);
    private static native void native_drawRoundRect(int nativeCanvas,
                                                    RectF rect, float rx,
                                                    float ry, int paint);
    private static native void native_drawPath(int nativeCanvas, int path,
                                               int paint);
    private native void native_drawBitmap(int nativeCanvas, int bitmap,
                                                 float left, float top,
                                                 int nativePaintOrZero,
                                                 int canvasDensity,
                                                 int screenDensity,
                                                 int bitmapDensity);
    private native void native_drawBitmap(int nativeCanvas, int bitmap,
                                                 Rect src, RectF dst,
                                                 int nativePaintOrZero,
                                                 int screenDensity,
                                                 int bitmapDensity);
    private static native void native_drawBitmap(int nativeCanvas, int bitmap,
                                                 Rect src, Rect dst,
                                                 int nativePaintOrZero,
                                                 int screenDensity,
                                                 int bitmapDensity);
    private static native void native_drawBitmap(int nativeCanvas, int[] colors,
                                                int offset, int stride, float x,
                                                 float y, int width, int height,
                                                 boolean hasAlpha,
                                                 int nativePaintOrZero);
    private static native void nativeDrawBitmapMatrix(int nCanvas, int nBitmap,
                                                      int nMatrix, int nPaint);
    private static native void nativeDrawBitmapMesh(int nCanvas, int nBitmap,
                                                    int meshWidth, int meshHeight,
                                                    float[] verts, int vertOffset,
                                                    int[] colors, int colorOffset, int nPaint);
    private static native void nativeDrawVertices(int nCanvas, int mode, int n,
                   float[] verts, int vertOffset, float[] texs, int texOffset,
                   int[] colors, int colorOffset, short[] indices,
                   int indexOffset, int indexCount, int nPaint);
    
    private static native void native_drawText(int nativeCanvas, char[] text,
                                               int index, int count, float x,
                                               float y, int flags, int paint);
    private static native void native_drawText(int nativeCanvas, String text,
                                               int start, int end, float x,
                                               float y, int flags, int paint);

    private static native void native_drawTextRun(int nativeCanvas, String text,
            int start, int end, int contextStart, int contextEnd,
            float x, float y, int flags, int paint);

    private static native void native_drawTextRun(int nativeCanvas, char[] text,
            int start, int count, int contextStart, int contextCount,
            float x, float y, int flags, int paint);

    private static native void native_drawPosText(int nativeCanvas,
                                                  char[] text, int index,
                                                  int count, float[] pos,
                                                  int paint);
    private static native void native_drawPosText(int nativeCanvas,
                                                  String text, float[] pos,
                                                  int paint);
    private static native void native_drawTextOnPath(int nativeCanvas,
                                                     char[] text, int index,
                                                     int count, int path,
                                                     float hOffset,
                                                     float vOffset, int bidiFlags,
                                                     int paint);
    private static native void native_drawTextOnPath(int nativeCanvas,
                                                     String text, int path,
                                                     float hOffset, 
                                                     float vOffset, 
                                                     int flags, int paint);
    private static native void native_drawPicture(int nativeCanvas,
                                                  int nativePicture);
    private static native void finalizer(int nativeCanvas);
}
