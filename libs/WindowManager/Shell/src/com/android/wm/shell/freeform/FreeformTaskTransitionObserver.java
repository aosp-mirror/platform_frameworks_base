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
import android.content.Context;
import android.os.IBinder;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.wm.shell.fullscreen.FullscreenTaskListener;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link Transitions.TransitionHandler} that handles freeform task launches, closes,
 * maximizing and restoring transitions. It also reports transitions so that window decorations can
 * be a part of transitions.
 */
public class FreeformTaskTransitionObserver implements Transitions.TransitionObserver {
    private static final String TAG = "FreeformTO";

    private final Transitions mTransitions;
    private final FreeformTaskListener<?> mFreeformTaskListener;
    private final FullscreenTaskListener<?> mFullscreenTaskListener;

    private final Map<IBinder, List<AutoCloseable>> mTransitionToWindowDecors = new HashMap<>();

    public FreeformTaskTransitionObserver(
            Context context,
            ShellInit shellInit,
            Transitions transitions,
            FullscreenTaskListener<?> fullscreenTaskListener,
            FreeformTaskListener<?> freeformTaskListener) {
        mTransitions = transitions;
        mFreeformTaskListener = freeformTaskListener;
        mFullscreenTaskListener = fullscreenTaskListener;
        if (Transitions.ENABLE_SHELL_TRANSITIONS && FreeformComponents.isFreeformEnabled(context)) {
            shellInit.addInitCallback(this::onInit, this);
        }
    }

    @VisibleForTesting
    void onInit() {
        mTransitions.registerObserver(this);
    }

    @Override
    public void onTransitionReady(
            @NonNull IBinder transition,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startT,
            @NonNull SurfaceControl.Transaction finishT) {
        final ArrayList<AutoCloseable> windowDecors = new ArrayList<>();
        final ArrayList<WindowContainerToken> taskParents = new ArrayList<>();
        for (TransitionInfo.Change change : info.getChanges()) {
            if ((change.getFlags() & TransitionInfo.FLAG_IS_WALLPAPER) != 0) {
                continue;
            }

            final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
            if (taskInfo == null || taskInfo.taskId == -1) {
                continue;
            }
            // Filter out non-leaf tasks. Freeform/fullscreen don't nest tasks, but split-screen
            // does, so this prevents adding duplicate captions in that scenario.
            if (change.getParent() != null
                    && info.getChange(change.getParent()).getTaskInfo() != null) {
                // This logic relies on 2 assumptions: 1 is that child tasks will be visited before
                // parents (due to how z-order works). 2 is that no non-tasks are interleaved
                // between tasks (hierarchically).
                taskParents.add(change.getContainer());
            }
            if (taskParents.contains(change.getContainer())) {
                continue;
            }

            switch (change.getMode()) {
                case WindowManager.TRANSIT_OPEN:
                case WindowManager.TRANSIT_TO_FRONT:
                    onOpenTransitionReady(change, startT, finishT);
                    break;
                case WindowManager.TRANSIT_CLOSE: {
                    onCloseTransitionReady(change, windowDecors, startT, finishT);
                    break;
                }
                case WindowManager.TRANSIT_CHANGE:
                    onChangeTransitionReady(info.getType(), change, startT, finishT);
                    break;
            }
        }
        if (!windowDecors.isEmpty()) {
            mTransitionToWindowDecors.put(transition, windowDecors);
        }
    }

    private void onOpenTransitionReady(
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
        }
    }

    private void onCloseTransitionReady(
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
    }

    private void onChangeTransitionReady(
            int type,
            TransitionInfo.Change change,
            SurfaceControl.Transaction startT,
            SurfaceControl.Transaction finishT) {
        AutoCloseable windowDecor = null;

        boolean adopted = false;
        final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
        if (taskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN) {
            windowDecor = mFreeformTaskListener.giveWindowDecoration(
                    change.getTaskInfo(), startT, finishT);
            if (windowDecor != null) {
                adopted = mFullscreenTaskListener.adoptWindowDecoration(
                        change, startT, finishT, windowDecor);
            } else {
                // will return false if it already has the window decor.
                adopted = mFullscreenTaskListener.createWindowDecoration(change, startT, finishT);
            }
        }

        if (taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            windowDecor = mFullscreenTaskListener.giveWindowDecoration(
                    change.getTaskInfo(), startT, finishT);
            if (windowDecor != null) {
                adopted = mFreeformTaskListener.adoptWindowDecoration(
                        change, startT, finishT, windowDecor);
            } else {
                // will return false if it already has the window decor.
                adopted = mFreeformTaskListener.createWindowDecoration(change, startT, finishT);
            }
        }

        if (!adopted) {
            releaseWindowDecor(windowDecor);
        }
    }

    @Override
    public void onTransitionStarting(@NonNull IBinder transition) {}

    @Override
    public void onTransitionMerged(@NonNull IBinder merged, @NonNull IBinder playing) {
        final List<AutoCloseable> windowDecorsOfMerged = mTransitionToWindowDecors.get(merged);
        if (windowDecorsOfMerged == null) {
            // We are adding window decorations of the merged transition to them of the playing
            // transition so if there is none of them there is nothing to do.
            return;
        }
        mTransitionToWindowDecors.remove(merged);

        final List<AutoCloseable> windowDecorsOfPlaying = mTransitionToWindowDecors.get(playing);
        if (windowDecorsOfPlaying != null) {
            windowDecorsOfPlaying.addAll(windowDecorsOfMerged);
        } else {
            mTransitionToWindowDecors.put(playing, windowDecorsOfMerged);
        }
    }

    @Override
    public void onTransitionFinished(@NonNull IBinder transition, boolean aborted) {
        final List<AutoCloseable> windowDecors = mTransitionToWindowDecors.getOrDefault(
                transition, Collections.emptyList());
        mTransitionToWindowDecors.remove(transition);

        for (AutoCloseable windowDecor : windowDecors) {
            releaseWindowDecor(windowDecor);
        }
        mFullscreenTaskListener.onTaskTransitionFinished();
        mFreeformTaskListener.onTaskTransitionFinished();
    }

    private static void releaseWindowDecor(AutoCloseable windowDecor) {
        if (windowDecor == null) {
            return;
        }
        try {
            windowDecor.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to release window decoration.", e);
        }
    }
}
