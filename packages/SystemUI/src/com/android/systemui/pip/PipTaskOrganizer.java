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

import static com.android.systemui.pip.PipAnimationController.ANIM_TYPE_ALPHA;
import static com.android.systemui.pip.PipAnimationController.ANIM_TYPE_BOUNDS;
import static com.android.systemui.pip.PipAnimationController.DURATION_DEFAULT_MS;
import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_NONE;
import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_SAME;
import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_TO_FULLSCREEN;
import static com.android.systemui.pip.PipAnimationController.TRANSITION_DIRECTION_TO_PIP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.ITaskOrganizerController;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.DisplayInfo;
import android.view.ITaskOrganizer;
import android.view.IWindowContainer;
import android.view.SurfaceControl;
import android.view.WindowContainerTransaction;

import com.android.internal.os.SomeArgs;
import com.android.systemui.R;
import com.android.systemui.pip.phone.PipUpdateThread;

import java.util.ArrayList;
import java.util.List;
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

    private final Handler mMainHandler;
    private final Handler mUpdateHandler;
    private final ITaskOrganizerController mTaskOrganizerController;
    private final PipBoundsHandler mPipBoundsHandler;
    private final PipAnimationController mPipAnimationController;
    private final List<PipTransitionCallback> mPipTransitionCallbacks = new ArrayList<>();
    private final Rect mDisplayBounds = new Rect();
    private final Rect mLastReportedBounds = new Rect();
    private final int mCornerRadius;

    // These callbacks are called on the update thread
    private final PipAnimationController.PipAnimationCallback mPipAnimationCallback =
            new PipAnimationController.PipAnimationCallback() {
        @Override
        public void onPipAnimationStart(PipAnimationController.PipTransitionAnimator animator) {
            mMainHandler.post(() -> {
                for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
                    final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
                    callback.onPipTransitionStarted();
                }
            });
        }

        @Override
        public void onPipAnimationEnd(SurfaceControl.Transaction tx,
                PipAnimationController.PipTransitionAnimator animator) {
            mMainHandler.post(() -> {
                for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
                    final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
                    callback.onPipTransitionFinished();
                }
            });
            finishResize(tx, animator.getDestinationBounds(), animator.getTransitionDirection());
        }

        @Override
        public void onPipAnimationCancel(PipAnimationController.PipTransitionAnimator animator) {
            mMainHandler.post(() -> {
                for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
                    final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
                    callback.onPipTransitionCanceled();
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
        }
        args.recycle();
        return true;
    };

    private ActivityManager.RunningTaskInfo mTaskInfo;
    private IWindowContainer mToken;
    private SurfaceControl mLeash;
    private boolean mInPip;
    private @PipAnimationController.AnimationType int mOneShotAnimationType = ANIM_TYPE_BOUNDS;

    public PipTaskOrganizer(Context context, @NonNull PipBoundsHandler boundsHandler) {
        mMainHandler = new Handler(Looper.getMainLooper());
        mUpdateHandler = new Handler(PipUpdateThread.get().getLooper(), mUpdateCallbacks);
        mTaskOrganizerController = ActivityTaskManager.getTaskOrganizerController();
        mPipBoundsHandler = boundsHandler;
        mPipAnimationController = new PipAnimationController(context);
        mCornerRadius = context.getResources().getDimensionPixelSize(R.dimen.pip_corner_radius);
    }

    public Handler getUpdateHandler() {
        return mUpdateHandler;
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
     * Updates the display dimension with given {@link DisplayInfo}
     */
    @SuppressWarnings("unchecked")
    public void onDisplayInfoChanged(DisplayInfo displayInfo) {
        final Rect newDisplayBounds = new Rect(0, 0,
                displayInfo.logicalWidth, displayInfo.logicalHeight);
        if (!mDisplayBounds.equals(newDisplayBounds)) {
            // Updates the exiting PiP animation in case the screen rotation changes in the middle.
            // It's a legit case that PiP window is in portrait mode on home screen and
            // the application requests landscape once back to fullscreen mode.
            final PipAnimationController.PipTransitionAnimator animator =
                    mPipAnimationController.getCurrentAnimator();
            if (animator != null
                    && animator.getAnimationType() == ANIM_TYPE_BOUNDS
                    && animator.getDestinationBounds().equals(mDisplayBounds)) {
                animator.updateEndValue(newDisplayBounds);
                animator.setDestinationBounds(newDisplayBounds);
            }
        }
        mDisplayBounds.set(newDisplayBounds);
    }

    /**
     * Callback to issue the final {@link WindowContainerTransaction} on end of movements.
     * @param destinationBounds the final bounds.
     */
    public void onMotionMovementEnd(Rect destinationBounds) {
        try {
            mLastReportedBounds.set(destinationBounds);
            final WindowContainerTransaction wct = new WindowContainerTransaction();
            wct.setBounds(mToken, destinationBounds);
            mTaskOrganizerController.applyContainerTransaction(wct, null /* ITaskOrganizer */);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to apply window container transaction", e);
        }
    }

    @Override
    public void taskAppeared(ActivityManager.RunningTaskInfo info) {
        Objects.requireNonNull(info, "Requires RunningTaskInfo");
        final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                getAspectRatioOrDefault(info.pictureInPictureParams), null /* bounds */);
        Objects.requireNonNull(destinationBounds, "Missing destination bounds");
        mTaskInfo = info;
        mToken = mTaskInfo.token;
        mInPip = true;
        try {
            mLeash = mToken.getLeash();
        } catch (RemoteException e) {
            throw new RuntimeException("Unable to get leash", e);
        }
        if (mOneShotAnimationType == ANIM_TYPE_BOUNDS) {
            final Rect currentBounds = mTaskInfo.configuration.windowConfiguration.getBounds();
            scheduleAnimateResizePip(currentBounds, destinationBounds,
                    TRANSITION_DIRECTION_TO_PIP, DURATION_DEFAULT_MS, null);
        } else if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            mUpdateHandler.post(() -> mPipAnimationController
                    .getAnimator(mLeash, destinationBounds, 0f, 1f)
                    .setTransitionDirection(TRANSITION_DIRECTION_TO_PIP)
                    .setCornerRadius(mCornerRadius)
                    .setPipAnimationCallback(mPipAnimationCallback)
                    .setDuration(DURATION_DEFAULT_MS)
                    .start());
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;
        } else {
            throw new RuntimeException("Unrecognized animation type: " + mOneShotAnimationType);
        }
    }

    @Override
    public void taskVanished(ActivityManager.RunningTaskInfo info) {
        IWindowContainer token = info.token;
        Objects.requireNonNull(token, "Requires valid IWindowContainer");
        if (token.asBinder() != mToken.asBinder()) {
            Log.wtf(TAG, "Unrecognized token: " + token);
            return;
        }
        scheduleAnimateResizePip(mLastReportedBounds, mDisplayBounds,
                TRANSITION_DIRECTION_TO_FULLSCREEN, DURATION_DEFAULT_MS, null);
        mInPip = false;
    }

    @Override
    public void transactionReady(int id, SurfaceControl.Transaction t) {
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo info) {
        final PictureInPictureParams newParams = info.pictureInPictureParams;
        if (!shouldUpdateDestinationBounds(newParams)) {
            Log.d(TAG, "Ignored onTaskInfoChanged with PiP param: " + newParams);
            return;
        }
        final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                getAspectRatioOrDefault(newParams), null /* bounds */);
        Objects.requireNonNull(destinationBounds, "Missing destination bounds");
        scheduleAnimateResizePip(destinationBounds, DURATION_DEFAULT_MS, null);
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
        Objects.requireNonNull(mToken, "Requires valid IWindowContainer");
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
        Objects.requireNonNull(mToken, "Requires valid IWindowContainer");
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = updateBoundsCallback;
        args.arg2 = toBounds;
        mUpdateHandler.sendMessage(mUpdateHandler.obtainMessage(MSG_RESIZE_IMMEDIATE, args));
    }

    /**
     * Finish a intermediate resize operation. This is expected to be called after
     * {@link #scheduleResizePip}.
     */
    public void scheduleFinishResizePip(Rect destinationBounds) {
        Objects.requireNonNull(mToken, "Requires valid IWindowContainer");
        SurfaceControl.Transaction tx = new SurfaceControl.Transaction()
                .setPosition(mLeash, destinationBounds.left, destinationBounds.top)
                .setWindowCrop(mLeash, destinationBounds.width(), destinationBounds.height())
                .setCornerRadius(mLeash, mInPip ? mCornerRadius : 0);
        scheduleFinishResizePip(tx, destinationBounds, TRANSITION_DIRECTION_NONE, null);
    }

    private void scheduleFinishResizePip(SurfaceControl.Transaction tx,
            Rect destinationBounds, @PipAnimationController.TransitionDirection int direction,
            Consumer<Rect> updateBoundsCallback) {
        Objects.requireNonNull(mToken, "Requires valid IWindowContainer");
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
        Objects.requireNonNull(mToken, "Requires valid IWindowContainer");
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
        Objects.requireNonNull(mToken, "Requires valid IWindowContainer");
        // Could happen when dismissPip
        if (mToken == null || mLeash == null) {
            Log.w(TAG, "Abort animation, invalid leash");
            return;
        }
        new SurfaceControl.Transaction()
                .setPosition(mLeash, destinationBounds.left, destinationBounds.top)
                .setWindowCrop(mLeash, destinationBounds.width(), destinationBounds.height())
                .setCornerRadius(mLeash, mInPip ? mCornerRadius : 0)
                .apply();
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
            if (direction == TRANSITION_DIRECTION_TO_PIP) {
                wct.scheduleFinishEnterPip(mToken, destinationBounds);
            } else {
                wct.setBounds(mToken, destinationBounds);
            }
            wct.setBoundsChangeTransaction(mToken, tx);
            mTaskOrganizerController.applyContainerTransaction(wct, null /* ITaskOrganizer */);
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
                .setCornerRadius(mCornerRadius)
                .setPipAnimationCallback(mPipAnimationCallback)
                .setDuration(durationMs)
                .start());
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
        void onPipTransitionStarted();

        /**
         * Callback when the pip transition is finished.
         */
        void onPipTransitionFinished();

        /**
         * Callback when the pip transition is cancelled.
         */
        void onPipTransitionCanceled();
    }
}
