/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.StatusBarState;

import java.io.FileDescriptor;
import java.io.PrintWriter;

public abstract class PanelView extends FrameLayout {
    public static final boolean DEBUG = PanelBar.DEBUG;
    public static final String TAG = PanelView.class.getSimpleName();

    private final void logf(String fmt, Object... args) {
        Log.v(TAG, (mViewName != null ? (mViewName + ": ") : "") + String.format(fmt, args));
    }

    protected PhoneStatusBar mStatusBar;
    private float mPeekHeight;
    private float mHintDistance;
    private int mEdgeTapAreaWidth;
    private float mInitialOffsetOnTouch;
    private float mExpandedFraction = 0;
    protected float mExpandedHeight = 0;
    private boolean mPanelClosedOnDown;
    private boolean mHasLayoutedSinceDown;
    private float mUpdateFlingVelocity;
    private boolean mUpdateFlingOnLayout;
    private boolean mPeekTouching;
    private boolean mJustPeeked;
    private boolean mClosing;
    protected boolean mTracking;
    private boolean mTouchSlopExceeded;
    private int mTrackingPointer;
    protected int mTouchSlop;
    protected boolean mHintAnimationRunning;
    private boolean mOverExpandedBeforeFling;
    private float mOriginalIndicationY;
    private boolean mTouchAboveFalsingThreshold;
    private int mUnlockFalsingThreshold;

    private ValueAnimator mHeightAnimator;
    private ObjectAnimator mPeekAnimator;
    private VelocityTrackerInterface mVelocityTracker;
    private FlingAnimationUtils mFlingAnimationUtils;

    /**
     * Whether an instant expand request is currently pending and we are just waiting for layout.
     */
    private boolean mInstantExpanding;

    PanelBar mBar;

    private String mViewName;
    private float mInitialTouchY;
    private float mInitialTouchX;

    private Interpolator mLinearOutSlowInInterpolator;
    private Interpolator mFastOutSlowInInterpolator;
    private Interpolator mBounceInterpolator;
    protected KeyguardBottomAreaView mKeyguardBottomArea;

    private boolean mPeekPending;
    private boolean mCollapseAfterPeek;
    private boolean mExpanding;
    private boolean mGestureWaitForTouchSlop;
    private Runnable mPeekRunnable = new Runnable() {
        @Override
        public void run() {
            mPeekPending = false;
            runPeekAnimation();
        }
    };

    protected void onExpandingFinished() {
        mClosing = false;
        mBar.onExpandingFinished();
    }

    protected void onExpandingStarted() {
    }

    private void notifyExpandingStarted() {
        if (!mExpanding) {
            mExpanding = true;
            onExpandingStarted();
        }
    }

    private void notifyExpandingFinished() {
        if (mExpanding) {
            mExpanding = false;
            onExpandingFinished();
        }
    }

    private void schedulePeek() {
        mPeekPending = true;
        long timeout = ViewConfiguration.getTapTimeout();
        postOnAnimationDelayed(mPeekRunnable, timeout);
        notifyBarPanelExpansionChanged();
    }

