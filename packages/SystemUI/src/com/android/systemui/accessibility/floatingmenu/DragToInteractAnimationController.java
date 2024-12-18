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

import static android.R.id.empty;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.util.ArrayMap;
import android.util.Pair;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Flags;
import com.android.wm.shell.shared.bubbles.DismissCircleView;
import com.android.wm.shell.shared.bubbles.DismissView;
import com.android.wm.shell.shared.magnetictarget.MagnetizedObject;

import java.util.Map;
import java.util.Objects;

/**
 * Controls the interaction between {@link MagnetizedObject} and
 * {@link MagnetizedObject.MagneticTarget}.
 */
class DragToInteractAnimationController {
    private static final float COMPLETELY_OPAQUE = 1.0f;
    private static final float COMPLETELY_TRANSPARENT = 0.0f;
    private static final float CIRCLE_VIEW_DEFAULT_SCALE = 1.0f;
    private static final float ANIMATING_MAX_ALPHA = 0.7f;

    private final DragToInteractView mInteractView;
    private final DismissView mDismissView;
    private final MenuView mMenuView;

    /**
     * MagnetizedObject cannot differentiate between its MagnetizedTargets,
     * so we need an object & an animator for every interactable.
     */
    private final ArrayMap<Integer, Pair<MagnetizedObject<MenuView>, ValueAnimator>> mInteractMap;

    private float mMinInteractSize;
    private float mSizePercent;

    DragToInteractAnimationController(DragToInteractView interactView, MenuView menuView) {
        mDismissView = null;
        mInteractView = interactView;
        mInteractView.setPivotX(interactView.getWidth() / 2.0f);
        mInteractView.setPivotY(interactView.getHeight() / 2.0f);
        mMenuView = menuView;

        updateResources();

        mInteractMap = new ArrayMap<>();
        interactView.getInteractMap().forEach((viewId, pair) -> {
            DismissCircleView circleView = pair.getFirst();
            createMagnetizedObjectAndAnimator(circleView);
        });
    }

    DragToInteractAnimationController(DismissView dismissView, MenuView menuView) {
        mDismissView = dismissView;
        mInteractView = null;
        mDismissView.setPivotX(dismissView.getWidth() / 2.0f);
        mDismissView.setPivotY(dismissView.getHeight() / 2.0f);
        mMenuView = menuView;

        updateResources();

        mInteractMap = new ArrayMap<>();
        createMagnetizedObjectAndAnimator(dismissView.getCircle());
    }

    void showInteractView(boolean show) {
        if (Flags.floatingMenuDragToEdit() && mInteractView != null) {
            if (show) {
                mInteractView.show();
            } else {
                mInteractView.hide();
            }
        } else if (mDismissView != null) {
            if (show) {
                mDismissView.show();
            } else {
                mDismissView.hide();
            }
        }
    }

    void setMagnetListener(MagnetizedObject.MagnetListener magnetListener) {
        mInteractMap.forEach((viewId, pair) -> {
            MagnetizedObject<?> magnetizedObject = pair.first;
            magnetizedObject.setMagnetListener(magnetListener);
        });
    }

    @VisibleForTesting
    MagnetizedObject.MagnetListener getMagnetListener(int id) {
        return Objects.requireNonNull(mInteractMap.get(id)).first.getMagnetListener();
    }

    void maybeConsumeDownMotionEvent(MotionEvent event) {
        mInteractMap.forEach((viewId, pair) -> {
            MagnetizedObject<?> magnetizedObject = pair.first;
            magnetizedObject.maybeConsumeMotionEvent(event);
        });
    }

    private int maybeConsumeMotionEvent(MotionEvent event) {
        for (Map.Entry<Integer, Pair<MagnetizedObject<MenuView>, ValueAnimator>> set:
                mInteractMap.entrySet()) {
            MagnetizedObject<MenuView> magnetizedObject = set.getValue().first;
            if (magnetizedObject.maybeConsumeMotionEvent(event)) {
                return set.getKey();
            }
        }
        return empty;
    }

