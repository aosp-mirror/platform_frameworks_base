/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.ColorInt;
import android.annotation.ColorLong;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Canvas.VertexMode;
import android.graphics.fonts.Font;
import android.graphics.text.MeasuredText;
import android.graphics.text.TextRunShaper;
import android.text.GraphicsOperations;
import android.text.MeasuredParagraph;
import android.text.PrecomputedText;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.TextShaper;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * This class is a base class for Canvas's drawing operations. Any modifications here
 * should be accompanied by a similar modification to {@link BaseRecordingCanvas}.
 *
 * The purpose of this class is to minimize the cost of deciding between regular JNI
 * and @FastNative JNI to just the virtual call that Canvas already has.
 *
 * @hide
 */
public abstract class BaseCanvas {
    /**
     * Should only be assigned in constructors (or setBitmap if software canvas),
     * freed by NativeAllocation.
     * @hide
     */
    @UnsupportedAppUsage
    protected long mNativeCanvasWrapper;

    /**
     * Used to determine when compatibility scaling is in effect.
     * @hide
     */
    protected int mScreenDensity = Bitmap.DENSITY_NONE;

    /**
     * @hide
     */
    protected int mDensity = Bitmap.DENSITY_NONE;
    private boolean mAllowHwBitmapsInSwMode = false;

    protected void throwIfCannotDraw(Bitmap bitmap) {
        if (bitmap.isRecycled()) {
            throw new RuntimeException("Canvas: trying to use a recycled bitmap " + bitmap);
        }
        if (!bitmap.isPremultiplied() && bitmap.getConfig() == Bitmap.Config.ARGB_8888 &&
                bitmap.hasAlpha()) {
            throw new RuntimeException("Canvas: trying to use a non-premultiplied bitmap "
                    + bitmap);
        }
        throwIfHwBitmapInSwMode(bitmap);
    }

    protected final static void checkRange(int length, int offset, int count) {
        if ((offset | count) < 0 || offset + count > length) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    public boolean isHardwareAccelerated() {
        return false;
    }

    // ---------------------------------------------------------------------------
    // Drawing methods
    // These are also implemented in RecordingCanvas so that we can
    // selectively apply on them
    // Everything below here is copy/pasted from Canvas.java
    // The JNI registration is handled by android_view_Canvas.cpp
    // ---------------------------------------------------------------------------

    public void drawArc(float left, float top, float right, float bottom, float startAngle,
            float sweepAngle, boolean useCenter, @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        nDrawArc(mNativeCanvasWrapper, left, top, right, bottom, startAngle, sweepAngle,
                useCenter, paint.getNativeInstance());
    }

    public void drawArc(@NonNull RectF oval, float startAngle, float sweepAngle, boolean useCenter,
            @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        drawArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, useCenter,
                paint);
    }

    public void drawARGB(int a, int r, int g, int b) {
        drawColor(Color.argb(a, r, g, b));
    }

    public void drawBitmap(@NonNull Bitmap bitmap, float left, float top, @Nullable Paint paint) {
        throwIfCannotDraw(bitmap);
        throwIfHasHwBitmapInSwMode(paint);
        nDrawBitmap(mNativeCanvasWrapper, bitmap.getNativeInstance(), left, top,
                paint != null ? paint.getNativeInstance() : 0, mDensity, mScreenDensity,
                bitmap.mDensity);
    }

    public void drawBitmap(@NonNull Bitmap bitmap, @NonNull Matrix matrix, @Nullable Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        nDrawBitmapMatrix(mNativeCanvasWrapper, bitmap.getNativeInstance(), matrix.ni(),
                paint != null ? paint.getNativeInstance() : 0);
    }

    public void drawBitmap(@NonNull Bitmap bitmap, @Nullable Rect src, @NonNull Rect dst,
            @Nullable Paint paint) {
        if (dst == null) {
            throw new NullPointerException();
        }
        throwIfCannotDraw(bitmap);
        throwIfHasHwBitmapInSwMode(paint);
        final long nativePaint = paint == null ? 0 : paint.getNativeInstance();

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

        nDrawBitmap(mNativeCanvasWrapper, bitmap.getNativeInstance(), left, top, right, bottom,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint, mScreenDensity,
                bitmap.mDensity);
    }

