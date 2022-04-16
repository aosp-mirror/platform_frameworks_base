/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.util.RotationUtils.deltaRotation;
import static android.util.RotationUtils.rotateBounds;

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_PIP;
import static com.android.wm.shell.ShellTaskOrganizer.taskListenerTypeToString;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;
import static com.android.wm.shell.pip.PipAnimationController.ANIM_TYPE_ALPHA;
import static com.android.wm.shell.pip.PipAnimationController.ANIM_TYPE_BOUNDS;
import static com.android.wm.shell.pip.PipAnimationController.FRACTION_START;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_EXPAND_OR_UNEXPAND;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_LEAVE_PIP;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_NONE;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_REMOVE_STACK;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_SAME;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_SNAP_AFTER_RESIZE;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_TO_PIP;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_USER_RESIZE;
import static com.android.wm.shell.pip.PipAnimationController.isInPipDirection;
import static com.android.wm.shell.pip.PipAnimationController.isOutPipDirection;
import static com.android.wm.shell.pip.PipAnimationController.isRemovePipDirection;
import static com.android.wm.shell.transition.Transitions.ENABLE_SHELL_TRANSITIONS;
import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP_TO_SPLIT;
import static com.android.wm.shell.transition.Transitions.TRANSIT_REMOVE_PIP;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.PictureInPictureParams;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Rational;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.TaskOrganizer;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.animation.Interpolators;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ScreenshotUtils;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.pip.phone.PipMotionHelper;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.splitscreen.SplitScreenController;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Manages PiP tasks such as resize and offset.
 *
 * This class listens on {@link TaskOrganizer} callbacks for windowing mode change
 * both to and from PiP and issues corresponding animation if applicable.
 * Normally, we apply series of {@link SurfaceControl.Transaction} when the animator is running
 * and files a final {@link WindowContainerTransaction} at the end of the transition.
 *
 * This class is also responsible for general resize/offset PiP operations within SysUI component,
 * see also {@link PipMotionHelper}.
 */
