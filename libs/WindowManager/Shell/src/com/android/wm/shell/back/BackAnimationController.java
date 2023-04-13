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
import static com.android.wm.shell.sysui.ShellSharedConstants.KEY_EXTRA_SHELL_BACK_ANIMATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.util.Log;
import android.util.SparseArray;
import android.view.IRemoteAnimationRunner;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.RemoteAnimationTarget;
import android.window.BackAnimationAdapter;
import android.window.BackEvent;
import android.window.BackMotionEvent;
import android.window.BackNavigationInfo;
import android.window.IBackAnimationFinishedCallback;
import android.window.IBackAnimationRunner;
import android.window.IOnBackInvokedCallback;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.view.AppearanceRegion;
import com.android.wm.shell.common.ExternalInterfaceBinder;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ShellBackgroundThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controls the window animation run when a user initiates a back gesture.
 */
public class BackAnimationController implements RemoteCallable<BackAnimationController> {
    private static final String TAG = "ShellBackPreview";
    private static final int SETTING_VALUE_OFF = 0;
    private static final int SETTING_VALUE_ON = 1;
    public static final boolean IS_ENABLED =
            SystemProperties.getInt("persist.wm.debug.predictive_back",
                    SETTING_VALUE_ON) == SETTING_VALUE_ON;
     /** Flag for U animation features */
    public static boolean IS_U_ANIMATION_ENABLED =
            SystemProperties.getInt("persist.wm.debug.predictive_back_anim",
                    SETTING_VALUE_ON) == SETTING_VALUE_ON;
    /** Predictive back animation developer option */
    private final AtomicBoolean mEnableAnimations = new AtomicBoolean(false);
    /**
     * Max duration to wait for an animation to finish before triggering the real back.
     */
    private static final long MAX_ANIMATION_DURATION = 2000;

    /** True when a back gesture is ongoing */
    private boolean mBackGestureStarted = false;

    /** Tracks if an uninterruptible animation is in progress */
    private boolean mPostCommitAnimationInProgress = false;
    /** Tracks if we should start the back gesture on the next motion move event */
    private boolean mShouldStartOnNextMoveEvent = false;
    /** @see #setTriggerBack(boolean) */
    private boolean mTriggerBack;

    @Nullable
    private BackNavigationInfo mBackNavigationInfo;
    private final IActivityTaskManager mActivityTaskManager;
    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final ShellController mShellController;
    private final ShellExecutor mShellExecutor;
    private final Handler mBgHandler;
    private final Runnable mAnimationTimeoutRunnable = () -> {
        ProtoLog.w(WM_SHELL_BACK_PREVIEW, "Animation didn't finish in %d ms. Resetting...",
                MAX_ANIMATION_DURATION);
        onBackAnimationFinished();
    };

    private IBackAnimationFinishedCallback mBackAnimationFinishedCallback;
    @VisibleForTesting
    BackAnimationAdapter mBackAnimationAdapter;

    private final TouchTracker mTouchTracker = new TouchTracker();

    private final SparseArray<BackAnimationRunner> mAnimationDefinition = new SparseArray<>();
    @Nullable
    private IOnBackInvokedCallback mActiveCallback;

    private CrossActivityAnimation mDefaultActivityAnimation;
    private CustomizeActivityAnimation mCustomizeActivityAnimation;

    @VisibleForTesting
    final RemoteCallback mNavigationObserver = new RemoteCallback(
            new RemoteCallback.OnResultListener() {
                @Override
                public void onResult(@Nullable Bundle result) {
                    mShellExecutor.execute(() -> {
                        if (!mBackGestureStarted || mPostCommitAnimationInProgress) {
                            // If an uninterruptible animation is already in progress, we should
                            // ignore this due to it may cause focus lost. (alpha = 0)
                            return;
                        }
                        ProtoLog.i(WM_SHELL_BACK_PREVIEW, "Navigation window gone.");
                        setTriggerBack(false);
                        onGestureFinished(false);
                    });
                }
            });

    private final BackAnimationBackground mAnimationBackground;
    private StatusBarCustomizer mCustomizer;

