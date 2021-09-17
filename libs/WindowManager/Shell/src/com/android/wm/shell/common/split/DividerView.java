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

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Property;
import android.view.GestureDetector;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.MotionEvent;
import android.view.SurfaceControlViewHost;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Interpolator;
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

    // TODO(b/191269755): use the value defined in InsetsController.
    private static final Interpolator RESIZE_INTERPOLATOR = Interpolators.LINEAR;

    // TODO(b/191269755): use the value defined in InsetsController.
    private static final int ANIMATION_DURATION_RESIZE = 300;

    /** The task bar expanded height. Used to determine whether to insets divider bounds or not. */
    private float mExpandedTaskBarHeight;

    private final int mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

    private SplitLayout mSplitLayout;
    private SurfaceControlViewHost mViewHost;
    private DividerHandleView mHandle;
    private View mBackground;
    private int mTouchElevation;

    private VelocityTracker mVelocityTracker;
    private boolean mMoving;
    private int mStartPos;
    private GestureDetector mDoubleTapDetector;
    private boolean mInteractive;

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
            SurfaceControlViewHost viewHost,
            InsetsState insetsState) {
        mSplitLayout = layout;
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
                animator.setInterpolator(RESIZE_INTERPOLATOR);
                animator.setDuration(ANIMATION_DURATION_RESIZE);
                animator.start();
            } else {
                DIVIDER_HEIGHT_PROPERTY.set(this, mTempRect.height());
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
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (mSplitLayout == null || !mInteractive) {
            return false;
        }

        if (mDoubleTapDetector.onTouchEvent(event)) {
            return true;
        }

        final int action = event.getAction() & MotionEvent.ACTION_MASK;
        final boolean isLandscape = isLandscape();
        // Using raw xy to prevent lost track of motion events while moving divider bar.
        final int touchPos = isLandscape ? (int) event.getRawX() : (int) event.getRawY();
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
        if (isLandscape()) {
            mBackground.animate().scaleX(1.4f);
        } else {
            mBackground.animate().scaleY(1.4f);
        }
        mBackground.animate()
                .setInterpolator(Interpolators.TOUCH_RESPONSE)
                .setDuration(TOUCH_ANIMATION_DURATION)
                .translationZ(mTouchElevation)
                .start();
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
        mBackground.animate()
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .setDuration(TOUCH_RELEASE_ANIMATION_DURATION)
                .translationZ(0)
                .scaleX(1f)
                .scaleY(1f)
                .start();
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
