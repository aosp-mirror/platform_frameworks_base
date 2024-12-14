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

import static java.util.Objects.requireNonNull;

import android.animation.ValueAnimator;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.OvershootInterpolator;
import android.view.animation.TranslateAnimation;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FlingAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;

/**
 * Controls the interaction animations of the {@link MenuView}. Also, it will use the relative
 * coordinate based on the {@link MenuViewLayer} to compute the offset of the {@link MenuView}.
 */
class MenuAnimationController {
    private static final String TAG = "MenuAnimationController";
    private static final boolean DEBUG = false;
    private static final float MIN_PERCENT = 0.0f;
    private static final float MAX_PERCENT = 1.0f;
    private static final float COMPLETELY_OPAQUE = 1.0f;
    private static final float COMPLETELY_TRANSPARENT = 0.0f;
    private static final float SCALE_SHRINK = 0.0f;
    private static final float SCALE_GROW = 1.0f;
    private static final float FLING_FRICTION_SCALAR = 1.9f;
    private static final float DEFAULT_FRICTION = 4.2f;
    private static final float SPRING_AFTER_FLING_DAMPING_RATIO = 0.85f;
    private static final float SPRING_STIFFNESS = 700f;
    private static final float ESCAPE_VELOCITY = 750f;
    // Make tucked animation by using translation X relative to the view itself.
    private static final float ANIMATION_TO_X_VALUE = 0.5f;

    private static final int ANIMATION_START_OFFSET_MS = 600;
    private static final int ANIMATION_DURATION_MS = 600;
    private static final int FADE_OUT_DURATION_MS = 1000;
    private static final int FADE_EFFECT_DURATION_MS = 3000;

    private final MenuView mMenuView;
    private final MenuViewAppearance mMenuViewAppearance;
    private final ValueAnimator mFadeOutAnimator;
    private final Handler mHandler;
    private boolean mIsFadeEffectEnabled;
    private Runnable mSpringAnimationsEndAction;
    private PointF mAnimationEndPosition = new PointF();

    // Cache the animations state of {@link DynamicAnimation.TRANSLATION_X} and {@link
    // DynamicAnimation.TRANSLATION_Y} to be well controlled by the touch handler
    @VisibleForTesting
    final HashMap<DynamicAnimation.ViewProperty, DynamicAnimation> mPositionAnimations =
            new HashMap<>();

    @VisibleForTesting
    final RadiiAnimator mRadiiAnimator;

    MenuAnimationController(MenuView menuView, MenuViewAppearance menuViewAppearance) {
        mMenuView = menuView;
        mMenuViewAppearance = menuViewAppearance;

        mHandler = createUiHandler();
        mFadeOutAnimator = new ValueAnimator();
        mFadeOutAnimator.setDuration(FADE_OUT_DURATION_MS);
        mFadeOutAnimator.addUpdateListener(
                (animation) -> menuView.setAlpha((float) animation.getAnimatedValue()));
        mRadiiAnimator = new RadiiAnimator(mMenuViewAppearance.getMenuRadii(),
                new IRadiiAnimationListener() {
                    @Override
                    public void onRadiiAnimationUpdate(float[] radii) {
                        mMenuView.setRadii(radii);
                    }

                    @Override
                    public void onRadiiAnimationStart() {}

                    @Override
                    public void onRadiiAnimationStop() {}
                });
        mAnimationEndPosition = mMenuView.getMenuPosition();
    }

    void moveToPosition(PointF position) {
        moveToPosition(position, /* animateMovement = */ false);
        mAnimationEndPosition = position;
    }

    /* Moves position without updating underlying percentage position. Can be animated. */
    void moveToPosition(PointF position, boolean animateMovement) {
        moveToPositionX(position.x, animateMovement);
        moveToPositionY(position.y, animateMovement);
    }

    void moveToPositionX(float positionX) {
        moveToPositionX(positionX, /* animateMovement = */ false);
    }