public class PipTaskOrganizer implements ShellTaskOrganizer.TaskListener,
        DisplayController.OnDisplaysChangedListener, ShellTaskOrganizer.FocusListener {
    private static final String TAG = PipTaskOrganizer.class.getSimpleName();
    private static final boolean DEBUG = false;
    /**
     * The alpha type is set for swiping to home. But the swiped task may not enter PiP. And if
     * another task enters PiP by non-swipe ways, e.g. call API in foreground or switch to 3-button
     * navigation, then the alpha type is unexpected.
     */
    private static final int ONE_SHOT_ALPHA_ANIMATION_TIMEOUT_MS = 1000;

    /**
     * The fixed start delay in ms when fading out the content overlay from bounds animation.
     * This is to overcome the flicker caused by configuration change when rotating from landscape
     * to portrait PiP in button navigation mode.
     */
    private static final int CONTENT_OVERLAY_FADE_OUT_DELAY_MS = 500;

    private final Context mContext;
    private final SyncTransactionQueue mSyncTransactionQueue;
    private final PipBoundsState mPipBoundsState;
    private final PipBoundsAlgorithm mPipBoundsAlgorithm;
    private final @NonNull PipMenuController mPipMenuController;
    private final PipAnimationController mPipAnimationController;
    private final PipTransitionController mPipTransitionController;
    private final PipUiEventLogger mPipUiEventLoggerLogger;
    private final int mEnterAnimationDuration;
    private final int mExitAnimationDuration;
    private final int mCrossFadeAnimationDuration;
    private final PipSurfaceTransactionHelper mSurfaceTransactionHelper;
    private final Optional<SplitScreenController> mSplitScreenOptional;
    protected final ShellTaskOrganizer mTaskOrganizer;
    protected final ShellExecutor mMainExecutor;

    // These callbacks are called on the update thread
    private final PipAnimationController.PipAnimationCallback mPipAnimationCallback =
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
            final int animationType = animator.getAnimationType();
            final Rect destinationBounds = animator.getDestinationBounds();
            if (isInPipDirection(direction) && animator.getContentOverlay() != null) {
                fadeOutAndRemoveOverlay(animator.getContentOverlay(),
                        animator::clearContentOverlay, true /* withStartDelay*/);
            }
            if (mWaitForFixedRotation && animationType == ANIM_TYPE_BOUNDS
                    && direction == TRANSITION_DIRECTION_TO_PIP) {
                // Notify the display to continue the deferred orientation change.
                final WindowContainerTransaction wct = new WindowContainerTransaction();
                wct.scheduleFinishEnterPip(mToken, destinationBounds);
                mTaskOrganizer.applyTransaction(wct);
                // The final task bounds will be applied by onFixedRotationFinished so that all
                // coordinates are in new rotation.
                mSurfaceTransactionHelper.round(tx, mLeash, isInPip());
                mDeferredAnimEndTransaction = tx;
                return;
            }
            final boolean isExitPipDirection = isOutPipDirection(direction)
                    || isRemovePipDirection(direction);
            if (mPipTransitionState.getTransitionState() != PipTransitionState.EXITING_PIP
                    || isExitPipDirection) {
                // Finish resize as long as we're not exiting PIP, or, if we are, only if this is
                // the end of an exit PIP animation.
                // This is necessary in case there was a resize animation ongoing when exit PIP
                // started, in which case the first resize will be skipped to let the exit
                // operation handle the final resize out of PIP mode. See b/185306679.
                finishResize(tx, destinationBounds, direction, animationType);
                sendOnPipTransitionFinished(direction);
            }
        }

        @Override
        public void onPipAnimationCancel(TaskInfo taskInfo,
                PipAnimationController.PipTransitionAnimator animator) {
            final int direction = animator.getTransitionDirection();
            if (isInPipDirection(direction) && animator.getContentOverlay() != null) {
                fadeOutAndRemoveOverlay(animator.getContentOverlay(),
                        animator::clearContentOverlay, true /* withStartDelay */);
            }
            sendOnPipTransitionCancelled(direction);
        }
    };

    private final PipAnimationController.PipTransactionHandler mPipTransactionHandler =
            new PipAnimationController.PipTransactionHandler() {
                @Override
                public boolean handlePipTransaction(SurfaceControl leash,
                        SurfaceControl.Transaction tx, Rect destinationBounds) {
                    if (mPipMenuController.isMenuVisible()) {
                        mPipMenuController.movePipMenu(leash, tx, destinationBounds);
                        return true;
                    }
                    return false;
                }
            };

    private ActivityManager.RunningTaskInfo mTaskInfo;
    // To handle the edge case that onTaskInfoChanged callback is received during the entering
    // PiP transition, where we do not want to intercept the transition but still want to apply the
    // changed RunningTaskInfo when it finishes.
    private ActivityManager.RunningTaskInfo mDeferredTaskInfo;
    private WindowContainerToken mToken;
    private SurfaceControl mLeash;
    private PipTransitionState mPipTransitionState;
    private @PipAnimationController.AnimationType int mOneShotAnimationType = ANIM_TYPE_BOUNDS;
    private long mLastOneShotAlphaAnimationTime;
    private PipSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mSurfaceControlTransactionFactory;
    private PictureInPictureParams mPictureInPictureParams;
    private IntConsumer mOnDisplayIdChangeCallback;
    /**
     * The end transaction of PiP animation for switching between PiP and fullscreen with
     * orientation change. The transaction should be applied after the display is rotated.
     */
    private SurfaceControl.Transaction mDeferredAnimEndTransaction;
    /** Whether the existing PiP is hidden by alpha. */
    private boolean mHasFadeOut;

    /**
     * If set to {@code true}, the entering animation will be skipped and we will wait for
     * {@link #onFixedRotationFinished(int)} callback to actually enter PiP.
     */
    private boolean mWaitForFixedRotation;

    /**
     * The rotation that the display will apply after expanding PiP to fullscreen. This is only
     * meaningful if {@link #mWaitForFixedRotation} is true.
     */
    private @Surface.Rotation int mNextRotation;

    private @Surface.Rotation int mCurrentRotation;

    /**
     * An optional overlay used to mask content changing between an app in/out of PiP, only set if
     * {@link PipTransitionState#getInSwipePipToHomeTransition()} is true.
     */
    @Nullable
    SurfaceControl mSwipePipToHomeOverlay;

    public PipTaskOrganizer(Context context,
            @NonNull SyncTransactionQueue syncTransactionQueue,
            @NonNull PipTransitionState pipTransitionState,
            @NonNull PipBoundsState pipBoundsState,
            @NonNull PipBoundsAlgorithm boundsHandler,
            @NonNull PipMenuController pipMenuController,
            @NonNull PipAnimationController pipAnimationController,
            @NonNull PipSurfaceTransactionHelper surfaceTransactionHelper,
            @NonNull PipTransitionController pipTransitionController,
            Optional<SplitScreenController> splitScreenOptional,
            @NonNull DisplayController displayController,
            @NonNull PipUiEventLogger pipUiEventLogger,
            @NonNull ShellTaskOrganizer shellTaskOrganizer,
            @ShellMainThread ShellExecutor mainExecutor) {
        mContext = context;
        mSyncTransactionQueue = syncTransactionQueue;
        mPipTransitionState = pipTransitionState;
        mPipBoundsState = pipBoundsState;
        mPipBoundsAlgorithm = boundsHandler;
        mPipMenuController = pipMenuController;
        mPipTransitionController = pipTransitionController;
        mEnterAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipEnterAnimationDuration);
        mExitAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipExitAnimationDuration);
        mCrossFadeAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipCrossfadeAnimationDuration);
        mSurfaceTransactionHelper = surfaceTransactionHelper;
        mPipAnimationController = pipAnimationController;
        mPipUiEventLoggerLogger = pipUiEventLogger;
        mSurfaceControlTransactionFactory =
                new PipSurfaceTransactionHelper.VsyncSurfaceControlTransactionFactory();
        mSplitScreenOptional = splitScreenOptional;
        mTaskOrganizer = shellTaskOrganizer;
        mMainExecutor = mainExecutor;

        // TODO: Can be removed once wm components are created on the shell-main thread
        mMainExecutor.execute(() -> {
            mTaskOrganizer.addListenerForType(this, TASK_LISTENER_TYPE_PIP);
        });
        mTaskOrganizer.addFocusListener(this);
        mPipTransitionController.setPipOrganizer(this);
        displayController.addDisplayWindowListener(this);
    }

    public Rect getCurrentOrAnimatingBounds() {
        PipAnimationController.PipTransitionAnimator animator =
                mPipAnimationController.getCurrentAnimator();
        if (animator != null && animator.isRunning()) {
            return new Rect(animator.getDestinationBounds());
        }
        return mPipBoundsState.getBounds();
    }

    public boolean isInPip() {
        return mPipTransitionState.isInPip();
    }

    private boolean isLaunchIntoPipTask() {
        return mPictureInPictureParams != null && mPictureInPictureParams.isLaunchIntoPip();
    }

    /**
     * Returns whether the entry animation is waiting to be started.
     */
    public boolean isEntryScheduled() {
        return mPipTransitionState.getTransitionState() == PipTransitionState.ENTRY_SCHEDULED;
    }

    /**
     * Registers a callback when a display change has been detected when we enter PiP.
     */
    public void registerOnDisplayIdChangeCallback(IntConsumer onDisplayIdChangeCallback) {
        mOnDisplayIdChangeCallback = onDisplayIdChangeCallback;
    }

    /**
     * Sets the preferred animation type for one time.
     * This is typically used to set the animation type to
     * {@link PipAnimationController#ANIM_TYPE_ALPHA}.
     */
    public void setOneShotAnimationType(@PipAnimationController.AnimationType int animationType) {
        mOneShotAnimationType = animationType;
        if (animationType == ANIM_TYPE_ALPHA) {
            mLastOneShotAlphaAnimationTime = SystemClock.uptimeMillis();
        }
    }

    /**
     * Callback when Launcher starts swipe-pip-to-home operation.
     * @return {@link Rect} for destination bounds.
     */
    public Rect startSwipePipToHome(ComponentName componentName, ActivityInfo activityInfo,
            PictureInPictureParams pictureInPictureParams) {
        mPipTransitionState.setInSwipePipToHomeTransition(true);
        sendOnPipTransitionStarted(TRANSITION_DIRECTION_TO_PIP);
        setBoundsStateForEntry(componentName, pictureInPictureParams, activityInfo);
        return mPipBoundsAlgorithm.getEntryDestinationBounds();
    }

    /**
     * Callback when launcher finishes swipe-pip-to-home operation.
     * Expect {@link #onTaskAppeared(ActivityManager.RunningTaskInfo, SurfaceControl)} afterwards.
     */
    public void stopSwipePipToHome(int taskId, ComponentName componentName, Rect destinationBounds,
            SurfaceControl overlay) {
        // do nothing if there is no startSwipePipToHome being called before
        if (!mPipTransitionState.getInSwipePipToHomeTransition()) {
            return;
        }
        mPipBoundsState.setBounds(destinationBounds);
        mSwipePipToHomeOverlay = overlay;
        if (ENABLE_SHELL_TRANSITIONS) {
            // With Shell transition, the overlay was attached to the remote transition leash, which
            // will be removed when the current transition is finished, so we need to reparent it
            // to the actual Task surface now.
            // PipTransition is responsible to fade it out and cleanup when finishing the enter PIP
            // transition.
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            mTaskOrganizer.reparentChildSurfaceToTask(taskId, overlay, t);
            t.setLayer(overlay, Integer.MAX_VALUE);
            t.apply();
        }
    }

    public ActivityManager.RunningTaskInfo getTaskInfo() {
        return mTaskInfo;
    }

    public SurfaceControl getSurfaceControl() {
        return mLeash;
    }

    private void setBoundsStateForEntry(ComponentName componentName,
            PictureInPictureParams params, ActivityInfo activityInfo) {
        mPipBoundsState.setBoundsStateForEntry(componentName, activityInfo, params,
                mPipBoundsAlgorithm);
    }

    /**
     * Expands PiP to the previous bounds, this is done in two phases using
     * {@link WindowContainerTransaction}
     * - setActivityWindowingMode to either fullscreen or split-secondary at beginning of the
     *   transaction. without changing the windowing mode of the Task itself. This makes sure the
     *   activity render it's final configuration while the Task is still in PiP.
     * - setWindowingMode to undefined at the end of transition
     * @param animationDurationMs duration in millisecond for the exiting PiP transition
     * @param requestEnterSplit whether the enterSplit button is pressed on PiP or not.
     *                             Indicate the user wishes to directly put PiP into split screen
     *                             mode.
     */
    public void exitPip(int animationDurationMs, boolean requestEnterSplit) {
        if (!mPipTransitionState.isInPip()
                || mPipTransitionState.getTransitionState() == PipTransitionState.EXITING_PIP
                || mToken == null) {
            ProtoLog.wtf(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Not allowed to exitPip in current state"
                            + " mState=%d mToken=%s", TAG, mPipTransitionState.getTransitionState(),
                    mToken);
            return;
        }

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        if (isLaunchIntoPipTask()) {
            exitLaunchIntoPipTask(wct);
            return;
        }

        if (ENABLE_SHELL_TRANSITIONS) {
            if (requestEnterSplit && mSplitScreenOptional.isPresent()) {
                mSplitScreenOptional.get().prepareEnterSplitScreen(wct, mTaskInfo,
                        isPipTopLeft()
                                ? SPLIT_POSITION_TOP_OR_LEFT : SPLIT_POSITION_BOTTOM_OR_RIGHT);
                mPipTransitionController.startExitTransition(
                        TRANSIT_EXIT_PIP_TO_SPLIT, wct, null /* destinationBounds */);
                return;
            }
        }

        final Rect destinationBounds = mPipBoundsState.getDisplayBounds();
        final int direction = syncWithSplitScreenBounds(destinationBounds, requestEnterSplit)
                ? TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN
                : TRANSITION_DIRECTION_LEAVE_PIP;

        if (Transitions.ENABLE_SHELL_TRANSITIONS && direction == TRANSITION_DIRECTION_LEAVE_PIP) {
            // When exit to fullscreen with Shell transition enabled, we update the Task windowing
            // mode directly so that it can also trigger display rotation and visibility update in
            // the same transition if there will be any.
            wct.setWindowingMode(mToken, WINDOWING_MODE_UNDEFINED);
            // We can inherit the parent bounds as it is going to be fullscreen. The
            // destinationBounds calculated above will be incorrect if this is with rotation.
            wct.setBounds(mToken, null);
        } else {
            final SurfaceControl.Transaction tx =
                    mSurfaceControlTransactionFactory.getTransaction();
            mSurfaceTransactionHelper.scale(tx, mLeash, destinationBounds,
                    mPipBoundsState.getBounds());
            tx.setWindowCrop(mLeash, destinationBounds.width(), destinationBounds.height());
            // We set to fullscreen here for now, but later it will be set to UNDEFINED for
            // the proper windowing mode to take place. See #applyWindowingModeChangeOnExit.
            wct.setActivityWindowingMode(mToken, WINDOWING_MODE_FULLSCREEN);
            wct.setBounds(mToken, destinationBounds);
            wct.setBoundsChangeTransaction(mToken, tx);
        }

        // Set the exiting state first so if there is fixed rotation later, the running animation
        // won't be interrupted by alpha animation for existing PiP.
        mPipTransitionState.setTransitionState(PipTransitionState.EXITING_PIP);

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            mPipTransitionController.startExitTransition(TRANSIT_EXIT_PIP, wct, destinationBounds);
            return;
        }
        mSyncTransactionQueue.queue(wct);
        mSyncTransactionQueue.runInSync(t -> {
            // Make sure to grab the latest source hint rect as it could have been
            // updated right after applying the windowing mode change.
            final Rect sourceHintRect = PipBoundsAlgorithm.getValidSourceHintRect(
                    mPictureInPictureParams, destinationBounds);
            final PipAnimationController.PipTransitionAnimator<?> animator =
                    animateResizePip(mPipBoundsState.getBounds(), destinationBounds, sourceHintRect,
                            direction, animationDurationMs, 0 /* startingAngle */);
            if (animator != null) {
                // Even though the animation was started above, re-apply the transaction for the
                // first frame using the SurfaceControl.Transaction supplied by the
                // SyncTransactionQueue. This is necessary because the initial surface transform
                // may not be applied until the next frame if a different Transaction than the one
                // supplied is used, resulting in 1 frame not being cropped to the source rect
                // hint during expansion that causes a visible jank/flash. See b/184166183.
                animator.applySurfaceControlTransaction(mLeash, t, FRACTION_START);
            }
        });
    }

    private void exitLaunchIntoPipTask(WindowContainerTransaction wct) {
        wct.startTask(mTaskInfo.launchIntoPipHostTaskId, null /* ActivityOptions */);
        mTaskOrganizer.applyTransaction(wct);

        // Remove the PiP with fade-out animation right after the host Task is brought to front.
        removePip();
    }

    private void applyWindowingModeChangeOnExit(WindowContainerTransaction wct, int direction) {
        // Reset the final windowing mode.
        wct.setWindowingMode(mToken, getOutPipWindowingMode());
        // Simply reset the activity mode set prior to the animation running.
        wct.setActivityWindowingMode(mToken, WINDOWING_MODE_UNDEFINED);
    }

    /**
     * Removes PiP immediately.
     */
    public void removePip() {
        if (!mPipTransitionState.isInPip() || mToken == null) {
            ProtoLog.wtf(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Not allowed to removePip in current state"
                            + " mState=%d mToken=%s", TAG, mPipTransitionState.getTransitionState(),
                    mToken);
            return;
        }

        // removePipImmediately is expected when the following animation finishes.
        ValueAnimator animator = mPipAnimationController
                .getAnimator(mTaskInfo, mLeash, mPipBoundsState.getBounds(),
                        1f /* alphaStart */, 0f /* alphaEnd */)
                .setTransitionDirection(TRANSITION_DIRECTION_REMOVE_STACK)
                .setPipTransactionHandler(mPipTransactionHandler)
                .setPipAnimationCallback(mPipAnimationCallback);
        animator.setDuration(mExitAnimationDuration);
        animator.setInterpolator(Interpolators.ALPHA_OUT);
        animator.start();
        mPipTransitionState.setTransitionState(PipTransitionState.EXITING_PIP);
    }

    private void removePipImmediately() {
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.setBounds(mToken, null);
            wct.setWindowingMode(mToken, WINDOWING_MODE_UNDEFINED);
            wct.reorder(mToken, false);
            mPipTransitionController.startExitTransition(TRANSIT_REMOVE_PIP, wct,
                    null /* destinationBounds */);
            return;
        }

        try {
            // Reset the task bounds first to ensure the activity configuration is reset as well
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.setBounds(mToken, null);
            mTaskOrganizer.applyTransaction(wct);

            ActivityTaskManager.getService().removeRootTasksInWindowingModes(
                    new int[]{ WINDOWING_MODE_PINNED });
        } catch (RemoteException e) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Failed to remove PiP, %s",
                    TAG, e);
        }
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo info, SurfaceControl leash) {
        Objects.requireNonNull(info, "Requires RunningTaskInfo");
        mTaskInfo = info;
        mToken = mTaskInfo.token;
        mPipTransitionState.setTransitionState(PipTransitionState.TASK_APPEARED);
        mLeash = leash;
        mPictureInPictureParams = mTaskInfo.pictureInPictureParams;
        setBoundsStateForEntry(mTaskInfo.topActivity, mPictureInPictureParams,
                mTaskInfo.topActivityInfo);

        mPipUiEventLoggerLogger.setTaskInfo(mTaskInfo);
        mPipUiEventLoggerLogger.log(PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_ENTER);

        // If the displayId of the task is different than what PipBoundsHandler has, then update
        // it. This is possible if we entered PiP on an external display.
        if (info.displayId != mPipBoundsState.getDisplayId()
                && mOnDisplayIdChangeCallback != null) {
            mOnDisplayIdChangeCallback.accept(info.displayId);
        }

        if (mPipTransitionState.getInSwipePipToHomeTransition()) {
            if (!mWaitForFixedRotation) {
                onEndOfSwipePipToHomeTransition();
            } else {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: Defer onTaskAppeared-SwipePipToHome until end of fixed rotation.",
                        TAG);
            }
            return;
        }

        if (mOneShotAnimationType == ANIM_TYPE_ALPHA
                && SystemClock.uptimeMillis() - mLastOneShotAlphaAnimationTime
                > ONE_SHOT_ALPHA_ANIMATION_TIMEOUT_MS) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Alpha animation is expired. Use bounds animation.", TAG);
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;
        }

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            // For Shell transition, we will animate the window in PipTransition#startAnimation
            // instead of #onTaskAppeared.
            return;
        }

        if (mWaitForFixedRotation) {
            onTaskAppearedWithFixedRotation();
            return;
        }

        final Rect destinationBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
        Objects.requireNonNull(destinationBounds, "Missing destination bounds");
        final Rect currentBounds = mTaskInfo.configuration.windowConfiguration.getBounds();

        if (mOneShotAnimationType == ANIM_TYPE_BOUNDS) {
            mPipMenuController.attach(mLeash);
            final Rect sourceHintRect = PipBoundsAlgorithm.getValidSourceHintRect(
                    info.pictureInPictureParams, currentBounds);
            scheduleAnimateResizePip(currentBounds, destinationBounds, 0 /* startingAngle */,
                    sourceHintRect, TRANSITION_DIRECTION_TO_PIP, mEnterAnimationDuration,
                    null /* updateBoundsCallback */);
            mPipTransitionState.setTransitionState(PipTransitionState.ENTERING_PIP);
        } else if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            enterPipWithAlphaAnimation(destinationBounds, mEnterAnimationDuration);
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;
        } else {
            throw new RuntimeException("Unrecognized animation type: " + mOneShotAnimationType);
        }
    }

    private void onTaskAppearedWithFixedRotation() {
        if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Defer entering PiP alpha animation, fixed rotation is ongoing", TAG);
            // If deferred, hside the surface till fixed rotation is completed.
            final SurfaceControl.Transaction tx =
                    mSurfaceControlTransactionFactory.getTransaction();
            tx.setAlpha(mLeash, 0f);
            tx.show(mLeash);
            tx.apply();
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;
            return;
        }
        final Rect currentBounds = mTaskInfo.configuration.windowConfiguration.getBounds();
        final Rect sourceHintRect = PipBoundsAlgorithm.getValidSourceHintRect(
                mPictureInPictureParams, currentBounds);
        final Rect destinationBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
        animateResizePip(currentBounds, destinationBounds, sourceHintRect,
                TRANSITION_DIRECTION_TO_PIP, mEnterAnimationDuration, 0 /* startingAngle */);
        mPipTransitionState.setTransitionState(PipTransitionState.ENTERING_PIP);
    }

    /**
     * Called when the display rotation handling is skipped (e.g. when rotation happens while in
     * the middle of an entry transition).
     */
    public void onDisplayRotationSkipped() {
        if (isEntryScheduled()) {
            // The PIP animation is scheduled to start with the previous orientation's bounds,
            // re-calculate the entry bounds and restart the alpha animation.
            final Rect destinationBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
            enterPipWithAlphaAnimation(destinationBounds, mEnterAnimationDuration);
        }
    }

    @VisibleForTesting
    void enterPipWithAlphaAnimation(Rect destinationBounds, long durationMs) {
        // If we are fading the PIP in, then we should move the pip to the final location as
        // soon as possible, but set the alpha immediately since the transaction can take a
        // while to process
        final SurfaceControl.Transaction tx =
                mSurfaceControlTransactionFactory.getTransaction();
        tx.setAlpha(mLeash, 0f);
        tx.apply();

        // When entering PiP this transaction will be applied within WindowContainerTransaction and
        // ensure that the PiP has rounded corners.
        final SurfaceControl.Transaction boundsChangeTx =
                mSurfaceControlTransactionFactory.getTransaction();
        mSurfaceTransactionHelper
                .crop(boundsChangeTx, mLeash, destinationBounds)
                .round(boundsChangeTx, mLeash, true /* applyCornerRadius */);

        mPipTransitionState.setTransitionState(PipTransitionState.ENTRY_SCHEDULED);
        applyEnterPipSyncTransaction(destinationBounds, () -> {
            mPipAnimationController
                    .getAnimator(mTaskInfo, mLeash, destinationBounds, 0f, 1f)
                    .setTransitionDirection(TRANSITION_DIRECTION_TO_PIP)
                    .setPipAnimationCallback(mPipAnimationCallback)
                    .setPipTransactionHandler(mPipTransactionHandler)
                    .setDuration(durationMs)
                    .start();
            // mState is set right after the animation is kicked off to block any resize
            // requests such as offsetPip that may have been called prior to the transition.
            mPipTransitionState.setTransitionState(PipTransitionState.ENTERING_PIP);
        }, boundsChangeTx);
    }

    private void onEndOfSwipePipToHomeTransition() {
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            return;
        }

        final Rect destinationBounds = mPipBoundsState.getBounds();
        final SurfaceControl swipeToHomeOverlay = mSwipePipToHomeOverlay;
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        mSurfaceTransactionHelper
                .resetScale(tx, mLeash, destinationBounds)
                .crop(tx, mLeash, destinationBounds)
                .round(tx, mLeash, isInPip());
        // The animation is finished in the Launcher and here we directly apply the final touch.
        applyEnterPipSyncTransaction(destinationBounds, () -> {
            // Ensure menu's settled in its final bounds first.
            finishResizeForMenu(destinationBounds);
            sendOnPipTransitionFinished(TRANSITION_DIRECTION_TO_PIP);

            // Remove the swipe to home overlay
            if (swipeToHomeOverlay != null) {
                fadeOutAndRemoveOverlay(swipeToHomeOverlay,
                        null /* callback */, false /* withStartDelay */);
            }
        }, tx);
        mPipTransitionState.setInSwipePipToHomeTransition(false);
        mSwipePipToHomeOverlay = null;
    }

    private void applyEnterPipSyncTransaction(Rect destinationBounds, Runnable runnable,
            @Nullable SurfaceControl.Transaction boundsChangeTransaction) {
        // PiP menu is attached late in the process here to avoid any artifacts on the leash
        // caused by addShellRoot when in gesture navigation mode.
        mPipMenuController.attach(mLeash);
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setActivityWindowingMode(mToken, WINDOWING_MODE_UNDEFINED);
        wct.setBounds(mToken, destinationBounds);
        if (boundsChangeTransaction != null) {
            wct.setBoundsChangeTransaction(mToken, boundsChangeTransaction);
        }
        mSyncTransactionQueue.queue(wct);
        if (runnable != null) {
            mSyncTransactionQueue.runInSync(t -> runnable.run());
        }
    }

    private void sendOnPipTransitionStarted(
            @PipAnimationController.TransitionDirection int direction) {
        if (direction == TRANSITION_DIRECTION_TO_PIP) {
            mPipTransitionState.setTransitionState(PipTransitionState.ENTERING_PIP);
        }
        mPipTransitionController.sendOnPipTransitionStarted(direction);
    }

    @VisibleForTesting
    void sendOnPipTransitionFinished(
            @PipAnimationController.TransitionDirection int direction) {
        if (direction == TRANSITION_DIRECTION_TO_PIP) {
            mPipTransitionState.setTransitionState(PipTransitionState.ENTERED_PIP);
        }
        mPipTransitionController.sendOnPipTransitionFinished(direction);
        // Apply the deferred RunningTaskInfo if applicable after all proper callbacks are sent.
        if (direction == TRANSITION_DIRECTION_TO_PIP && mDeferredTaskInfo != null) {
            onTaskInfoChanged(mDeferredTaskInfo);
            mDeferredTaskInfo = null;
        }
    }

    private void sendOnPipTransitionCancelled(
            @PipAnimationController.TransitionDirection int direction) {
        mPipTransitionController.sendOnPipTransitionCancelled(direction);
    }

    /**
     * Note that dismissing PiP is now originated from SystemUI, see {@link #exitPip(int, boolean)}.
     * Meanwhile this callback is invoked whenever the task is removed. For instance:
     *   - as a result of removeRootTasksInWindowingModes from WM
     *   - activity itself is died
     * Nevertheless, we simply update the internal state here as all the heavy lifting should
     * have been done in WM.
     */
    @Override
    public void onTaskVanished(ActivityManager.RunningTaskInfo info) {
        if (mPipTransitionState.getTransitionState() == PipTransitionState.UNDEFINED) {
            return;
        }
        if (Transitions.ENABLE_SHELL_TRANSITIONS
                && mPipTransitionState.getTransitionState() == PipTransitionState.EXITING_PIP) {
            // With Shell transition, we do the cleanup in PipTransition after exiting PIP.
            return;
        }
        final WindowContainerToken token = info.token;
        Objects.requireNonNull(token, "Requires valid WindowContainerToken");
        if (token.asBinder() != mToken.asBinder()) {
            ProtoLog.wtf(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Unrecognized token: %s", TAG, token);
            return;
        }
        onExitPipFinished(info);

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            mPipTransitionController.forceFinishTransition();
        }
        final PipAnimationController.PipTransitionAnimator<?> animator =
                mPipAnimationController.getCurrentAnimator();
        if (animator != null) {
            if (animator.getContentOverlay() != null) {
                removeContentOverlay(animator.getContentOverlay(), animator::clearContentOverlay);
            }
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            animator.cancel();
        }
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo info) {
        Objects.requireNonNull(mToken, "onTaskInfoChanged requires valid existing mToken");
        if (mPipTransitionState.getTransitionState() != PipTransitionState.ENTERED_PIP
                && mPipTransitionState.getTransitionState() != PipTransitionState.EXITING_PIP) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Defer onTaskInfoChange in current state: %d", TAG,
                    mPipTransitionState.getTransitionState());
            // Defer applying PiP parameters if the task is entering PiP to avoid disturbing
            // the animation.
            mDeferredTaskInfo = info;
            return;
        }
        mPipBoundsState.setLastPipComponentName(info.topActivity);
        mPipBoundsState.setOverrideMinSize(
                mPipBoundsAlgorithm.getMinimalSize(info.topActivityInfo));
        final PictureInPictureParams newParams = info.pictureInPictureParams;
        if (newParams == null || !applyPictureInPictureParams(newParams)) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Ignored onTaskInfoChanged with PiP param: %s", TAG, newParams);
            return;
        }
        // Aspect ratio changed, re-calculate bounds if valid.
        final Rect destinationBounds = mPipBoundsAlgorithm.getAdjustedDestinationBounds(
                mPipBoundsState.getBounds(), mPipBoundsState.getAspectRatio());
        Objects.requireNonNull(destinationBounds, "Missing destination bounds");
        scheduleAnimateResizePip(destinationBounds, mEnterAnimationDuration,
                null /* updateBoundsCallback */);
    }

    @Override
    public void onFocusTaskChanged(ActivityManager.RunningTaskInfo taskInfo) {
        mPipMenuController.onFocusTaskChanged(taskInfo);
    }

    @Override
    public boolean supportCompatUI() {
        // PIP doesn't support compat.
        return false;
    }

    @Override
    public void attachChildSurfaceToTask(int taskId, SurfaceControl.Builder b) {
        b.setParent(findTaskSurface(taskId));
    }

    @Override
    public void reparentChildSurfaceToTask(int taskId, SurfaceControl sc,
            SurfaceControl.Transaction t) {
        t.reparent(sc, findTaskSurface(taskId));
    }

    private SurfaceControl findTaskSurface(int taskId) {
        if (mTaskInfo == null || mLeash == null || mTaskInfo.taskId != taskId) {
            throw new IllegalArgumentException("There is no surface for taskId=" + taskId);
        }
        return mLeash;
    }

    @Override
    public void onFixedRotationStarted(int displayId, int newRotation) {
        mNextRotation = newRotation;
        mWaitForFixedRotation = true;

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            // The fixed rotation will also be included in the transition info. However, if it is
            // not a PIP transition (such as open another app to different orientation),
            // PIP transition handler may not be aware of the fixed rotation start.
            // Notify the PIP transition handler so that it can fade out the PIP window early for
            // fixed transition of other windows.
            mPipTransitionController.onFixedRotationStarted();
            return;
        }

        if (mPipTransitionState.isInPip()) {
            // Fade out the existing PiP to avoid jump cut during seamless rotation.
            fadeExistingPip(false /* show */);
        }
    }

    @Override
    public void onFixedRotationFinished(int displayId) {
        if (!mWaitForFixedRotation) {
            return;
        }
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            clearWaitForFixedRotation();
            return;
        }
        if (mPipTransitionState.getTransitionState() == PipTransitionState.TASK_APPEARED) {
            if (mPipTransitionState.getInSwipePipToHomeTransition()) {
                onEndOfSwipePipToHomeTransition();
            } else {
                // Schedule a regular animation to ensure all the callbacks are still being sent.
                enterPipWithAlphaAnimation(mPipBoundsAlgorithm.getEntryDestinationBounds(),
                        mEnterAnimationDuration);
            }
        } else if (mPipTransitionState.getTransitionState() == PipTransitionState.ENTERED_PIP
                && mHasFadeOut) {
            fadeExistingPip(true /* show */);
        } else if (mPipTransitionState.getTransitionState() == PipTransitionState.ENTERING_PIP
                && mDeferredAnimEndTransaction != null) {
            final PipAnimationController.PipTransitionAnimator<?> animator =
                    mPipAnimationController.getCurrentAnimator();
            final Rect destinationBounds = animator.getDestinationBounds();
            mPipBoundsState.setBounds(destinationBounds);
            applyEnterPipSyncTransaction(destinationBounds, () -> {
                finishResizeForMenu(destinationBounds);
                sendOnPipTransitionFinished(TRANSITION_DIRECTION_TO_PIP);
            }, mDeferredAnimEndTransaction);
        }
        clearWaitForFixedRotation();
    }

    /** Called when exiting PIP transition is finished to do the state cleanup. */
    void onExitPipFinished(TaskInfo info) {
        clearWaitForFixedRotation();
        if (mSwipePipToHomeOverlay != null) {
            removeContentOverlay(mSwipePipToHomeOverlay, null /* callback */);
            mSwipePipToHomeOverlay = null;
        }
        resetShadowRadius();
        mPipTransitionState.setInSwipePipToHomeTransition(false);
        mPictureInPictureParams = null;
        mPipTransitionState.setTransitionState(PipTransitionState.UNDEFINED);
        // Re-set the PIP bounds to none.
        mPipBoundsState.setBounds(new Rect());
        mPipUiEventLoggerLogger.setTaskInfo(null);
        mPipMenuController.detach();

        if (info.displayId != Display.DEFAULT_DISPLAY && mOnDisplayIdChangeCallback != null) {
            mOnDisplayIdChangeCallback.accept(Display.DEFAULT_DISPLAY);
        }
    }

    private void fadeExistingPip(boolean show) {
        if (mLeash == null || !mLeash.isValid()) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Invalid leash on fadeExistingPip: %s", TAG, mLeash);
            return;
        }
        final float alphaStart = show ? 0 : 1;
        final float alphaEnd = show ? 1 : 0;
        mPipAnimationController
                .getAnimator(mTaskInfo, mLeash, mPipBoundsState.getBounds(), alphaStart, alphaEnd)
                .setTransitionDirection(TRANSITION_DIRECTION_SAME)
                .setPipTransactionHandler(mPipTransactionHandler)
                .setDuration(show ? mEnterAnimationDuration : mExitAnimationDuration)
                .start();
        mHasFadeOut = !show;
    }

    private void clearWaitForFixedRotation() {
        mWaitForFixedRotation = false;
        mDeferredAnimEndTransaction = null;
    }

    @Override
    public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
        mCurrentRotation = newConfig.windowConfiguration.getRotation();
    }

    /**
     * Called when display size or font size of settings changed
     */
    public void onDensityOrFontScaleChanged(Context context) {
        mSurfaceTransactionHelper.onDensityOrFontScaleChanged(context);
    }

    /**
     * TODO(b/152809058): consolidate the display info handling logic in SysUI
     *
     * @param destinationBoundsOut the current destination bounds will be populated to this param
     */
    @SuppressWarnings("unchecked")
    public void onMovementBoundsChanged(Rect destinationBoundsOut, boolean fromRotation,
            boolean fromImeAdjustment, boolean fromShelfAdjustment,
            WindowContainerTransaction wct) {
        // note that this can be called when swipe-to-home or fixed-rotation is happening.
        // Skip this entirely if that's the case.
        final boolean waitForFixedRotationOnEnteringPip = mWaitForFixedRotation
                && (mPipTransitionState.getTransitionState() != PipTransitionState.ENTERED_PIP);
        if ((mPipTransitionState.getInSwipePipToHomeTransition()
                || waitForFixedRotationOnEnteringPip) && fromRotation) {
            if (DEBUG) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: Skip onMovementBoundsChanged on rotation change"
                                + " InSwipePipToHomeTransition=%b"
                                + " mWaitForFixedRotation=%b"
                                + " getTransitionState=%d", TAG,
                        mPipTransitionState.getInSwipePipToHomeTransition(), mWaitForFixedRotation,
                        mPipTransitionState.getTransitionState());
            }
            return;
        }
        final PipAnimationController.PipTransitionAnimator animator =
                mPipAnimationController.getCurrentAnimator();
        if (animator == null || !animator.isRunning()
                || animator.getTransitionDirection() != TRANSITION_DIRECTION_TO_PIP) {
            final boolean rotatingPip = mPipTransitionState.isInPip() && fromRotation;
            if (rotatingPip && Transitions.ENABLE_SHELL_TRANSITIONS) {
                // The animation and surface update will be handled by the shell transition handler.
                mPipBoundsState.setBounds(destinationBoundsOut);
            } else if (rotatingPip && mWaitForFixedRotation && mHasFadeOut) {
                // The position will be used by fade-in animation when the fixed rotation is done.
                mPipBoundsState.setBounds(destinationBoundsOut);
            } else if (rotatingPip) {
                // Update bounds state to final destination first. It's important to do this
                // before finishing & cancelling the transition animation so that the MotionHelper
                // bounds are synchronized to the destination bounds when the animation ends.
                mPipBoundsState.setBounds(destinationBoundsOut);
                // If we are rotating while there is a current animation, immediately cancel the
                // animation (remove the listeners so we don't trigger the normal finish resize
                // call that should only happen on the update thread)
                int direction = TRANSITION_DIRECTION_NONE;
                if (animator != null) {
                    direction = animator.getTransitionDirection();
                    animator.removeAllUpdateListeners();
                    animator.removeAllListeners();
                    animator.cancel();
                    // Do notify the listeners that this was canceled
                    sendOnPipTransitionCancelled(direction);
                    sendOnPipTransitionFinished(direction);
                }

                // Create a reset surface transaction for the new bounds and update the window
                // container transaction
                final SurfaceControl.Transaction tx = createFinishResizeSurfaceTransaction(
                        destinationBoundsOut);
                prepareFinishResizeTransaction(destinationBoundsOut, direction, tx, wct);
            } else  {
                // There could be an animation on-going. If there is one on-going, last-reported
                // bounds isn't yet updated. We'll use the animator's bounds instead.
                if (animator != null && animator.isRunning()) {
                    if (!animator.getDestinationBounds().isEmpty()) {
                        destinationBoundsOut.set(animator.getDestinationBounds());
                    }
                } else {
                    if (!mPipBoundsState.getBounds().isEmpty()) {
                        destinationBoundsOut.set(mPipBoundsState.getBounds());
                    }
                }
            }
            return;
        }

        final Rect currentDestinationBounds = animator.getDestinationBounds();
        destinationBoundsOut.set(currentDestinationBounds);
        if (!fromImeAdjustment && !fromShelfAdjustment
                && mPipBoundsState.getDisplayBounds().contains(currentDestinationBounds)) {
            // no need to update the destination bounds, bail early
            return;
        }

        final Rect newDestinationBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
        if (newDestinationBounds.equals(currentDestinationBounds)) return;
        if (animator.getAnimationType() == ANIM_TYPE_BOUNDS) {
            if (mWaitForFixedRotation) {
                // The new destination bounds are in next rotation (DisplayLayout has been rotated
                // in computeRotatedBounds). The animation runs in previous rotation so the end
                // bounds need to be transformed.
                final Rect displayBounds = mPipBoundsState.getDisplayBounds();
                final Rect rotatedEndBounds = new Rect(newDestinationBounds);
                rotateBounds(rotatedEndBounds, displayBounds, mNextRotation, mCurrentRotation);
                animator.updateEndValue(rotatedEndBounds);
            } else {
                animator.updateEndValue(newDestinationBounds);
            }
        }
        animator.setDestinationBounds(newDestinationBounds);
        destinationBoundsOut.set(newDestinationBounds);
    }

    /**
     * @return {@code true} if the aspect ratio is changed since no other parameters within
     * {@link PictureInPictureParams} would affect the bounds.
     */
    private boolean applyPictureInPictureParams(@NonNull PictureInPictureParams params) {
        final Rational currentAspectRatio =
                mPictureInPictureParams != null ? mPictureInPictureParams.getAspectRatio()
                        : null;
        final boolean aspectRatioChanged = !Objects.equals(currentAspectRatio,
                params.getAspectRatio());
        mPictureInPictureParams = params;
        if (aspectRatioChanged) {
            mPipBoundsState.setAspectRatio(params.getAspectRatioFloat());
        }
        return aspectRatioChanged;
    }

    /**
     * Animates resizing of the pinned stack given the duration.
     */
    public void scheduleAnimateResizePip(Rect toBounds, int duration,
            Consumer<Rect> updateBoundsCallback) {
        scheduleAnimateResizePip(toBounds, duration, TRANSITION_DIRECTION_NONE,
                updateBoundsCallback);
    }

    /**
     * Animates resizing of the pinned stack given the duration.
     */
    public void scheduleAnimateResizePip(Rect toBounds, int duration,
            @PipAnimationController.TransitionDirection int direction,
            Consumer<Rect> updateBoundsCallback) {
        if (mWaitForFixedRotation) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: skip scheduleAnimateResizePip, entering pip deferred", TAG);
            return;
        }
        scheduleAnimateResizePip(mPipBoundsState.getBounds(), toBounds, 0 /* startingAngle */,
                null /* sourceHintRect */, direction, duration, updateBoundsCallback);
    }

    /**
     * Animates resizing of the pinned stack given the duration and start bounds.
     * This is used when the starting bounds is not the current PiP bounds.
     */
    public void scheduleAnimateResizePip(Rect fromBounds, Rect toBounds, int duration,
            float startingAngle, Consumer<Rect> updateBoundsCallback) {
        if (mWaitForFixedRotation) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: skip scheduleAnimateResizePip, entering pip deferred", TAG);
            return;
        }
        scheduleAnimateResizePip(fromBounds, toBounds, startingAngle, null /* sourceHintRect */,
                TRANSITION_DIRECTION_SNAP_AFTER_RESIZE, duration, updateBoundsCallback);
    }

    /**
     * Animates resizing of the pinned stack given the duration and start bounds.
     * This always animates the angle to zero from the starting angle.
     */
    private @Nullable PipAnimationController.PipTransitionAnimator<?> scheduleAnimateResizePip(
            Rect currentBounds, Rect destinationBounds, float startingAngle, Rect sourceHintRect,
            @PipAnimationController.TransitionDirection int direction, int durationMs,
            Consumer<Rect> updateBoundsCallback) {
        if (!mPipTransitionState.isInPip()) {
            // TODO: tend to use shouldBlockResizeRequest here as well but need to consider
            // the fact that when in exitPip, scheduleAnimateResizePip is executed in the window
            // container transaction callback and we want to set the mState immediately.
            return null;
        }

        final PipAnimationController.PipTransitionAnimator<?> animator = animateResizePip(
                currentBounds, destinationBounds, sourceHintRect, direction, durationMs,
                startingAngle);
        if (updateBoundsCallback != null) {
            updateBoundsCallback.accept(destinationBounds);
        }
        return animator;
    }

    /**
     * Directly perform manipulation/resize on the leash. This will not perform any
     * {@link WindowContainerTransaction} until {@link #scheduleFinishResizePip} is called.
     */
    public void scheduleResizePip(Rect toBounds, Consumer<Rect> updateBoundsCallback) {
        // Could happen when exitPip
        if (mToken == null || mLeash == null) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Abort animation, invalid leash", TAG);
            return;
        }
        mPipBoundsState.setBounds(toBounds);
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        mSurfaceTransactionHelper
                .crop(tx, mLeash, toBounds)
                .round(tx, mLeash, mPipTransitionState.isInPip());
        if (mPipMenuController.isMenuVisible()) {
            mPipMenuController.resizePipMenu(mLeash, tx, toBounds);
        } else {
            tx.apply();
        }
        if (updateBoundsCallback != null) {
            updateBoundsCallback.accept(toBounds);
        }
    }

    /**
     * Directly perform manipulation/resize on the leash, along with rotation. This will not perform
     * any {@link WindowContainerTransaction} until {@link #scheduleFinishResizePip} is called.
     */
    public void scheduleUserResizePip(Rect startBounds, Rect toBounds,
            Consumer<Rect> updateBoundsCallback) {
        scheduleUserResizePip(startBounds, toBounds, 0 /* degrees */, updateBoundsCallback);
    }

    /**
     * Directly perform a scaled matrix transformation on the leash. This will not perform any
     * {@link WindowContainerTransaction} until {@link #scheduleFinishResizePip} is called.
     */
    public void scheduleUserResizePip(Rect startBounds, Rect toBounds, float degrees,
            Consumer<Rect> updateBoundsCallback) {
        // Could happen when exitPip
        if (mToken == null || mLeash == null) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Abort animation, invalid leash", TAG);
            return;
        }

        if (startBounds.isEmpty() || toBounds.isEmpty()) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Attempted to user resize PIP to or from empty bounds, aborting.", TAG);
            return;
        }

        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        mSurfaceTransactionHelper
                .scale(tx, mLeash, startBounds, toBounds, degrees)
                .round(tx, mLeash, startBounds, toBounds);
        if (mPipMenuController.isMenuVisible()) {
            mPipMenuController.movePipMenu(mLeash, tx, toBounds);
        } else {
            tx.apply();
        }
        if (updateBoundsCallback != null) {
            updateBoundsCallback.accept(toBounds);
        }
    }

    /**
     * Finish an intermediate resize operation. This is expected to be called after
     * {@link #scheduleResizePip}.
     */
    public void scheduleFinishResizePip(Rect destinationBounds) {
        scheduleFinishResizePip(destinationBounds, null /* updateBoundsCallback */);
    }

    /**
     * Same as {@link #scheduleFinishResizePip} but with a callback.
     */
    public void scheduleFinishResizePip(Rect destinationBounds,
            Consumer<Rect> updateBoundsCallback) {
        scheduleFinishResizePip(destinationBounds, TRANSITION_DIRECTION_NONE, updateBoundsCallback);
    }

    /**
     * Finish an intermediate resize operation. This is expected to be called after
     * {@link #scheduleResizePip}.
     *
     * @param destinationBounds the final bounds of the PIP after resizing
     * @param direction the transition direction
     * @param updateBoundsCallback a callback to invoke after finishing the resize
     */
    public void scheduleFinishResizePip(Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction,
            Consumer<Rect> updateBoundsCallback) {
        if (mPipTransitionState.shouldBlockResizeRequest()) {
            return;
        }

        finishResize(createFinishResizeSurfaceTransaction(destinationBounds), destinationBounds,
                direction, -1);
        if (updateBoundsCallback != null) {
            updateBoundsCallback.accept(destinationBounds);
        }
    }

    private SurfaceControl.Transaction createFinishResizeSurfaceTransaction(
            Rect destinationBounds) {
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        mSurfaceTransactionHelper
                .crop(tx, mLeash, destinationBounds)
                .resetScale(tx, mLeash, destinationBounds)
                .round(tx, mLeash, mPipTransitionState.isInPip());
        return tx;
    }

    /**
     * Offset the PiP window by a given offset on Y-axis, triggered also from screen rotation.
     */
    public void scheduleOffsetPip(Rect originalBounds, int offset, int duration,
            Consumer<Rect> updateBoundsCallback) {
        if (mPipTransitionState.shouldBlockResizeRequest()) {
            return;
        }
        if (mWaitForFixedRotation) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: skip scheduleOffsetPip, entering pip deferred", TAG);
            return;
        }
        offsetPip(originalBounds, 0 /* xOffset */, offset, duration);
        Rect toBounds = new Rect(originalBounds);
        toBounds.offset(0, offset);
        if (updateBoundsCallback != null) {
            updateBoundsCallback.accept(toBounds);
        }
    }

    private void offsetPip(Rect originalBounds, int xOffset, int yOffset, int durationMs) {
        if (mTaskInfo == null) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE, "%s: mTaskInfo is not set",
                    TAG);
            return;
        }
        final Rect destinationBounds = new Rect(originalBounds);
        destinationBounds.offset(xOffset, yOffset);
        animateResizePip(originalBounds, destinationBounds, null /* sourceHintRect */,
                TRANSITION_DIRECTION_SAME, durationMs, 0);
    }

    private void finishResize(SurfaceControl.Transaction tx, Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction,
            @PipAnimationController.AnimationType int type) {
        final Rect preResizeBounds = new Rect(mPipBoundsState.getBounds());
        final boolean isPipTopLeft = isPipTopLeft();
        mPipBoundsState.setBounds(destinationBounds);
        if (direction == TRANSITION_DIRECTION_REMOVE_STACK) {
            removePipImmediately();
            return;
        } else if (isInPipDirection(direction) && type == ANIM_TYPE_ALPHA) {
            // TODO: Synchronize this correctly in #applyEnterPipSyncTransaction
            finishResizeForMenu(destinationBounds);
            return;
        }

        WindowContainerTransaction wct = new WindowContainerTransaction();
        prepareFinishResizeTransaction(destinationBounds, direction, tx, wct);

        // Only corner drag, pinch or expand/un-expand resizing may lead to animating the finish
        // resize operation.
        final boolean mayAnimateFinishResize = direction == TRANSITION_DIRECTION_USER_RESIZE
                || direction == TRANSITION_DIRECTION_SNAP_AFTER_RESIZE
                || direction == TRANSITION_DIRECTION_EXPAND_OR_UNEXPAND;
        // Animate with a cross-fade if enabled and seamless resize is disables by the app.
        final boolean animateCrossFadeResize = mayAnimateFinishResize
                && mPictureInPictureParams != null
                && !mPictureInPictureParams.isSeamlessResizeEnabled();
        if (animateCrossFadeResize) {
            // Take a snapshot of the PIP task and show it. We'll fade it out after the wct
            // transaction is applied and the activity is laid out again.
            preResizeBounds.offsetTo(0, 0);
            final Rect snapshotDest = new Rect(0, 0, destinationBounds.width(),
                    destinationBounds.height());
            // Note: Put this at layer=MAX_VALUE-2 since the input consumer for PIP is placed at
            //       MAX_VALUE-1
            final SurfaceControl snapshotSurface = ScreenshotUtils.takeScreenshot(
                    mSurfaceControlTransactionFactory.getTransaction(), mLeash, preResizeBounds,
                    Integer.MAX_VALUE - 2);
            if (snapshotSurface != null) {
                mSyncTransactionQueue.queue(wct);
                mSyncTransactionQueue.runInSync(t -> {
                    // Scale the snapshot from its pre-resize bounds to the post-resize bounds.
                    mSurfaceTransactionHelper.scale(t, snapshotSurface, preResizeBounds,
                            snapshotDest);

                    // Start animation to fade out the snapshot.
                    fadeOutAndRemoveOverlay(snapshotSurface,
                            null /* callback */, false /* withStartDelay */);
                });
            } else {
                applyFinishBoundsResize(wct, direction, isPipTopLeft);
            }
        } else {
            applyFinishBoundsResize(wct, direction, isPipTopLeft);
        }

        finishResizeForMenu(destinationBounds);
    }

    /** Moves the PiP menu to the destination bounds. */
    public void finishResizeForMenu(Rect destinationBounds) {
        if (!isInPip()) {
            return;
        }
        mPipMenuController.movePipMenu(null, null, destinationBounds);
        mPipMenuController.updateMenuBounds(destinationBounds);
    }

    private void prepareFinishResizeTransaction(Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction,
            SurfaceControl.Transaction tx,
            WindowContainerTransaction wct) {
        final Rect taskBounds;
        if (isInPipDirection(direction)) {
            // If we are animating from fullscreen using a bounds animation, then reset the
            // activity windowing mode set by WM, and set the task bounds to the final bounds
            taskBounds = destinationBounds;
            wct.setActivityWindowingMode(mToken, WINDOWING_MODE_UNDEFINED);
        } else if (isOutPipDirection(direction)) {
            // If we are animating to fullscreen or split screen, then we need to reset the
            // override bounds on the task to ensure that the task "matches" the parent's bounds.
            taskBounds = null;
            applyWindowingModeChangeOnExit(wct, direction);
        } else {
            // Just a resize in PIP
            taskBounds = destinationBounds;
        }
        mSurfaceTransactionHelper.round(tx, mLeash, isInPip());

        wct.setBounds(mToken, taskBounds);
        wct.setBoundsChangeTransaction(mToken, tx);
    }

    /**
     * Applies the window container transaction to finish a bounds resize.
     *
     * Called by {@link #finishResize(SurfaceControl.Transaction, Rect, int, int)}} once it has
     * finished preparing the transaction. It allows subclasses to modify the transaction before
     * applying it.
     */
    public void applyFinishBoundsResize(@NonNull WindowContainerTransaction wct,
            @PipAnimationController.TransitionDirection int direction, boolean wasPipTopLeft) {
        if (direction == TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN) {
            mSplitScreenOptional.ifPresent(splitScreenController ->
                    splitScreenController.enterSplitScreen(mTaskInfo.taskId, wasPipTopLeft, wct));
        } else {
            mTaskOrganizer.applyTransaction(wct);
        }
    }

    private boolean isPipTopLeft() {
        if (!mSplitScreenOptional.isPresent()) {
            return false;
        }
        final Rect topLeft = new Rect();
        final Rect bottomRight = new Rect();
        mSplitScreenOptional.get().getStageBounds(topLeft, bottomRight);

        return topLeft.contains(mPipBoundsState.getBounds());
    }

    /**
     * The windowing mode to restore to when resizing out of PIP direction. Defaults to undefined
     * and can be overridden to restore to an alternate windowing mode.
     */
    public int getOutPipWindowingMode() {
        // By default, simply reset the windowing mode to undefined.
        return WINDOWING_MODE_UNDEFINED;
    }

    private @Nullable PipAnimationController.PipTransitionAnimator<?> animateResizePip(
            Rect currentBounds, Rect destinationBounds, Rect sourceHintRect,
            @PipAnimationController.TransitionDirection int direction, int durationMs,
            float startingAngle) {
        // Could happen when exitPip
        if (mToken == null || mLeash == null) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Abort animation, invalid leash", TAG);
            return null;
        }
        final int rotationDelta = mWaitForFixedRotation
                ? deltaRotation(mCurrentRotation, mNextRotation)
                : Surface.ROTATION_0;
        if (rotationDelta != Surface.ROTATION_0) {
            sourceHintRect = computeRotatedBounds(rotationDelta, direction, destinationBounds,
                    sourceHintRect);
        }
        Rect baseBounds = direction == TRANSITION_DIRECTION_SNAP_AFTER_RESIZE
                ? mPipBoundsState.getBounds() : currentBounds;
        final boolean existingAnimatorRunning = mPipAnimationController.getCurrentAnimator() != null
                && mPipAnimationController.getCurrentAnimator().isRunning();
        final PipAnimationController.PipTransitionAnimator<?> animator = mPipAnimationController
                .getAnimator(mTaskInfo, mLeash, baseBounds, currentBounds, destinationBounds,
                        sourceHintRect, direction, startingAngle, rotationDelta);
        animator.setTransitionDirection(direction)
                .setPipTransactionHandler(mPipTransactionHandler)
                .setDuration(durationMs);
        if (!existingAnimatorRunning) {
            animator.setPipAnimationCallback(mPipAnimationCallback);
        }
        if (isInPipDirection(direction)) {
            // Similar to auto-enter-pip transition, we use content overlay when there is no
            // source rect hint to enter PiP use bounds animation.
            if (sourceHintRect == null) animator.setUseContentOverlay(mContext);
            // The destination bounds are used for the end rect of animation and the final bounds
            // after animation finishes. So after the animation is started, the destination bounds
            // can be updated to new rotation (computeRotatedBounds has changed the DisplayLayout
            // without affecting the animation.
            if (rotationDelta != Surface.ROTATION_0) {
                animator.setDestinationBounds(mPipBoundsAlgorithm.getEntryDestinationBounds());
            }
        }
        animator.start();
        return animator;
    }

    /** Computes destination bounds in old rotation and returns source hint rect if available. */
    private @Nullable Rect computeRotatedBounds(int rotationDelta, int direction,
            Rect outDestinationBounds, Rect sourceHintRect) {
        if (direction == TRANSITION_DIRECTION_TO_PIP) {
            mPipBoundsState.getDisplayLayout().rotateTo(mContext.getResources(), mNextRotation);
            final Rect displayBounds = mPipBoundsState.getDisplayBounds();
            outDestinationBounds.set(mPipBoundsAlgorithm.getEntryDestinationBounds());
            // Transform the destination bounds to current display coordinates.
            rotateBounds(outDestinationBounds, displayBounds, mNextRotation, mCurrentRotation);
            // When entering PiP (from button navigation mode), adjust the source rect hint by
            // display cutout if applicable.
            if (sourceHintRect != null && mTaskInfo.displayCutoutInsets != null) {
                if (rotationDelta == Surface.ROTATION_270) {
                    sourceHintRect.offset(mTaskInfo.displayCutoutInsets.left,
                            mTaskInfo.displayCutoutInsets.top);
                }
            }
        } else if (direction == TRANSITION_DIRECTION_LEAVE_PIP) {
            final Rect rotatedDestinationBounds = new Rect(outDestinationBounds);
            rotateBounds(rotatedDestinationBounds, mPipBoundsState.getDisplayBounds(),
                    rotationDelta);
            return PipBoundsAlgorithm.getValidSourceHintRect(mPictureInPictureParams,
                    rotatedDestinationBounds);
        }
        return sourceHintRect;
    }

    /**
     * Sync with {@link SplitScreenController} on destination bounds if PiP is going to
     * split screen.
     *
     * @param destinationBoundsOut contain the updated destination bounds if applicable
     * @return {@code true} if destinationBounds is altered for split screen
     */
    private boolean syncWithSplitScreenBounds(Rect destinationBoundsOut, boolean enterSplit) {
        if (!enterSplit || !mSplitScreenOptional.isPresent()) {
            return false;
        }
        final Rect topLeft = new Rect();
        final Rect bottomRight = new Rect();
        mSplitScreenOptional.get().getStageBounds(topLeft, bottomRight);
        final boolean isPipTopLeft = isPipTopLeft();
        destinationBoundsOut.set(isPipTopLeft ? topLeft : bottomRight);
        return true;
    }

    /**
     * Fades out and removes an overlay surface.
     */
    void fadeOutAndRemoveOverlay(SurfaceControl surface, Runnable callback,
            boolean withStartDelay) {
        if (surface == null) {
            return;
        }

        final ValueAnimator animator = ValueAnimator.ofFloat(1.0f, 0.0f);
        animator.setDuration(mCrossFadeAnimationDuration);
        animator.addUpdateListener(animation -> {
            if (mPipTransitionState.getTransitionState() == PipTransitionState.UNDEFINED) {
                // Could happen if onTaskVanished happens during the animation since we may have
                // set a start delay on this animation.
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: Task vanished, skip fadeOutAndRemoveOverlay", TAG);
                animation.removeAllListeners();
                animation.removeAllUpdateListeners();
                animation.cancel();
            } else {
                final float alpha = (float) animation.getAnimatedValue();
                final SurfaceControl.Transaction transaction =
                        mSurfaceControlTransactionFactory.getTransaction();
                transaction.setAlpha(surface, alpha);
                transaction.apply();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                removeContentOverlay(surface, callback);
            }
        });
        animator.setStartDelay(withStartDelay ? CONTENT_OVERLAY_FADE_OUT_DELAY_MS : 0);
        animator.start();
    }

    private void removeContentOverlay(SurfaceControl surface, Runnable callback) {
        if (mPipTransitionState.getTransitionState() == PipTransitionState.UNDEFINED) {
            // Avoid double removal, which is fatal.
            return;
        }
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        tx.remove(surface);
        tx.apply();
        if (callback != null) callback.run();
    }

    private void resetShadowRadius() {
        if (mPipTransitionState.getTransitionState() == PipTransitionState.UNDEFINED) {
            // mLeash is undefined when in PipTransitionState.UNDEFINED
            return;
        }
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        tx.setShadowRadius(mLeash, 0f);
        tx.apply();
    }

    @VisibleForTesting
    public void setSurfaceControlTransactionFactory(
            PipSurfaceTransactionHelper.SurfaceControlTransactionFactory factory) {
        mSurfaceControlTransactionFactory = factory;
    }

    /**
     * Dumps internal states.
     */
    @Override
    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mTaskInfo=" + mTaskInfo);
        pw.println(innerPrefix + "mToken=" + mToken
                + " binder=" + (mToken != null ? mToken.asBinder() : null));
        pw.println(innerPrefix + "mLeash=" + mLeash);
        pw.println(innerPrefix + "mState=" + mPipTransitionState.getTransitionState());
        pw.println(innerPrefix + "mOneShotAnimationType=" + mOneShotAnimationType);
        pw.println(innerPrefix + "mPictureInPictureParams=" + mPictureInPictureParams);
    }

    @Override
    public String toString() {
        return TAG + ":" + taskListenerTypeToString(TASK_LISTENER_TYPE_PIP);
    }
}
