/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.back;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_BACK_PREVIEW;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.hardware.HardwareBuffer;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.window.BackNavigationInfo;
import android.window.IOnBackInvokedCallback;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ShellMainThread;

/**
 * Controls the window animation run when a user initiates a back gesture.
 */
public class BackAnimationController implements RemoteCallable<BackAnimationController> {

    private static final String BACK_PREDICTABILITY_PROP = "persist.debug.back_predictability";
    public static final boolean IS_ENABLED = SystemProperties
            .getInt(BACK_PREDICTABILITY_PROP, 0) > 0;
    private static final String TAG = "BackAnimationController";

    /**
     * Location of the initial touch event of the back gesture.
     */
    private final PointF mInitTouchLocation = new PointF();

    /**
     * Raw delta between {@link #mInitTouchLocation} and the last touch location.
     */
    private final Point mTouchEventDelta = new Point();
    private final ShellExecutor mShellExecutor;

    /** True when a back gesture is ongoing */
    private boolean mBackGestureStarted = false;

    /** @see #setTriggerBack(boolean) */
    private boolean mTriggerBack;

    @Nullable
    private BackNavigationInfo mBackNavigationInfo;
    private final SurfaceControl.Transaction mTransaction;
    private final IActivityTaskManager mActivityTaskManager;
    private final Context mContext;
    @Nullable
    private IOnBackInvokedCallback mBackToLauncherCallback;

    public BackAnimationController(
            @ShellMainThread ShellExecutor shellExecutor,
            Context context) {
        this(shellExecutor, new SurfaceControl.Transaction(), ActivityTaskManager.getService(),
                context);
    }

    @VisibleForTesting
    BackAnimationController(@NonNull ShellExecutor shellExecutor,
            @NonNull SurfaceControl.Transaction transaction,
            @NonNull IActivityTaskManager activityTaskManager,
            Context context) {
        mShellExecutor = shellExecutor;
        mTransaction = transaction;
        mActivityTaskManager = activityTaskManager;
        mContext = context;
    }

    public BackAnimation getBackAnimationImpl() {
        return mBackAnimation;
    }