    void moveToPositionX(float positionX, boolean animateMovement) {
        if (animateMovement) {
            springMenuWith(DynamicAnimation.TRANSLATION_X,
                    createSpringForce(),
                    /* velocity = */ 0,
                    positionX, /* writeToPosition = */ false);
        } else {
            DynamicAnimation.TRANSLATION_X.setValue(mMenuView, positionX);
        }
        mAnimationEndPosition.x = positionX;
    }

    void moveToPositionY(float positionY) {
        moveToPositionY(positionY, /* animateMovement = */ false);
    }

    void moveToPositionY(float positionY, boolean animateMovement) {
        if (animateMovement) {
            springMenuWith(DynamicAnimation.TRANSLATION_Y,
                    createSpringForce(),
                    /* velocity = */ 0,
                    positionY, /* writeToPosition = */ false);
        } else {
            DynamicAnimation.TRANSLATION_Y.setValue(mMenuView, positionY);
        }
        mAnimationEndPosition.y = positionY;
    }

    void moveToPositionYIfNeeded(float positionY) {
        // If the list view was out of screen bounds, it would allow users to nest scroll inside
        // and avoid conflicting with outer scroll.
        final RecyclerView listView = (RecyclerView) mMenuView.getChildAt(/* index= */ 0);
        if (listView.getOverScrollMode() == View.OVER_SCROLL_NEVER) {
            moveToPositionY(positionY);
        }
    }

    /**
     * Sets the action to be called when the all dynamic animations are completed.
     */
    void setSpringAnimationsEndAction(Runnable runnable) {
        mSpringAnimationsEndAction = runnable;
    }

    void moveToTopLeftPosition() {
        mMenuView.updateMenuMoveToTucked(/* isMoveToTucked= */ false);
        final Rect draggableBounds = mMenuView.getMenuDraggableBounds();
        moveAndPersistPosition(new PointF(draggableBounds.left, draggableBounds.top));
    }

    void moveToTopRightPosition() {
        mMenuView.updateMenuMoveToTucked(/* isMoveToTucked= */ false);
        final Rect draggableBounds = mMenuView.getMenuDraggableBounds();
        moveAndPersistPosition(new PointF(draggableBounds.right, draggableBounds.top));
    }

    void moveToBottomLeftPosition() {
        mMenuView.updateMenuMoveToTucked(/* isMoveToTucked= */ false);
        final Rect draggableBounds = mMenuView.getMenuDraggableBounds();
        moveAndPersistPosition(new PointF(draggableBounds.left, draggableBounds.bottom));
    }

    void moveToBottomRightPosition() {
        mMenuView.updateMenuMoveToTucked(/* isMoveToTucked= */ false);
        final Rect draggableBounds = mMenuView.getMenuDraggableBounds();
        moveAndPersistPosition(new PointF(draggableBounds.right, draggableBounds.bottom));
    }

    void moveAndPersistPosition(PointF position) {
        moveToPosition(position);
        mMenuView.onBoundsInParentChanged((int) position.x, (int) position.y);
        constrainPositionAndUpdate(position, /* writeToPosition = */ true);
    }

    void flingMenuThenSpringToEdge(float x, float velocityX, float velocityY) {
        final boolean shouldMenuFlingLeft = isOnLeftSide()
                ? velocityX < ESCAPE_VELOCITY
                : velocityX < -ESCAPE_VELOCITY;

        final Rect draggableBounds = mMenuView.getMenuDraggableBounds();
        final float finalPositionX = shouldMenuFlingLeft
                ? draggableBounds.left : draggableBounds.right;

        final float minimumVelocityToReachEdge =
                (finalPositionX - x) * (FLING_FRICTION_SCALAR * DEFAULT_FRICTION);

        final float startXVelocity = shouldMenuFlingLeft
                ? Math.min(minimumVelocityToReachEdge, velocityX)
                : Math.max(minimumVelocityToReachEdge, velocityX);

        flingThenSpringMenuWith(DynamicAnimation.TRANSLATION_X,
                startXVelocity,
                FLING_FRICTION_SCALAR,
                createSpringForce(),
                finalPositionX);

        flingThenSpringMenuWith(DynamicAnimation.TRANSLATION_Y,
                velocityY,
                FLING_FRICTION_SCALAR,
                createSpringForce(),
                /* finalPosition= */ null);
    }

