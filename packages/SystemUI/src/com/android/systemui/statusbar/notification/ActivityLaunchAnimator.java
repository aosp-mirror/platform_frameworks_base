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

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.systemui.Interpolators;
import com.android.systemui.shared.system.SurfaceControlCompat;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.NotificationPanelView;
import com.android.systemui.statusbar.phone.StatusBarWindowView;

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
    private final NotificationPanelView mNotificationPanel;
    private final NotificationListContainer mNotificationContainer;
    private final StatusBarWindowView mStatusBarWindow;
    private final float mWindowCornerRadius;
    private Callback mCallback;
    private final Runnable mTimeoutRunnable = () -> {
        setAnimationPending(false);
        mCallback.onExpandAnimationTimedOut();
    };
    private boolean mAnimationPending;
    private boolean mAnimationRunning;
    private boolean mIsLaunchForActivity;

    public ActivityLaunchAnimator(StatusBarWindowView statusBarWindow,
            Callback callback,
            NotificationPanelView notificationPanel,
            NotificationListContainer container) {
        mNotificationPanel = notificationPanel;
        mNotificationContainer = container;
        mStatusBarWindow = statusBarWindow;
        mCallback = callback;
        mWindowCornerRadius = ScreenDecorationsUtils
                .getWindowCornerRadius(statusBarWindow.getResources());
    }

    public RemoteAnimationAdapter getLaunchAnimation(
            ExpandableNotificationRow sourceNotification, boolean occluded) {
        if (!mCallback.areLaunchAnimationsEnabled() || occluded) {
            return null;
        }
        AnimationRunner animationRunner = new AnimationRunner(sourceNotification);
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
        mStatusBarWindow.setExpandAnimationPending(pending);
        if (pending) {
            mStatusBarWindow.postDelayed(mTimeoutRunnable, LAUNCH_TIMEOUT);
        } else {
            mStatusBarWindow.removeCallbacks(mTimeoutRunnable);
        }
    }

    public boolean isAnimationRunning() {
        return mAnimationRunning;
    }

    class AnimationRunner extends IRemoteAnimationRunner.Stub {

        private final ExpandableNotificationRow mSourceNotification;
        private final ExpandAnimationParameters mParams;
        private final Rect mWindowCrop = new Rect();
        private final float mNotificationCornerRadius;
        private float mCornerRadius;
        private boolean mIsFullScreenLaunch = true;
        private final SyncRtSurfaceTransactionApplier mSyncRtTransactionApplier;

        public AnimationRunner(ExpandableNotificationRow sourceNofitication) {
            mSourceNotification = sourceNofitication;
            mParams = new ExpandAnimationParameters();
            mSyncRtTransactionApplier = new SyncRtSurfaceTransactionApplier(mSourceNotification);
            mNotificationCornerRadius = Math.max(mSourceNotification.getCurrentTopRoundness(),
                    mSourceNotification.getCurrentBottomRoundness());
        }

        @Override
        public void onAnimationStart(RemoteAnimationTarget[] remoteAnimationTargets,
                IRemoteAnimationFinishedCallback iRemoteAnimationFinishedCallback)
                    throws RemoteException {
            mSourceNotification.post(() -> {
                RemoteAnimationTarget primary = getPrimaryRemoteAnimationTarget(
                        remoteAnimationTargets);
                if (primary == null) {
                    setAnimationPending(false);
                    invokeCallback(iRemoteAnimationFinishedCallback);
                    return;
                }

                setExpandAnimationRunning(true);
                mIsFullScreenLaunch = primary.position.y == 0
                        && primary.sourceContainerBounds.height()
                                >= mNotificationPanel.getHeight();
                if (!mIsFullScreenLaunch) {
                    mNotificationPanel.collapseWithDuration(ANIMATION_DURATION);
                }
                ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
                mParams.startPosition = mSourceNotification.getLocationOnScreen();
                mParams.startTranslationZ = mSourceNotification.getTranslationZ();
                mParams.startClipTopAmount = mSourceNotification.getClipTopAmount();
                if (mSourceNotification.isChildInGroup()) {
                    int parentClip = mSourceNotification
                            .getNotificationParent().getClipTopAmount();
                    mParams.parentStartClipTopAmount = parentClip;
                    // We need to calculate how much the child is clipped by the parent
                    // because children always have 0 clipTopAmount
                    if (parentClip != 0) {
                        float childClip = parentClip
                                - mSourceNotification.getTranslationY();
                        if (childClip > 0.0f) {
                            mParams.startClipTopAmount = (int) Math.ceil(childClip);
                        }
                    }
                }
                int targetWidth = primary.sourceContainerBounds.width();
                int notificationHeight = mSourceNotification.getActualHeight()
                        - mSourceNotification.getClipBottomAmount();
                int notificationWidth = mSourceNotification.getWidth();
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
                        applyParamsToNotificationList(mParams);
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
            mSourceNotification.setExpandAnimationRunning(running);
            mStatusBarWindow.setExpandAnimationRunning(running);
            mNotificationContainer.setExpandingNotification(running ? mSourceNotification : null);
            mAnimationRunning = running;
            if (!running) {
                mCallback.onExpandAnimationFinished(mIsFullScreenLaunch);
                applyParamsToNotification(null);
                applyParamsToNotificationList(null);
            }

        }

        private void applyParamsToNotificationList(ExpandAnimationParameters params) {
            mNotificationContainer.applyExpandAnimationParams(params);
            mNotificationPanel.applyExpandAnimationParams(params);
        }

        private void applyParamsToNotification(ExpandAnimationParameters params) {
            mSourceNotification.applyExpandAnimationParams(params);
        }

        private void applyParamsToWindow(RemoteAnimationTarget app) {
            Matrix m = new Matrix();
            m.postTranslate(0, (float) (mParams.top - app.position.y));
            mWindowCrop.set(mParams.left, 0, mParams.right, mParams.getHeight());
            SurfaceParams params = new SurfaceParams(app.leash, 1f /* alpha */, m, mWindowCrop,
                    app.prefixOrderIndex, mCornerRadius, true /* visible */);
            mSyncRtTransactionApplier.scheduleApply(params);
        }

        @Override
        public void onAnimationCancelled() throws RemoteException {
            mSourceNotification.post(() -> {
                setAnimationPending(false);
                mCallback.onLaunchAnimationCancelled();
            });
        }
    };

    public static class ExpandAnimationParameters {
        float linearProgress;
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
