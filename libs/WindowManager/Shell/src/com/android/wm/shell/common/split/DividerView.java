/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.common.split;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.WindowManager.LayoutParams.FLAG_SLIPPERY;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Property;
import android.view.GestureDetector;
import android.view.InsetsController;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.MotionEvent;
import android.view.SurfaceControlViewHost;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.wm.shell.R;
import com.android.wm.shell.animation.Interpolators;

/**
 * Divider for multi window splits.
 */
public class DividerView extends FrameLayout implements View.OnTouchListener {
    public static final long TOUCH_ANIMATION_DURATION = 150;
    public static final long TOUCH_RELEASE_ANIMATION_DURATION = 200;

    /** The task bar expanded height. Used to determine whether to insets divider bounds or not. */
    private float mExpandedTaskBarHeight;

    private final int mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

    private SplitLayout mSplitLayout;
    private SplitWindowManager mSplitWindowManager;
    private SurfaceControlViewHost mViewHost;
    private DividerHandleView mHandle;
    private View mBackground;
    private int mTouchElevation;

    private VelocityTracker mVelocityTracker;
    private boolean mMoving;
    private int mStartPos;
    private GestureDetector mDoubleTapDetector;
    private boolean mInteractive;
    private boolean mSetTouchRegion = true;

    /**
     * Tracks divider bar visible bounds in screen-based coordination. Used to calculate with
     * insets.
     */
    private final Rect mDividerBounds = new Rect();
    private final Rect mTempRect = new Rect();
    private FrameLayout mDividerBar;

