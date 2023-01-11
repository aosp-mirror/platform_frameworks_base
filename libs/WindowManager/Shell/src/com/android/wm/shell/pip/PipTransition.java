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

import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.util.RotationUtils.deltaRotation;
import static android.util.RotationUtils.rotateBounds;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_PIP;
import static android.view.WindowManager.transitTypeToString;
import static android.window.TransitionInfo.FLAG_IS_DISPLAY;

import static com.android.wm.shell.pip.PipAnimationController.ANIM_TYPE_ALPHA;
import static com.android.wm.shell.pip.PipAnimationController.ANIM_TYPE_BOUNDS;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_LEAVE_PIP;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_SAME;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_TO_PIP;
import static com.android.wm.shell.pip.PipAnimationController.isInPipDirection;
import static com.android.wm.shell.pip.PipAnimationController.isOutPipDirection;
import static com.android.wm.shell.pip.PipTransitionState.ENTERED_PIP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP_TO_SPLIT;
import static com.android.wm.shell.transition.Transitions.TRANSIT_REMOVE_PIP;
import static com.android.wm.shell.transition.Transitions.isOpeningType;

import android.animation.Animator;
import android.app.ActivityManager;
import android.app.TaskInfo;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.CounterRotatorHelper;
import com.android.wm.shell.transition.Transitions;

import java.util.Optional;

/**
 * Implementation of transitions for PiP on phone. Responsible for enter (alpha, bounds) and
 * exit animation.
 */
public class PipTransition extends PipTransitionController {

    private static final String TAG = PipTransition.class.getSimpleName();

    private final Context mContext;
    private final PipTransitionState mPipTransitionState;
    private final int mEnterExitAnimationDuration;
    private final PipSurfaceTransactionHelper mSurfaceTransactionHelper;
    private final Optional<SplitScreenController> mSplitScreenOptional;
    private @PipAnimationController.AnimationType int mOneShotAnimationType = ANIM_TYPE_BOUNDS;
    private Transitions.TransitionFinishCallback mFinishCallback;
    private SurfaceControl.Transaction mFinishTransaction;
    private final Rect mExitDestinationBounds = new Rect();
    @Nullable
    private IBinder mExitTransition;
    private IBinder mRequestedEnterTransition;
    private WindowContainerToken mRequestedEnterTask;
    /** The Task window that is currently in PIP windowing mode. */
    @Nullable
    private WindowContainerToken mCurrentPipTaskToken;
    /** Whether display is in fixed rotation. */
    private boolean mInFixedRotation;
    /**
     * The rotation that the display will apply after expanding PiP to fullscreen. This is only
     * meaningful if {@link #mInFixedRotation} is true.
     */
    @Surface.Rotation
    private int mEndFixedRotation;
    /** Whether the PIP window has fade out for fixed rotation. */
    private boolean mHasFadeOut;