    /**
     * This used to pass {@link MotionEvent#ACTION_DOWN} to the magnetized objects
     * to check if it was within a magnetic field.
     * It should be used in the {@link MenuListViewTouchHandler}.
     *
     * @param event that move the magnetized object which is also the menu list view.
     * @return id of a target if the location of the motion events moves
     * within the field of the target, otherwise it returns{@link android.R.id#empty}.
     * <p>
     * {@link DragToInteractAnimationController#setMagnetListener(MagnetizedObject.MagnetListener)}.
     */
    int maybeConsumeMoveMotionEvent(MotionEvent event) {
        return maybeConsumeMotionEvent(event);
    }

    /**
     * This used to pass {@link MotionEvent#ACTION_UP} to the magnetized object to check if it was
     * within the magnetic field. It should be used in the {@link MenuListViewTouchHandler}.
     *
     * @param event that move the magnetized object which is also the menu list view.
     * @return id of a target if the location of the motion events moves
     * within the field of the target, otherwise it returns{@link android.R.id#empty}.
     * {@link DragToInteractAnimationController#setMagnetListener(MagnetizedObject.MagnetListener)}.
     */
    int maybeConsumeUpMotionEvent(MotionEvent event) {
        return maybeConsumeMotionEvent(event);
    }

    void animateInteractMenu(int targetViewId, boolean scaleUp) {
        Pair<MagnetizedObject<MenuView>, ValueAnimator> value = mInteractMap.get(targetViewId);
        if (value == null) {
            return;
        }
        ValueAnimator animator = value.second;
        if (scaleUp) {
            animator.start();
        } else {
            animator.reverse();
        }
    }

    void updateResources() {
        final float maxInteractSize = mMenuView.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.dismiss_circle_size);
        mMinInteractSize = mMenuView.getResources().getDimensionPixelSize(
                com.android.wm.shell.R.dimen.dismiss_circle_small);
        mSizePercent = mMinInteractSize / maxInteractSize;
    }

    /**
     * Creates a magnetizedObject & valueAnimator pair for the provided circleView,
     * and adds them to the interactMap.
     *
     * @param circleView circleView to create objects for.
     */
    private void createMagnetizedObjectAndAnimator(DismissCircleView circleView) {
        MagnetizedObject<MenuView> magnetizedObject = new MagnetizedObject<MenuView>(
                mMenuView.getContext(), mMenuView,
                new MenuAnimationController.MenuPositionProperty(
                        DynamicAnimation.TRANSLATION_X),
                new MenuAnimationController.MenuPositionProperty(
                        DynamicAnimation.TRANSLATION_Y)) {
            @Override
            public void getLocationOnScreen(MenuView underlyingObject, @NonNull int[] loc) {
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
        // Avoid unintended selection of an object / option
        magnetizedObject.setFlingToTargetEnabled(false);
        magnetizedObject.addTarget(new MagnetizedObject.MagneticTarget(
                circleView, (int) mMinInteractSize));

        final ValueAnimator animator =
                ValueAnimator.ofFloat(COMPLETELY_OPAQUE, COMPLETELY_TRANSPARENT);

        animator.addUpdateListener(dismissAnimation -> {
            final float animatedValue = (float) dismissAnimation.getAnimatedValue();
            final float scaleValue = Math.max(animatedValue, mSizePercent);
            circleView.setScaleX(scaleValue);
            circleView.setScaleY(scaleValue);

            mMenuView.setAlpha(Math.max(animatedValue, ANIMATING_MAX_ALPHA));
        });

        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(@NonNull Animator animation, boolean isReverse) {
                super.onAnimationEnd(animation, isReverse);

                if (isReverse) {
                    circleView.setScaleX(CIRCLE_VIEW_DEFAULT_SCALE);
                    circleView.setScaleY(CIRCLE_VIEW_DEFAULT_SCALE);
                    mMenuView.setAlpha(COMPLETELY_OPAQUE);
                }
            }
        });

        mInteractMap.put(circleView.getId(), new Pair<>(magnetizedObject, animator));
    }
}