    static final Property<DividerView, Integer> DIVIDER_HEIGHT_PROPERTY =
            new Property<DividerView, Integer>(Integer.class, "height") {
                @Override
                public Integer get(DividerView object) {
                    return object.mDividerBar.getLayoutParams().height;
                }

                @Override
                public void set(DividerView object, Integer value) {
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams)
                            object.mDividerBar.getLayoutParams();
                    lp.height = value;
                    object.mDividerBar.setLayoutParams(lp);
                }
            };

    private AnimatorListenerAdapter mAnimatorListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mSetTouchRegion = true;
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mSetTouchRegion = true;
        }
    };

    private final AccessibilityDelegate mHandleDelegate = new AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            final DividerSnapAlgorithm snapAlgorithm = mSplitLayout.mDividerSnapAlgorithm;
            if (isLandscape()) {
                info.addAction(new AccessibilityAction(R.id.action_move_tl_full,
                        mContext.getString(R.string.accessibility_action_divider_left_full)));
                if (snapAlgorithm.isFirstSplitTargetAvailable()) {
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_70,
                            mContext.getString(R.string.accessibility_action_divider_left_70)));
                }
                if (snapAlgorithm.showMiddleSplitTargetForAccessibility()) {
                    // Only show the middle target if there are more than 1 split target
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_50,
                            mContext.getString(R.string.accessibility_action_divider_left_50)));
                }
                if (snapAlgorithm.isLastSplitTargetAvailable()) {
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_30,
                            mContext.getString(R.string.accessibility_action_divider_left_30)));
                }
                info.addAction(new AccessibilityAction(R.id.action_move_rb_full,
                        mContext.getString(R.string.accessibility_action_divider_right_full)));
            } else {
                info.addAction(new AccessibilityAction(R.id.action_move_tl_full,
                        mContext.getString(R.string.accessibility_action_divider_top_full)));
                if (snapAlgorithm.isFirstSplitTargetAvailable()) {
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_70,
                            mContext.getString(R.string.accessibility_action_divider_top_70)));
                }
                if (snapAlgorithm.showMiddleSplitTargetForAccessibility()) {
                    // Only show the middle target if there are more than 1 split target
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_50,
                            mContext.getString(R.string.accessibility_action_divider_top_50)));
                }
                if (snapAlgorithm.isLastSplitTargetAvailable()) {
                    info.addAction(new AccessibilityAction(R.id.action_move_tl_30,
                            mContext.getString(R.string.accessibility_action_divider_top_30)));
                }
                info.addAction(new AccessibilityAction(R.id.action_move_rb_full,
                        mContext.getString(R.string.accessibility_action_divider_bottom_full)));
            }
        }

        @Override
        public boolean performAccessibilityAction(@NonNull View host, int action,
                @Nullable Bundle args) {
            DividerSnapAlgorithm.SnapTarget nextTarget = null;
            DividerSnapAlgorithm snapAlgorithm = mSplitLayout.mDividerSnapAlgorithm;
            if (action == R.id.action_move_tl_full) {
                nextTarget = snapAlgorithm.getDismissEndTarget();
            } else if (action == R.id.action_move_tl_70) {
                nextTarget = snapAlgorithm.getLastSplitTarget();
            } else if (action == R.id.action_move_tl_50) {
                nextTarget = snapAlgorithm.getMiddleTarget();
            } else if (action == R.id.action_move_tl_30) {
                nextTarget = snapAlgorithm.getFirstSplitTarget();
            } else if (action == R.id.action_move_rb_full) {
                nextTarget = snapAlgorithm.getDismissStartTarget();
            }
            if (nextTarget != null) {
                mSplitLayout.snapToTarget(mSplitLayout.getDividePosition(), nextTarget);
                return true;
            }
            return super.performAccessibilityAction(host, action, args);
        }
    };

    public DividerView(@NonNull Context context) {
        super(context);
    }

    public DividerView(@NonNull Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DividerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DividerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /** Sets up essential dependencies of the divider bar. */
    public void setup(
            SplitLayout layout,
            SplitWindowManager splitWindowManager,
            SurfaceControlViewHost viewHost,
            InsetsState insetsState) {
        mSplitLayout = layout;
        mSplitWindowManager = splitWindowManager;
        mViewHost = viewHost;
        mDividerBounds.set(layout.getDividerBounds());
        onInsetsChanged(insetsState, false /* animate */);
    }

    void onInsetsChanged(InsetsState insetsState, boolean animate) {
        mTempRect.set(mSplitLayout.getDividerBounds());
        final InsetsSource taskBarInsetsSource =
                insetsState.getSource(InsetsState.ITYPE_EXTRA_NAVIGATION_BAR);
        // Only insets the divider bar with task bar when it's expanded so that the rounded corners
        // will be drawn against task bar.
        if (taskBarInsetsSource.getFrame().height() >= mExpandedTaskBarHeight) {
            mTempRect.inset(taskBarInsetsSource.calculateVisibleInsets(mTempRect));
        }

        if (!mTempRect.equals(mDividerBounds)) {
            if (animate) {
                ObjectAnimator animator = ObjectAnimator.ofInt(this,
                        DIVIDER_HEIGHT_PROPERTY, mDividerBounds.height(), mTempRect.height());
                animator.setInterpolator(InsetsController.RESIZE_INTERPOLATOR);
                animator.setDuration(InsetsController.ANIMATION_DURATION_RESIZE);
                animator.addListener(mAnimatorListener);
                animator.start();
            } else {
                DIVIDER_HEIGHT_PROPERTY.set(this, mTempRect.height());
                mSetTouchRegion = true;
            }
            mDividerBounds.set(mTempRect);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mDividerBar = findViewById(R.id.divider_bar);
        mHandle = findViewById(R.id.docked_divider_handle);
        mBackground = findViewById(R.id.docked_divider_background);
        mExpandedTaskBarHeight = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.taskbar_frame_height);
        mTouchElevation = getResources().getDimensionPixelSize(
                R.dimen.docked_stack_divider_lift_elevation);
        mDoubleTapDetector = new GestureDetector(getContext(), new DoubleTapListener());
        mInteractive = true;
        setOnTouchListener(this);
        mHandle.setAccessibilityDelegate(mHandleDelegate);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mSetTouchRegion) {
            mTempRect.set(mHandle.getLeft(), mHandle.getTop(), mHandle.getRight(),
                    mHandle.getBottom());
            mSplitWindowManager.setTouchRegion(mTempRect);
            mSetTouchRegion = false;
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (mSplitLayout == null || !mInteractive) {
            return false;
        }

        if (mDoubleTapDetector.onTouchEvent(event)) {
            return true;
        }

        // Convert to use screen-based coordinates to prevent lost track of motion events while
        // moving divider bar and calculating dragging velocity.
        event.setLocation(event.getRawX(), event.getRawY());
        final int action = event.getAction() & MotionEvent.ACTION_MASK;
        final boolean isLandscape = isLandscape();
        final int touchPos = (int) (isLandscape ? event.getX() : event.getY());
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mVelocityTracker = VelocityTracker.obtain();
                mVelocityTracker.addMovement(event);
                setTouching();
                mStartPos = touchPos;
                mMoving = false;
                break;
            case MotionEvent.ACTION_MOVE:
                mVelocityTracker.addMovement(event);
                if (!mMoving && Math.abs(touchPos - mStartPos) > mTouchSlop) {
                    mStartPos = touchPos;
                    mMoving = true;
                }
                if (mMoving) {
                    final int position = mSplitLayout.getDividePosition() + touchPos - mStartPos;
                    mSplitLayout.updateDivideBounds(position);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                releaseTouching();
                if (!mMoving) break;

                mVelocityTracker.addMovement(event);
                mVelocityTracker.computeCurrentVelocity(1000 /* units */);
                final float velocity = isLandscape
                        ? mVelocityTracker.getXVelocity()
                        : mVelocityTracker.getYVelocity();
                final int position = mSplitLayout.getDividePosition() + touchPos - mStartPos;
                final DividerSnapAlgorithm.SnapTarget snapTarget =
                        mSplitLayout.findSnapTarget(position, velocity, false /* hardDismiss */);
                mSplitLayout.snapToTarget(position, snapTarget);
                mMoving = false;
                break;
        }

        return true;
    }

    private void setTouching() {
        setSlippery(false);
        mHandle.setTouching(true, true);
        // Lift handle as well so it doesn't get behind the background, even though it doesn't
        // cast shadow.
        mHandle.animate()
                .setInterpolator(Interpolators.TOUCH_RESPONSE)
                .setDuration(TOUCH_ANIMATION_DURATION)
                .translationZ(mTouchElevation)
                .start();
    }

    private void releaseTouching() {
        setSlippery(true);
        mHandle.setTouching(false, true);
        mHandle.animate()
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .setDuration(TOUCH_RELEASE_ANIMATION_DURATION)
                .translationZ(0)
                .start();
    }

    private void setSlippery(boolean slippery) {
        if (mViewHost == null) {
            return;
        }

        final WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        final boolean isSlippery = (lp.flags & FLAG_SLIPPERY) != 0;
        if (isSlippery == slippery) {
            return;
        }

        if (slippery) {
            lp.flags |= FLAG_SLIPPERY;
        } else {
            lp.flags &= ~FLAG_SLIPPERY;
        }
        mViewHost.relayout(lp);
    }

    void setInteractive(boolean interactive) {
        if (interactive == mInteractive) return;
        mInteractive = interactive;
        releaseTouching();
        mHandle.setVisibility(mInteractive ? View.VISIBLE : View.INVISIBLE);
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == ORIENTATION_LANDSCAPE;
    }

    private class DoubleTapListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (mSplitLayout != null) {
                mSplitLayout.onDoubleTappedDivider();
            }
            return true;
        }
    }
}
