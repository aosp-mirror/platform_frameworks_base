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

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;


/**
 * The {@link Transitions.TransitionHandler} that handles transitions for desktop mode tasks
 * entering and exiting freeform.
 */
public class ExitDesktopTaskTransitionHandler implements Transitions.TransitionHandler {
    private static final int FULLSCREEN_ANIMATION_DURATION = 336;
    private final Context mContext;
    private final Transitions mTransitions;
    private final List<IBinder> mPendingTransitionTokens = new ArrayList<>();

    private Supplier<SurfaceControl.Transaction> mTransactionSupplier;

    public ExitDesktopTaskTransitionHandler(
            Transitions transitions,
            Context context) {
        this(transitions, SurfaceControl.Transaction::new, context);
    }

    private ExitDesktopTaskTransitionHandler(
            Transitions transitions,
            Supplier<SurfaceControl.Transaction> supplier,
            Context context) {
        mTransitions = transitions;
        mTransactionSupplier = supplier;
        mContext = context;
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

    @VisibleForTesting
    boolean startChangeTransition(
            @NonNull IBinder transition,
            @WindowManager.TransitionType int type,
            @NonNull TransitionInfo.Change change,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (!mPendingTransitionTokens.contains(transition)) {
            return false;
        }
        final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
        if (type == Transitions.TRANSIT_EXIT_DESKTOP_MODE
                && taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
            // This Transition animates a task to fullscreen after being dragged to status bar
            final Resources resources = mContext.getResources();
            final DisplayMetrics metrics = resources.getDisplayMetrics();
            final int screenWidth = metrics.widthPixels;
            final int screenHeight = metrics.heightPixels;
            final SurfaceControl sc = change.getLeash();
            startT.setCrop(sc, null);
            startT.apply();
            final ValueAnimator animator = new ValueAnimator();
            animator.setFloatValues(0f, 1f);
            animator.setDuration(FULLSCREEN_ANIMATION_DURATION);
            final Rect startBounds = change.getStartAbsBounds();
            final float scaleX = (float) startBounds.width() / screenWidth;
            final float scaleY = (float) startBounds.height() / screenHeight;
            final SurfaceControl.Transaction t = mTransactionSupplier.get();
            Point startPos = new Point(startBounds.left,
                    startBounds.top);
            animator.addUpdateListener(animation -> {
                float fraction = animation.getAnimatedFraction();
                float currentScaleX = scaleX + ((1 - scaleX) * fraction);
                float currentScaleY = scaleY + ((1 - scaleY) * fraction);
                t.setPosition(sc, startPos.x * (1 - fraction), startPos.y * (1 - fraction));
                t.setScale(sc, currentScaleX, currentScaleY);
                t.apply();
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
