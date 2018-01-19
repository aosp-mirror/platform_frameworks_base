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
import com.android.systemui.statusbar.phone.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.phone.NotificationPanelView;
import com.android.systemui.statusbar.phone.StatusBarWindowView;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

import java.util.function.Consumer;

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
    private final NotificationPanelView mNotificationPanel;
    private final NotificationListContainer mNotificationContainer;
    private final StatusBarWindowView mStatusBarWindow;
    private final Consumer<Boolean> mPanelCollapser;

    public ActivityLaunchAnimator(StatusBarWindowView statusBarWindow,
            Consumer<Boolean> panelCollapser,
            NotificationPanelView notificationPanel,
            NotificationListContainer container) {
        mNotificationPanel = notificationPanel;
        mNotificationContainer = container;
        mStatusBarWindow = statusBarWindow;
        mPanelCollapser = panelCollapser;
    }

    public ActivityOptions getLaunchAnimation(
            ExpandableNotificationRow sourceNofitication) {
        AnimationRunner animationRunner = new AnimationRunner(sourceNofitication);
        return ActivityOptions.makeRemoteAnimation(
                new RemoteAnimationAdapter(animationRunner, 1000 /* Duration */, 0 /* delay */));
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
                boolean first = true;
                for (RemoteAnimationTarget app : remoteAnimationTargets) {
                    if (app.mode == RemoteAnimationTarget.MODE_OPENING) {
                        setExpandAnimationRunning(true);
                        mInstantCollapsePanel = app.position.y == 0
                                && app.sourceContainerBounds.height()
                                        >= mNotificationPanel.getHeight();
                        if (!mInstantCollapsePanel) {
                            mNotificationPanel.collapseWithDuration(ANIMATION_DURATION);
                        }
                        ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
                        mParams.startPosition = mSourceNotification.getLocationOnScreen();
                        mParams.startTranslationZ = mSourceNotification.getTranslationZ();
                        int targetWidth = app.sourceContainerBounds.width();
                        int notificationHeight = mSourceNotification.getActualHeight();
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
                                        app.position.y, progress);
                                mParams.bottom = (int) MathUtils.lerp(mParams.startPosition[1]
                                                + notificationHeight,
                                        app.position.y + app.sourceContainerBounds.bottom,
                                        progress);
                                applyParamsToWindow(app);
                                applyParamsToNotification(mParams);
                                applyParamsToNotificationList(mParams);
                            }
                        });
                        anim.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                setExpandAnimationRunning(false);
                                if (mInstantCollapsePanel) {
                                    mPanelCollapser.accept(false /* animate */);
                                }
                                try {
                                    iRemoteAnimationFinishedCallback.onAnimationFinished();
                                } catch (RemoteException e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                        anim.start();
                        break;
                    }
                }
            });
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
            t.apply();
        }

        @Override
        public void onAnimationCancelled() throws RemoteException {
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
            return Math.min(top - startPosition[1], 0);
        }


        public float getProgress(long delay, long duration) {
            return MathUtils.constrain((linearProgress * ANIMATION_DURATION - delay)
                    / duration, 0.0f, 1.0f);
        }

        public float getStartTranslationZ() {
            return startTranslationZ;
        }
    }
}
