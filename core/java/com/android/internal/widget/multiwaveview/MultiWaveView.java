/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.widget.multiwaveview;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;

import java.util.ArrayList;

/**
 * A special widget containing a center and outer ring. Moving the center ring to the outer ring
 * causes an event that can be caught by implementing OnTriggerListener.
 */
public class MultiWaveView extends View {
    private static final String TAG = "MultiWaveView";
    private static final boolean DEBUG = false;

    // Wave state machine
    private static final int STATE_IDLE = 0;
    private static final int STATE_START = 1;
    private static final int STATE_FIRST_TOUCH = 2;
    private static final int STATE_TRACKING = 3;
    private static final int STATE_SNAP = 4;
    private static final int STATE_FINISH = 5;

    // Animation properties.
    private static final float SNAP_MARGIN_DEFAULT = 20.0f; // distance to ring before we snap to it

    public interface OnTriggerListener {
        int NO_HANDLE = 0;
        int CENTER_HANDLE = 1;
        public void onGrabbed(View v, int handle);
        public void onReleased(View v, int handle);
        public void onTrigger(View v, int target);
        public void onGrabbedStateChange(View v, int handle);
        public void onFinishFinalAnimation();
    }

    // Tuneable parameters for animation
    private static final int CHEVRON_INCREMENTAL_DELAY = 160;
    private static final int CHEVRON_ANIMATION_DURATION = 850;
    private static final int RETURN_TO_HOME_DELAY = 1200;
    private static final int RETURN_TO_HOME_DURATION = 200;
    private static final int HIDE_ANIMATION_DELAY = 200;
    private static final int HIDE_ANIMATION_DURATION = 200;
    private static final int SHOW_ANIMATION_DURATION = 200;
    private static final int SHOW_ANIMATION_DELAY = 50;
    private static final int INITIAL_SHOW_HANDLE_DURATION = 200;

    private static final float TAP_RADIUS_SCALE_ACCESSIBILITY_ENABLED = 1.3f;
    private static final float TARGET_SCALE_EXPANDED = 1.0f;
    private static final float TARGET_SCALE_COLLAPSED = 0.8f;
    private static final float RING_SCALE_EXPANDED = 1.0f;
    private static final float RING_SCALE_COLLAPSED = 0.5f;

    private TimeInterpolator mChevronAnimationInterpolator = Ease.Quad.easeOut;

    private ArrayList<TargetDrawable> mTargetDrawables = new ArrayList<TargetDrawable>();
    private ArrayList<TargetDrawable> mChevronDrawables = new ArrayList<TargetDrawable>();
    private AnimationBundle mChevronAnimations = new AnimationBundle();
    private AnimationBundle mTargetAnimations = new AnimationBundle();
    private AnimationBundle mHandleAnimations = new AnimationBundle();
    private ArrayList<String> mTargetDescriptions;
    private ArrayList<String> mDirectionDescriptions;
    private OnTriggerListener mOnTriggerListener;
    private TargetDrawable mHandleDrawable;
    private TargetDrawable mOuterRing;
    private Vibrator mVibrator;

    private int mFeedbackCount = 3;
    private int mVibrationDuration = 0;
    private int mGrabbedState;
    private int mActiveTarget = -1;
    private float mTapRadius;
    private float mWaveCenterX;
    private float mWaveCenterY;
    private int mMaxTargetHeight;
    private int mMaxTargetWidth;

    private float mOuterRadius = 0.0f;
    private float mSnapMargin = 0.0f;
    private boolean mDragging;
    private int mNewTargetResources;

    private class AnimationBundle extends ArrayList<Tweener> {
        private static final long serialVersionUID = 0xA84D78726F127468L;
        private boolean mSuspended;

        public void start() {
            if (mSuspended) return; // ignore attempts to start animations
            final int count = size();
            for (int i = 0; i < count; i++) {
                Tweener anim = get(i);
                anim.animator.start();
            }
        }

        public void cancel() {
            final int count = size();
            for (int i = 0; i < count; i++) {
                Tweener anim = get(i);
                anim.animator.cancel();
            }
            clear();
        }

        public void stop() {
            final int count = size();
            for (int i = 0; i < count; i++) {
                Tweener anim = get(i);
                anim.animator.end();
            }
            clear();
        }

        public void setSuspended(boolean suspend) {
            mSuspended = suspend;
        }
    };

    private AnimatorListener mResetListener = new AnimatorListenerAdapter() {
        public void onAnimationEnd(Animator animator) {
            switchToState(STATE_IDLE, mWaveCenterX, mWaveCenterY);
            dispatchOnFinishFinalAnimation();
        }
    };

    private AnimatorListener mResetListenerWithPing = new AnimatorListenerAdapter() {
        public void onAnimationEnd(Animator animator) {
            ping();
            switchToState(STATE_IDLE, mWaveCenterX, mWaveCenterY);
            dispatchOnFinishFinalAnimation();
        }
    };

    private AnimatorUpdateListener mUpdateListener = new AnimatorUpdateListener() {
        public void onAnimationUpdate(ValueAnimator animation) {
            invalidateGlobalRegion(mHandleDrawable);
            invalidate();
        }
    };

