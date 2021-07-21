/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.pip;

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_REMOVE_STACK;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_TO_PIP;

import android.app.PictureInPictureParams;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceControl;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;
import java.util.List;

/**
 * Responsible supplying PiP Transitions.
 */
public abstract class PipTransitionController implements Transitions.TransitionHandler {

    protected final PipAnimationController mPipAnimationController;
    protected final PipBoundsAlgorithm mPipBoundsAlgorithm;
    protected final PipBoundsState mPipBoundsState;
    protected final ShellTaskOrganizer mShellTaskOrganizer;
    protected final PipMenuController mPipMenuController;
    private final Handler mMainHandler;
    private final List<PipTransitionCallback> mPipTransitionCallbacks = new ArrayList<>();

    protected final PipAnimationController.PipAnimationCallback mPipAnimationCallback =
            new PipAnimationController.PipAnimationCallback() {
                @Override
                public void onPipAnimationStart(TaskInfo taskInfo,
                        PipAnimationController.PipTransitionAnimator animator) {
                    final int direction = animator.getTransitionDirection();
                    if (direction == TRANSITION_DIRECTION_TO_PIP) {
                        // TODO (b//169221267): Add jank listener for transactions without buffer
                        //  updates.
                        //InteractionJankMonitor.getInstance().begin(
                        //        InteractionJankMonitor.CUJ_LAUNCHER_APP_CLOSE_TO_PIP, 2000);
                    }
                    sendOnPipTransitionStarted(direction);
                }

                @Override
                public void onPipAnimationEnd(TaskInfo taskInfo, SurfaceControl.Transaction tx,
                        PipAnimationController.PipTransitionAnimator animator) {
                    final int direction = animator.getTransitionDirection();
                    mPipBoundsState.setBounds(animator.getDestinationBounds());
                    if (direction == TRANSITION_DIRECTION_REMOVE_STACK) {
                        return;
                    }
                    onFinishResize(taskInfo, animator.getDestinationBounds(), direction, tx);
                    sendOnPipTransitionFinished(direction);
                    if (direction == TRANSITION_DIRECTION_TO_PIP) {
                        // TODO (b//169221267): Add jank listener for transactions without buffer
                        //  updates.
                        //InteractionJankMonitor.getInstance().end(
                        //        InteractionJankMonitor.CUJ_LAUNCHER_APP_CLOSE_TO_PIP);
                    }
                }

                @Override
                public void onPipAnimationCancel(TaskInfo taskInfo,
                        PipAnimationController.PipTransitionAnimator animator) {
                    sendOnPipTransitionCancelled(animator.getTransitionDirection());
                }
            };

    /**
     * Called when transition is about to finish. This is usually for performing tasks such as
     * applying WindowContainerTransaction to finalize the PiP bounds and send to the framework.
     */
    public void onFinishResize(TaskInfo taskInfo, Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction,
            SurfaceControl.Transaction tx) {
    }

    public PipTransitionController(PipBoundsState pipBoundsState,
            PipMenuController pipMenuController, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipAnimationController pipAnimationController, Transitions transitions,
            @android.annotation.NonNull ShellTaskOrganizer shellTaskOrganizer) {
        mPipBoundsState = pipBoundsState;
        mPipMenuController = pipMenuController;
        mShellTaskOrganizer = shellTaskOrganizer;
        mPipBoundsAlgorithm = pipBoundsAlgorithm;
        mPipAnimationController = pipAnimationController;
        mMainHandler = new Handler(Looper.getMainLooper());
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            transitions.addHandler(this);
        }
    }

    /**
     * Registers {@link PipTransitionCallback} to receive transition callbacks.
     */
    public void registerPipTransitionCallback(PipTransitionCallback callback) {
        mPipTransitionCallbacks.add(callback);
    }

    protected void sendOnPipTransitionStarted(
            @PipAnimationController.TransitionDirection int direction) {
        final Rect pipBounds = mPipBoundsState.getBounds();
        for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
            final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
            callback.onPipTransitionStarted(direction, pipBounds);
        }
    }

    protected void sendOnPipTransitionFinished(
            @PipAnimationController.TransitionDirection int direction) {
        for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
            final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
            callback.onPipTransitionFinished(direction);
        }
    }

    protected void sendOnPipTransitionCancelled(
            @PipAnimationController.TransitionDirection int direction) {
        for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
            final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
            callback.onPipTransitionCanceled(direction);
        }
    }

    /**
     * The windowing mode to restore to when resizing out of PIP direction. Defaults to undefined
     * and can be overridden to restore to an alternate windowing mode.
     */
    public int getOutPipWindowingMode() {
        // By default, simply reset the windowing mode to undefined.
        return WINDOWING_MODE_UNDEFINED;
    }

    protected void setBoundsStateForEntry(ComponentName componentName,
            PictureInPictureParams params,
            ActivityInfo activityInfo) {
        mPipBoundsState.setBoundsStateForEntry(componentName,
                mPipBoundsAlgorithm.getAspectRatioOrDefault(params),
                mPipBoundsAlgorithm.getMinimalSize(activityInfo));
    }

    /**
     * Callback interface for PiP transitions (both from and to PiP mode)
     */
    public interface PipTransitionCallback {
        /**
         * Callback when the pip transition is started.
         */
        void onPipTransitionStarted(int direction, Rect pipBounds);

        /**
         * Callback when the pip transition is finished.
         */
        void onPipTransitionFinished(int direction);

        /**
         * Callback when the pip transition is cancelled.
         */
        void onPipTransitionCanceled(int direction);
    }
}
