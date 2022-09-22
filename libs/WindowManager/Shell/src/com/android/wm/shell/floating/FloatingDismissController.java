/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.floating;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;

import com.android.wm.shell.R;
import com.android.wm.shell.bubbles.DismissView;
import com.android.wm.shell.common.magnetictarget.MagnetizedObject;
import com.android.wm.shell.floating.views.FloatingTaskLayer;
import com.android.wm.shell.floating.views.FloatingTaskView;

import java.util.Objects;

/**
 * Controls a floating dismiss circle that has a 'magnetic' field around it, causing views moved
 * close to the target to be stuck to it unless moved out again.
 */
public class FloatingDismissController {

    /** Velocity required to dismiss the view without dragging it into the dismiss target. */
    private static final float FLING_TO_DISMISS_MIN_VELOCITY = 4000f;
    /**
     * Max velocity that the view can be moving through the target with to stick (i.e. if it's
     * more than this velocity, it will pass through the target.
     */
    private static final float STICK_TO_TARGET_MAX_X_VELOCITY = 2000f;
    /**
     * Percentage of the target width to use to determine if an object flung towards the target
     * should dismiss (e.g. if target is 100px and this is set ot 2f, anything flung within a
     * 200px-wide area around the target will be considered 'near' enough get dismissed).
     */
    private static final float FLING_TO_TARGET_WIDTH_PERCENT = 2f;
    /** Minimum alpha to apply to the view being dismissed when it is in the target. */
    private static final float DISMISS_VIEW_MIN_ALPHA = 0.6f;
    /** Amount to scale down the view being dismissed when it is in the target. */
    private static final float DISMISS_VIEW_SCALE_DOWN_PERCENT = 0.15f;

    private Context mContext;
    private FloatingTasksController mController;
    private FloatingTaskLayer mParent;

    private DismissView mDismissView;
    private ValueAnimator mDismissAnimator;
    private View mViewBeingDismissed;
    private float mDismissSizePercent;
    private float mDismissSize;

    /**
     * The currently magnetized object, which is being dragged and will be attracted to the magnetic
     * dismiss target.
     */
    private MagnetizedObject<View> mMagnetizedObject;
    /**
     * The MagneticTarget instance for our circular dismiss view. This is added to the
     * MagnetizedObject instances for the view being dragged.
     */
    private MagnetizedObject.MagneticTarget mMagneticTarget;
    /** Magnet listener that handles animating and dismissing the view. */
    private MagnetizedObject.MagnetListener mFloatingViewMagnetListener;

    public FloatingDismissController(Context context, FloatingTasksController controller,
            FloatingTaskLayer parent) {
        mContext = context;
        mController = controller;
        mParent = parent;
        updateSizes();
        createAndAddDismissView();

        mDismissAnimator = ValueAnimator.ofFloat(1f, 0f);
        mDismissAnimator.addUpdateListener(animation -> {
            final float value = (float) animation.getAnimatedValue();
            if (mDismissView != null) {
                mDismissView.setPivotX((mDismissView.getRight() - mDismissView.getLeft()) / 2f);
                mDismissView.setPivotY((mDismissView.getBottom() - mDismissView.getTop()) / 2f);
                final float scaleValue = Math.max(value, mDismissSizePercent);
                mDismissView.getCircle().setScaleX(scaleValue);
                mDismissView.getCircle().setScaleY(scaleValue);
            }
            if (mViewBeingDismissed != null) {
                // TODO: alpha doesn't actually apply to taskView currently.
                mViewBeingDismissed.setAlpha(Math.max(value, DISMISS_VIEW_MIN_ALPHA));
                mViewBeingDismissed.setScaleX(Math.max(value, DISMISS_VIEW_SCALE_DOWN_PERCENT));
                mViewBeingDismissed.setScaleY(Math.max(value, DISMISS_VIEW_SCALE_DOWN_PERCENT));
            }
        });

        mFloatingViewMagnetListener = new MagnetizedObject.MagnetListener() {
            @Override
            public void onStuckToTarget(
                    @NonNull MagnetizedObject.MagneticTarget target) {
                animateDismissing(/* dismissing= */ true);
            }

            @Override
            public void onUnstuckFromTarget(@NonNull MagnetizedObject.MagneticTarget target,
                    float velX, float velY, boolean wasFlungOut) {
                animateDismissing(/* dismissing= */ false);
                mParent.onUnstuckFromTarget((FloatingTaskView) mViewBeingDismissed, velX, velY,
                        wasFlungOut);
            }

            @Override
            public void onReleasedInTarget(@NonNull MagnetizedObject.MagneticTarget target) {
                doDismiss();
            }
        };
    }

