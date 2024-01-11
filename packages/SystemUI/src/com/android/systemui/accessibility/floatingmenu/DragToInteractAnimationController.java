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

package com.android.systemui.accessibility.floatingmenu;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.common.bubbles.DismissView;
import com.android.wm.shell.common.magnetictarget.MagnetizedObject;

/**
 * Controls the interaction between {@link MagnetizedObject} and
 * {@link MagnetizedObject.MagneticTarget}.
 */
class DragToInteractAnimationController {
    private static final boolean ENABLE_FLING_TO_DISMISS_MENU = false;
    private static final float COMPLETELY_OPAQUE = 1.0f;
    private static final float COMPLETELY_TRANSPARENT = 0.0f;
    private static final float CIRCLE_VIEW_DEFAULT_SCALE = 1.0f;
    private static final float ANIMATING_MAX_ALPHA = 0.7f;

    private final DismissView mDismissView;
    private final MenuView mMenuView;
    private final ValueAnimator mDismissAnimator;
    private final MagnetizedObject<?> mMagnetizedObject;
    private float mMinDismissSize;
    private float mSizePercent;

    DragToInteractAnimationController(DismissView dismissView, MenuView menuView) {
        mDismissView = dismissView;
        mDismissView.setPivotX(dismissView.getWidth() / 2.0f);
        mDismissView.setPivotY(dismissView.getHeight() / 2.0f);
        mMenuView = menuView;

        updateResources();

        mDismissAnimator = ValueAnimator.ofFloat(COMPLETELY_OPAQUE, COMPLETELY_TRANSPARENT);
        mDismissAnimator.addUpdateListener(dismissAnimation -> {
            final float animatedValue = (float) dismissAnimation.getAnimatedValue();
            final float scaleValue = Math.max(animatedValue, mSizePercent);
            dismissView.getCircle().setScaleX(scaleValue);
            dismissView.getCircle().setScaleY(scaleValue);

            menuView.setAlpha(Math.max(animatedValue, ANIMATING_MAX_ALPHA));
        });

        mDismissAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(@NonNull Animator animation, boolean isReverse) {
                super.onAnimationEnd(animation, isReverse);

                if (isReverse) {
                    mDismissView.getCircle().setScaleX(CIRCLE_VIEW_DEFAULT_SCALE);
                    mDismissView.getCircle().setScaleY(CIRCLE_VIEW_DEFAULT_SCALE);
                    mMenuView.setAlpha(COMPLETELY_OPAQUE);
                }
            }
        });

        mMagnetizedObject =
                new MagnetizedObject<MenuView>(mMenuView.getContext(), mMenuView,
                        new MenuAnimationController.MenuPositionProperty(
                                DynamicAnimation.TRANSLATION_X),
                        new MenuAnimationController.MenuPositionProperty(
                                DynamicAnimation.TRANSLATION_Y)) {
                    @Override
                    public void getLocationOnScreen(MenuView underlyingObject, int[] loc) {
                        underlyingObject.getLocationOnScreen(loc);
                    }

                    @Override
                    public float getHeight(MenuView underlyingObject) {
                        return underlyingObject.getHeight();
                    }

                    @Override
                    public float getWidth(MenuView underlyingObject) {
                        return underlyingObject.getWidth();
                    }
                };

        final MagnetizedObject.MagneticTarget magneticTarget = new MagnetizedObject.MagneticTarget(
                dismissView.getCircle(), (int) mMinDismissSize);
        mMagnetizedObject.addTarget(magneticTarget);
        mMagnetizedObject.setFlingToTargetEnabled(ENABLE_FLING_TO_DISMISS_MENU);
    }

    void showDismissView(boolean show) {
        if (show) {
            mDismissView.show();
        } else {
            mDismissView.hide();
        }
    }

    void setMagnetListener(MagnetizedObject.MagnetListener magnetListener) {
        mMagnetizedObject.setMagnetListener(magnetListener);
    }

    @VisibleForTesting
    MagnetizedObject.MagnetListener getMagnetListener() {
        return mMagnetizedObject.getMagnetListener();
    }

    void maybeConsumeDownMotionEvent(MotionEvent event) {
        mMagnetizedObject.maybeConsumeMotionEvent(event);
    }

    /**
     * This used to pass {@link MotionEvent#ACTION_DOWN} to the magnetized object to check if it was
     * within the magnetic field. It should be used in the {@link MenuListViewTouchHandler}.
     *
     * @param event that move the magnetized object which is also the menu list view.
     * @return true if the location of the motion events moves within the magnetic field of a
     * target, but false if didn't set
     * {@link DragToInteractAnimationController#setMagnetListener(MagnetizedObject.MagnetListener)}.
     */
    boolean maybeConsumeMoveMotionEvent(MotionEvent event) {
        return mMagnetizedObject.maybeConsumeMotionEvent(event);
    }

    /**
     * This used to pass {@link MotionEvent#ACTION_UP} to the magnetized object to check if it was
     * within the magnetic field. It should be used in the {@link MenuListViewTouchHandler}.
     *
     * @param event that move the magnetized object which is also the menu list view.
     * @return true if the location of the motion events moves within the magnetic field of a
     * target, but false if didn't set
     * {@link DragToInteractAnimationController#setMagnetListener(MagnetizedObject.MagnetListener)}.
     */
    boolean maybeConsumeUpMotionEvent(MotionEvent event) {
        return mMagnetizedObject.maybeConsumeMotionEvent(event);
    }

    void animateDismissMenu(boolean scaleUp) {
        if (scaleUp) {
            mDismissAnimator.start();
        } else {
            mDismissAnimator.reverse();
        }
    }

    void updateResources() {
        final float maxDismissSize = mDismissView.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.dismiss_circle_size);
        mMinDismissSize = mDismissView.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.dismiss_circle_small);
        mSizePercent = mMinDismissSize / maxDismissSize;
    }

    interface DismissCallback {
        void onDismiss();
    }
}
