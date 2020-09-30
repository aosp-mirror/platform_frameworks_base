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

package com.android.systemui.pip;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.systemui.pip.PipAnimationController.ANIM_TYPE_ALPHA;
import static com.android.systemui.pip.PipAnimationController.ANIM_TYPE_BOUNDS;
import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_NONE;
import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_REMOVE_STACK;
import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_SAME;
import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_TO_FULLSCREEN;
import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_TO_PIP;
import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_TO_SPLIT_SCREEN;
import static com.android.systemui.pip.PipAnimationController.isInPipDirection;
import static com.android.systemui.pip.PipAnimationController.isOutPipDirection;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceControl;
import android.window.TaskOrganizer;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;
import android.window.WindowContainerTransactionCallback;
import android.window.WindowOrganizer;

import com.android.internal.os.SomeArgs;
import com.android.systemui.R;
import com.android.systemui.pip.phone.PipUpdateThread;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.wm.DisplayController;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages PiP tasks such as resize and offset.
 *
 * This class listens on {@link TaskOrganizer} callbacks for windowing mode change
 * both to and from PiP and issues corresponding animation if applicable.
 * Normally, we apply series of {@link SurfaceControl.Transaction} when the animator is running
 * and files a final {@link WindowContainerTransaction} at the end of the transition.
 *
 * This class is also responsible for general resize/offset PiP operations within SysUI component,
 * see also {@link com.android.systemui.pip.phone.PipMotionHelper}.
 */
