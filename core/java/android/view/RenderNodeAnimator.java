/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.animation.TimeInterpolator;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Paint;
import android.util.SparseIntArray;

import com.android.internal.util.VirtualRefBasePtr;
import com.android.internal.view.animation.FallbackLUTInterpolator;
import com.android.internal.view.animation.HasNativeInterpolator;
import com.android.internal.view.animation.NativeInterpolatorFactory;

import java.lang.ref.WeakReference;

/**
 * @hide
 */
public final class RenderNodeAnimator {

    // Keep in sync with enum RenderProperty in Animator.h
    public static final int TRANSLATION_X = 0;
    public static final int TRANSLATION_Y = 1;
    public static final int TRANSLATION_Z = 2;
    public static final int SCALE_X = 3;
    public static final int SCALE_Y = 4;
    public static final int ROTATION = 5;
    public static final int ROTATION_X = 6;
    public static final int ROTATION_Y = 7;
    public static final int X = 8;
    public static final int Y = 9;
    public static final int Z = 10;
    public static final int ALPHA = 11;

    // Keep in sync with enum PaintFields in Animator.h
    public static final int PAINT_STROKE_WIDTH = 0;
    public static final int PAINT_ALPHA = 1;

    // ViewPropertyAnimator uses a mask for its values, we need to remap them
    // to the enum values here. RenderPropertyAnimator can't use the mask values
    // directly as internally it uses a lookup table so it needs the values to
    // be sequential starting from 0
    private static final SparseIntArray sViewPropertyAnimatorMap = new SparseIntArray(15) {{
        put(ViewPropertyAnimator.TRANSLATION_X, TRANSLATION_X);
        put(ViewPropertyAnimator.TRANSLATION_Y, TRANSLATION_Y);
        put(ViewPropertyAnimator.TRANSLATION_Z, TRANSLATION_Z);
        put(ViewPropertyAnimator.SCALE_X, SCALE_X);
        put(ViewPropertyAnimator.SCALE_Y, SCALE_Y);
        put(ViewPropertyAnimator.ROTATION, ROTATION);
        put(ViewPropertyAnimator.ROTATION_X, ROTATION_X);
        put(ViewPropertyAnimator.ROTATION_Y, ROTATION_Y);
        put(ViewPropertyAnimator.X, X);
        put(ViewPropertyAnimator.Y, Y);
        put(ViewPropertyAnimator.Z, Z);
        put(ViewPropertyAnimator.ALPHA, ALPHA);
    }};

    private VirtualRefBasePtr mNativePtr;

    private RenderNode mTarget;
    private TimeInterpolator mInterpolator;
    private boolean mStarted = false;

    public int mapViewPropertyToRenderProperty(int viewProperty) {
        return sViewPropertyAnimatorMap.get(viewProperty);
    }

    public RenderNodeAnimator(int property, float finalValue) {
        init(nCreateAnimator(new WeakReference<RenderNodeAnimator>(this),
                property, finalValue));
    }

    public RenderNodeAnimator(CanvasProperty<Float> property, float finalValue) {
        init(nCreateCanvasPropertyFloatAnimator(
                new WeakReference<RenderNodeAnimator>(this),
                property.getNativeContainer(), finalValue));
    }

    public RenderNodeAnimator(CanvasProperty<Paint> property, int paintField, float finalValue) {
        init(nCreateCanvasPropertyPaintAnimator(
                new WeakReference<RenderNodeAnimator>(this),
                property.getNativeContainer(), paintField, finalValue));
    }

    private void init(long ptr) {
        mNativePtr = new VirtualRefBasePtr(ptr);
    }

    private void checkMutable() {
        if (mStarted) {
            throw new IllegalStateException("Animator has already started, cannot change it now!");
        }
    }

    private void applyInterpolator() {
        if (mInterpolator == null) return;

        long ni;
        if (mInterpolator.getClass().isAnnotationPresent(HasNativeInterpolator.class)) {
            ni = ((NativeInterpolatorFactory)mInterpolator).createNativeInterpolator();
        } else {
            int duration = nGetDuration(mNativePtr.get());
            ni = FallbackLUTInterpolator.createNativeInterpolator(mInterpolator, duration);
        }
        nSetInterpolator(mNativePtr.get(), ni);
    }

    private void start(RenderNode node) {
        if (mStarted) {
            throw new IllegalStateException("Already started!");
        }
        mStarted = true;
        applyInterpolator();
        mTarget = node;
        mTarget.addAnimator(this);
    }

    public void start(View target) {
        start(target.mRenderNode);
        // Kick off a frame to start the process
        target.invalidateViewProperty(true, false);
    }

    public void start(Canvas canvas) {
        if (!(canvas instanceof GLES20RecordingCanvas)) {
            throw new IllegalArgumentException("Not a GLES20RecordingCanvas");
        }
        GLES20RecordingCanvas recordingCanvas = (GLES20RecordingCanvas) canvas;
        start(recordingCanvas.mNode);
    }

    public void cancel() {
        mTarget.removeAnimator(this);
    }

    public void setDuration(int duration) {
        checkMutable();
        nSetDuration(mNativePtr.get(), duration);
    }

    public void setInterpolator(TimeInterpolator interpolator) {
        checkMutable();
        mInterpolator = interpolator;
    }

    long getNativeAnimator() {
        return mNativePtr.get();
    }

    private void onFinished() {
        mTarget.removeAnimator(this);
    }

    // Called by native
    private static void callOnFinished(WeakReference<RenderNodeAnimator> weakThis) {
        RenderNodeAnimator animator = weakThis.get();
        if (animator != null) {
            animator.onFinished();
        }
    }

    private static native long nCreateAnimator(WeakReference<RenderNodeAnimator> weakThis,
            int property, float deltaValue);
    private static native long nCreateCanvasPropertyFloatAnimator(WeakReference<RenderNodeAnimator> weakThis,
            long canvasProperty, float deltaValue);
    private static native long nCreateCanvasPropertyPaintAnimator(WeakReference<RenderNodeAnimator> weakThis,
            long canvasProperty, int paintField, float deltaValue);
    private static native void nSetDuration(long nativePtr, int duration);
    private static native int nGetDuration(long nativePtr);
    private static native void nSetInterpolator(long animPtr, long interpolatorPtr);
}
