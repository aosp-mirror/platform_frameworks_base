/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.MathUtils;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.SyncRtSurfaceTransactionApplier.SurfaceParams;
import android.view.View;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.systemui.Interpolators;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.NotificationPanelViewController;
import com.android.systemui.statusbar.phone.NotificationShadeWindowViewController;

import java.util.concurrent.Executor;

/**
 * A class that allows activities to be launched in a seamless way where the notification
 * transforms nicely into the starting window.
 */
public class ActivityLaunchAnimator {

    private static final int ANIMATION_DURATION = 400;
    public static final long ANIMATION_DURATION_FADE_CONTENT = 67;
    public static final long ANIMATION_DURATION_FADE_APP = 200;
    public static final long ANIMATION_DELAY_ICON_FADE_IN = ANIMATION_DURATION -
            CollapsedStatusBarFragment.FADE_IN_DURATION - CollapsedStatusBarFragment.FADE_IN_DELAY
            - 16;
    private static final long LAUNCH_TIMEOUT = 500;
    private final NotificationPanelViewController mNotificationPanel;
    private final NotificationListContainer mNotificationContainer;
    private final float mWindowCornerRadius;
    private final NotificationShadeWindowViewController mNotificationShadeWindowViewController;
    private final NotificationShadeDepthController mDepthController;
    private final Executor mMainExecutor;
    private Callback mCallback;
    private final Runnable mTimeoutRunnable = () -> {
        setAnimationPending(false);
        mCallback.onExpandAnimationTimedOut();
    };
    private boolean mAnimationPending;
    private boolean mAnimationRunning;
    private boolean mIsLaunchForActivity;

    public ActivityLaunchAnimator(
            NotificationShadeWindowViewController notificationShadeWindowViewController,
            Callback callback,
            NotificationPanelViewController notificationPanel,
            NotificationShadeDepthController depthController,
            NotificationListContainer container,
            Executor mainExecutor) {
        mNotificationPanel = notificationPanel;
        mNotificationContainer = container;
        mDepthController = depthController;
        mNotificationShadeWindowViewController = notificationShadeWindowViewController;
        mCallback = callback;
        mMainExecutor = mainExecutor;
        mWindowCornerRadius = ScreenDecorationsUtils
                .getWindowCornerRadius(mNotificationShadeWindowViewController.getView()
                        .getResources());
    }

    public RemoteAnimationAdapter getLaunchAnimation(
            View sourceView, boolean occluded) {
        if (!(sourceView instanceof ExpandableNotificationRow)
                || !mCallback.areLaunchAnimationsEnabled() || occluded) {
            return null;
        }
        AnimationRunner animationRunner = new AnimationRunner(
                (ExpandableNotificationRow) sourceView, mMainExecutor);
        return new RemoteAnimationAdapter(animationRunner, ANIMATION_DURATION,
                ANIMATION_DURATION - 150 /* statusBarTransitionDelay */);
    }

    public boolean isAnimationPending() {
        return mAnimationPending;
    }

    /**
     * Set the launch result the intent requested
     *
     * @param launchResult the launch result
     * @param wasIntentActivity was this launch for an activity
     */
    public void setLaunchResult(int launchResult, boolean wasIntentActivity) {
        mIsLaunchForActivity = wasIntentActivity;
        setAnimationPending((launchResult == ActivityManager.START_TASK_TO_FRONT
                || launchResult == ActivityManager.START_SUCCESS)
                        && mCallback.areLaunchAnimationsEnabled());
    }

    public boolean isLaunchForActivity() {
        return mIsLaunchForActivity;
    }

    private void setAnimationPending(boolean pending) {
        mAnimationPending = pending;
        mNotificationShadeWindowViewController.setExpandAnimationPending(pending);
        if (pending) {
            mNotificationShadeWindowViewController.getView().postDelayed(mTimeoutRunnable,
                    LAUNCH_TIMEOUT);
        } else {
            mNotificationShadeWindowViewController.getView().removeCallbacks(mTimeoutRunnable);
        }
    }

    public boolean isAnimationRunning() {
        return mAnimationRunning;
    }

    class AnimationRunner extends IRemoteAnimationRunner.Stub {

        private final ExpandAnimationParameters mParams = new ExpandAnimationParameters();
        private final Rect mWindowCrop = new Rect();
        private final float mNotificationCornerRadius;
        private final Executor mMainExecutor;
        @Nullable private ExpandableNotificationRow mSourceNotification;
        @Nullable private SyncRtSurfaceTransactionApplier mSyncRtTransactionApplier;
        private float mCornerRadius;
        private boolean mIsFullScreenLaunch = true;

