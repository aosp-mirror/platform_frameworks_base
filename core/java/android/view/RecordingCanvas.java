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

package android.view;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.graphics.BaseCanvas;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.NinePatch;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.TemporaryBuffer;
import android.text.GraphicsOperations;
import android.text.PrecomputedText;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.TextUtils;

import dalvik.annotation.optimization.FastNative;

/**
 * This class is a base class for canvases that defer drawing operations, so all
 * the draw operations can be marked @FastNative. It contains a re-implementation of
 * all the methods in {@link BaseCanvas}.
 *
 * @hide
 */
public class RecordingCanvas extends Canvas {

    public RecordingCanvas(long nativeCanvas) {
        super(nativeCanvas);
    }

    @Override
    public final void drawArc(float left, float top, float right, float bottom, float startAngle,
            float sweepAngle, boolean useCenter, @NonNull Paint paint) {
        nDrawArc(mNativeCanvasWrapper, left, top, right, bottom, startAngle, sweepAngle,
                useCenter, paint.getNativeInstance());
    }

    @Override
    public final void drawArc(@NonNull RectF oval, float startAngle, float sweepAngle,
            boolean useCenter, @NonNull Paint paint) {
        drawArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, useCenter,
                paint);
    }

    @Override
    public final void drawARGB(int a, int r, int g, int b) {
        drawColor(Color.argb(a, r, g, b));
    }

    @Override
    public final void drawBitmap(@NonNull Bitmap bitmap, float left, float top,
            @Nullable Paint paint) {
        throwIfCannotDraw(bitmap);
        nDrawBitmap(mNativeCanvasWrapper, bitmap, left, top,
                paint != null ? paint.getNativeInstance() : 0, mDensity, mScreenDensity,
                bitmap.mDensity);
    }

    @Override
    public final void drawBitmap(@NonNull Bitmap bitmap, @NonNull Matrix matrix,
            @Nullable Paint paint) {
        nDrawBitmapMatrix(mNativeCanvasWrapper, bitmap, matrix.ni(),
                paint != null ? paint.getNativeInstance() : 0);
    }

    @Override
    public final void drawBitmap(@NonNull Bitmap bitmap, @Nullable Rect src, @NonNull Rect dst,
            @Nullable Paint paint) {
        if (dst == null) {
            throw new NullPointerException();
        }
        throwIfCannotDraw(bitmap);
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

        nDrawBitmap(mNativeCanvasWrapper, bitmap, left, top, right, bottom,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint, mScreenDensity,
                bitmap.mDensity);
    }

    @Override
    public final void drawBitmap(@NonNull Bitmap bitmap, @Nullable Rect src, @NonNull RectF dst,
            @Nullable Paint paint) {
        if (dst == null) {
            throw new NullPointerException();
        }
        throwIfCannotDraw(bitmap);
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

        nDrawBitmap(mNativeCanvasWrapper, bitmap, left, top, right, bottom,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint, mScreenDensity,
                bitmap.mDensity);
    }

    /** @deprecated checkstyle */
    @Override
    @Deprecated
    public final void drawBitmap(@NonNull int[] colors, int offset, int stride, float x, float y,
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
        nDrawBitmap(mNativeCanvasWrapper, colors, offset, stride, x, y, width, height, hasAlpha,
                paint != null ? paint.getNativeInstance() : 0);
    }

    /** @deprecated checkstyle */
    @Override
    @Deprecated
    public final void drawBitmap(@NonNull int[] colors, int offset, int stride, int x, int y,
            int width, int height, boolean hasAlpha, @Nullable Paint paint) {
        // call through to the common float version
        drawBitmap(colors, offset, stride, (float) x, (float) y, width, height,
                hasAlpha, paint);
    }

    @Override
    public final void drawBitmapMesh(@NonNull Bitmap bitmap, int meshWidth, int meshHeight,
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
        nDrawBitmapMesh(mNativeCanvasWrapper, bitmap, meshWidth, meshHeight,
                verts, vertOffset, colors, colorOffset,
                paint != null ? paint.getNativeInstance() : 0);
    }

    @Override
    public final void drawCircle(float cx, float cy, float radius, @NonNull Paint paint) {
        nDrawCircle(mNativeCanvasWrapper, cx, cy, radius, paint.getNativeInstance());
    }

    @Override
    public final void drawColor(@ColorInt int color) {
        nDrawColor(mNativeCanvasWrapper, color, PorterDuff.Mode.SRC_OVER.nativeInt);
    }

    @Override
    public final void drawColor(@ColorInt int color, @NonNull PorterDuff.Mode mode) {
        nDrawColor(mNativeCanvasWrapper, color, mode.nativeInt);
    }

    @Override
    public final void drawLine(float startX, float startY, float stopX, float stopY,
            @NonNull Paint paint) {
        nDrawLine(mNativeCanvasWrapper, startX, startY, stopX, stopY, paint.getNativeInstance());
    }

    @Override
    public final void drawLines(@Size(multiple = 4) @NonNull float[] pts, int offset, int count,
            @NonNull Paint paint) {
        nDrawLines(mNativeCanvasWrapper, pts, offset, count, paint.getNativeInstance());
    }

    @Override
    public final void drawLines(@Size(multiple = 4) @NonNull float[] pts, @NonNull Paint paint) {
        drawLines(pts, 0, pts.length, paint);
    }

    @Override
    public final void drawOval(float left, float top, float right, float bottom,
            @NonNull Paint paint) {
        nDrawOval(mNativeCanvasWrapper, left, top, right, bottom, paint.getNativeInstance());
    }

    @Override
    public final void drawOval(@NonNull RectF oval, @NonNull Paint paint) {
        if (oval == null) {
            throw new NullPointerException();
        }
        drawOval(oval.left, oval.top, oval.right, oval.bottom, paint);
    }

    @Override
    public final void drawPaint(@NonNull Paint paint) {
        nDrawPaint(mNativeCanvasWrapper, paint.getNativeInstance());
    }

    @Override
    public final void drawPatch(@NonNull NinePatch patch, @NonNull Rect dst,
            @Nullable Paint paint) {
        Bitmap bitmap = patch.getBitmap();
        throwIfCannotDraw(bitmap);
        final long nativePaint = paint == null ? 0 : paint.getNativeInstance();
        nDrawNinePatch(mNativeCanvasWrapper, bitmap.getNativeInstance(), patch.mNativeChunk,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint,
                mDensity, patch.getDensity());
    }

    @Override
    public final void drawPatch(@NonNull NinePatch patch, @NonNull RectF dst,
            @Nullable Paint paint) {
        Bitmap bitmap = patch.getBitmap();
        throwIfCannotDraw(bitmap);
        final long nativePaint = paint == null ? 0 : paint.getNativeInstance();
        nDrawNinePatch(mNativeCanvasWrapper, bitmap.getNativeInstance(), patch.mNativeChunk,
                dst.left, dst.top, dst.right, dst.bottom, nativePaint,
                mDensity, patch.getDensity());
    }

    @Override
    public final void drawPath(@NonNull Path path, @NonNull Paint paint) {
        if (path.isSimplePath && path.rects != null) {
            nDrawRegion(mNativeCanvasWrapper, path.rects.mNativeRegion, paint.getNativeInstance());
        } else {
            nDrawPath(mNativeCanvasWrapper, path.readOnlyNI(), paint.getNativeInstance());
        }
    }

    @Override
    public final void drawPicture(@NonNull Picture picture) {
        picture.endRecording();
        int restoreCount = save();
        picture.draw(this);
        restoreToCount(restoreCount);
    }

    @Override
    public final void drawPicture(@NonNull Picture picture, @NonNull Rect dst) {
        save();
        translate(dst.left, dst.top);
        if (picture.getWidth() > 0 && picture.getHeight() > 0) {
            scale((float) dst.width() / picture.getWidth(),
                    (float) dst.height() / picture.getHeight());
        }
        drawPicture(picture);
        restore();
    }

    @Override
    public final void drawPicture(@NonNull Picture picture, @NonNull RectF dst) {
        save();
        translate(dst.left, dst.top);
        if (picture.getWidth() > 0 && picture.getHeight() > 0) {
            scale(dst.width() / picture.getWidth(), dst.height() / picture.getHeight());
        }
        drawPicture(picture);
        restore();
    }

    @Override
    public final void drawPoint(float x, float y, @NonNull Paint paint) {
        nDrawPoint(mNativeCanvasWrapper, x, y, paint.getNativeInstance());
    }

    @Override
    public final void drawPoints(@Size(multiple = 2) float[] pts, int offset, int count,
            @NonNull Paint paint) {
        nDrawPoints(mNativeCanvasWrapper, pts, offset, count, paint.getNativeInstance());
    }

    @Override
    public final void drawPoints(@Size(multiple = 2) @NonNull float[] pts, @NonNull Paint paint) {
        drawPoints(pts, 0, pts.length, paint);
    }

    /** @deprecated checkstyle */
    @Override
    @Deprecated
    public final void drawPosText(@NonNull char[] text, int index, int count,
            @NonNull @Size(multiple = 2) float[] pos,
            @NonNull Paint paint) {
        if (index < 0 || index + count > text.length || count * 2 > pos.length) {
            throw new IndexOutOfBoundsException();
        }
        for (int i = 0; i < count; i++) {
            drawText(text, index + i, 1, pos[i * 2], pos[i * 2 + 1], paint);
        }
    }

    /** @deprecated checkstyle */
    @Override
    @Deprecated
    public final void drawPosText(@NonNull String text, @NonNull @Size(multiple = 2) float[] pos,
            @NonNull Paint paint) {
        drawPosText(text.toCharArray(), 0, text.length(), pos, paint);
    }

    @Override
    public final void drawRect(float left, float top, float right, float bottom,
            @NonNull Paint paint) {
        nDrawRect(mNativeCanvasWrapper, left, top, right, bottom, paint.getNativeInstance());
    }

    @Override
    public final void drawRect(@NonNull Rect r, @NonNull Paint paint) {
        drawRect(r.left, r.top, r.right, r.bottom, paint);
    }

    @Override
    public final void drawRect(@NonNull RectF rect, @NonNull Paint paint) {
        nDrawRect(mNativeCanvasWrapper,
                rect.left, rect.top, rect.right, rect.bottom, paint.getNativeInstance());
    }

    @Override
    public final void drawRGB(int r, int g, int b) {
        drawColor(Color.rgb(r, g, b));
    }

    @Override
    public final void drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, @NonNull Paint paint) {
        nDrawRoundRect(mNativeCanvasWrapper, left, top, right, bottom, rx, ry,
                paint.getNativeInstance());
    }

    @Override
    public final void drawRoundRect(@NonNull RectF rect, float rx, float ry, @NonNull Paint paint) {
        drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry, paint);
    }

    @Override
    public final void drawText(@NonNull char[] text, int index, int count, float x, float y,
            @NonNull Paint paint) {
        if ((index | count | (index + count)
                | (text.length - index - count)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        nDrawText(mNativeCanvasWrapper, text, index, count, x, y, paint.mBidiFlags,
                paint.getNativeInstance());
    }

    @Override
    public final void drawText(@NonNull CharSequence text, int start, int end, float x, float y,
            @NonNull Paint paint) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (text instanceof String || text instanceof SpannedString
                || text instanceof SpannableString) {
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

    @Override
    public final void drawText(@NonNull String text, float x, float y, @NonNull Paint paint) {
        nDrawText(mNativeCanvasWrapper, text, 0, text.length(), x, y, paint.mBidiFlags,
                paint.getNativeInstance());
    }

    @Override
    public final void drawText(@NonNull String text, int start, int end, float x, float y,
            @NonNull Paint paint) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        nDrawText(mNativeCanvasWrapper, text, start, end, x, y, paint.mBidiFlags,
                paint.getNativeInstance());
    }

    @Override
    public final void drawTextOnPath(@NonNull char[] text, int index, int count, @NonNull Path path,
            float hOffset, float vOffset, @NonNull Paint paint) {
        if (index < 0 || index + count > text.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        nDrawTextOnPath(mNativeCanvasWrapper, text, index, count,
                path.readOnlyNI(), hOffset, vOffset,
                paint.mBidiFlags, paint.getNativeInstance());
    }

    @Override
    public final void drawTextOnPath(@NonNull String text, @NonNull Path path, float hOffset,
            float vOffset, @NonNull Paint paint) {
        if (text.length() > 0) {
            nDrawTextOnPath(mNativeCanvasWrapper, text, path.readOnlyNI(), hOffset, vOffset,
                    paint.mBidiFlags, paint.getNativeInstance());
        }
    }

    @Override
    public final void drawTextRun(@NonNull char[] text, int index, int count, int contextIndex,
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

        nDrawTextRun(mNativeCanvasWrapper, text, index, count, contextIndex, contextCount,
                x, y, isRtl, paint.getNativeInstance(), 0 /* measured text */);
    }

    @Override
    public final void drawTextRun(@NonNull CharSequence text, int start, int end, int contextStart,
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

        if (text instanceof String || text instanceof SpannedString
                || text instanceof SpannableString) {
            nDrawTextRun(mNativeCanvasWrapper, text.toString(), start, end, contextStart,
                    contextEnd, x, y, isRtl, paint.getNativeInstance());
        } else if (text instanceof GraphicsOperations) {
            ((GraphicsOperations) text).drawTextRun(this, start, end,
                    contextStart, contextEnd, x, y, isRtl, paint);
        } else {
            int contextLen = contextEnd - contextStart;
            int len = end - start;
            char[] buf = TemporaryBuffer.obtain(contextLen);
            TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
            long measuredTextPtr = 0;
            if (text instanceof PrecomputedText) {
                PrecomputedText mt = (PrecomputedText) text;
                int paraIndex = mt.findParaIndex(start);
                if (end <= mt.getParagraphEnd(paraIndex)) {
                    // Only support if the target is in the same paragraph.
                    measuredTextPtr = mt.getMeasuredParagraph(paraIndex).getNativePtr();
                }
            }
            nDrawTextRun(mNativeCanvasWrapper, buf, start - contextStart, len,
                    0, contextLen, x, y, isRtl, paint.getNativeInstance(), measuredTextPtr);
            TemporaryBuffer.recycle(buf);
        }
    }

    @Override
    public final void drawVertices(@NonNull VertexMode mode, int vertexCount,
            @NonNull float[] verts, int vertOffset, @Nullable float[] texs, int texOffset,
            @Nullable int[] colors, int colorOffset, @Nullable short[] indices, int indexOffset,
            int indexCount, @NonNull Paint paint) {
        checkRange(verts.length, vertOffset, vertexCount);
        if (isHardwareAccelerated()) {
            return;
        }
        if (texs != null) {
            checkRange(texs.length, texOffset, vertexCount);
        }
        if (colors != null) {
            checkRange(colors.length, colorOffset, vertexCount / 2);
        }
        if (indices != null) {
            checkRange(indices.length, indexOffset, indexCount);
        }
        nDrawVertices(mNativeCanvasWrapper, mode.nativeInt, vertexCount, verts,
                vertOffset, texs, texOffset, colors, colorOffset,
                indices, indexOffset, indexCount, paint.getNativeInstance());
    }

    @FastNative
    private static native void nDrawBitmap(long nativeCanvas, Bitmap bitmap, float left, float top,
            long nativePaintOrZero, int canvasDensity, int screenDensity, int bitmapDensity);

    @FastNative
    private static native void nDrawBitmap(long nativeCanvas, Bitmap bitmap,
            float srcLeft, float srcTop, float srcRight, float srcBottom,
            float dstLeft, float dstTop, float dstRight, float dstBottom,
            long nativePaintOrZero, int screenDensity, int bitmapDensity);

    @FastNative
    private static native void nDrawBitmap(long nativeCanvas, int[] colors, int offset, int stride,
            float x, float y, int width, int height, boolean hasAlpha, long nativePaintOrZero);

    @FastNative
    private static native void nDrawColor(long nativeCanvas, int color, int mode);

    @FastNative
    private static native void nDrawPaint(long nativeCanvas, long nativePaint);

    @FastNative
    private static native void nDrawPoint(long canvasHandle, float x, float y, long paintHandle);

    @FastNative
    private static native void nDrawPoints(long canvasHandle, float[] pts, int offset, int count,
            long paintHandle);

    @FastNative
    private static native void nDrawLine(long nativeCanvas, float startX, float startY, float stopX,
            float stopY, long nativePaint);

    @FastNative
    private static native void nDrawLines(long canvasHandle, float[] pts, int offset, int count,
            long paintHandle);

    @FastNative
    private static native void nDrawRect(long nativeCanvas, float left, float top, float right,
            float bottom, long nativePaint);

    @FastNative
    private static native void nDrawOval(long nativeCanvas, float left, float top, float right,
            float bottom, long nativePaint);

    @FastNative
    private static native void nDrawCircle(long nativeCanvas, float cx, float cy, float radius,
            long nativePaint);

    @FastNative
    private static native void nDrawArc(long nativeCanvas, float left, float top, float right,
            float bottom, float startAngle, float sweep, boolean useCenter, long nativePaint);

    @FastNative
    private static native void nDrawRoundRect(long nativeCanvas, float left, float top, float right,
            float bottom, float rx, float ry, long nativePaint);

    @FastNative
    private static native void nDrawPath(long nativeCanvas, long nativePath, long nativePaint);

    @FastNative
    private static native void nDrawRegion(long nativeCanvas, long nativeRegion, long nativePaint);

    @FastNative
    private static native void nDrawNinePatch(long nativeCanvas, long nativeBitmap, long ninePatch,
            float dstLeft, float dstTop, float dstRight, float dstBottom, long nativePaintOrZero,
            int screenDensity, int bitmapDensity);

    @FastNative
    private static native void nDrawBitmapMatrix(long nativeCanvas, Bitmap bitmap,
            long nativeMatrix, long nativePaint);

    @FastNative
    private static native void nDrawBitmapMesh(long nativeCanvas, Bitmap bitmap, int meshWidth,
            int meshHeight, float[] verts, int vertOffset, int[] colors, int colorOffset,
            long nativePaint);

    @FastNative
    private static native void nDrawVertices(long nativeCanvas, int mode, int n, float[] verts,
            int vertOffset, float[] texs, int texOffset, int[] colors, int colorOffset,
            short[] indices, int indexOffset, int indexCount, long nativePaint);

    @FastNative
    private static native void nDrawText(long nativeCanvas, char[] text, int index, int count,
            float x, float y, int flags, long nativePaint);

    @FastNative
    private static native void nDrawText(long nativeCanvas, String text, int start, int end,
            float x, float y, int flags, long nativePaint);

    @FastNative
    private static native void nDrawTextRun(long nativeCanvas, String text, int start, int end,
            int contextStart, int contextEnd, float x, float y, boolean isRtl, long nativePaint);

    @FastNative
    private static native void nDrawTextRun(long nativeCanvas, char[] text, int start, int count,
            int contextStart, int contextCount, float x, float y, boolean isRtl, long nativePaint,
            long nativePrecomputedText);

    @FastNative
    private static native void nDrawTextOnPath(long nativeCanvas, char[] text, int index, int count,
            long nativePath, float hOffset, float vOffset, int bidiFlags, long nativePaint);

    @FastNative
    private static native void nDrawTextOnPath(long nativeCanvas, String text, long nativePath,
            float hOffset, float vOffset, int flags, long nativePaint);
}