    /** Updates all the sizes used and applies them to the {@link DismissView}. */
    public void updateSizes() {
        Resources res = mContext.getResources();
        mDismissSize = res.getDimensionPixelSize(
                R.dimen.floating_task_dismiss_circle_size);
        final float minDismissSize = res.getDimensionPixelSize(
                R.dimen.floating_dismiss_circle_small);
        mDismissSizePercent = minDismissSize / mDismissSize;

        if (mDismissView != null) {
            mDismissView.updateResources();
        }
    }

    /** Prepares the view being dragged to be magnetic. */
    public void setUpMagneticObject(View viewBeingDragged) {
        mViewBeingDismissed = viewBeingDragged;
        mMagnetizedObject = getMagnetizedView(viewBeingDragged);
        mMagnetizedObject.clearAllTargets();
        mMagnetizedObject.addTarget(mMagneticTarget);
        mMagnetizedObject.setMagnetListener(mFloatingViewMagnetListener);
    }

    /** Shows or hides the dismiss target. */
    public void showDismiss(boolean show) {
        if (show) {
            mDismissView.show();
        } else {
            mDismissView.hide();
        }
    }

    /** Passes the MotionEvent to the magnetized object and returns true if it was consumed. */
    public boolean passEventToMagnetizedObject(MotionEvent event) {
        return mMagnetizedObject != null && mMagnetizedObject.maybeConsumeMotionEvent(event);
    }

    private void createAndAddDismissView() {
        if (mDismissView != null) {
            mParent.removeView(mDismissView);
        }
        mDismissView = new DismissView(mContext);
        mDismissView.setTargetSizeResId(R.dimen.floating_task_dismiss_circle_size);
        mDismissView.updateResources();
        mParent.addView(mDismissView);

        final float dismissRadius = mDismissSize;
        // Save the MagneticTarget instance for the newly set up view - we'll add this to the
        // MagnetizedObjects when the dismiss view gets shown.
        mMagneticTarget = new MagnetizedObject.MagneticTarget(
                mDismissView.getCircle(), (int) dismissRadius);
    }

    private MagnetizedObject<View> getMagnetizedView(View v) {
        if (mMagnetizedObject != null
                && Objects.equals(mMagnetizedObject.getUnderlyingObject(), v)) {
            // Same view being dragged, we can reuse the magnetic object.
            return mMagnetizedObject;
        }
        MagnetizedObject<View> magnetizedView = new MagnetizedObject<View>(
                mContext,
                v,
                DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y
        ) {
            @Override
            public float getWidth(@NonNull View underlyingObject) {
                return underlyingObject.getWidth();
            }

            @Override
            public float getHeight(@NonNull View underlyingObject) {
                return underlyingObject.getHeight();
            }

            @Override
            public void getLocationOnScreen(@NonNull View underlyingObject,
                    @NonNull int[] loc) {
                loc[0] = (int) underlyingObject.getTranslationX();
                loc[1] = (int) underlyingObject.getTranslationY();
            }
        };
        magnetizedView.setHapticsEnabled(true);
        magnetizedView.setFlingToTargetMinVelocity(FLING_TO_DISMISS_MIN_VELOCITY);
        magnetizedView.setStickToTargetMaxXVelocity(STICK_TO_TARGET_MAX_X_VELOCITY);
        magnetizedView.setFlingToTargetWidthPercent(FLING_TO_TARGET_WIDTH_PERCENT);
        return magnetizedView;
    }

    /** Animates the dismiss treatment on the view being dismissed. */
    private void animateDismissing(boolean shouldDismiss) {
        if (mViewBeingDismissed == null) {
            return;
        }
        if (shouldDismiss) {
            mDismissAnimator.removeAllListeners();
            mDismissAnimator.start();
        } else {
            mDismissAnimator.removeAllListeners();
            mDismissAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    resetDismissAnimator();
                }
            });
            mDismissAnimator.reverse();
        }
    }

    /** Actually dismisses the view. */
    private void doDismiss() {
        mDismissView.hide();
        mController.removeTask();
        resetDismissAnimator();
        mViewBeingDismissed = null;
    }

    private void resetDismissAnimator() {
        mDismissAnimator.removeAllListeners();
        mDismissAnimator.cancel();
        if (mDismissView != null) {
            mDismissView.cancelAnimators();
            mDismissView.getCircle().setScaleX(1f);
            mDismissView.getCircle().setScaleY(1f);
        }
    }
}
