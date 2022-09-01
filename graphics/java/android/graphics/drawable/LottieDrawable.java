/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;

import dalvik.annotation.optimization.FastNative;

import libcore.util.NativeAllocationRegistry;

import java.io.IOException;

/**
 * {@link Drawable} for drawing Lottie files.
 *
 * <p>The framework handles decoding subsequent frames in another thread and
 * updating when necessary. The drawable will only animate while it is being
 * displayed.</p>
 *
 * @hide
 */
@SuppressLint("NotCloseable")
public class LottieDrawable extends Drawable implements Animatable {
    private long mNativePtr;

    /**
     * Create an animation from the provided JSON string
     * @hide
     */
    private LottieDrawable(@NonNull String animationJson) throws IOException {
        mNativePtr = nCreate(animationJson);
        if (mNativePtr == 0) {
            throw new IOException("could not make LottieDrawable from json");
        }

        final long nativeSize = nNativeByteSize(mNativePtr);
        NativeAllocationRegistry registry = new NativeAllocationRegistry(
                LottieDrawable.class.getClassLoader(), nGetNativeFinalizer(), nativeSize);
        registry.registerNativeAllocation(this, mNativePtr);
    }

    /**
     * Factory for LottieDrawable, throws IOException if native Skottie Builder fails to parse
     */
    public static LottieDrawable makeLottieDrawable(@NonNull String animationJson)
            throws IOException {
        return new LottieDrawable(animationJson);
    }



    /**
     * Draw the current frame to the Canvas.
     * @hide
     */
    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mNativePtr == 0) {
            throw new IllegalStateException("called draw on empty LottieDrawable");
        }

        nDraw(mNativePtr, canvas.getNativeCanvasWrapper());
    }

    /**
     * Start the animation. Needs to be called before draw calls.
     * @hide
     */
    @Override
    public void start() {
        if (mNativePtr == 0) {
            throw new IllegalStateException("called start on empty LottieDrawable");
        }

        if (nStart(mNativePtr)) {
            invalidateSelf();
        }
    }

    /**
     * Stops the animation playback. Does not release anything.
     * @hide
     */
    @Override
    public void stop() {
        if (mNativePtr == 0) {
            throw new IllegalStateException("called stop on empty LottieDrawable");
        }
        nStop(mNativePtr);
    }

    /**
     *  Return whether the animation is currently running.
     */
    @Override
    public boolean isRunning() {
        if (mNativePtr == 0) {
            throw new IllegalStateException("called isRunning on empty LottieDrawable");
        }
        return nIsRunning(mNativePtr);
    }

    @Override
    public int getOpacity() {
        // We assume translucency to avoid checking each pixel.
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void setAlpha(int alpha) {
        //TODO
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        //TODO
    }

    private static native long nCreate(String json);
    private static native void nDraw(long nativeInstance, long nativeCanvas);
    @FastNative
    private static native long nGetNativeFinalizer();
    @FastNative
    private static native long nNativeByteSize(long nativeInstance);
    @FastNative
    private static native boolean nIsRunning(long nativeInstance);
    @FastNative
    private static native boolean nStart(long nativeInstance);
    @FastNative
    private static native boolean nStop(long nativeInstance);

}