    private void flingThenSpringMenuWith(DynamicAnimation.ViewProperty property, float velocity,
            float friction, SpringForce spring, Float finalPosition) {

        final MenuPositionProperty menuPositionProperty = new MenuPositionProperty(property);
        final float currentValue = menuPositionProperty.getValue(mMenuView);
        final Rect bounds = mMenuView.getMenuDraggableBounds();
        final float min =
                property.equals(DynamicAnimation.TRANSLATION_X)
                        ? bounds.left
                        : bounds.top;
        final float max =
                property.equals(DynamicAnimation.TRANSLATION_X)
                        ? bounds.right
                        : bounds.bottom;

        final FlingAnimation flingAnimation = createFlingAnimation(mMenuView, menuPositionProperty);
        flingAnimation.setFriction(friction)
                .setStartVelocity(velocity)
                .setMinValue(Math.min(currentValue, min))
                .setMaxValue(Math.max(currentValue, max))
                .addEndListener((animation, canceled, endValue, endVelocity) -> {
                    if (canceled) {
                        if (DEBUG) {
                            Log.d(TAG, "The fling animation was canceled.");
                        }

                        return;
                    }

                    final float endPosition = finalPosition != null
                            ? finalPosition
                            : Math.max(min, Math.min(max, endValue));
                    springMenuWith(property, spring, endVelocity, endPosition,
                            /* writeToPosition = */ true);
                });

        cancelAnimation(property);
        mPositionAnimations.put(property, flingAnimation);
        if (finalPosition != null) {
            setAnimationEndPosition(property, finalPosition);
        }
        flingAnimation.start();
    }

    @VisibleForTesting
    FlingAnimation createFlingAnimation(MenuView menuView,
            MenuPositionProperty menuPositionProperty) {
        return new FlingAnimation(menuView, menuPositionProperty);
    }

    @VisibleForTesting
    void springMenuWith(DynamicAnimation.ViewProperty property, SpringForce spring,
            float velocity, float finalPosition, boolean writeToPosition) {
        final MenuPositionProperty menuPositionProperty = new MenuPositionProperty(property);
        final SpringAnimation springAnimation =
                new SpringAnimation(mMenuView, menuPositionProperty)
                        .setSpring(spring)
                        .addEndListener((animation, canceled, endValue, endVelocity) -> {
                            if (canceled || endValue != finalPosition) {
                                return;
                            }

                            final boolean areAnimationsRunning =
                                    mPositionAnimations.values().stream().anyMatch(
                                            DynamicAnimation::isRunning);
                            if (!areAnimationsRunning) {
                                onSpringAnimationsEnd(new PointF(mMenuView.getTranslationX(),
                                        mMenuView.getTranslationY()), writeToPosition);
                            }
                        })
                        .setStartVelocity(velocity);

        cancelAnimation(property);
        mPositionAnimations.put(property, springAnimation);
        setAnimationEndPosition(property, finalPosition);
        springAnimation.animateToFinalPosition(finalPosition);
    }

    /**
     * Determines whether to hide the menu to the edge of the screen with the given current
     * translation x of the menu view. It should be used when receiving the action up touch event.
     *
     * @param currentXTranslation the current translation x of the menu view.
     * @return true if the menu would be hidden to the edge, otherwise false.
     */
    boolean maybeMoveToEdgeAndHide(float currentXTranslation) {
        final Rect draggableBounds = mMenuView.getMenuDraggableBounds();

        // If the translation x is zero, it should be at the left of the bound.
        if (currentXTranslation < draggableBounds.left
                || currentXTranslation > draggableBounds.right) {
            constrainPositionAndUpdate(
                    new PointF(mMenuView.getTranslationX(), mMenuView.getTranslationY()),
                    /* writeToPosition = */ true);
            mMenuView.onPositionChanged(true);
            moveToEdgeAndHide();
            return true;
        }
        return false;
    }

    boolean isOnLeftSide() {
        return mMenuView.getTranslationX() < mMenuView.getMenuDraggableBounds().centerX();
    }

