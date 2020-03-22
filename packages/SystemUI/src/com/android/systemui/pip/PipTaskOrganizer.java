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
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.systemui.pip.PipAnimationController.ANIM_TYPE_ALPHA;
import static com.android.systemui.pip.PipAnimationController.ANIM_TYPE_BOUNDS;
import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_NONE;
import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_SAME;
import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_TO_FULLSCREEN;
import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_TO_PIP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
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
import android.window.ITaskOrganizer;
import android.window.IWindowContainer;
import android.window.WindowContainerTransaction;
import android.window.WindowOrganizer;

import com.android.internal.os.SomeArgs;
import com.android.systemui.R;
import com.android.systemui.pip.phone.PipUpdateThread;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Manages PiP tasks such as resize and offset.
 *
 * This class listens on {@link ITaskOrganizer} callbacks for windowing mode change
 * both to and from PiP and issues corresponding animation if applicable.
 * Normally, we apply series of {@link SurfaceControl.Transaction} when the animator is running
 * and files a final {@link WindowContainerTransaction} at the end of the transition.
 *
 * This class is also responsible for general resize/offset PiP operations within SysUI component,
 * see also {@link com.android.systemui.pip.phone.PipMotionHelper}.
 */
public class PipTaskOrganizer extends ITaskOrganizer.Stub {
    private static final String TAG = PipTaskOrganizer.class.getSimpleName();

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
    private final Map<IBinder, Rect> mBoundsToRestore = new HashMap<>();

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
            mMainHandler.post(() -> {
                for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
                    final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
                    callback.onPipTransitionFinished(mTaskInfo.baseActivity,
                            animator.getTransitionDirection());
                }
            });
            finishResize(tx, animator.getDestinationBounds(), animator.getTransitionDirection());
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
    private Handler.Callback mUpdateCallbacks = (msg) -> {
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
                finishResize(tx, toBounds, args.argi1 /* direction */);
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
    private IWindowContainer mToken;
    private SurfaceControl mLeash;
    private boolean mInPip;
    private @PipAnimationController.AnimationType int mOneShotAnimationType = ANIM_TYPE_BOUNDS;
    private PipSurfaceTransactionHelper.SurfaceControlTransactionFactory
            mSurfaceControlTransactionFactory;

    public PipTaskOrganizer(Context context, @NonNull PipBoundsHandler boundsHandler,
            @NonNull PipSurfaceTransactionHelper surfaceTransactionHelper) {
        mMainHandler = new Handler(Looper.getMainLooper());
        mUpdateHandler = new Handler(PipUpdateThread.get().getLooper(), mUpdateCallbacks);
        mPipBoundsHandler = boundsHandler;
        mEnterExitAnimationDuration = context.getResources()
                .getInteger(R.integer.config_pipResizeAnimationDuration);
        mSurfaceTransactionHelper = surfaceTransactionHelper;
        mPipAnimationController = new PipAnimationController(context, surfaceTransactionHelper);
        mSurfaceControlTransactionFactory = SurfaceControl.Transaction::new;
    }

    public Handler getUpdateHandler() {
        return mUpdateHandler;
    }

    public Rect getLastReportedBounds() {
        return new Rect(mLastReportedBounds);
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
     * Dismiss PiP, this is done in two phases using {@link WindowContainerTransaction}
     * - setActivityWindowingMode to fullscreen at beginning of the transaction. without changing
     *   the windowing mode of the Task itself. This makes sure the activity render it's fullscreen
     *   configuration while the Task is still in PiP.
     * - setWindowingMode to fullscreen at the end of transition
     * @param animationDurationMs duration in millisecond for the exiting PiP transition
     */
    public void dismissPip(int animationDurationMs) {
        try {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.setActivityWindowingMode(mToken, WINDOWING_MODE_FULLSCREEN);
            WindowOrganizer.applyTransaction(wct);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to apply container transaction", e);
        }
        final Rect destinationBounds = mBoundsToRestore.remove(mToken.asBinder());
        scheduleAnimateResizePip(mLastReportedBounds, destinationBounds,
                TRANSITION_DIRECTION_TO_FULLSCREEN, animationDurationMs,
                null /* updateBoundsCallback */);
        mInPip = false;
    }

    @Override
    public void onTaskAppeared(ActivityManager.RunningTaskInfo info) {
        Objects.requireNonNull(info, "Requires RunningTaskInfo");
        final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                getAspectRatioOrDefault(info.pictureInPictureParams),
                null /* bounds */, getMinimalSize(info.topActivityInfo));
        Objects.requireNonNull(destinationBounds, "Missing destination bounds");
        mTaskInfo = info;
        mToken = mTaskInfo.token;
        mInPip = true;
        try {
            mLeash = mToken.getLeash();
        } catch (RemoteException e) {
            throw new RuntimeException("Unable to get leash", e);
        }
        final Rect currentBounds = mTaskInfo.configuration.windowConfiguration.getBounds();
        mBoundsToRestore.put(mToken.asBinder(), currentBounds);
        if (mOneShotAnimationType == ANIM_TYPE_BOUNDS) {
            scheduleAnimateResizePip(currentBounds, destinationBounds,
                    TRANSITION_DIRECTION_TO_PIP, mEnterExitAnimationDuration,
                    null /* updateBoundsCallback */);
        } else if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            mUpdateHandler.post(() -> mPipAnimationController
                    .getAnimator(mLeash, destinationBounds, 0f, 1f)
                    .setTransitionDirection(TRANSITION_DIRECTION_TO_PIP)
                    .setPipAnimationCallback(mPipAnimationCallback)
                    .setDuration(mEnterExitAnimationDuration)
                    .start());
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;
        } else {
            throw new RuntimeException("Unrecognized animation type: " + mOneShotAnimationType);
        }
    }

    /**
     * Note that dismissing PiP is now originated from SystemUI, see {@link #dismissPip(int)}.
     * Meanwhile this callback is invoked whenever the task is removed. For instance:
     *   - as a result of removeStacksInWindowingModes from WM
     *   - activity itself is died
     */
    @Override
    public void onTaskVanished(ActivityManager.RunningTaskInfo info) {
        IWindowContainer token = info.token;
        Objects.requireNonNull(token, "Requires valid IWindowContainer");
        if (token.asBinder() != mToken.asBinder()) {
            Log.wtf(TAG, "Unrecognized token: " + token);
            return;
        }
        final Rect boundsToRestore = mBoundsToRestore.remove(token.asBinder());
        scheduleAnimateResizePip(mLastReportedBounds, boundsToRestore,
                TRANSITION_DIRECTION_TO_FULLSCREEN, mEnterExitAnimationDuration,
                null /* updateBoundsCallback */);
        mInPip = false;
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo info) {
        final PictureInPictureParams newParams = info.pictureInPictureParams;
        if (!shouldUpdateDestinationBounds(newParams)) {
            Log.d(TAG, "Ignored onTaskInfoChanged with PiP param: " + newParams);
            return;
        }
        final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                getAspectRatioOrDefault(newParams),
                null /* bounds */, getMinimalSize(info.topActivityInfo));
        Objects.requireNonNull(destinationBounds, "Missing destination bounds");
        scheduleAnimateResizePip(destinationBounds, mEnterExitAnimationDuration,
                null /* updateBoundsCallback */);
    }

    @Override
    public void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo taskInfo) {
        // Do nothing
    }

    /**
     * @return {@code true} if the aspect ratio is changed since no other parameters within
     * {@link PictureInPictureParams} would affect the bounds.
     */
    private boolean shouldUpdateDestinationBounds(PictureInPictureParams params) {
        if (params == null || mTaskInfo.pictureInPictureParams == null) {
            return params != mTaskInfo.pictureInPictureParams;
        }
        return !Objects.equals(mTaskInfo.pictureInPictureParams.getAspectRatioRational(),
                params.getAspectRatioRational());
    }

    /**
     * Animates resizing of the pinned stack given the duration.
     */
    public void scheduleAnimateResizePip(Rect toBounds, int duration,
            Consumer<Rect> updateBoundsCallback) {
        scheduleAnimateResizePip(mLastReportedBounds, toBounds,
                TRANSITION_DIRECTION_NONE, duration, updateBoundsCallback);
    }

    private void scheduleAnimateResizePip(Rect currentBounds, Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction, int durationMs,
            Consumer<Rect> updateBoundsCallback) {
        if (!mInPip) {
            // Ignore animation when we are no longer in PIP
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
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        mSurfaceTransactionHelper
                .crop(tx, mLeash, destinationBounds)
                .resetScale(tx, mLeash, destinationBounds)
                .round(tx, mLeash, mInPip);
        scheduleFinishResizePip(tx, destinationBounds, TRANSITION_DIRECTION_NONE, null);
    }

    private void scheduleFinishResizePip(SurfaceControl.Transaction tx,
            Rect destinationBounds, @PipAnimationController.TransitionDirection int direction,
            Consumer<Rect> updateBoundsCallback) {
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
            // Ignore offsets when we are no longer in PIP
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
        // Could happen when dismissPip
        if (mToken == null || mLeash == null) {
            Log.w(TAG, "Abort animation, invalid leash");
            return;
        }
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
        // Could happen when dismissPip
        if (mToken == null || mLeash == null) {
            Log.w(TAG, "Abort animation, invalid leash");
            return;
        }
        final SurfaceControl.Transaction tx = mSurfaceControlTransactionFactory.getTransaction();
        mSurfaceTransactionHelper.scale(tx, mLeash, startBounds, destinationBounds);
        tx.apply();
    }

    private void finishResize(SurfaceControl.Transaction tx, Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction) {
        if (Looper.myLooper() != mUpdateHandler.getLooper()) {
            throw new RuntimeException("Callers should call scheduleResizePip() instead of this "
                    + "directly");
        }
        mLastReportedBounds.set(destinationBounds);
        try {
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            final Rect taskBounds;
            if (direction == TRANSITION_DIRECTION_TO_FULLSCREEN) {
                // If we are animating to fullscreen, then we need to reset the override bounds
                // on the task to ensure that the task "matches" the parent's bounds, this applies
                // also to the final windowing mode, which should be reset to undefined rather than
                // fullscreen.
                taskBounds = null;
                wct.setWindowingMode(mToken, WINDOWING_MODE_UNDEFINED)
                        .setActivityWindowingMode(mToken, WINDOWING_MODE_UNDEFINED);
            } else {
                taskBounds = destinationBounds;
            }
            if (direction == TRANSITION_DIRECTION_TO_PIP) {
                wct.scheduleFinishEnterPip(mToken, taskBounds);
            } else {
                wct.setBounds(mToken, taskBounds);
            }
            wct.setBoundsChangeTransaction(mToken, tx);
            WindowOrganizer.applyTransaction(wct);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to apply container transaction", e);
        }
    }

    private void animateResizePip(Rect currentBounds, Rect destinationBounds,
            @PipAnimationController.TransitionDirection int direction, int durationMs) {
        if (Looper.myLooper() != mUpdateHandler.getLooper()) {
            throw new RuntimeException("Callers should call scheduleAnimateResizePip() instead of "
                    + "this directly");
        }
        // Could happen when dismissPip
        if (mToken == null || mLeash == null) {
            Log.w(TAG, "Abort animation, invalid leash");
            return;
        }
        mUpdateHandler.post(() -> mPipAnimationController
                .getAnimator(mLeash, currentBounds, destinationBounds)
                .setTransitionDirection(direction)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setDuration(durationMs)
                .start());
    }

    private Size getMinimalSize(ActivityInfo activityInfo) {
        if (activityInfo == null || activityInfo.windowLayout == null) {
            return null;
        }
        final ActivityInfo.WindowLayout windowLayout = activityInfo.windowLayout;
        return new Size(windowLayout.minWidth, windowLayout.minHeight);
    }

    private float getAspectRatioOrDefault(@Nullable PictureInPictureParams params) {
        return params == null
                ? mPipBoundsHandler.getDefaultAspectRatio()
                : params.getAspectRatio();
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
