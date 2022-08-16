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
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;

import com.android.internal.R;

import dalvik.annotation.optimization.FastNative;

import libcore.util.NativeAllocationRegistry;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * {@link Drawable} for drawing animated images (like GIF).
 *
 * <p>The framework handles decoding subsequent frames in another thread and
 * updating when necessary. The drawable will only animate while it is being
 * displayed.</p>
 *
 * <p>Created by {@link ImageDecoder#decodeDrawable}. A user needs to call
 * {@link #start} to start the animation.</p>
 *
 * <p>It can also be defined in XML using the <code>&lt;animated-image></code>
 * element.</p>
 *
 * @attr ref android.R.styleable#AnimatedImageDrawable_src
 * @attr ref android.R.styleable#AnimatedImageDrawable_autoStart
 * @attr ref android.R.styleable#AnimatedImageDrawable_repeatCount
 * @attr ref android.R.styleable#AnimatedImageDrawable_autoMirrored
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

        final long mNativePtr;

        // These just keep references so the native code can continue using them.
        private final InputStream mInputStream;
        private final AssetFileDescriptor mAssetFd;

        int[] mThemeAttrs = null;
        boolean mAutoMirrored = false;
        int mRepeatCount = REPEAT_UNDEFINED;
    }

    private State mState;

    private Runnable mRunnable;

    private ColorFilter mColorFilter;

    /**
     *  Pass this to {@link #setRepeatCount} to repeat infinitely.
     *
     *  <p>{@link Animatable2.AnimationCallback#onAnimationEnd} will never be
     *  called unless there is an error.</p>
     */
    public static final int REPEAT_INFINITE = -1;

    /** @removed
     * @deprecated Replaced with REPEAT_INFINITE to match other APIs.
     */
    @java.lang.Deprecated
    public static final int LOOP_INFINITE = REPEAT_INFINITE;

    private static final int REPEAT_UNDEFINED = -2;

    /**
     *  Specify the number of times to repeat the animation.
     *
     *  <p>By default, the repeat count in the encoded data is respected. If set
     *  to {@link #REPEAT_INFINITE}, the animation will repeat as long as it is
     *  displayed. If the value is {@code 0}, the animation will play once.</p>
     *
     *  <p>This call replaces the current repeat count. If the encoded data
     *  specified a repeat count of {@code 2} (meaning that
     *  {@link #getRepeatCount()} returns {@code 2}, the animation will play
     *  three times. Calling {@code setRepeatCount(1)} will result in playing only
     *  twice and {@link #getRepeatCount()} returning {@code 1}.</p>
     *
     *  <p>If the animation is already playing, the iterations that have already
     *  occurred count towards the new count. If the animation has already
     *  repeated the appropriate number of times (or more), it will finish its
     *  current iteration and then stop.</p>
     */
    public void setRepeatCount(@IntRange(from = REPEAT_INFINITE) int repeatCount) {
        if (repeatCount < REPEAT_INFINITE) {
            throw new IllegalArgumentException("invalid value passed to setRepeatCount"
                    + repeatCount);
        }
        if (mState.mRepeatCount != repeatCount) {
            mState.mRepeatCount = repeatCount;
            if (mState.mNativePtr != 0) {
                nSetRepeatCount(mState.mNativePtr, repeatCount);
            }
        }
    }

    /** @removed
     * @deprecated Replaced with setRepeatCount to match other APIs.
     */
    @java.lang.Deprecated
    public void setLoopCount(int loopCount) {
        setRepeatCount(loopCount);
    }

    /**
     *  Retrieve the number of times the animation will repeat.
     *
     *  <p>By default, the repeat count in the encoded data is respected. If the
     *  value is {@link #REPEAT_INFINITE}, the animation will repeat as long as
     *  it is displayed. If the value is {@code 0}, it will play once.</p>
     *
     *  <p>Calling {@link #setRepeatCount} will make future calls to this method
     *  return the value passed to {@link #setRepeatCount}.</p>
     */
    public int getRepeatCount() {
        if (mState.mNativePtr == 0) {
            throw new IllegalStateException("called getRepeatCount on empty AnimatedImageDrawable");
        }
        if (mState.mRepeatCount == REPEAT_UNDEFINED) {
            mState.mRepeatCount = nGetRepeatCount(mState.mNativePtr);

        }
        return mState.mRepeatCount;
    }

    /** @removed
     * @deprecated Replaced with getRepeatCount to match other APIs.
     */
    @java.lang.Deprecated
    public int getLoopCount(int loopCount) {
        return getRepeatCount();
    }

    /**
     * Create an empty AnimatedImageDrawable.
     */
    public AnimatedImageDrawable() {
        mState = new State(0, null, null);
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
        State oldState = mState;
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

            // This may have previously been set without a src if we were waiting for a
            // theme.
            final int repeatCount = mState.mRepeatCount;
            // Transfer the state of other to this one. other will be discarded.
            AnimatedImageDrawable other = (AnimatedImageDrawable) drawable;
            mState = other.mState;
            other.mState = null;
            mIntrinsicWidth =  other.mIntrinsicWidth;
            mIntrinsicHeight = other.mIntrinsicHeight;
            if (repeatCount != REPEAT_UNDEFINED) {
                this.setRepeatCount(repeatCount);
            }
        }

        mState.mThemeAttrs = a.extractThemeAttrs();
        if (mState.mNativePtr == 0 && (mState.mThemeAttrs == null
                || mState.mThemeAttrs[R.styleable.AnimatedImageDrawable_src] == 0)) {
            throw new XmlPullParserException(a.getPositionDescription() +
                    ": <animated-image> requires a valid 'src' attribute");
        }

        mState.mAutoMirrored = a.getBoolean(
                R.styleable.AnimatedImageDrawable_autoMirrored, oldState.mAutoMirrored);

        int repeatCount = a.getInt(
                R.styleable.AnimatedImageDrawable_repeatCount, REPEAT_UNDEFINED);
        if (repeatCount != REPEAT_UNDEFINED) {
            this.setRepeatCount(repeatCount);
        }

        boolean autoStart = a.getBoolean(
                R.styleable.AnimatedImageDrawable_autoStart, false);
        if (autoStart && mState.mNativePtr != 0) {
            this.start();
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
            long colorSpaceHandle, boolean extended, int srcDensity, int dstDensity,
            Rect cropRect, InputStream inputStream, AssetFileDescriptor afd)
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

        mState = new State(nCreate(nativeImageDecoder, decoder, width, height, colorSpaceHandle,
                    extended, cropRect), inputStream, afd);

        final long nativeSize = nNativeByteSize(mState.mNativePtr);
        NativeAllocationRegistry registry = NativeAllocationRegistry.createMalloced(
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
        if (mState.mNativePtr == 0) {
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
            scheduleSelf(mRunnable, nextUpdate + SystemClock.uptimeMillis());
        } else if (nextUpdate == FINISHED) {
            // This means the animation was drawn in software mode and ended.
            postOnAnimationEnd();
        }
    }

    @Override
    public void setAlpha(@IntRange(from = 0, to = 255) int alpha) {
        if (alpha < 0 || alpha > 255) {
            throw new IllegalArgumentException("Alpha must be between 0 and"
                   + " 255! provided " + alpha);
        }

        if (mState.mNativePtr == 0) {
            throw new IllegalStateException("called setAlpha on empty AnimatedImageDrawable");
        }

        nSetAlpha(mState.mNativePtr, alpha);
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        if (mState.mNativePtr == 0) {
            throw new IllegalStateException("called getAlpha on empty AnimatedImageDrawable");
        }
        return nGetAlpha(mState.mNativePtr);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        if (mState.mNativePtr == 0) {
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
    public void setAutoMirrored(boolean mirrored) {
        if (mState.mAutoMirrored != mirrored) {
            mState.mAutoMirrored = mirrored;
            if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL && mState.mNativePtr != 0) {
                nSetMirrored(mState.mNativePtr, mirrored);
                invalidateSelf();
            }
        }
    }

    @Override
    public boolean onLayoutDirectionChanged(int layoutDirection) {
        if (!mState.mAutoMirrored || mState.mNativePtr == 0) {
            return false;
        }

        final boolean mirror = layoutDirection == View.LAYOUT_DIRECTION_RTL;
        nSetMirrored(mState.mNativePtr, mirror);
        return true;
    }

    @Override
    public final boolean isAutoMirrored() {
        return mState.mAutoMirrored;
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
        if (mState.mNativePtr == 0) {
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
     *  <p>When the drawable is drawn, starting the animation,
     *  {@link Animatable2.AnimationCallback#onAnimationStart} will be called.</p>
     */
    @Override
    public void start() {
        if (mState.mNativePtr == 0) {
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
        if (mState.mNativePtr == 0) {
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
            nSetOnAnimationEndListener(mState.mNativePtr, new WeakReference<>(this));
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
    private static void callOnAnimationEnd(WeakReference<AnimatedImageDrawable> weakDrawable) {
        AnimatedImageDrawable drawable = weakDrawable.get();
        if (drawable != null) {
            drawable.onAnimationEnd();
        }
    }

    private void onAnimationEnd() {
        if (mAnimationCallbacks != null) {
            for (Animatable2.AnimationCallback callback : mAnimationCallbacks) {
                callback.onAnimationEnd(this);
            }
        }
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        if (mState.mNativePtr != 0) {
            nSetBounds(mState.mNativePtr, bounds);
        }
    }


    private static native long nCreate(long nativeImageDecoder,
            @Nullable ImageDecoder decoder, int width, int height, long colorSpaceHandle,
            boolean extended, Rect cropRect) throws IOException;
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
    private static native int nGetRepeatCount(long nativePtr);
    @FastNative
    private static native void nSetRepeatCount(long nativePtr, int repeatCount);
    // Pass the drawable down to native so it can call onAnimationEnd.
    private static native void nSetOnAnimationEndListener(long nativePtr,
            @Nullable WeakReference<AnimatedImageDrawable> drawable);
    @FastNative
    private static native long nNativeByteSize(long nativePtr);
    @FastNative
    private static native void nSetMirrored(long nativePtr, boolean mirror);
    @FastNative
    private static native void nSetBounds(long nativePtr, Rect rect);
}
