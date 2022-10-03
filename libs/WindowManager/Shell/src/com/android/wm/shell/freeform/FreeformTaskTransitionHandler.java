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

package com.android.wm.shell.freeform;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.WindowDecorViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link Transitions.TransitionHandler} that handles freeform task maximizing and restoring
 * transitions.
 */
public class FreeformTaskTransitionHandler
        implements Transitions.TransitionHandler, FreeformTaskTransitionStarter {

    private final Transitions mTransitions;
    private final WindowDecorViewModel<?> mWindowDecorViewModel;

    private final List<IBinder> mPendingTransitionTokens = new ArrayList<>();

    public FreeformTaskTransitionHandler(
            ShellInit shellInit,
            Transitions transitions,
            WindowDecorViewModel<?> windowDecorViewModel) {
        mTransitions = transitions;
        mWindowDecorViewModel = windowDecorViewModel;
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            shellInit.addInitCallback(this::onInit, this);
        }
    }

    private void onInit() {
        mWindowDecorViewModel.setFreeformTaskTransitionStarter(this);
    }

    @Override
    public void startWindowingModeTransition(
            int targetWindowingMode, WindowContainerTransaction wct) {
        final int type;
        switch (targetWindowingMode) {
            case WINDOWING_MODE_FULLSCREEN:
                type = Transitions.TRANSIT_MAXIMIZE;
                break;
            case WINDOWING_MODE_FREEFORM:
                type = Transitions.TRANSIT_RESTORE_FROM_MAXIMIZE;
                break;
            default:
                throw new IllegalArgumentException("Unexpected target windowing mode "
                        + WindowConfiguration.windowingModeToString(targetWindowingMode));
        }
        final IBinder token = mTransitions.startTransition(type, wct, this);
        mPendingTransitionTokens.add(token);
    }

    @Override
    public void startMinimizedModeTransition(WindowContainerTransaction wct) {
        final int type = WindowManager.TRANSIT_TO_BACK;
        mPendingTransitionTokens.add(mTransitions.startTransition(type, wct, this));
    }


    @Override
    public void startRemoveTransition(WindowContainerTransaction wct) {
        final int type = WindowManager.TRANSIT_CLOSE;
        mPendingTransitionTokens.add(mTransitions.startTransition(type, wct, this));
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

            switch (change.getMode()) {
                case WindowManager.TRANSIT_CHANGE:
                    transitionHandled |= startChangeTransition(
                            transition, info.getType(), change);
                    break;
                case WindowManager.TRANSIT_TO_BACK:
                    transitionHandled |= startMinimizeTransition(transition);
                    break;
            }
        }

        mPendingTransitionTokens.remove(transition);

        if (!transitionHandled) {
            return false;
        }

        startT.apply();
        mTransitions.getMainExecutor().execute(
                () -> finishCallback.onTransitionFinished(null, null));
        return true;
    }

    private boolean startChangeTransition(
            IBinder transition,
            int type,
            TransitionInfo.Change change) {
        if (!mPendingTransitionTokens.contains(transition)) {
            return false;
        }

        boolean handled = false;
        final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
        if (type == Transitions.TRANSIT_MAXIMIZE
                && taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
            // TODO: Add maximize animations
            handled = true;
        }

        if (type == Transitions.TRANSIT_RESTORE_FROM_MAXIMIZE
                && taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            // TODO: Add restore animations
            handled = true;
        }

        return handled;
    }

    private boolean startMinimizeTransition(IBinder transition) {
        return mPendingTransitionTokens.contains(transition);
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        return null;
    }
}
