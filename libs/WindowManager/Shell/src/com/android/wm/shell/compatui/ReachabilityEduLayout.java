/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.compatui;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.app.TaskInfo;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;

import com.android.wm.shell.R;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Container for reachability education which handles all the show/hide animations.
 */
public class ReachabilityEduLayout extends FrameLayout {

    private static final float ALPHA_FULL_TRANSPARENT = 0f;

    private static final float ALPHA_FULL_OPAQUE = 1f;

    private static final long VISIBILITY_SHOW_ANIMATION_DURATION_MS = 167;

    private static final long VISIBILITY_SHOW_ANIMATION_DELAY_MS = 250;

    private static final long VISIBILITY_SHOW_DOUBLE_TAP_ANIMATION_DELAY_MS = 80;

    private static final long MARGINS_ANIMATION_DURATION_MS = 250;

    private ReachabilityEduWindowManager mWindowManager;

    private ReachabilityEduHandLayout mMoveLeftButton;
    private ReachabilityEduHandLayout mMoveRightButton;
    private ReachabilityEduHandLayout mMoveUpButton;
    private ReachabilityEduHandLayout mMoveDownButton;

    private int mLastLeftMargin = TaskInfo.PROPERTY_VALUE_UNSET;
    private int mLastRightMargin = TaskInfo.PROPERTY_VALUE_UNSET;
    private int mLastTopMargin = TaskInfo.PROPERTY_VALUE_UNSET;
    private int mLastBottomMargin = TaskInfo.PROPERTY_VALUE_UNSET;

    private boolean mIsLayoutActive;

    public ReachabilityEduLayout(Context context) {
        this(context, null);
    }

