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
import static com.android.systemui.pip.PipAnimationController.DURATION_NONE;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    private final Handler mMainHandler;
    private final ITaskOrganizerController mTaskOrganizerController;
    private final PipBoundsHandler mPipBoundsHandler;
    private final PipAnimationController mPipAnimationController;
    private final List<PipTransitionCallback> mPipTransitionCallbacks = new ArrayList<>();
    private final Rect mDisplayBounds = new Rect();
    private final Rect mLastReportedBounds = new Rect();

    private final PipAnimationController.PipAnimationCallback mPipAnimationCallback =
            new PipAnimationController.PipAnimationCallback() {
        @Override
        public void onPipAnimationStart(IWindowContainer wc,
                PipAnimationController.PipTransitionAnimator animator) {
            mMainHandler.post(() -> {
                for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
                    final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
                    callback.onPipTransitionStarted();
                }
            });
        }

        @Override
        public void onPipAnimationEnd(IWindowContainer wc, SurfaceControl.Transaction tx,
                PipAnimationController.PipTransitionAnimator animator) {
            mMainHandler.post(() -> {
                for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
                    final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
                    callback.onPipTransitionFinished();
                }
            });
            final Rect destinationBounds = animator.getDestinationBounds();
            mLastReportedBounds.set(destinationBounds);
            try {
                final WindowContainerTransaction wct = new WindowContainerTransaction();
                if (animator.shouldScheduleFinishPip()) {
                    wct.scheduleFinishEnterPip(wc, destinationBounds);
                } else {
                    wct.setBounds(wc, destinationBounds);
                }
                wct.setBoundsChangeTransaction(wc, tx);
                mTaskOrganizerController.applyContainerTransaction(wct, null /* ITaskOrganizer */);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to apply container transaction", e);
            }
        }

        @Override
        public void onPipAnimationCancel(IWindowContainer wc,
                PipAnimationController.PipTransitionAnimator animator) {
            mMainHandler.post(() -> {
                for (int i = mPipTransitionCallbacks.size() - 1; i >= 0; i--) {
                    final PipTransitionCallback callback = mPipTransitionCallbacks.get(i);
                    callback.onPipTransitionCanceled();
                }
            });
        }
    };

    private ActivityManager.RunningTaskInfo mTaskInfo;
    private @PipAnimationController.AnimationType int mOneShotAnimationType = ANIM_TYPE_BOUNDS;

    public PipTaskOrganizer(Context context, @NonNull PipBoundsHandler boundsHandler) {
        mMainHandler = new Handler(Looper.getMainLooper());
        mTaskOrganizerController = ActivityTaskManager.getTaskOrganizerController();
        mPipBoundsHandler = boundsHandler;
        mPipAnimationController = new PipAnimationController(context);
    }

    /**
     * Resize the PiP window, animate if the given duration is not {@link #DURATION_NONE}
     */
    public void resizePinnedStack(Rect destinationBounds, int durationMs) {
        Objects.requireNonNull(mTaskInfo, "Requires valid IWindowContainer");
        resizePinnedStackInternal(mTaskInfo.token, false /* scheduleFinishPip */,
                mLastReportedBounds, destinationBounds, durationMs);
    }

    /**
     * Offset the PiP window, animate if the given duration is not {@link #DURATION_NONE}
     */
    public void offsetPinnedStack(Rect originalBounds, int xOffset, int yOffset, int durationMs) {
        if (mTaskInfo == null) {
            Log.w(TAG, "mTaskInfo is not set");
            return;
        }
        final Rect destinationBounds = new Rect(originalBounds);
        destinationBounds.offset(xOffset, yOffset);
        resizePinnedStackInternal(mTaskInfo.token, false /* scheduleFinishPip*/,
                originalBounds, destinationBounds, durationMs);
    }

    /**
     * Registers {@link PipTransitionCallback} to receive transition callbacks.
     */
    public void registerPipTransitionCallback(PipTransitionCallback callback) {
        mPipTransitionCallbacks.add(callback);
    }

    /**
     * Sets the preferred animation type for one time.
     * This is typically used to set the animation type to {@link #ANIM_TYPE_ALPHA}.
     */
    public void setOneShotAnimationType(@PipAnimationController.AnimationType int animationType) {
        mOneShotAnimationType = animationType;
    }

    /**
     * Updates the display dimension with given {@link DisplayInfo}
     */
    public void onDisplayInfoChanged(DisplayInfo displayInfo) {
        final Rect newDisplayBounds = new Rect(0, 0,
                displayInfo.logicalWidth, displayInfo.logicalHeight);
        if (!mDisplayBounds.equals(newDisplayBounds)) {
            // Updates the exiting PiP animation in case the screen rotation changes in the middle.
            // It's a legit case that PiP window is in portrait mode on home screen and
            // the application requests landscape onces back to fullscreen mode.
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
            wct.setBounds(mTaskInfo.token, destinationBounds);
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
        if (mOneShotAnimationType == ANIM_TYPE_BOUNDS) {
            final Rect currentBounds = mTaskInfo.configuration.windowConfiguration.getBounds();
            resizePinnedStackInternal(mTaskInfo.token, true /* scheduleFinishPip */,
                    currentBounds, destinationBounds,
                    PipAnimationController.DURATION_DEFAULT_MS);
        } else if (mOneShotAnimationType == ANIM_TYPE_ALPHA) {
            mMainHandler.post(() -> mPipAnimationController
                    .getAnimator(mTaskInfo.token, true /* scheduleFinishPip */,
                            destinationBounds, 0f, 1f)
                    .setPipAnimationCallback(mPipAnimationCallback)
                    .setDuration(PipAnimationController.DURATION_DEFAULT_MS)
                    .start());
            mOneShotAnimationType = ANIM_TYPE_BOUNDS;
        } else {
            throw new RuntimeException("Unrecognized animation type: " + mOneShotAnimationType);
        }
    }

    @Override
    public void taskVanished(IWindowContainer token) {
        Objects.requireNonNull(token, "Requires valid IWindowContainer");
        if (token.asBinder() != mTaskInfo.token.asBinder()) {
            Log.wtf(TAG, "Unrecognized token: " + token);
            return;
        }
        resizePinnedStackInternal(token, false /* scheduleFinishPip */,
                mLastReportedBounds, mDisplayBounds,
                PipAnimationController.DURATION_DEFAULT_MS);
    }

    @Override
    public void transactionReady(int id, SurfaceControl.Transaction t) {
    }

    @Override
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo info) {
        final Rect destinationBounds = mPipBoundsHandler.getDestinationBounds(
                getAspectRatioOrDefault(info.pictureInPictureParams), null /* bounds */);
        Objects.requireNonNull(destinationBounds, "Missing destination bounds");
        resizePinnedStack(destinationBounds, PipAnimationController.DURATION_DEFAULT_MS);
    }

    private void resizePinnedStackInternal(IWindowContainer wc, boolean scheduleFinishPip,
            Rect currentBounds, Rect destinationBounds, int animationDurationMs) {
        try {
            // Could happen when dismissPip
            if (wc == null || wc.getLeash() == null) {
                Log.w(TAG, "Abort animation, invalid leash");
                return;
            }
            final SurfaceControl leash = wc.getLeash();
            if (animationDurationMs == DURATION_NONE) {
                // Directly resize if no animation duration is set. When fling, wait for final
                // callback to issue the proper WindowContainerTransaction with destination bounds.
                new SurfaceControl.Transaction()
                        .setPosition(leash, destinationBounds.left, destinationBounds.top)
                        .setWindowCrop(leash, destinationBounds.width(), destinationBounds.height())
                        .apply();
            } else {
                mMainHandler.post(() -> mPipAnimationController
                        .getAnimator(wc, scheduleFinishPip, currentBounds, destinationBounds)
                        .setPipAnimationCallback(mPipAnimationCallback)
                        .setDuration(animationDurationMs)
                        .start());
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Abort animation, invalid window container", e);
        } catch (Exception e) {
            Log.e(TAG, "Should not reach here, terrible thing happened", e);
        }
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
