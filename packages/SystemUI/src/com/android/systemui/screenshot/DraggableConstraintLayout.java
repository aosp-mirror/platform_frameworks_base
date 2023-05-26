/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static com.android.systemui.screenshot.LogConfig.DEBUG_ANIM;
import static com.android.systemui.screenshot.LogConfig.DEBUG_DISMISS;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.MathUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.systemui.R;

/**
 * ConstraintLayout that is draggable when touched in a specific region
 */
public class DraggableConstraintLayout extends ConstraintLayout
        implements ViewTreeObserver.OnComputeInternalInsetsListener {

    private static final float VELOCITY_DP_PER_MS = 1;
    private static final int MAXIMUM_DISMISS_DISTANCE_DP = 400;

    private final SwipeDismissHandler mSwipeDismissHandler;
    private final GestureDetector mSwipeDetector;
    private View mActionsContainer;
    private SwipeDismissCallbacks mCallbacks;
    private final DisplayMetrics mDisplayMetrics;

    /**
     * Stores the callbacks when the view is interacted with or dismissed.
     */
    public interface SwipeDismissCallbacks {
        /**
         * Run when the view is interacted with (touched)
         */
        default void onInteraction() {

        }

        /**
         * Run when the view is dismissed (the distance threshold is met), pre-dismissal animation
         */
        default void onSwipeDismissInitiated(Animator animator) {

        }

        /**
         * Run when the view is dismissed (the distance threshold is met), post-dismissal animation
         */
        default void onDismissComplete() {

        }
    }

    public DraggableConstraintLayout(Context context) {
        this(context, null);
    }

    public DraggableConstraintLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DraggableConstraintLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mDisplayMetrics = new DisplayMetrics();
        mContext.getDisplay().getRealMetrics(mDisplayMetrics);

        mSwipeDismissHandler = new SwipeDismissHandler(mContext, this);
        setOnTouchListener(mSwipeDismissHandler);

        mSwipeDetector = new GestureDetector(mContext,
                new GestureDetector.SimpleOnGestureListener() {
                    final Rect mActionsRect = new Rect();

                    @Override
                    public boolean onScroll(
                            MotionEvent ev1, MotionEvent ev2, float distanceX, float distanceY) {
                        mActionsContainer.getBoundsOnScreen(mActionsRect);
                        // return true if we aren't in the actions bar, or if we are but it isn't
                        // scrollable in the direction of movement
                        return !mActionsRect.contains((int) ev2.getRawX(), (int) ev2.getRawY())
                                || !mActionsContainer.canScrollHorizontally((int) distanceX);
                    }
                });
        mSwipeDetector.setIsLongpressEnabled(false);

        mCallbacks = new SwipeDismissCallbacks() {
        }; // default to unimplemented callbacks
    }

    public void setCallbacks(SwipeDismissCallbacks callbacks) {
        mCallbacks = callbacks;
    }

    @Override
    public boolean onInterceptHoverEvent(MotionEvent event) {
        mCallbacks.onInteraction();
        return super.onInterceptHoverEvent(event);
    }

    @Override // View
    protected void onFinishInflate() {
        mActionsContainer = findViewById(R.id.actions_container);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mSwipeDismissHandler.onTouch(this, ev);
        }
        return mSwipeDetector.onTouchEvent(ev);
    }

    /**
     * Cancel current dismissal animation, if any
     */
    public void cancelDismissal() {
        mSwipeDismissHandler.cancel();
    }

    /**
     * Return whether the view is currently dismissing
     */
    public boolean isDismissing() {
        return mSwipeDismissHandler.isDismissing();
    }

    /**
     * Dismiss the view, with animation controlled by SwipeDismissHandler
     */
    public void dismiss() {
        mSwipeDismissHandler.dismiss();
    }


    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
    }

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        // Only child views are touchable.
        Region r = new Region();
        Rect rect = new Rect();
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).getGlobalVisibleRect(rect);
            r.op(rect, Region.Op.UNION);
        }
        inoutInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        inoutInfo.touchableRegion.set(r);
    }

    private int getBackgroundRight() {
        // background expected to be null in testing.
        // animation may have unexpected behavior if view is not present
        View background = findViewById(R.id.actions_container_background);
        return background == null ? 0 : background.getRight();
    }

    /**
     * Allows a view to be swipe-dismissed, or returned to its location if distance threshold is not
     * met
     */
    private class SwipeDismissHandler implements OnTouchListener {
        private static final String TAG = "SwipeDismissHandler";

        // distance needed to register a dismissal
        private static final float DISMISS_DISTANCE_THRESHOLD_DP = 20;

        private final DraggableConstraintLayout mView;
        private final GestureDetector mGestureDetector;
        private final DisplayMetrics mDisplayMetrics;
        private ValueAnimator mDismissAnimation;

        private float mStartX;
        // Keeps track of the most recent direction (between the last two move events).
        // -1 for left; +1 for right.
        private int mDirectionX;
        private float mPreviousX;

        SwipeDismissHandler(Context context, DraggableConstraintLayout view) {
            mView = view;
            GestureDetector.OnGestureListener gestureListener = new SwipeDismissGestureListener();
            mGestureDetector = new GestureDetector(context, gestureListener);
            mDisplayMetrics = new DisplayMetrics();
            context.getDisplay().getRealMetrics(mDisplayMetrics);
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            boolean gestureResult = mGestureDetector.onTouchEvent(event);
            mCallbacks.onInteraction();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mStartX = event.getRawX();
                mPreviousX = mStartX;
                return true;
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (mDismissAnimation != null && mDismissAnimation.isRunning()) {
                    return true;
                }
                if (isPastDismissThreshold()) {
                    ValueAnimator anim = createSwipeDismissAnimation();
                    mCallbacks.onSwipeDismissInitiated(anim);
                    dismiss(anim);
                } else {
                    // if we've moved, but not past the threshold, start the return animation
                    if (DEBUG_DISMISS) {
                        Log.d(TAG, "swipe gesture abandoned");
                    }
                    createSwipeReturnAnimation().start();
                }
                return true;
            }
            return gestureResult;
        }

        class SwipeDismissGestureListener extends GestureDetector.SimpleOnGestureListener {
            @Override
            public boolean onScroll(
                    MotionEvent ev1, MotionEvent ev2, float distanceX, float distanceY) {
                mView.setTranslationX(ev2.getRawX() - mStartX);
                mDirectionX = (ev2.getRawX() < mPreviousX) ? -1 : 1;
                mPreviousX = ev2.getRawX();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                    float velocityY) {
                if (mView.getTranslationX() * velocityX > 0
                        && (mDismissAnimation == null || !mDismissAnimation.isRunning())) {
                    ValueAnimator dismissAnimator =
                            createSwipeDismissAnimation(velocityX / (float) 1000);
                    mCallbacks.onSwipeDismissInitiated(dismissAnimator);
                    dismiss(dismissAnimator);
                    return true;
                }
                return false;
            }
        }

        private boolean isPastDismissThreshold() {
            float translationX = mView.getTranslationX();
            // Determines whether the absolute translation from the start is in the same direction
            // as the current movement. For example, if the user moves most of the way to the right,
            // but then starts dragging back left, we do not dismiss even though the absolute
            // distance is greater than the threshold.
            if (translationX * mDirectionX > 0) {
                return Math.abs(translationX) >= FloatingWindowUtil.dpToPx(mDisplayMetrics,
                        DISMISS_DISTANCE_THRESHOLD_DP);
            }
            return false;
        }

        boolean isDismissing() {
            return (mDismissAnimation != null && mDismissAnimation.isRunning());
        }

        void cancel() {
            if (isDismissing()) {
                if (DEBUG_ANIM) {
                    Log.d(TAG, "cancelling dismiss animation");
                }
                mDismissAnimation.cancel();
            }
        }

        void dismiss() {
            dismiss(createSwipeDismissAnimation());
        }

        private void dismiss(ValueAnimator animator) {
            mDismissAnimation = animator;
            mDismissAnimation.addListener(new AnimatorListenerAdapter() {
                private boolean mCancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    super.onAnimationCancel(animation);
                    mCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    if (!mCancelled) {
                        mCallbacks.onDismissComplete();
                    }
                }
            });
            mDismissAnimation.start();
        }

        private ValueAnimator createSwipeDismissAnimation() {
            float velocityPxPerMs = FloatingWindowUtil.dpToPx(mDisplayMetrics, VELOCITY_DP_PER_MS);
            return createSwipeDismissAnimation(velocityPxPerMs);
        }

        private ValueAnimator createSwipeDismissAnimation(float velocity) {
            // velocity is measured in pixels per millisecond
            velocity = Math.min(3, Math.max(1, velocity));
            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
            float startX = mView.getTranslationX();
            // make sure the UI gets all the way off the screen in the direction of movement
            // (the actions container background is guaranteed to be both the leftmost and
            // rightmost UI element in LTR and RTL)
            float finalX;
            int layoutDir =
                    mView.getContext().getResources().getConfiguration().getLayoutDirection();
            if (startX > 0 || (startX == 0 && layoutDir == LAYOUT_DIRECTION_RTL)) {
                finalX = mDisplayMetrics.widthPixels;
            } else {
                finalX = -1 * getBackgroundRight();
            }
            float distance = Math.min(Math.abs(finalX - startX),
                    FloatingWindowUtil.dpToPx(mDisplayMetrics, MAXIMUM_DISMISS_DISTANCE_DP));
            // ensure that view dismisses in the right direction (right in LTR, left in RTL)
            float distanceVector = Math.copySign(distance, finalX - startX);

            anim.addUpdateListener(animation -> {
                float translation = MathUtils.lerp(
                        startX, startX + distanceVector, animation.getAnimatedFraction());
                mView.setTranslationX(translation);
                mView.setAlpha(1 - animation.getAnimatedFraction());
            });
            anim.setDuration((long) (Math.abs(distance / velocity)));
            return anim;
        }

        private ValueAnimator createSwipeReturnAnimation() {
            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
            float startX = mView.getTranslationX();
            float finalX = 0;

            anim.addUpdateListener(animation -> {
                float translation = MathUtils.lerp(
                        startX, finalX, animation.getAnimatedFraction());
                mView.setTranslationX(translation);
            });

            return anim;
        }
    }
}
