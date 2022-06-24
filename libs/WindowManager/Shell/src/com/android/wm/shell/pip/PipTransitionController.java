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
import static android.view.WindowManager.TRANSIT_PIP;

import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_REMOVE_STACK;
import static com.android.wm.shell.pip.PipAnimationController.isInPipDirection;

import android.annotation.Nullable;
import android.app.PictureInPictureParams;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;

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
    protected final Transitions mTransitions;
    private final Handler mMainHandler;
    private final List<PipTransitionCallback> mPipTransitionCallbacks = new ArrayList<>();
    protected PipTaskOrganizer mPipOrganizer;

    protected final PipAnimationController.PipAnimationCallback mPipAnimationCallback =
            new PipAnimationController.PipAnimationCallback() {
                @Override
                public void onPipAnimationStart(TaskInfo taskInfo,
                        PipAnimationController.PipTransitionAnimator animator) {
                    final int direction = animator.getTransitionDirection();
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
                    if (isInPipDirection(direction) && animator.getContentOverlayLeash() != null) {
                        mPipOrganizer.fadeOutAndRemoveOverlay(animator.getContentOverlayLeash(),
                                animator::clearContentOverlay, true /* withStartDelay*/);
                    }
                    onFinishResize(taskInfo, animator.getDestinationBounds(), direction, tx);
                    sendOnPipTransitionFinished(direction);
                }

                @Override
                public void onPipAnimationCancel(TaskInfo taskInfo,
                        PipAnimationController.PipTransitionAnimator animator) {
                    final int direction = animator.getTransitionDirection();
                    if (isInPipDirection(direction) && animator.getContentOverlayLeash() != null) {
                        mPipOrganizer.fadeOutAndRemoveOverlay(animator.getContentOverlayLeash(),
                                animator::clearContentOverlay, true /* withStartDelay */);
                    }
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

    /**
     * Called to inform the transition that the animation should start with the assumption that
     * PiP is not animating from its original bounds, but rather a continuation of another
     * animation. For example, gesture navigation would first fade out the PiP activity, and the
     * transition should be responsible to animate in (such as fade in) the PiP.
     */
    public void setIsFullAnimation(boolean isFullAnimation) {
    }

    /**
     * Called when the Shell wants to start an exit Pip transition/animation.
     */
    public void startExitTransition(int type, WindowContainerTransaction out,
            @Nullable Rect destinationBounds) {
        // Default implementation does nothing.
    }

    /**
     * Called when the transition animation can't continue (eg. task is removed during
     * animation)
     */
    public void forceFinishTransition() {
    }

    /** Called when the fixed rotation started. */
    public void onFixedRotationStarted() {
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
        mTransitions = transitions;
        mMainHandler = new Handler(Looper.getMainLooper());
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            transitions.addHandler(this);
        }
    }

    void setPipOrganizer(PipTaskOrganizer pto) {
        mPipOrganizer = pto;
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
        mPipBoundsState.setBoundsStateForEntry(componentName, activityInfo, params,
                mPipBoundsAlgorithm);
    }

    /**
     * Called when the display is going to rotate.
     *
     * @return {@code true} if it was handled, otherwise the existing pip logic
     *                      will deal with rotation.
     */
    public boolean handleRotateDisplay(int startRotation, int endRotation,
            WindowContainerTransaction wct) {
        return false;
    }

    /** @return whether the transition-request represents a pip-entry. */
    public boolean requestHasPipEnter(@NonNull TransitionRequestInfo request) {
        return request.getType() == TRANSIT_PIP;
    }

    /** Whether a particular change is a window that is entering pip. */
    public boolean isEnteringPip(@NonNull TransitionInfo.Change change,
            @WindowManager.TransitionType int transitType) {
        return false;
    }

    /** Add PiP-related changes to `outWCT` for the given request. */
    public void augmentRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request, @NonNull WindowContainerTransaction outWCT) {
        throw new IllegalStateException("Request isn't entering PiP");
    }

    /** Play a transition animation for entering PiP on a specific PiP change. */
    public void startEnterAnimation(@NonNull final TransitionInfo.Change pipChange,
            @NonNull final SurfaceControl.Transaction startTransaction,
            @NonNull final SurfaceControl.Transaction finishTransaction,
            @NonNull final Transitions.TransitionFinishCallback finishCallback) {
    }

    /** End the currently-playing PiP animation. */
    public void end() {
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
