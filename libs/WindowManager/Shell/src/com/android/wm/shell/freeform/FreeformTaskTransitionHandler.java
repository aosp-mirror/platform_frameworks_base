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
import android.util.Log;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.fullscreen.FullscreenTaskListener;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.WindowDecorViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link Transitions.TransitionHandler} that handles freeform task launches, closes,
 * maximizing and restoring transitions. It also reports transitions so that window decorations can
 * be a part of transitions.
 */
public class FreeformTaskTransitionHandler
        implements Transitions.TransitionHandler, FreeformTaskTransitionStarter {
    private static final String TAG = "FreeformTH";

    private final Transitions mTransitions;
    private final FreeformTaskListener<?> mFreeformTaskListener;
    private final FullscreenTaskListener<?> mFullscreenTaskListener;
    private final WindowDecorViewModel<?> mWindowDecorViewModel;

    private final List<IBinder> mPendingTransitionTokens = new ArrayList<>();

    public FreeformTaskTransitionHandler(
            ShellInit shellInit,
            Transitions transitions,
            WindowDecorViewModel<?> windowDecorViewModel,
            FullscreenTaskListener<?> fullscreenTaskListener,
            FreeformTaskListener<?> freeformTaskListener) {
        mTransitions = transitions;
        mFullscreenTaskListener = fullscreenTaskListener;
        mFreeformTaskListener = freeformTaskListener;
        mWindowDecorViewModel = windowDecorViewModel;
        if (shellInit != null && Transitions.ENABLE_SHELL_TRANSITIONS) {
            shellInit.addInitCallback(this::onInit, this);
        }
    }

    private void onInit() {
        mWindowDecorViewModel.setFreeformTaskTransitionStarter(this);
        mTransitions.addHandler(this);
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
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull SurfaceControl.Transaction finishT,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        boolean transitionHandled = false;
        final ArrayList<AutoCloseable> windowDecorsInCloseTransitions = new ArrayList<>();
        for (TransitionInfo.Change change : info.getChanges()) {
            if ((change.getFlags() & TransitionInfo.FLAG_IS_WALLPAPER) != 0) {
                continue;
            }

            final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
            if (taskInfo == null || taskInfo.taskId == -1) {
                continue;
            }

            switch (change.getMode()) {
                case WindowManager.TRANSIT_OPEN:
                    transitionHandled |= startOpenTransition(change, startT, finishT);
                    break;
                case WindowManager.TRANSIT_CLOSE:
                    transitionHandled |= startCloseTransition(
                            change, windowDecorsInCloseTransitions, startT, finishT);
                    break;
                case WindowManager.TRANSIT_CHANGE:
                    transitionHandled |= startChangeTransition(
                            transition, info.getType(), change, startT, finishT);
                    break;
                case WindowManager.TRANSIT_TO_BACK:
                    transitionHandled |= startMinimizeTransition(transition);
                    break;
                case WindowManager.TRANSIT_TO_FRONT:
                    break;
            }
        }

        mPendingTransitionTokens.remove(transition);

        if (!transitionHandled) {
            return false;
        }

        startT.apply();
        mTransitions.getMainExecutor().execute(
                () -> finishTransition(windowDecorsInCloseTransitions, finishCallback));
        return true;
    }

    private boolean startOpenTransition(
            TransitionInfo.Change change,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        switch (change.getTaskInfo().getWindowingMode()){
            case WINDOWING_MODE_FREEFORM:
                mFreeformTaskListener.createWindowDecoration(change, startT, finishT);
                break;
            case WINDOWING_MODE_FULLSCREEN:
                mFullscreenTaskListener.createWindowDecoration(change, startT, finishT);
                break;
            default:
                return false;
        }

        // Intercepted transition to manage the window decorations. Let other handlers animate.
        return false;
    }

    private boolean startCloseTransition(
            TransitionInfo.Change change,
            ArrayList<AutoCloseable> windowDecors,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        final AutoCloseable windowDecor;
        switch (change.getTaskInfo().getWindowingMode()) {
            case WINDOWING_MODE_FREEFORM:
                windowDecor = mFreeformTaskListener.giveWindowDecoration(change.getTaskInfo(),
                        startT, finishT);
                break;
            case WINDOWING_MODE_FULLSCREEN:
                windowDecor = mFullscreenTaskListener.giveWindowDecoration(change.getTaskInfo(),
                        startT, finishT);
                break;
            default:
                windowDecor = null;
        }
        if (windowDecor != null) {
            windowDecors.add(windowDecor);
        }
        // Intercepted transition to manage the window decorations. Let other handlers animate.
        return false;
    }

    private boolean startMinimizeTransition(IBinder transition) {
        if (!mPendingTransitionTokens.contains(transition)) {
            return false;
        }
        return true;
    }

    private boolean startChangeTransition(
            IBinder transition,
            int type,
            TransitionInfo.Change change,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        AutoCloseable windowDecor = null;

        if (!mPendingTransitionTokens.contains(transition)) {
            return false;
        }

        boolean handled = false;
        boolean adopted = false;
        final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
        if (type == Transitions.TRANSIT_MAXIMIZE
                && taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
            handled = true;
            windowDecor = mFreeformTaskListener.giveWindowDecoration(
                    change.getTaskInfo(), startT, finishT);
            adopted = mFullscreenTaskListener.adoptWindowDecoration(change,
                    startT, finishT, windowDecor);
        }

        if (type == Transitions.TRANSIT_RESTORE_FROM_MAXIMIZE
                && taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            handled = true;
            windowDecor = mFullscreenTaskListener.giveWindowDecoration(
                    change.getTaskInfo(), startT, finishT);
            adopted = mFreeformTaskListener.adoptWindowDecoration(change,
                    startT, finishT, windowDecor);
        }

        if (!adopted) {
            releaseWindowDecor(windowDecor);
        }
        return handled;
    }

    private void finishTransition(
            ArrayList<AutoCloseable> windowDecorsInCloseTransitions,
            Transitions.TransitionFinishCallback finishCallback) {
        for (AutoCloseable windowDecor : windowDecorsInCloseTransitions) {
            releaseWindowDecor(windowDecor);
        }
        mFreeformTaskListener.onTaskTransitionFinished();
        mFullscreenTaskListener.onTaskTransitionFinished();
        finishCallback.onTransitionFinished(null, null);
    }

    private void releaseWindowDecor(AutoCloseable windowDecor) {
        if (windowDecor == null) {
            return;
        }
        try {
            windowDecor.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to release window decoration.", e);
        }
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        final ActivityManager.RunningTaskInfo taskInfo = request.getTriggerTask();
        if (taskInfo == null || taskInfo.getWindowingMode() != WINDOWING_MODE_FREEFORM) {
            return null;
        }
        switch (request.getType()) {
            case WindowManager.TRANSIT_OPEN:
            case WindowManager.TRANSIT_CLOSE:
            case WindowManager.TRANSIT_TO_FRONT:
            case WindowManager.TRANSIT_TO_BACK:
                return new WindowContainerTransaction();
            default:
                return null;
        }
    }

}
