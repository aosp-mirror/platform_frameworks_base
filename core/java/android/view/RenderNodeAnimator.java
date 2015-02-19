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

import android.animation.Animator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Paint;
import android.util.SparseIntArray;

import com.android.internal.util.VirtualRefBasePtr;
import com.android.internal.view.animation.FallbackLUTInterpolator;
import com.android.internal.view.animation.HasNativeInterpolator;
import com.android.internal.view.animation.NativeInterpolatorFactory;

import java.util.ArrayList;

/**
 * @hide
 */
public class RenderNodeAnimator extends Animator {
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
    // The last value in the enum, used for array size initialization
    public static final int LAST_VALUE = ALPHA;

    // Keep in sync with enum PaintFields in Animator.h
    public static final int PAINT_STROKE_WIDTH = 0;

    /**
     * Field for the Paint alpha channel, which should be specified as a value
     * between 0 and 255.
     */
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
    private View mViewTarget;
    private int mRenderProperty = -1;
    private float mFinalValue;
    private TimeInterpolator mInterpolator;

    private static final int STATE_PREPARE = 0;
    private static final int STATE_DELAYED = 1;
    private static final int STATE_RUNNING = 2;
    private static final int STATE_FINISHED = 3;
    private int mState = STATE_PREPARE;

    private long mUnscaledDuration = 300;
    private long mUnscaledStartDelay = 0;
    // If this is true, we will run any start delays on the UI thread. This is
    // the safe default, and is necessary to ensure start listeners fire at
    // the correct time. Animators created by RippleDrawable (the
    // CanvasProperty<> ones) do not have this expectation, and as such will
    // set this to false so that the renderthread handles the startdelay instead
    private final boolean mUiThreadHandlesDelay;
    private long mStartDelay = 0;
    private long mStartTime;

    public static int mapViewPropertyToRenderProperty(int viewProperty) {
        return sViewPropertyAnimatorMap.get(viewProperty);
    }

    public RenderNodeAnimator(int property, float finalValue) {
        mRenderProperty = property;
        mFinalValue = finalValue;
        mUiThreadHandlesDelay = true;
        init(nCreateAnimator(property, finalValue));
    }

    public RenderNodeAnimator(CanvasProperty<Float> property, float finalValue) {
        init(nCreateCanvasPropertyFloatAnimator(
                property.getNativeContainer(), finalValue));
        mUiThreadHandlesDelay = false;
    }

    /**
     * Creates a new render node animator for a field on a Paint property.
     *
     * @param property The paint property to target
     * @param paintField Paint field to animate, one of {@link #PAINT_ALPHA} or
     *            {@link #PAINT_STROKE_WIDTH}
     * @param finalValue The target value for the property
     */
    public RenderNodeAnimator(CanvasProperty<Paint> property, int paintField, float finalValue) {
        init(nCreateCanvasPropertyPaintAnimator(
                property.getNativeContainer(), paintField, finalValue));
        mUiThreadHandlesDelay = false;
    }

    public RenderNodeAnimator(int x, int y, float startRadius, float endRadius) {
        init(nCreateRevealAnimator(x, y, startRadius, endRadius));
        mUiThreadHandlesDelay = true;
    }

    private void init(long ptr) {
        mNativePtr = new VirtualRefBasePtr(ptr);
    }

    private void checkMutable() {
        if (mState != STATE_PREPARE) {
            throw new IllegalStateException("Animator has already started, cannot change it now!");
        }
        if (mNativePtr == null) {
            throw new IllegalStateException("Animator's target has been destroyed "
                    + "(trying to modify an animation after activity destroy?)");
        }
    }

    static boolean isNativeInterpolator(TimeInterpolator interpolator) {
        return interpolator.getClass().isAnnotationPresent(HasNativeInterpolator.class);
    }

    private void applyInterpolator() {
        if (mInterpolator == null) return;

        long ni;
        if (isNativeInterpolator(mInterpolator)) {
            ni = ((NativeInterpolatorFactory)mInterpolator).createNativeInterpolator();
        } else {
            long duration = nGetDuration(mNativePtr.get());
            ni = FallbackLUTInterpolator.createNativeInterpolator(mInterpolator, duration);
        }
        nSetInterpolator(mNativePtr.get(), ni);
    }

    @Override
    public void start() {
        if (mTarget == null) {
            throw new IllegalStateException("Missing target!");
        }

        if (mState != STATE_PREPARE) {
            throw new IllegalStateException("Already started!");
        }

        mState = STATE_DELAYED;
        applyInterpolator();

        if (mNativePtr == null) {
            // It's dead, immediately cancel
            cancel();
        } else if (mStartDelay <= 0 || !mUiThreadHandlesDelay) {
            nSetStartDelay(mNativePtr.get(), mStartDelay);
            doStart();
        } else {
            getHelper().addDelayedAnimation(this);
        }
    }

