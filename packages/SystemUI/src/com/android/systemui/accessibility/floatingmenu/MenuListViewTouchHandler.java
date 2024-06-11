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

import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Optional;

/**
 * Controls the all touch events of the accessibility target features view{@link RecyclerView} in
 * the {@link MenuView}. And then compute the gestures' velocity for fling and spring
 * animations.
 */
class MenuListViewTouchHandler implements RecyclerView.OnItemTouchListener {
    private static final int VELOCITY_UNIT_SECONDS = 1000;
    private final VelocityTracker mVelocityTracker = VelocityTracker.obtain();
    private final MenuAnimationController mMenuAnimationController;
    private final PointF mDown = new PointF();
    private final PointF mMenuTranslationDown = new PointF();
    private boolean mIsDragging = false;
    private float mTouchSlop;
    private final DragToInteractAnimationController mDragToInteractAnimationController;
    private Optional<Runnable> mOnActionDownEnd = Optional.empty();

    MenuListViewTouchHandler(MenuAnimationController menuAnimationController,
            DragToInteractAnimationController dragToInteractAnimationController) {
        mMenuAnimationController = menuAnimationController;
        mDragToInteractAnimationController = dragToInteractAnimationController;
    }

    @Override
    public boolean onInterceptTouchEvent(@NonNull RecyclerView recyclerView,
            @NonNull MotionEvent motionEvent) {

        final View menuView = (View) recyclerView.getParent();
        addMovement(motionEvent);

        final float dx = motionEvent.getRawX() - mDown.x;
        final float dy = motionEvent.getRawY() - mDown.y;

        switch (motionEvent.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mMenuAnimationController.fadeInNowIfEnabled();
                mTouchSlop = ViewConfiguration.get(recyclerView.getContext()).getScaledTouchSlop();
                mDown.set(motionEvent.getRawX(), motionEvent.getRawY());
                mMenuTranslationDown.set(menuView.getTranslationX(), menuView.getTranslationY());

                mMenuAnimationController.cancelAnimations();
                mDragToInteractAnimationController.maybeConsumeDownMotionEvent(motionEvent);

                mOnActionDownEnd.ifPresent(Runnable::run);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mIsDragging || Math.hypot(dx, dy) > mTouchSlop) {
                    if (!mIsDragging) {
                        mIsDragging = true;
                        mMenuAnimationController.onDraggingStart();
                    }

                    mDragToInteractAnimationController.showInteractView(/* show= */ true);
                    if (mDragToInteractAnimationController.maybeConsumeMoveMotionEvent(motionEvent)
                            == empty) {
                        mMenuAnimationController.moveToPositionX(mMenuTranslationDown.x + dx);
                        mMenuAnimationController.moveToPositionYIfNeeded(
                                mMenuTranslationDown.y + dy);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mIsDragging) {
                    final float endX = mMenuTranslationDown.x + dx;
                    mIsDragging = false;

                    mDragToInteractAnimationController.showInteractView(/* show= */ false);

                    if (mMenuAnimationController.maybeMoveToEdgeAndHide(endX)) {
                        mMenuAnimationController.fadeOutIfEnabled();
                        return true;
                    }

                    if (mDragToInteractAnimationController.maybeConsumeUpMotionEvent(motionEvent)
                            == empty) {
                        mVelocityTracker.computeCurrentVelocity(VELOCITY_UNIT_SECONDS);
                        mMenuAnimationController.flingMenuThenSpringToEdge(endX,
                                mVelocityTracker.getXVelocity(), mVelocityTracker.getYVelocity());
                        mMenuAnimationController.fadeOutIfEnabled();
                    }
                    // Avoid triggering the listener of the item.
                    return true;
                }

                mMenuAnimationController.fadeOutIfEnabled();
                break;
            default: // Do nothing
        }

        // not consume all the events here because keeping the scroll behavior of list view.
        return false;
    }

    @Override
    public void onTouchEvent(@NonNull RecyclerView recyclerView,
            @NonNull MotionEvent motionEvent) {
        // Do nothing
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean b) {
        // Do nothing
    }

    void setOnActionDownEndListener(Runnable onActionDownEndListener) {
        mOnActionDownEnd = Optional.ofNullable(onActionDownEndListener);
    }

    /**
     * Adds a movement to the velocity tracker using raw screen coordinates.
     */
    private void addMovement(MotionEvent motionEvent) {
        final float deltaX = motionEvent.getRawX() - motionEvent.getX();
        final float deltaY = motionEvent.getRawY() - motionEvent.getY();
        motionEvent.offsetLocation(deltaX, deltaY);
        mVelocityTracker.addMovement(motionEvent);
        motionEvent.offsetLocation(-deltaX, -deltaY);
    }
}