    private final BackAnimation mBackAnimation = new BackAnimationImpl();

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mShellExecutor;
    }

    private class BackAnimationImpl implements BackAnimation {
        private IBackAnimationImpl mBackAnimation;

        @Override
        public IBackAnimation createExternalInterface() {
            if (mBackAnimation != null) {
                mBackAnimation.invalidate();
            }
            mBackAnimation = new IBackAnimationImpl(BackAnimationController.this);
            return mBackAnimation;
        }

        @Override
        public void onBackMotion(MotionEvent event) {
            mShellExecutor.execute(() -> onMotionEvent(event));
        }

        @Override
        public void setTriggerBack(boolean triggerBack) {
            mShellExecutor.execute(() -> BackAnimationController.this.setTriggerBack(triggerBack));
        }
    }

    private static class IBackAnimationImpl extends IBackAnimation.Stub {
        private BackAnimationController mController;

        IBackAnimationImpl(BackAnimationController controller) {
            mController = controller;
        }

        @Override
        public void setBackToLauncherCallback(IOnBackInvokedCallback callback) {
            executeRemoteCallWithTaskPermission(mController, "setBackToLauncherCallback",
                    (controller) -> mController.setBackToLauncherCallback(callback));
        }

        @Override
        public void clearBackToLauncherCallback() {
            executeRemoteCallWithTaskPermission(mController, "clearBackToLauncherCallback",
                    (controller) -> mController.clearBackToLauncherCallback());
        }

        @Override
        public void onBackToLauncherAnimationFinished() {
            executeRemoteCallWithTaskPermission(mController, "onBackToLauncherAnimationFinished",
                    (controller) -> mController.onBackToLauncherAnimationFinished());
        }

        void invalidate() {
            mController = null;
        }
    }

    private void setBackToLauncherCallback(IOnBackInvokedCallback callback) {
        mBackToLauncherCallback = callback;
    }

    private void clearBackToLauncherCallback() {
        mBackToLauncherCallback = null;
    }

    private void onBackToLauncherAnimationFinished() {
        finishAnimation();
    }

    /**
     * Called when a new motion event needs to be transferred to this
     * {@link BackAnimationController}
     */
    public void onMotionEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            initAnimation(event);
        } else if (action == MotionEvent.ACTION_MOVE) {
            onMove(event);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            onGestureFinished();
        }
    }

    private void initAnimation(MotionEvent event) {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "initAnimation mMotionStarted=%b", mBackGestureStarted);
        if (mBackGestureStarted) {
            Log.e(TAG, "Animation is being initialized but is already started.");
            return;
        }

        if (mBackNavigationInfo != null) {
            finishAnimation();
        }
        mInitTouchLocation.set(event.getX(), event.getY());
        mBackGestureStarted = true;

        try {
            mBackNavigationInfo = mActivityTaskManager.startBackNavigation();
            onBackNavigationInfoReceived(mBackNavigationInfo);
        } catch (RemoteException remoteException) {
            Log.e(TAG, "Failed to initAnimation", remoteException);
            finishAnimation();
        }
    }

    private void onBackNavigationInfoReceived(@Nullable BackNavigationInfo backNavigationInfo) {
        if (backNavigationInfo == null
                || backNavigationInfo.getDepartingWindowContainer() == null) {
            Log.e(TAG, "Received BackNavigationInfo is null.");
            finishAnimation();
            return;
        }

        HardwareBuffer hardwareBuffer = backNavigationInfo.getScreenshotHardwareBuffer();
        if (hardwareBuffer != null) {
            displayTargetScreenshot(hardwareBuffer,
                    backNavigationInfo.getTaskWindowConfiguration());
        }
        mTransaction.apply();
    }

    /**
     * Display the screenshot of the activity beneath.
     *
     * @param hardwareBuffer The buffer containing the screenshot.
     */
    private void displayTargetScreenshot(@NonNull HardwareBuffer hardwareBuffer,
            WindowConfiguration taskWindowConfiguration) {
        SurfaceControl screenshotSurface =
                mBackNavigationInfo == null ? null : mBackNavigationInfo.getScreenshotSurface();
        if (screenshotSurface == null) {
            Log.e(TAG, "BackNavigationInfo doesn't contain a surface for the screenshot. ");
            return;
        }

        // Scale the buffer to fill the whole Task
        float sx = 1;
        float sy = 1;
        float w = taskWindowConfiguration.getBounds().width();
        float h = taskWindowConfiguration.getBounds().height();

        if (w != hardwareBuffer.getWidth()) {
            sx = w / hardwareBuffer.getWidth();
        }

        if (h != hardwareBuffer.getHeight()) {
            sy = h / hardwareBuffer.getHeight();
        }
        mTransaction.setScale(screenshotSurface, sx, sy);
        mTransaction.setBuffer(screenshotSurface, hardwareBuffer);
        mTransaction.setVisibility(screenshotSurface, true);
    }

    private void onMove(MotionEvent event) {
        if (!mBackGestureStarted || mBackNavigationInfo == null) {
            return;
        }
        int deltaX = Math.round(event.getX() - mInitTouchLocation.x);
        int deltaY = Math.round(event.getY() - mInitTouchLocation.y);
        ProtoLog.v(WM_SHELL_BACK_PREVIEW, "Runner move: %d %d", deltaX, deltaY);
        SurfaceControl topWindowLeash = mBackNavigationInfo.getDepartingWindowContainer();
        mTransaction.setPosition(topWindowLeash, deltaX, deltaY);
        mTouchEventDelta.set(deltaX, deltaY);
        mTransaction.apply();
    }

    private void onGestureFinished() {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "onGestureFinished() mTriggerBack == %s", mTriggerBack);
        if (mBackGestureStarted) {
            if (mTriggerBack) {
                prepareTransition();
            } else {
                resetPositionAnimated();
            }
        }
        mBackGestureStarted = false;
        mTriggerBack = false;
    }

    /**
     * Animate the top window leash to its initial position.
     */
    private void resetPositionAnimated() {
        mBackGestureStarted = false;
        // TODO(208786853) Handle overlap with a new coming gesture.
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Runner: Back not triggered, cancelling animation "
                + "mLastPos=%s mInitTouch=%s", mTouchEventDelta, mInitTouchLocation);

        // TODO(208427216) : Replace placeholder animation with an actual one.
        ValueAnimator animation = ValueAnimator.ofFloat(0f, 1f).setDuration(200);
        animation.addUpdateListener(animation1 -> {
            if (mBackNavigationInfo == null) {
                return;
            }
            float fraction = animation1.getAnimatedFraction();
            int deltaX = Math.round(mTouchEventDelta.x - (mTouchEventDelta.x * fraction));
            int deltaY = Math.round(mTouchEventDelta.y - (mTouchEventDelta.y * fraction));
            mTransaction.setPosition(mBackNavigationInfo.getDepartingWindowContainer(),
                    deltaX, deltaY);
            mTransaction.apply();
        });

        animation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                ProtoLog.d(WM_SHELL_BACK_PREVIEW, "BackAnimationController: onAnimationEnd");
                finishAnimation();
            }
        });
        animation.start();
    }

    private void prepareTransition() {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "prepareTransition()");
        mTriggerBack = false;
        mBackGestureStarted = false;
    }

    /**
     * Sets to true when the back gesture has passed the triggering threshold, false otherwise.
     */
    public void setTriggerBack(boolean triggerBack) {
        mTriggerBack = triggerBack;
    }

    private void finishAnimation() {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "BackAnimationController: finishAnimation()");
        mBackGestureStarted = false;
        mTouchEventDelta.set(0, 0);
        mInitTouchLocation.set(0, 0);
        BackNavigationInfo backNavigationInfo = mBackNavigationInfo;
        mBackNavigationInfo = null;
        if (backNavigationInfo == null) {
            return;
        }
        SurfaceControl topWindowLeash = backNavigationInfo.getDepartingWindowContainer();
        if (topWindowLeash != null && topWindowLeash.isValid()) {
            mTransaction.remove(topWindowLeash);
        }
        SurfaceControl screenshotSurface = backNavigationInfo.getScreenshotSurface();
        if (screenshotSurface != null && screenshotSurface.isValid()) {
            mTransaction.remove(screenshotSurface);
        }
        mTransaction.apply();
        backNavigationInfo.onBackNavigationFinished();
    }
}