    public PipTransition(Context context,
            @NonNull ShellInit shellInit,
            @NonNull ShellTaskOrganizer shellTaskOrganizer,
            @NonNull Transitions transitions,
            PipBoundsState pipBoundsState,
            PipTransitionState pipTransitionState,
            PipMenuController pipMenuController,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipAnimationController pipAnimationController,
            PipSurfaceTransactionHelper pipSurfaceTransactionHelper,
            Optional<SplitScreenController> splitScreenOptional) {
        super(shellInit, shellTaskOrganizer, transitions, pipBoundsState, pipMenuController,
                pipBoundsAlgorithm, pipAnimationController);
        mContext = context;
        mPipTransitionState = pipTransitionState;
        mEnterExitAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipResizeAnimationDuration);
        mSurfaceTransactionHelper = pipSurfaceTransactionHelper;
        mSplitScreenOptional = splitScreenOptional;
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
    public void startExitTransition(int type, WindowContainerTransaction out,
            @Nullable Rect destinationBounds) {
        if (destinationBounds != null) {
            mExitDestinationBounds.set(destinationBounds);
        }
        final PipAnimationController.PipTransitionAnimator animator =
                mPipAnimationController.getCurrentAnimator();
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
        mExitTransition = mTransitions.startTransition(type, out, this);
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        final TransitionInfo.Change currentPipTaskChange = findCurrentPipTaskChange(info);
        final TransitionInfo.Change fixedRotationChange = findFixedRotationChange(info);
        mInFixedRotation = fixedRotationChange != null;
        mEndFixedRotation = mInFixedRotation
                ? fixedRotationChange.getEndFixedRotation()
                : ROTATION_UNDEFINED;

        // Exiting PIP.
        final int type = info.getType();
        if (transition.equals(mExitTransition)) {
            mExitDestinationBounds.setEmpty();
            mExitTransition = null;
            mHasFadeOut = false;
            if (mFinishCallback != null) {
                callFinishCallback(null /* wct */);
                mFinishTransaction = null;
                throw new RuntimeException("Previous callback not called, aborting exit PIP.");
            }

            // PipTaskChange can be null if the PIP task has been detached, for example, when the
            // task contains multiple activities, the PIP will be moved to a new PIP task when
            // entering, and be moved back when exiting. In that case, the PIP task will be removed
            // immediately.
            final TaskInfo pipTaskInfo = currentPipTaskChange != null
                    ? currentPipTaskChange.getTaskInfo()
                    : mPipOrganizer.getTaskInfo();
            if (pipTaskInfo == null) {
                throw new RuntimeException("Cannot find the pip task for exit-pip transition.");
            }

            switch (type) {
                case TRANSIT_EXIT_PIP:
                    startExitAnimation(info, startTransaction, finishTransaction, finishCallback,
                            pipTaskInfo, currentPipTaskChange);
                    break;
                case TRANSIT_EXIT_PIP_TO_SPLIT:
                    startExitToSplitAnimation(info, startTransaction, finishTransaction,
                            finishCallback, pipTaskInfo);
                    break;
                case TRANSIT_REMOVE_PIP:
                    removePipImmediately(info, startTransaction, finishTransaction, finishCallback,
                            pipTaskInfo);
                    break;
                default:
                    throw new IllegalStateException("mExitTransition with unexpected transit type="
                            + transitTypeToString(type));
            }
            mCurrentPipTaskToken = null;
            return true;
        } else if (transition == mRequestedEnterTransition) {
            mRequestedEnterTransition = null;
            mRequestedEnterTask = null;
        }

        // The previous PIP Task is no longer in PIP, but this is not an exit transition (This can
        // happen when a new activity requests enter PIP). In this case, we just show this Task in
        // its end state, and play other animation as normal.
        if (currentPipTaskChange != null
                && currentPipTaskChange.getTaskInfo().getWindowingMode() != WINDOWING_MODE_PINNED) {
            resetPrevPip(currentPipTaskChange, startTransaction);
        }

        // Entering PIP.
        if (isEnteringPip(info)) {
            startEnterAnimation(info, startTransaction, finishTransaction, finishCallback);
            return true;
        }

        // For transition that we don't animate, but contains the PIP leash, we need to update the
        // PIP surface, otherwise it will be reset after the transition.
        if (currentPipTaskChange != null) {
            // Set the "end" bounds of pip. The default setup uses the start bounds. Since this is
            // changing the *finish*Transaction, we need to use the end bounds. This will also
            // make sure that the fade-in animation (below) uses the end bounds as well.
            if (!currentPipTaskChange.getEndAbsBounds().isEmpty()) {
                mPipBoundsState.setBounds(currentPipTaskChange.getEndAbsBounds());
            }
            updatePipForUnhandledTransition(currentPipTaskChange, startTransaction,
                    finishTransaction);
        }

        // Fade in the fadeout PIP when the fixed rotation is finished.
        if (mPipTransitionState.isInPip() && !mInFixedRotation && mHasFadeOut) {
            fadeExistingPip(true /* show */);
        }

        return false;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        end();
    }

    /** Helper to identify whether this handler is currently the one playing an animation */
    private boolean isAnimatingLocally() {
        return mFinishTransaction != null;
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        if (requestHasPipEnter(request)) {
            WindowContainerTransaction wct = new WindowContainerTransaction();
            augmentRequest(transition, request, wct);
            return wct;
        } else {
            return null;
        }
    }

