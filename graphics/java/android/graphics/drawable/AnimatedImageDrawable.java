/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.graphics.drawable;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.AssetFileDescriptor;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;

import libcore.io.IoUtils;
import libcore.util.NativeAllocationRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.lang.Runnable;

/**
 * @hide
 */
public class AnimatedImageDrawable extends Drawable implements Animatable {
    private final long                mNativePtr;
    private final InputStream         mInputStream;
    private final AssetFileDescriptor mAssetFd;

    private final int                 mIntrinsicWidth;
    private final int                 mIntrinsicHeight;

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            invalidateSelf();
        }
    };

    /**
     * @hide
     * This should only be called by ImageDecoder.
     *
     * decoder is only non-null if it has a PostProcess
     */
    public AnimatedImageDrawable(long nativeImageDecoder,
            @Nullable ImageDecoder decoder, int width, int height, Rect cropRect,
            InputStream inputStream, AssetFileDescriptor afd)
            throws IOException {
        mNativePtr = nCreate(nativeImageDecoder, decoder, width, height, cropRect);
        mInputStream = inputStream;
        mAssetFd = afd;

        if (cropRect == null) {
            mIntrinsicWidth  = width;
            mIntrinsicHeight = height;
        } else {
            mIntrinsicWidth  = cropRect.width();
            mIntrinsicHeight = cropRect.height();
        }

        long nativeSize = nNativeByteSize(mNativePtr);
        NativeAllocationRegistry registry = new NativeAllocationRegistry(
                AnimatedImageDrawable.class.getClassLoader(), nGetNativeFinalizer(), nativeSize);
        registry.registerNativeAllocation(this, mNativePtr);
    }

    @Override
    protected void finalize() throws Throwable {
        // FIXME: It's a shame that we have *both* a native finalizer and a Java
        // one. The native one is necessary to report how much memory is being
        // used natively, and this one is necessary to close the input. An
        // alternative might be to read the entire stream ahead of time, so we
        // can eliminate the Java finalizer.
        try {
            IoUtils.closeQuietly(mInputStream);
            IoUtils.closeQuietly(mAssetFd);
        } finally {
            super.finalize();
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return mIntrinsicWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return mIntrinsicHeight;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        long nextUpdate = nDraw(mNativePtr, canvas.getNativeCanvasWrapper(),
                SystemClock.uptimeMillis());
        scheduleSelf(mRunnable, nextUpdate);
    }

    @Override
    public void setAlpha(@IntRange(from=0,to=255) int alpha) {
        if (alpha < 0 || alpha > 255) {
            throw new IllegalArgumentException("Alpha must be between 0 and"
                   + " 255! provided " + alpha);
        }
        nSetAlpha(mNativePtr, alpha);
    }

    @Override
    public int getAlpha() {
        return nGetAlpha(mNativePtr);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        long nativeFilter = colorFilter == null ? 0 : colorFilter.getNativeInstance();
        nSetColorFilter(mNativePtr, nativeFilter);
    }

    @Override
    public @PixelFormat.Opacity int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    // TODO: Add a Constant State?
    // @Override
    // public @Nullable ConstantState getConstantState() {}


    // Animatable overrides
    @Override
    public boolean isRunning() {
        return nIsRunning(mNativePtr);
    }

    @Override
    public void start() {
        nStart(mNativePtr);
    }

    @Override
    public void stop() {
        nStop(mNativePtr);
    }

    private static native long nCreate(long nativeImageDecoder,
            @Nullable ImageDecoder decoder, int width, int height, Rect cropRect)
        throws IOException;
    private static native long nGetNativeFinalizer();
    private static native long nDraw(long nativePtr, long canvasNativePtr, long msecs);
    private static native void nSetAlpha(long nativePtr, int alpha);
    private static native int nGetAlpha(long nativePtr);
    private static native void nSetColorFilter(long nativePtr, long nativeFilter);
    private static native boolean nIsRunning(long nativePtr);
    private static native void nStart(long nativePtr);
    private static native void nStop(long nativePtr);
    private static native long nNativeByteSize(long nativePtr);
}