    public BackAnimationController(
            @NonNull ShellInit shellInit,
            @NonNull ShellController shellController,
            @NonNull @ShellMainThread ShellExecutor shellExecutor,
            @NonNull @ShellBackgroundThread Handler backgroundHandler,
            Context context,
            @NonNull BackAnimationBackground backAnimationBackground) {
        this(shellInit, shellController, shellExecutor, backgroundHandler,
                ActivityTaskManager.getService(), context, context.getContentResolver(),
                backAnimationBackground);
    }

    @VisibleForTesting
    BackAnimationController(
            @NonNull ShellInit shellInit,
            @NonNull ShellController shellController,
            @NonNull @ShellMainThread ShellExecutor shellExecutor,
            @NonNull @ShellBackgroundThread Handler bgHandler,
            @NonNull IActivityTaskManager activityTaskManager,
            Context context, ContentResolver contentResolver,
            @NonNull BackAnimationBackground backAnimationBackground) {
        mShellController = shellController;
        mShellExecutor = shellExecutor;
        mActivityTaskManager = activityTaskManager;
        mContext = context;
        mContentResolver = contentResolver;
        mBgHandler = bgHandler;
        shellInit.addInitCallback(this::onInit, this);
        mAnimationBackground = backAnimationBackground;
    }

    @VisibleForTesting
    void setEnableUAnimation(boolean enable) {
        IS_U_ANIMATION_ENABLED = enable;
    }

    private void onInit() {
        setupAnimationDeveloperSettingsObserver(mContentResolver, mBgHandler);
        createAdapter();
        mShellController.addExternalInterface(KEY_EXTRA_SHELL_BACK_ANIMATION,
                this::createExternalInterface, this);

        initBackAnimationRunners();
    }

    private void initBackAnimationRunners() {
        if (!IS_U_ANIMATION_ENABLED) {
            return;
        }

        final CrossTaskBackAnimation crossTaskAnimation =
                new CrossTaskBackAnimation(mContext, mAnimationBackground);
        mAnimationDefinition.set(BackNavigationInfo.TYPE_CROSS_TASK,
                crossTaskAnimation.mBackAnimationRunner);
        mDefaultActivityAnimation =
                new CrossActivityAnimation(mContext, mAnimationBackground);
        mAnimationDefinition.set(BackNavigationInfo.TYPE_CROSS_ACTIVITY,
                mDefaultActivityAnimation.mBackAnimationRunner);
        mCustomizeActivityAnimation =
                new CustomizeActivityAnimation(mContext, mAnimationBackground);
        // TODO (236760237): register dialog close animation when it's completed.
    }