    private void doStart() {
        // Alpha is a special snowflake that has the canonical value stored
        // in mTransformationInfo instead of in RenderNode, so we need to update
        // it with the final value here.
        if (mRenderProperty == RenderNodeAnimator.ALPHA) {
            // Don't need null check because ViewPropertyAnimator's
            // ctor calls ensureTransformationInfo()
            mViewTarget.mTransformationInfo.mAlpha = mFinalValue;
        }

        moveToRunningState();

        if (mViewTarget != null) {
            // Kick off a frame to start the process
            mViewTarget.invalidateViewProperty(true, false);
        }
    }

    private void moveToRunningState() {
        mState = STATE_RUNNING;
        if (mNativePtr != null) {
            nStart(mNativePtr.get());
        }
        notifyStartListeners();
    }

    private void notifyStartListeners() {
        final ArrayList<AnimatorListener> listeners = cloneListeners();
        final int numListeners = listeners == null ? 0 : listeners.size();
        for (int i = 0; i < numListeners; i++) {
            listeners.get(i).onAnimationStart(this);
        }
    }

    @Override
    public void cancel() {
        if (mState != STATE_PREPARE && mState != STATE_FINISHED) {
            if (mState == STATE_DELAYED) {
                getHelper().removeDelayedAnimation(this);
                moveToRunningState();
            }

            final ArrayList<AnimatorListener> listeners = cloneListeners();
            final int numListeners = listeners == null ? 0 : listeners.size();
            for (int i = 0; i < numListeners; i++) {
                listeners.get(i).onAnimationCancel(this);
            }

            end();
        }
    }

    @Override
    public void end() {
        if (mState != STATE_FINISHED) {
            if (mState < STATE_RUNNING) {
                getHelper().removeDelayedAnimation(this);
                doStart();
            }
            if (mNativePtr != null) {
                nEnd(mNativePtr.get());
                if (mViewTarget != null) {
                    // Kick off a frame to flush the state change
                    mViewTarget.invalidateViewProperty(true, false);
                }
            } else {
                // It's already dead, jump to onFinish
                onFinished();
            }
        }
    }

    @Override
    public void pause() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void resume() {
        throw new UnsupportedOperationException();
    }

    public void setTarget(View view) {
        mViewTarget = view;
        setTarget(mViewTarget.mRenderNode);
    }

    public void setTarget(Canvas canvas) {
        if (!(canvas instanceof GLES20RecordingCanvas)) {
            throw new IllegalArgumentException("Not a GLES20RecordingCanvas");
        }
        final GLES20RecordingCanvas recordingCanvas = (GLES20RecordingCanvas) canvas;
        setTarget(recordingCanvas.mNode);
    }

    private void setTarget(RenderNode node) {
        checkMutable();
        if (mTarget != null) {
            throw new IllegalStateException("Target already set!");
        }
        nSetListener(mNativePtr.get(), this);
        mTarget = node;
        mTarget.addAnimator(this);
    }

    public void setStartValue(float startValue) {
        checkMutable();
        nSetStartValue(mNativePtr.get(), startValue);
    }

    @Override
    public void setStartDelay(long startDelay) {
        checkMutable();
        if (startDelay < 0) {
            throw new IllegalArgumentException("startDelay must be positive; " + startDelay);
        }
        mUnscaledStartDelay = startDelay;
        mStartDelay = (long) (ValueAnimator.getDurationScale() * startDelay);
    }

    @Override
    public long getStartDelay() {
        return mUnscaledStartDelay;
    }

    @Override
    public RenderNodeAnimator setDuration(long duration) {
        checkMutable();
        if (duration < 0) {
            throw new IllegalArgumentException("duration must be positive; " + duration);
        }
        mUnscaledDuration = duration;
        nSetDuration(mNativePtr.get(), (long) (duration * ValueAnimator.getDurationScale()));
        return this;
    }

    @Override
    public long getDuration() {
        return mUnscaledDuration;
    }

    @Override
    public boolean isRunning() {
        return mState == STATE_DELAYED || mState == STATE_RUNNING;
    }

    @Override
    public boolean isStarted() {
        return mState != STATE_PREPARE;
    }

    @Override
    public void setInterpolator(TimeInterpolator interpolator) {
        checkMutable();
        mInterpolator = interpolator;
    }

    @Override
    public TimeInterpolator getInterpolator() {
        return mInterpolator;
    }