    public void drawBitmap(@NonNull Bitmap bitmap, @Nullable Rect src, @NonNull RectF dst,
            @Nullable Paint paint) {
        if (dst == null) {
            throw new NullPointerException();
        }
        throwIfCannotDraw(bitmap);
        throwIfHasHwBitmapInSwMode(paint);
        final long nativePaint = paint == null ? 0 : paint.getNativeInstance();

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

        nDrawBitmap(mNativeCanvasWrapper, bitmap.getNativeInstance(), left, top, right, bottom,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint, mScreenDensity,
                bitmap.mDensity);
    }

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
        throwIfHasHwBitmapInSwMode(paint);
        // quick escape if there's nothing to draw
        if (width == 0 || height == 0) {
            return;
        }
        // punch down to native for the actual draw
        nDrawBitmap(mNativeCanvasWrapper, colors, offset, stride, x, y, width, height, hasAlpha,
                paint != null ? paint.getNativeInstance() : 0);
    }

    @Deprecated
    public void drawBitmap(@NonNull int[] colors, int offset, int stride, int x, int y,
            int width, int height, boolean hasAlpha, @Nullable Paint paint) {
        // call through to the common float version
        drawBitmap(colors, offset, stride, (float) x, (float) y, width, height,
                hasAlpha, paint);
    }

    public void drawBitmapMesh(@NonNull Bitmap bitmap, int meshWidth, int meshHeight,
            @NonNull float[] verts, int vertOffset, @Nullable int[] colors, int colorOffset,
            @Nullable Paint paint) {
        if ((meshWidth | meshHeight | vertOffset | colorOffset) < 0) {
            throw new ArrayIndexOutOfBoundsException();
        }
        throwIfHasHwBitmapInSwMode(paint);
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
        nDrawBitmapMesh(mNativeCanvasWrapper, bitmap.getNativeInstance(), meshWidth, meshHeight,
                verts, vertOffset, colors, colorOffset,
                paint != null ? paint.getNativeInstance() : 0);
    }

    public void drawCircle(float cx, float cy, float radius, @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        nDrawCircle(mNativeCanvasWrapper, cx, cy, radius, paint.getNativeInstance());
    }

    public void drawColor(@ColorInt int color) {
        nDrawColor(mNativeCanvasWrapper, color, BlendMode.SRC_OVER.getXfermode().porterDuffMode);
    }

    public void drawColor(@ColorInt int color, @NonNull PorterDuff.Mode mode) {
        nDrawColor(mNativeCanvasWrapper, color, mode.nativeInt);
    }

    /**
     * Make lint happy.
     * See {@link Canvas#drawColor(int, BlendMode)}
     */
    public void drawColor(@ColorInt int color, @NonNull BlendMode mode) {
        nDrawColor(mNativeCanvasWrapper, color, mode.getXfermode().porterDuffMode);
    }

    /**
     * Make lint happy.
     * See {@link Canvas#drawColor(long, BlendMode)}
     */
    public void drawColor(@ColorLong long color, @NonNull BlendMode mode) {
        ColorSpace cs = Color.colorSpace(color);
        nDrawColor(mNativeCanvasWrapper, cs.getNativeInstance(), color,
                mode.getXfermode().porterDuffMode);
    }

    public void drawLine(float startX, float startY, float stopX, float stopY,
            @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        nDrawLine(mNativeCanvasWrapper, startX, startY, stopX, stopY, paint.getNativeInstance());
    }

    public void drawLines(@Size(multiple = 4) @NonNull float[] pts, int offset, int count,
            @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        nDrawLines(mNativeCanvasWrapper, pts, offset, count, paint.getNativeInstance());
    }

    public void drawLines(@Size(multiple = 4) @NonNull float[] pts, @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        drawLines(pts, 0, pts.length, paint);
    }

    public void drawOval(float left, float top, float right, float bottom, @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        nDrawOval(mNativeCanvasWrapper, left, top, right, bottom, paint.getNativeInstance());
    }

    public void drawOval(@NonNull RectF oval, @NonNull Paint paint) {
        if (oval == null) {
            throw new NullPointerException();
        }
        throwIfHasHwBitmapInSwMode(paint);
        drawOval(oval.left, oval.top, oval.right, oval.bottom, paint);
    }

    public void drawPaint(@NonNull Paint paint) {
        nDrawPaint(mNativeCanvasWrapper, paint.getNativeInstance());
    }

    public void drawPatch(@NonNull NinePatch patch, @NonNull Rect dst, @Nullable Paint paint) {
        Bitmap bitmap = patch.getBitmap();
        throwIfCannotDraw(bitmap);
        throwIfHasHwBitmapInSwMode(paint);
        final long nativePaint = paint == null ? 0 : paint.getNativeInstance();
        nDrawNinePatch(mNativeCanvasWrapper, bitmap.getNativeInstance(), patch.mNativeChunk,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint,
                mDensity, patch.getDensity());
    }

    public void drawPatch(@NonNull NinePatch patch, @NonNull RectF dst, @Nullable Paint paint) {
        Bitmap bitmap = patch.getBitmap();
        throwIfCannotDraw(bitmap);
        throwIfHasHwBitmapInSwMode(paint);
        final long nativePaint = paint == null ? 0 : paint.getNativeInstance();
        nDrawNinePatch(mNativeCanvasWrapper, bitmap.getNativeInstance(), patch.mNativeChunk,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint,
                mDensity, patch.getDensity());
    }

    public void drawPath(@NonNull Path path, @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        if (path.isSimplePath && path.rects != null) {
            nDrawRegion(mNativeCanvasWrapper, path.rects.mNativeRegion, paint.getNativeInstance());
        } else {
            nDrawPath(mNativeCanvasWrapper, path.readOnlyNI(), paint.getNativeInstance());
        }
    }

    public void drawPoint(float x, float y, @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        nDrawPoint(mNativeCanvasWrapper, x, y, paint.getNativeInstance());
    }

    public void drawPoints(@Size(multiple = 2) float[] pts, int offset, int count,
            @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        nDrawPoints(mNativeCanvasWrapper, pts, offset, count, paint.getNativeInstance());
    }

    public void drawPoints(@Size(multiple = 2) @NonNull float[] pts, @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        drawPoints(pts, 0, pts.length, paint);
    }

    @Deprecated
    public void drawPosText(@NonNull char[] text, int index, int count,
            @NonNull @Size(multiple = 2) float[] pos,
            @NonNull Paint paint) {
        if (index < 0 || index + count > text.length || count * 2 > pos.length) {
            throw new IndexOutOfBoundsException();
        }
        throwIfHasHwBitmapInSwMode(paint);
        for (int i = 0; i < count; i++) {
            drawText(text, index + i, 1, pos[i * 2], pos[i * 2 + 1], paint);
        }
    }

    @Deprecated
    public void drawPosText(@NonNull String text, @NonNull @Size(multiple = 2) float[] pos,
            @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        drawPosText(text.toCharArray(), 0, text.length(), pos, paint);
    }

    public void drawRect(float left, float top, float right, float bottom, @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        nDrawRect(mNativeCanvasWrapper, left, top, right, bottom, paint.getNativeInstance());
    }

    public void drawRect(@NonNull Rect r, @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        drawRect(r.left, r.top, r.right, r.bottom, paint);
    }

    public void drawRect(@NonNull RectF rect, @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        nDrawRect(mNativeCanvasWrapper,
                rect.left, rect.top, rect.right, rect.bottom, paint.getNativeInstance());
    }

    public void drawRGB(int r, int g, int b) {
        drawColor(Color.rgb(r, g, b));
    }

    public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry,
            @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        nDrawRoundRect(mNativeCanvasWrapper, left, top, right, bottom, rx, ry,
                paint.getNativeInstance());
    }

    public void drawRoundRect(@NonNull RectF rect, float rx, float ry, @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry, paint);
    }

    /**
     * Make lint happy.
     * See {@link Canvas#drawDoubleRoundRect(RectF, float, float, RectF, float, float, Paint)}
     */
    public void drawDoubleRoundRect(@NonNull RectF outer, float outerRx, float outerRy,
            @NonNull RectF inner, float innerRx, float innerRy, @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        float outerLeft = outer.left;
        float outerTop = outer.top;
        float outerRight = outer.right;
        float outerBottom = outer.bottom;

        float innerLeft = inner.left;
        float innerTop = inner.top;
        float innerRight = inner.right;
        float innerBottom = inner.bottom;
        nDrawDoubleRoundRect(mNativeCanvasWrapper, outerLeft, outerTop, outerRight, outerBottom,
                outerRx, outerRy, innerLeft, innerTop, innerRight, innerBottom, innerRx, innerRy,
                paint.getNativeInstance());
    }

    /**
     * Make lint happy.
     * See {@link Canvas#drawDoubleRoundRect(RectF, float[], RectF, float[], Paint)}
     */
    public void drawDoubleRoundRect(@NonNull RectF outer, @NonNull float[] outerRadii,
            @NonNull RectF inner, @NonNull float[] innerRadii, @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        if (innerRadii == null || outerRadii == null
                || innerRadii.length != 8 || outerRadii.length != 8) {
            throw new IllegalArgumentException("Both inner and outer radii arrays must contain "
                    + "exactly 8 values");
        }
        float outerLeft = outer.left;
        float outerTop = outer.top;
        float outerRight = outer.right;
        float outerBottom = outer.bottom;

        float innerLeft = inner.left;
        float innerTop = inner.top;
        float innerRight = inner.right;
        float innerBottom = inner.bottom;
        nDrawDoubleRoundRect(mNativeCanvasWrapper, outerLeft, outerTop, outerRight,
                outerBottom, outerRadii, innerLeft, innerTop, innerRight, innerBottom, innerRadii,
                paint.getNativeInstance());
    }

    /**
     * Draw array of glyphs with specified font.
     *
     * @param glyphIds Array of glyph IDs. The length of array must be greater than or equal to
     *                 {@code glyphStart + glyphCount}.
     * @param glyphIdOffset Number of elements to skip before drawing in <code>glyphIds</code>
     *                     array.
     * @param positions A flattened X and Y position array. The first glyph X position must be
     *                  stored at {@code positionOffset}. The first glyph Y position must be stored
     *                  at {@code positionOffset + 1}, then the second glyph X position must be
     *                  stored at {@code positionOffset + 2}.
     *                 The length of array must be greater than or equal to
     *                 {@code positionOffset + glyphCount * 2}.
     * @param positionOffset Number of elements to skip before drawing in {@code positions}.
     *                       The first glyph X position must be stored at {@code positionOffset}.
     *                       The first glyph Y position must be stored at
     *                       {@code positionOffset + 1}, then the second glyph X position must be
     *                       stored at {@code positionOffset + 2}.
     * @param glyphCount Number of glyphs to be drawn.
     * @param font Font used for drawing.
     * @param paint Paint used for drawing. The typeface set to this paint is ignored.
     *
     * @see TextRunShaper
     * @see TextShaper
     */
    public void drawGlyphs(
            @NonNull int[] glyphIds,
            @IntRange(from = 0) int glyphIdOffset,
            @NonNull float[] positions,
            @IntRange(from = 0) int positionOffset,
            @IntRange(from = 0) int glyphCount,
            @NonNull Font font,
            @NonNull Paint paint) {
        Objects.requireNonNull(glyphIds, "glyphIds must not be null.");
        Objects.requireNonNull(positions, "positions must not be null.");
        Objects.requireNonNull(font, "font must not be null.");
        Objects.requireNonNull(paint, "paint must not be null.");
        Preconditions.checkArgumentNonnegative(glyphCount);

        if (glyphIdOffset < 0 || glyphIdOffset + glyphCount > glyphIds.length) {
            throw new IndexOutOfBoundsException(
                    "glyphIds must have at least " + (glyphIdOffset + glyphCount) + " of elements");
        }
        if (positionOffset < 0 || positionOffset + glyphCount * 2 > positions.length) {
            throw new IndexOutOfBoundsException(
                    "positions must have at least " + (positionOffset + glyphCount * 2)
                            + " of elements");
        }
        nDrawGlyphs(mNativeCanvasWrapper, glyphIds, positions, glyphIdOffset, positionOffset,
                glyphCount, font.getNativePtr(), paint.getNativeInstance());
    }

    public void drawText(@NonNull char[] text, int index, int count, float x, float y,
            @NonNull Paint paint) {
        if ((index | count | (index + count) |
                (text.length - index - count)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        throwIfHasHwBitmapInSwMode(paint);
        nDrawText(mNativeCanvasWrapper, text, index, count, x, y, paint.mBidiFlags,
                paint.getNativeInstance());
    }

    public void drawText(@NonNull CharSequence text, int start, int end, float x, float y,
            @NonNull Paint paint) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        throwIfHasHwBitmapInSwMode(paint);
        if (text instanceof String || text instanceof SpannedString ||
                text instanceof SpannableString) {
            nDrawText(mNativeCanvasWrapper, text.toString(), start, end, x, y,
                    paint.mBidiFlags, paint.getNativeInstance());
        } else if (text instanceof GraphicsOperations) {
            ((GraphicsOperations) text).drawText(this, start, end, x, y,
                    paint);
        } else {
            char[] buf = TemporaryBuffer.obtain(end - start);
            TextUtils.getChars(text, start, end, buf, 0);
            nDrawText(mNativeCanvasWrapper, buf, 0, end - start, x, y,
                    paint.mBidiFlags, paint.getNativeInstance());
            TemporaryBuffer.recycle(buf);
        }
    }

    public void drawText(@NonNull String text, float x, float y, @NonNull Paint paint) {
        throwIfHasHwBitmapInSwMode(paint);
        nDrawText(mNativeCanvasWrapper, text, 0, text.length(), x, y, paint.mBidiFlags,
                paint.getNativeInstance());
    }

    public void drawText(@NonNull String text, int start, int end, float x, float y,
            @NonNull Paint paint) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        throwIfHasHwBitmapInSwMode(paint);
        nDrawText(mNativeCanvasWrapper, text, start, end, x, y, paint.mBidiFlags,
                paint.getNativeInstance());
    }

    public void drawTextOnPath(@NonNull char[] text, int index, int count, @NonNull Path path,
            float hOffset, float vOffset, @NonNull Paint paint) {
        if (index < 0 || index + count > text.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        throwIfHasHwBitmapInSwMode(paint);
        nDrawTextOnPath(mNativeCanvasWrapper, text, index, count,
                path.readOnlyNI(), hOffset, vOffset,
                paint.mBidiFlags, paint.getNativeInstance());
    }

    public void drawTextOnPath(@NonNull String text, @NonNull Path path, float hOffset,
            float vOffset, @NonNull Paint paint) {
        if (text.length() > 0) {
            throwIfHasHwBitmapInSwMode(paint);
            nDrawTextOnPath(mNativeCanvasWrapper, text, path.readOnlyNI(), hOffset, vOffset,
                    paint.mBidiFlags, paint.getNativeInstance());
        }
    }

    public void drawTextRun(@NonNull char[] text, int index, int count, int contextIndex,
            int contextCount, float x, float y, boolean isRtl, @NonNull Paint paint) {

        if (text == null) {
            throw new NullPointerException("text is null");
        }
        if (paint == null) {
            throw new NullPointerException("paint is null");
        }
        if ((index | count | contextIndex | contextCount | index - contextIndex
                | (contextIndex + contextCount) - (index + count)
                | text.length - (contextIndex + contextCount)) < 0) {
            throw new IndexOutOfBoundsException();
        }

        throwIfHasHwBitmapInSwMode(paint);
        nDrawTextRun(mNativeCanvasWrapper, text, index, count, contextIndex, contextCount,
                x, y, isRtl, paint.getNativeInstance(), 0 /* measured text */);
    }

    public void drawTextRun(@NonNull CharSequence text, int start, int end, int contextStart,
            int contextEnd, float x, float y, boolean isRtl, @NonNull Paint paint) {

        if (text == null) {
            throw new NullPointerException("text is null");
        }
        if (paint == null) {
            throw new NullPointerException("paint is null");
        }
        if ((start | end | contextStart | contextEnd | start - contextStart | end - start
                | contextEnd - end | text.length() - contextEnd) < 0) {
            throw new IndexOutOfBoundsException();
        }

        throwIfHasHwBitmapInSwMode(paint);
        if (text instanceof String || text instanceof SpannedString ||
                text instanceof SpannableString) {
            nDrawTextRun(mNativeCanvasWrapper, text.toString(), start, end, contextStart,
                    contextEnd, x, y, isRtl, paint.getNativeInstance());
        } else if (text instanceof GraphicsOperations) {
            ((GraphicsOperations) text).drawTextRun(this, start, end,
                    contextStart, contextEnd, x, y, isRtl, paint);
        } else {
            if (text instanceof PrecomputedText) {
                final PrecomputedText pt = (PrecomputedText) text;
                final int paraIndex = pt.findParaIndex(start);
                if (end <= pt.getParagraphEnd(paraIndex)) {
                    final int paraStart = pt.getParagraphStart(paraIndex);
                    final MeasuredParagraph mp = pt.getMeasuredParagraph(paraIndex);
                    // Only support the text in the same paragraph.
                    drawTextRun(mp.getMeasuredText(),
                                start - paraStart,
                                end - paraStart,
                                contextStart - paraStart,
                                contextEnd - paraStart,
                                x, y, isRtl, paint);
                    return;
                }
            }
            int contextLen = contextEnd - contextStart;
            int len = end - start;
            char[] buf = TemporaryBuffer.obtain(contextLen);
            TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
            nDrawTextRun(mNativeCanvasWrapper, buf, start - contextStart, len,
                    0, contextLen, x, y, isRtl, paint.getNativeInstance(),
                    0 /* measured paragraph pointer */);
            TemporaryBuffer.recycle(buf);
        }
    }

    public void drawTextRun(@NonNull MeasuredText measuredText, int start, int end,
            int contextStart, int contextEnd, float x, float y, boolean isRtl,
            @NonNull Paint paint) {
        nDrawTextRun(mNativeCanvasWrapper, measuredText.getChars(), start, end - start,
                contextStart, contextEnd - contextStart, x, y, isRtl, paint.getNativeInstance(),
                measuredText.getNativePtr());
    }

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
        throwIfHasHwBitmapInSwMode(paint);
        nDrawVertices(mNativeCanvasWrapper, mode.nativeInt, vertexCount, verts,
                vertOffset, texs, texOffset, colors, colorOffset,
                indices, indexOffset, indexCount, paint.getNativeInstance());
    }

    /**
     * @hide
     */
    public void punchHole(float left, float top, float right, float bottom, float rx, float ry) {
        nPunchHole(mNativeCanvasWrapper, left, top, right, bottom, rx, ry);
    }

    /**
     * @hide
     */
    public void setHwBitmapsInSwModeEnabled(boolean enabled) {
        mAllowHwBitmapsInSwMode = enabled;
    }

    /**
     * @hide
     */
    public boolean isHwBitmapsInSwModeEnabled() {
        return mAllowHwBitmapsInSwMode;
    }

    /**
     * @hide
     */
    protected void onHwBitmapInSwMode() {
        if (!mAllowHwBitmapsInSwMode) {
            throw new IllegalArgumentException(
                    "Software rendering doesn't support hardware bitmaps");
        }
    }

    private void throwIfHwBitmapInSwMode(Bitmap bitmap) {
        if (!isHardwareAccelerated() && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            onHwBitmapInSwMode();
        }
    }

    private void throwIfHasHwBitmapInSwMode(Paint p) {
        if (isHardwareAccelerated() || p == null) {
            return;
        }
        throwIfHasHwBitmapInSwMode(p.getShader());
    }

    private void throwIfHasHwBitmapInSwMode(Shader shader) {
        if (shader == null) {
            return;
        }
        if (shader instanceof BitmapShader) {
            throwIfHwBitmapInSwMode(((BitmapShader) shader).mBitmap);
        }
        if (shader instanceof ComposeShader) {
            throwIfHasHwBitmapInSwMode(((ComposeShader) shader).mShaderA);
            throwIfHasHwBitmapInSwMode(((ComposeShader) shader).mShaderB);
        }
    }

    private static native void nDrawBitmap(long nativeCanvas, long bitmapHandle, float left,
            float top, long nativePaintOrZero, int canvasDensity, int screenDensity,
            int bitmapDensity);

    private static native void nDrawBitmap(long nativeCanvas, long bitmapHandle, float srcLeft,
            float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop, float dstRight,
            float dstBottom, long nativePaintOrZero, int screenDensity, int bitmapDensity);

    private static native void nDrawBitmap(long nativeCanvas, int[] colors, int offset, int stride,
            float x, float y, int width, int height, boolean hasAlpha, long nativePaintOrZero);

    private static native void nDrawColor(long nativeCanvas, int color, int mode);

    private static native void nDrawColor(long nativeCanvas, long nativeColorSpace,
            @ColorLong long color, int mode);

    private static native void nDrawPaint(long nativeCanvas, long nativePaint);

    private static native void nDrawPoint(long canvasHandle, float x, float y, long paintHandle);

    private static native void nDrawPoints(long canvasHandle, float[] pts, int offset, int count,
            long paintHandle);

    private static native void nDrawLine(long nativeCanvas, float startX, float startY, float stopX,
            float stopY, long nativePaint);

    private static native void nDrawLines(long canvasHandle, float[] pts, int offset, int count,
            long paintHandle);

    private static native void nDrawRect(long nativeCanvas, float left, float top, float right,
            float bottom, long nativePaint);

    private static native void nDrawOval(long nativeCanvas, float left, float top, float right,
            float bottom, long nativePaint);

    private static native void nDrawCircle(long nativeCanvas, float cx, float cy, float radius,
            long nativePaint);

    private static native void nDrawArc(long nativeCanvas, float left, float top, float right,
            float bottom, float startAngle, float sweep, boolean useCenter, long nativePaint);

    private static native void nDrawRoundRect(long nativeCanvas, float left, float top, float right,
            float bottom, float rx, float ry, long nativePaint);

    private static native void nDrawDoubleRoundRect(long nativeCanvas, float outerLeft,
            float outerTop, float outerRight, float outerBottom, float outerRx, float outerRy,
            float innerLeft, float innerTop, float innerRight, float innerBottom, float innerRx,
            float innerRy, long nativePaint);

    private static native void nDrawDoubleRoundRect(long nativeCanvas, float outerLeft,
            float outerTop, float outerRight, float outerBottom, float[] outerRadii,
            float innerLeft, float innerTop, float innerRight, float innerBottom,
            float[] innerRadii, long nativePaint);

    private static native void nDrawPath(long nativeCanvas, long nativePath, long nativePaint);

    private static native void nDrawRegion(long nativeCanvas, long nativeRegion, long nativePaint);

    private static native void nDrawNinePatch(long nativeCanvas, long nativeBitmap, long ninePatch,
            float dstLeft, float dstTop, float dstRight, float dstBottom, long nativePaintOrZero,
            int screenDensity, int bitmapDensity);

    private static native void nDrawBitmapMatrix(long nativeCanvas, long bitmapHandle,
            long nativeMatrix, long nativePaint);

    private static native void nDrawBitmapMesh(long nativeCanvas, long bitmapHandle, int meshWidth,
            int meshHeight, float[] verts, int vertOffset, int[] colors, int colorOffset,
            long nativePaint);

    private static native void nDrawVertices(long nativeCanvas, int mode, int n, float[] verts,
            int vertOffset, float[] texs, int texOffset, int[] colors, int colorOffset,
            short[] indices, int indexOffset, int indexCount, long nativePaint);

    private static native void nDrawGlyphs(long nativeCanvas, int[] glyphIds, float[] positions,
            int glyphIdStart, int positionStart, int glyphCount, long nativeFont, long nativePaint);

    private static native void nDrawText(long nativeCanvas, char[] text, int index, int count,
            float x, float y, int flags, long nativePaint);

    private static native void nDrawText(long nativeCanvas, String text, int start, int end,
            float x, float y, int flags, long nativePaint);

    private static native void nDrawTextRun(long nativeCanvas, String text, int start, int end,
            int contextStart, int contextEnd, float x, float y, boolean isRtl, long nativePaint);

    private static native void nDrawTextRun(long nativeCanvas, char[] text, int start, int count,
            int contextStart, int contextCount, float x, float y, boolean isRtl, long nativePaint,
            long nativePrecomputedText);

    private static native void nDrawTextOnPath(long nativeCanvas, char[] text, int index, int count,
            long nativePath, float hOffset, float vOffset, int bidiFlags, long nativePaint);

    private static native void nDrawTextOnPath(long nativeCanvas, String text, long nativePath,
            float hOffset, float vOffset, int flags, long nativePaint);

    private static native void nPunchHole(long renderer, float left, float top, float right,
            float bottom, float rx, float ry);
}