    private void setupAnimationDeveloperSettingsObserver(
            @NonNull ContentResolver contentResolver,
            @NonNull @ShellBackgroundThread final Handler backgroundHandler) {
        ContentObserver settingsObserver = new ContentObserver(backgroundHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                updateEnableAnimationFromSetting();
            }
        };
        contentResolver.registerContentObserver(
                Global.getUriFor(Global.ENABLE_BACK_ANIMATION),
                false, settingsObserver, UserHandle.USER_SYSTEM
        );
        updateEnableAnimationFromSetting();
    }

    @ShellBackgroundThread
    private void updateEnableAnimationFromSetting() {
        int settingValue = Global.getInt(mContext.getContentResolver(),
                Global.ENABLE_BACK_ANIMATION, SETTING_VALUE_OFF);
        boolean isEnabled = settingValue == SETTING_VALUE_ON;
        mEnableAnimations.set(isEnabled);
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Back animation enabled=%s", isEnabled);
    }

    public BackAnimation getBackAnimationImpl() {
        return mBackAnimation;
    }

    private ExternalInterfaceBinder createExternalInterface() {
        return new IBackAnimationImpl(this);
    }

    private final BackAnimationImpl mBackAnimation = new BackAnimationImpl();

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mShellExecutor;
    }

    private class BackAnimationImpl implements BackAnimation {
        @Override
        public void onBackMotion(
                float touchX,
                float touchY,
                float velocityX,
                float velocityY,
                int keyAction,
                @BackEvent.SwipeEdge int swipeEdge
        ) {
            mShellExecutor.execute(() -> onMotionEvent(
                    /* touchX = */ touchX,
                    /* touchY = */ touchY,
                    /* velocityX = */ velocityX,
                    /* velocityY = */ velocityY,
                    /* keyAction = */ keyAction,
                    /* swipeEdge = */ swipeEdge));
        }

        @Override
        public void setTriggerBack(boolean triggerBack) {
            mShellExecutor.execute(() -> BackAnimationController.this.setTriggerBack(triggerBack));
        }

        @Override
        public void setSwipeThresholds(float triggerThreshold, float progressThreshold) {
            mShellExecutor.execute(() -> BackAnimationController.this.setSwipeThresholds(
                    triggerThreshold, progressThreshold));
        }

        @Override
        public void setStatusBarCustomizer(StatusBarCustomizer customizer) {
            mCustomizer = customizer;
            mAnimationBackground.setStatusBarCustomizer(customizer);
        }
    }

    private static class IBackAnimationImpl extends IBackAnimation.Stub
            implements ExternalInterfaceBinder {
        private BackAnimationController mController;

        IBackAnimationImpl(BackAnimationController controller) {
            mController = controller;
        }

        @Override
        public void setBackToLauncherCallback(IOnBackInvokedCallback callback,
                IRemoteAnimationRunner runner) {
            executeRemoteCallWithTaskPermission(mController, "setBackToLauncherCallback",
                    (controller) -> controller.registerAnimation(
                            BackNavigationInfo.TYPE_RETURN_TO_HOME,
                            new BackAnimationRunner(callback, runner)));
        }

        @Override
        public void clearBackToLauncherCallback() {
            executeRemoteCallWithTaskPermission(mController, "clearBackToLauncherCallback",
                    (controller) -> controller.unregisterAnimation(
                            BackNavigationInfo.TYPE_RETURN_TO_HOME));
        }

        public void customizeStatusBarAppearance(AppearanceRegion appearance) {
            executeRemoteCallWithTaskPermission(mController, "useLauncherSysBarFlags",
                    (controller) -> controller.customizeStatusBarAppearance(appearance));
        }

        @Override
        public void invalidate() {
            mController = null;
        }
    }

    private void customizeStatusBarAppearance(AppearanceRegion appearance) {
        if (mCustomizer != null) {
            mCustomizer.customizeStatusBarAppearance(appearance);
        }
    }

    void registerAnimation(@BackNavigationInfo.BackTargetType int type,
            @NonNull BackAnimationRunner runner) {
        mAnimationDefinition.set(type, runner);
    }

    void unregisterAnimation(@BackNavigationInfo.BackTargetType int type) {
        mAnimationDefinition.remove(type);
    }

    /**
     * Called when a new motion event needs to be transferred to this
     * {@link BackAnimationController}
     */
    public void onMotionEvent(
            float touchX,
            float touchY,
            float velocityX,
            float velocityY,
            int keyAction,
            @BackEvent.SwipeEdge int swipeEdge) {
        if (mPostCommitAnimationInProgress) {
            return;
        }

        mTouchTracker.update(touchX, touchY, velocityX, velocityY);
        if (keyAction == MotionEvent.ACTION_DOWN) {
            if (!mBackGestureStarted) {
                mShouldStartOnNextMoveEvent = true;
            }
        } else if (keyAction == MotionEvent.ACTION_MOVE) {
            if (!mBackGestureStarted && mShouldStartOnNextMoveEvent) {
                // Let the animation initialized here to make sure the onPointerDownOutsideFocus
                // could be happened when ACTION_DOWN, it may change the current focus that we
                // would access it when startBackNavigation.
                onGestureStarted(touchX, touchY, swipeEdge);
                mShouldStartOnNextMoveEvent = false;
            }
            onMove();
        } else if (keyAction == MotionEvent.ACTION_UP || keyAction == MotionEvent.ACTION_CANCEL) {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW,
                    "Finishing gesture with event action: %d", keyAction);
            if (keyAction == MotionEvent.ACTION_CANCEL) {
                mTriggerBack = false;
            }
            onGestureFinished(true);
        }
    }

    private void onGestureStarted(float touchX, float touchY, @BackEvent.SwipeEdge int swipeEdge) {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "initAnimation mMotionStarted=%b", mBackGestureStarted);
        if (mBackGestureStarted || mBackNavigationInfo != null) {
            Log.e(TAG, "Animation is being initialized but is already started.");
            finishBackNavigation();
        }

        mTouchTracker.setGestureStartLocation(touchX, touchY, swipeEdge);
        mBackGestureStarted = true;

        try {
            mBackNavigationInfo = mActivityTaskManager.startBackNavigation(
                    mNavigationObserver, mEnableAnimations.get() ? mBackAnimationAdapter : null);
            onBackNavigationInfoReceived(mBackNavigationInfo);
        } catch (RemoteException remoteException) {
            Log.e(TAG, "Failed to initAnimation", remoteException);
            finishBackNavigation();
        }
    }

    private void onBackNavigationInfoReceived(@Nullable BackNavigationInfo backNavigationInfo) {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Received backNavigationInfo:%s", backNavigationInfo);
        if (backNavigationInfo == null) {
            Log.e(TAG, "Received BackNavigationInfo is null.");
            return;
        }
        final int backType = backNavigationInfo.getType();
        final boolean shouldDispatchToAnimator = shouldDispatchToAnimator();
        if (shouldDispatchToAnimator) {
            if (mAnimationDefinition.contains(backType)) {
                mAnimationDefinition.get(backType).startGesture();
            } else {
                mActiveCallback = null;
            }
        } else {
            mActiveCallback = mBackNavigationInfo.getOnBackInvokedCallback();
            dispatchOnBackStarted(mActiveCallback, mTouchTracker.createStartEvent(null));
        }
    }

    private void onMove() {
        if (!mBackGestureStarted || mBackNavigationInfo == null || mActiveCallback == null) {
            return;
        }

        final BackMotionEvent backEvent = mTouchTracker.createProgressEvent();
        dispatchOnBackProgressed(mActiveCallback, backEvent);
    }

    private void injectBackKey() {
        sendBackEvent(KeyEvent.ACTION_DOWN);
        sendBackEvent(KeyEvent.ACTION_UP);
    }

    private void sendBackEvent(int action) {
        final long when = SystemClock.uptimeMillis();
        final KeyEvent ev = new KeyEvent(when, when, action, KeyEvent.KEYCODE_BACK, 0 /* repeat */,
                0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);

        ev.setDisplayId(mContext.getDisplay().getDisplayId());
        if (!mContext.getSystemService(InputManager.class)
                .injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)) {
            Log.e(TAG, "Inject input event fail");
        }
    }

    private boolean shouldDispatchToAnimator() {
        return mEnableAnimations.get()
                && mBackNavigationInfo != null
                && mBackNavigationInfo.isPrepareRemoteAnimation();
    }

    private void dispatchOnBackStarted(IOnBackInvokedCallback callback,
            BackMotionEvent backEvent) {
        if (callback == null) {
            return;
        }
        try {
            callback.onBackStarted(backEvent);
        } catch (RemoteException e) {
            Log.e(TAG, "dispatchOnBackStarted error: ", e);
        }
    }

    private void dispatchOnBackInvoked(IOnBackInvokedCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.onBackInvoked();
        } catch (RemoteException e) {
            Log.e(TAG, "dispatchOnBackInvoked error: ", e);
        }
    }

    private void dispatchOnBackCancelled(IOnBackInvokedCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.onBackCancelled();
        } catch (RemoteException e) {
            Log.e(TAG, "dispatchOnBackCancelled error: ", e);
        }
    }

    private void dispatchOnBackProgressed(IOnBackInvokedCallback callback,
            BackMotionEvent backEvent) {
        if (callback == null) {
            return;
        }
        try {
            callback.onBackProgressed(backEvent);
        } catch (RemoteException e) {
            Log.e(TAG, "dispatchOnBackProgressed error: ", e);
        }
    }

    /**
     * Sets to true when the back gesture has passed the triggering threshold, false otherwise.
     */
    public void setTriggerBack(boolean triggerBack) {
        if (mPostCommitAnimationInProgress) {
            return;
        }
        mTriggerBack = triggerBack;
        mTouchTracker.setTriggerBack(triggerBack);
    }

    private void setSwipeThresholds(float triggerThreshold, float progressThreshold) {
        mTouchTracker.setProgressThreshold(progressThreshold);
    }

    private void invokeOrCancelBack() {
        // Make a synchronized call to core before dispatch back event to client side.
        // If the close transition happens before the core receives onAnimationFinished, there will
        // play a second close animation for that transition.
        if (mBackAnimationFinishedCallback != null) {
            try {
                mBackAnimationFinishedCallback.onAnimationFinished(mTriggerBack);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed call IBackAnimationFinishedCallback", e);
            }
            mBackAnimationFinishedCallback = null;
        }

        if (mBackNavigationInfo != null) {
            final IOnBackInvokedCallback callback = mBackNavigationInfo.getOnBackInvokedCallback();
            if (mTriggerBack) {
                dispatchOnBackInvoked(callback);
            } else {
                dispatchOnBackCancelled(callback);
            }
        }
        finishBackNavigation();
    }

    /**
     * Called when the gesture is released, then it could start the post commit animation.
     */
    private void onGestureFinished(boolean fromTouch) {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "onGestureFinished() mTriggerBack == %s", mTriggerBack);
        if (!mBackGestureStarted) {
            finishBackNavigation();
            return;
        }

        if (fromTouch) {
            // Let touch reset the flag otherwise it will start a new back navigation and refresh
            // the info when received a new move event.
            mBackGestureStarted = false;
        }

        if (mPostCommitAnimationInProgress) {
            ProtoLog.w(WM_SHELL_BACK_PREVIEW, "Animation is still running");
            return;
        }

        if (mBackNavigationInfo == null) {
            // No focus window found or core are running recents animation, inject back key as
            // legacy behavior.
            if (mTriggerBack) {
                injectBackKey();
            }
            finishBackNavigation();
            return;
        }

        final int backType = mBackNavigationInfo.getType();
        final BackAnimationRunner runner = mAnimationDefinition.get(backType);
        // Simply trigger and finish back navigation when no animator defined.
        if (!shouldDispatchToAnimator() || runner == null) {
            invokeOrCancelBack();
            return;
        }
        if (runner.isWaitingAnimation()) {
            ProtoLog.w(WM_SHELL_BACK_PREVIEW, "Gesture released, but animation didn't ready.");
            // Supposed it is in post commit animation state, and start the timeout to watch
            // if the animation is ready.
            mShellExecutor.executeDelayed(mAnimationTimeoutRunnable, MAX_ANIMATION_DURATION);
            return;
        } else if (runner.isAnimationCancelled()) {
            invokeOrCancelBack();
            return;
        }
        startPostCommitAnimation();
    }

    /**
     * Start the phase 2 animation when gesture is released.
     * Callback to {@link #onBackAnimationFinished} when it is finished or timeout.
     */
    private void startPostCommitAnimation() {
        if (mPostCommitAnimationInProgress) {
            return;
        }

        mShellExecutor.removeCallbacks(mAnimationTimeoutRunnable);
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "BackAnimationController: startPostCommitAnimation()");
        mPostCommitAnimationInProgress = true;
        mShellExecutor.executeDelayed(mAnimationTimeoutRunnable, MAX_ANIMATION_DURATION);

        // The next callback should be {@link #onBackAnimationFinished}.
        if (mTriggerBack) {
            dispatchOnBackInvoked(mActiveCallback);
        } else {
            dispatchOnBackCancelled(mActiveCallback);
        }
    }

    /**
     * Called when the post commit animation is completed or timeout.
     * This will trigger the real {@link IOnBackInvokedCallback} behavior.
     */
    @VisibleForTesting
    void onBackAnimationFinished() {
        // Stop timeout runner.
        mShellExecutor.removeCallbacks(mAnimationTimeoutRunnable);
        mPostCommitAnimationInProgress = false;

        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "BackAnimationController: onBackAnimationFinished()");

        // Trigger the real back.
        invokeOrCancelBack();
    }

    /**
     * This should be called after the whole back navigation is completed.
     */
    @VisibleForTesting
    void finishBackNavigation() {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "BackAnimationController: finishBackNavigation()");
        mShouldStartOnNextMoveEvent = false;
        mTouchTracker.reset();
        mActiveCallback = null;
        // reset to default
        if (mDefaultActivityAnimation != null
                && mAnimationDefinition.contains(BackNavigationInfo.TYPE_CROSS_ACTIVITY)) {
            mAnimationDefinition.set(BackNavigationInfo.TYPE_CROSS_ACTIVITY,
                    mDefaultActivityAnimation.mBackAnimationRunner);
        }
        if (mBackNavigationInfo != null) {
            mBackNavigationInfo.onBackNavigationFinished(mTriggerBack);
            mBackNavigationInfo = null;
        }
        mTriggerBack = false;
    }

    private BackAnimationRunner getAnimationRunnerAndInit() {
        int type = mBackNavigationInfo.getType();
        // Initiate customized cross-activity animation, or fall back to cross activity animation
        if (type == BackNavigationInfo.TYPE_CROSS_ACTIVITY && mAnimationDefinition.contains(type)) {
            final BackNavigationInfo.CustomAnimationInfo animationInfo =
                    mBackNavigationInfo.getCustomAnimationInfo();
            if (animationInfo != null && mCustomizeActivityAnimation != null
                    && mCustomizeActivityAnimation.prepareNextAnimation(animationInfo)) {
                mAnimationDefinition.get(type).resetWaitingAnimation();
                mAnimationDefinition.set(BackNavigationInfo.TYPE_CROSS_ACTIVITY,
                        mCustomizeActivityAnimation.mBackAnimationRunner);
            }
        }
        return mAnimationDefinition.get(type);
    }

    private void createAdapter() {
        IBackAnimationRunner runner = new IBackAnimationRunner.Stub() {
            @Override
            public void onAnimationStart(RemoteAnimationTarget[] apps,
                    RemoteAnimationTarget[] wallpapers, RemoteAnimationTarget[] nonApps,
                    IBackAnimationFinishedCallback finishedCallback) {
                mShellExecutor.execute(() -> {
                    if (mBackNavigationInfo == null) {
                        Log.e(TAG, "Lack of navigation info to start animation.");
                        return;
                    }
                    final int type = mBackNavigationInfo.getType();
                    final BackAnimationRunner runner = getAnimationRunnerAndInit();
                    if (runner == null) {
                        Log.e(TAG, "Animation didn't be defined for type "
                                + BackNavigationInfo.typeToString(type));
                        if (finishedCallback != null) {
                            try {
                                finishedCallback.onAnimationFinished(false);
                            } catch (RemoteException e) {
                                Log.w(TAG, "Failed call IBackNaviAnimationController", e);
                            }
                        }
                        return;
                    }
                    mActiveCallback = runner.getCallback();
                    mBackAnimationFinishedCallback = finishedCallback;

                    ProtoLog.d(WM_SHELL_BACK_PREVIEW, "BackAnimationController: startAnimation()");
                    runner.startAnimation(apps, wallpapers, nonApps, () -> mShellExecutor.execute(
                            BackAnimationController.this::onBackAnimationFinished));

                    if (apps.length >= 1) {
                        dispatchOnBackStarted(
                                mActiveCallback, mTouchTracker.createStartEvent(apps[0]));
                    }

                    // Dispatch the first progress after animation start for smoothing the initial
                    // animation, instead of waiting for next onMove.
                    final BackMotionEvent backFinish = mTouchTracker.createProgressEvent();
                    dispatchOnBackProgressed(mActiveCallback, backFinish);
                    if (!mBackGestureStarted) {
                        // if the down -> up gesture happened before animation start, we have to
                        // trigger the uninterruptible transition to finish the back animation.
                        startPostCommitAnimation();
                    }
                });
            }

            @Override
            public void onAnimationCancelled() {
                mShellExecutor.execute(() -> {
                    final BackAnimationRunner runner = mAnimationDefinition.get(
                            mBackNavigationInfo.getType());
                    if (runner == null) {
                        return;
                    }
                    runner.cancelAnimation();
                    if (!mBackGestureStarted) {
                        invokeOrCancelBack();
                    }
                });
            }
        };
        mBackAnimationAdapter = new BackAnimationAdapter(runner);
    }
}