    protected void onFinished() {
        if (mState == STATE_PREPARE) {
            // Unlikely but possible, the native side has been destroyed
            // before we have started.
            releaseNativePtr();
            return;
        }
        if (mState == STATE_DELAYED) {
            getHelper().removeDelayedAnimation(this);
            notifyStartListeners();
        }
        mState = STATE_FINISHED;

        final ArrayList<AnimatorListener> listeners = cloneListeners();
        final int numListeners = listeners == null ? 0 : listeners.size();
        for (int i = 0; i < numListeners; i++) {
            listeners.get(i).onAnimationEnd(this);
        }

        // Release the native object, as it has a global reference to us. This
        // breaks the cyclic reference chain, and allows this object to be
        // GC'd
        releaseNativePtr();
    }

    private void releaseNativePtr() {
        if (mNativePtr != null) {
            mNativePtr.release();
            mNativePtr = null;
        }
    }

    @SuppressWarnings("unchecked")
    private ArrayList<AnimatorListener> cloneListeners() {
        ArrayList<AnimatorListener> listeners = getListeners();
        if (listeners != null) {
            listeners = (ArrayList<AnimatorListener>) listeners.clone();
        }
        return listeners;
    }

    long getNativeAnimator() {
        return mNativePtr.get();
    }

    /**
     * @return true if the animator was started, false if still delayed
     */
    private boolean processDelayed(long frameTimeMs) {
        if (mStartTime == 0) {
            mStartTime = frameTimeMs;
        } else if ((frameTimeMs - mStartTime) >= mStartDelay) {
            doStart();
            return true;
        }
        return false;
    }

    private static DelayedAnimationHelper getHelper() {
        DelayedAnimationHelper helper = sAnimationHelper.get();
        if (helper == null) {
            helper = new DelayedAnimationHelper();
            sAnimationHelper.set(helper);
        }
        return helper;
    }

    private static ThreadLocal<DelayedAnimationHelper> sAnimationHelper =
            new ThreadLocal<DelayedAnimationHelper>();

    private static class DelayedAnimationHelper implements Runnable {

        private ArrayList<RenderNodeAnimator> mDelayedAnims = new ArrayList<RenderNodeAnimator>();
        private final Choreographer mChoreographer;
        private boolean mCallbackScheduled;

        public DelayedAnimationHelper() {
            mChoreographer = Choreographer.getInstance();
        }

        public void addDelayedAnimation(RenderNodeAnimator animator) {
            mDelayedAnims.add(animator);
            scheduleCallback();
        }

        public void removeDelayedAnimation(RenderNodeAnimator animator) {
            mDelayedAnims.remove(animator);
        }

        private void scheduleCallback() {
            if (!mCallbackScheduled) {
                mCallbackScheduled = true;
                mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION, this, null);
            }
        }

        @Override
        public void run() {
            long frameTimeMs = mChoreographer.getFrameTime();
            mCallbackScheduled = false;

            int end = 0;
            for (int i = 0; i < mDelayedAnims.size(); i++) {
                RenderNodeAnimator animator = mDelayedAnims.get(i);
                if (!animator.processDelayed(frameTimeMs)) {
                    if (end != i) {
                        mDelayedAnims.set(end, animator);
                    }
                    end++;
                }
            }
            while (mDelayedAnims.size() > end) {
                mDelayedAnims.remove(mDelayedAnims.size() - 1);
            }

            if (mDelayedAnims.size() > 0) {
                scheduleCallback();
            }
        }
    }

    // Called by native
    private static void callOnFinished(RenderNodeAnimator animator) {
        animator.onFinished();
    }

    @Override
    public Animator clone() {
        throw new IllegalStateException("Cannot clone this animator");
    }

    @Override
    public void setAllowRunningAsynchronously(boolean mayRunAsync) {
        checkMutable();
        nSetAllowRunningAsync(mNativePtr.get(), mayRunAsync);
    }

    private static native long nCreateAnimator(int property, float finalValue);
    private static native long nCreateCanvasPropertyFloatAnimator(
            long canvasProperty, float finalValue);
    private static native long nCreateCanvasPropertyPaintAnimator(
            long canvasProperty, int paintField, float finalValue);
    private static native long nCreateRevealAnimator(
            int x, int y, float startRadius, float endRadius);

    private static native void nSetStartValue(long nativePtr, float startValue);
    private static native void nSetDuration(long nativePtr, long duration);
    private static native long nGetDuration(long nativePtr);
    private static native void nSetStartDelay(long nativePtr, long startDelay);
    private static native void nSetInterpolator(long animPtr, long interpolatorPtr);
    private static native void nSetAllowRunningAsync(long animPtr, boolean mayRunAsync);
    private static native void nSetListener(long animPtr, RenderNodeAnimator listener);

    private static native void nStart(long animPtr);
    private static native void nEnd(long animPtr);
}
