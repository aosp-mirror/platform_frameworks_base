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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.GraphicsOperations;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.microedition.khronos.opengles.GL;

/**
 * The Canvas class holds the "draw" calls. To draw something, you need
 * 4 basic components: A Bitmap to hold the pixels, a Canvas to host
 * the draw calls (writing into the bitmap), a drawing primitive (e.g. Rect,
 * Path, text, Bitmap), and a paint (to describe the colors and styles for the
 * drawing).
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about how to use Canvas, read the
 * <a href="{@docRoot}guide/topics/graphics/2d-graphics.html">
 * Canvas and Drawables</a> developer guide.</p></div>
 */
public class Canvas {

    // assigned in constructors or setBitmap, freed in finalizer
    private long mNativeCanvasWrapper;

    /** @hide */
    public long getNativeCanvasWrapper() {
        return mNativeCanvasWrapper;
    }

    /** @hide */
    public boolean isRecordingFor(Object o) { return false; }

    // may be null
    private Bitmap mBitmap;

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
    @SuppressWarnings("UnusedDeclaration")
    private int mSurfaceFormat;

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
    private final CanvasFinalizer mFinalizer;

    private static final class CanvasFinalizer {
        private long mNativeCanvasWrapper;

        public CanvasFinalizer(long nativeCanvas) {
            mNativeCanvasWrapper = nativeCanvas;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                dispose();
            } finally {
                super.finalize();
            }
        }

