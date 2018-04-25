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
import android.app.ActivityOptions;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.MathUtils;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.ViewRootImpl;

import com.android.systemui.Interpolators;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationListContainer;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.NotificationPanelView;
import com.android.systemui.statusbar.phone.StatusBar;
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
    private StatusBar mStatusBar;
    private final Runnable mTimeoutRunnable = () -> {
        setAnimationPending(false);
        mStatusBar.collapsePanel(true /* animate */);
    };
    private boolean mAnimationPending;

    public ActivityLaunchAnimator(StatusBarWindowView statusBarWindow,
            StatusBar statusBar,
            NotificationPanelView notificationPanel,
            NotificationListContainer container) {
        mNotificationPanel = notificationPanel;
        mNotificationContainer = container;
        mStatusBarWindow = statusBarWindow;
        mStatusBar = statusBar;
    }

    public RemoteAnimationAdapter getLaunchAnimation(
            ExpandableNotificationRow sourceNotification) {
        if (mStatusBar.getBarState() != StatusBarState.SHADE) {
            return null;
        }
        AnimationRunner animationRunner = new AnimationRunner(sourceNotification);
        return new RemoteAnimationAdapter(animationRunner, ANIMATION_DURATION,
                0 /* statusBarTransitionDelay */);
    }

    public boolean isAnimationPending() {
        return mAnimationPending;
    }

    public void setLaunchResult(int launchResult) {
        setAnimationPending((launchResult == ActivityManager.START_TASK_TO_FRONT
                || launchResult == ActivityManager.START_SUCCESS)
                        && mStatusBar.getBarState() == StatusBarState.SHADE);
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

    class AnimationRunner extends IRemoteAnimationRunner.Stub {

        private final ExpandableNotificationRow mSourceNotification;
        private final ExpandAnimationParameters mParams;
        private final Rect mWindowCrop = new Rect();
        private boolean mLeashShown;
        private boolean mInstantCollapsePanel = true;

        public AnimationRunner(ExpandableNotificationRow sourceNofitication) {
            mSourceNotification = sourceNofitication;
            mParams = new ExpandAnimationParameters();
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
                mInstantCollapsePanel = primary.position.y == 0
                        && primary.sourceContainerBounds.height()
                                >= mNotificationPanel.getHeight();
                if (!mInstantCollapsePanel) {
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
                        float progress
                                = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
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
                        applyParamsToWindow(primary);
                        applyParamsToNotification(mParams);
                        applyParamsToNotificationList(mParams);
                    }
                });
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        setExpandAnimationRunning(false);
                        if (mInstantCollapsePanel) {
                            mStatusBar.collapsePanel(false /* animate */);
                        }
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
            if (!running) {
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
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            if (!mLeashShown) {
                t.show(app.leash);
                mLeashShown = true;
            }
            Matrix m = new Matrix();
            m.postTranslate(0, (float) (mParams.top - app.position.y));
            t.setMatrix(app.leash, m, new float[9]);
            mWindowCrop.set(mParams.left, 0, mParams.right, mParams.getHeight());
            t.setWindowCrop(app.leash, mWindowCrop);
            ViewRootImpl viewRootImpl = mSourceNotification.getViewRootImpl();
            if (viewRootImpl != null) {
                Surface systemUiSurface = viewRootImpl.mSurface;
                t.deferTransactionUntilSurface(app.leash, systemUiSurface,
                        systemUiSurface.getNextFrameNumber());
            }
            t.setEarlyWakeup();
            t.apply();
        }

        @Override
        public void onAnimationCancelled() throws RemoteException {
            mSourceNotification.post(() -> {
                setAnimationPending(false);
                mStatusBar.onLaunchAnimationCancelled();
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
}