@Singleton
public class PipTaskOrganizer extends TaskOrganizer implements
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
        EXITING_PIP(3);

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
    private final PipBoundsHandler mPipBoundsHandler;
    private final PipAnimationController mPipAnimationController;
    private final PipUiEventLogger mPipUiEventLoggerLogger;
    private final List<PipTransitionCallback> mPipTransitionCallbacks = new ArrayList<>();
    private final Rect mLastReportedBounds = new Rect();
    private final int mEnterExitAnimationDuration;
    private final PipSurfaceTransactionHelper mSurfaceTransactionHelper;
    private final Map<IBinder, PipWindowConfigurationCompact> mCompactState = new HashMap<>();
    private final Divider mSplitDivider;

    // These callbacks are called on the update thread
    private final PipAnimationController.PipAnimationCallback mPipAnimationCallback =
            new PipAnimationController.PipAnimationCallback() {
        @Override
        public void onPipAnimationStart(PipAnimationController.PipTransitionAnimator animator) {
            sendOnPipTransitionStarted(animator.getTransitionDirection());
        }

        @Override
        public void onPipAnimationEnd(SurfaceControl.Transaction tx,
                PipAnimationController.PipTransitionAnimator animator) {
            finishResize(tx, animator.getDestinationBounds(), animator.getTransitionDirection(),
                    animator.getAnimationType());
            sendOnPipTransitionFinished(animator.getTransitionDirection());
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

    /**
     * If set to {@code true}, the entering animation will be skipped and we will wait for
     * {@link #onFixedRotationFinished(int)} callback to actually enter PiP.
     */
    private boolean mShouldDeferEnteringPip;

    private @ActivityInfo.ScreenOrientation int mRequestedOrientation;

    @Inject
    public PipTaskOrganizer(Context context, @NonNull PipBoundsHandler boundsHandler,
            @NonNull PipSurfaceTransactionHelper surfaceTransactionHelper,
            @Nullable Divider divider,
            @NonNull DisplayController displayController,
            @NonNull PipAnimationController pipAnimationController,
            @NonNull PipUiEventLogger pipUiEventLogger) {
        mMainHandler = new Handler(Looper.getMainLooper());
        mUpdateHandler = new Handler(PipUpdateThread.get().getLooper(), mUpdateCallbacks);
        mPipBoundsHandler = boundsHandler;
        mEnterExitAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipResizeAnimationDuration);
        mSurfaceTransactionHelper = surfaceTransactionHelper;
        mPipAnimationController = pipAnimationController;
        mPipUiEventLoggerLogger = pipUiEventLogger;
        mSurfaceControlTransactionFactory = SurfaceControl.Transaction::new;
        mSplitDivider = divider;
        displayController.addDisplayWindowListener(this);
    }

    public Handler getUpdateHandler() {
        return mUpdateHandler;
    }

    public Rect getLastReportedBounds() {
        return new Rect(mLastReportedBounds);
    }

    public Rect getCurrentOrAnimatingBounds() {
        PipAnimationController.PipTransitionAnimator animator =
                mPipAnimationController.getCurrentAnimator();
        if (animator != null && animator.isRunning()) {
            return new Rect(animator.getDestinationBounds());
        }
        return getLastReportedBounds();
    }

    public boolean isInPip() {
        return mState.isInPip();
    }

    public boolean isDeferringEnterPipAnimation() {
        return mState.isInPip() && mShouldDeferEnteringPip;
    }

    /**
     * Registers {@link PipTransitionCallback} to receive transition callbacks.
     */
    public void registerPipTransitionCallback(PipTransitionCallback callback) {
        mPipTransitionCallbacks.add(callback);
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
     * Expands PiP to the previous bounds, this is done in two phases using
     * {@link WindowContainerTransaction}
     * - setActivityWindowingMode to either fullscreen or split-secondary at beginning of the
     *   transaction. without changing the windowing mode of the Task itself. This makes sure the
     *   activity render it's final configuration while the Task is still in PiP.
     * - setWindowingMode to undefined at the end of transition
     * @param animationDurationMs duration in millisecond for the exiting PiP transition
     */
    public void exitPip(int animationDurationMs) {
        if (!mState.isInPip() || mToken == null) {
            Log.wtf(TAG, "Not allowed to exitPip in current state"
                    + " mState=" + mState + " mToken=" + mToken);
            return;
        }

        final PipWindowConfigurationCompact config = mCompactState.remove(mToken.asBinder());
        if (config == null) {
            Log.wtf(TAG, "Token not in record, this should not happen mToken=" + mToken);
            return;
        }

        mPipUiEventLoggerLogger.log(
                PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_EXPAND_TO_FULLSCREEN);
        config.syncWithScreenOrientation(mRequestedOrientation,
                mPipBoundsHandler.getDisplayRotation());
        final boolean orientationDiffers = config.getRotation()
                != mPipBoundsHandler.getDisplayRotation();
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        final Rect destinationBounds = config.getBounds();
        final int direction = syncWithSplitScreenBounds(destinationBounds)
                ? TRANSITION_DIRECTION_TO_SPLIT_SCREEN
                : TRANSITION_DIRECTION_TO_FULLSCREEN;
        if (orientationDiffers) {
            mState = State.EXITING_PIP;
            // Send started callback though animation is ignored.
            sendOnPipTransitionStarted(direction);
            // Don't bother doing an animation if the display rotation differs or if it's in
            // a non-supported windowing mode
            applyWindowingModeChangeOnExit(wct, direction);
            WindowOrganizer.applyTransaction(wct);
            // Send finished callback though animation is ignored.
            sendOnPipTransitionFinished(direction);
        } else {
            final SurfaceControl.Transaction tx =
                    mSurfaceControlTransactionFactory.getTransaction();
            mSurfaceTransactionHelper.scale(tx, mLeash, destinationBounds,
                    mLastReportedBounds);
            tx.setWindowCrop(mLeash, destinationBounds.width(), destinationBounds.height());
            wct.setActivityWindowingMode(mToken, direction == TRANSITION_DIRECTION_TO_SPLIT_SCREEN
                    ? WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                    : WINDOWING_MODE_FULLSCREEN);
            wct.setBounds(mToken, destinationBounds);
            wct.setBoundsChangeTransaction(mToken, tx);
            applySyncTransaction(wct, new WindowContainerTransactionCallback() {
                @Override
                public void onTransactionReady(int id, SurfaceControl.Transaction t) {
                    t.apply();
                    scheduleAnimateResizePip(mLastReportedBounds, destinationBounds,
                            null /* sourceHintRect */, direction, animationDurationMs,
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
        if (mSplitDivider != null && direction == TRANSITION_DIRECTION_TO_SPLIT_SCREEN) {
            wct.reparent(mToken, mSplitDivider.getSecondaryRoot(), true /* onTop */);
        }
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
                .getAnimator(mLeash, mLastReportedBounds, 1f, 0f)
                .setTransitionDirection(TRANSITION_DIRECTION_REMOVE_STACK)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setDuration(mEnterExitAnimationDuration)
                .start());
        mCompactState.remove(mToken.asBinder());
        mState = State.EXITING_PIP;
    }

    private void removePipImmediately() {
        try {
            // Reset the task bounds first to ensure the activity configuration is reset as well
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.setBounds(mToken, null);
            WindowOrganizer.applyTransaction(wct);

            ActivityTaskManager.getService().removeStacksInWindowingModes(
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
        mCompactState.put(mToken.asBinder(),
                new PipWindowConfigurationCompact(mTaskInfo.configuration.windowConfiguration));
        mPictureInPictureParams = mTaskInfo.pictureInPictureParams;
        mRequestedOrientation = info.requestedOrientation;

        mPipUiEventLoggerLogger.setTaskInfo(mTaskInfo);
        mPipUiEventLoggerLogger.log(PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_ENTER);

        if (mShouldDeferEnteringPip) {
            if (DEBUG) Log.d(TAG, "Defer entering PiP animation, fixed rotation is ongoing");
            // if deferred, hide the surface till fixed rotation is completed
            final SurfaceControl.Transaction tx =
                    mSurfaceControlTransactionFactory.getTransaction();
            tx.setAlpha(mLeash, 0f);
            tx.show(mLeash);
            tx.apply();
            return;
        }

        final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                mTaskInfo.topActivity, getAspectRatioOrDefault(mPictureInPictureParams),
                null /* bounds */, getMinimalSize(mTaskInfo.topActivityInfo));
        Objects.requireNonNull(destinationBounds, "Missing destination bounds");
        final Rect currentBounds = mTaskInfo.configuration.windowConfiguration.getBounds();

        if (mOneShotAnimationType == ANIM_TYPE_BOUNDS) {
            final Rect sourceHintRect = getValidSourceHintRect(info, currentBounds);
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
    private Rect getValidSourceHintRect(ActivityManager.RunningTaskInfo info, Rect sourceBounds) {
        final Rect sourceHintRect = info.pictureInPictureParams != null
                && info.pictureInPictureParams.hasSourceBoundsHint()
                ? info.pictureInPictureParams.getSourceRectHint()
                : null;
        if (sourceHintRect != null && sourceBounds.contains(sourceHintRect)) {
            return sourceHintRect;
        }
        return null;
    }

    private void enterPipWithAlphaAnimation(Rect destinationBounds, long durationMs) {
        // If we are fading the PIP in, then we should move the pip to the final location as
        // soon as possible, but set the alpha immediately since the transaction can take a
        // while to process
        final SurfaceControl.Transaction tx =
                mSurfaceControlTransactionFactory.getTransaction();
        tx.setAlpha(mLeash, 0f);
        tx.apply();
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setActivityWindowingMode(mToken, WINDOWING_MODE_UNDEFINED);
        wct.setBounds(mToken, destinationBounds);
        wct.scheduleFinishEnterPip(mToken, destinationBounds);
        applySyncTransaction(wct, new WindowContainerTransactionCallback() {
            @Override
            public void onTransactionReady(int id, SurfaceControl.Transaction t) {
                t.apply();
                mUpdateHandler.post(() -> mPipAnimationController
                        .getAnimator(mLeash, destinationBounds, 0f, 1f)
                        .setTransitionDirection(TRANSITION_DIRECTION_TO_PIP)
                        .setPipAnimationCallback(mPipAnimationCallback)
                        .setDuration(durationMs)
                        .start());
                // mState is set right after the animation is kicked off to block any resize
                // requests such as offsetPip that may have been called prior to the transition.
                mState = State.ENTERING_PIP;
            }
        });
    }

    private void sendOnPipTransitionStarted(
            @PipAnimationController.TransitionDirection int direction) {
        runOnMainHandler(() -> {
            for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
                final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
                callback.onPipTransitionStarted(mTaskInfo.baseActivity, direction);
            }
        });
    }

    private void sendOnPipTransitionFinished(
            @PipAnimationController.TransitionDirection int direction) {
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
     *   - as a result of removeStacksInWindowingModes from WM
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
        mShouldDeferEnteringPip = false;
        mPictureInPictureParams = null;
        mState = State.UNDEFINED;
        mPipUiEventLoggerLogger.setTaskInfo(null);
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo info) {
        Objects.requireNonNull(mToken, "onTaskInfoChanged requires valid existing mToken");
        mRequestedOrientation = info.requestedOrientation;
        // check PictureInPictureParams for aspect ratio change.
        final PictureInPictureParams newParams = info.pictureInPictureParams;
        if (newParams == null || !applyPictureInPictureParams(newParams)) {
            Log.d(TAG, "Ignored onTaskInfoChanged with PiP param: " + newParams);
            return;
        }
        final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                info.topActivity, getAspectRatioOrDefault(newParams),
                mLastReportedBounds, getMinimalSize(info.topActivityInfo),
                true /* userCurrentMinEdgeSize */);
        Objects.requireNonNull(destinationBounds, "Missing destination bounds");
        scheduleAnimateResizePip(destinationBounds, mEnterExitAnimationDuration,
                null /* updateBoundsCallback */);
    }

    @Override
    public void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo taskInfo) {
        // Do nothing
    }

    @Override
    public void onFixedRotationStarted(int displayId, int newRotation) {
        mShouldDeferEnteringPip = true;
    }

    @Override
    public void onFixedRotationFinished(int displayId) {
        if (mShouldDeferEnteringPip && mState.isInPip()) {
            final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                    mTaskInfo.topActivity, getAspectRatioOrDefault(mPictureInPictureParams),
                    null /* bounds */, getMinimalSize(mTaskInfo.topActivityInfo));
            // schedule a regular animation to ensure all the callbacks are still being sent
            enterPipWithAlphaAnimation(destinationBounds, 0 /* durationMs */);
        }
        mShouldDeferEnteringPip = false;
    }

    /**
     * @param destinationBoundsOut the current destination bounds will be populated to this param
     */
    @SuppressWarnings("unchecked")
    public void onMovementBoundsChanged(Rect destinationBoundsOut, boolean fromRotation,
            boolean fromImeAdjustment, boolean fromShelfAdjustment,
            WindowContainerTransaction wct) {
        final PipAnimationController.PipTransitionAnimator animator =
                mPipAnimationController.getCurrentAnimator();
        if (animator == null || !animator.isRunning()
                || animator.getTransitionDirection() != TRANSITION_DIRECTION_TO_PIP) {
            if (mState.isInPip() && fromRotation) {
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
                mLastReportedBounds.set(destinationBoundsOut);

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
                    if (!mLastReportedBounds.isEmpty()) {
                        destinationBoundsOut.set(mLastReportedBounds);
                    }
                }
            }
            return;
        }

        final Rect currentDestinationBounds = animator.getDestinationBounds();
        destinationBoundsOut.set(currentDestinationBounds);
        if (!fromImeAdjustment && !fromShelfAdjustment
                && mPipBoundsHandler.getDisplayBounds().contains(currentDestinationBounds)) {
            // no need to update the destination bounds, bail early
            return;
        }

        final Rect newDestinationBounds = mPipBoundsHandler.getDestinationBounds(
                mTaskInfo.topActivity, getAspectRatioOrDefault(mPictureInPictureParams),
                null /* bounds */, getMinimalSize(mTaskInfo.topActivityInfo));
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
        final boolean changed = (mPictureInPictureParams == null) || !Objects.equals(
                mPictureInPictureParams.getAspectRatioRational(), params.getAspectRatioRational());
        if (changed) {
            mPictureInPictureParams = params;
            mPipBoundsHandler.onAspectRatioChanged(params.getAspectRatio());
        }
        return changed;
    }

    /**
     * Animates resizing of the pinned stack given the duration.
     */
    public void scheduleAnimateResizePip(Rect toBounds, int duration,
            Consumer<Rect> updateBoundsCallback) {
        if (mShouldDeferEnteringPip) {
            Log.d(TAG, "skip scheduleAnimateResizePip, entering pip deferred");
            return;
        }
        scheduleAnimateResizePip(mLastReportedBounds, toBounds, null /* sourceHintRect */,
                TRANSITION_DIRECTION_NONE, duration, updateBoundsCallback);
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
        if (mShouldDeferEnteringPip) {
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
        mLastReportedBounds.set(destinationBounds);
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        mSurfaceTransactionHelper
                .crop(tx, mLeash, destinationBounds)
                .round(tx, mLeash, mState.isInPip());
        tx.apply();
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
        tx.apply();
    }

    private void finishResize(SurfaceControl.Transaction tx, Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction,
            @PipAnimationController.AnimationType int type) {
        if (Looper.myLooper() != mUpdateHandler.getLooper()) {
            throw new RuntimeException("Callers should call scheduleResizePip() instead of this "
                    + "directly");
        }
        mLastReportedBounds.set(destinationBounds);
        if (direction == TRANSITION_DIRECTION_REMOVE_STACK) {
            removePipImmediately();
            return;
        } else if (isInPipDirection(direction) && type == ANIM_TYPE_ALPHA) {
            return;
        }

        WindowContainerTransaction wct = new WindowContainerTransaction();
        prepareFinishResizeTransaction(destinationBounds, direction, tx, wct);
        applyFinishBoundsResize(wct, direction);
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
            taskBounds = (direction == TRANSITION_DIRECTION_TO_FULLSCREEN)
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
        WindowOrganizer.applyTransaction(wct);
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
        mPipAnimationController
                .getAnimator(mLeash, currentBounds, destinationBounds, sourceHintRect)
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
                ? mPipBoundsHandler.getDefaultAspectRatio()
                : params.getAspectRatio();
    }

    /**
     * Sync with {@link #mSplitDivider} on destination bounds if PiP is going to split screen.
     *
     * @param destinationBoundsOut contain the updated destination bounds if applicable
     * @return {@code true} if destinationBounds is altered for split screen
     */
    private boolean syncWithSplitScreenBounds(Rect destinationBoundsOut) {
        if (mSplitDivider == null || !mSplitDivider.isDividerVisible()) {
            // bail early if system is not in split screen mode
            return false;
        }
        // PiP window will go to split-secondary mode instead of fullscreen, populates the
        // split screen bounds here.
        destinationBoundsOut.set(
                mSplitDivider.getView().getNonMinimizedSplitScreenSecondaryBounds());
        return true;
    }

    /**
     * Dumps internal states.
     */
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
        pw.println(innerPrefix + "mLastReportedBounds=" + mLastReportedBounds);
        pw.println(innerPrefix + "mInitialState:");
        for (Map.Entry<IBinder, PipWindowConfigurationCompact> e : mCompactState.entrySet()) {
            pw.println(innerPrefix + "  binder=" + e.getKey()
                    + " config=" + e.getValue());
        }
    }

    /**
     * Callback interface for PiP transitions (both from and to PiP mode)
     */
    public interface PipTransitionCallback {
        /**
         * Callback when the pip transition is started.
         */
        void onPipTransitionStarted(ComponentName activity, int direction);

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
