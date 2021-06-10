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
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.util.RotationUtils.deltaRotation;
import static android.util.RotationUtils.rotateBounds;

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_PIP;
import static com.android.wm.shell.ShellTaskOrganizer.taskListenerTypeToString;
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
import android.util.Log;
import android.util.Rational;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.TaskOrganizer;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.animation.Interpolators;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ScreenshotUtils;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreenController;
import com.android.wm.shell.pip.phone.PipMotionHelper;
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
        DisplayController.OnDisplaysChangedListener {
    private static final String TAG = PipTaskOrganizer.class.getSimpleName();
    private static final boolean DEBUG = false;
    /**
     * The alpha type is set for swiping to home. But the swiped task may not enter PiP. And if
     * another task enters PiP by non-swipe ways, e.g. call API in foreground or switch to 3-button
     * navigation, then the alpha type is unexpected.
     */
    private static final int ONE_SHOT_ALPHA_ANIMATION_TIMEOUT_MS = 1000;

    // Not a complete set of states but serves what we want right now.
    private enum State {
        UNDEFINED(0),
        TASK_APPEARED(1),
        ENTRY_SCHEDULED(2),
        ENTERING_PIP(3),
        ENTERED_PIP(4),
        EXITING_PIP(5);

        private final int mStateValue;

        State(int value) {
            mStateValue = value;
        }

        private boolean isInPip() {
            return mStateValue >= TASK_APPEARED.mStateValue
                    && mStateValue != EXITING_PIP.mStateValue;
        }

        /**
         * Resize request can be initiated in other component, ignore if we are no longer in PIP,
         * still waiting for animation or we're exiting from it.
         *
         * @return {@code true} if the resize request should be blocked/ignored.
         */
        private boolean shouldBlockResizeRequest() {
            return mStateValue < ENTERING_PIP.mStateValue
                    || mStateValue == EXITING_PIP.mStateValue;
        }
    }

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
    private final Optional<LegacySplitScreenController> mSplitScreenOptional;
    protected final ShellTaskOrganizer mTaskOrganizer;
    protected final ShellExecutor mMainExecutor;

    // These callbacks are called on the update thread
    private final PipAnimationController.PipAnimationCallback mPipAnimationCallback =
            new PipAnimationController.PipAnimationCallback() {
        @Override
        public void onPipAnimationStart(TaskInfo taskInfo,
                PipAnimationController.PipTransitionAnimator animator) {
            final int direction = animator.getTransitionDirection();
            if (direction == TRANSITION_DIRECTION_TO_PIP) {
                // TODO (b//169221267): Add jank listener for transactions without buffer updates.
                //InteractionJankMonitor.getInstance().begin(
                //        InteractionJankMonitor.CUJ_LAUNCHER_APP_CLOSE_TO_PIP, 2000);
            }
            sendOnPipTransitionStarted(direction);
        }

        @Override
        public void onPipAnimationEnd(TaskInfo taskInfo, SurfaceControl.Transaction tx,
                PipAnimationController.PipTransitionAnimator animator) {
            final int direction = animator.getTransitionDirection();
            final int animationType = animator.getAnimationType();
            final Rect destinationBounds = animator.getDestinationBounds();
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
            if (mState != State.EXITING_PIP || isExitPipDirection) {
                // Finish resize as long as we're not exiting PIP, or, if we are, only if this is
                // the end of an exit PIP animation.
                // This is necessary in case there was a resize animation ongoing when exit PIP
                // started, in which case the first resize will be skipped to let the exit
                // operation handle the final resize out of PIP mode. See b/185306679.
                finishResize(tx, destinationBounds, direction, animationType);
                sendOnPipTransitionFinished(direction);
            }
            if (direction == TRANSITION_DIRECTION_TO_PIP) {
                // TODO (b//169221267): Add jank listener for transactions without buffer updates.
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
    private State mState = State.UNDEFINED;
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
     * If set to {@code true}, no entering PiP transition would be kicked off and most likely
     * it's due to the fact that Launcher is handling the transition directly when swiping
     * auto PiP-able Activity to home.
     * See also {@link #startSwipePipToHome(ComponentName, ActivityInfo, PictureInPictureParams)}.
     */
    private boolean mInSwipePipToHomeTransition;

    /**
     * An optional overlay used to mask content changing between an app in/out of PiP, only set if
     * {@link #mInSwipePipToHomeTransition} is true.
     */
    private SurfaceControl mSwipePipToHomeOverlay;

    public PipTaskOrganizer(Context context,
            @NonNull SyncTransactionQueue syncTransactionQueue,
            @NonNull PipBoundsState pipBoundsState,
            @NonNull PipBoundsAlgorithm boundsHandler,
            @NonNull PipMenuController pipMenuController,
            @NonNull PipAnimationController pipAnimationController,
            @NonNull PipSurfaceTransactionHelper surfaceTransactionHelper,
            @NonNull PipTransitionController pipTransitionController,
            Optional<LegacySplitScreenController> splitScreenOptional,
            @NonNull DisplayController displayController,
            @NonNull PipUiEventLogger pipUiEventLogger,
            @NonNull ShellTaskOrganizer shellTaskOrganizer,
            @ShellMainThread ShellExecutor mainExecutor) {
        mContext = context;
        mSyncTransactionQueue = syncTransactionQueue;
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
        mSurfaceControlTransactionFactory = SurfaceControl.Transaction::new;
        mSplitScreenOptional = splitScreenOptional;
        mTaskOrganizer = shellTaskOrganizer;
        mMainExecutor = mainExecutor;

        // TODO: Can be removed once wm components are created on the shell-main thread
        mMainExecutor.execute(() -> {
            mTaskOrganizer.addListenerForType(this, TASK_LISTENER_TYPE_PIP);
        });
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
        return mState.isInPip();
    }

    /**
     * Returns whether the entry animation is waiting to be started.
     */
    public boolean isEntryScheduled() {
        return mState == State.ENTRY_SCHEDULED;
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
        mInSwipePipToHomeTransition = true;
        sendOnPipTransitionStarted(TRANSITION_DIRECTION_TO_PIP);
        setBoundsStateForEntry(componentName, pictureInPictureParams, activityInfo);
        return mPipBoundsAlgorithm.getEntryDestinationBounds();
    }

    /**
     * Callback when launcher finishes swipe-pip-to-home operation.
     * Expect {@link #onTaskAppeared(ActivityManager.RunningTaskInfo, SurfaceControl)} afterwards.
     */
    public void stopSwipePipToHome(ComponentName componentName, Rect destinationBounds,
            SurfaceControl overlay) {
        // do nothing if there is no startSwipePipToHome being called before
        if (mInSwipePipToHomeTransition) {
            mPipBoundsState.setBounds(destinationBounds);
            mSwipePipToHomeOverlay = overlay;
        }
    }

    public SurfaceControl getSurfaceControl() {
        return mLeash;
    }

    private void setBoundsStateForEntry(ComponentName componentName, PictureInPictureParams params,
            ActivityInfo activityInfo) {
        mPipBoundsState.setBoundsStateForEntry(componentName,
                mPipBoundsAlgorithm.getAspectRatioOrDefault(params),
                mPipBoundsAlgorithm.getMinimalSize(activityInfo));
    }

    /**
     * Expands PiP to the previous bounds, this is done in two phases using
     * {@link WindowContainerTransaction}
     * - setActivityWindowingMode to either fullscreen or split-secondary at beginning of the
     *   transaction. without changing the windowing mode of the Task itself. This makes sure the
     *   activity render it's final configuration while the Task is still in PiP.
     * - setWindowingMode to undefined at the end of transition
     * @param animationDurationMs duration in millisecond for the exiting PiP transition
     */
    public void exitPip(int animationDurationMs) {
        if (!mState.isInPip() || mState == State.EXITING_PIP || mToken == null) {
            Log.wtf(TAG, "Not allowed to exitPip in current state"
                    + " mState=" + mState + " mToken=" + mToken);
            return;
        }

        mPipUiEventLoggerLogger.log(
                PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_EXPAND_TO_FULLSCREEN);
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        final Rect destinationBounds = mPipBoundsState.getDisplayBounds();
        final int direction = syncWithSplitScreenBounds(destinationBounds)
                ? TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN
                : TRANSITION_DIRECTION_LEAVE_PIP;
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        mSurfaceTransactionHelper.scale(tx, mLeash, destinationBounds, mPipBoundsState.getBounds());
        tx.setWindowCrop(mLeash, destinationBounds.width(), destinationBounds.height());
        // We set to fullscreen here for now, but later it will be set to UNDEFINED for
        // the proper windowing mode to take place. See #applyWindowingModeChangeOnExit.
        wct.setActivityWindowingMode(mToken,
                direction == TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN
                        ? WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                        : WINDOWING_MODE_FULLSCREEN);
        wct.setBounds(mToken, destinationBounds);
        wct.setBoundsChangeTransaction(mToken, tx);
        // Set the exiting state first so if there is fixed rotation later, the running animation
        // won't be interrupted by alpha animation for existing PiP.
        mState = State.EXITING_PIP;

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            mPipTransitionController.startTransition(destinationBounds, wct);
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

    private void applyWindowingModeChangeOnExit(WindowContainerTransaction wct, int direction) {
        // Reset the final windowing mode.
        wct.setWindowingMode(mToken, getOutPipWindowingMode());
        // Simply reset the activity mode set prior to the animation running.
        wct.setActivityWindowingMode(mToken, WINDOWING_MODE_UNDEFINED);
        mSplitScreenOptional.ifPresent(splitScreen -> {
            if (direction == TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN) {
                wct.reparent(mToken, splitScreen.getSecondaryRoot(), true /* onTop */);
            }
        });
    }

    /**
     * Removes PiP immediately.
     */
    public void removePip() {
        if (!mState.isInPip() ||  mToken == null) {
            Log.wtf(TAG, "Not allowed to removePip in current state"
                    + " mState=" + mState + " mToken=" + mToken);
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
        mState = State.EXITING_PIP;
    }

    private void removePipImmediately() {
        try {
            // Reset the task bounds first to ensure the activity configuration is reset as well
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.setBounds(mToken, null);
            mTaskOrganizer.applyTransaction(wct);

            ActivityTaskManager.getService().removeRootTasksInWindowingModes(
                    new int[]{ WINDOWING_MODE_PINNED });
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to remove PiP", e);
        }
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo info, SurfaceControl leash) {
        Objects.requireNonNull(info, "Requires RunningTaskInfo");
        mTaskInfo = info;
        mToken = mTaskInfo.token;
        mState = State.TASK_APPEARED;
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

        if (mInSwipePipToHomeTransition) {
            if (!mWaitForFixedRotation) {
                onEndOfSwipePipToHomeTransition();
            } else {
                Log.d(TAG, "Defer onTaskAppeared-SwipePipToHome until end of fixed rotation.");
            }
            return;
        }

        if (mOneShotAnimationType == ANIM_TYPE_ALPHA
                && SystemClock.uptimeMillis() - mLastOneShotAlphaAnimationTime
                > ONE_SHOT_ALPHA_ANIMATION_TIMEOUT_MS) {
            Log.d(TAG, "Alpha animation is expired. Use bounds animation.");
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;
        }
        if (mWaitForFixedRotation) {
            onTaskAppearedWithFixedRotation();
            return;
        }

        final Rect destinationBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
        Objects.requireNonNull(destinationBounds, "Missing destination bounds");
        final Rect currentBounds = mTaskInfo.configuration.windowConfiguration.getBounds();

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            if (mOneShotAnimationType == ANIM_TYPE_BOUNDS) {
                mPipMenuController.attach(mLeash);
            }
            return;
        }

        if (mOneShotAnimationType == ANIM_TYPE_BOUNDS) {
            mPipMenuController.attach(mLeash);
            final Rect sourceHintRect = PipBoundsAlgorithm.getValidSourceHintRect(
                    info.pictureInPictureParams, currentBounds);
            scheduleAnimateResizePip(currentBounds, destinationBounds, 0 /* startingAngle */,
                    sourceHintRect, TRANSITION_DIRECTION_TO_PIP, mEnterAnimationDuration,
                    null /* updateBoundsCallback */);
            mState = State.ENTERING_PIP;
        } else if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            enterPipWithAlphaAnimation(destinationBounds, mEnterAnimationDuration);
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;
        } else {
            throw new RuntimeException("Unrecognized animation type: " + mOneShotAnimationType);
        }
    }

    private void onTaskAppearedWithFixedRotation() {
        if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            Log.d(TAG, "Defer entering PiP alpha animation, fixed rotation is ongoing");
            // If deferred, hide the surface till fixed rotation is completed.
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
        mState = State.ENTERING_PIP;
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
        mState = State.ENTRY_SCHEDULED;
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
            mState = State.ENTERING_PIP;
        }, null /* boundsChangeTransaction */);
    }

    private void onEndOfSwipePipToHomeTransition() {
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
                fadeOutAndRemoveOverlay(swipeToHomeOverlay);
            }
        }, tx);
        mInSwipePipToHomeTransition = false;
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
            mState = State.ENTERING_PIP;
        }
        mPipTransitionController.sendOnPipTransitionStarted(direction);
    }

    @VisibleForTesting
    void sendOnPipTransitionFinished(
            @PipAnimationController.TransitionDirection int direction) {
        if (direction == TRANSITION_DIRECTION_TO_PIP) {
            mState = State.ENTERED_PIP;
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
     * Note that dismissing PiP is now originated from SystemUI, see {@link #exitPip(int)}.
     * Meanwhile this callback is invoked whenever the task is removed. For instance:
     *   - as a result of removeRootTasksInWindowingModes from WM
     *   - activity itself is died
     * Nevertheless, we simply update the internal state here as all the heavy lifting should
     * have been done in WM.
     */
    @Override
    public void onTaskVanished(ActivityManager.RunningTaskInfo info) {
        if (mState == State.UNDEFINED) {
            return;
        }
        final WindowContainerToken token = info.token;
        Objects.requireNonNull(token, "Requires valid WindowContainerToken");
        if (token.asBinder() != mToken.asBinder()) {
            Log.wtf(TAG, "Unrecognized token: " + token);
            return;
        }
        clearWaitForFixedRotation();
        mInSwipePipToHomeTransition = false;
        mPictureInPictureParams = null;
        mState = State.UNDEFINED;
        // Re-set the PIP bounds to none.
        mPipBoundsState.setBounds(new Rect());
        mPipUiEventLoggerLogger.setTaskInfo(null);
        mPipMenuController.detach();

        if (info.displayId != Display.DEFAULT_DISPLAY && mOnDisplayIdChangeCallback != null) {
            mOnDisplayIdChangeCallback.accept(Display.DEFAULT_DISPLAY);
        }
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo info) {
        Objects.requireNonNull(mToken, "onTaskInfoChanged requires valid existing mToken");
        if (mState != State.ENTERED_PIP && mState != State.EXITING_PIP) {
            Log.d(TAG, "Defer onTaskInfoChange in current state: " + mState);
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
            Log.d(TAG, "Ignored onTaskInfoChanged with PiP param: " + newParams);
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
    public boolean supportSizeCompatUI() {
        // PIP doesn't support size compat.
        return false;
    }

    @Override
    public void onFixedRotationStarted(int displayId, int newRotation) {
        mNextRotation = newRotation;
        mWaitForFixedRotation = true;

        if (mState.isInPip()) {
            // Fade out the existing PiP to avoid jump cut during seamless rotation.
            fadeExistingPip(false /* show */);
        }
    }

    @Override
    public void onFixedRotationFinished(int displayId) {
        if (!mWaitForFixedRotation) {
            return;
        }
        if (mState == State.TASK_APPEARED) {
            if (mInSwipePipToHomeTransition) {
                onEndOfSwipePipToHomeTransition();
            } else {
                // Schedule a regular animation to ensure all the callbacks are still being sent.
                enterPipWithAlphaAnimation(mPipBoundsAlgorithm.getEntryDestinationBounds(),
                        mEnterAnimationDuration);
            }
        } else if (mState == State.ENTERED_PIP && mHasFadeOut) {
            fadeExistingPip(true /* show */);
        } else if (mState == State.ENTERING_PIP && mDeferredAnimEndTransaction != null) {
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

    private void fadeExistingPip(boolean show) {
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
        // note that this can be called when swiping pip to home is happening. For instance,
        // swiping an app in landscape to portrait home. skip this entirely if that's the case.
        if (mInSwipePipToHomeTransition && fromRotation) {
            if (DEBUG) Log.d(TAG, "skip onMovementBoundsChanged due to swipe-pip-to-home");
            return;
        }
        final PipAnimationController.PipTransitionAnimator animator =
                mPipAnimationController.getCurrentAnimator();
        if (animator == null || !animator.isRunning()
                || animator.getTransitionDirection() != TRANSITION_DIRECTION_TO_PIP) {
            final boolean rotatingPip = mState.isInPip() && fromRotation;
            if (rotatingPip && mWaitForFixedRotation && mHasFadeOut) {
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
                mPictureInPictureParams != null ? mPictureInPictureParams.getAspectRatioRational()
                        : null;
        final boolean aspectRatioChanged = !Objects.equals(currentAspectRatio,
                params.getAspectRatioRational());
        mPictureInPictureParams = params;
        if (aspectRatioChanged) {
            mPipBoundsState.setAspectRatio(params.getAspectRatio());
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
            Log.d(TAG, "skip scheduleAnimateResizePip, entering pip deferred");
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
            Log.d(TAG, "skip scheduleAnimateResizePip, entering pip deferred");
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
        if (!mState.isInPip()) {
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
            Log.w(TAG, "Abort animation, invalid leash");
            return;
        }
        mPipBoundsState.setBounds(toBounds);
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        mSurfaceTransactionHelper
                .crop(tx, mLeash, toBounds)
                .round(tx, mLeash, mState.isInPip());
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
            Log.w(TAG, "Abort animation, invalid leash");
            return;
        }

        if (startBounds.isEmpty() || toBounds.isEmpty()) {
            Log.w(TAG, "Attempted to user resize PIP to or from empty bounds, aborting.");
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
        if (mState.shouldBlockResizeRequest()) {
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
                .round(tx, mLeash, mState.isInPip());
        return tx;
    }

    /**
     * Offset the PiP window by a given offset on Y-axis, triggered also from screen rotation.
     */
    public void scheduleOffsetPip(Rect originalBounds, int offset, int duration,
            Consumer<Rect> updateBoundsCallback) {
        if (mState.shouldBlockResizeRequest()) {
            return;
        }
        if (mWaitForFixedRotation) {
            Log.d(TAG, "skip scheduleOffsetPip, entering pip deferred");
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
            Log.w(TAG, "mTaskInfo is not set");
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
                    fadeOutAndRemoveOverlay(snapshotSurface);
                });
            } else {
                applyFinishBoundsResize(wct, direction);
            }
        } else {
            applyFinishBoundsResize(wct, direction);
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
            @PipAnimationController.TransitionDirection int direction) {
        mTaskOrganizer.applyTransaction(wct);
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
            Log.w(TAG, "Abort animation, invalid leash");
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
        final PipAnimationController.PipTransitionAnimator<?> animator = mPipAnimationController
                .getAnimator(mTaskInfo, mLeash, baseBounds, currentBounds, destinationBounds,
                        sourceHintRect, direction, startingAngle, rotationDelta);
        animator.setTransitionDirection(direction)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setPipTransactionHandler(mPipTransactionHandler)
                .setDuration(durationMs)
                .start();
        if (rotationDelta != Surface.ROTATION_0 && direction == TRANSITION_DIRECTION_TO_PIP) {
            // The destination bounds are used for the end rect of animation and the final bounds
            // after animation finishes. So after the animation is started, the destination bounds
            // can be updated to new rotation (computeRotatedBounds has changed the DisplayLayout
            // without affecting the animation.
            animator.setDestinationBounds(mPipBoundsAlgorithm.getEntryDestinationBounds());
        }
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
     * Sync with {@link LegacySplitScreenController} on destination bounds if PiP is going to split
     * screen.
     *
     * @param destinationBoundsOut contain the updated destination bounds if applicable
     * @return {@code true} if destinationBounds is altered for split screen
     */
    private boolean syncWithSplitScreenBounds(Rect destinationBoundsOut) {
        if (!mSplitScreenOptional.isPresent()) {
            return false;
        }

        LegacySplitScreenController legacySplitScreen = mSplitScreenOptional.get();
        if (!legacySplitScreen.isDividerVisible()) {
            // fail early if system is not in split screen mode
            return false;
        }

        // PiP window will go to split-secondary mode instead of fullscreen, populates the
        // split screen bounds here.
        destinationBoundsOut.set(legacySplitScreen.getDividerView()
                .getNonMinimizedSplitScreenSecondaryBounds());
        return true;
    }

    /**
     * Fades out and removes an overlay surface.
     */
    private void fadeOutAndRemoveOverlay(SurfaceControl surface) {
        if (surface == null) {
            return;
        }

        final ValueAnimator animator = ValueAnimator.ofFloat(1.0f, 0.0f);
        animator.setDuration(mCrossFadeAnimationDuration);
        animator.addUpdateListener(animation -> {
            final float alpha = (float) animation.getAnimatedValue();
            final SurfaceControl.Transaction transaction =
                    mSurfaceControlTransactionFactory.getTransaction();
            transaction.setAlpha(surface, alpha);
            transaction.apply();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                final SurfaceControl.Transaction tx =
                        mSurfaceControlTransactionFactory.getTransaction();
                tx.remove(surface);
                tx.apply();
            }
        });
        animator.start();
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
        pw.println(innerPrefix + "mState=" + mState);
        pw.println(innerPrefix + "mOneShotAnimationType=" + mOneShotAnimationType);
        pw.println(innerPrefix + "mPictureInPictureParams=" + mPictureInPictureParams);
    }

    @Override
    public String toString() {
        return TAG + ":" + taskListenerTypeToString(TASK_LISTENER_TYPE_PIP);
    }
}
