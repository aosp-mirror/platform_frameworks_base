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

import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Paint;
import android.util.SparseIntArray;

import com.android.internal.util.VirtualRefBasePtr;

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

    // Keep in sync DeltaValueType in Animator.h
    public static final int DELTA_TYPE_ABSOLUTE = 0;
    public static final int DELTA_TYPE_DELTA = 1;

    private RenderNode mTarget;
    private VirtualRefBasePtr mNativePtr;

    public int mapViewPropertyToRenderProperty(int viewProperty) {
        return sViewPropertyAnimatorMap.get(viewProperty);
    }

    public RenderNodeAnimator(int property, int deltaType, float deltaValue) {
        init(nCreateAnimator(new WeakReference<RenderNodeAnimator>(this),
                property, deltaType, deltaValue));
    }

    public RenderNodeAnimator(CanvasProperty<Float> property, int deltaType, float deltaValue) {
        init(nCreateCanvasPropertyFloatAnimator(
                new WeakReference<RenderNodeAnimator>(this),
                property.getNativeContainer(), deltaType, deltaValue));
    }

    public RenderNodeAnimator(CanvasProperty<Paint> property, int paintField,
            int deltaType, float deltaValue) {
        init(nCreateCanvasPropertyPaintAnimator(
                new WeakReference<RenderNodeAnimator>(this),
                property.getNativeContainer(), paintField, deltaType, deltaValue));
    }

    private void init(long ptr) {
        mNativePtr = new VirtualRefBasePtr(ptr);
    }

    public void start(View target) {
        mTarget = target.mRenderNode;
        mTarget.addAnimator(this);
        // Kick off a frame to start the process
        target.invalidateViewProperty(true, false);
    }

    public void start(Canvas canvas) {
        if (!(canvas instanceof GLES20RecordingCanvas)) {
            throw new IllegalArgumentException("Not a GLES20RecordingCanvas");
        }
        GLES20RecordingCanvas recordingCanvas = (GLES20RecordingCanvas) canvas;
        mTarget = recordingCanvas.mNode;
        mTarget.addAnimator(this);
    }

    public void cancel() {
        mTarget.removeAnimator(this);
    }

    public void setDuration(int duration) {
        nSetDuration(mNativePtr.get(), duration);
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
            int property, int deltaValueType, float deltaValue);
    private static native long nCreateCanvasPropertyFloatAnimator(WeakReference<RenderNodeAnimator> weakThis,
            long canvasProperty, int deltaValueType, float deltaValue);
    private static native long nCreateCanvasPropertyPaintAnimator(WeakReference<RenderNodeAnimator> weakThis,
            long canvasProperty, int paintField, int deltaValueType, float deltaValue);
    private static native void nSetDuration(long nativePtr, int duration);
}
