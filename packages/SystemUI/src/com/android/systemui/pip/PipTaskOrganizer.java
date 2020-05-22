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
import android.content.res.Configuration;
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

    private final Handler mMainHandler;
    private final Handler mUpdateHandler;
    private final PipBoundsHandler mPipBoundsHandler;
    private final PipAnimationController mPipAnimationController;
    private final List<PipTransitionCallback> mPipTransitionCallbacks = new ArrayList<>();
    private final Rect mLastReportedBounds = new Rect();
    private final int mEnterExitAnimationDuration;
    private final PipSurfaceTransactionHelper mSurfaceTransactionHelper;
    private final Map<IBinder, Configuration> mInitialState = new HashMap<>();
    private final Divider mSplitDivider;

    // These callbacks are called on the update thread
    private final PipAnimationController.PipAnimationCallback mPipAnimationCallback =
            new PipAnimationController.PipAnimationCallback() {
        @Override
        public void onPipAnimationStart(PipAnimationController.PipTransitionAnimator animator) {
            mMainHandler.post(() -> {
                for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
                    final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
                    callback.onPipTransitionStarted(mTaskInfo.baseActivity,
                            animator.getTransitionDirection());
                }
            });
        }

        @Override
        public void onPipAnimationEnd(SurfaceControl.Transaction tx,
                PipAnimationController.PipTransitionAnimator animator) {
            finishResize(tx, animator.getDestinationBounds(), animator.getTransitionDirection(),
                    animator.getAnimationType());
            mMainHandler.post(() -> {
                for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
                    final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
                    callback.onPipTransitionFinished(mTaskInfo.baseActivity,
                            animator.getTransitionDirection());
                }
            });
        }

        @Override
        public void onPipAnimationCancel(PipAnimationController.PipTransitionAnimator animator) {
            mMainHandler.post(() -> {
                for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
                    final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
                    callback.onPipTransitionCanceled(mTaskInfo.baseActivity,
                            animator.getTransitionDirection());
                }
            });
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
                int duration = args.argi2;
                animateResizePip(currentBounds, toBounds, args.argi1 /* direction */, duration);
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
    private boolean mInPip;
    private @PipAnimationController.AnimationType int mOneShotAnimationType = ANIM_TYPE_BOUNDS;
    private PipSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mSurfaceControlTransactionFactory;
    private PictureInPictureParams mPictureInPictureParams;

    /**
     * If set to {@code true}, the entering animation will be skipped and we will wait for
     * {@link #onFixedRotationFinished(int)} callback to actually enter PiP.
     */
    private boolean mShouldDeferEnteringPip;

    @Inject
    public PipTaskOrganizer(Context context, @NonNull PipBoundsHandler boundsHandler,
            @NonNull PipSurfaceTransactionHelper surfaceTransactionHelper,
            @Nullable Divider divider,
            @NonNull DisplayController displayController) {
        mMainHandler = new Handler(Looper.getMainLooper());
        mUpdateHandler = new Handler(PipUpdateThread.get().getLooper(), mUpdateCallbacks);
        mPipBoundsHandler = boundsHandler;
        mEnterExitAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipResizeAnimationDuration);
        mSurfaceTransactionHelper = surfaceTransactionHelper;
        mPipAnimationController = new PipAnimationController(context, surfaceTransactionHelper);
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

    public boolean isInPip() {
        return mInPip;
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
        if (!mInPip || mToken == null) {
            Log.wtf(TAG, "Not allowed to exitPip in current state"
                    + " mInPip=" + mInPip + " mToken=" + mToken);
            return;
        }

        final Configuration initialConfig = mInitialState.remove(mToken.asBinder());
        final boolean orientationDiffers = initialConfig.windowConfiguration.getRotation()
                != mPipBoundsHandler.getDisplayRotation();
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        if (orientationDiffers) {
            // Don't bother doing an animation if the display rotation differs or if it's in
            // a non-supported windowing mode
            wct.setWindowingMode(mToken, WINDOWING_MODE_UNDEFINED);
            wct.setActivityWindowingMode(mToken, WINDOWING_MODE_UNDEFINED);
            WindowOrganizer.applyTransaction(wct);
            mInPip = false;
        } else {
            final Rect destinationBounds = initialConfig.windowConfiguration.getBounds();
            final int direction = syncWithSplitScreenBounds(destinationBounds)
                    ? TRANSITION_DIRECTION_TO_SPLIT_SCREEN
                    : TRANSITION_DIRECTION_TO_FULLSCREEN;
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
                            direction, animationDurationMs, null /* updateBoundsCallback */);
                    mInPip = false;
                }
            });
        }
    }

    /**
     * Removes PiP immediately.
     */
    public void removePip() {
        if (!mInPip || mToken == null) {
            Log.wtf(TAG, "Not allowed to removePip in current state"
                    + " mInPip=" + mInPip + " mToken=" + mToken);
            return;
        }
        getUpdateHandler().post(() -> {
            try {
                ActivityTaskManager.getService().removeStacksInWindowingModes(
                        new int[]{ WINDOWING_MODE_PINNED });
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to remove PiP", e);
            }
        });
        mInitialState.remove(mToken.asBinder());
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo info, SurfaceControl leash) {
        Objects.requireNonNull(info, "Requires RunningTaskInfo");
        mTaskInfo = info;
        mToken = mTaskInfo.token;
        mInPip = true;
        mLeash = leash;
        mInitialState.put(mToken.asBinder(), new Configuration(mTaskInfo.configuration));
        mPictureInPictureParams = mTaskInfo.pictureInPictureParams;

        if (mShouldDeferEnteringPip) {
            if (DEBUG) Log.d(TAG, "Defer entering PiP animation, fixed rotation is ongoing");
            // if deferred, hide the surface till fixed rotation is completed
            final SurfaceControl.Transaction tx =
                    mSurfaceControlTransactionFactory.getTransaction();
            tx.setAlpha(mLeash, 0f);
            tx.apply();
            return;
        }

        final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                mTaskInfo.topActivity, getAspectRatioOrDefault(mPictureInPictureParams),
                null /* bounds */, getMinimalSize(mTaskInfo.topActivityInfo));
        Objects.requireNonNull(destinationBounds, "Missing destination bounds");
        final Rect currentBounds = mTaskInfo.configuration.windowConfiguration.getBounds();

        if (mOneShotAnimationType == ANIM_TYPE_BOUNDS) {
            scheduleAnimateResizePip(currentBounds, destinationBounds,
                    TRANSITION_DIRECTION_TO_PIP, mEnterExitAnimationDuration,
                    null /* updateBoundsCallback */);
        } else if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            enterPipWithAlphaAnimation(destinationBounds, mEnterExitAnimationDuration);
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;
        } else {
            throw new RuntimeException("Unrecognized animation type: " + mOneShotAnimationType);
        }
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
            }
        });
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
        if (!mInPip) {
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
        mInPip = false;
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo info) {
        Objects.requireNonNull(mToken, "onTaskInfoChanged requires valid existing mToken");
        final PictureInPictureParams newParams = info.pictureInPictureParams;
        if (newParams == null || !applyPictureInPictureParams(newParams)) {
            Log.d(TAG, "Ignored onTaskInfoChanged with PiP param: " + newParams);
            return;
        }
        final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                info.topActivity, getAspectRatioOrDefault(newParams),
                null /* bounds */, getMinimalSize(info.topActivityInfo));
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
        if (mShouldDeferEnteringPip && mInPip) {
            final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                    mTaskInfo.topActivity, getAspectRatioOrDefault(mPictureInPictureParams),
                    null /* bounds */, getMinimalSize(mTaskInfo.topActivityInfo));
            // schedule a regular animation to ensure all the callbacks are still being sent
            enterPipWithAlphaAnimation(destinationBounds, 0 /* durationMs */);
        }
        mShouldDeferEnteringPip = false;
    }

    /**
     * TODO(b/152809058): consolidate the display info handling logic in SysUI
     *
     * @param destinationBoundsOut the current destination bounds will be populated to this param
     */
    @SuppressWarnings("unchecked")
    public void onMovementBoundsChanged(Rect destinationBoundsOut, boolean fromRotation,
            boolean fromImeAdjustment, boolean fromShelfAdjustment) {
        final PipAnimationController.PipTransitionAnimator animator =
                mPipAnimationController.getCurrentAnimator();
        if (animator == null || !animator.isRunning()
                || animator.getTransitionDirection() != TRANSITION_DIRECTION_TO_PIP) {
            if (mInPip && fromRotation) {
                // this could happen if rotation finishes before the animation
                mLastReportedBounds.set(destinationBoundsOut);
                scheduleFinishResizePip(mLastReportedBounds);
            } else if (!mLastReportedBounds.isEmpty()) {
                destinationBoundsOut.set(mLastReportedBounds);
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
        final boolean changed = (mPictureInPictureParams == null) ? true : !Objects.equals(
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
        scheduleAnimateResizePip(mLastReportedBounds, toBounds,
                TRANSITION_DIRECTION_NONE, duration, updateBoundsCallback);
    }

    private void scheduleAnimateResizePip(Rect currentBounds, Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction, int durationMs,
            Consumer<Rect> updateBoundsCallback) {
        if (!mInPip) {
            // can be initiated in other component, ignore if we are no longer in PIP
            return;
        }

        SomeArgs args = SomeArgs.obtain();
        args.arg1 = updateBoundsCallback;
        args.arg2 = currentBounds;
        args.arg3 = destinationBounds;
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
        scheduleFinishResizePip(destinationBounds, null);
    }

    /**
     * Same as {@link #scheduleFinishResizePip} but with a callback.
     */
    public void scheduleFinishResizePip(Rect destinationBounds,
            Consumer<Rect> updateBoundsCallback) {
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        mSurfaceTransactionHelper
                .crop(tx, mLeash, destinationBounds)
                .resetScale(tx, mLeash, destinationBounds)
                .round(tx, mLeash, mInPip);
        scheduleFinishResizePip(tx, destinationBounds, TRANSITION_DIRECTION_NONE,
                updateBoundsCallback);
    }

    private void scheduleFinishResizePip(SurfaceControl.Transaction tx,
            Rect destinationBounds, @PipAnimationController.TransitionDirection int direction,
            Consumer<Rect> updateBoundsCallback) {
        if (!mInPip) {
            // can be initiated in other component, ignore if we are no longer in PIP
            return;
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = updateBoundsCallback;
        args.arg2 = tx;
        args.arg3 = destinationBounds;
        args.argi1 = direction;
        mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_FINISH_RESIZE, args));
    }

    /**
     * Offset the PiP window by a given offset on Y-axis, triggered also from screen rotation.
     */
    public void scheduleOffsetPip(Rect originalBounds, int offset, int duration,
            Consumer<Rect> updateBoundsCallback) {
        if (!mInPip) {
            // can be initiated in other component, ignore if we are no longer in PIP
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
        animateResizePip(originalBounds, destinationBounds, TRANSITION_DIRECTION_SAME, durationMs);
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
                .round(tx, mLeash, mInPip);
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
        if (isInPipDirection(direction) && type == ANIM_TYPE_ALPHA) {
            return;
        }

        final WindowContainerTransaction wct = new WindowContainerTransaction();
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
            // As for the final windowing mode, simply reset it to undefined and reset the activity
            // mode set prior to the animation running
            wct.setWindowingMode(mToken, WINDOWING_MODE_UNDEFINED);
            wct.setActivityWindowingMode(mToken, WINDOWING_MODE_UNDEFINED);
            if (mSplitDivider != null && direction == TRANSITION_DIRECTION_TO_SPLIT_SCREEN) {
                wct.reparent(mToken, mSplitDivider.getSecondaryRoot(), true /* onTop */);
            }
        } else {
            // Just a resize in PIP
            taskBounds = destinationBounds;
        }

        wct.setBounds(mToken, taskBounds);
        wct.setBoundsChangeTransaction(mToken, tx);
        WindowOrganizer.applyTransaction(wct);
    }

    private void animateResizePip(Rect currentBounds, Rect destinationBounds,
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
                .getAnimator(mLeash, currentBounds, destinationBounds)
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
        return params == null
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
        pw.println(innerPrefix + "mInPip=" + mInPip);
        pw.println(innerPrefix + "mOneShotAnimationType=" + mOneShotAnimationType);
        pw.println(innerPrefix + "mPictureInPictureParams=" + mPictureInPictureParams);
        pw.println(innerPrefix + "mLastReportedBounds=" + mLastReportedBounds);
        pw.println(innerPrefix + "mInitialState:");
        for (Map.Entry<IBinder, Configuration> e : mInitialState.entrySet()) {
            pw.println(innerPrefix + "  binder=" + e.getKey()
                    + " winConfig=" + e.getValue().windowConfiguration);
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
