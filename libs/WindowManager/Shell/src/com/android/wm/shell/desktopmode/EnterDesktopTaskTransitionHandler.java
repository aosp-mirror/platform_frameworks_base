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

package com.android.wm.shell.desktopmode;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.RectEvaluator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.DesktopModeWindowDecoration;
import com.android.wm.shell.windowdecor.MoveToDesktopAnimator;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The {@link Transitions.TransitionHandler} that handles transitions for desktop mode tasks
 * entering and exiting freeform.
 */
public class EnterDesktopTaskTransitionHandler implements Transitions.TransitionHandler {

    private static final String TAG = "EnterDesktopTaskTransitionHandler";
    private final Transitions mTransitions;
    private final Supplier<SurfaceControl.Transaction> mTransactionSupplier;

    // The size of the screen after drag relative to the fullscreen size
    public static final float FINAL_FREEFORM_SCALE = 0.6f;
    public static final int FREEFORM_ANIMATION_DURATION = 336;

    private final List<IBinder> mPendingTransitionTokens = new ArrayList<>();
    private Consumer<SurfaceControl.Transaction> mOnAnimationFinishedCallback;
    private MoveToDesktopAnimator mMoveToDesktopAnimator;
    private DesktopModeWindowDecoration mDesktopModeWindowDecoration;

    public EnterDesktopTaskTransitionHandler(
            Transitions transitions) {
        this(transitions, SurfaceControl.Transaction::new);
    }

    public EnterDesktopTaskTransitionHandler(
            Transitions transitions,
            Supplier<SurfaceControl.Transaction> supplier) {
        mTransitions = transitions;
        mTransactionSupplier = supplier;
    }

    /**
     * Starts Transition of a given type
     * @param type Transition type
     * @param wct WindowContainerTransaction for transition
     * @param onAnimationEndCallback to be called after animation
     */
    private void startTransition(@WindowManager.TransitionType int type,
            @NonNull WindowContainerTransaction wct,
            Consumer<SurfaceControl.Transaction> onAnimationEndCallback) {
        mOnAnimationFinishedCallback = onAnimationEndCallback;
        final IBinder token = mTransitions.startTransition(type, wct, this);
        mPendingTransitionTokens.add(token);
    }

    /**
     * Starts Transition of type TRANSIT_START_DRAG_TO_DESKTOP_MODE
     * @param wct WindowContainerTransaction for transition
     * @param moveToDesktopAnimator Animator that shrinks and positions task during two part move
     *                              to desktop animation
     * @param onAnimationEndCallback to be called after animation
     */
    public void startMoveToDesktop(@NonNull WindowContainerTransaction wct,
            @NonNull MoveToDesktopAnimator moveToDesktopAnimator,
            Consumer<SurfaceControl.Transaction> onAnimationEndCallback) {
        mMoveToDesktopAnimator = moveToDesktopAnimator;
        startTransition(Transitions.TRANSIT_START_DRAG_TO_DESKTOP_MODE, wct,
                onAnimationEndCallback);
    }

    /**
     * Starts Transition of type TRANSIT_FINALIZE_DRAG_TO_DESKTOP_MODE
     * @param wct WindowContainerTransaction for transition
     * @param onAnimationEndCallback to be called after animation
     */
    public void finalizeMoveToDesktop(@NonNull WindowContainerTransaction wct,
            Consumer<SurfaceControl.Transaction> onAnimationEndCallback) {
        startTransition(Transitions.TRANSIT_FINALIZE_DRAG_TO_DESKTOP_MODE, wct,
                onAnimationEndCallback);
    }

    /**
     * Starts Transition of type TRANSIT_CANCEL_ENTERING_DESKTOP_MODE
     * @param wct WindowContainerTransaction for transition
     * @param moveToDesktopAnimator Animator that shrinks and positions task during two part move
     *                              to desktop animation
     * @param onAnimationEndCallback to be called after animation
     */
    public void startCancelMoveToDesktopMode(@NonNull WindowContainerTransaction wct,
            MoveToDesktopAnimator moveToDesktopAnimator,
            Consumer<SurfaceControl.Transaction> onAnimationEndCallback) {
        mMoveToDesktopAnimator = moveToDesktopAnimator;
        startTransition(Transitions.TRANSIT_CANCEL_DRAG_TO_DESKTOP_MODE, wct,
                onAnimationEndCallback);
    }

