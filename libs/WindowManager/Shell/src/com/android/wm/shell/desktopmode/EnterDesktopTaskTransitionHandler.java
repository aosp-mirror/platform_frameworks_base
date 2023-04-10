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
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The {@link Transitions.TransitionHandler} that handles transitions for desktop mode tasks
 * entering and exiting freeform.
 */
public class EnterDesktopTaskTransitionHandler implements Transitions.TransitionHandler {

    private final Transitions mTransitions;
    private final Supplier<SurfaceControl.Transaction> mTransactionSupplier;

    // The size of the screen during drag relative to the fullscreen size
    public static final float DRAG_FREEFORM_SCALE = 0.4f;
    // The size of the screen after drag relative to the fullscreen size
    public static final float FINAL_FREEFORM_SCALE = 0.6f;
    public static final int FREEFORM_ANIMATION_DURATION = 336;

    private final List<IBinder> mPendingTransitionTokens = new ArrayList<>();

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
     */
    public void startTransition(@WindowManager.TransitionType int type,
                @NonNull WindowContainerTransaction wct) {
        final IBinder token = mTransitions.startTransition(type, wct, this);
        mPendingTransitionTokens.add(token);
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
                        transition, info.getType(), change, startT, finishCallback);
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
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (!mPendingTransitionTokens.contains(transition)) {
            return false;
        }

        final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
        if (type == Transitions.TRANSIT_ENTER_FREEFORM
                && taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            // Transitioning to freeform but keeping fullscreen bounds, so the crop is set
            // to null and we don't require an animation
            final SurfaceControl sc = change.getLeash();
            startT.setWindowCrop(sc, null);
            startT.apply();
            mTransitions.getMainExecutor().execute(
                    () -> finishCallback.onTransitionFinished(null, null));
            return true;
        }

        Rect endBounds = change.getEndAbsBounds();
        if (type == Transitions.TRANSIT_ENTER_DESKTOP_MODE
                && taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM
                && !endBounds.isEmpty()) {
            // This Transition animates a task to freeform bounds after being dragged into freeform
            // mode and brings the remaining freeform tasks to front
            final SurfaceControl sc = change.getLeash();
            startT.setWindowCrop(sc, endBounds.width(),
                    endBounds.height());
            startT.apply();

            // We want to find the scale of the current bounds relative to the end bounds. The
            // task is currently scaled to DRAG_FREEFORM_SCALE and the final bounds will be
            // scaled to FINAL_FREEFORM_SCALE. So, it is scaled to
            // DRAG_FREEFORM_SCALE / FINAL_FREEFORM_SCALE relative to the freeform bounds
            final ValueAnimator animator =
                    ValueAnimator.ofFloat(DRAG_FREEFORM_SCALE / FINAL_FREEFORM_SCALE, 1f);
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
                    mTransitions.getMainExecutor().execute(
                            () -> finishCallback.onTransitionFinished(null, null));
                }
            });

            animator.start();
            return true;
        }

        return false;
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        return null;
    }
}