    private boolean mAnimatingTargets;
    private AnimatorListener mTargetUpdateListener = new AnimatorListenerAdapter() {
        public void onAnimationEnd(Animator animator) {
            if (mNewTargetResources != 0) {
                internalSetTargetResources(mNewTargetResources);
                mNewTargetResources = 0;
                hideTargets(false, false);
            }
            mAnimatingTargets = false;
        }
    };
    private int mTargetResourceId;
    private int mTargetDescriptionsResourceId;
    private int mDirectionDescriptionsResourceId;
    private boolean mAlwaysTrackFinger;
    private int mHorizontalInset;
    private int mVerticalInset;
    private int mGravity = Gravity.TOP;
    private boolean mInitialLayout = true;
    private Tweener mBackgroundAnimator;

    public MultiWaveView(Context context) {
        this(context, null);
    }

    public MultiWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        Resources res = context.getResources();

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.MultiWaveView);
        mOuterRadius = a.getDimension(R.styleable.MultiWaveView_outerRadius, mOuterRadius);
        mSnapMargin = a.getDimension(R.styleable.MultiWaveView_snapMargin, mSnapMargin);
        mVibrationDuration = a.getInt(R.styleable.MultiWaveView_vibrationDuration,
                mVibrationDuration);
        mFeedbackCount = a.getInt(R.styleable.MultiWaveView_feedbackCount,
                mFeedbackCount);
        mHandleDrawable = new TargetDrawable(res,
                a.peekValue(R.styleable.MultiWaveView_handleDrawable).resourceId);
        mTapRadius = mHandleDrawable.getWidth()/2;
        mOuterRing = new TargetDrawable(res,
                a.peekValue(R.styleable.MultiWaveView_waveDrawable).resourceId);
        mAlwaysTrackFinger = a.getBoolean(R.styleable.MultiWaveView_alwaysTrackFinger, false);

        // Read array of chevron drawables
        TypedValue outValue = new TypedValue();
        if (a.getValue(R.styleable.MultiWaveView_chevronDrawables, outValue)) {
            ArrayList<TargetDrawable> chevrons = loadDrawableArray(outValue.resourceId);
            for (int i = 0; i < chevrons.size(); i++) {
                final TargetDrawable chevron = chevrons.get(i);
                for (int k = 0; k < mFeedbackCount; k++) {
                    mChevronDrawables.add(chevron == null ? null : new TargetDrawable(chevron));
                }
            }
        }

        // Read array of target drawables
        if (a.getValue(R.styleable.MultiWaveView_targetDrawables, outValue)) {
            internalSetTargetResources(outValue.resourceId);
        }
        if (mTargetDrawables == null || mTargetDrawables.size() == 0) {
            throw new IllegalStateException("Must specify at least one target drawable");
        }

        // Read array of target descriptions
        if (a.getValue(R.styleable.MultiWaveView_targetDescriptions, outValue)) {
            final int resourceId = outValue.resourceId;
            if (resourceId == 0) {
                throw new IllegalStateException("Must specify target descriptions");
            }
            setTargetDescriptionsResourceId(resourceId);
        }

        // Read array of direction descriptions
        if (a.getValue(R.styleable.MultiWaveView_directionDescriptions, outValue)) {
            final int resourceId = outValue.resourceId;
            if (resourceId == 0) {
                throw new IllegalStateException("Must specify direction descriptions");
            }
            setDirectionDescriptionsResourceId(resourceId);
        }

        a.recycle();

        // Use gravity attribute from LinearLayout
        a = context.obtainStyledAttributes(attrs, android.R.styleable.LinearLayout);
        mGravity = a.getInt(android.R.styleable.LinearLayout_gravity, Gravity.TOP);
        a.recycle();

        setVibrateEnabled(mVibrationDuration > 0);
        assignDefaultsIfNeeded();
    }

    private void dump() {
        Log.v(TAG, "Outer Radius = " + mOuterRadius);
        Log.v(TAG, "SnapMargin = " + mSnapMargin);
        Log.v(TAG, "FeedbackCount = " + mFeedbackCount);
        Log.v(TAG, "VibrationDuration = " + mVibrationDuration);
        Log.v(TAG, "TapRadius = " + mTapRadius);
        Log.v(TAG, "WaveCenterX = " + mWaveCenterX);
        Log.v(TAG, "WaveCenterY = " + mWaveCenterY);
    }

    public void suspendAnimations() {
        mChevronAnimations.setSuspended(true);
        mTargetAnimations.setSuspended(true);
        mHandleAnimations.setSuspended(true);
    }

    public void resumeAnimations() {
        mChevronAnimations.setSuspended(false);
        mTargetAnimations.setSuspended(false);
        mHandleAnimations.setSuspended(false);
        mChevronAnimations.start();
        mTargetAnimations.start();
        mHandleAnimations.start();
    }

    @Override
    protected int getSuggestedMinimumWidth() {
        // View should be large enough to contain the background + handle and
        // target drawable on either edge.
        return (int) (Math.max(mOuterRing.getWidth(), 2 * mOuterRadius) + mMaxTargetWidth);
    }

    @Override
    protected int getSuggestedMinimumHeight() {
        // View should be large enough to contain the unlock ring + target and
        // target drawable on either edge
        return (int) (Math.max(mOuterRing.getHeight(), 2 * mOuterRadius) + mMaxTargetHeight);
    }

    private int resolveMeasured(int measureSpec, int desired)
    {
        int result = 0;
        int specSize = MeasureSpec.getSize(measureSpec);
        switch (MeasureSpec.getMode(measureSpec)) {
            case MeasureSpec.UNSPECIFIED:
                result = desired;
                break;
            case MeasureSpec.AT_MOST:
                result = Math.min(specSize, desired);
                break;
            case MeasureSpec.EXACTLY:
            default:
                result = specSize;
        }
        return result;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int minimumWidth = getSuggestedMinimumWidth();
        final int minimumHeight = getSuggestedMinimumHeight();
        int computedWidth = resolveMeasured(widthMeasureSpec, minimumWidth);
        int computedHeight = resolveMeasured(heightMeasureSpec, minimumHeight);
        computeInsets((computedWidth - minimumWidth), (computedHeight - minimumHeight));
        setMeasuredDimension(computedWidth, computedHeight);
    }

    private void switchToState(int state, float x, float y) {
        switch (state) {
            case STATE_IDLE:
                deactivateTargets();
                hideTargets(true, false);
                startBackgroundAnimation(0, 0.0f);
                mHandleDrawable.setState(TargetDrawable.STATE_INACTIVE);
                break;

            case STATE_START:
                deactivateHandle(0, 0, 1.0f, null);
                startBackgroundAnimation(0, 0.0f);
                break;

            case STATE_FIRST_TOUCH:
                deactivateTargets();
                showTargets(true);
                mHandleDrawable.setState(TargetDrawable.STATE_ACTIVE);
                startBackgroundAnimation(INITIAL_SHOW_HANDLE_DURATION, 1.0f);
                setGrabbedState(OnTriggerListener.CENTER_HANDLE);
                if (AccessibilityManager.getInstance(mContext).isEnabled()) {
                    announceTargets();
                }
                break;

            case STATE_TRACKING:
                break;

            case STATE_SNAP:
                break;

            case STATE_FINISH:
                doFinish();
                break;
        }
    }

    private void activateHandle(int duration, int delay, float finalAlpha,
            AnimatorListener finishListener) {
        mHandleAnimations.cancel();
        mHandleAnimations.add(Tweener.to(mHandleDrawable, duration,
                "ease", Ease.Cubic.easeIn,
                "delay", delay,
                "alpha", finalAlpha,
                "onUpdate", mUpdateListener,
                "onComplete", finishListener));
        mHandleAnimations.start();
    }

    private void deactivateHandle(int duration, int delay, float finalAlpha,
            AnimatorListener finishListener) {
        mHandleAnimations.cancel();
        mHandleAnimations.add(Tweener.to(mHandleDrawable, duration,
            "ease", Ease.Quart.easeOut,
            "delay", delay,
            "alpha", finalAlpha,
            "x", 0,
            "y", 0,
            "onUpdate", mUpdateListener,
            "onComplete", finishListener));
        mHandleAnimations.start();
    }

    /**
     * Animation used to attract user's attention to the target button.
     * Assumes mChevronDrawables is an a list with an even number of chevrons filled with
     * mFeedbackCount items in the order: left, right, top, bottom.
     */
    private void startChevronAnimation() {
        final float chevronStartDistance = mHandleDrawable.getWidth() * 0.8f;
        final float chevronStopDistance = mOuterRadius * 0.9f / 2.0f;
        final float startScale = 0.5f;
        final float endScale = 2.0f;
        final int directionCount = mFeedbackCount > 0 ? mChevronDrawables.size()/mFeedbackCount : 0;

        mChevronAnimations.stop();

        // Add an animation for all chevron drawables.  There are mFeedbackCount drawables
        // in each direction and directionCount directions.
        for (int direction = 0; direction < directionCount; direction++) {
            double angle = 2.0 * Math.PI * direction / directionCount;
            final float sx = (float) Math.cos(angle);
            final float sy = 0.0f - (float) Math.sin(angle);
            final float[] xrange = new float[]
                 {sx * chevronStartDistance, sx * chevronStopDistance};
            final float[] yrange = new float[]
                 {sy * chevronStartDistance, sy * chevronStopDistance};
            for (int count = 0; count < mFeedbackCount; count++) {
                int delay = count * CHEVRON_INCREMENTAL_DELAY;
                final TargetDrawable icon = mChevronDrawables.get(direction*mFeedbackCount + count);
                if (icon == null) {
                    continue;
                }
                mChevronAnimations.add(Tweener.to(icon, CHEVRON_ANIMATION_DURATION,
                        "ease", mChevronAnimationInterpolator,
                        "delay", delay,
                        "x", xrange,
                        "y", yrange,
                        "alpha", new float[] {1.0f, 0.0f},
                        "scaleX", new float[] {startScale, endScale},
                        "scaleY", new float[] {startScale, endScale},
                        "onUpdate", mUpdateListener));
            }
        }
        mChevronAnimations.start();
    }

    private void deactivateTargets() {
        final int count = mTargetDrawables.size();
        for (int i = 0; i < count; i++) {
            TargetDrawable target = mTargetDrawables.get(i);
            target.setState(TargetDrawable.STATE_INACTIVE);
        }
        mActiveTarget = -1;
    }

    void invalidateGlobalRegion(TargetDrawable drawable) {
        int width = drawable.getWidth();
        int height = drawable.getHeight();
        RectF childBounds = new RectF(0, 0, width, height);
        childBounds.offset(drawable.getX() - width/2, drawable.getY() - height/2);
        View view = this;
        while (view.getParent() != null && view.getParent() instanceof View) {
            view = (View) view.getParent();
            view.getMatrix().mapRect(childBounds);
            view.invalidate((int) Math.floor(childBounds.left),
                    (int) Math.floor(childBounds.top),
                    (int) Math.ceil(childBounds.right),
                    (int) Math.ceil(childBounds.bottom));
        }
    }

    /**
     * Dispatches a trigger event to listener. Ignored if a listener is not set.
     * @param whichTarget the target that was triggered.
     */
    private void dispatchTriggerEvent(int whichTarget) {
        vibrate();
        if (mOnTriggerListener != null) {
            mOnTriggerListener.onTrigger(this, whichTarget);
        }
    }

    private void dispatchOnFinishFinalAnimation() {
        if (mOnTriggerListener != null) {
            mOnTriggerListener.onFinishFinalAnimation();
        }
    }

    private void doFinish() {
        final int activeTarget = mActiveTarget;
        final boolean targetHit =  activeTarget != -1;

        if (targetHit) {
            if (DEBUG) Log.v(TAG, "Finish with target hit = " + targetHit);

            highlightSelected(activeTarget);

            // Inform listener of any active targets.  Typically only one will be active.
            deactivateHandle(RETURN_TO_HOME_DURATION, RETURN_TO_HOME_DELAY, 0.0f, mResetListener);
            dispatchTriggerEvent(activeTarget);
            if (!mAlwaysTrackFinger) {
                // Force ring and targets to finish animation to final expanded state
                mTargetAnimations.stop();
            }
        } else {
            // Animate handle back to the center based on current state.
            deactivateHandle(HIDE_ANIMATION_DURATION, HIDE_ANIMATION_DELAY, 1.0f,
                    mResetListenerWithPing);
            hideTargets(true, false);
        }

        setGrabbedState(OnTriggerListener.NO_HANDLE);
    }

    private void highlightSelected(int activeTarget) {
        // Highlight the given target and fade others
        mTargetDrawables.get(activeTarget).setState(TargetDrawable.STATE_ACTIVE);
        hideUnselected(activeTarget);
    }

    private void hideUnselected(int active) {
        for (int i = 0; i < mTargetDrawables.size(); i++) {
            if (i != active) {
                mTargetDrawables.get(i).setAlpha(0.0f);
            }
        }
    }

    private void hideTargets(boolean animate, boolean expanded) {
        mTargetAnimations.cancel();
        // Note: these animations should complete at the same time so that we can swap out
        // the target assets asynchronously from the setTargetResources() call.
        mAnimatingTargets = animate;
        final int duration = animate ? HIDE_ANIMATION_DURATION : 0;
        final int delay = animate ? HIDE_ANIMATION_DELAY : 0;

        final float targetScale = expanded ? TARGET_SCALE_EXPANDED : TARGET_SCALE_COLLAPSED;
        final int length = mTargetDrawables.size();
        for (int i = 0; i < length; i++) {
            TargetDrawable target = mTargetDrawables.get(i);
            target.setState(TargetDrawable.STATE_INACTIVE);
            mTargetAnimations.add(Tweener.to(target, duration,
                    "ease", Ease.Cubic.easeOut,
                    "alpha", 0.0f,
                    "scaleX", targetScale,
                    "scaleY", targetScale,
                    "delay", delay,
                    "onUpdate", mUpdateListener));
        }

        final float ringScaleTarget = expanded ? RING_SCALE_EXPANDED : RING_SCALE_COLLAPSED;
        mTargetAnimations.add(Tweener.to(mOuterRing, duration,
                "ease", Ease.Cubic.easeOut,
                "alpha", 0.0f,
                "scaleX", ringScaleTarget,
                "scaleY", ringScaleTarget,
                "delay", delay,
                "onUpdate", mUpdateListener,
                "onComplete", mTargetUpdateListener));

        mTargetAnimations.start();
    }

    private void showTargets(boolean animate) {
        mTargetAnimations.stop();
        mAnimatingTargets = animate;
        final int delay = animate ? SHOW_ANIMATION_DELAY : 0;
        final int duration = animate ? SHOW_ANIMATION_DURATION : 0;
        final int length = mTargetDrawables.size();
        for (int i = 0; i < length; i++) {
            TargetDrawable target = mTargetDrawables.get(i);
            target.setState(TargetDrawable.STATE_INACTIVE);
            mTargetAnimations.add(Tweener.to(target, duration,
                    "ease", Ease.Cubic.easeOut,
                    "alpha", 1.0f,
                    "scaleX", 1.0f,
                    "scaleY", 1.0f,
                    "delay", delay,
                    "onUpdate", mUpdateListener));
        }
        mTargetAnimations.add(Tweener.to(mOuterRing, duration,
                "ease", Ease.Cubic.easeOut,
                "alpha", 1.0f,
                "scaleX", 1.0f,
                "scaleY", 1.0f,
                "delay", delay,
                "onUpdate", mUpdateListener,
                "onComplete", mTargetUpdateListener));

        mTargetAnimations.start();
    }

    private void vibrate() {
        final boolean hapticEnabled = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1,
                UserHandle.USER_CURRENT) != 0;
        if (mVibrator != null && hapticEnabled) {
            mVibrator.vibrate(mVibrationDuration);
        }
    }

    private ArrayList<TargetDrawable> loadDrawableArray(int resourceId) {
        Resources res = getContext().getResources();
        TypedArray array = res.obtainTypedArray(resourceId);
        final int count = array.length();
        ArrayList<TargetDrawable> drawables = new ArrayList<TargetDrawable>(count);
        for (int i = 0; i < count; i++) {
            TypedValue value = array.peekValue(i);
            TargetDrawable target = new TargetDrawable(res, value != null ? value.resourceId : 0);
            drawables.add(target);
        }
        array.recycle();
        return drawables;
    }

    private void internalSetTargetResources(int resourceId) {
        mTargetDrawables = loadDrawableArray(resourceId);
        mTargetResourceId = resourceId;
        final int count = mTargetDrawables.size();
        int maxWidth = mHandleDrawable.getWidth();
        int maxHeight = mHandleDrawable.getHeight();
        for (int i = 0; i < count; i++) {
            TargetDrawable target = mTargetDrawables.get(i);
            maxWidth = Math.max(maxWidth, target.getWidth());
            maxHeight = Math.max(maxHeight, target.getHeight());
        }
        if (mMaxTargetWidth != maxWidth || mMaxTargetHeight != maxHeight) {
            mMaxTargetWidth = maxWidth;
            mMaxTargetHeight = maxHeight;
            requestLayout(); // required to resize layout and call updateTargetPositions()
        } else {
            updateTargetPositions(mWaveCenterX, mWaveCenterY);
            updateChevronPositions(mWaveCenterX, mWaveCenterY);
        }
    }

    /**
     * Loads an array of drawables from the given resourceId.
     *
     * @param resourceId
     */
    public void setTargetResources(int resourceId) {
        if (mAnimatingTargets) {
            // postpone this change until we return to the initial state
            mNewTargetResources = resourceId;
        } else {
            internalSetTargetResources(resourceId);
        }
    }

    public int getTargetResourceId() {
        return mTargetResourceId;
    }

    /**
     * Sets the resource id specifying the target descriptions for accessibility.
     *
     * @param resourceId The resource id.
     */
    public void setTargetDescriptionsResourceId(int resourceId) {
        mTargetDescriptionsResourceId = resourceId;
        if (mTargetDescriptions != null) {
            mTargetDescriptions.clear();
        }
    }

    /**
     * Gets the resource id specifying the target descriptions for accessibility.
     *
     * @return The resource id.
     */
    public int getTargetDescriptionsResourceId() {
        return mTargetDescriptionsResourceId;
    }

    /**
     * Sets the resource id specifying the target direction descriptions for accessibility.
     *
     * @param resourceId The resource id.
     */
    public void setDirectionDescriptionsResourceId(int resourceId) {
        mDirectionDescriptionsResourceId = resourceId;
        if (mDirectionDescriptions != null) {
            mDirectionDescriptions.clear();
        }
    }

    /**
     * Gets the resource id specifying the target direction descriptions.
     *
     * @return The resource id.
     */
    public int getDirectionDescriptionsResourceId() {
        return mDirectionDescriptionsResourceId;
    }

    /**
     * Enable or disable vibrate on touch.
     *
     * @param enabled
     */
    public void setVibrateEnabled(boolean enabled) {
        if (enabled && mVibrator == null) {
            mVibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        } else {
            mVibrator = null;
        }
    }

    /**
     * Starts chevron animation. Example use case: show chevron animation whenever the phone rings
     * or the user touches the screen.
     *
     */
    public void ping() {
        startChevronAnimation();
    }

    /**
     * Resets the widget to default state and cancels all animation. If animate is 'true', will
     * animate objects into place. Otherwise, objects will snap back to place.
     *
     * @param animate
     */
    public void reset(boolean animate) {
        mChevronAnimations.stop();
        mHandleAnimations.stop();
        mTargetAnimations.stop();
        startBackgroundAnimation(0, 0.0f);
        hideChevrons();
        hideTargets(animate, false);
        deactivateHandle(0, 0, 1.0f, null);
        Tweener.reset();
    }

    private void startBackgroundAnimation(int duration, float alpha) {
        Drawable background = getBackground();
        if (mAlwaysTrackFinger && background != null) {
            if (mBackgroundAnimator != null) {
                mBackgroundAnimator.animator.end();
            }
            mBackgroundAnimator = Tweener.to(background, duration,
                    "ease", Ease.Cubic.easeIn,
                    "alpha", new int[] {0, (int)(255.0f * alpha)},
                    "delay", SHOW_ANIMATION_DELAY);
            mBackgroundAnimator.animator.start();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getAction();
        boolean handled = false;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (DEBUG) Log.v(TAG, "*** DOWN ***");
                handleDown(event);
                handled = true;
                break;

            case MotionEvent.ACTION_MOVE:
                if (DEBUG) Log.v(TAG, "*** MOVE ***");
                handleMove(event);
                handled = true;
                break;

            case MotionEvent.ACTION_UP:
                if (DEBUG) Log.v(TAG, "*** UP ***");
                handleMove(event);
                handleUp(event);
                handled = true;
                break;

            case MotionEvent.ACTION_CANCEL:
                if (DEBUG) Log.v(TAG, "*** CANCEL ***");
                handleMove(event);
                handleCancel(event);
                handled = true;
                break;
        }
        invalidate();
        return handled ? true : super.onTouchEvent(event);
    }

    private void moveHandleTo(float x, float y, boolean animate) {
        mHandleDrawable.setX(x);
        mHandleDrawable.setY(y);
    }

    private void handleDown(MotionEvent event) {
        float eventX = event.getX();
        float eventY = event.getY();
        switchToState(STATE_START, eventX, eventY);
        if (!trySwitchToFirstTouchState(eventX, eventY)) {
            mDragging = false;
            ping();
        }
    }

    private void handleUp(MotionEvent event) {
        if (DEBUG && mDragging) Log.v(TAG, "** Handle RELEASE");
        switchToState(STATE_FINISH, event.getX(), event.getY());
    }

    private void handleCancel(MotionEvent event) {
        if (DEBUG && mDragging) Log.v(TAG, "** Handle CANCEL");

        // We should drop the active target here but it interferes with
        // moving off the screen in the direction of the navigation bar. At some point we may
        // want to revisit how we handle this. For now we'll allow a canceled event to
        // activate the current target.

        // mActiveTarget = -1; // Drop the active target if canceled.

        switchToState(STATE_FINISH, event.getX(), event.getY());
    }

    private void handleMove(MotionEvent event) {
        int activeTarget = -1;
        final int historySize = event.getHistorySize();
        ArrayList<TargetDrawable> targets = mTargetDrawables;
        int ntargets = targets.size();
        float x = 0.0f;
        float y = 0.0f;
        for (int k = 0; k < historySize + 1; k++) {
            float eventX = k < historySize ? event.getHistoricalX(k) : event.getX();
            float eventY = k < historySize ? event.getHistoricalY(k) : event.getY();
            // tx and ty are relative to wave center
            float tx = eventX - mWaveCenterX;
            float ty = eventY - mWaveCenterY;
            float touchRadius = (float) Math.sqrt(dist2(tx, ty));
            final float scale = touchRadius > mOuterRadius ? mOuterRadius / touchRadius : 1.0f;
            float limitX = tx * scale;
            float limitY = ty * scale;
            double angleRad = Math.atan2(-ty, tx);

            if (!mDragging) {
                trySwitchToFirstTouchState(eventX, eventY);
            }

            if (mDragging) {
                // For multiple targets, snap to the one that matches
                final float snapRadius = mOuterRadius - mSnapMargin;
                final float snapDistance2 = snapRadius * snapRadius;
                // Find first target in range
                for (int i = 0; i < ntargets; i++) {
                    TargetDrawable target = targets.get(i);

                    double targetMinRad = (i - 0.5) * 2 * Math.PI / ntargets;
                    double targetMaxRad = (i + 0.5) * 2 * Math.PI / ntargets;
                    if (target.isEnabled()) {
                        boolean angleMatches =
                            (angleRad > targetMinRad && angleRad <= targetMaxRad) ||
                            (angleRad + 2 * Math.PI > targetMinRad &&
                             angleRad + 2 * Math.PI <= targetMaxRad);
                        if (angleMatches && (dist2(tx, ty) > snapDistance2)) {
                            activeTarget = i;
                        }
                    }
                }
            }
            x = limitX;
            y = limitY;
        }

        if (!mDragging) {
            return;
        }

        if (activeTarget != -1) {
            switchToState(STATE_SNAP, x,y);
            moveHandleTo(x, y, false);
        } else {
            switchToState(STATE_TRACKING, x, y);
            moveHandleTo(x, y, false);
        }

        // Draw handle outside parent's bounds
        invalidateGlobalRegion(mHandleDrawable);

        if (mActiveTarget != activeTarget) {
            // Defocus the old target
            if (mActiveTarget != -1) {
                TargetDrawable target = targets.get(mActiveTarget);
                if (target.hasState(TargetDrawable.STATE_FOCUSED)) {
                    target.setState(TargetDrawable.STATE_INACTIVE);
                }
            }
            // Focus the new target
            if (activeTarget != -1) {
                TargetDrawable target = targets.get(activeTarget);
                if (target.hasState(TargetDrawable.STATE_FOCUSED)) {
                    target.setState(TargetDrawable.STATE_FOCUSED);
                }
                if (AccessibilityManager.getInstance(mContext).isEnabled()) {
                    String targetContentDescription = getTargetDescription(activeTarget);
                    announceText(targetContentDescription);
                }
                activateHandle(0, 0, 0.0f, null);
            } else {
                activateHandle(0, 0, 1.0f, null);
            }
        }
        mActiveTarget = activeTarget;
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        if (AccessibilityManager.getInstance(mContext).isTouchExplorationEnabled()) {
            final int action = event.getAction();
            switch (action) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    event.setAction(MotionEvent.ACTION_DOWN);
                    break;
                case MotionEvent.ACTION_HOVER_MOVE:
                    event.setAction(MotionEvent.ACTION_MOVE);
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    event.setAction(MotionEvent.ACTION_UP);
                    break;
            }
            onTouchEvent(event);
            event.setAction(action);
        }
        return super.onHoverEvent(event);
    }

    /**
     * Sets the current grabbed state, and dispatches a grabbed state change
     * event to our listener.
     */
    private void setGrabbedState(int newState) {
        if (newState != mGrabbedState) {
            if (newState != OnTriggerListener.NO_HANDLE) {
                vibrate();
            }
            mGrabbedState = newState;
            if (mOnTriggerListener != null) {
                if (newState == OnTriggerListener.NO_HANDLE) {
                    mOnTriggerListener.onReleased(this, OnTriggerListener.CENTER_HANDLE);
                } else {
                    mOnTriggerListener.onGrabbed(this, OnTriggerListener.CENTER_HANDLE);
                }
                mOnTriggerListener.onGrabbedStateChange(this, newState);
            }
        }
    }

    private boolean trySwitchToFirstTouchState(float x, float y) {
        final float tx = x - mWaveCenterX;
        final float ty = y - mWaveCenterY;
        if (mAlwaysTrackFinger || dist2(tx,ty) <= getScaledTapRadiusSquared()) {
            if (DEBUG) Log.v(TAG, "** Handle HIT");
            switchToState(STATE_FIRST_TOUCH, x, y);
            moveHandleTo(tx, ty, false);
            mDragging = true;
            return true;
        }
        return false;
    }

    private void assignDefaultsIfNeeded() {
        if (mOuterRadius == 0.0f) {
            mOuterRadius = Math.max(mOuterRing.getWidth(), mOuterRing.getHeight())/2.0f;
        }
        if (mSnapMargin == 0.0f) {
            mSnapMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    SNAP_MARGIN_DEFAULT, getContext().getResources().getDisplayMetrics());
        }
    }

    private void computeInsets(int dx, int dy) {
        final int layoutDirection = getLayoutDirection();
        final int absoluteGravity = Gravity.getAbsoluteGravity(mGravity, layoutDirection);

        switch (absoluteGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
            case Gravity.LEFT:
                mHorizontalInset = 0;
                break;
            case Gravity.RIGHT:
                mHorizontalInset = dx;
                break;
            case Gravity.CENTER_HORIZONTAL:
            default:
                mHorizontalInset = dx / 2;
                break;
        }
        switch (absoluteGravity & Gravity.VERTICAL_GRAVITY_MASK) {
            case Gravity.TOP:
                mVerticalInset = 0;
                break;
            case Gravity.BOTTOM:
                mVerticalInset = dy;
                break;
            case Gravity.CENTER_VERTICAL:
            default:
                mVerticalInset = dy / 2;
                break;
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Target placement width/height. This puts the targets on the greater of the ring
        // width or the specified outer radius.
        final float placementWidth = Math.max(mOuterRing.getWidth(), 2 * mOuterRadius);
        final float placementHeight = Math.max(mOuterRing.getHeight(), 2 * mOuterRadius);
        float newWaveCenterX = mHorizontalInset + (mMaxTargetWidth + placementWidth) / 2;
        float newWaveCenterY = mVerticalInset + (mMaxTargetHeight + placementHeight) / 2;

        if (mInitialLayout) {
            hideChevrons();
            hideTargets(false, false);
            moveHandleTo(0, 0, false);
            mInitialLayout = false;
        }

        mOuterRing.setPositionX(newWaveCenterX);
        mOuterRing.setPositionY(newWaveCenterY);

        mHandleDrawable.setPositionX(newWaveCenterX);
        mHandleDrawable.setPositionY(newWaveCenterY);

        updateTargetPositions(newWaveCenterX, newWaveCenterY);
        updateChevronPositions(newWaveCenterX, newWaveCenterY);

        mWaveCenterX = newWaveCenterX;
        mWaveCenterY = newWaveCenterY;

        if (DEBUG) dump();
    }

    private void updateTargetPositions(float centerX, float centerY) {
        // Reposition the target drawables if the view changed.
        ArrayList<TargetDrawable> targets = mTargetDrawables;
        final int size = targets.size();
        final float alpha = (float) (-2.0f * Math.PI / size);
        for (int i = 0; i < size; i++) {
            final TargetDrawable targetIcon = targets.get(i);
            final float angle = alpha * i;
            targetIcon.setPositionX(centerX);
            targetIcon.setPositionY(centerY);
            targetIcon.setX(mOuterRadius * (float) Math.cos(angle));
            targetIcon.setY(mOuterRadius * (float) Math.sin(angle));
        }
    }

    private void updateChevronPositions(float centerX, float centerY) {
        ArrayList<TargetDrawable> chevrons = mChevronDrawables;
        final int size = chevrons.size();
        for (int i = 0; i < size; i++) {
            TargetDrawable target = chevrons.get(i);
            if (target != null) {
                target.setPositionX(centerX);
                target.setPositionY(centerY);
            }
        }
    }

    private void hideChevrons() {
        ArrayList<TargetDrawable> chevrons = mChevronDrawables;
        final int size = chevrons.size();
        for (int i = 0; i < size; i++) {
            TargetDrawable chevron = chevrons.get(i);
            if (chevron != null) {
                chevron.setAlpha(0.0f);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mOuterRing.draw(canvas);
        final int ntargets = mTargetDrawables.size();
        for (int i = 0; i < ntargets; i++) {
            TargetDrawable target = mTargetDrawables.get(i);
            if (target != null) {
                target.draw(canvas);
            }
        }
        final int nchevrons = mChevronDrawables.size();
        for (int i = 0; i < nchevrons; i++) {
            TargetDrawable chevron = mChevronDrawables.get(i);
            if (chevron != null) {
                chevron.draw(canvas);
            }
        }
        mHandleDrawable.draw(canvas);
    }

    public void setOnTriggerListener(OnTriggerListener listener) {
        mOnTriggerListener = listener;
    }

    private float square(float d) {
        return d * d;
    }

    private float dist2(float dx, float dy) {
        return dx*dx + dy*dy;
    }

    private float getScaledTapRadiusSquared() {
        final float scaledTapRadius;
        if (AccessibilityManager.getInstance(mContext).isEnabled()) {
            scaledTapRadius = TAP_RADIUS_SCALE_ACCESSIBILITY_ENABLED * mTapRadius;
        } else {
            scaledTapRadius = mTapRadius;
        }
        return square(scaledTapRadius);
    }

    private void announceTargets() {
        StringBuilder utterance = new StringBuilder();
        final int targetCount = mTargetDrawables.size();
        for (int i = 0; i < targetCount; i++) {
            String targetDescription = getTargetDescription(i);
            String directionDescription = getDirectionDescription(i);
            if (!TextUtils.isEmpty(targetDescription)
                    && !TextUtils.isEmpty(directionDescription)) {
                String text = String.format(directionDescription, targetDescription);
                utterance.append(text);
            }
            if (utterance.length() > 0) {
                announceText(utterance.toString());
            }
        }
    }

    private void announceText(String text) {
        setContentDescription(text);
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
        setContentDescription(null);
    }

    private String getTargetDescription(int index) {
        if (mTargetDescriptions == null || mTargetDescriptions.isEmpty()) {
            mTargetDescriptions = loadDescriptions(mTargetDescriptionsResourceId);
            if (mTargetDrawables.size() != mTargetDescriptions.size()) {
                Log.w(TAG, "The number of target drawables must be"
                        + " euqal to the number of target descriptions.");
                return null;
            }
        }
        return mTargetDescriptions.get(index);
    }

    private String getDirectionDescription(int index) {
        if (mDirectionDescriptions == null || mDirectionDescriptions.isEmpty()) {
            mDirectionDescriptions = loadDescriptions(mDirectionDescriptionsResourceId);
            if (mTargetDrawables.size() != mDirectionDescriptions.size()) {
                Log.w(TAG, "The number of target drawables must be"
                        + " euqal to the number of direction descriptions.");
                return null;
            }
        }
        return mDirectionDescriptions.get(index);
    }

    private ArrayList<String> loadDescriptions(int resourceId) {
        TypedArray array = getContext().getResources().obtainTypedArray(resourceId);
        final int count = array.length();
        ArrayList<String> targetContentDescriptions = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            String contentDescription = array.getString(i);
            targetContentDescriptions.add(contentDescription);
        }
        array.recycle();
        return targetContentDescriptions;
    }

    public int getResourceIdForTarget(int index) {
        final TargetDrawable drawable = mTargetDrawables.get(index);
        return drawable == null ? 0 : drawable.getResourceId();
    }

    public void setEnableTarget(int resourceId, boolean enabled) {
        for (int i = 0; i < mTargetDrawables.size(); i++) {
            final TargetDrawable target = mTargetDrawables.get(i);
            if (target.getResourceId() == resourceId) {
                target.setEnabled(enabled);
                break; // should never be more than one match
            }
        }
    }

    /**
     * Gets the position of a target in the array that matches the given resource.
     * @param resourceId
     * @return the index or -1 if not found
     */
    public int getTargetPosition(int resourceId) {
        for (int i = 0; i < mTargetDrawables.size(); i++) {
            final TargetDrawable target = mTargetDrawables.get(i);
            if (target.getResourceId() == resourceId) {
                return i; // should never be more than one match
            }
        }
        return -1;
    }

    private boolean replaceTargetDrawables(Resources res, int existingResourceId,
            int newResourceId) {
        if (existingResourceId == 0 || newResourceId == 0) {
            return false;
        }

        boolean result = false;
        final ArrayList<TargetDrawable> drawables = mTargetDrawables;
        final int size = drawables.size();
        for (int i = 0; i < size; i++) {
            final TargetDrawable target = drawables.get(i);
            if (target != null && target.getResourceId() == existingResourceId) {
                target.setDrawable(res, newResourceId);
                result = true;
            }
        }

        if (result) {
            requestLayout(); // in case any given drawable's size changes
        }

        return result;
    }

    /**
     * Searches the given package for a resource to use to replace the Drawable on the
     * target with the given resource id
     * @param component of the .apk that contains the resource
     * @param name of the metadata in the .apk
     * @param existingResId the resource id of the target to search for
     * @return true if found in the given package and replaced at least one target Drawables
     */
    public boolean replaceTargetDrawablesIfPresent(ComponentName component, String name,
                int existingResId) {
        if (existingResId == 0) return false;

        try {
            PackageManager packageManager = mContext.getPackageManager();
            // Look for the search icon specified in the activity meta-data
            Bundle metaData = packageManager.getActivityInfo(
                    component, PackageManager.GET_META_DATA).metaData;
            if (metaData != null) {
                int iconResId = metaData.getInt(name);
                if (iconResId != 0) {
                    Resources res = packageManager.getResourcesForActivity(component);
                    return replaceTargetDrawables(res, existingResId, iconResId);
                }
            }
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Failed to swap drawable; "
                    + component.flattenToShortString() + " not found", e);
        } catch (Resources.NotFoundException nfe) {
            Log.w(TAG, "Failed to swap drawable from "
                    + component.flattenToShortString(), nfe);
        }
        return false;
    }
}