    public ReachabilityEduLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ReachabilityEduLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ReachabilityEduLayout(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    void inject(ReachabilityEduWindowManager windowManager) {
        mWindowManager = windowManager;
    }

    void handleVisibility(boolean isActivityLetterboxed, int letterboxVerticalPosition,
            int letterboxHorizontalPosition, int availableWidth, int availableHeight,
            boolean isDoubleTap) {
        // If the app is not letterboxed we hide all the buttons.
        if (!mIsLayoutActive || !isActivityLetterboxed || (
                letterboxHorizontalPosition == TaskInfo.PROPERTY_VALUE_UNSET
                        && letterboxVerticalPosition == TaskInfo.PROPERTY_VALUE_UNSET)) {
            hideAllImmediately();
        } else if (letterboxHorizontalPosition != TaskInfo.PROPERTY_VALUE_UNSET) {
            handleLetterboxHorizontalPosition(availableWidth, letterboxHorizontalPosition,
                    isDoubleTap);
        } else {
            handleLetterboxVerticalPosition(availableHeight, letterboxVerticalPosition,
                    isDoubleTap);
        }
    }

    void hideAllImmediately() {
        mMoveLeftButton.hide();
        mMoveRightButton.hide();
        mMoveUpButton.hide();
        mMoveDownButton.hide();
        mLastLeftMargin = TaskInfo.PROPERTY_VALUE_UNSET;
        mLastRightMargin = TaskInfo.PROPERTY_VALUE_UNSET;
        mLastTopMargin = TaskInfo.PROPERTY_VALUE_UNSET;
        mLastBottomMargin = TaskInfo.PROPERTY_VALUE_UNSET;
    }

    void setIsLayoutActive(boolean isLayoutActive) {
        this.mIsLayoutActive = isLayoutActive;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mMoveLeftButton = findViewById(R.id.reachability_move_left_button);
        mMoveRightButton = findViewById(R.id.reachability_move_right_button);
        mMoveUpButton = findViewById(R.id.reachability_move_up_button);
        mMoveDownButton = findViewById(R.id.reachability_move_down_button);
        mMoveLeftButton.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        mMoveRightButton.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        mMoveUpButton.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        mMoveDownButton.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
    }

    private Animator marginAnimator(View view, Function<LayoutParams, Integer> marginSupplier,
            BiConsumer<LayoutParams, Integer> marginConsumer, int from, int to) {
        final LayoutParams layoutParams = ((LayoutParams) view.getLayoutParams());
        ValueAnimator animator = ValueAnimator.ofInt(marginSupplier.apply(layoutParams), from, to);
        animator.addUpdateListener(valueAnimator -> {
            marginConsumer.accept(layoutParams, (Integer) valueAnimator.getAnimatedValue());
            view.requestLayout();
        });
        animator.setDuration(MARGINS_ANIMATION_DURATION_MS);
        return animator;
    }

    private void handleLetterboxHorizontalPosition(int availableWidth,
            int letterboxHorizontalPosition, boolean isDoubleTap) {
        mMoveUpButton.hide();
        mMoveDownButton.hide();
        mLastTopMargin = TaskInfo.PROPERTY_VALUE_UNSET;
        mLastBottomMargin = TaskInfo.PROPERTY_VALUE_UNSET;
        // We calculate the available space on the left and right
        final int horizontalGap = availableWidth / 2;
        final int leftAvailableSpace = letterboxHorizontalPosition * horizontalGap;
        final int rightAvailableSpace = availableWidth - leftAvailableSpace;
        // We show the button if we have enough space
        if (leftAvailableSpace >= mMoveLeftButton.getMeasuredWidth()) {
            int newLeftMargin = (horizontalGap - mMoveLeftButton.getMeasuredWidth()) / 2;
            if (mLastLeftMargin == TaskInfo.PROPERTY_VALUE_UNSET) {
                mLastLeftMargin = newLeftMargin;
            }
            if (mLastLeftMargin != newLeftMargin) {
                marginAnimator(mMoveLeftButton, layoutParams -> layoutParams.leftMargin,
                        (layoutParams, margin) -> layoutParams.leftMargin = margin,
                        mLastLeftMargin, newLeftMargin).start();
            } else {
                final LayoutParams leftParams = ((LayoutParams) mMoveLeftButton.getLayoutParams());
                leftParams.leftMargin = mLastLeftMargin;
                mMoveLeftButton.setLayoutParams(leftParams);
            }
            showItem(mMoveLeftButton, isDoubleTap);
        } else {
            mMoveLeftButton.hide();
            mLastLeftMargin = TaskInfo.PROPERTY_VALUE_UNSET;
        }
        if (rightAvailableSpace >= mMoveRightButton.getMeasuredWidth()) {
            int newRightMargin = (horizontalGap - mMoveRightButton.getMeasuredWidth()) / 2;
            if (mLastRightMargin == TaskInfo.PROPERTY_VALUE_UNSET) {
                mLastRightMargin = newRightMargin;
            }
            if (mLastRightMargin != newRightMargin) {
                marginAnimator(mMoveRightButton, layoutParams -> layoutParams.rightMargin,
                        (layoutParams, margin) -> layoutParams.rightMargin = margin,
                        mLastRightMargin, newRightMargin).start();
            } else {
                final LayoutParams rightParams =
                        ((LayoutParams) mMoveRightButton.getLayoutParams());
                rightParams.rightMargin = mLastRightMargin;
                mMoveRightButton.setLayoutParams(rightParams);
            }
            showItem(mMoveRightButton, isDoubleTap);
        } else {
            mMoveRightButton.hide();
            mLastRightMargin = TaskInfo.PROPERTY_VALUE_UNSET;
        }
    }

    private void handleLetterboxVerticalPosition(int availableHeight,
            int letterboxVerticalPosition, boolean isDoubleTap) {
        mMoveLeftButton.hide();
        mMoveRightButton.hide();
        mLastLeftMargin = TaskInfo.PROPERTY_VALUE_UNSET;
        mLastRightMargin = TaskInfo.PROPERTY_VALUE_UNSET;
        // We calculate the available space on the left and right
        final int verticalGap = availableHeight / 2;
        final int topAvailableSpace = letterboxVerticalPosition * verticalGap;
        final int bottomAvailableSpace = availableHeight - topAvailableSpace;
        if (topAvailableSpace >= mMoveUpButton.getMeasuredHeight()) {
            int newTopMargin = (verticalGap - mMoveUpButton.getMeasuredHeight()) / 2;
            if (mLastTopMargin == TaskInfo.PROPERTY_VALUE_UNSET) {
                mLastTopMargin = newTopMargin;
            }
            if (mLastTopMargin != newTopMargin) {
                marginAnimator(mMoveUpButton, layoutParams -> layoutParams.topMargin,
                        (layoutParams, margin) -> layoutParams.topMargin = margin,
                        mLastTopMargin, newTopMargin).start();
            } else {
                final LayoutParams topParams = ((LayoutParams) mMoveUpButton.getLayoutParams());
                topParams.topMargin = mLastTopMargin;
                mMoveUpButton.setLayoutParams(topParams);
            }
            showItem(mMoveUpButton, isDoubleTap);
        } else {
            mMoveUpButton.hide();
            mLastTopMargin = TaskInfo.PROPERTY_VALUE_UNSET;
        }
        if (bottomAvailableSpace >= mMoveDownButton.getMeasuredHeight()) {
            int newBottomMargin = (verticalGap - mMoveDownButton.getMeasuredHeight()) / 2;
            if (mLastBottomMargin == TaskInfo.PROPERTY_VALUE_UNSET) {
                mLastBottomMargin = newBottomMargin;
            }
            if (mLastBottomMargin != newBottomMargin) {
                marginAnimator(mMoveDownButton, layoutParams -> layoutParams.bottomMargin,
                        (layoutParams, margin) -> layoutParams.bottomMargin = margin,
                        mLastBottomMargin, newBottomMargin).start();
            } else {
                final LayoutParams bottomParams =
                        ((LayoutParams) mMoveDownButton.getLayoutParams());
                bottomParams.bottomMargin = mLastBottomMargin;
                mMoveDownButton.setLayoutParams(bottomParams);
            }
            showItem(mMoveDownButton, isDoubleTap);
        } else {
            mMoveDownButton.hide();
            mLastBottomMargin = TaskInfo.PROPERTY_VALUE_UNSET;
        }
    }

    private void showItem(ReachabilityEduHandLayout view, boolean fromDoubleTap) {
        if (view.getVisibility() == View.VISIBLE) {
            // Already visible we just start animation
            view.startAnimation();
            return;
        }
        view.setVisibility(View.VISIBLE);
        final long delay = fromDoubleTap ? VISIBILITY_SHOW_DOUBLE_TAP_ANIMATION_DELAY_MS
                : VISIBILITY_SHOW_ANIMATION_DELAY_MS;
        AlphaAnimation alphaAnimation = new AlphaAnimation(ALPHA_FULL_TRANSPARENT,
                ALPHA_FULL_OPAQUE);
        alphaAnimation.setDuration(VISIBILITY_SHOW_ANIMATION_DURATION_MS);
        alphaAnimation.setStartOffset(delay);
        alphaAnimation.setFillAfter(true);
        alphaAnimation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                // We trigger the hand animation
                view.setAlpha(ALPHA_FULL_OPAQUE);
                view.startAnimation();
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
        view.startAnimation(alphaAnimation);
    }
}