    private void runPeekAnimation() {
        mPeekHeight = getPeekHeight();
        if (DEBUG) logf("peek to height=%.1f", mPeekHeight);
        if (mHeightAnimator != null) {
            return;
        }
        mPeekAnimator = ObjectAnimator.ofFloat(this, "expandedHeight", mPeekHeight)
                .setDuration(250);
        mPeekAnimator.setInterpolator(mLinearOutSlowInInterpolator);
        mPeekAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mPeekAnimator = null;
                if (mCollapseAfterPeek && !mCancelled) {
                    postOnAnimation(new Runnable() {
                        @Override
                        public void run() {
                            collapse(false /* delayed */);
                        }
                    });
                }
                mCollapseAfterPeek = false;
            }
        });
        notifyExpandingStarted();
        mPeekAnimator.start();
        mJustPeeked = true;
    }

    public PanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFlingAnimationUtils = new FlingAnimationUtils(context, 0.6f);
        mFastOutSlowInInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in);
        mLinearOutSlowInInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.linear_out_slow_in);
        mBounceInterpolator = new BounceInterpolator();
    }

    protected void loadDimens() {
        final Resources res = getContext().getResources();
        final ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mHintDistance = res.getDimension(R.dimen.hint_move_distance);
        mEdgeTapAreaWidth = res.getDimensionPixelSize(R.dimen.edge_tap_area_width);
        mUnlockFalsingThreshold = res.getDimensionPixelSize(R.dimen.unlock_falsing_threshold);
    }

    private void trackMovement(MotionEvent event) {
        // Add movement to velocity tracker using raw screen X and Y coordinates instead
        // of window coordinates because the window frame may be moving at the same time.
        float deltaX = event.getRawX() - event.getX();
        float deltaY = event.getRawY() - event.getY();
        event.offsetLocation(deltaX, deltaY);
        if (mVelocityTracker != null) mVelocityTracker.addMovement(event);
        event.offsetLocation(-deltaX, -deltaY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mInstantExpanding) {
            return false;
        }

        /*
         * We capture touch events here and update the expand height here in case according to
         * the users fingers. This also handles multi-touch.
         *
         * If the user just clicks shortly, we give him a quick peek of the shade.
         *
         * Flinging is also enabled in order to open or close the shade.
         */

        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float y = event.getY(pointerIndex);
        final float x = event.getX(pointerIndex);

        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mGestureWaitForTouchSlop = mExpandedHeight == 0f;
        }
        boolean waitForTouchSlop = hasConflictingGestures() || mGestureWaitForTouchSlop;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mInitialTouchY = y;
                mInitialTouchX = x;
                mInitialOffsetOnTouch = mExpandedHeight;
                mTouchSlopExceeded = false;
                mJustPeeked = false;
                mPanelClosedOnDown = mExpandedHeight == 0.0f;
                mHasLayoutedSinceDown = false;
                mUpdateFlingOnLayout = false;
                mPeekTouching = mPanelClosedOnDown;
                mTouchAboveFalsingThreshold = false;
                if (mVelocityTracker == null) {
                    initVelocityTracker();
                }
                trackMovement(event);
                if (!waitForTouchSlop || (mHeightAnimator != null && !mHintAnimationRunning) ||
                        mPeekPending || mPeekAnimator != null) {
                    if (mHeightAnimator != null) {
                        mHeightAnimator.cancel(); // end any outstanding animations
                    }
                    cancelPeek();
                    mTouchSlopExceeded = (mHeightAnimator != null && !mHintAnimationRunning)
                            || mPeekPending || mPeekAnimator != null;
                    onTrackingStarted();
                }
                if (mExpandedHeight == 0) {
                    schedulePeek();
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    final float newY = event.getY(newIndex);
                    final float newX = event.getX(newIndex);
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialOffsetOnTouch = mExpandedHeight;
                    mInitialTouchY = newY;
                    mInitialTouchX = newX;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                float h = y - mInitialTouchY;

                // If the panel was collapsed when touching, we only need to check for the
                // y-component of the gesture, as we have no conflicting horizontal gesture.
                if (Math.abs(h) > mTouchSlop
                        && (Math.abs(h) > Math.abs(x - mInitialTouchX)
                                || mInitialOffsetOnTouch == 0f)) {
                    mTouchSlopExceeded = true;
                    if (waitForTouchSlop && !mTracking) {
                        if (!mJustPeeked) {
                            mInitialOffsetOnTouch = mExpandedHeight;
                            mInitialTouchX = x;
                            mInitialTouchY = y;
                            h = 0;
                        }
                        if (mHeightAnimator != null) {
                            mHeightAnimator.cancel(); // end any outstanding animations
                        }
                        removeCallbacks(mPeekRunnable);
                        mPeekPending = false;
                        onTrackingStarted();
                    }
                }
                final float newHeight = Math.max(0, h + mInitialOffsetOnTouch);
                if (newHeight > mPeekHeight) {
                    if (mPeekAnimator != null) {
                        mPeekAnimator.cancel();
                    }
                    mJustPeeked = false;
                }
                if (-h >= mUnlockFalsingThreshold) {
                    mTouchAboveFalsingThreshold = true;
                }
                if (!mJustPeeked && (!waitForTouchSlop || mTracking) && !isTrackingBlocked()) {
                    setExpandedHeightInternal(newHeight);
                }

                trackMovement(event);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTrackingPointer = -1;
                trackMovement(event);
                if ((mTracking && mTouchSlopExceeded)
                        || Math.abs(x - mInitialTouchX) > mTouchSlop
                        || Math.abs(y - mInitialTouchY) > mTouchSlop
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    float vel = 0f;
                    float vectorVel = 0f;
                    if (mVelocityTracker != null) {
                        mVelocityTracker.computeCurrentVelocity(1000);
                        vel = mVelocityTracker.getYVelocity();
                        vectorVel = (float) Math.hypot(
                                mVelocityTracker.getXVelocity(), mVelocityTracker.getYVelocity());
                    }
                    boolean expand = flingExpands(vel, vectorVel);
                    onTrackingStopped(expand);
                    fling(vel, expand);
                    mUpdateFlingOnLayout = expand && mPanelClosedOnDown && !mHasLayoutedSinceDown;
                    if (mUpdateFlingOnLayout) {
                        mUpdateFlingVelocity = vel;
                    }
                } else {
                    boolean expands = onEmptySpaceClick(mInitialTouchX);
                    onTrackingStopped(expands);
                }

                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                mPeekTouching = false;
                break;
        }
        return !waitForTouchSlop || mTracking;
    }

    protected abstract boolean hasConflictingGestures();

    protected void onTrackingStopped(boolean expand) {
        mTracking = false;
        mBar.onTrackingStopped(PanelView.this, expand);
    }

    protected void onTrackingStarted() {
        mTracking = true;
        mCollapseAfterPeek = false;
        mBar.onTrackingStarted(PanelView.this);
        notifyExpandingStarted();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mInstantExpanding) {
            return false;
        }

        /*
         * If the user drags anywhere inside the panel we intercept it if he moves his finger
         * upwards. This allows closing the shade from anywhere inside the panel.
         *
         * We only do this if the current content is scrolled to the bottom,
         * i.e isScrolledToBottom() is true and therefore there is no conflicting scrolling gesture
         * possible.
         */
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float x = event.getX(pointerIndex);
        final float y = event.getY(pointerIndex);
        boolean scrolledToBottom = isScrolledToBottom();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mStatusBar.userActivity();
                if (mHeightAnimator != null && !mHintAnimationRunning ||
                        mPeekPending || mPeekAnimator != null) {
                    if (mHeightAnimator != null) {
                        mHeightAnimator.cancel(); // end any outstanding animations
                    }
                    cancelPeek();
                    mTouchSlopExceeded = true;
                    return true;
                }
                mInitialTouchY = y;
                mInitialTouchX = x;
                mTouchSlopExceeded = false;
                mJustPeeked = false;
                mPanelClosedOnDown = mExpandedHeight == 0.0f;
                mHasLayoutedSinceDown = false;
                mUpdateFlingOnLayout = false;
                mTouchAboveFalsingThreshold = false;
                initVelocityTracker();
                trackMovement(event);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialTouchX = event.getX(newIndex);
                    mInitialTouchY = event.getY(newIndex);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                final float h = y - mInitialTouchY;
                trackMovement(event);
                if (scrolledToBottom) {
                    if (h < -mTouchSlop && h < -Math.abs(x - mInitialTouchX)) {
                        if (mHeightAnimator != null) {
                            mHeightAnimator.cancel();
                        }
                        mInitialOffsetOnTouch = mExpandedHeight;
                        mInitialTouchY = y;
                        mInitialTouchX = x;
                        mTracking = true;
                        mTouchSlopExceeded = true;
                        onTrackingStarted();
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                break;
        }
        return false;
    }

    private void initVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
        }
        mVelocityTracker = VelocityTrackerFactory.obtain(getContext());
    }

    protected boolean isScrolledToBottom() {
        return true;
    }

    protected float getContentHeight() {
        return mExpandedHeight;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        loadDimens();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        loadDimens();
    }

    /**
     * @param vel the current vertical velocity of the motion
     * @param vectorVel the length of the vectorial velocity
     * @return whether a fling should expands the panel; contracts otherwise
     */
    protected boolean flingExpands(float vel, float vectorVel) {
        if (!mTouchAboveFalsingThreshold && mStatusBar.isFalsingThresholdNeeded()) {
            return true;
        }
        if (Math.abs(vectorVel) < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            return getExpandedFraction() > 0.5f;
        } else {
            return vel > 0;
        }
    }

    protected void fling(float vel, boolean expand) {
        cancelPeek();
        float target = expand ? getMaxPanelHeight() : 0.0f;

        // Hack to make the expand transition look nice when clear all button is visible - we make
        // the animation only to the last notification, and then jump to the maximum panel height so
        // clear all just fades in and the decelerating motion is towards the last notification.
        final boolean clearAllExpandHack = expand && fullyExpandedClearAllVisible()
                && mExpandedHeight < getMaxPanelHeight() - getClearAllHeight()
                && !isClearAllVisible();
        if (clearAllExpandHack) {
            target = getMaxPanelHeight() - getClearAllHeight();
        }
        if (target == mExpandedHeight || getOverExpansionAmount() > 0f && expand) {
            notifyExpandingFinished();
            return;
        }
        mOverExpandedBeforeFling = getOverExpansionAmount() > 0f;
        ValueAnimator animator = createHeightAnimator(target);
        if (expand) {
            mFlingAnimationUtils.apply(animator, mExpandedHeight, target, vel, getHeight());
        } else {
            mFlingAnimationUtils.applyDismissing(animator, mExpandedHeight, target, vel,
                    getHeight());

            // Make it shorter if we run a canned animation
            if (vel == 0) {
                animator.setDuration((long)
                        (animator.getDuration() * getCannedFlingDurationFactor()));
            }
        }
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (clearAllExpandHack && !mCancelled) {
                    setExpandedHeightInternal(getMaxPanelHeight());
                }
                mHeightAnimator = null;
                if (!mCancelled) {
                    notifyExpandingFinished();
                }
            }
        });
        mHeightAnimator = animator;
        animator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mViewName = getResources().getResourceName(getId());
    }

    public String getName() {
        return mViewName;
    }

    public void setExpandedHeight(float height) {
        if (DEBUG) logf("setExpandedHeight(%.1f)", height);
        setExpandedHeightInternal(height + getOverExpansionPixels());
    }

    @Override
    protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        requestPanelHeightUpdate();
        mHasLayoutedSinceDown = true;
        if (mUpdateFlingOnLayout) {
            abortAnimations();
            fling(mUpdateFlingVelocity, true);
            mUpdateFlingOnLayout = false;
        }
    }

    protected void requestPanelHeightUpdate() {
        float currentMaxPanelHeight = getMaxPanelHeight();

        // If the user isn't actively poking us, let's update the height
        if ((!mTracking || isTrackingBlocked())
                && mHeightAnimator == null
                && mExpandedHeight > 0
                && currentMaxPanelHeight != mExpandedHeight
                && !mPeekPending
                && mPeekAnimator == null
                && !mPeekTouching) {
            setExpandedHeight(currentMaxPanelHeight);
        }
    }

    public void setExpandedHeightInternal(float h) {
        float fhWithoutOverExpansion = getMaxPanelHeight() - getOverExpansionAmount();
        if (mHeightAnimator == null) {
            float overExpansionPixels = Math.max(0, h - fhWithoutOverExpansion);
            if (getOverExpansionPixels() != overExpansionPixels && mTracking) {
                setOverExpansion(overExpansionPixels, true /* isPixels */);
            }
            mExpandedHeight = Math.min(h, fhWithoutOverExpansion) + getOverExpansionAmount();
        } else {
            mExpandedHeight = h;
            if (mOverExpandedBeforeFling) {
                setOverExpansion(Math.max(0, h - fhWithoutOverExpansion), false /* isPixels */);
            }
        }

        mExpandedHeight = Math.max(0, mExpandedHeight);
        onHeightUpdated(mExpandedHeight);
        mExpandedFraction = Math.min(1f, fhWithoutOverExpansion == 0
                ? 0
                : mExpandedHeight / fhWithoutOverExpansion);
        notifyBarPanelExpansionChanged();
    }

    /**
     * @return true if the panel tracking should be temporarily blocked; this is used when a
     *         conflicting gesture (opening QS) is happening
     */
    protected abstract boolean isTrackingBlocked();

    protected abstract void setOverExpansion(float overExpansion, boolean isPixels);

    protected abstract void onHeightUpdated(float expandedHeight);

    protected abstract float getOverExpansionAmount();

    protected abstract float getOverExpansionPixels();

    /**
     * This returns the maximum height of the panel. Children should override this if their
     * desired height is not the full height.
     *
     * @return the default implementation simply returns the maximum height.
     */
    protected abstract int getMaxPanelHeight();

    public void setExpandedFraction(float frac) {
        setExpandedHeight(getMaxPanelHeight() * frac);
    }

    public float getExpandedHeight() {
        return mExpandedHeight;
    }

    public float getExpandedFraction() {
        return mExpandedFraction;
    }

    public boolean isFullyExpanded() {
        return mExpandedHeight >= getMaxPanelHeight();
    }

    public boolean isFullyCollapsed() {
        return mExpandedHeight <= 0;
    }

    public boolean isCollapsing() {
        return mClosing;
    }

    public boolean isTracking() {
        return mTracking;
    }

    public void setBar(PanelBar panelBar) {
        mBar = panelBar;
    }

    public void collapse(boolean delayed) {
        if (DEBUG) logf("collapse: " + this);
        if (mPeekPending || mPeekAnimator != null) {
            mCollapseAfterPeek = true;
            if (mPeekPending) {

                // We know that the whole gesture is just a peek triggered by a simple click, so
                // better start it now.
                removeCallbacks(mPeekRunnable);
                mPeekRunnable.run();
            }
        } else if (!isFullyCollapsed() && !mTracking) {
            if (mHeightAnimator != null) {
                mHeightAnimator.cancel();
            }
            mClosing = true;
            notifyExpandingStarted();
            if (delayed) {
                postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        fling(0, false /* expand */);
                    }
                }, 120);
            } else {
                fling(0, false /* expand */);
            }
        }
    }

    public void expand() {
        if (DEBUG) logf("expand: " + this);
        if (isFullyCollapsed()) {
            mBar.startOpeningPanel(this);
            notifyExpandingStarted();
            fling(0, true /* expand */);
        } else if (DEBUG) {
            if (DEBUG) logf("skipping expansion: is expanded");
        }
    }

    public void cancelPeek() {
        if (mPeekAnimator != null) {
            mPeekAnimator.cancel();
        }
        removeCallbacks(mPeekRunnable);
        mPeekPending = false;

        // When peeking, we already tell mBar that we expanded ourselves. Make sure that we also
        // notify mBar that we might have closed ourselves.
        notifyBarPanelExpansionChanged();
    }

    public void instantExpand() {
        mInstantExpanding = true;
        abortAnimations();
        if (mTracking) {
            onTrackingStopped(true /* expands */); // The panel is expanded after this call.
            notifyExpandingFinished();
        }
        setVisibility(VISIBLE);

        // Wait for window manager to pickup the change, so we know the maximum height of the panel
        // then.
        getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (mStatusBar.getStatusBarWindow().getHeight()
                                != mStatusBar.getStatusBarHeight()) {
                            getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            setExpandedFraction(1f);
                            mInstantExpanding = false;
                        }
                    }
                });

        // Make sure a layout really happens.
        requestLayout();
    }

    private void abortAnimations() {
        cancelPeek();
        if (mHeightAnimator != null) {
            mHeightAnimator.cancel();
        }
    }

    protected void startUnlockHintAnimation() {

        // We don't need to hint the user if an animation is already running or the user is changing
        // the expansion.
        if (mHeightAnimator != null || mTracking) {
            return;
        }
        cancelPeek();
        notifyExpandingStarted();
        startUnlockHintAnimationPhase1(new Runnable() {
            @Override
            public void run() {
                notifyExpandingFinished();
                mStatusBar.onHintFinished();
                mHintAnimationRunning = false;
            }
        });
        mStatusBar.onUnlockHintStarted();
        mHintAnimationRunning = true;
    }

    /**
     * Phase 1: Move everything upwards.
     */
    private void startUnlockHintAnimationPhase1(final Runnable onAnimationFinished) {
        float target = Math.max(0, getMaxPanelHeight() - mHintDistance);
        ValueAnimator animator = createHeightAnimator(target);
        animator.setDuration(250);
        animator.setInterpolator(mFastOutSlowInInterpolator);
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCancelled) {
                    mHeightAnimator = null;
                    onAnimationFinished.run();
                } else {
                    startUnlockHintAnimationPhase2(onAnimationFinished);
                }
            }
        });
        animator.start();
        mHeightAnimator = animator;
        mOriginalIndicationY = mKeyguardBottomArea.getIndicationView().getY();
        mKeyguardBottomArea.getIndicationView().animate()
                .y(mOriginalIndicationY - mHintDistance)
                .setDuration(250)
                .setInterpolator(mFastOutSlowInInterpolator)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        mKeyguardBottomArea.getIndicationView().animate()
                                .y(mOriginalIndicationY)
                                .setDuration(450)
                                .setInterpolator(mBounceInterpolator)
                                .start();
                    }
                })
                .start();
    }

    /**
     * Phase 2: Bounce down.
     */
    private void startUnlockHintAnimationPhase2(final Runnable onAnimationFinished) {
        ValueAnimator animator = createHeightAnimator(getMaxPanelHeight());
        animator.setDuration(450);
        animator.setInterpolator(mBounceInterpolator);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mHeightAnimator = null;
                onAnimationFinished.run();
            }
        });
        animator.start();
        mHeightAnimator = animator;
    }

    private ValueAnimator createHeightAnimator(float targetHeight) {
        ValueAnimator animator = ValueAnimator.ofFloat(mExpandedHeight, targetHeight);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setExpandedHeightInternal((Float) animation.getAnimatedValue());
            }
        });
        return animator;
    }

    private void notifyBarPanelExpansionChanged() {
        mBar.panelExpansionChanged(this, mExpandedFraction, mExpandedFraction > 0f || mPeekPending
                || mPeekAnimator != null);
    }

    /**
     * Gets called when the user performs a click anywhere in the empty area of the panel.
     *
     * @return whether the panel will be expanded after the action performed by this method
     */
    private boolean onEmptySpaceClick(float x) {
        if (mHintAnimationRunning) {
            return true;
        }
        if (x < mEdgeTapAreaWidth
                && mStatusBar.getBarState() == StatusBarState.KEYGUARD) {
            onEdgeClicked(false /* right */);
            return true;
        } else if (x > getWidth() - mEdgeTapAreaWidth
                && mStatusBar.getBarState() == StatusBarState.KEYGUARD) {
            onEdgeClicked(true /* right */);
            return true;
        } else {
            return onMiddleClicked();
        }
    }

    private final Runnable mPostCollapseRunnable = new Runnable() {
        @Override
        public void run() {
            collapse(false /* delayed */);
        }
    };
    private boolean onMiddleClicked() {
        switch (mStatusBar.getBarState()) {
            case StatusBarState.KEYGUARD:
                startUnlockHintAnimation();
                return true;
            case StatusBarState.SHADE_LOCKED:
                mStatusBar.goToKeyguard();
                return true;
            case StatusBarState.SHADE:

                // This gets called in the middle of the touch handling, where the state is still
                // that we are tracking the panel. Collapse the panel after this is done.
                post(mPostCollapseRunnable);
                return false;
            default:
                return true;
        }
    }

    protected abstract void onEdgeClicked(boolean right);

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(String.format("[PanelView(%s): expandedHeight=%f maxPanelHeight=%d closing=%s"
                + " tracking=%s justPeeked=%s peekAnim=%s%s timeAnim=%s%s"
                + "]",
                this.getClass().getSimpleName(),
                getExpandedHeight(),
                getMaxPanelHeight(),
                mClosing?"T":"f",
                mTracking?"T":"f",
                mJustPeeked?"T":"f",
                mPeekAnimator, ((mPeekAnimator!=null && mPeekAnimator.isStarted())?" (started)":""),
                mHeightAnimator, ((mHeightAnimator !=null && mHeightAnimator.isStarted())?" (started)":"")
        ));
    }

    public abstract void resetViews();

    protected abstract float getPeekHeight();

    protected abstract float getCannedFlingDurationFactor();

    /**
     * @return whether "Clear all" button will be visible when the panel is fully expanded
     */
    protected abstract boolean fullyExpandedClearAllVisible();

    protected abstract boolean isClearAllVisible();

    /**
     * @return the height of the clear all button, in pixels
     */
    protected abstract int getClearAllHeight();
}
