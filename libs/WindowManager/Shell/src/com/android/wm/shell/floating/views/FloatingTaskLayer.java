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

package com.android.wm.shell.floating.views;

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_FLOATING_APPS;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FlingAnimation;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.floating.FloatingDismissController;
import com.android.wm.shell.floating.FloatingTasksController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This is the layout that {@link FloatingTaskView}s are contained in. It handles input and
 * movement of the task views.
 */
public class FloatingTaskLayer extends FrameLayout
        implements ViewTreeObserver.OnComputeInternalInsetsListener {

    private static final String TAG = FloatingTaskLayer.class.getSimpleName();

    /** How big to make the task view based on screen width of the largest size. */
    private static final float START_SIZE_WIDTH_PERCENT = 0.33f;
    /** Min fling velocity required to move the view from one side of the screen to the other. */
    private static final float ESCAPE_VELOCITY = 750f;
    /** Amount of friction to apply to fling animations. */
    private static final float FLING_FRICTION = 1.9f;

    private final FloatingTasksController mController;
    private final FloatingDismissController mDismissController;
    private final WindowManager mWindowManager;
    private final TouchHandlerImpl mTouchHandler;

    private final Region mTouchableRegion = new Region();
    private final Rect mPositionRect = new Rect();
    private final Point mDefaultStartPosition = new Point();
    private final Point mTaskViewSize = new Point();
    private WindowInsets mWindowInsets;
    private int mVerticalPadding;
    private int mOverhangWhenStashed;

    private final List<Rect> mSystemGestureExclusionRects = Collections.singletonList(new Rect());
    private ViewTreeObserver.OnDrawListener mSystemGestureExclusionListener =
            this::updateSystemGestureExclusion;

    /** Interface allowing something to handle the touch events going to a task. */
    interface FloatingTaskTouchHandler {
        void onDown(@NonNull FloatingTaskView v, @NonNull MotionEvent ev,
                float viewInitialX, float viewInitialY);

        void onMove(@NonNull FloatingTaskView v, @NonNull MotionEvent ev,
                 float dx, float dy);

        void onUp(@NonNull FloatingTaskView v, @NonNull MotionEvent ev,
                float dx, float dy, float velX, float velY);

        void onClick(@NonNull FloatingTaskView v);
    }

    public FloatingTaskLayer(Context context,
            FloatingTasksController controller,
            WindowManager windowManager) {
        super(context);
        // TODO: Why is this necessary? Without it FloatingTaskView does not render correctly.
        setBackgroundColor(Color.argb(0, 0, 0, 0));

        mController = controller;
        mWindowManager = windowManager;
        updateSizes();

        // TODO: Might make sense to put dismiss controller in the touch handler since that's the
        //  main user of dismiss controller.
        mDismissController = new FloatingDismissController(context, mController, this);
        mTouchHandler = new TouchHandlerImpl();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
        getViewTreeObserver().addOnDrawListener(mSystemGestureExclusionListener);
        setOnApplyWindowInsetsListener((view, windowInsets) -> {
            if (!windowInsets.equals(mWindowInsets)) {
                mWindowInsets = windowInsets;
                updateSizes();
            }
            return windowInsets;
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
        getViewTreeObserver().removeOnDrawListener(mSystemGestureExclusionListener);
    }

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        mTouchableRegion.setEmpty();
        getTouchableRegion(mTouchableRegion);
        inoutInfo.touchableRegion.set(mTouchableRegion);
    }

    /** Adds a floating task to the layout. */
    public void addTask(FloatingTasksController.Task task) {
        if (task.floatingView == null) return;

        task.floatingView.setTouchHandler(mTouchHandler);
        addView(task.floatingView, new LayoutParams(mTaskViewSize.x, mTaskViewSize.y));
        updateTaskViewPosition(task.floatingView);
    }

    /** Animates the stashed state of the provided task, if it's part of the floating layer. */
    public void setStashed(FloatingTasksController.Task task, boolean shouldStash) {
        if (task.floatingView != null && task.floatingView.getParent() == this) {
            mTouchHandler.stashTaskView(task.floatingView, shouldStash);
        }
    }

    /** Removes all {@link FloatingTaskView} from the layout. */
    public void removeAllTaskViews() {
        int childCount = getChildCount();
        ArrayList<View> viewsToRemove = new ArrayList<>();
        for (int i = 0; i < childCount; i++) {
            if (getChildAt(i) instanceof FloatingTaskView) {
                viewsToRemove.add(getChildAt(i));
            }
        }
        for (View v : viewsToRemove) {
            removeView(v);
        }
    }

    /** Returns the number of task views in the layout. */
    public int getTaskViewCount() {
        int taskViewCount = 0;
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            if (getChildAt(i) instanceof FloatingTaskView) {
                taskViewCount++;
            }
        }
        return taskViewCount;
    }

    /**
     * Called when the task view is un-stuck from the dismiss target.
     * @param v the task view being moved.
     * @param velX the x velocity of the motion event.
     * @param velY the y velocity of the motion event.
     * @param wasFlungOut true if the user flung the task view out of the dismiss target (i.e. there
     *                    was an 'up' event), otherwise the user is still dragging.
     */
    public void onUnstuckFromTarget(FloatingTaskView v, float velX, float velY,
            boolean wasFlungOut) {
        mTouchHandler.onUnstuckFromTarget(v, velX, velY, wasFlungOut);
    }

    /**
     * Updates dimensions and applies them to any task views.
     */
    public void updateSizes() {
        if (mDismissController != null) {
            mDismissController.updateSizes();
        }

        mOverhangWhenStashed = getResources().getDimensionPixelSize(
                R.dimen.floating_task_stash_offset);
        mVerticalPadding = getResources().getDimensionPixelSize(
                R.dimen.floating_task_vertical_padding);

        WindowMetrics windowMetrics = mWindowManager.getCurrentWindowMetrics();
        WindowInsets windowInsets = windowMetrics.getWindowInsets();
        Insets insets = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()
                | WindowInsets.Type.statusBars()
                | WindowInsets.Type.displayCutout());
        Rect bounds = windowMetrics.getBounds();
        mPositionRect.set(bounds.left + insets.left,
                bounds.top + insets.top + mVerticalPadding,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom - mVerticalPadding);

        int taskViewWidth = Math.max(bounds.height(), bounds.width());
        int taskViewHeight = Math.min(bounds.height(), bounds.width());
        taskViewHeight = taskViewHeight - (insets.top + insets.bottom + (mVerticalPadding * 2));
        mTaskViewSize.set((int) (taskViewWidth * START_SIZE_WIDTH_PERCENT), taskViewHeight);
        mDefaultStartPosition.set(mPositionRect.left, mPositionRect.top);

        // Update existing views
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            if (getChildAt(i) instanceof FloatingTaskView) {
                FloatingTaskView child = (FloatingTaskView) getChildAt(i);
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                lp.width = mTaskViewSize.x;
                lp.height = mTaskViewSize.y;
                child.setLayoutParams(lp);
                updateTaskViewPosition(child);
            }
        }
    }

    /** Returns the first floating task view in the layout. (Currently only ever 1 view). */
    @Nullable
    public FloatingTaskView getFirstTaskView() {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child instanceof FloatingTaskView) {
                return (FloatingTaskView) child;
            }
        }
        return null;
    }

    private void updateTaskViewPosition(FloatingTaskView floatingView) {
        Point lastPosition = mController.getLastPosition();
        if (lastPosition.x == -1 && lastPosition.y == -1) {
            floatingView.setX(mDefaultStartPosition.x);
            floatingView.setY(mDefaultStartPosition.y);
        } else {
            floatingView.setX(lastPosition.x);
            floatingView.setY(lastPosition.y);
        }
        if (mTouchHandler.isStashedPosition(floatingView)) {
            floatingView.setStashed(true);
        }
        floatingView.updateLocation();
    }

    /**
     * Updates the area of the screen that shouldn't allow the back gesture due to the placement
     * of task view (i.e. when task view is stashed on an edge, tapping or swiping that edge would
     * un-stash the task view instead of performing the back gesture).
     */
    private void updateSystemGestureExclusion() {
        Rect excludeZone = mSystemGestureExclusionRects.get(0);
        FloatingTaskView floatingTaskView = getFirstTaskView();
        if (floatingTaskView != null && floatingTaskView.isStashed()) {
            excludeZone.set(floatingTaskView.getLeft(),
                    floatingTaskView.getTop(),
                    floatingTaskView.getRight(),
                    floatingTaskView.getBottom());
            excludeZone.offset((int) (floatingTaskView.getTranslationX()),
                    (int) (floatingTaskView.getTranslationY()));
            setSystemGestureExclusionRects(mSystemGestureExclusionRects);
        } else {
            excludeZone.setEmpty();
            setSystemGestureExclusionRects(Collections.emptyList());
        }
    }

    /**
     * Fills in the touchable region for floating windows. This is used by WindowManager to
     * decide which touch events go to the floating windows.
     */
    private void getTouchableRegion(Region outRegion) {
        int childCount = getChildCount();
        Rect temp = new Rect();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child instanceof FloatingTaskView) {
                child.getBoundsOnScreen(temp);
                outRegion.op(temp, Region.Op.UNION);
            }
        }
    }

    /**
     * Implementation of the touch handler. Animates the task view based on touch events.
     */
    private class TouchHandlerImpl implements FloatingTaskTouchHandler {
        /**
         * The view can be stashed by swiping it towards the current edge or moving it there. If
         * the view gets moved in a way that is not one of these gestures, this is flipped to false.
         */
        private boolean mCanStash = true;
        /**
         * This is used to indicate that the view has been un-stuck from the dismiss target and
         * needs to spring to the current touch location.
         */
        // TODO: implement this behavior
        private boolean mSpringToTouchOnNextMotionEvent = false;

        private ArrayList<FlingAnimation> mFlingAnimations;
        private ViewPropertyAnimator mViewPropertyAnimation;

        private float mViewInitialX;
        private float mViewInitialY;

        private float[] mMinMax = new float[2];

        @Override
        public void onDown(@NonNull FloatingTaskView v, @NonNull MotionEvent ev, float viewInitialX,
                float viewInitialY) {
            mCanStash = true;
            mViewInitialX = viewInitialX;
            mViewInitialY = viewInitialY;
            mDismissController.setUpMagneticObject(v);
            mDismissController.passEventToMagnetizedObject(ev);
        }

        @Override
        public void onMove(@NonNull FloatingTaskView v, @NonNull MotionEvent ev,
                float dx, float dy) {
            // Shows the magnetic dismiss target if needed.
            mDismissController.showDismiss(/* show= */ true);

            // Send it to magnetic target first.
            if (mDismissController.passEventToMagnetizedObject(ev)) {
                v.setStashed(false);
                mCanStash = true;

                return;
            }

            // If we're here magnetic target didn't want it so move as per normal.

            v.setTranslationX(capX(v, mViewInitialX + dx, /* isMoving= */ true));
            v.setTranslationY(capY(v, mViewInitialY + dy));
            if (v.isStashed()) {
                // Check if we've moved far enough to be not stashed.
                final float centerX = mPositionRect.centerX() - (v.getWidth() / 2f);
                final boolean viewInitiallyOnLeftSide = mViewInitialX < centerX;
                if (viewInitiallyOnLeftSide) {
                    if (v.getTranslationX() > mPositionRect.left) {
                        v.setStashed(false);
                        mCanStash = true;
                    }
                } else if (v.getTranslationX() + v.getWidth() < mPositionRect.right) {
                    v.setStashed(false);
                    mCanStash = true;
                }
            }
        }

        // Reference for math / values: StackAnimationController#flingStackThenSpringToEdge.
        // TODO clean up the code here, pretty hard to comprehend
        // TODO code here doesn't work the best when in portrait (e.g. can't fling up/down on edges)
        @Override
        public void onUp(@NonNull FloatingTaskView v, @NonNull MotionEvent ev,
                float dx, float dy, float velX, float velY) {

            // Send it to magnetic target first.
            if (mDismissController.passEventToMagnetizedObject(ev)) {
                v.setStashed(false);
                return;
            }
            mDismissController.showDismiss(/* show= */ false);

            // If we're here magnetic target didn't want it so handle up as per normal.

            final float x = capX(v, mViewInitialX + dx, /* isMoving= */ false);
            final float centerX = mPositionRect.centerX();
            final boolean viewInitiallyOnLeftSide = mViewInitialX + v.getWidth() < centerX;
            final boolean viewOnLeftSide = x + v.getWidth() < centerX;
            final boolean isFling = Math.abs(velX) > ESCAPE_VELOCITY;
            final boolean isFlingLeft = isFling && velX < ESCAPE_VELOCITY;
            // TODO: check velX here sometimes it doesn't stash on move when I think it should
            final boolean shouldStashFromMove =
                    (velX < 0 && v.getTranslationX() < mPositionRect.left)
                            || (velX > 0
                            && v.getTranslationX() + v.getWidth() > mPositionRect.right);
            final boolean shouldStashFromFling = viewInitiallyOnLeftSide == viewOnLeftSide
                    && isFling
                    && ((viewOnLeftSide && velX < ESCAPE_VELOCITY)
                    || (!viewOnLeftSide && velX > ESCAPE_VELOCITY));
            final boolean shouldStash = mCanStash && (shouldStashFromFling || shouldStashFromMove);

            ProtoLog.d(WM_SHELL_FLOATING_APPS,
                    "shouldStash=%s shouldStashFromFling=%s shouldStashFromMove=%s"
                    + " viewInitiallyOnLeftSide=%s viewOnLeftSide=%s isFling=%s velX=%f"
                    + " isStashed=%s", shouldStash, shouldStashFromFling, shouldStashFromMove,
                    viewInitiallyOnLeftSide, viewOnLeftSide, isFling, velX, v.isStashed());

            if (v.isStashed()) {
                mMinMax[0] = viewOnLeftSide
                        ? mPositionRect.left - v.getWidth() + mOverhangWhenStashed
                        : mPositionRect.right - v.getWidth();
                mMinMax[1] = viewOnLeftSide
                        ? mPositionRect.left
                        : mPositionRect.right - mOverhangWhenStashed;
            } else {
                populateMinMax(v, viewOnLeftSide, shouldStash, mMinMax);
            }

            boolean movingLeft = isFling ? isFlingLeft : viewOnLeftSide;
            float destinationRelativeX = movingLeft
                    ? mMinMax[0]
                    : mMinMax[1];

            // TODO: why is this necessary / when does this happen?
            if (mMinMax[1] < v.getTranslationX()) {
                mMinMax[1] = v.getTranslationX();
            }
            if (v.getTranslationX() < mMinMax[0]) {
                mMinMax[0] = v.getTranslationX();
            }

            // Use the touch event's velocity if it's sufficient, otherwise use the minimum velocity
            // so that it'll make it all the way to the side of the screen.
            final float minimumVelocityToReachEdge =
                    getMinimumVelocityToReachEdge(v, destinationRelativeX);
            final float startXVelocity = movingLeft
                    ? Math.min(minimumVelocityToReachEdge, velX)
                    : Math.max(minimumVelocityToReachEdge, velX);

            cancelAnyAnimations(v);

            mFlingAnimations = getAnimationForUpEvent(v, shouldStash,
                    startXVelocity, mMinMax[0], mMinMax[1], destinationRelativeX);
            for (int i = 0; i < mFlingAnimations.size(); i++) {
                mFlingAnimations.get(i).start();
            }
        }

        @Override
        public void onClick(@NonNull FloatingTaskView v) {
            if (v.isStashed()) {
                final float centerX = mPositionRect.centerX() - (v.getWidth() / 2f);
                final boolean viewOnLeftSide = v.getTranslationX() < centerX;
                final float destinationRelativeX = viewOnLeftSide
                        ? mPositionRect.left
                        : mPositionRect.right - v.getWidth();
                final float minimumVelocityToReachEdge =
                        getMinimumVelocityToReachEdge(v, destinationRelativeX);
                populateMinMax(v, viewOnLeftSide, /* stashed= */ true, mMinMax);

                cancelAnyAnimations(v);

                FlingAnimation flingAnimation = new FlingAnimation(v,
                        DynamicAnimation.TRANSLATION_X);
                flingAnimation.setFriction(FLING_FRICTION)
                        .setStartVelocity(minimumVelocityToReachEdge)
                        .setMinValue(mMinMax[0])
                        .setMaxValue(mMinMax[1])
                        .addEndListener((animation, canceled, value, velocity) -> {
                            if (canceled) return;
                            mController.setLastPosition((int) v.getTranslationX(),
                                    (int) v.getTranslationY());
                            v.setStashed(false);
                            v.updateLocation();
                        });
                mFlingAnimations = new ArrayList<>();
                mFlingAnimations.add(flingAnimation);
                flingAnimation.start();
            }
        }

        public void onUnstuckFromTarget(FloatingTaskView v, float velX, float velY,
                boolean wasFlungOut) {
            if (wasFlungOut) {
                snapTaskViewToEdge(v, velX, /* shouldStash= */ false);
            } else {
                // TODO: use this for something / to spring the view to the touch location
                mSpringToTouchOnNextMotionEvent = true;
            }
        }

        public void stashTaskView(FloatingTaskView v, boolean shouldStash) {
            if (v.isStashed() == shouldStash) {
                return;
            }
            final float centerX = mPositionRect.centerX() - (v.getWidth() / 2f);
            final boolean viewOnLeftSide = v.getTranslationX() < centerX;
            snapTaskViewToEdge(v, viewOnLeftSide ? -ESCAPE_VELOCITY : ESCAPE_VELOCITY, shouldStash);
        }

        public boolean isStashedPosition(View v) {
            return v.getTranslationX() < mPositionRect.left
                    || v.getTranslationX() + v.getWidth() > mPositionRect.right;
        }

        // TODO: a lot of this is duplicated in onUp -- can it be unified?
        private void snapTaskViewToEdge(FloatingTaskView v, float velX, boolean shouldStash) {
            final boolean movingLeft = velX < ESCAPE_VELOCITY;
            populateMinMax(v, movingLeft, shouldStash, mMinMax);
            float destinationRelativeX = movingLeft
                    ? mMinMax[0]
                    : mMinMax[1];

            // TODO: why is this necessary / when does this happen?
            if (mMinMax[1] < v.getTranslationX()) {
                mMinMax[1] = v.getTranslationX();
            }
            if (v.getTranslationX() < mMinMax[0]) {
                mMinMax[0] = v.getTranslationX();
            }

            // Use the touch event's velocity if it's sufficient, otherwise use the minimum velocity
            // so that it'll make it all the way to the side of the screen.
            final float minimumVelocityToReachEdge =
                    getMinimumVelocityToReachEdge(v, destinationRelativeX);
            final float startXVelocity = movingLeft
                    ? Math.min(minimumVelocityToReachEdge, velX)
                    : Math.max(minimumVelocityToReachEdge, velX);

            cancelAnyAnimations(v);

            mFlingAnimations = getAnimationForUpEvent(v,
                    shouldStash, startXVelocity,  mMinMax[0], mMinMax[1],
                    destinationRelativeX);
            for (int i = 0; i < mFlingAnimations.size(); i++) {
                mFlingAnimations.get(i).start();
            }
        }

        private void cancelAnyAnimations(FloatingTaskView v) {
            if (mFlingAnimations != null) {
                for (int i = 0; i < mFlingAnimations.size(); i++) {
                    if (mFlingAnimations.get(i).isRunning()) {
                        mFlingAnimations.get(i).cancel();
                    }
                }
            }
            if (mViewPropertyAnimation != null) {
                mViewPropertyAnimation.cancel();
                mViewPropertyAnimation = null;
            }
        }

        private ArrayList<FlingAnimation> getAnimationForUpEvent(FloatingTaskView v,
                boolean shouldStash, float startVelX, float minValue, float maxValue,
                float destinationRelativeX) {
            final float ty = v.getTranslationY();
            final ArrayList<FlingAnimation> animations = new ArrayList<>();
            if (ty != capY(v, ty)) {
                // The view was being dismissed so the Y is out of bounds, need to animate that.
                FlingAnimation yFlingAnimation = new FlingAnimation(v,
                        DynamicAnimation.TRANSLATION_Y);
                yFlingAnimation.setFriction(FLING_FRICTION)
                        .setStartVelocity(startVelX)
                        .setMinValue(mPositionRect.top)
                        .setMaxValue(mPositionRect.bottom - mTaskViewSize.y);
                animations.add(yFlingAnimation);
            }
            FlingAnimation flingAnimation = new FlingAnimation(v, DynamicAnimation.TRANSLATION_X);
            flingAnimation.setFriction(FLING_FRICTION)
                    .setStartVelocity(startVelX)
                    .setMinValue(minValue)
                    .setMaxValue(maxValue)
                    .addEndListener((animation, canceled, value, velocity) -> {
                        if (canceled) return;
                        Runnable endAction = () -> {
                            v.setStashed(shouldStash);
                            v.updateLocation();
                            if (!v.isStashed()) {
                                mController.setLastPosition((int) v.getTranslationX(),
                                        (int) v.getTranslationY());
                            }
                        };
                        if (!shouldStash) {
                            final int xTranslation = (int) v.getTranslationX();
                            if (xTranslation != destinationRelativeX) {
                                // TODO: this animation doesn't feel great, should figure out
                                //  a better way to do this or remove the need for it all together.
                                mViewPropertyAnimation = v.animate()
                                        .translationX(destinationRelativeX)
                                        .setListener(getAnimationListener(endAction));
                                mViewPropertyAnimation.start();
                            } else {
                                endAction.run();
                            }
                        } else {
                            endAction.run();
                        }
                    });
            animations.add(flingAnimation);
            return animations;
        }

        private AnimatorListenerAdapter getAnimationListener(Runnable endAction) {
            return new AnimatorListenerAdapter() {
                boolean translationCanceled = false;
                @Override
                public void onAnimationCancel(Animator animation) {
                    super.onAnimationCancel(animation);
                    translationCanceled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    if (!translationCanceled) {
                        endAction.run();
                    }
                }
            };
        }

        private void populateMinMax(FloatingTaskView v, boolean onLeft, boolean shouldStash,
                float[] out) {
            if (shouldStash) {
                out[0] = onLeft
                        ? mPositionRect.left - v.getWidth() + mOverhangWhenStashed
                        : mPositionRect.right - v.getWidth();
                out[1] = onLeft
                        ? mPositionRect.left
                        : mPositionRect.right - mOverhangWhenStashed;
            } else {
                out[0] = mPositionRect.left;
                out[1] = mPositionRect.right - mTaskViewSize.x;
            }
        }

        private float getMinimumVelocityToReachEdge(FloatingTaskView v,
                float destinationRelativeX) {
            // Minimum velocity required for the view to make it to the targeted side of the screen,
            // taking friction into account (4.2f is the number that friction scalars are multiplied
            // by in DynamicAnimation.DragForce). This is an estimate and could be slightly off, the
            // animation at the end will ensure that it reaches the destination X regardless.
            return (destinationRelativeX - v.getTranslationX()) * (FLING_FRICTION * 4.2f);
        }

        private float capX(FloatingTaskView v, float x, boolean isMoving) {
            final int width = v.getWidth();
            if (v.isStashed() || isMoving) {
                if (x < mPositionRect.left - v.getWidth() + mOverhangWhenStashed) {
                    return mPositionRect.left - v.getWidth() + mOverhangWhenStashed;
                }
                if (x > mPositionRect.right - mOverhangWhenStashed) {
                    return mPositionRect.right - mOverhangWhenStashed;
                }
            } else {
                if (x < mPositionRect.left) {
                    return mPositionRect.left;
                }
                if (x > mPositionRect.right - width) {
                    return mPositionRect.right - width;
                }
            }
            return x;
        }

        private float capY(FloatingTaskView v, float y) {
            final int height = v.getHeight();
            if (y < mPositionRect.top) {
                return mPositionRect.top;
            }
            if (y > mPositionRect.bottom - height) {
                return mPositionRect.bottom - height;
            }
            return y;
        }
    }
}
