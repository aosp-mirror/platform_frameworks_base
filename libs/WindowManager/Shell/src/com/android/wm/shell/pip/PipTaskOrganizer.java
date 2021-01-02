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

import static com.android.wm.shell.ShellTaskOrganizer.TASK_LISTENER_TYPE_PIP;
import static com.android.wm.shell.ShellTaskOrganizer.taskListenerTypeToString;
import static com.android.wm.shell.pip.PipAnimationController.ANIM_TYPE_ALPHA;
import static com.android.wm.shell.pip.PipAnimationController.ANIM_TYPE_BOUNDS;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_LEAVE_PIP;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_NONE;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_REMOVE_STACK;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_SAME;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_SNAP_AFTER_RESIZE;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_TO_PIP;
import static com.android.wm.shell.pip.PipAnimationController.isInPipDirection;
import static com.android.wm.shell.pip.PipAnimationController.isOutPipDirection;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.util.Rational;
import android.util.Size;
import android.view.SurfaceControl;
import android.window.TaskOrganizer;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;
import android.window.WindowContainerTransactionCallback;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.os.SomeArgs;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.pip.phone.PipMotionHelper;
import com.android.wm.shell.pip.phone.PipUpdateThread;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final int MSG_RESIZE_IMMEDIATE = 1;
    private static final int MSG_RESIZE_ANIMATE = 2;
    private static final int MSG_OFFSET_ANIMATE = 3;
    private static final int MSG_FINISH_RESIZE = 4;
    private static final int MSG_RESIZE_USER = 5;

    // Not a complete set of states but serves what we want right now.
    private enum State {
        UNDEFINED(0),
        TASK_APPEARED(1),
        ENTERING_PIP(2),
        ENTERED_PIP(3),
        EXITING_PIP(4);

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

    private final Handler mMainHandler;
    private final Handler mUpdateHandler;
    private final PipBoundsState mPipBoundsState;
    private final PipBoundsAlgorithm mPipBoundsAlgorithm;
    private final @NonNull PipMenuController mPipMenuController;
    private final PipAnimationController mPipAnimationController;
    private final PipUiEventLogger mPipUiEventLoggerLogger;
    private final List<PipTransitionCallback> mPipTransitionCallbacks = new ArrayList<>();
    private final int mEnterExitAnimationDuration;
    private final PipSurfaceTransactionHelper mSurfaceTransactionHelper;
    private final Map<IBinder, Configuration> mInitialState = new HashMap<>();
    private final Optional<LegacySplitScreen> mSplitScreenOptional;
    protected final ShellTaskOrganizer mTaskOrganizer;

    // These callbacks are called on the update thread
    private final PipAnimationController.PipAnimationCallback mPipAnimationCallback =
            new PipAnimationController.PipAnimationCallback() {
        @Override
        public void onPipAnimationStart(PipAnimationController.PipTransitionAnimator animator) {
            final int direction = animator.getTransitionDirection();
            if (direction == TRANSITION_DIRECTION_TO_PIP) {
                InteractionJankMonitor.getInstance().begin(
                        InteractionJankMonitor.CUJ_LAUNCHER_APP_CLOSE_TO_PIP, 2000);
            }
            sendOnPipTransitionStarted(direction);
        }

        @Override
        public void onPipAnimationEnd(SurfaceControl.Transaction tx,
                PipAnimationController.PipTransitionAnimator animator) {
            final int direction = animator.getTransitionDirection();
            finishResize(tx, animator.getDestinationBounds(), direction,
                    animator.getAnimationType());
            sendOnPipTransitionFinished(direction);
            if (direction == TRANSITION_DIRECTION_TO_PIP) {
                InteractionJankMonitor.getInstance().end(
                        InteractionJankMonitor.CUJ_LAUNCHER_APP_CLOSE_TO_PIP);
            }
        }

        @Override
        public void onPipAnimationCancel(PipAnimationController.PipTransitionAnimator animator) {
            sendOnPipTransitionCancelled(animator.getTransitionDirection());
        }
    };

    @SuppressWarnings("unchecked")
    private final Handler.Callback mUpdateCallbacks = (msg) -> {
        SomeArgs args = (SomeArgs) msg.obj;
        Consumer<Rect> updateBoundsCallback = (Consumer<Rect>) args.arg1;
        switch (msg.what) {
            case MSG_RESIZE_IMMEDIATE: {
                Rect toBounds = (Rect) args.arg2;
                resizePip(toBounds);
                if (updateBoundsCallback != null) {
                    updateBoundsCallback.accept(toBounds);
                }
                break;
            }
            case MSG_RESIZE_ANIMATE: {
                Rect currentBounds = (Rect) args.arg2;
                Rect toBounds = (Rect) args.arg3;
                Rect sourceHintRect = (Rect) args.arg4;
                int duration = args.argi2;
                animateResizePip(currentBounds, toBounds, sourceHintRect,
                        args.argi1 /* direction */, duration);
                if (updateBoundsCallback != null) {
                    updateBoundsCallback.accept(toBounds);
                }
                break;
            }
            case MSG_OFFSET_ANIMATE: {
                Rect originalBounds = (Rect) args.arg2;
                final int offset = args.argi1;
                final int duration = args.argi2;
                offsetPip(originalBounds, 0 /* xOffset */, offset, duration);
                Rect toBounds = new Rect(originalBounds);
                toBounds.offset(0, offset);
                if (updateBoundsCallback != null) {
                    updateBoundsCallback.accept(toBounds);
                }
                break;
            }
            case MSG_FINISH_RESIZE: {
                SurfaceControl.Transaction tx = (SurfaceControl.Transaction) args.arg2;
                Rect toBounds = (Rect) args.arg3;
                finishResize(tx, toBounds, args.argi1 /* direction */, -1);
                if (updateBoundsCallback != null) {
                    updateBoundsCallback.accept(toBounds);
                }
                break;
            }
            case MSG_RESIZE_USER: {
                Rect startBounds = (Rect) args.arg2;
                Rect toBounds = (Rect) args.arg3;
                userResizePip(startBounds, toBounds);
                if (updateBoundsCallback != null) {
                    updateBoundsCallback.accept(toBounds);
                }
                break;
            }
        }
        args.recycle();
        return true;
    };

    private ActivityManager.RunningTaskInfo mTaskInfo;
    private WindowContainerToken mToken;
    private SurfaceControl mLeash;
    private State mState = State.UNDEFINED;
    private @PipAnimationController.AnimationType int mOneShotAnimationType = ANIM_TYPE_BOUNDS;
    private PipSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mSurfaceControlTransactionFactory;
    private PictureInPictureParams mPictureInPictureParams;
    private IntConsumer mOnDisplayIdChangeCallback;

    /**
     * If set to {@code true}, the entering animation will be skipped and we will wait for
     * {@link #onFixedRotationFinished(int)} callback to actually enter PiP.
     */
    private boolean mWaitForFixedRotation;

    /**
     * If set to {@code true}, no entering PiP transition would be kicked off and most likely
     * it's due to the fact that Launcher is handling the transition directly when swiping
     * auto PiP-able Activity to home.
     * See also {@link #startSwipePipToHome(ComponentName, ActivityInfo, PictureInPictureParams)}.
     */
    private boolean mInSwipePipToHomeTransition;

    public PipTaskOrganizer(Context context, @NonNull PipBoundsState pipBoundsState,
            @NonNull PipBoundsAlgorithm boundsHandler,
            @NonNull PipMenuController pipMenuController,
            @NonNull PipSurfaceTransactionHelper surfaceTransactionHelper,
            Optional<LegacySplitScreen> splitScreenOptional,
            @NonNull DisplayController displayController,
            @NonNull PipUiEventLogger pipUiEventLogger,
            @NonNull ShellTaskOrganizer shellTaskOrganizer) {
        mMainHandler = new Handler(Looper.getMainLooper());
        mUpdateHandler = new Handler(PipUpdateThread.get().getLooper(), mUpdateCallbacks);
        mPipBoundsState = pipBoundsState;
        mPipBoundsAlgorithm = boundsHandler;
        mPipMenuController = pipMenuController;
        mEnterExitAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipResizeAnimationDuration);
        mSurfaceTransactionHelper = surfaceTransactionHelper;
        mPipAnimationController = new PipAnimationController(mSurfaceTransactionHelper);
        mPipUiEventLoggerLogger = pipUiEventLogger;
        mSurfaceControlTransactionFactory = SurfaceControl.Transaction::new;
        mSplitScreenOptional = splitScreenOptional;
        mTaskOrganizer = shellTaskOrganizer;
        mTaskOrganizer.addListenerForType(this, TASK_LISTENER_TYPE_PIP);
        displayController.addDisplayWindowListener(this);
    }

    public Handler getUpdateHandler() {
        return mUpdateHandler;
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

    public boolean isDeferringEnterPipAnimation() {
        return mState.isInPip() && mWaitForFixedRotation;
    }

    /**
     * Registers {@link PipTransitionCallback} to receive transition callbacks.
     */
    public void registerPipTransitionCallback(PipTransitionCallback callback) {
        mPipTransitionCallbacks.add(callback);
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
    }

    /**
     * Callback when Launcher starts swipe-pip-to-home operation.
     * @return {@link Rect} for destination bounds.
     */
    public Rect startSwipePipToHome(ComponentName componentName, ActivityInfo activityInfo,
            PictureInPictureParams pictureInPictureParams) {
        mInSwipePipToHomeTransition = true;
        sendOnPipTransitionStarted(componentName, TRANSITION_DIRECTION_TO_PIP);
        setBoundsStateForEntry(componentName, pictureInPictureParams, activityInfo);
        // disable the conflicting transaction from fixed rotation, see also
        // onFixedRotationStarted and onFixedRotationFinished
        mWaitForFixedRotation = false;
        return mPipBoundsAlgorithm.getEntryDestinationBounds();
    }

    /**
     * Callback when launcher finishes swipe-pip-to-home operation.
     * Expect {@link #onTaskAppeared(ActivityManager.RunningTaskInfo, SurfaceControl)} afterwards.
     */
    public void stopSwipePipToHome(ComponentName componentName, Rect destinationBounds) {
        // do nothing if there is no startSwipePipToHome being called before
        if (mInSwipePipToHomeTransition) {
            mPipBoundsState.setBounds(destinationBounds);
        }
    }

    private void setBoundsStateForEntry(ComponentName componentName, PictureInPictureParams params,
            ActivityInfo activityInfo) {
        mPipBoundsState.setLastPipComponentName(componentName);
        mPipBoundsState.setAspectRatio(getAspectRatioOrDefault(params));
        mPipBoundsState.setOverrideMinSize(getMinimalSize(activityInfo));
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

        final Configuration initialConfig = mInitialState.remove(mToken.asBinder());
        if (initialConfig == null) {
            Log.wtf(TAG, "Token not in record, this should not happen mToken=" + mToken);
            return;
        }
        mPipUiEventLoggerLogger.log(
                PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_EXPAND_TO_FULLSCREEN);
        final boolean orientationDiffers = initialConfig.windowConfiguration.getRotation()
                != mPipBoundsState.getDisplayInfo().rotation;
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        final Rect destinationBounds = initialConfig.windowConfiguration.getBounds();
        final int direction = syncWithSplitScreenBounds(destinationBounds)
                ? TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN
                : TRANSITION_DIRECTION_LEAVE_PIP;
        if (orientationDiffers) {
            mState = State.EXITING_PIP;
            // Send started callback though animation is ignored.
            sendOnPipTransitionStarted(direction);
            // Don't bother doing an animation if the display rotation differs or if it's in
            // a non-supported windowing mode
            applyWindowingModeChangeOnExit(wct, direction);
            mTaskOrganizer.applyTransaction(wct);
            // Send finished callback though animation is ignored.
            sendOnPipTransitionFinished(direction);
        } else {
            final SurfaceControl.Transaction tx =
                    mSurfaceControlTransactionFactory.getTransaction();
            mSurfaceTransactionHelper.scale(tx, mLeash, destinationBounds,
                    mPipBoundsState.getBounds());
            tx.setWindowCrop(mLeash, destinationBounds.width(), destinationBounds.height());
            // We set to fullscreen here for now, but later it will be set to UNDEFINED for
            // the proper windowing mode to take place. See #applyWindowingModeChangeOnExit.
            wct.setActivityWindowingMode(mToken,
                    direction == TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN
                    ? WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                    : WINDOWING_MODE_FULLSCREEN);
            wct.setBounds(mToken, destinationBounds);
            wct.setBoundsChangeTransaction(mToken, tx);
            mTaskOrganizer.applySyncTransaction(wct, new WindowContainerTransactionCallback() {
                @Override
                public void onTransactionReady(int id, SurfaceControl.Transaction t) {
                    t.apply();
                    // Make sure to grab the latest source hint rect as it could have been updated
                    // right after applying the windowing mode change.
                    final Rect sourceHintRect = getValidSourceHintRect(mPictureInPictureParams,
                            destinationBounds);
                    scheduleAnimateResizePip(mPipBoundsState.getBounds(), destinationBounds,
                            sourceHintRect, direction, animationDurationMs,
                            null /* updateBoundsCallback */);
                    mState = State.EXITING_PIP;
                }
            });
        }
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
        mUpdateHandler.post(() -> mPipAnimationController
                .getAnimator(mLeash, mPipBoundsState.getBounds(), 1f, 0f)
                .setTransitionDirection(TRANSITION_DIRECTION_REMOVE_STACK)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setDuration(mEnterExitAnimationDuration)
                .start());
        mInitialState.remove(mToken.asBinder());
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
        mInitialState.put(mToken.asBinder(), new Configuration(mTaskInfo.configuration));
        mPictureInPictureParams = mTaskInfo.pictureInPictureParams;
        setBoundsStateForEntry(mTaskInfo.topActivity, mPictureInPictureParams,
                mTaskInfo.topActivityInfo);

        mPipUiEventLoggerLogger.setTaskInfo(mTaskInfo);
        mPipUiEventLoggerLogger.log(PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_ENTER);

        // If the displayId of the task is different than what PipBoundsHandler has, then update
        // it. This is possible if we entered PiP on an external display.
        if (info.displayId != mPipBoundsState.getDisplayInfo().displayId
                && mOnDisplayIdChangeCallback != null) {
            mOnDisplayIdChangeCallback.accept(info.displayId);
        }

        mPipMenuController.attach(leash);

        if (mInSwipePipToHomeTransition) {
            final Rect destinationBounds = mPipBoundsState.getBounds();
            // animation is finished in the Launcher and here we directly apply the final touch.
            applyEnterPipSyncTransaction(destinationBounds, () -> {
                // ensure menu's settled in its final bounds first
                finishResizeForMenu(destinationBounds);
                sendOnPipTransitionFinished(TRANSITION_DIRECTION_TO_PIP);
            });
            mInSwipePipToHomeTransition = false;
            return;
        }

        if (mWaitForFixedRotation) {
            if (DEBUG) Log.d(TAG, "Defer entering PiP animation, fixed rotation is ongoing");
            // if deferred, hide the surface till fixed rotation is completed
            final SurfaceControl.Transaction tx =
                    mSurfaceControlTransactionFactory.getTransaction();
            tx.setAlpha(mLeash, 0f);
            tx.show(mLeash);
            tx.apply();
            return;
        }

        final Rect destinationBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
        Objects.requireNonNull(destinationBounds, "Missing destination bounds");
        final Rect currentBounds = mTaskInfo.configuration.windowConfiguration.getBounds();

        if (mOneShotAnimationType == ANIM_TYPE_BOUNDS) {
            final Rect sourceHintRect = getValidSourceHintRect(info.pictureInPictureParams,
                    currentBounds);
            scheduleAnimateResizePip(currentBounds, destinationBounds, sourceHintRect,
                    TRANSITION_DIRECTION_TO_PIP, mEnterExitAnimationDuration,
                    null /* updateBoundsCallback */);
            mState = State.ENTERING_PIP;
        } else if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            enterPipWithAlphaAnimation(destinationBounds, mEnterExitAnimationDuration);
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;
        } else {
            throw new RuntimeException("Unrecognized animation type: " + mOneShotAnimationType);
        }
    }

    /**
     * Returns the source hint rect if it is valid (if provided and is contained by the current
     * task bounds).
     */
    private Rect getValidSourceHintRect(PictureInPictureParams params, Rect sourceBounds) {
        final Rect sourceHintRect = params != null
                && params.hasSourceBoundsHint()
                ? params.getSourceRectHint()
                : null;
        if (sourceHintRect != null && sourceBounds.contains(sourceHintRect)) {
            return sourceHintRect;
        }
        return null;
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
        applyEnterPipSyncTransaction(destinationBounds, () -> {
            mUpdateHandler.post(() -> mPipAnimationController
                    .getAnimator(mLeash, destinationBounds, 0f, 1f)
                    .setTransitionDirection(TRANSITION_DIRECTION_TO_PIP)
                    .setPipAnimationCallback(mPipAnimationCallback)
                    .setDuration(durationMs)
                    .start());
            // mState is set right after the animation is kicked off to block any resize
            // requests such as offsetPip that may have been called prior to the transition.
            mState = State.ENTERING_PIP;
        });
    }

    private void applyEnterPipSyncTransaction(Rect destinationBounds, Runnable runnable) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setActivityWindowingMode(mToken, WINDOWING_MODE_UNDEFINED);
        wct.setBounds(mToken, destinationBounds);
        wct.scheduleFinishEnterPip(mToken, destinationBounds);
        mTaskOrganizer.applySyncTransaction(wct, new WindowContainerTransactionCallback() {
            @Override
            public void onTransactionReady(int id, SurfaceControl.Transaction t) {
                t.apply();
                if (runnable != null) {
                    runnable.run();
                }
            }
        });
    }

    private void sendOnPipTransitionStarted(
            @PipAnimationController.TransitionDirection int direction) {
        sendOnPipTransitionStarted(mTaskInfo.baseActivity, direction);
    }

    private void sendOnPipTransitionStarted(ComponentName componentName,
            @PipAnimationController.TransitionDirection int direction) {
        if (direction == TRANSITION_DIRECTION_TO_PIP) {
            mState = State.ENTERING_PIP;
        }
        final Rect pipBounds = mPipBoundsState.getBounds();
        runOnMainHandler(() -> {
            for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
                final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
                callback.onPipTransitionStarted(componentName, direction, pipBounds);
            }
        });
    }

    private void sendOnPipTransitionFinished(
            @PipAnimationController.TransitionDirection int direction) {
        if (direction == TRANSITION_DIRECTION_TO_PIP) {
            mState = State.ENTERED_PIP;
        }
        runOnMainHandler(() -> {
            for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
                final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
                callback.onPipTransitionFinished(mTaskInfo.baseActivity, direction);
            }
        });
    }

    private void sendOnPipTransitionCancelled(
            @PipAnimationController.TransitionDirection int direction) {
        runOnMainHandler(() -> {
            for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
                final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
                callback.onPipTransitionCanceled(mTaskInfo.baseActivity, direction);
            }
        });
    }

    private void runOnMainHandler(Runnable r) {
        if (Looper.getMainLooper() == Looper.myLooper()) {
            r.run();
        } else {
            mMainHandler.post(r);
        }
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
        if (!mState.isInPip()) {
            return;
        }
        final WindowContainerToken token = info.token;
        Objects.requireNonNull(token, "Requires valid WindowContainerToken");
        if (token.asBinder() != mToken.asBinder()) {
            Log.wtf(TAG, "Unrecognized token: " + token);
            return;
        }
        mWaitForFixedRotation = false;
        mInSwipePipToHomeTransition = false;
        mPictureInPictureParams = null;
        mState = State.UNDEFINED;
        mPipUiEventLoggerLogger.setTaskInfo(null);
        mPipMenuController.detach();
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo info) {
        Objects.requireNonNull(mToken, "onTaskInfoChanged requires valid existing mToken");
        mPipBoundsState.setLastPipComponentName(info.topActivity);
        mPipBoundsState.setOverrideMinSize(getMinimalSize(info.topActivityInfo));
        final PictureInPictureParams newParams = info.pictureInPictureParams;
        if (newParams == null || !applyPictureInPictureParams(newParams)) {
            Log.d(TAG, "Ignored onTaskInfoChanged with PiP param: " + newParams);
            return;
        }
        // Aspect ratio changed, re-calculate bounds if valid.
        final Rect destinationBounds = mPipBoundsAlgorithm.getAdjustedDestinationBounds(
                mPipBoundsState.getBounds(), mPipBoundsState.getAspectRatio());
        Objects.requireNonNull(destinationBounds, "Missing destination bounds");
        scheduleAnimateResizePip(destinationBounds, mEnterExitAnimationDuration,
                null /* updateBoundsCallback */);
    }

    @Override
    public void onFixedRotationStarted(int displayId, int newRotation) {
        mWaitForFixedRotation = true;
    }

    @Override
    public void onFixedRotationFinished(int displayId) {
        if (mWaitForFixedRotation && mState.isInPip()) {
            final Rect destinationBounds = mPipBoundsAlgorithm.getEntryDestinationBounds();
            // schedule a regular animation to ensure all the callbacks are still being sent
            enterPipWithAlphaAnimation(destinationBounds, 0 /* durationMs */);
        }
        mWaitForFixedRotation = false;
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
            if (mState.isInPip() && fromRotation) {
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
            animator.updateEndValue(newDestinationBounds);
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
        if (mWaitForFixedRotation) {
            Log.d(TAG, "skip scheduleAnimateResizePip, entering pip deferred");
            return;
        }
        scheduleAnimateResizePip(mPipBoundsState.getBounds(), toBounds, null /* sourceHintRect */,
                TRANSITION_DIRECTION_NONE, duration, updateBoundsCallback);
    }

    /**
     * Animates resizing of the pinned stack given the duration and start bounds.
     * This is used when the starting bounds is not the current PiP bounds.
     */
    public void scheduleAnimateResizePip(Rect fromBounds, Rect toBounds, int duration,
            Consumer<Rect> updateBoundsCallback) {
        if (mWaitForFixedRotation) {
            Log.d(TAG, "skip scheduleAnimateResizePip, entering pip deferred");
            return;
        }
        scheduleAnimateResizePip(fromBounds, toBounds, null /* sourceHintRect */,
                TRANSITION_DIRECTION_SNAP_AFTER_RESIZE, duration, updateBoundsCallback);
    }

    private void scheduleAnimateResizePip(Rect currentBounds, Rect destinationBounds,
            Rect sourceHintRect, @PipAnimationController.TransitionDirection int direction,
            int durationMs, Consumer<Rect> updateBoundsCallback) {
        if (!mState.isInPip()) {
            // TODO: tend to use shouldBlockResizeRequest here as well but need to consider
            // the fact that when in exitPip, scheduleAnimateResizePip is executed in the window
            // container transaction callback and we want to set the mState immediately.
            return;
        }

        SomeArgs args = SomeArgs.obtain();
        args.arg1 = updateBoundsCallback;
        args.arg2 = currentBounds;
        args.arg3 = destinationBounds;
        args.arg4 = sourceHintRect;
        args.argi1 = direction;
        args.argi2 = durationMs;
        mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_RESIZE_ANIMATE, args));
    }

    /**
     * Directly perform manipulation/resize on the leash. This will not perform any
     * {@link WindowContainerTransaction} until {@link #scheduleFinishResizePip} is called.
     */
    public void scheduleResizePip(Rect toBounds, Consumer<Rect> updateBoundsCallback) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = updateBoundsCallback;
        args.arg2 = toBounds;
        mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_RESIZE_IMMEDIATE, args));
    }

    /**
     * Directly perform a scaled matrix transformation on the leash. This will not perform any
     * {@link WindowContainerTransaction} until {@link #scheduleFinishResizePip} is called.
     */
    public void scheduleUserResizePip(Rect startBounds, Rect toBounds,
            Consumer<Rect> updateBoundsCallback) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = updateBoundsCallback;
        args.arg2 = startBounds;
        args.arg3 = toBounds;
        mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_RESIZE_USER, args));
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

    private void scheduleFinishResizePip(Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction,
            Consumer<Rect> updateBoundsCallback) {
        if (mState.shouldBlockResizeRequest()) {
            return;
        }

        SomeArgs args = SomeArgs.obtain();
        args.arg1 = updateBoundsCallback;
        args.arg2 = createFinishResizeSurfaceTransaction(
                destinationBounds);
        args.arg3 = destinationBounds;
        args.argi1 = direction;
        mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_FINISH_RESIZE, args));
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
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = updateBoundsCallback;
        args.arg2 = originalBounds;
        // offset would be zero if triggered from screen rotation.
        args.argi1 = offset;
        args.argi2 = duration;
        mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_OFFSET_ANIMATE, args));
    }

    private void offsetPip(Rect originalBounds, int xOffset, int yOffset, int durationMs) {
        if (Looper.myLooper() != mUpdateHandler.getLooper()) {
            throw new RuntimeException("Callers should call scheduleOffsetPip() instead of this "
                    + "directly");
        }
        if (mTaskInfo == null) {
            Log.w(TAG, "mTaskInfo is not set");
            return;
        }
        final Rect destinationBounds = new Rect(originalBounds);
        destinationBounds.offset(xOffset, yOffset);
        animateResizePip(originalBounds, destinationBounds, null /* sourceHintRect */,
                TRANSITION_DIRECTION_SAME, durationMs);
    }

    private void resizePip(Rect destinationBounds) {
        if (Looper.myLooper() != mUpdateHandler.getLooper()) {
            throw new RuntimeException("Callers should call scheduleResizePip() instead of this "
                    + "directly");
        }
        // Could happen when exitPip
        if (mToken == null || mLeash == null) {
            Log.w(TAG, "Abort animation, invalid leash");
            return;
        }
        mPipBoundsState.setBounds(destinationBounds);
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        mSurfaceTransactionHelper
                .crop(tx, mLeash, destinationBounds)
                .round(tx, mLeash, mState.isInPip());
        if (mPipMenuController.isMenuVisible()) {
            runOnMainHandler(() ->
                    mPipMenuController.resizePipMenu(mLeash, tx, destinationBounds));
        } else {
            tx.apply();
        }
    }

    private void userResizePip(Rect startBounds, Rect destinationBounds) {
        if (Looper.myLooper() != mUpdateHandler.getLooper()) {
            throw new RuntimeException("Callers should call scheduleUserResizePip() instead of "
                    + "this directly");
        }
        // Could happen when exitPip
        if (mToken == null || mLeash == null) {
            Log.w(TAG, "Abort animation, invalid leash");
            return;
        }

        if (startBounds.isEmpty() || destinationBounds.isEmpty()) {
            Log.w(TAG, "Attempted to user resize PIP to or from empty bounds, aborting.");
            return;
        }

        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        mSurfaceTransactionHelper.scale(tx, mLeash, startBounds, destinationBounds);
        if (mPipMenuController.isMenuVisible()) {
            runOnMainHandler(() ->
                    mPipMenuController.movePipMenu(mLeash, tx, destinationBounds));
        } else {
            tx.apply();
        }
    }

    private void finishResize(SurfaceControl.Transaction tx, Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction,
            @PipAnimationController.AnimationType int type) {
        if (Looper.myLooper() != mUpdateHandler.getLooper()) {
            throw new RuntimeException("Callers should call scheduleResizePip() instead of this "
                    + "directly");
        }
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
        applyFinishBoundsResize(wct, direction);
        finishResizeForMenu(destinationBounds);
    }

    private void finishResizeForMenu(Rect destinationBounds) {
        runOnMainHandler(() -> {
            mPipMenuController.movePipMenu(null, null, destinationBounds);
            mPipMenuController.updateMenuBounds(destinationBounds);
        });
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
            wct.scheduleFinishEnterPip(mToken, destinationBounds);
        } else if (isOutPipDirection(direction)) {
            // If we are animating to fullscreen, then we need to reset the override bounds
            // on the task to ensure that the task "matches" the parent's bounds.
            taskBounds = (direction == TRANSITION_DIRECTION_LEAVE_PIP)
                    ? null : destinationBounds;
            applyWindowingModeChangeOnExit(wct, direction);
        } else {
            // Just a resize in PIP
            taskBounds = destinationBounds;
        }

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

    private void animateResizePip(Rect currentBounds, Rect destinationBounds, Rect sourceHintRect,
            @PipAnimationController.TransitionDirection int direction, int durationMs) {
        if (Looper.myLooper() != mUpdateHandler.getLooper()) {
            throw new RuntimeException("Callers should call scheduleAnimateResizePip() instead of "
                    + "this directly");
        }
        // Could happen when exitPip
        if (mToken == null || mLeash == null) {
            Log.w(TAG, "Abort animation, invalid leash");
            return;
        }
        Rect baseBounds = direction == TRANSITION_DIRECTION_SNAP_AFTER_RESIZE
                ? mPipBoundsState.getBounds() : currentBounds;
        mPipAnimationController
                .getAnimator(mLeash, baseBounds, currentBounds, destinationBounds, sourceHintRect,
                        direction)
                .setTransitionDirection(direction)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setDuration(durationMs)
                .start();
    }

    private Size getMinimalSize(ActivityInfo activityInfo) {
        if (activityInfo == null || activityInfo.windowLayout == null) {
            return null;
        }
        final ActivityInfo.WindowLayout windowLayout = activityInfo.windowLayout;
        // -1 will be populated if an activity specifies defaultWidth/defaultHeight in <layout>
        // without minWidth/minHeight
        if (windowLayout.minWidth > 0 && windowLayout.minHeight > 0) {
            return new Size(windowLayout.minWidth, windowLayout.minHeight);
        }
        return null;
    }

    private float getAspectRatioOrDefault(@Nullable PictureInPictureParams params) {
        return params == null || !params.hasSetAspectRatio()
                ? mPipBoundsAlgorithm.getDefaultAspectRatio()
                : params.getAspectRatio();
    }

    /**
     * Sync with {@link LegacySplitScreen} on destination bounds if PiP is going to split screen.
     *
     * @param destinationBoundsOut contain the updated destination bounds if applicable
     * @return {@code true} if destinationBounds is altered for split screen
     */
    private boolean syncWithSplitScreenBounds(Rect destinationBoundsOut) {
        if (!mSplitScreenOptional.isPresent()) {
            return false;
        }

        LegacySplitScreen legacySplitScreen = mSplitScreenOptional.get();
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
        pw.println(innerPrefix + "mInitialState:");
        for (Map.Entry<IBinder, Configuration> e : mInitialState.entrySet()) {
            pw.println(innerPrefix + "  binder=" + e.getKey()
                    + " winConfig=" + e.getValue().windowConfiguration);
        }
    }

    @Override
    public String toString() {
        return TAG + ":" + taskListenerTypeToString(TASK_LISTENER_TYPE_PIP);
    }

    /**
     * Callback interface for PiP transitions (both from and to PiP mode)
     */
    public interface PipTransitionCallback {
        /**
         * Callback when the pip transition is started.
         */
        void onPipTransitionStarted(ComponentName activity, int direction, Rect pipBounds);

        /**
         * Callback when the pip transition is finished.
         */
        void onPipTransitionFinished(ComponentName activity, int direction);

        /**
         * Callback when the pip transition is cancelled.
         */
        void onPipTransitionCanceled(ComponentName activity, int direction);
    }
}
