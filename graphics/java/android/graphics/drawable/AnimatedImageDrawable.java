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

import dalvik.annotation.optimization.FastNative;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.InflateException;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import libcore.io.IoUtils;
import libcore.util.NativeAllocationRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.lang.Runnable;
import java.util.ArrayList;

/**
 * {@link Drawable} for drawing animated images (like GIF).
 *
 * <p>Created by {@link ImageDecoder#decodeDrawable}. A user needs to call
 * {@link #start} to start the animation.</p>
 */
public class AnimatedImageDrawable extends Drawable implements Animatable2 {
    private int mIntrinsicWidth;
    private int mIntrinsicHeight;

    private boolean mStarting;

    private Handler mHandler;

    private class State {
        State(long nativePtr, InputStream is, AssetFileDescriptor afd) {
            mNativePtr = nativePtr;
            mInputStream = is;
            mAssetFd = afd;
        }

        public  final long mNativePtr;

        // These just keep references so the native code can continue using them.
        private final InputStream mInputStream;
        private final AssetFileDescriptor mAssetFd;
    }

    private State mState;

    private Runnable mRunnable;

    private ColorFilter mColorFilter;

    /**
     *  Pass this to {@link #setLoopCount} to loop infinitely.
     *
     *  <p>{@link Animatable2.AnimationCallback#onAnimationEnd} will never be
     *  called unless there is an error.</p>
     */
    public static final int LOOP_INFINITE = -1;

    /**
     *  Specify the number of times to loop the animation.
     *
     *  <p>By default, the loop count in the encoded data is respected.</p>
     */
    public void setLoopCount(int loopCount) {
        if (mState == null) {
            throw new IllegalStateException("called setLoopCount on empty AnimatedImageDrawable");
        }
        nSetLoopCount(mState.mNativePtr, loopCount);
    }

