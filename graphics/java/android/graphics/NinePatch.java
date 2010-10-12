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


/**
 * The NinePatch class permits drawing a bitmap in nine sections.
 * The four corners are unscaled; the four edges are scaled in one axis,
 * and the middle is scaled in both axes. Normally, the middle is 
 * transparent so that the patch can provide a selection about a rectangle.
 * Essentially, it allows the creation of custom graphics that will scale the 
 * way that you define, when content added within the image exceeds the normal
 * bounds of the graphic. For a thorough explanation of a NinePatch image, 
 * read the discussion in the 
 * <a href="{@docRoot}guide/topics/graphics/2d-graphics.html#nine-patch">2D
 * Graphics</a> document.
 * <p>
 * The <a href="{@docRoot}guide/developing/tools/draw9patch.html">Draw 9-Patch</a> 
 * tool offers an extremely handy way to create your NinePatch images,
 * using a WYSIWYG graphics editor.
 * </p>
 */
public class NinePatch {
    private final Bitmap mBitmap;
    private final byte[] mChunk;
    private Paint mPaint;
    private String mSrcName;  // Useful for debugging
    private final RectF mRect = new RectF();
    
    /** 
     * Create a drawable projection from a bitmap to nine patches.
     *
     * @param bitmap    The bitmap describing the patches.
     * @param chunk     The 9-patch data chunk describing how the underlying
     *                  bitmap is split apart and drawn.
     * @param srcName   The name of the source for the bitmap. Might be null.
     */
    public NinePatch(Bitmap bitmap, byte[] chunk, String srcName) {
        mBitmap = bitmap;
        mChunk = chunk;
        mSrcName = srcName;
        validateNinePatchChunk(mBitmap.ni(), chunk);
    }

    /**
     * @hide
     */
    public NinePatch(NinePatch patch) {
        mBitmap = patch.mBitmap;
        mChunk = patch.mChunk;
        mSrcName = patch.mSrcName;
        if (patch.mPaint != null) {
            mPaint = new Paint(patch.mPaint);
        }
        validateNinePatchChunk(mBitmap.ni(), mChunk);
    }

    public void setPaint(Paint p) {
        mPaint = p;
    }
    
    /** 
     * Draw a bitmap of nine patches.
     *
     * @param canvas    A container for the current matrix and clip used to draw the bitmap.
     * @param location  Where to draw the bitmap.
     */
    public void draw(Canvas canvas, RectF location) {
        if (!canvas.isHardwareAccelerated()) {
            nativeDraw(canvas.mNativeCanvas, location,
                       mBitmap.ni(), mChunk,
                       mPaint != null ? mPaint.mNativePaint : 0,
                       canvas.mDensity, mBitmap.mDensity);
        } else {
            canvas.drawPatch(mBitmap, mChunk, location, mPaint);
        }
    }
    
    /** 
     * Draw a bitmap of nine patches.
     *
     * @param canvas    A container for the current matrix and clip used to draw the bitmap.
     * @param location  Where to draw the bitmap.
     */
    public void draw(Canvas canvas, Rect location) {
        if (!canvas.isHardwareAccelerated()) {
            nativeDraw(canvas.mNativeCanvas, location,
                        mBitmap.ni(), mChunk,
                        mPaint != null ? mPaint.mNativePaint : 0,
                        canvas.mDensity, mBitmap.mDensity);
        } else {
            mRect.set(location);
            canvas.drawPatch(mBitmap, mChunk, mRect, mPaint);
        }
    }

    /** 
     * Draw a bitmap of nine patches.
     *
     * @param canvas    A container for the current matrix and clip used to draw the bitmap.
     * @param location  Where to draw the bitmap.
     * @param paint     The Paint to draw through.
     */
    public void draw(Canvas canvas, Rect location, Paint paint) {
        if (!canvas.isHardwareAccelerated()) {
            nativeDraw(canvas.mNativeCanvas, location,
                    mBitmap.ni(), mChunk, paint != null ? paint.mNativePaint : 0,
                    canvas.mDensity, mBitmap.mDensity);
        } else {
            mRect.set(location);
            canvas.drawPatch(mBitmap, mChunk, mRect, paint);
        }
    }

    /**
     * Return the underlying bitmap's density, as per
     * {@link Bitmap#getDensity() Bitmap.getDensity()}.
     */
    public int getDensity() {
        return mBitmap.mDensity;
    }
    
    public int getWidth() {
        return mBitmap.getWidth();
    }

    public int getHeight() {
        return mBitmap.getHeight();
    }

    public final boolean hasAlpha() {
        return mBitmap.hasAlpha();
    }

    public final Region getTransparentRegion(Rect location) {
        int r = nativeGetTransparentRegion(mBitmap.ni(), mChunk, location);
        return r != 0 ? new Region(r) : null;
    }
    
    public native static boolean isNinePatchChunk(byte[] chunk);

    private static native void validateNinePatchChunk(int bitmap, byte[] chunk);
    private static native void nativeDraw(int canvas_instance, RectF loc, int bitmap_instance,
                                          byte[] c, int paint_instance_or_null,
                                          int destDensity, int srcDensity);
    private static native void nativeDraw(int canvas_instance, Rect loc, int bitmap_instance,
                                          byte[] c, int paint_instance_or_null,
                                          int destDensity, int srcDensity);
    private static native int nativeGetTransparentRegion(
            int bitmap, byte[] chunk, Rect location);
}