        AnimationRunner(ExpandableNotificationRow sourceNotification, Executor mainExecutor) {
            mMainExecutor = mainExecutor;
            mSourceNotification = sourceNotification;
            mSyncRtTransactionApplier = new SyncRtSurfaceTransactionApplier(mSourceNotification);
            mNotificationCornerRadius = Math.max(mSourceNotification.getCurrentTopRoundness(),
                    mSourceNotification.getCurrentBottomRoundness());
        }

        @Override
        public void onAnimationStart(RemoteAnimationTarget[] remoteAnimationTargets,
                RemoteAnimationTarget[] remoteAnimationWallpaperTargets,
                IRemoteAnimationFinishedCallback iRemoteAnimationFinishedCallback)
                    throws RemoteException {
            mMainExecutor.execute(() -> {
                RemoteAnimationTarget primary = getPrimaryRemoteAnimationTarget(
                        remoteAnimationTargets);
                if (primary == null || mSourceNotification == null) {
                    setAnimationPending(false);
                    invokeCallback(iRemoteAnimationFinishedCallback);
                    mNotificationPanel.collapse(false /* delayed */, 1.0f /* speedUpFactor */);
                    mSourceNotification = null;
                    mSyncRtTransactionApplier = null;
                    return;
                }

                setExpandAnimationRunning(true);
                mIsFullScreenLaunch = primary.position.y == 0
                        && primary.sourceContainerBounds.height()
                                >= mNotificationPanel.getHeight();
                if (!mIsFullScreenLaunch) {
                    mNotificationPanel.collapseWithDuration(ANIMATION_DURATION);
                }
                mParams.initFrom(mSourceNotification);
                final int targetWidth = primary.sourceContainerBounds.width();
                final int notificationHeight;
                final int notificationWidth;
                notificationHeight = mSourceNotification.getActualHeight()
                        - mSourceNotification.getClipBottomAmount();
                notificationWidth = mSourceNotification.getWidth();
                ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
                anim.setDuration(ANIMATION_DURATION);
                anim.setInterpolator(Interpolators.LINEAR);
                anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        mParams.linearProgress = animation.getAnimatedFraction();
                        float progress = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                                        mParams.linearProgress);
                        int newWidth = (int) MathUtils.lerp(notificationWidth,
                                targetWidth, progress);
                        mParams.left = (int) ((targetWidth - newWidth) / 2.0f);
                        mParams.right = mParams.left + newWidth;
                        mParams.top = (int) MathUtils.lerp(mParams.startPosition[1],
                                primary.position.y, progress);
                        mParams.bottom = (int) MathUtils.lerp(mParams.startPosition[1]
                                        + notificationHeight,
                                primary.position.y + primary.sourceContainerBounds.bottom,
                                progress);
                        mCornerRadius = MathUtils.lerp(mNotificationCornerRadius,
                                mWindowCornerRadius, progress);
                        applyParamsToWindow(primary);
                        applyParamsToNotification(mParams);
                        applyParamsToNotificationShade(mParams);
                    }
                });
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        setExpandAnimationRunning(false);
                        invokeCallback(iRemoteAnimationFinishedCallback);
                    }
                });
                anim.start();
                setAnimationPending(false);
            });
        }

        @Nullable
        ExpandableNotificationRow getRow() {
            return mSourceNotification;
        }

        private void invokeCallback(IRemoteAnimationFinishedCallback callback) {
            try {
                callback.onAnimationFinished();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        private RemoteAnimationTarget getPrimaryRemoteAnimationTarget(
                RemoteAnimationTarget[] remoteAnimationTargets) {
            RemoteAnimationTarget primary = null;
            for (RemoteAnimationTarget app : remoteAnimationTargets) {
                if (app.mode == RemoteAnimationTarget.MODE_OPENING) {
                    primary = app;
                    break;
                }
            }
            return primary;
        }

        private void setExpandAnimationRunning(boolean running) {
            mNotificationPanel.setLaunchingNotification(running);
            if (mSourceNotification != null) {
                mSourceNotification.setExpandAnimationRunning(running);
            }
            mNotificationShadeWindowViewController.setExpandAnimationRunning(running);
            mNotificationContainer.setExpandingNotification(running ? mSourceNotification : null);
            mAnimationRunning = running;
            if (!running) {
                mCallback.onExpandAnimationFinished(mIsFullScreenLaunch);
                applyParamsToNotification(null);
                applyParamsToNotificationShade(null);
                mSourceNotification = null;
                mSyncRtTransactionApplier = null;
            }

        }

        private void applyParamsToNotificationShade(ExpandAnimationParameters params) {
            mNotificationContainer.applyExpandAnimationParams(params);
            mNotificationPanel.applyExpandAnimationParams(params);
            mDepthController.setNotificationLaunchAnimationParams(params);
        }

        private void applyParamsToNotification(ExpandAnimationParameters params) {
            if (mSourceNotification != null) {
                mSourceNotification.applyExpandAnimationParams(params);
            }
        }

        private void applyParamsToWindow(RemoteAnimationTarget app) {
            Matrix m = new Matrix();
            m.postTranslate(0, (float) (mParams.top - app.position.y));
            mWindowCrop.set(mParams.left, 0, mParams.right, mParams.getHeight());
            SurfaceParams params = new SurfaceParams.Builder(app.leash)
                    .withAlpha(1f)
                    .withMatrix(m)
                    .withWindowCrop(mWindowCrop)
                    .withLayer(app.prefixOrderIndex)
                    .withCornerRadius(mCornerRadius)
                    .withVisibility(true)
                    .build();
            if (mSyncRtTransactionApplier != null) {
                mSyncRtTransactionApplier.scheduleApply(true /* earlyWakeup */, params);
            }
        }

        @Override
        public void onAnimationCancelled() throws RemoteException {
            mMainExecutor.execute(() -> {
                setAnimationPending(false);
                mCallback.onLaunchAnimationCancelled();
                mSourceNotification = null;
                mSyncRtTransactionApplier = null;
            });
        }
    };

    public static class ExpandAnimationParameters {
        public float linearProgress;
        int[] startPosition;
        float startTranslationZ;
        int left;
        int top;
        int right;
        int bottom;
        int startClipTopAmount;
        int parentStartClipTopAmount;

        public ExpandAnimationParameters() {
        }

        public int getTop() {
            return top;
        }

        public int getBottom() {
            return bottom;
        }

        public int getWidth() {
            return right - left;
        }

        public int getHeight() {
            return bottom - top;
        }

        public int getTopChange() {
            // We need this compensation to ensure that the QS moves in sync.
            int clipTopAmountCompensation = 0;
            if (startClipTopAmount != 0.0f) {
                clipTopAmountCompensation = (int) MathUtils.lerp(0, startClipTopAmount,
                        Interpolators.FAST_OUT_SLOW_IN.getInterpolation(linearProgress));
            }
            return Math.min(top - startPosition[1] - clipTopAmountCompensation, 0);
        }

        public float getProgress() {
            return linearProgress;
        }

        public float getProgress(long delay, long duration) {
            return MathUtils.constrain((linearProgress * ANIMATION_DURATION - delay)
                    / duration, 0.0f, 1.0f);
        }

        public int getStartClipTopAmount() {
            return startClipTopAmount;
        }

        public int getParentStartClipTopAmount() {
            return parentStartClipTopAmount;
        }

        public float getStartTranslationZ() {
            return startTranslationZ;
        }

        /** Initialize with data pulled from the row. */
        void initFrom(@Nullable ExpandableNotificationRow row) {
            if (row == null) {
                return;
            }
            startPosition = row.getLocationOnScreen();
            startTranslationZ = row.getTranslationZ();
            startClipTopAmount = row.getClipTopAmount();
            if (row.isChildInGroup()) {
                int parentClip = row.getNotificationParent().getClipTopAmount();
                parentStartClipTopAmount = parentClip;
                // We need to calculate how much the child is clipped by the parent
                // because children always have 0 clipTopAmount
                if (parentClip != 0) {
                    float childClip = parentClip - row.getTranslationY();
                    if (childClip > 0.0f) {
                        startClipTopAmount = (int) Math.ceil(childClip);
                    }
                }
            }
        }
    }

    public interface Callback {

        /**
         * Called when the launch animation was cancelled.
         */
        void onLaunchAnimationCancelled();

        /**
         * Called when the launch animation has timed out without starting an actual animation.
         */
        void onExpandAnimationTimedOut();

        /**
         * Called when the expand animation has finished.
         *
         * @param launchIsFullScreen True if this launch was fullscreen, such that now the window
         *                           fills the whole screen
         */
        void onExpandAnimationFinished(boolean launchIsFullScreen);

        /**
         * Are animations currently enabled.
         */
        boolean areLaunchAnimationsEnabled();
    }
}