    /**
     * Create an empty AnimatedImageDrawable.
     */
    public AnimatedImageDrawable() {
        mState = null;
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.AnimatedImageDrawable);
        updateStateFromTypedArray(a, mSrcDensityOverride);
    }

    private void updateStateFromTypedArray(TypedArray a, int srcDensityOverride)
            throws XmlPullParserException {
        final Resources r = a.getResources();
        final int srcResId = a.getResourceId(R.styleable.AnimatedImageDrawable_src, 0);
        if (srcResId != 0) {
            // Follow the density handling in BitmapDrawable.
            final TypedValue value = new TypedValue();
            r.getValueForDensity(srcResId, srcDensityOverride, value, true);
            if (srcDensityOverride > 0 && value.density > 0
                    && value.density != TypedValue.DENSITY_NONE) {
                if (value.density == srcDensityOverride) {
                    value.density = r.getDisplayMetrics().densityDpi;
                } else {
                    value.density =
                            (value.density * r.getDisplayMetrics().densityDpi) / srcDensityOverride;
                }
            }

            int density = Bitmap.DENSITY_NONE;
            if (value.density == TypedValue.DENSITY_DEFAULT) {
                density = DisplayMetrics.DENSITY_DEFAULT;
            } else if (value.density != TypedValue.DENSITY_NONE) {
                density = value.density;
            }

            Drawable drawable = null;
            try {
                InputStream is = r.openRawResource(srcResId, value);
                ImageDecoder.Source source = ImageDecoder.createSource(r, is, density);
                drawable = ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                    if (!info.isAnimated()) {
                        throw new IllegalArgumentException("image is not animated");
                    }
                });
            } catch (IOException e) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        ": <animated-image> requires a valid 'src' attribute", null, e);
            }

            if (!(drawable instanceof AnimatedImageDrawable)) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        ": <animated-image> did not decode animated");
            }

            // Transfer the state of other to this one. other will be discarded.
            AnimatedImageDrawable other = (AnimatedImageDrawable) drawable;
            mState = other.mState;
            other.mState = null;
            mIntrinsicWidth =  other.mIntrinsicWidth;
            mIntrinsicHeight = other.mIntrinsicHeight;
        }
    }

    /**
     * @hide
     * This should only be called by ImageDecoder.
     *
     * decoder is only non-null if it has a PostProcess
     */
    public AnimatedImageDrawable(long nativeImageDecoder,
            @Nullable ImageDecoder decoder, int width, int height,
            int srcDensity, int dstDensity, Rect cropRect,
            InputStream inputStream, AssetFileDescriptor afd)
            throws IOException {
        width = Bitmap.scaleFromDensity(width, srcDensity, dstDensity);
        height = Bitmap.scaleFromDensity(height, srcDensity, dstDensity);

        if (cropRect == null) {
            mIntrinsicWidth  = width;
            mIntrinsicHeight = height;
        } else {
            cropRect.set(Bitmap.scaleFromDensity(cropRect.left, srcDensity, dstDensity),
                    Bitmap.scaleFromDensity(cropRect.top, srcDensity, dstDensity),
                    Bitmap.scaleFromDensity(cropRect.right, srcDensity, dstDensity),
                    Bitmap.scaleFromDensity(cropRect.bottom, srcDensity, dstDensity));
            mIntrinsicWidth  = cropRect.width();
            mIntrinsicHeight = cropRect.height();
        }

        mState = new State(nCreate(nativeImageDecoder, decoder, width, height, cropRect),
                inputStream, afd);

        // FIXME: Use the right size for the native allocation.
        long nativeSize = 200;
        NativeAllocationRegistry registry = new NativeAllocationRegistry(
                AnimatedImageDrawable.class.getClassLoader(), nGetNativeFinalizer(), nativeSize);
        registry.registerNativeAllocation(mState, mState.mNativePtr);
    }

    @Override
    public int getIntrinsicWidth() {
        return mIntrinsicWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return mIntrinsicHeight;
    }

    // nDraw returns -1 if the animation has finished.
    private static final int FINISHED = -1;

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mState == null) {
            throw new IllegalStateException("called draw on empty AnimatedImageDrawable");
        }

        if (mStarting) {
            mStarting = false;

            postOnAnimationStart();
        }

        long nextUpdate = nDraw(mState.mNativePtr, canvas.getNativeCanvasWrapper());
        // a value <= 0 indicates that the drawable is stopped or that renderThread
        // will manage the animation
        if (nextUpdate > 0) {
            if (mRunnable == null) {
                mRunnable = this::invalidateSelf;
            }
            scheduleSelf(mRunnable, nextUpdate);
        } else if (nextUpdate == FINISHED) {
            // This means the animation was drawn in software mode and ended.
            postOnAnimationEnd();
        }
    }

    @Override
    public void setAlpha(@IntRange(from=0,to=255) int alpha) {
        if (alpha < 0 || alpha > 255) {
            throw new IllegalArgumentException("Alpha must be between 0 and"
                   + " 255! provided " + alpha);
        }

        if (mState == null) {
            throw new IllegalStateException("called setAlpha on empty AnimatedImageDrawable");
        }

        nSetAlpha(mState.mNativePtr, alpha);
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        if (mState == null) {
            throw new IllegalStateException("called getAlpha on empty AnimatedImageDrawable");
        }
        return nGetAlpha(mState.mNativePtr);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        if (mState == null) {
            throw new IllegalStateException("called setColorFilter on empty AnimatedImageDrawable");
        }

        if (colorFilter != mColorFilter) {
            mColorFilter = colorFilter;
            long nativeFilter = colorFilter == null ? 0 : colorFilter.getNativeInstance();
            nSetColorFilter(mState.mNativePtr, nativeFilter);
            invalidateSelf();
        }
    }

    @Override
    @Nullable
    public ColorFilter getColorFilter() {
        return mColorFilter;
    }

    @Override
    public @PixelFormat.Opacity int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        if (!super.setVisible(visible, restart)) {
            return false;
        }

        if (!visible) {
            nMarkInvisible(mState.mNativePtr);
        }

        return true;
    }

    // Animatable overrides
    /**
     *  Return whether the animation is currently running.
     *
     *  <p>When this drawable is created, this will return {@code false}. A client
     *  needs to call {@link #start} to start the animation.</p>
     */
    @Override
    public boolean isRunning() {
        if (mState == null) {
            throw new IllegalStateException("called isRunning on empty AnimatedImageDrawable");
        }
        return nIsRunning(mState.mNativePtr);
    }

    /**
     *  Start the animation.
     *
     *  <p>Does nothing if the animation is already running. If the animation is stopped,
     *  this will reset it.</p>
     *
     *  <p>If the animation starts, this will call
     *  {@link Animatable2.AnimationCallback#onAnimationStart}.</p>
     */
    @Override
    public void start() {
        if (mState == null) {
            throw new IllegalStateException("called start on empty AnimatedImageDrawable");
        }

        if (nStart(mState.mNativePtr)) {
            mStarting = true;
            invalidateSelf();
        }
    }

    /**
     *  Stop the animation.
     *
     *  <p>If the animation is stopped, it will continue to display the frame
     *  it was displaying when stopped.</p>
     */
    @Override
    public void stop() {
        if (mState == null) {
            throw new IllegalStateException("called stop on empty AnimatedImageDrawable");
        }
        if (nStop(mState.mNativePtr)) {
            postOnAnimationEnd();
        }
    }

    // Animatable2 overrides
    private ArrayList<Animatable2.AnimationCallback> mAnimationCallbacks = null;

    @Override
    public void registerAnimationCallback(@NonNull AnimationCallback callback) {
        if (callback == null) {
            return;
        }

        if (mAnimationCallbacks == null) {
            mAnimationCallbacks = new ArrayList<Animatable2.AnimationCallback>();
            nSetOnAnimationEndListener(mState.mNativePtr, this);
        }

        if (!mAnimationCallbacks.contains(callback)) {
            mAnimationCallbacks.add(callback);
        }
    }

    @Override
    public boolean unregisterAnimationCallback(@NonNull AnimationCallback callback) {
        if (callback == null || mAnimationCallbacks == null
                || !mAnimationCallbacks.remove(callback)) {
            return false;
        }

        if (mAnimationCallbacks.isEmpty()) {
            clearAnimationCallbacks();
        }

        return true;
    }

    @Override
    public void clearAnimationCallbacks() {
        if (mAnimationCallbacks != null) {
            mAnimationCallbacks = null;
            nSetOnAnimationEndListener(mState.mNativePtr, null);
        }
    }

    private void postOnAnimationStart() {
        if (mAnimationCallbacks == null) {
            return;
        }

        getHandler().post(() -> {
            for (Animatable2.AnimationCallback callback : mAnimationCallbacks) {
                callback.onAnimationStart(this);
            }
        });
    }

    private void postOnAnimationEnd() {
        if (mAnimationCallbacks == null) {
            return;
        }

        getHandler().post(() -> {
            for (Animatable2.AnimationCallback callback : mAnimationCallbacks) {
                callback.onAnimationEnd(this);
            }
        });
    }

    private Handler getHandler() {
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }
        return mHandler;
    }

    /**
     *  Called by JNI.
     *
     *  The JNI code has already posted this to the thread that created the
     *  callback, so no need to post.
     */
    @SuppressWarnings("unused")
    private void onAnimationEnd() {
        if (mAnimationCallbacks != null) {
            for (Animatable2.AnimationCallback callback : mAnimationCallbacks) {
                callback.onAnimationEnd(this);
            }
        }
    }


    private static native long nCreate(long nativeImageDecoder,
            @Nullable ImageDecoder decoder, int width, int height, Rect cropRect)
        throws IOException;
    @FastNative
    private static native long nGetNativeFinalizer();
    private static native long nDraw(long nativePtr, long canvasNativePtr);
    @FastNative
    private static native void nSetAlpha(long nativePtr, int alpha);
    @FastNative
    private static native int nGetAlpha(long nativePtr);
    @FastNative
    private static native void nSetColorFilter(long nativePtr, long nativeFilter);
    @FastNative
    private static native boolean nIsRunning(long nativePtr);
    // Return whether the animation started.
    @FastNative
    private static native boolean nStart(long nativePtr);
    @FastNative
    private static native boolean nStop(long nativePtr);
    @FastNative
    private static native void nSetLoopCount(long nativePtr, int loopCount);
    // Pass the drawable down to native so it can call onAnimationEnd.
    private static native void nSetOnAnimationEndListener(long nativePtr,
            @Nullable AnimatedImageDrawable drawable);
    @FastNative
    private static native long nNativeByteSize(long nativePtr);
    @FastNative
    private static native void nMarkInvisible(long nativePtr);
}