        public void dispose() {
            if (mNativeCanvasWrapper != 0) {
                finalizer(mNativeCanvasWrapper);
                mNativeCanvasWrapper = 0;
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
        if (!isHardwareAccelerated()) {
            // 0 means no native bitmap
            mNativeCanvasWrapper = initRaster(0);
            mFinalizer = new CanvasFinalizer(mNativeCanvasWrapper);
        } else {
            mFinalizer = null;
        }
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
    public Canvas(@NonNull Bitmap bitmap) {
        if (!bitmap.isMutable()) {
            throw new IllegalStateException("Immutable bitmap passed to Canvas constructor");
        }
        throwIfCannotDraw(bitmap);
        mNativeCanvasWrapper = initRaster(bitmap.ni());
        mFinalizer = new CanvasFinalizer(mNativeCanvasWrapper);
        mBitmap = bitmap;
        mDensity = bitmap.mDensity;
    }

    /** @hide */
    public Canvas(long nativeCanvas) {
        if (nativeCanvas == 0) {
            throw new IllegalStateException();
        }
        mNativeCanvasWrapper = nativeCanvas;
        mFinalizer = new CanvasFinalizer(mNativeCanvasWrapper);
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
     * Specify a bitmap for the canvas to draw into. All canvas state such as
     * layers, filters, and the save/restore stack are reset with the exception
     * of the current matrix and clip stack. Additionally, as a side-effect
     * the canvas' target density is updated to match that of the bitmap.
     *
     * @param bitmap Specifies a mutable bitmap for the canvas to draw into.
     * @see #setDensity(int)
     * @see #getDensity()
     */
    public void setBitmap(@Nullable Bitmap bitmap) {
        if (isHardwareAccelerated()) {
            throw new RuntimeException("Can't set a bitmap device on a HW accelerated canvas");
        }

        if (bitmap == null) {
            native_setBitmap(mNativeCanvasWrapper, 0, false);
            mDensity = Bitmap.DENSITY_NONE;
        } else {
            if (!bitmap.isMutable()) {
                throw new IllegalStateException();
            }
            throwIfCannotDraw(bitmap);

            native_setBitmap(mNativeCanvasWrapper, bitmap.ni(), true);
            mDensity = bitmap.mDensity;
        }

        mBitmap = bitmap;
    }

    /**
     * setBitmap() variant for native callers with a raw bitmap handle.
     */
    private void setNativeBitmap(long bitmapHandle) {
        native_setBitmap(mNativeCanvasWrapper, bitmapHandle, false);
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
    public void setViewport(int width, int height) {}

    /** @hide */
    public void setHighContrastText(boolean highContrastText) {}

    /** @hide */
    public void insertReorderBarrier() {}

    /** @hide */
    public void insertInorderBarrier() {}

    /**
     * Return true if the device that the current layer draws into is opaque
     * (i.e. does not support per-pixel alpha).
     *
     * @return true if the device that the current layer draws into is opaque
     */
    public boolean isOpaque() {
        return native_isOpaque(mNativeCanvasWrapper);
    }

    /**
     * Returns the width of the current drawing layer
     *
     * @return the width of the current drawing layer
     */
    public int getWidth() {
        return native_getWidth(mNativeCanvasWrapper);
    }

    /**
     * Returns the height of the current drawing layer
     *
     * @return the height of the current drawing layer
     */
    public int getHeight() {
        return native_getHeight(mNativeCanvasWrapper);
    }

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

    /** @hide */
    @IntDef(flag = true,
            value = {
                MATRIX_SAVE_FLAG,
                CLIP_SAVE_FLAG,
                HAS_ALPHA_LAYER_SAVE_FLAG,
                FULL_COLOR_LAYER_SAVE_FLAG,
                CLIP_TO_LAYER_SAVE_FLAG,
                ALL_SAVE_FLAG
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Saveflags {}

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
    public int save() {
        return native_save(mNativeCanvasWrapper, MATRIX_SAVE_FLAG | CLIP_SAVE_FLAG);
    }

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
    public int save(@Saveflags int saveFlags) {
        return native_save(mNativeCanvasWrapper, saveFlags);
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
     * @param bounds May be null. The maximum size the offscreen bitmap
     *               needs to be (in local coordinates)
     * @param paint  This is copied, and is applied to the offscreen when
     *               restore() is called.
     * @param saveFlags  see _SAVE_FLAG constants
     * @return       value to pass to restoreToCount() to balance this save()
     */
    public int saveLayer(@Nullable RectF bounds, @Nullable Paint paint, @Saveflags int saveFlags) {
        if (bounds == null) {
            bounds = new RectF(getClipBounds());
        }
        return saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, paint, saveFlags);
    }

    /**
     * Convenience for saveLayer(bounds, paint, {@link #ALL_SAVE_FLAG})
     */
    public int saveLayer(@Nullable RectF bounds, @Nullable Paint paint) {
        return saveLayer(bounds, paint, ALL_SAVE_FLAG);
    }

    /**
     * Helper version of saveLayer() that takes 4 values rather than a RectF.
     */
    public int saveLayer(float left, float top, float right, float bottom, @Nullable Paint paint,
            @Saveflags int saveFlags) {
        return native_saveLayer(mNativeCanvasWrapper, left, top, right, bottom,
                paint != null ? paint.mNativePaint : 0,
                saveFlags);
    }

    /**
     * Convenience for saveLayer(left, top, right, bottom, paint, {@link #ALL_SAVE_FLAG})
     */
    public int saveLayer(float left, float top, float right, float bottom, @Nullable Paint paint) {
        return saveLayer(left, top, right, bottom, paint, ALL_SAVE_FLAG);
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
    public int saveLayerAlpha(@Nullable RectF bounds, int alpha, @Saveflags int saveFlags) {
        if (bounds == null) {
            bounds = new RectF(getClipBounds());
        }
        return saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, alpha, saveFlags);
    }

    /**
     * Convenience for saveLayerAlpha(bounds, alpha, {@link #ALL_SAVE_FLAG})
     */
    public int saveLayerAlpha(@Nullable RectF bounds, int alpha) {
        return saveLayerAlpha(bounds, alpha, ALL_SAVE_FLAG);
    }

    /**
     * Helper for saveLayerAlpha() that takes 4 values instead of a RectF.
     */
    public int saveLayerAlpha(float left, float top, float right, float bottom, int alpha,
            @Saveflags int saveFlags) {
        alpha = Math.min(255, Math.max(0, alpha));
        return native_saveLayerAlpha(mNativeCanvasWrapper, left, top, right, bottom,
                                     alpha, saveFlags);
    }

    /**
     * Helper for saveLayerAlpha(left, top, right, bottom, alpha, {@link #ALL_SAVE_FLAG})
     */
    public int saveLayerAlpha(float left, float top, float right, float bottom, int alpha) {
        return saveLayerAlpha(left, top, right, bottom, alpha, ALL_SAVE_FLAG);
    }

    /**
     * This call balances a previous call to save(), and is used to remove all
     * modifications to the matrix/clip state since the last save call. It is
     * an error to call restore() more times than save() was called.
     */
    public void restore() {
        native_restore(mNativeCanvasWrapper);
    }

    /**
     * Returns the number of matrix/clip states on the Canvas' private stack.
     * This will equal # save() calls - # restore() calls.
     */
    public int getSaveCount() {
        return native_getSaveCount(mNativeCanvasWrapper);
    }

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
    public void restoreToCount(int saveCount) {
        native_restoreToCount(mNativeCanvasWrapper, saveCount);
    }

    /**
     * Preconcat the current matrix with the specified translation
     *
     * @param dx The distance to translate in X
     * @param dy The distance to translate in Y
    */
    public void translate(float dx, float dy) {
        native_translate(mNativeCanvasWrapper, dx, dy);
    }

    /**
     * Preconcat the current matrix with the specified scale.
     *
     * @param sx The amount to scale in X
     * @param sy The amount to scale in Y
     */
    public void scale(float sx, float sy) {
        native_scale(mNativeCanvasWrapper, sx, sy);
    }

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
    public void rotate(float degrees) {
        native_rotate(mNativeCanvasWrapper, degrees);
    }

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
    public void skew(float sx, float sy) {
        native_skew(mNativeCanvasWrapper, sx, sy);
    }

    /**
     * Preconcat the current matrix with the specified matrix. If the specified
     * matrix is null, this method does nothing.
     *
     * @param matrix The matrix to preconcatenate with the current matrix
     */
    public void concat(@Nullable Matrix matrix) {
        if (matrix != null) native_concat(mNativeCanvasWrapper, matrix.native_instance);
    }

    /**
     * Completely replace the current matrix with the specified matrix. If the
     * matrix parameter is null, then the current matrix is reset to identity.
     *
     * <strong>Note:</strong> it is recommended to use {@link #concat(Matrix)},
     * {@link #scale(float, float)}, {@link #translate(float, float)} and
     * {@link #rotate(float)} instead of this method.
     *
     * @param matrix The matrix to replace the current matrix with. If it is
     *               null, set the current matrix to identity.
     *
     * @see #concat(Matrix)
     */
    public void setMatrix(@Nullable Matrix matrix) {
        native_setMatrix(mNativeCanvasWrapper,
                         matrix == null ? 0 : matrix.native_instance);
    }

    /**
     * Return, in ctm, the current transformation matrix. This does not alter
     * the matrix in the canvas, but just returns a copy of it.
     */
    @Deprecated
    public void getMatrix(@NonNull Matrix ctm) {
        native_getCTM(mNativeCanvasWrapper, ctm.native_instance);
    }

    /**
     * Return a new matrix with a copy of the canvas' current transformation
     * matrix.
     */
    @Deprecated
    public final @NonNull Matrix getMatrix() {
        Matrix m = new Matrix();
        //noinspection deprecation
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
    public boolean clipRect(@NonNull RectF rect, @NonNull Region.Op op) {
        return native_clipRect(mNativeCanvasWrapper, rect.left, rect.top, rect.right, rect.bottom,
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
    public boolean clipRect(@NonNull Rect rect, @NonNull Region.Op op) {
        return native_clipRect(mNativeCanvasWrapper, rect.left, rect.top, rect.right, rect.bottom,
                op.nativeInt);
    }

    /**
     * Intersect the current clip with the specified rectangle, which is
     * expressed in local coordinates.
     *
     * @param rect The rectangle to intersect with the current clip.
     * @return true if the resulting clip is non-empty
     */
    public boolean clipRect(@NonNull RectF rect) {
        return native_clipRect(mNativeCanvasWrapper, rect.left, rect.top, rect.right, rect.bottom,
                Region.Op.INTERSECT.nativeInt);
    }

    /**
     * Intersect the current clip with the specified rectangle, which is
     * expressed in local coordinates.
     *
     * @param rect The rectangle to intersect with the current clip.
     * @return true if the resulting clip is non-empty
     */
    public boolean clipRect(@NonNull Rect rect) {
        return native_clipRect(mNativeCanvasWrapper, rect.left, rect.top, rect.right, rect.bottom,
                Region.Op.INTERSECT.nativeInt);
    }

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
            @NonNull Region.Op op) {
        return native_clipRect(mNativeCanvasWrapper, left, top, right, bottom, op.nativeInt);
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
    public boolean clipRect(float left, float top, float right, float bottom) {
        return native_clipRect(mNativeCanvasWrapper, left, top, right, bottom,
                Region.Op.INTERSECT.nativeInt);
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
    public boolean clipRect(int left, int top, int right, int bottom) {
        return native_clipRect(mNativeCanvasWrapper, left, top, right, bottom,
                Region.Op.INTERSECT.nativeInt);
    }

    /**
        * Modify the current clip with the specified path.
     *
     * @param path The path to operate on the current clip
     * @param op   How the clip is modified
     * @return     true if the resulting is non-empty
     */
    public boolean clipPath(@NonNull Path path, @NonNull Region.Op op) {
        return native_clipPath(mNativeCanvasWrapper, path.ni(), op.nativeInt);
    }

    /**
     * Intersect the current clip with the specified path.
     *
     * @param path The path to intersect with the current clip
     * @return     true if the resulting is non-empty
     */
    public boolean clipPath(@NonNull Path path) {
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
     *
     * @deprecated Unlike all other clip calls this API does not respect the
     *             current matrix. Use {@link #clipRect(Rect)} as an alternative.
     */
    public boolean clipRegion(@NonNull Region region, @NonNull Region.Op op) {
        return native_clipRegion(mNativeCanvasWrapper, region.ni(), op.nativeInt);
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
     *
     * @deprecated Unlike all other clip calls this API does not respect the
     *             current matrix. Use {@link #clipRect(Rect)} as an alternative.
     */
    public boolean clipRegion(@NonNull Region region) {
        return clipRegion(region, Region.Op.INTERSECT);
    }

    public @Nullable DrawFilter getDrawFilter() {
        return mDrawFilter;
    }

    public void setDrawFilter(@Nullable DrawFilter filter) {
        long nativeFilter = 0;
        if (filter != null) {
            nativeFilter = filter.mNativeInt;
        }
        mDrawFilter = filter;
        nativeSetDrawFilter(mNativeCanvasWrapper, nativeFilter);
    }

    public enum EdgeType {

        /**
         * Black-and-White: Treat edges by just rounding to nearest pixel boundary
         */
        BW(0),  //!< treat edges by just rounding to nearest pixel boundary

        /**
         * Antialiased: Treat edges by rounding-out, since they may be antialiased
         */
        AA(1);

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
     * @param type  {@link Canvas.EdgeType#AA} if the path should be considered antialiased,
     *              since that means it may affect a larger area (more pixels) than
     *              non-antialiased ({@link Canvas.EdgeType#BW}).
     * @return      true if the rect (transformed by the canvas' matrix)
     *              does not intersect with the canvas' clip
     */
    public boolean quickReject(@NonNull RectF rect, @NonNull EdgeType type) {
        return native_quickReject(mNativeCanvasWrapper,
                rect.left, rect.top, rect.right, rect.bottom);
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
     * @param type        {@link Canvas.EdgeType#AA} if the path should be considered antialiased,
     *                    since that means it may affect a larger area (more pixels) than
     *                    non-antialiased ({@link Canvas.EdgeType#BW}).
     * @return            true if the path (transformed by the canvas' matrix)
     *                    does not intersect with the canvas' clip
     */
    public boolean quickReject(@NonNull Path path, @NonNull EdgeType type) {
        return native_quickReject(mNativeCanvasWrapper, path.ni());
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
     * @param type        {@link Canvas.EdgeType#AA} if the path should be considered antialiased,
     *                    since that means it may affect a larger area (more pixels) than
     *                    non-antialiased ({@link Canvas.EdgeType#BW}).
     * @return            true if the rect (transformed by the canvas' matrix)
     *                    does not intersect with the canvas' clip
     */
    public boolean quickReject(float left, float top, float right, float bottom,
            @NonNull EdgeType type) {
        return native_quickReject(mNativeCanvasWrapper, left, top, right, bottom);
    }

    /**
     * Return the bounds of the current clip (in local coordinates) in the
     * bounds parameter, and return true if it is non-empty. This can be useful
     * in a way similar to quickReject, in that it tells you that drawing
     * outside of these bounds will be clipped out.
     *
     * @param bounds Return the clip bounds here. If it is null, ignore it but
     *               still return true if the current clip is non-empty.
     * @return true if the current clip is non-empty.
     */
    public boolean getClipBounds(@Nullable Rect bounds) {
        return native_getClipBounds(mNativeCanvasWrapper, bounds);
    }

    /**
     * Retrieve the bounds of the current clip (in local coordinates).
     *
     * @return the clip bounds, or [0, 0, 0, 0] if the clip is empty.
     */
    public final @NonNull Rect getClipBounds() {
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
        drawColor(Color.rgb(r, g, b));
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
        drawColor(Color.argb(a, r, g, b));
    }

    /**
     * Fill the entire canvas' bitmap (restricted to the current clip) with the
     * specified color, using srcover porterduff mode.
     *
     * @param color the color to draw onto the canvas
     */
    public void drawColor(int color) {
        native_drawColor(mNativeCanvasWrapper, color, PorterDuff.Mode.SRC_OVER.nativeInt);
    }

    /**
     * Fill the entire canvas' bitmap (restricted to the current clip) with the
     * specified color and porter-duff xfermode.
     *
     * @param color the color to draw with
     * @param mode  the porter-duff mode to apply to the color
     */
    public void drawColor(int color, @NonNull PorterDuff.Mode mode) {
        native_drawColor(mNativeCanvasWrapper, color, mode.nativeInt);
    }

    /**
     * Fill the entire canvas' bitmap (restricted to the current clip) with
     * the specified paint. This is equivalent (but faster) to drawing an
     * infinitely large rectangle with the specified paint.
     *
     * @param paint The paint used to draw onto the canvas
     */
    public void drawPaint(@NonNull Paint paint) {
        native_drawPaint(mNativeCanvasWrapper, paint.mNativePaint);
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
    public void drawPoints(float[] pts, int offset, int count, @NonNull Paint paint) {
        native_drawPoints(mNativeCanvasWrapper, pts, offset, count, paint.mNativePaint);
    }

    /**
     * Helper for drawPoints() that assumes you want to draw the entire array
     */
    public void drawPoints(@NonNull float[] pts, @NonNull Paint paint) {
        drawPoints(pts, 0, pts.length, paint);
    }

    /**
     * Helper for drawPoints() for drawing a single point.
     */
    public void drawPoint(float x, float y, @NonNull Paint paint) {
        native_drawPoint(mNativeCanvasWrapper, x, y, paint.mNativePaint);
    }

    /**
     * Draw a line segment with the specified start and stop x,y coordinates,
     * using the specified paint.
     *
     * <p>Note that since a line is always "framed", the Style is ignored in the paint.</p>
     *
     * <p>Degenerate lines (length is 0) will not be drawn.</p>
     *
     * @param startX The x-coordinate of the start point of the line
     * @param startY The y-coordinate of the start point of the line
     * @param paint  The paint used to draw the line
     */
    public void drawLine(float startX, float startY, float stopX, float stopY,
            @NonNull Paint paint) {
        native_drawLine(mNativeCanvasWrapper, startX, startY, stopX, stopY, paint.mNativePaint);
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
    public void drawLines(float[] pts, int offset, int count, Paint paint) {
        native_drawLines(mNativeCanvasWrapper, pts, offset, count, paint.mNativePaint);
    }

    public void drawLines(@NonNull float[] pts, @NonNull Paint paint) {
        drawLines(pts, 0, pts.length, paint);
    }

    /**
     * Draw the specified Rect using the specified paint. The rectangle will
     * be filled or framed based on the Style in the paint.
     *
     * @param rect  The rect to be drawn
     * @param paint The paint used to draw the rect
     */
    public void drawRect(@NonNull RectF rect, @NonNull Paint paint) {
        native_drawRect(mNativeCanvasWrapper,
                rect.left, rect.top, rect.right, rect.bottom, paint.mNativePaint);
    }

    /**
     * Draw the specified Rect using the specified Paint. The rectangle
     * will be filled or framed based on the Style in the paint.
     *
     * @param r        The rectangle to be drawn.
     * @param paint    The paint used to draw the rectangle
     */
    public void drawRect(@NonNull Rect r, @NonNull Paint paint) {
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
    public void drawRect(float left, float top, float right, float bottom, @NonNull Paint paint) {
        native_drawRect(mNativeCanvasWrapper, left, top, right, bottom, paint.mNativePaint);
    }

    /**
     * Draw the specified oval using the specified paint. The oval will be
     * filled or framed based on the Style in the paint.
     *
     * @param oval The rectangle bounds of the oval to be drawn
     */
    public void drawOval(@NonNull RectF oval, @NonNull Paint paint) {
        if (oval == null) {
            throw new NullPointerException();
        }
        drawOval(oval.left, oval.top, oval.right, oval.bottom, paint);
    }

    /**
     * Draw the specified oval using the specified paint. The oval will be
     * filled or framed based on the Style in the paint.
     */
    public void drawOval(float left, float top, float right, float bottom, @NonNull Paint paint) {
        native_drawOval(mNativeCanvasWrapper, left, top, right, bottom, paint.mNativePaint);
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
    public void drawCircle(float cx, float cy, float radius, @NonNull Paint paint) {
        native_drawCircle(mNativeCanvasWrapper, cx, cy, radius, paint.mNativePaint);
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
    public void drawArc(@NonNull RectF oval, float startAngle, float sweepAngle, boolean useCenter,
            @NonNull Paint paint) {
        drawArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, useCenter,
                paint);
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
     * @param startAngle Starting angle (in degrees) where the arc begins
     * @param sweepAngle Sweep angle (in degrees) measured clockwise
     * @param useCenter If true, include the center of the oval in the arc, and
                        close it if it is being stroked. This will draw a wedge
     * @param paint      The paint used to draw the arc
     */
    public void drawArc(float left, float top, float right, float bottom, float startAngle,
            float sweepAngle, boolean useCenter, @NonNull Paint paint) {
        native_drawArc(mNativeCanvasWrapper, left, top, right, bottom, startAngle, sweepAngle,
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
    public void drawRoundRect(@NonNull RectF rect, float rx, float ry, @NonNull Paint paint) {
        drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry, paint);
    }

    /**
     * Draw the specified round-rect using the specified paint. The roundrect
     * will be filled or framed based on the Style in the paint.
     *
     * @param rx    The x-radius of the oval used to round the corners
     * @param ry    The y-radius of the oval used to round the corners
     * @param paint The paint used to draw the roundRect
     */
    public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry,
            @NonNull Paint paint) {
        native_drawRoundRect(mNativeCanvasWrapper, left, top, right, bottom, rx, ry, paint.mNativePaint);
    }

    /**
     * Draw the specified path using the specified paint. The path will be
     * filled or framed based on the Style in the paint.
     *
     * @param path  The path to be drawn
     * @param paint The paint used to draw the path
     */
    public void drawPath(@NonNull Path path, @NonNull Paint paint) {
        native_drawPath(mNativeCanvasWrapper, path.ni(), paint.mNativePaint);
    }

    /**
     * @hide
     */
    protected static void throwIfCannotDraw(Bitmap bitmap) {
        if (bitmap.isRecycled()) {
            throw new RuntimeException("Canvas: trying to use a recycled bitmap " + bitmap);
        }
        if (!bitmap.isPremultiplied() && bitmap.getConfig() == Bitmap.Config.ARGB_8888 &&
                bitmap.hasAlpha()) {
            throw new RuntimeException("Canvas: trying to use a non-premultiplied bitmap "
                    + bitmap);
        }
    }

    /**
     * Draws the specified bitmap as an N-patch (most often, a 9-patches.)
     *
     * @param patch The ninepatch object to render
     * @param dst The destination rectangle.
     * @param paint The paint to draw the bitmap with. may be null
     *
     * @hide
     */
    public void drawPatch(@NonNull NinePatch patch, @NonNull Rect dst, @Nullable Paint paint) {
        patch.drawSoftware(this, dst, paint);
    }

    /**
     * Draws the specified bitmap as an N-patch (most often, a 9-patches.)
     *
     * @param patch The ninepatch object to render
     * @param dst The destination rectangle.
     * @param paint The paint to draw the bitmap with. may be null
     *
     * @hide
     */
    public void drawPatch(@NonNull NinePatch patch, @NonNull RectF dst, @Nullable Paint paint) {
        patch.drawSoftware(this, dst, paint);
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
    public void drawBitmap(@NonNull Bitmap bitmap, float left, float top, @Nullable Paint paint) {
        throwIfCannotDraw(bitmap);
        native_drawBitmap(mNativeCanvasWrapper, bitmap.ni(), left, top,
                paint != null ? paint.mNativePaint : 0, mDensity, mScreenDensity, bitmap.mDensity);
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
    public void drawBitmap(@NonNull Bitmap bitmap, @Nullable Rect src, @NonNull RectF dst,
            @Nullable Paint paint) {
      if (dst == null) {
          throw new NullPointerException();
      }
      throwIfCannotDraw(bitmap);
      final long nativePaint = paint == null ? 0 : paint.mNativePaint;

      float left, top, right, bottom;
      if (src == null) {
          left = top = 0;
          right = bitmap.getWidth();
          bottom = bitmap.getHeight();
      } else {
          left = src.left;
          right = src.right;
          top = src.top;
          bottom = src.bottom;
      }

      native_drawBitmap(mNativeCanvasWrapper, bitmap.ni(), left, top, right, bottom,
              dst.left, dst.top, dst.right, dst.bottom, nativePaint, mScreenDensity,
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
    public void drawBitmap(@NonNull Bitmap bitmap, @Nullable Rect src, @NonNull Rect dst,
            @Nullable Paint paint) {
        if (dst == null) {
            throw new NullPointerException();
        }
        throwIfCannotDraw(bitmap);
        final long nativePaint = paint == null ? 0 : paint.mNativePaint;

        int left, top, right, bottom;
        if (src == null) {
            left = top = 0;
            right = bitmap.getWidth();
            bottom = bitmap.getHeight();
        } else {
            left = src.left;
            right = src.right;
            top = src.top;
            bottom = src.bottom;
        }

        native_drawBitmap(mNativeCanvasWrapper, bitmap.ni(), left, top, right, bottom,
            dst.left, dst.top, dst.right, dst.bottom, nativePaint, mScreenDensity,
            bitmap.mDensity);
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
     *
     * @deprecated Usage with a {@link #isHardwareAccelerated() hardware accelerated} canvas
     * requires an internal copy of color buffer contents every time this method is called. Using a
     * Bitmap avoids this copy, and allows the application to more explicitly control the lifetime
     * and copies of pixel data.
     */
    @Deprecated
    public void drawBitmap(@NonNull int[] colors, int offset, int stride, float x, float y,
            int width, int height, boolean hasAlpha, @Nullable Paint paint) {
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
        native_drawBitmap(mNativeCanvasWrapper, colors, offset, stride, x, y, width, height, hasAlpha,
                paint != null ? paint.mNativePaint : 0);
    }

    /**
     * Legacy version of drawBitmap(int[] colors, ...) that took ints for x,y
     *
     * @deprecated Usage with a {@link #isHardwareAccelerated() hardware accelerated} canvas
     * requires an internal copy of color buffer contents every time this method is called. Using a
     * Bitmap avoids this copy, and allows the application to more explicitly control the lifetime
     * and copies of pixel data.
     */
    @Deprecated
    public void drawBitmap(@NonNull int[] colors, int offset, int stride, int x, int y,
            int width, int height, boolean hasAlpha, @Nullable Paint paint) {
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
    public void drawBitmap(@NonNull Bitmap bitmap, @NonNull Matrix matrix, @Nullable Paint paint) {
        nativeDrawBitmapMatrix(mNativeCanvasWrapper, bitmap.ni(), matrix.ni(),
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
     * method is drawVertices().
     *
     * @param bitmap The bitmap to draw using the mesh
     * @param meshWidth The number of columns in the mesh. Nothing is drawn if
     *                  this is 0
     * @param meshHeight The number of rows in the mesh. Nothing is drawn if
     *                   this is 0
     * @param verts Array of x,y pairs, specifying where the mesh should be
     *              drawn. There must be at least
     *              (meshWidth+1) * (meshHeight+1) * 2 + vertOffset values
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
    public void drawBitmapMesh(@NonNull Bitmap bitmap, int meshWidth, int meshHeight,
            @NonNull float[] verts, int vertOffset, @Nullable int[] colors, int colorOffset,
            @Nullable Paint paint) {
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
        nativeDrawBitmapMesh(mNativeCanvasWrapper, bitmap.ni(), meshWidth, meshHeight,
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
    public void drawVertices(@NonNull VertexMode mode, int vertexCount, @NonNull float[] verts,
            int vertOffset, @Nullable float[] texs, int texOffset, @Nullable int[] colors,
            int colorOffset, @Nullable short[] indices, int indexOffset, int indexCount,
            @NonNull Paint paint) {
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
        nativeDrawVertices(mNativeCanvasWrapper, mode.nativeInt, vertexCount, verts,
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
    public void drawText(@NonNull char[] text, int index, int count, float x, float y,
            @NonNull Paint paint) {
        if ((index | count | (index + count) |
            (text.length - index - count)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        native_drawText(mNativeCanvasWrapper, text, index, count, x, y, paint.mBidiFlags,
                paint.mNativePaint, paint.mNativeTypeface);
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
    public void drawText(@NonNull String text, float x, float y, @NonNull Paint paint) {
        native_drawText(mNativeCanvasWrapper, text, 0, text.length(), x, y, paint.mBidiFlags,
                paint.mNativePaint, paint.mNativeTypeface);
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
    public void drawText(@NonNull String text, int start, int end, float x, float y,
            @NonNull Paint paint) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        native_drawText(mNativeCanvasWrapper, text, start, end, x, y, paint.mBidiFlags,
                paint.mNativePaint, paint.mNativeTypeface);
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
    public void drawText(@NonNull CharSequence text, int start, int end, float x, float y,
            @NonNull Paint paint) {
        if (text instanceof String || text instanceof SpannedString ||
            text instanceof SpannableString) {
            native_drawText(mNativeCanvasWrapper, text.toString(), start, end, x, y,
                    paint.mBidiFlags, paint.mNativePaint, paint.mNativeTypeface);
        } else if (text instanceof GraphicsOperations) {
            ((GraphicsOperations) text).drawText(this, start, end, x, y,
                    paint);
        } else {
            char[] buf = TemporaryBuffer.obtain(end - start);
            TextUtils.getChars(text, start, end, buf, 0);
            native_drawText(mNativeCanvasWrapper, buf, 0, end - start, x, y,
                    paint.mBidiFlags, paint.mNativePaint, paint.mNativeTypeface);
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
     * @param isRtl whether the run is in RTL direction
     * @param paint the paint
     * @hide
     */
    public void drawTextRun(@NonNull char[] text, int index, int count, int contextIndex,
            int contextCount, float x, float y, boolean isRtl, @NonNull Paint paint) {

        if (text == null) {
            throw new NullPointerException("text is null");
        }
        if (paint == null) {
            throw new NullPointerException("paint is null");
        }
        if ((index | count | text.length - index - count) < 0) {
            throw new IndexOutOfBoundsException();
        }

        native_drawTextRun(mNativeCanvasWrapper, text, index, count,
                contextIndex, contextCount, x, y, isRtl, paint.mNativePaint, paint.mNativeTypeface);
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
     * @param isRtl whether the run is in RTL direction
     * @param paint the paint
     * @hide
     */
    public void drawTextRun(@NonNull CharSequence text, int start, int end, int contextStart,
            int contextEnd, float x, float y, boolean isRtl, @NonNull Paint paint) {

        if (text == null) {
            throw new NullPointerException("text is null");
        }
        if (paint == null) {
            throw new NullPointerException("paint is null");
        }
        if ((start | end | end - start | text.length() - end) < 0) {
            throw new IndexOutOfBoundsException();
        }

        if (text instanceof String || text instanceof SpannedString ||
                text instanceof SpannableString) {
            native_drawTextRun(mNativeCanvasWrapper, text.toString(), start, end,
                    contextStart, contextEnd, x, y, isRtl, paint.mNativePaint, paint.mNativeTypeface);
        } else if (text instanceof GraphicsOperations) {
            ((GraphicsOperations) text).drawTextRun(this, start, end,
                    contextStart, contextEnd, x, y, isRtl, paint);
        } else {
            int contextLen = contextEnd - contextStart;
            int len = end - start;
            char[] buf = TemporaryBuffer.obtain(contextLen);
            TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
            native_drawTextRun(mNativeCanvasWrapper, buf, start - contextStart, len,
                    0, contextLen, x, y, isRtl, paint.mNativePaint, paint.mNativeTypeface);
            TemporaryBuffer.recycle(buf);
        }
    }

    /**
     * Draw the text in the array, with each character's origin specified by
     * the pos array.
     *
     * This method does not support glyph composition and decomposition and
     * should therefore not be used to render complex scripts. It also doesn't
     * handle supplementary characters (eg emoji).
     *
     * @param text     The text to be drawn
     * @param index    The index of the first character to draw
     * @param count    The number of characters to draw, starting from index.
     * @param pos      Array of [x,y] positions, used to position each
     *                 character
     * @param paint    The paint used for the text (e.g. color, size, style)
     */
    @Deprecated
    public void drawPosText(@NonNull char[] text, int index, int count, @NonNull float[] pos,
            @NonNull Paint paint) {
        if (index < 0 || index + count > text.length || count*2 > pos.length) {
            throw new IndexOutOfBoundsException();
        }
        for (int i = 0; i < count; i++) {
            drawText(text, index + i, 1, pos[i * 2], pos[i * 2 + 1], paint);
        }
    }

    /**
     * Draw the text in the array, with each character's origin specified by
     * the pos array.
     *
     * This method does not support glyph composition and decomposition and
     * should therefore not be used to render complex scripts. It also doesn't
     * handle supplementary characters (eg emoji).
     *
     * @param text  The text to be drawn
     * @param pos   Array of [x,y] positions, used to position each character
     * @param paint The paint used for the text (e.g. color, size, style)
     */
    @Deprecated
    public void drawPosText(@NonNull String text, @NonNull float[] pos, @NonNull Paint paint) {
        drawPosText(text.toCharArray(), 0, text.length(), pos, paint);
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
    public void drawTextOnPath(@NonNull char[] text, int index, int count, @NonNull Path path,
            float hOffset, float vOffset, @NonNull Paint paint) {
        if (index < 0 || index + count > text.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        native_drawTextOnPath(mNativeCanvasWrapper, text, index, count,
                path.ni(), hOffset, vOffset,
                paint.mBidiFlags, paint.mNativePaint, paint.mNativeTypeface);
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
    public void drawTextOnPath(@NonNull String text, @NonNull Path path, float hOffset,
            float vOffset, @NonNull Paint paint) {
        if (text.length() > 0) {
            native_drawTextOnPath(mNativeCanvasWrapper, text, path.ni(), hOffset, vOffset,
                    paint.mBidiFlags, paint.mNativePaint, paint.mNativeTypeface);
        }
    }

    /**
     * Save the canvas state, draw the picture, and restore the canvas state.
     * This differs from picture.draw(canvas), which does not perform any
     * save/restore.
     *
     * <p>
     * <strong>Note:</strong> This forces the picture to internally call
     * {@link Picture#endRecording} in order to prepare for playback.
     *
     * @param picture  The picture to be drawn
     */
    public void drawPicture(@NonNull Picture picture) {
        picture.endRecording();
        int restoreCount = save();
        picture.draw(this);
        restoreToCount(restoreCount);
    }

    /**
     * Draw the picture, stretched to fit into the dst rectangle.
     */
    public void drawPicture(@NonNull Picture picture, @NonNull RectF dst) {
        save();
        translate(dst.left, dst.top);
        if (picture.getWidth() > 0 && picture.getHeight() > 0) {
            scale(dst.width() / picture.getWidth(), dst.height() / picture.getHeight());
        }
        drawPicture(picture);
        restore();
    }

    /**
     * Draw the picture, stretched to fit into the dst rectangle.
     */
    public void drawPicture(@NonNull Picture picture, @NonNull Rect dst) {
        save();
        translate(dst.left, dst.top);
        if (picture.getWidth() > 0 && picture.getHeight() > 0) {
            scale((float) dst.width() / picture.getWidth(),
                    (float) dst.height() / picture.getHeight());
        }
        drawPicture(picture);
        restore();
    }

    /**
     * Releases the resources associated with this canvas.
     *
     * @hide
     */
    public void release() {
        mFinalizer.dispose();
    }

    /**
     * Free up as much memory as possible from private caches (e.g. fonts, images)
     *
     * @hide
     */
    public static native void freeCaches();

    /**
     * Free up text layout caches
     *
     * @hide
     */
    public static native void freeTextLayoutCaches();

    private static native long initRaster(long nativeBitmapOrZero);
    private static native void native_setBitmap(long canvasHandle,
                                                long bitmapHandle,
                                                boolean copyState);
    private static native boolean native_isOpaque(long canvasHandle);
    private static native int native_getWidth(long canvasHandle);
    private static native int native_getHeight(long canvasHandle);

    private static native int native_save(long canvasHandle, int saveFlags);
    private static native int native_saveLayer(long nativeCanvas, float l,
                                               float t, float r, float b,
                                               long nativePaint,
                                               int layerFlags);
    private static native int native_saveLayerAlpha(long nativeCanvas, float l,
                                                    float t, float r, float b,
                                                    int alpha, int layerFlags);
    private static native void native_restore(long canvasHandle);
    private static native void native_restoreToCount(long canvasHandle,
                                                     int saveCount);
    private static native int native_getSaveCount(long canvasHandle);

    private static native void native_translate(long canvasHandle,
                                                float dx, float dy);
    private static native void native_scale(long canvasHandle,
                                            float sx, float sy);
    private static native void native_rotate(long canvasHandle, float degrees);
    private static native void native_skew(long canvasHandle,
                                           float sx, float sy);
    private static native void native_concat(long nativeCanvas,
                                             long nativeMatrix);
    private static native void native_setMatrix(long nativeCanvas,
                                                long nativeMatrix);
    private static native boolean native_clipRect(long nativeCanvas,
                                                  float left, float top,
                                                  float right, float bottom,
                                                  int regionOp);
    private static native boolean native_clipPath(long nativeCanvas,
                                                  long nativePath,
                                                  int regionOp);
    private static native boolean native_clipRegion(long nativeCanvas,
                                                    long nativeRegion,
                                                    int regionOp);
    private static native void nativeSetDrawFilter(long nativeCanvas,
                                                   long nativeFilter);
    private static native boolean native_getClipBounds(long nativeCanvas,
                                                       Rect bounds);
    private static native void native_getCTM(long nativeCanvas,
                                             long nativeMatrix);
    private static native boolean native_quickReject(long nativeCanvas,
                                                     long nativePath);
    private static native boolean native_quickReject(long nativeCanvas,
                                                     float left, float top,
                                                     float right, float bottom);
    private static native void native_drawColor(long nativeCanvas, int color,
                                                int mode);
    private static native void native_drawPaint(long nativeCanvas,
                                                long nativePaint);
    private static native void native_drawPoint(long canvasHandle, float x, float y,
                                                long paintHandle);
    private static native void native_drawPoints(long canvasHandle, float[] pts,
                                                 int offset, int count,
                                                 long paintHandle);
    private static native void native_drawLine(long nativeCanvas, float startX,
                                               float startY, float stopX,
                                               float stopY, long nativePaint);
    private static native void native_drawLines(long canvasHandle, float[] pts,
                                                int offset, int count,
                                                long paintHandle);
    private static native void native_drawRect(long nativeCanvas, float left,
                                               float top, float right,
                                               float bottom,
                                               long nativePaint);
    private static native void native_drawOval(long nativeCanvas, float left, float top,
                                               float right, float bottom, long nativePaint);
    private static native void native_drawCircle(long nativeCanvas, float cx,
                                                 float cy, float radius,
                                                 long nativePaint);
    private static native void native_drawArc(long nativeCanvas, float left, float top,
                                              float right, float bottom,
                                              float startAngle, float sweep, boolean useCenter,
                                              long nativePaint);
    private static native void native_drawRoundRect(long nativeCanvas,
            float left, float top, float right, float bottom,
            float rx, float ry, long nativePaint);
    private static native void native_drawPath(long nativeCanvas,
                                               long nativePath,
                                               long nativePaint);
    private native void native_drawBitmap(long nativeCanvas, long nativeBitmap,
                                                 float left, float top,
                                                 long nativePaintOrZero,
                                                 int canvasDensity,
                                                 int screenDensity,
                                                 int bitmapDensity);
    private native void native_drawBitmap(long nativeCanvas, long nativeBitmap,
            float srcLeft, float srcTop, float srcRight, float srcBottom,
            float dstLeft, float dstTop, float dstRight, float dstBottom,
            long nativePaintOrZero, int screenDensity, int bitmapDensity);
    private static native void native_drawBitmap(long nativeCanvas, int[] colors,
                                                int offset, int stride, float x,
                                                 float y, int width, int height,
                                                 boolean hasAlpha,
                                                 long nativePaintOrZero);
    private static native void nativeDrawBitmapMatrix(long nativeCanvas,
                                                      long nativeBitmap,
                                                      long nativeMatrix,
                                                      long nativePaint);
    private static native void nativeDrawBitmapMesh(long nativeCanvas,
                                                    long nativeBitmap,
                                                    int meshWidth, int meshHeight,
                                                    float[] verts, int vertOffset,
                                                    int[] colors, int colorOffset,
                                                    long nativePaint);
    private static native void nativeDrawVertices(long nativeCanvas, int mode, int n,
                   float[] verts, int vertOffset, float[] texs, int texOffset,
                   int[] colors, int colorOffset, short[] indices,
                   int indexOffset, int indexCount, long nativePaint);

    private static native void native_drawText(long nativeCanvas, char[] text,
                                               int index, int count, float x,
                                               float y, int flags, long nativePaint,
                                               long nativeTypeface);
    private static native void native_drawText(long nativeCanvas, String text,
                                               int start, int end, float x,
                                               float y, int flags, long nativePaint,
                                               long nativeTypeface);

    private static native void native_drawTextRun(long nativeCanvas, String text,
            int start, int end, int contextStart, int contextEnd,
            float x, float y, boolean isRtl, long nativePaint, long nativeTypeface);

    private static native void native_drawTextRun(long nativeCanvas, char[] text,
            int start, int count, int contextStart, int contextCount,
            float x, float y, boolean isRtl, long nativePaint, long nativeTypeface);

    private static native void native_drawTextOnPath(long nativeCanvas,
                                                     char[] text, int index,
                                                     int count, long nativePath,
                                                     float hOffset,
                                                     float vOffset, int bidiFlags,
                                                     long nativePaint, long nativeTypeface);
    private static native void native_drawTextOnPath(long nativeCanvas,
                                                     String text, long nativePath,
                                                     float hOffset,
                                                     float vOffset,
                                                     int flags, long nativePaint, long nativeTypeface);
    private static native void finalizer(long nativeCanvas);
}