    boolean isMoveToTucked() {
        return mMenuView.isMoveToTucked();
    }

    PointF getTuckedMenuPosition() {
        final PointF position = mMenuView.getMenuPosition();
        final float menuHalfWidth = mMenuView.getMenuWidth() / 2.0f;
        final float endX = isOnLeftSide()
                ? position.x - menuHalfWidth
                : position.x + menuHalfWidth;
        return new PointF(endX, position.y);
    }

    void moveToEdgeAndHide() {
        mMenuView.updateMenuMoveToTucked(/* isMoveToTucked= */ true);
        final PointF position = mMenuView.getMenuPosition();
        final PointF tuckedPosition = getTuckedMenuPosition();
        flingThenSpringMenuWith(DynamicAnimation.TRANSLATION_X,
                Math.signum(tuckedPosition.x - position.x) * ESCAPE_VELOCITY,
                FLING_FRICTION_SCALAR,
                createDefaultSpringForce(),
                tuckedPosition.x);

        // Keep the touch region let users could click extra space to pop up the menu view
        // from the screen edge
        mMenuView.onBoundsInParentChanged((int) position.x, (int) position.y);

        fadeOutIfEnabled();
    }

    void moveOutEdgeAndShow() {
        mMenuView.updateMenuMoveToTucked(/* isMoveToTucked= */ false);

        PointF position = mMenuView.getMenuPosition();
        springMenuWith(DynamicAnimation.TRANSLATION_X,
                createDefaultSpringForce(),
                0,
                position.x,
                true
        );
        springMenuWith(DynamicAnimation.TRANSLATION_Y,
                createDefaultSpringForce(),
                0,
                position.y,
                true
        );

        mMenuView.onEdgeChangedIfNeeded();
    }

    void cancelAnimations() {
        cancelAnimation(DynamicAnimation.TRANSLATION_X);
        cancelAnimation(DynamicAnimation.TRANSLATION_Y);
    }

    private void cancelAnimation(DynamicAnimation.ViewProperty property) {
        if (!mPositionAnimations.containsKey(property)) {
            return;
        }

        mPositionAnimations.get(property).cancel();
    }

    private void setAnimationEndPosition(
            DynamicAnimation.ViewProperty property, Float endPosition) {
        if (property.equals(DynamicAnimation.TRANSLATION_X)) {
            mAnimationEndPosition.x = endPosition;
        }
        if (property.equals(DynamicAnimation.TRANSLATION_Y)) {
            mAnimationEndPosition.y = endPosition;
        }
    }

    void skipAnimations() {
        cancelAnimations();
        moveToPosition(mAnimationEndPosition, false);
    }

    @VisibleForTesting
    DynamicAnimation getAnimation(DynamicAnimation.ViewProperty property) {
        return mPositionAnimations.getOrDefault(property, null);
    }

    void onDraggingStart() {
        mMenuView.onDraggingStart();
    }

    void startShrinkAnimation(Runnable endAction) {
        mMenuView.animate().cancel();

        mMenuView.animate()
                .scaleX(SCALE_SHRINK)
                .scaleY(SCALE_SHRINK)
                .alpha(COMPLETELY_TRANSPARENT)
                .translationY(mMenuView.getTranslationY())
                .withEndAction(endAction).start();
    }

    void startGrowAnimation() {
        mMenuView.animate().cancel();

        mMenuView.animate()
                .scaleX(SCALE_GROW)
                .scaleY(SCALE_GROW)
                .alpha(COMPLETELY_OPAQUE)
                .translationY(mMenuView.getTranslationY())
                .start();
    }

    void startRadiiAnimation(float[] endRadii) {
        mRadiiAnimator.startAnimation(endRadii);
    }

    private void onSpringAnimationsEnd(PointF position, boolean writeToPosition) {
        mMenuView.onBoundsInParentChanged((int) position.x, (int) position.y);
        constrainPositionAndUpdate(position, writeToPosition);

        if (mSpringAnimationsEndAction != null) {
            mSpringAnimationsEndAction.run();
        }
    }