    /**
     * Starts Transition of type TRANSIT_MOVE_TO_DESKTOP
     * @param wct WindowContainerTransaction for transition
     * @param decor {@link DesktopModeWindowDecoration} of task being animated
     */
    public void moveToDesktop(@NonNull WindowContainerTransaction wct,
            DesktopModeWindowDecoration decor) {
        mDesktopModeWindowDecoration = decor;
        startTransition(Transitions.TRANSIT_MOVE_TO_DESKTOP, wct,
                null /* onAnimationEndCallback */);
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull SurfaceControl.Transaction finishT,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        boolean transitionHandled = false;
        for (TransitionInfo.Change change : info.getChanges()) {
            if ((change.getFlags() & TransitionInfo.FLAG_IS_WALLPAPER) != 0) {
                continue;
            }

            final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
            if (taskInfo == null || taskInfo.taskId == -1) {
                continue;
            }

            if (change.getMode() == WindowManager.TRANSIT_CHANGE) {
                transitionHandled |= startChangeTransition(
                        transition, info.getType(), change, startT, finishT, finishCallback);
            }
        }

        mPendingTransitionTokens.remove(transition);

        return transitionHandled;
    }

    private boolean startChangeTransition(
            @NonNull IBinder transition,
            @WindowManager.TransitionType int type,
            @NonNull TransitionInfo.Change change,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull SurfaceControl.Transaction finishT,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (!mPendingTransitionTokens.contains(transition)) {
            return false;
        }

        final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
        if (type == Transitions.TRANSIT_MOVE_TO_DESKTOP
                && taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            return animateMoveToDesktop(change, startT, finishCallback);
        }

        if (type == Transitions.TRANSIT_START_DRAG_TO_DESKTOP_MODE
                && taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            return animateStartDragToDesktopMode(change, startT, finishT, finishCallback);
        }

        final Rect endBounds = change.getEndAbsBounds();
        if (type == Transitions.TRANSIT_FINALIZE_DRAG_TO_DESKTOP_MODE
                && taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM
                && !endBounds.isEmpty()) {
            return animateFinalizeDragToDesktopMode(change, startT, finishT, finishCallback,
                    endBounds);
        }

        if (type == Transitions.TRANSIT_CANCEL_DRAG_TO_DESKTOP_MODE
                && taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            return animateCancelDragToDesktopMode(change, startT, finishT, finishCallback,
                    endBounds);
        }

        return false;
    }

