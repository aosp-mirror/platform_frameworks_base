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

import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FlingAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.RecyclerView;

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
    private static final float FLING_FRICTION_SCALAR = 1.9f;
    private static final float DEFAULT_FRICTION = 4.2f;
    private static final float SPRING_AFTER_FLING_DAMPING_RATIO = 0.85f;
    private static final float SPRING_STIFFNESS = 700f;
    private static final float ESCAPE_VELOCITY = 750f;

    private final MenuView mMenuView;

    // Cache the animations state of {@link DynamicAnimation.TRANSLATION_X} and {@link
    // DynamicAnimation.TRANSLATION_Y} to be well controlled by the touch handler
    private final HashMap<DynamicAnimation.ViewProperty, DynamicAnimation> mPositionAnimations =
            new HashMap<>();

    MenuAnimationController(MenuView menuView) {
        mMenuView = menuView;
    }

    void moveToPosition(PointF position) {
        moveToPositionX(position.x);
        moveToPositionY(position.y);
    }

    void moveToPositionX(float positionX) {
        DynamicAnimation.TRANSLATION_X.setValue(mMenuView, positionX);
    }

    private void moveToPositionY(float positionY) {
        DynamicAnimation.TRANSLATION_Y.setValue(mMenuView, positionY);
    }

    void moveToPositionYIfNeeded(float positionY) {
        // If the list view was out of screen bounds, it would allow users to nest scroll inside
        // and avoid conflicting with outer scroll.
        final RecyclerView listView = (RecyclerView) mMenuView.getChildAt(/* index= */ 0);
        if (listView.getOverScrollMode() == View.OVER_SCROLL_NEVER) {
            moveToPositionY(positionY);
        }
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
                new SpringForce()
                        .setStiffness(SPRING_STIFFNESS)
                        .setDampingRatio(SPRING_AFTER_FLING_DAMPING_RATIO),
                finalPositionX);

        flingThenSpringMenuWith(DynamicAnimation.TRANSLATION_Y,
                velocityY,
                FLING_FRICTION_SCALAR,
                new SpringForce()
                        .setStiffness(SPRING_STIFFNESS)
                        .setDampingRatio(SPRING_AFTER_FLING_DAMPING_RATIO),
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

        final FlingAnimation flingAnimation = new FlingAnimation(mMenuView, menuPositionProperty);
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
                    springMenuWith(property, spring, endVelocity, endPosition);
                });

        cancelAnimation(property);
        mPositionAnimations.put(property, flingAnimation);
        flingAnimation.start();
    }

    private void springMenuWith(DynamicAnimation.ViewProperty property, SpringForce spring,
            float velocity, float finalPosition) {
        final MenuPositionProperty menuPositionProperty = new MenuPositionProperty(property);
        final SpringAnimation springAnimation =
                new SpringAnimation(mMenuView, menuPositionProperty)
                        .setSpring(spring)
                        .addEndListener((animation, canceled, endValue, endVelocity) -> {
                            if (canceled || endValue != finalPosition) {
                                return;
                            }

                            onSpringAnimationEnd(new PointF(mMenuView.getTranslationX(),
                                    mMenuView.getTranslationY()));
                        })
                        .setStartVelocity(velocity);

        cancelAnimation(property);
        mPositionAnimations.put(property, springAnimation);
        springAnimation.animateToFinalPosition(finalPosition);
    }

    private boolean isOnLeftSide() {
        return mMenuView.getTranslationX() < mMenuView.getMenuDraggableBounds().centerX();
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

    void onDraggingStart() {
        mMenuView.onDraggingStart();
    }

    private void onSpringAnimationEnd(PointF position) {
        mMenuView.onBoundsInParentChanged((int) position.x, (int) position.y);

        final Rect draggableBounds = mMenuView.getMenuDraggableBounds();
        // Have the space gap margin between the top bound and the menu view, so actually the
        // position y range needs to cut the margin.
        position.offset(-draggableBounds.left, -draggableBounds.top);

        final float percentageX = position.x < draggableBounds.centerX()
                ? MIN_PERCENT : MAX_PERCENT;

        final float percentageY = position.y < 0 || draggableBounds.height() == 0
                ? MIN_PERCENT
                : Math.min(MAX_PERCENT, position.y / draggableBounds.height());
        mMenuView.persistPositionAndUpdateEdge(new Position(percentageX, percentageY));
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
}