    @Override
    public void augmentRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request, @NonNull WindowContainerTransaction outWCT) {
        if (!requestHasPipEnter(request)) {
            throw new IllegalStateException("Called PiP augmentRequest when request has no PiP");
        }
        if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            mRequestedEnterTransition = transition;
            mRequestedEnterTask = request.getTriggerTask().token;
            outWCT.setActivityWindowingMode(request.getTriggerTask().token,
                    WINDOWING_MODE_UNDEFINED);
            final Rect destinationBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
            outWCT.setBounds(request.getTriggerTask().token, destinationBounds);
        }
    }

    @Override
    public void end() {
        Animator animator = mPipAnimationController.getCurrentAnimator();
        if (animator == null) return;
        animator.end();
    }

    @Override
    public boolean handleRotateDisplay(int startRotation, int endRotation,
            WindowContainerTransaction wct) {
        if (mRequestedEnterTransition != null && mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            // A fade-in was requested but not-yet started. In this case, just recalculate the
            // initial state under the new rotation.
            int rotationDelta = deltaRotation(startRotation, endRotation);
            if (rotationDelta != Surface.ROTATION_0) {
                mPipBoundsState.getDisplayLayout().rotateTo(mContext.getResources(), endRotation);
                final Rect destinationBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
                wct.setBounds(mRequestedEnterTask, destinationBounds);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
            @Nullable SurfaceControl.Transaction finishT) {
        if (transition != mExitTransition) {
            return;
        }
        // This means an expand happened before enter-pip finished and we are now "merging" a
        // no-op transition that happens to match our exit-pip.
        boolean cancelled = false;
        if (mPipAnimationController.getCurrentAnimator() != null) {
            mPipAnimationController.getCurrentAnimator().cancel();
            cancelled = true;
        }
        // Unset exitTransition AFTER cancel so that finishResize knows we are merging.
        mExitTransition = null;
        if (!cancelled || aborted) return;
        final ActivityManager.RunningTaskInfo taskInfo = mPipOrganizer.getTaskInfo();
        if (taskInfo != null) {
            startExpandAnimation(taskInfo, mPipOrganizer.getSurfaceControl(),
                    mPipBoundsState.getBounds(), mPipBoundsState.getBounds(),
                    new Rect(mExitDestinationBounds), Surface.ROTATION_0);
        }
        mExitDestinationBounds.setEmpty();
        mCurrentPipTaskToken = null;
    }

    @Override
    public void onFinishResize(TaskInfo taskInfo, Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction,
            @Nullable SurfaceControl.Transaction tx) {
        final boolean enteringPip = isInPipDirection(direction);
        if (enteringPip) {
            mPipTransitionState.setTransitionState(ENTERED_PIP);
        }
        // If we have an exit transition, but aren't playing a transition locally, it
        // means we're expecting the exit transition will be "merged" into another transition
        // (likely a remote like launcher), so don't fire the finish-callback here -- wait until
        // the exit transition is merged.
        if ((mExitTransition == null || isAnimatingLocally()) && mFinishCallback != null) {
            WindowContainerTransaction wct = null;
            if (isOutPipDirection(direction)) {
                // Only need to reset surface properties. The server-side operations were already
                // done at the start. But if it is running fixed rotation, there will be a seamless
                // display transition later. So the last rotation transform needs to be kept to
                // avoid flickering, and then the display transition will reset the transform.
                if (tx != null && !mInFixedRotation) {
                    mFinishTransaction.merge(tx);
                }
            } else {
                wct = new WindowContainerTransaction();
                if (isInPipDirection(direction)) {
                    // If we are animating from fullscreen using a bounds animation, then reset the
                    // activity windowing mode, and set the task bounds to the final bounds
                    wct.setActivityWindowingMode(taskInfo.token, WINDOWING_MODE_UNDEFINED);
                    wct.scheduleFinishEnterPip(taskInfo.token, destinationBounds);
                    wct.setBounds(taskInfo.token, destinationBounds);
                } else {
                    wct.setBounds(taskInfo.token, null /* bounds */);
                }
                if (tx != null) {
                    wct.setBoundsChangeTransaction(taskInfo.token, tx);
                }
            }
            final SurfaceControl leash = mPipOrganizer.getSurfaceControl();
            final int displayRotation = taskInfo.getConfiguration().windowConfiguration
                    .getDisplayRotation();
            if (enteringPip && mInFixedRotation && mEndFixedRotation != displayRotation
                    && leash != null && leash.isValid()) {
                // Launcher may update the Shelf height during the animation, which will update the
                // destination bounds. Because this is in fixed rotation, We need to make sure the
                // finishTransaction is using the updated bounds in the display rotation.
                final Rect displayBounds = mPipBoundsState.getDisplayBounds();
                final Rect finishBounds = new Rect(destinationBounds);
                rotateBounds(finishBounds, displayBounds, mEndFixedRotation, displayRotation);
                mSurfaceTransactionHelper.crop(mFinishTransaction, leash, finishBounds);
            }
            mFinishTransaction = null;
            callFinishCallback(wct);
        }
        finishResizeForMenu(destinationBounds);
    }

    private void callFinishCallback(WindowContainerTransaction wct) {
        // Need to unset mFinishCallback first because onTransitionFinished can re-enter this
        // handler if there is a pending PiP animation.
        final Transitions.TransitionFinishCallback finishCallback = mFinishCallback;
        mFinishCallback = null;
        finishCallback.onTransitionFinished(wct, null /* callback */);
    }

    @Override
    public void forceFinishTransition() {
        if (mFinishCallback == null) return;
        mFinishCallback.onTransitionFinished(null /* wct */, null /* callback */);
        mFinishCallback = null;
        mFinishTransaction = null;
    }

    @Override
    public void onFixedRotationStarted() {
        // The transition with this fixed rotation may be handled by other handler before reaching
        // PipTransition, so we cannot do this in #startAnimation.
        if (mPipTransitionState.getTransitionState() == ENTERED_PIP && !mHasFadeOut) {
            // Fade out the existing PiP to avoid jump cut during seamless rotation.
            fadeExistingPip(false /* show */);
        }
    }

    @Nullable
    private TransitionInfo.Change findCurrentPipTaskChange(@NonNull TransitionInfo info) {
        if (mCurrentPipTaskToken == null) {
            return null;
        }
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (mCurrentPipTaskToken.equals(change.getContainer())) {
                return change;
            }
        }
        return null;
    }

    @Nullable
    private TransitionInfo.Change findFixedRotationChange(@NonNull TransitionInfo info) {
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (change.getEndFixedRotation() != ROTATION_UNDEFINED) {
                return change;
            }
        }
        return null;
    }

    private void startExitAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback,
            @NonNull TaskInfo taskInfo, @Nullable TransitionInfo.Change pipTaskChange) {
        TransitionInfo.Change pipChange = pipTaskChange;
        if (mCurrentPipTaskToken == null) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: There is no existing PiP Task for TRANSIT_EXIT_PIP", TAG);
        } else if (pipChange == null) {
            // The pipTaskChange is null, this can happen if we are reparenting the PIP activity
            // back to its original Task. In that case, we should animate the activity leash
            // instead, which should be the change whose last parent is the recorded PiP Task.
            for (int i = info.getChanges().size() - 1; i >= 0; --i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                if (mCurrentPipTaskToken.equals(change.getLastParent())) {
                    // Find the activity that is exiting PiP.
                    pipChange = change;
                    break;
                }
            }
        }
        if (pipChange == null) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: No window of exiting PIP is found. Can't play expand animation", TAG);
            removePipImmediately(info, startTransaction, finishTransaction, finishCallback,
                    taskInfo);
            return;
        }

        // When exiting PiP, the PiP leash may be an Activity of a multi-windowing Task, for which
        // case it may not be in the screen coordinate.
        // Reparent the pip leash to the root with max layer so that we can animate it outside of
        // parent crop, and make sure it is not covered by other windows.
        final SurfaceControl pipLeash = pipChange.getLeash();
        startTransaction.reparent(pipLeash, info.getRootLeash());
        startTransaction.setLayer(pipLeash, Integer.MAX_VALUE);
        // Note: because of this, the bounds to animate should be translated to the root coordinate.
        final Point offset = info.getRootOffset();
        final Rect currentBounds = mPipBoundsState.getBounds();
        currentBounds.offset(-offset.x, -offset.y);
        startTransaction.setPosition(pipLeash, currentBounds.left, currentBounds.top);

        mFinishCallback = (wct, wctCB) -> {
            mPipOrganizer.onExitPipFinished(taskInfo);
            finishCallback.onTransitionFinished(wct, wctCB);
        };
        mFinishTransaction = finishTransaction;

        // Check if it is Shell rotation.
        if (Transitions.SHELL_TRANSITIONS_ROTATION) {
            TransitionInfo.Change displayRotationChange = null;
            for (int i = info.getChanges().size() - 1; i >= 0; --i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                if (change.getMode() == TRANSIT_CHANGE
                        && (change.getFlags() & FLAG_IS_DISPLAY) != 0
                        && change.getStartRotation() != change.getEndRotation()) {
                    displayRotationChange = change;
                    break;
                }
            }
            if (displayRotationChange != null) {
                // Exiting PIP to fullscreen with orientation change.
                startExpandAndRotationAnimation(info, startTransaction, finishTransaction,
                        displayRotationChange, taskInfo, pipChange, offset);
                return;
            }
        }

        // Set the initial frame as scaling the end to the start.
        final Rect destinationBounds = new Rect(pipChange.getEndAbsBounds());
        destinationBounds.offset(-offset.x, -offset.y);
        startTransaction.setWindowCrop(pipLeash, destinationBounds);
        mSurfaceTransactionHelper.scale(startTransaction, pipLeash, destinationBounds,
                currentBounds);
        startTransaction.apply();

        // Check if it is fixed rotation.
        final int rotationDelta;
        if (mInFixedRotation) {
            final int startRotation = pipChange.getStartRotation();
            final int endRotation = mEndFixedRotation;
            rotationDelta = deltaRotation(startRotation, endRotation);
            final Rect endBounds = new Rect(destinationBounds);

            // Set the end frame since the display won't rotate until fixed rotation is finished
            // in the next display change transition.
            rotateBounds(endBounds, destinationBounds, rotationDelta);
            final int degree, x, y;
            if (rotationDelta == ROTATION_90) {
                degree = 90;
                x = destinationBounds.right;
                y = destinationBounds.top;
            } else {
                degree = -90;
                x = destinationBounds.left;
                y = destinationBounds.bottom;
            }
            mSurfaceTransactionHelper.rotateAndScaleWithCrop(finishTransaction,
                    pipLeash, endBounds, endBounds, new Rect(), degree, x, y,
                    true /* isExpanding */, rotationDelta == ROTATION_270 /* clockwise */);
        } else {
            rotationDelta = Surface.ROTATION_0;
        }
        startExpandAnimation(taskInfo, pipLeash, currentBounds, currentBounds, destinationBounds,
                rotationDelta);
    }

    private void startExpandAndRotationAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull TransitionInfo.Change displayRotationChange,
            @NonNull TaskInfo taskInfo, @NonNull TransitionInfo.Change pipChange,
            @NonNull Point offset) {
        final int rotateDelta = deltaRotation(displayRotationChange.getStartRotation(),
                displayRotationChange.getEndRotation());

        // Counter-rotate all "going-away" things since they are still in the old orientation.
        final CounterRotatorHelper rotator = new CounterRotatorHelper();
        rotator.handleClosingChanges(info, startTransaction, displayRotationChange);

        // Get the start bounds in new orientation.
        final Rect startBounds = new Rect(pipChange.getStartAbsBounds());
        rotateBounds(startBounds, displayRotationChange.getStartAbsBounds(), rotateDelta);
        final Rect endBounds = new Rect(pipChange.getEndAbsBounds());
        startBounds.offset(-offset.x, -offset.y);
        endBounds.offset(-offset.x, -offset.y);

        // Reverse the rotation direction for expansion.
        final int pipRotateDelta = deltaRotation(rotateDelta, 0);

        // Set the start frame.
        final int degree, x, y;
        if (pipRotateDelta == ROTATION_90) {
            degree = 90;
            x = startBounds.right;
            y = startBounds.top;
        } else {
            degree = -90;
            x = startBounds.left;
            y = startBounds.bottom;
        }
        mSurfaceTransactionHelper.rotateAndScaleWithCrop(startTransaction, pipChange.getLeash(),
                endBounds, startBounds, new Rect(), degree, x, y, true /* isExpanding */,
                pipRotateDelta == ROTATION_270 /* clockwise */);
        startTransaction.apply();
        rotator.cleanUp(finishTransaction);

        // Expand and rotate the pip window to fullscreen.
        final PipAnimationController.PipTransitionAnimator animator =
                mPipAnimationController.getAnimator(taskInfo, pipChange.getLeash(),
                        startBounds, startBounds, endBounds, null, TRANSITION_DIRECTION_LEAVE_PIP,
                        0 /* startingAngle */, pipRotateDelta);
        animator.setTransitionDirection(TRANSITION_DIRECTION_LEAVE_PIP)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setDuration(mEnterExitAnimationDuration)
                .start();
    }

    private void startExpandAnimation(final TaskInfo taskInfo, final SurfaceControl leash,
            final Rect baseBounds, final Rect startBounds, final Rect endBounds,
            final int rotationDelta) {
        final PipAnimationController.PipTransitionAnimator animator =
                mPipAnimationController.getAnimator(taskInfo, leash, baseBounds, startBounds,
                        endBounds, null /* sourceHintRect */, TRANSITION_DIRECTION_LEAVE_PIP,
                        0 /* startingAngle */, rotationDelta);
        animator.setTransitionDirection(TRANSITION_DIRECTION_LEAVE_PIP)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setDuration(mEnterExitAnimationDuration)
                .start();
    }

    /** For {@link Transitions#TRANSIT_REMOVE_PIP}, we just immediately remove the PIP Task. */
    private void removePipImmediately(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback,
            @NonNull TaskInfo taskInfo) {
        startTransaction.apply();
        finishTransaction.setWindowCrop(info.getChanges().get(0).getLeash(),
                mPipBoundsState.getDisplayBounds());
        mPipOrganizer.onExitPipFinished(taskInfo);
        finishCallback.onTransitionFinished(null, null);
    }

    /** Whether we should handle the given {@link TransitionInfo} animation as entering PIP. */
    private boolean isEnteringPip(@NonNull TransitionInfo info) {
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (isEnteringPip(change, info.getType())) return true;
        }
        return false;
    }

    /** Whether a particular change is a window that is entering pip. */
    @Override
    public boolean isEnteringPip(@NonNull TransitionInfo.Change change,
            @WindowManager.TransitionType int transitType) {
        if (change.getTaskInfo() != null
                && change.getTaskInfo().getWindowingMode() == WINDOWING_MODE_PINNED
                && !change.getContainer().equals(mCurrentPipTaskToken)) {
            // We support TRANSIT_PIP type (from RootWindowContainer) or TRANSIT_OPEN (from apps
            // that enter PiP instantly on opening, mostly from CTS/Flicker tests)
            if (transitType == TRANSIT_PIP || transitType == TRANSIT_OPEN) {
                return true;
            }
            // This can happen if the request to enter PIP happens when we are collecting for
            // another transition, such as TRANSIT_CHANGE (display rotation).
            if (transitType == TRANSIT_CHANGE) {
                return true;
            }

            // Please file a bug to handle the unexpected transition type.
            android.util.Slog.e(TAG, "Found new PIP in transition with mis-matched type="
                    + transitTypeToString(transitType), new Throwable());
        }
        return false;
    }

    private void startEnterAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        // Search for an Enter PiP transition
        TransitionInfo.Change enterPip = null;
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (change.getTaskInfo() != null
                    && change.getTaskInfo().getWindowingMode() == WINDOWING_MODE_PINNED) {
                enterPip = change;
            }
        }
        if (enterPip == null) {
            throw new IllegalStateException("Trying to start PiP animation without a pip"
                    + "participant");
        }

        // Make sure other open changes are visible as entering PIP. Some may be hidden in
        // Transitions#setupStartState because the transition type is OPEN (such as auto-enter).
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (change == enterPip) continue;
            if (isOpeningType(change.getMode())) {
                final SurfaceControl leash = change.getLeash();
                startTransaction.show(leash).setAlpha(leash, 1.f);
            }
        }

        startEnterAnimation(enterPip, startTransaction, finishTransaction, finishCallback);
    }

    @Override
    public void startEnterAnimation(@NonNull final TransitionInfo.Change pipChange,
            @NonNull final SurfaceControl.Transaction startTransaction,
            @NonNull final SurfaceControl.Transaction finishTransaction,
            @NonNull final Transitions.TransitionFinishCallback finishCallback) {
        if (mFinishCallback != null) {
            callFinishCallback(null /* wct */);
            mFinishTransaction = null;
            throw new RuntimeException("Previous callback not called, aborting entering PIP.");
        }

        // Keep track of the PIP task and animation.
        mCurrentPipTaskToken = pipChange.getContainer();
        mHasFadeOut = false;
        mPipTransitionState.setTransitionState(PipTransitionState.ENTERING_PIP);
        mFinishCallback = finishCallback;
        mFinishTransaction = finishTransaction;

        final ActivityManager.RunningTaskInfo taskInfo = pipChange.getTaskInfo();
        final SurfaceControl leash = pipChange.getLeash();
        final int startRotation = pipChange.getStartRotation();
        final int endRotation = mInFixedRotation ? mEndFixedRotation : pipChange.getEndRotation();

        setBoundsStateForEntry(taskInfo.topActivity, taskInfo.pictureInPictureParams,
                taskInfo.topActivityInfo);
        final Rect destinationBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
        final Rect currentBounds = taskInfo.configuration.windowConfiguration.getBounds();
        int rotationDelta = deltaRotation(startRotation, endRotation);
        Rect sourceHintRect = PipBoundsAlgorithm.getValidSourceHintRect(
                taskInfo.pictureInPictureParams, currentBounds);
        if (rotationDelta != Surface.ROTATION_0 && mInFixedRotation) {
            // Need to get the bounds of new rotation in old rotation for fixed rotation,
            computeEnterPipRotatedBounds(rotationDelta, startRotation, endRotation, taskInfo,
                    destinationBounds, sourceHintRect);
        }
        // Set corner radius for entering pip.
        mSurfaceTransactionHelper
                .crop(finishTransaction, leash, destinationBounds)
                .round(finishTransaction, leash, true /* applyCornerRadius */);
        mTransitions.getMainExecutor().executeDelayed(() -> mPipMenuController.attach(leash), 0);

        if (taskInfo.pictureInPictureParams != null
                && taskInfo.pictureInPictureParams.isAutoEnterEnabled()
                && mPipTransitionState.getInSwipePipToHomeTransition()) {
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;
            final SurfaceControl swipePipToHomeOverlay = mPipOrganizer.mSwipePipToHomeOverlay;
            startTransaction.setMatrix(leash, Matrix.IDENTITY_MATRIX, new float[9])
                    .setPosition(leash, destinationBounds.left, destinationBounds.top)
                    .setWindowCrop(leash, destinationBounds.width(), destinationBounds.height());
            if (swipePipToHomeOverlay != null) {
                // Launcher fade in the overlay on top of the fullscreen Task. It is possible we
                // reparent the PIP activity to a new PIP task (in case there are other activities
                // in the original Task), so we should also reparent the overlay to the PIP task.
                startTransaction.reparent(swipePipToHomeOverlay, leash)
                        .setLayer(swipePipToHomeOverlay, Integer.MAX_VALUE);
                mPipOrganizer.mSwipePipToHomeOverlay = null;
            }
            startTransaction.apply();
            if (rotationDelta != Surface.ROTATION_0 && mInFixedRotation) {
                // For fixed rotation, set the destination bounds to the new rotation coordinates
                // at the end.
                destinationBounds.set(mPipBoundsAlgorithm.getEntryDestinationBounds());
            }
            mPipBoundsState.setBounds(destinationBounds);
            onFinishResize(taskInfo, destinationBounds, TRANSITION_DIRECTION_TO_PIP, null /* tx */);
            sendOnPipTransitionFinished(TRANSITION_DIRECTION_TO_PIP);
            if (swipePipToHomeOverlay != null) {
                mPipOrganizer.fadeOutAndRemoveOverlay(swipePipToHomeOverlay,
                        null /* callback */, false /* withStartDelay */);
            }
            mPipTransitionState.setInSwipePipToHomeTransition(false);
            return;
        }

        if (rotationDelta != Surface.ROTATION_0) {
            Matrix tmpTransform = new Matrix();
            tmpTransform.postRotate(rotationDelta);
            startTransaction.setMatrix(leash, tmpTransform, new float[9]);
        }
        if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            startTransaction.setAlpha(leash, 0f);
        }
        startTransaction.apply();

        PipAnimationController.PipTransitionAnimator animator;
        if (mOneShotAnimationType == ANIM_TYPE_BOUNDS) {
            animator = mPipAnimationController.getAnimator(taskInfo, leash, currentBounds,
                    currentBounds, destinationBounds, sourceHintRect, TRANSITION_DIRECTION_TO_PIP,
                    0 /* startingAngle */, rotationDelta);
            if (sourceHintRect == null) {
                // We use content overlay when there is no source rect hint to enter PiP use bounds
                // animation.
                animator.setColorContentOverlay(mContext);
            }
        } else if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            animator = mPipAnimationController.getAnimator(taskInfo, leash, destinationBounds,
                    0f, 1f);
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;
        } else {
            throw new RuntimeException("Unrecognized animation type: "
                    + mOneShotAnimationType);
        }
        animator.setTransitionDirection(TRANSITION_DIRECTION_TO_PIP)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setDuration(mEnterExitAnimationDuration);
        if (rotationDelta != Surface.ROTATION_0 && mInFixedRotation) {
            // For fixed rotation, the animation destination bounds is in old rotation coordinates.
            // Set the destination bounds to new coordinates after the animation is finished.
            // ComputeRotatedBounds has changed the DisplayLayout without affecting the animation.
            animator.setDestinationBounds(mPipBoundsAlgorithm.getEntryDestinationBounds());
        }
        animator.start();
    }

    /** Computes destination bounds in old rotation and updates source hint rect if available. */
    private void computeEnterPipRotatedBounds(int rotationDelta, int startRotation, int endRotation,
            TaskInfo taskInfo, Rect outDestinationBounds, @Nullable Rect outSourceHintRect) {
        mPipBoundsState.getDisplayLayout().rotateTo(mContext.getResources(), endRotation);
        final Rect displayBounds = mPipBoundsState.getDisplayBounds();
        outDestinationBounds.set(mPipBoundsAlgorithm.getEntryDestinationBounds());
        // Transform the destination bounds to current display coordinates.
        rotateBounds(outDestinationBounds, displayBounds, endRotation, startRotation);
        // When entering PiP (from button navigation mode), adjust the source rect hint by
        // display cutout if applicable.
        if (outSourceHintRect != null && taskInfo.displayCutoutInsets != null) {
            if (rotationDelta == Surface.ROTATION_270) {
                outSourceHintRect.offset(taskInfo.displayCutoutInsets.left,
                        taskInfo.displayCutoutInsets.top);
            }
        }
    }

    private void startExitToSplitAnimation(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback,
            @NonNull TaskInfo taskInfo) {
        final int changeSize = info.getChanges().size();
        if (changeSize < 4) {
            throw new RuntimeException(
                    "Got an exit-pip-to-split transition with unexpected change-list");
        }
        for (int i = changeSize - 1; i >= 0; i--) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            final int mode = change.getMode();

            if (mode == TRANSIT_CHANGE && change.getParent() != null) {
                // TODO: perform resize/expand animation for reparented child task.
                continue;
            }

            if (isOpeningType(mode) && change.getParent() == null) {
                final SurfaceControl leash = change.getLeash();
                final Rect endBounds = change.getEndAbsBounds();
                startTransaction
                        .show(leash)
                        .setAlpha(leash, 1f)
                        .setPosition(leash, endBounds.left, endBounds.top)
                        .setWindowCrop(leash, endBounds.width(), endBounds.height());
            }
        }
        mSplitScreenOptional.get().finishEnterSplitScreen(startTransaction);
        startTransaction.apply();

        mPipOrganizer.onExitPipFinished(taskInfo);
        finishCallback.onTransitionFinished(null, null);
    }

    private void resetPrevPip(@NonNull TransitionInfo.Change prevPipTaskChange,
            @NonNull SurfaceControl.Transaction startTransaction) {
        final SurfaceControl leash = prevPipTaskChange.getLeash();
        final Rect bounds = prevPipTaskChange.getEndAbsBounds();
        final Point offset = prevPipTaskChange.getEndRelOffset();
        bounds.offset(-offset.x, -offset.y);

        startTransaction.setWindowCrop(leash, null);
        startTransaction.setMatrix(leash, 1, 0, 0, 1);
        startTransaction.setCornerRadius(leash, 0);
        startTransaction.setPosition(leash, bounds.left, bounds.top);

        if (mHasFadeOut && prevPipTaskChange.getTaskInfo().isVisible()) {
            if (mPipAnimationController.getCurrentAnimator() != null) {
                mPipAnimationController.getCurrentAnimator().cancel();
            }
            startTransaction.setAlpha(leash, 1);
        }
        mHasFadeOut = false;
        mCurrentPipTaskToken = null;
        mPipOrganizer.onExitPipFinished(prevPipTaskChange.getTaskInfo());
    }

    private void updatePipForUnhandledTransition(@NonNull TransitionInfo.Change pipChange,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        // When the PIP window is visible and being a part of the transition, such as display
        // rotation, we need to update its bounds and rounded corner.
        final SurfaceControl leash = pipChange.getLeash();
        final Rect destBounds = mPipBoundsState.getBounds();
        final boolean isInPip = mPipTransitionState.isInPip();
        mSurfaceTransactionHelper
                .crop(startTransaction, leash, destBounds)
                .round(startTransaction, leash, isInPip);
        mSurfaceTransactionHelper
                .crop(finishTransaction, leash, destBounds)
                .round(finishTransaction, leash, isInPip);
    }

    /** Hides and shows the existing PIP during fixed rotation transition of other activities. */
    private void fadeExistingPip(boolean show) {
        final SurfaceControl leash = mPipOrganizer.getSurfaceControl();
        final TaskInfo taskInfo = mPipOrganizer.getTaskInfo();
        if (leash == null || !leash.isValid() || taskInfo == null) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Invalid leash on fadeExistingPip: %s", TAG, leash);
            return;
        }
        final float alphaStart = show ? 0 : 1;
        final float alphaEnd = show ? 1 : 0;
        mPipAnimationController
                .getAnimator(taskInfo, leash, mPipBoundsState.getBounds(), alphaStart, alphaEnd)
                .setTransitionDirection(TRANSITION_DIRECTION_SAME)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setDuration(mEnterExitAnimationDuration)
                .start();
        mHasFadeOut = !show;
    }

    private void finishResizeForMenu(Rect destinationBounds) {
        mPipMenuController.movePipMenu(null, null, destinationBounds);
        mPipMenuController.updateMenuBounds(destinationBounds);
    }
}