    private boolean animateMoveToDesktop(
            @NonNull TransitionInfo.Change change,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (mDesktopModeWindowDecoration == null) {
            Slog.e(TAG, "Window Decoration is not available for this transition");
            return false;
        }

        final SurfaceControl leash = change.getLeash();
        final Rect startBounds = change.getStartAbsBounds();
        startT.setPosition(leash, startBounds.left, startBounds.right)
                .setWindowCrop(leash, startBounds.width(), startBounds.height())
                .show(leash);
        mDesktopModeWindowDecoration.showResizeVeil(startT, startBounds);

        final ValueAnimator animator = ValueAnimator.ofObject(new RectEvaluator(),
                change.getStartAbsBounds(), change.getEndAbsBounds());
        animator.setDuration(FREEFORM_ANIMATION_DURATION);
        SurfaceControl.Transaction t = mTransactionSupplier.get();
        animator.addUpdateListener(animation -> {
            final Rect animationValue = (Rect) animator.getAnimatedValue();
            t.setPosition(leash, animationValue.left, animationValue.right)
                    .setWindowCrop(leash, animationValue.width(), animationValue.height())
                    .show(leash);
            mDesktopModeWindowDecoration.updateResizeVeil(t, animationValue);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mDesktopModeWindowDecoration.hideResizeVeil();
                mTransitions.getMainExecutor().execute(
                        () -> finishCallback.onTransitionFinished(null));
            }
        });
        animator.start();
        return true;
    }

    private boolean animateStartDragToDesktopMode(
            @NonNull TransitionInfo.Change change,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull SurfaceControl.Transaction finishT,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        // Transitioning to freeform but keeping fullscreen bounds, so the crop is set
        // to null and we don't require an animation
        final SurfaceControl sc = change.getLeash();
        startT.setWindowCrop(sc, null);

        if (mMoveToDesktopAnimator == null
                || mMoveToDesktopAnimator.getTaskId() != change.getTaskInfo().taskId) {
            Slog.e(TAG, "No animator available for this transition");
            return false;
        }

        // Calculate and set position of the task
        final PointF position = mMoveToDesktopAnimator.getPosition();
        startT.setPosition(sc, position.x, position.y);
        finishT.setPosition(sc, position.x, position.y);

        startT.apply();

        mTransitions.getMainExecutor().execute(
                () -> finishCallback.onTransitionFinished(null));

        return true;
    }

    private boolean animateFinalizeDragToDesktopMode(
            @NonNull TransitionInfo.Change change,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull SurfaceControl.Transaction finishT,
            @NonNull Transitions.TransitionFinishCallback finishCallback,
            @NonNull Rect endBounds) {
        // This Transition animates a task to freeform bounds after being dragged into freeform
        // mode and brings the remaining freeform tasks to front
        final SurfaceControl sc = change.getLeash();
        startT.setWindowCrop(sc, endBounds.width(),
                endBounds.height());
        startT.apply();

        // End the animation that shrinks the window when task is first dragged from fullscreen
        if (mMoveToDesktopAnimator != null) {
            mMoveToDesktopAnimator.endAnimator();
        }

        // We want to find the scale of the current bounds relative to the end bounds. The
        // task is currently scaled to DRAG_FREEFORM_SCALE and the final bounds will be
        // scaled to FINAL_FREEFORM_SCALE. So, it is scaled to
        // DRAG_FREEFORM_SCALE / FINAL_FREEFORM_SCALE relative to the freeform bounds
        final ValueAnimator animator =
                ValueAnimator.ofFloat(
                        MoveToDesktopAnimator.DRAG_FREEFORM_SCALE / FINAL_FREEFORM_SCALE, 1f);
        animator.setDuration(FREEFORM_ANIMATION_DURATION);
        final SurfaceControl.Transaction t = mTransactionSupplier.get();
        animator.addUpdateListener(animation -> {
            final float animationValue = (float) animation.getAnimatedValue();
            t.setScale(sc, animationValue, animationValue);

            final float animationWidth = endBounds.width() * animationValue;
            final float animationHeight = endBounds.height() * animationValue;
            final int animationX = endBounds.centerX() - (int) (animationWidth / 2);
            final int animationY = endBounds.centerY() - (int) (animationHeight / 2);

            t.setPosition(sc, animationX, animationY);
            t.apply();
        });

        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mOnAnimationFinishedCallback != null) {
                    mOnAnimationFinishedCallback.accept(finishT);
                }
                mTransitions.getMainExecutor().execute(
                        () -> finishCallback.onTransitionFinished(null));
            }
        });

        animator.start();
        return true;
    }
    private boolean animateCancelDragToDesktopMode(
            @NonNull TransitionInfo.Change change,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull SurfaceControl.Transaction finishT,
            @NonNull Transitions.TransitionFinishCallback finishCallback,
            @NonNull Rect endBounds) {
        // This Transition animates a task to fullscreen after being dragged from the status
        // bar and then released back into the status bar area
        final SurfaceControl sc = change.getLeash();
        // Hide the first (fullscreen) frame because the animation will start from the smaller
        // scale size.
        startT.hide(sc)
                .setWindowCrop(sc, endBounds.width(), endBounds.height())
                .apply();

        if (mMoveToDesktopAnimator == null
                || mMoveToDesktopAnimator.getTaskId() != change.getTaskInfo().taskId) {
            Slog.e(TAG, "No animator available for this transition");
            return false;
        }

        // End the animation that shrinks the window when task is first dragged from fullscreen
        mMoveToDesktopAnimator.endAnimator();

        final ValueAnimator animator = new ValueAnimator();
        animator.setFloatValues(MoveToDesktopAnimator.DRAG_FREEFORM_SCALE, 1f);
        animator.setDuration(FREEFORM_ANIMATION_DURATION);
        final SurfaceControl.Transaction t = mTransactionSupplier.get();

        // Get position of the task
        final float x = mMoveToDesktopAnimator.getPosition().x;
        final float y = mMoveToDesktopAnimator.getPosition().y;

        animator.addUpdateListener(animation -> {
            final float scale = (float) animation.getAnimatedValue();
            t.setPosition(sc, x * (1 - scale), y * (1 - scale))
                    .setScale(sc, scale, scale)
                    .show(sc)
                    .apply();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mOnAnimationFinishedCallback != null) {
                    mOnAnimationFinishedCallback.accept(finishT);
                }
                mTransitions.getMainExecutor().execute(
                        () -> finishCallback.onTransitionFinished(null));
            }
        });
        animator.start();
        return true;
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        return null;
    }
}