    private void constrainPositionAndUpdate(PointF position, boolean writeToPosition) {
        final Rect draggableBounds = mMenuView.getMenuDraggableBoundsExcludeIme();
        // Have the space gap margin between the top bound and the menu view, so actually the
        // position y range needs to cut the margin.
        position.offset(-draggableBounds.left, -draggableBounds.top);

        final float percentageX = position.x < draggableBounds.centerX()
                ? MIN_PERCENT : MAX_PERCENT;

        final float percentageY = position.y < 0 || draggableBounds.height() == 0
                ? MIN_PERCENT
                : Math.min(MAX_PERCENT, position.y / draggableBounds.height());

        if (!writeToPosition) {
            mMenuView.onEdgeChangedIfNeeded();
        } else {
            mMenuView.persistPositionAndUpdateEdge(new Position(percentageX, percentageY));
        }
    }

    void updateOpacityWith(boolean isFadeEffectEnabled, float newOpacityValue) {
        mIsFadeEffectEnabled = isFadeEffectEnabled;

        mHandler.removeCallbacksAndMessages(/* token= */ null);
        mFadeOutAnimator.cancel();
        mFadeOutAnimator.setFloatValues(COMPLETELY_OPAQUE, newOpacityValue);
        mHandler.post(() -> mMenuView.setAlpha(
                mIsFadeEffectEnabled ? newOpacityValue : COMPLETELY_OPAQUE));
    }

    void fadeInNowIfEnabled() {
        if (!mIsFadeEffectEnabled) {
            return;
        }

        cancelAndRemoveCallbacksAndMessages();
        mMenuView.setAlpha(COMPLETELY_OPAQUE);
    }

    void fadeOutIfEnabled() {
        if (!mIsFadeEffectEnabled) {
            return;
        }

        cancelAndRemoveCallbacksAndMessages();
        mHandler.postDelayed(mFadeOutAnimator::start, FADE_EFFECT_DURATION_MS);
    }

    private void cancelAndRemoveCallbacksAndMessages() {
        mFadeOutAnimator.cancel();
        mHandler.removeCallbacksAndMessages(/* token= */ null);
    }

    void startTuckedAnimationPreview() {
        fadeInNowIfEnabled();

        final float toXValue = isOnLeftSide()
                ? -ANIMATION_TO_X_VALUE
                : ANIMATION_TO_X_VALUE;
        final TranslateAnimation animation =
                new TranslateAnimation(Animation.RELATIVE_TO_SELF, 0,
                        Animation.RELATIVE_TO_SELF, toXValue,
                        Animation.RELATIVE_TO_SELF, 0,
                        Animation.RELATIVE_TO_SELF, 0);
        animation.setDuration(ANIMATION_DURATION_MS);
        animation.setRepeatMode(Animation.REVERSE);
        animation.setInterpolator(new OvershootInterpolator());
        animation.setRepeatCount(Animation.INFINITE);
        animation.setStartOffset(ANIMATION_START_OFFSET_MS);

        mMenuView.startAnimation(animation);
    }

    private Handler createUiHandler() {
        return new Handler(requireNonNull(Looper.myLooper(), "looper must not be null"));
    }

    private static SpringForce createDefaultSpringForce() {
        return new SpringForce()
                .setStiffness(SPRING_STIFFNESS)
                .setDampingRatio(SPRING_AFTER_FLING_DAMPING_RATIO);
    }

    static class MenuPositionProperty
            extends FloatPropertyCompat<MenuView> {
        private final DynamicAnimation.ViewProperty mProperty;

        MenuPositionProperty(DynamicAnimation.ViewProperty property) {
            super(property.toString());
            mProperty = property;
        }

        @Override
        public float getValue(MenuView menuView) {
            return mProperty.getValue(menuView);
        }

        @Override
        public void setValue(MenuView menuView, float value) {
            mProperty.setValue(menuView, value);
        }
    }

    @VisibleForTesting
    static SpringForce createSpringForce() {
        return new SpringForce()
                .setStiffness(SPRING_STIFFNESS)
                .setDampingRatio(SPRING_AFTER_FLING_DAMPING_RATIO);
    }
}
