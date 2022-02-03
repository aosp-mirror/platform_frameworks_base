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
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.MathUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * Allows a view to be swipe-dismissed, or returned to its location if distance threshold is not met
 */
public class SwipeDismissHandler implements View.OnTouchListener {
    private static final String TAG = "SwipeDismissHandler";

    // distance needed to register a dismissal
    private static final float DISMISS_DISTANCE_THRESHOLD_DP = 20;

    /**
     * Stores the callbacks when the view is interacted with or dismissed.
     */
    public interface SwipeDismissCallbacks {
        /**
         * Run when the view is interacted with (touched)
         */
        void onInteraction();

        /**
         * Run when the view is dismissed (the distance threshold is met), post-dismissal animation
         */
        void onDismiss();
    }

    private final View mView;
    private final SwipeDismissCallbacks mCallbacks;
    private final GestureDetector mGestureDetector;
    private DisplayMetrics mDisplayMetrics;
    private ValueAnimator mDismissAnimation;


    private float mStartX;
    // Keeps track of the most recent direction (between the last two move events).
    // -1 for left; +1 for right.
    private int mDirectionX;
    private float mPreviousX;

    public SwipeDismissHandler(Context context, View view, SwipeDismissCallbacks callbacks) {
        mView = view;
        mCallbacks = callbacks;
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
                dismiss();
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
                dismiss(velocityX / (float) 1000);
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

    /**
     * Return whether the view is currently being dismissed
     */
    public boolean isDismissing() {
        return (mDismissAnimation != null && mDismissAnimation.isRunning());
    }

    /**
     * Cancel the currently-running dismissal animation, if any.
     */
    public void cancel() {
        if (isDismissing()) {
            if (DEBUG_ANIM) {
                Log.d(TAG, "cancelling dismiss animation");
            }
            mDismissAnimation.cancel();
        }
    }

    /**
     * Start dismissal animation (will run onDismiss callback when animation complete)
     */
    public void dismiss() {
        dismiss(1);
    }

    private void dismiss(float velocity) {
        mDismissAnimation = createSwipeDismissAnimation(velocity);
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
                    mCallbacks.onDismiss();
                }
            }
        });
        mDismissAnimation.start();
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
        int layoutDir = mView.getContext().getResources().getConfiguration().getLayoutDirection();
        if (startX > 0 || (startX == 0 && layoutDir == View.LAYOUT_DIRECTION_RTL)) {
            finalX = mDisplayMetrics.widthPixels;
        } else {
            finalX = -1 * mView.getRight();
        }
        float distance = Math.abs(finalX - startX);

        anim.addUpdateListener(animation -> {
            float translation = MathUtils.lerp(startX, finalX, animation.getAnimatedFraction());
            mView.setTranslationX(translation);
            mView.setAlpha(1 - animation.getAnimatedFraction());
        });
        anim.setDuration((long) (distance / Math.abs(velocity)));
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
