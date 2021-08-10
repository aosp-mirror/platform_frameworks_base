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

import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.util.RotationUtils.deltaRotation;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_PIP;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;

import static com.android.wm.shell.pip.PipAnimationController.ANIM_TYPE_ALPHA;
import static com.android.wm.shell.pip.PipAnimationController.ANIM_TYPE_BOUNDS;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_LEAVE_PIP;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_TO_PIP;
import static com.android.wm.shell.pip.PipAnimationController.isInPipDirection;
import static com.android.wm.shell.pip.PipAnimationController.isOutPipDirection;
import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_REMOVE_PIP;

import android.app.TaskInfo;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;
import android.window.WindowContainerTransactionCallback;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.transition.Transitions;

/**
 * Implementation of transitions for PiP on phone. Responsible for enter (alpha, bounds) and
 * exit animation.
 */
public class PipTransition extends PipTransitionController {

    private final PipTransitionState mPipTransitionState;
    private final int mEnterExitAnimationDuration;
    private @PipAnimationController.AnimationType int mOneShotAnimationType = ANIM_TYPE_BOUNDS;
    private Transitions.TransitionFinishCallback mFinishCallback;
    private Rect mExitDestinationBounds = new Rect();

    public PipTransition(Context context,
            PipBoundsState pipBoundsState,
            PipTransitionState pipTransitionState,
            PipMenuController pipMenuController,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipAnimationController pipAnimationController,
            Transitions transitions,
            @NonNull ShellTaskOrganizer shellTaskOrganizer) {
        super(pipBoundsState, pipMenuController, pipBoundsAlgorithm,
                pipAnimationController, transitions, shellTaskOrganizer);
        mPipTransitionState = pipTransitionState;
        mEnterExitAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipResizeAnimationDuration);
    }

    @Override
    public void setIsFullAnimation(boolean isFullAnimation) {
        setOneShotAnimationType(isFullAnimation ? ANIM_TYPE_BOUNDS : ANIM_TYPE_ALPHA);
    }

    /**
     * Sets the preferred animation type for one time.
     * This is typically used to set the animation type to
     * {@link PipAnimationController#ANIM_TYPE_ALPHA}.
     */
    private void setOneShotAnimationType(@PipAnimationController.AnimationType int animationType) {
        mOneShotAnimationType = animationType;
    }

    @Override
    public void startTransition(Rect destinationBounds, WindowContainerTransaction out) {
        if (destinationBounds != null) {
            mExitDestinationBounds.set(destinationBounds);
            mTransitions.startTransition(TRANSIT_EXIT_PIP, out, this);
        } else {
            mTransitions.startTransition(TRANSIT_REMOVE_PIP, out, this);
        }
    }

    @Override
    public boolean startAnimation(@android.annotation.NonNull IBinder transition,
            @android.annotation.NonNull TransitionInfo info,
            @android.annotation.NonNull SurfaceControl.Transaction startTransaction,
            @android.annotation.NonNull SurfaceControl.Transaction finishTransaction,
            @android.annotation.NonNull Transitions.TransitionFinishCallback finishCallback) {

        if (info.getType() == TRANSIT_EXIT_PIP && info.getChanges().size() == 1) {
            final TransitionInfo.Change change = info.getChanges().get(0);
            mFinishCallback = finishCallback;
            startTransaction.apply();
            boolean success = startExpandAnimation(change.getTaskInfo(), change.getLeash(),
                    new Rect(mExitDestinationBounds));
            mExitDestinationBounds.setEmpty();
            return success;
        }

        if (info.getType() == TRANSIT_REMOVE_PIP) {
            startTransaction.apply();
            finishTransaction.setWindowCrop(info.getChanges().get(0).getLeash(),
                    mPipBoundsState.getDisplayBounds());
            finishCallback.onTransitionFinished(null, null);
            return true;
        }

        // We only support TRANSIT_PIP type (from RootWindowContainer) or TRANSIT_OPEN (from apps
        // that enter PiP instantly on opening, mostly from CTS/Flicker tests)
        if (info.getType() != TRANSIT_PIP && info.getType() != TRANSIT_OPEN) {
            return false;
        }

        // Search for an Enter PiP transition (along with a show wallpaper one)
        TransitionInfo.Change enterPip = null;
        TransitionInfo.Change wallpaper = null;
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (change.getTaskInfo() != null
                    && change.getTaskInfo().configuration.windowConfiguration.getWindowingMode()
                    == WINDOWING_MODE_PINNED) {
                enterPip = change;
            } else if ((change.getFlags() & FLAG_IS_WALLPAPER) != 0) {
                wallpaper = change;
            }
        }
        if (enterPip == null) {
            return false;
        }

        // Show the wallpaper if there is a wallpaper change.
        if (wallpaper != null) {
            startTransaction.show(wallpaper.getLeash());
            startTransaction.setAlpha(wallpaper.getLeash(), 1.f);
        }

        mPipTransitionState.setTransitionState(PipTransitionState.ENTERING_PIP);
        mFinishCallback = finishCallback;
        return startEnterAnimation(enterPip.getTaskInfo(), enterPip.getLeash(),
                startTransaction, finishTransaction, enterPip.getStartRotation(),
                enterPip.getEndRotation());
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        if (request.getType() == TRANSIT_PIP) {
            WindowContainerTransaction wct = new WindowContainerTransaction();
            mPipTransitionState.setTransitionState(PipTransitionState.ENTRY_SCHEDULED);
            final Rect destinationBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
            wct.setActivityWindowingMode(request.getTriggerTask().token, WINDOWING_MODE_UNDEFINED);
            wct.setBounds(request.getTriggerTask().token, destinationBounds);
            return wct;
        } else {
            return null;
        }
    }

    @Override
    public void onFinishResize(TaskInfo taskInfo, Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction,
            SurfaceControl.Transaction tx) {

        if (isInPipDirection(direction)) {
            mPipTransitionState.setTransitionState(PipTransitionState.ENTERED_PIP);
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        prepareFinishResizeTransaction(taskInfo, destinationBounds,
                direction, tx, wct);
        mFinishCallback.onTransitionFinished(wct, new WindowContainerTransactionCallback() {
            @Override
            public void onTransactionReady(int id, @NonNull SurfaceControl.Transaction t) {
                t.merge(tx);
                t.apply();
            }
        });
        finishResizeForMenu(destinationBounds);
    }

    private boolean startExpandAnimation(final TaskInfo taskInfo, final SurfaceControl leash,
            final Rect destinationBounds) {
        PipAnimationController.PipTransitionAnimator animator =
                mPipAnimationController.getAnimator(taskInfo, leash, mPipBoundsState.getBounds(),
                        mPipBoundsState.getBounds(), destinationBounds, null,
                        TRANSITION_DIRECTION_LEAVE_PIP, 0 /* startingAngle */, Surface.ROTATION_0);

        animator.setTransitionDirection(TRANSITION_DIRECTION_LEAVE_PIP)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setDuration(mEnterExitAnimationDuration)
                .start();

        return true;
    }

    private boolean startEnterAnimation(final TaskInfo taskInfo, final SurfaceControl leash,
            final SurfaceControl.Transaction startTransaction,
            final SurfaceControl.Transaction finishTransaction,
            final int startRotation, final int endRotation) {
        setBoundsStateForEntry(taskInfo.topActivity, taskInfo.pictureInPictureParams,
                taskInfo.topActivityInfo);
        final Rect destinationBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
        final Rect currentBounds = taskInfo.configuration.windowConfiguration.getBounds();
        PipAnimationController.PipTransitionAnimator animator;
        finishTransaction.setPosition(leash, destinationBounds.left, destinationBounds.top);
        if (taskInfo.pictureInPictureParams != null
                && taskInfo.pictureInPictureParams.isAutoEnterEnabled()
                && mPipTransitionState.getInSwipePipToHomeTransition()) {
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;

            // PiP menu is attached late in the process here to avoid any artifacts on the leash
            // caused by addShellRoot when in gesture navigation mode.
            mPipMenuController.attach(leash);
            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
            tx.setMatrix(leash, Matrix.IDENTITY_MATRIX, new float[9])
                    .setPosition(leash, destinationBounds.left, destinationBounds.top)
                    .setWindowCrop(leash, destinationBounds.width(), destinationBounds.height());
            startTransaction.merge(tx);
            startTransaction.apply();
            mPipBoundsState.setBounds(destinationBounds);
            onFinishResize(taskInfo, destinationBounds, TRANSITION_DIRECTION_TO_PIP, tx);
            sendOnPipTransitionFinished(TRANSITION_DIRECTION_TO_PIP);
            mFinishCallback = null;
            return true;
        }

        int rotationDelta = deltaRotation(endRotation, startRotation);
        if (rotationDelta != Surface.ROTATION_0) {
            Matrix tmpTransform = new Matrix();
            tmpTransform.postRotate(rotationDelta == Surface.ROTATION_90
                    ? Surface.ROTATION_270 : Surface.ROTATION_90);
            startTransaction.setMatrix(leash, tmpTransform, new float[9]);
        }
        if (mOneShotAnimationType == ANIM_TYPE_BOUNDS) {
            final Rect sourceHintRect =
                    PipBoundsAlgorithm.getValidSourceHintRect(
                            taskInfo.pictureInPictureParams, currentBounds);
            animator = mPipAnimationController.getAnimator(taskInfo, leash, currentBounds,
                    currentBounds, destinationBounds, sourceHintRect, TRANSITION_DIRECTION_TO_PIP,
                    0 /* startingAngle */, rotationDelta);
        } else if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            startTransaction.setAlpha(leash, 0f);
            // PiP menu is attached late in the process here to avoid any artifacts on the leash
            // caused by addShellRoot when in gesture navigation mode.
            mPipMenuController.attach(leash);
            animator = mPipAnimationController.getAnimator(taskInfo, leash, destinationBounds,
                    0f, 1f);
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;
        } else {
            throw new RuntimeException("Unrecognized animation type: "
                    + mOneShotAnimationType);
        }
        startTransaction.apply();
        animator.setTransitionDirection(TRANSITION_DIRECTION_TO_PIP)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setDuration(mEnterExitAnimationDuration)
                .start();

        return true;
    }

    private void finishResizeForMenu(Rect destinationBounds) {
        mPipMenuController.movePipMenu(null, null, destinationBounds);
        mPipMenuController.updateMenuBounds(destinationBounds);
    }

    private void prepareFinishResizeTransaction(TaskInfo taskInfo, Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction,
            SurfaceControl.Transaction tx,
            WindowContainerTransaction wct) {
        Rect taskBounds = null;
        if (isInPipDirection(direction)) {
            // If we are animating from fullscreen using a bounds animation, then reset the
            // activity windowing mode set by WM, and set the task bounds to the final bounds
            taskBounds = destinationBounds;
            wct.setActivityWindowingMode(taskInfo.token, WINDOWING_MODE_UNDEFINED);
            wct.scheduleFinishEnterPip(taskInfo.token, destinationBounds);
        } else if (isOutPipDirection(direction)) {
            // If we are animating to fullscreen, then we need to reset the override bounds
            // on the task to ensure that the task "matches" the parent's bounds.
            taskBounds = (direction == TRANSITION_DIRECTION_LEAVE_PIP)
                    ? null : destinationBounds;
            wct.setWindowingMode(taskInfo.token, getOutPipWindowingMode());
            // Simply reset the activity mode set prior to the animation running.
            wct.setActivityWindowingMode(taskInfo.token, WINDOWING_MODE_UNDEFINED);
        }

        wct.setBounds(taskInfo.token, taskBounds);
    }
}
