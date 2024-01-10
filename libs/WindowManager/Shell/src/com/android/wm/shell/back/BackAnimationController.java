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

import static com.android.internal.jank.InteractionJankMonitor.CUJ_PREDICTIVE_BACK_HOME;
import static com.android.window.flags.Flags.predictiveBackSystemAnimations;
import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_BACK_PREVIEW;
import static com.android.wm.shell.sysui.ShellSharedConstants.KEY_EXTRA_SHELL_BACK_ANIMATION;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
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
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.MathUtils;
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
import com.android.internal.util.LatencyTracker;
import com.android.internal.view.AppearanceRegion;
import com.android.wm.shell.animation.FlingAnimationUtils;
import com.android.wm.shell.common.ExternalInterfaceBinder;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ShellBackgroundThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;

import java.io.PrintWriter;
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
    public static final float FLING_MAX_LENGTH_SECONDS = 0.1f;     // 100ms
    public static final float FLING_SPEED_UP_FACTOR = 0.6f;

    /**
     * The maximum additional progress in case of fling gesture.
     * The end animation starts after the user lifts the finger from the screen, we continue to
     * fire {@link BackEvent}s until the velocity reaches 0.
     */
    private static final float MAX_FLING_PROGRESS = 0.3f; /* 30% of the screen */

    /** Predictive back animation developer option */
    private final AtomicBoolean mEnableAnimations = new AtomicBoolean(false);
    /**
     * Max duration to wait for an animation to finish before triggering the real back.
     */
    private static final long MAX_ANIMATION_DURATION = 2000;
    private final LatencyTracker mLatencyTracker;

    /** True when a back gesture is ongoing */
    private boolean mBackGestureStarted = false;

    /** Tracks if an uninterruptible animation is in progress */
    private boolean mPostCommitAnimationInProgress = false;

    /** Tracks if we should start the back gesture on the next motion move event */
    private boolean mShouldStartOnNextMoveEvent = false;
    private boolean mOnBackStartDispatched = false;

    private final FlingAnimationUtils mFlingAnimationUtils;

    /** Registry for the back animations */
    private final ShellBackAnimationRegistry mShellBackAnimationRegistry;

    @Nullable
    private BackNavigationInfo mBackNavigationInfo;
    private final IActivityTaskManager mActivityTaskManager;
    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final ShellController mShellController;
    private final ShellCommandHandler mShellCommandHandler;
    private final ShellExecutor mShellExecutor;
    private final Handler mBgHandler;

    /**
     * Tracks the current user back gesture.
     */
    private TouchTracker mCurrentTracker = new TouchTracker();

    /**
     * Tracks the next back gesture in case a new user gesture has started while the back animation
     * (and navigation) associated with {@link #mCurrentTracker} have not yet finished.
     */
    private TouchTracker mQueuedTracker = new TouchTracker();

    private final Runnable mAnimationTimeoutRunnable = () -> {
        ProtoLog.w(WM_SHELL_BACK_PREVIEW, "Animation didn't finish in %d ms. Resetting...",
                MAX_ANIMATION_DURATION);
        onBackAnimationFinished();
    };

    private IBackAnimationFinishedCallback mBackAnimationFinishedCallback;
    @VisibleForTesting
    BackAnimationAdapter mBackAnimationAdapter;

    @Nullable
    private IOnBackInvokedCallback mActiveCallback;

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
                        resetTouchTracker();
                    });
                }
            });

    private final BackAnimationBackground mAnimationBackground;
    private StatusBarCustomizer mCustomizer;
    private boolean mTrackingLatency;

    public BackAnimationController(
            @NonNull ShellInit shellInit,
            @NonNull ShellController shellController,
            @NonNull @ShellMainThread ShellExecutor shellExecutor,
            @NonNull @ShellBackgroundThread Handler backgroundHandler,
            Context context,
            @NonNull BackAnimationBackground backAnimationBackground,
            ShellBackAnimationRegistry shellBackAnimationRegistry,
            ShellCommandHandler shellCommandHandler) {
        this(
                shellInit,
                shellController,
                shellExecutor,
                backgroundHandler,
                ActivityTaskManager.getService(),
                context,
                context.getContentResolver(),
                backAnimationBackground,
                shellBackAnimationRegistry,
                shellCommandHandler);
    }

    @VisibleForTesting
    BackAnimationController(
            @NonNull ShellInit shellInit,
            @NonNull ShellController shellController,
            @NonNull @ShellMainThread ShellExecutor shellExecutor,
            @NonNull @ShellBackgroundThread Handler bgHandler,
            @NonNull IActivityTaskManager activityTaskManager,
            Context context,
            ContentResolver contentResolver,
            @NonNull BackAnimationBackground backAnimationBackground,
            ShellBackAnimationRegistry shellBackAnimationRegistry,
            ShellCommandHandler shellCommandHandler) {
        mShellController = shellController;
        mShellExecutor = shellExecutor;
        mActivityTaskManager = activityTaskManager;
        mContext = context;
        mContentResolver = contentResolver;
        mBgHandler = bgHandler;
        shellInit.addInitCallback(this::onInit, this);
        mAnimationBackground = backAnimationBackground;
        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        mFlingAnimationUtils = new FlingAnimationUtils.Builder(displayMetrics)
                .setMaxLengthSeconds(FLING_MAX_LENGTH_SECONDS)
                .setSpeedUpFactor(FLING_SPEED_UP_FACTOR)
                .build();
        mShellBackAnimationRegistry = shellBackAnimationRegistry;
        mLatencyTracker = LatencyTracker.getInstance(mContext);
        mShellCommandHandler = shellCommandHandler;
    }

    private void onInit() {
        setupAnimationDeveloperSettingsObserver(mContentResolver, mBgHandler);
        updateEnableAnimationFromFlags();
        createAdapter();
        mShellController.addExternalInterface(KEY_EXTRA_SHELL_BACK_ANIMATION,
                this::createExternalInterface, this);
        mShellCommandHandler.addDumpCallback(this::dump, this);
    }

    private void setupAnimationDeveloperSettingsObserver(
            @NonNull ContentResolver contentResolver,
            @NonNull @ShellBackgroundThread final Handler backgroundHandler) {
        if (predictiveBackSystemAnimations()) {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Back animation aconfig flag is enabled, therefore "
                    + "developer settings flag is ignored and no content observer registered");
            return;
        }
        ContentObserver settingsObserver = new ContentObserver(backgroundHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                updateEnableAnimationFromFlags();
            }
        };
        contentResolver.registerContentObserver(
                Global.getUriFor(Global.ENABLE_BACK_ANIMATION),
                false, settingsObserver, UserHandle.USER_SYSTEM
        );
    }

    /**
     * Updates {@link BackAnimationController#mEnableAnimations} based on the current values of the
     * aconfig flag and the developer settings flag
     */
    @ShellBackgroundThread
    private void updateEnableAnimationFromFlags() {
        boolean isEnabled = predictiveBackSystemAnimations() || isDeveloperSettingEnabled();
        mEnableAnimations.set(isEnabled);
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Back animation enabled=%s", isEnabled);
    }

    private boolean isDeveloperSettingEnabled() {
        return Global.getInt(mContext.getContentResolver(),
                Global.ENABLE_BACK_ANIMATION, SETTING_VALUE_OFF) == SETTING_VALUE_ON;
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
        public void onPilferPointers() {
            BackAnimationController.this.onPilferPointers();
        }

        @Override
        public void setTriggerBack(boolean triggerBack) {
            mShellExecutor.execute(() -> BackAnimationController.this.setTriggerBack(triggerBack));
        }

        @Override
        public void setSwipeThresholds(
                float linearDistance,
                float maxDistance,
                float nonLinearFactor) {
            mShellExecutor.execute(() -> BackAnimationController.this.setSwipeThresholds(
                    linearDistance, maxDistance, nonLinearFactor));
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
                            new BackAnimationRunner(
                                    callback,
                                    runner,
                                    controller.mContext,
                                    CUJ_PREDICTIVE_BACK_HOME)));
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
        mShellBackAnimationRegistry.registerAnimation(type, runner);
    }

    void unregisterAnimation(@BackNavigationInfo.BackTargetType int type) {
        mShellBackAnimationRegistry.unregisterAnimation(type);
    }

    private TouchTracker getActiveTracker() {
        if (mCurrentTracker.isActive()) return mCurrentTracker;
        if (mQueuedTracker.isActive()) return mQueuedTracker;
        return null;
    }

    @VisibleForTesting
    void onPilferPointers() {
        mCurrentTracker.updateStartLocation();
        // Dispatch onBackStarted, only to app callbacks.
        // System callbacks will receive onBackStarted when the remote animation starts.
        if (!shouldDispatchToAnimator() && mActiveCallback != null) {
            tryDispatchAppOnBackStarted(mActiveCallback, mCurrentTracker.createStartEvent(null));
        }
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

        TouchTracker activeTouchTracker = getActiveTracker();
        if (activeTouchTracker != null) {
            activeTouchTracker.update(touchX, touchY, velocityX, velocityY);
        }

        // two gestures are waiting to be processed at the moment, skip any further user touches
        if (mCurrentTracker.isFinished() && mQueuedTracker.isFinished()) {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW,
                    "Ignoring MotionEvent because two gestures are already being queued.");
            return;
        }

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
                setTriggerBack(false);
            }
            onGestureFinished();
        }
    }

    private void onGestureStarted(float touchX, float touchY, @BackEvent.SwipeEdge int swipeEdge) {
        TouchTracker touchTracker;
        if (mCurrentTracker.isInInitialState()) {
            touchTracker = mCurrentTracker;
        } else if (mQueuedTracker.isInInitialState()) {
            touchTracker = mQueuedTracker;
        } else {
            ProtoLog.w(WM_SHELL_BACK_PREVIEW,
                    "Cannot start tracking new gesture with neither tracker in initial state.");
            return;
        }
        touchTracker.setGestureStartLocation(touchX, touchY, swipeEdge);
        touchTracker.setState(TouchTracker.TouchTrackerState.ACTIVE);
        mBackGestureStarted = true;

        if (touchTracker == mCurrentTracker) {
            // Only start the back navigation if no other gesture is being processed. Otherwise,
            // the back navigation will be started once the current gesture has finished.
            startBackNavigation(mCurrentTracker);
        }
    }

    private void startBackNavigation(@NonNull TouchTracker touchTracker) {
        try {
            startLatencyTracking();
            mBackNavigationInfo = mActivityTaskManager.startBackNavigation(
                    mNavigationObserver, mEnableAnimations.get() ? mBackAnimationAdapter : null);
            onBackNavigationInfoReceived(mBackNavigationInfo, touchTracker);
        } catch (RemoteException remoteException) {
            Log.e(TAG, "Failed to initAnimation", remoteException);
            finishBackNavigation(touchTracker.getTriggerBack());
        }
    }

    private void onBackNavigationInfoReceived(@Nullable BackNavigationInfo backNavigationInfo,
            @NonNull TouchTracker touchTracker) {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Received backNavigationInfo:%s", backNavigationInfo);
        if (backNavigationInfo == null) {
            ProtoLog.e(WM_SHELL_BACK_PREVIEW, "Received BackNavigationInfo is null.");
            cancelLatencyTracking();
            return;
        }
        final int backType = backNavigationInfo.getType();
        final boolean shouldDispatchToAnimator = shouldDispatchToAnimator();
        if (shouldDispatchToAnimator) {
            if (!mShellBackAnimationRegistry.startGesture(backType)) {
                mActiveCallback = null;
            }
        } else {
            mActiveCallback = mBackNavigationInfo.getOnBackInvokedCallback();
            // App is handling back animation. Cancel system animation latency tracking.
            cancelLatencyTracking();
            tryDispatchAppOnBackStarted(mActiveCallback, touchTracker.createStartEvent(null));
        }
    }

    private void onMove() {
        if (!mBackGestureStarted
                || mBackNavigationInfo == null
                || mActiveCallback == null
                || !mOnBackStartDispatched) {
            return;
        }
        // Skip dispatching if the move corresponds to the queued instead of the current gesture
        if (mQueuedTracker.isActive()) return;
        final BackMotionEvent backEvent = mCurrentTracker.createProgressEvent();
        dispatchOnBackProgressed(mActiveCallback, backEvent);
    }

    private void injectBackKey() {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "injectBackKey");
        sendBackEvent(KeyEvent.ACTION_DOWN);
        sendBackEvent(KeyEvent.ACTION_UP);
    }

    @SuppressLint("MissingPermission")
    private void sendBackEvent(int action) {
        final long when = SystemClock.uptimeMillis();
        final KeyEvent ev = new KeyEvent(when, when, action, KeyEvent.KEYCODE_BACK, 0 /* repeat */,
                0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);

        ev.setDisplayId(mContext.getDisplay().getDisplayId());
        if (!mContext.getSystemService(InputManager.class)
                .injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)) {
            ProtoLog.e(WM_SHELL_BACK_PREVIEW, "Inject input event fail");
        }
    }

    private boolean shouldDispatchToAnimator() {
        return mEnableAnimations.get()
                && mBackNavigationInfo != null
                && mBackNavigationInfo.isPrepareRemoteAnimation();
    }

    private void tryDispatchAppOnBackStarted(
            IOnBackInvokedCallback callback,
            BackMotionEvent backEvent) {
        if (mOnBackStartDispatched && callback != null) {
            return;
        }
        dispatchOnBackStarted(callback, backEvent);
        mOnBackStartDispatched = true;
    }

    private void dispatchOnBackStarted(
            IOnBackInvokedCallback callback,
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


    /**
     * Allows us to manage the fling gesture, it smoothly animates the current progress value to
     * the final position, calculated based on the current velocity.
     *
     * @param callback the callback to be invoked when the animation ends.
     */
    private void dispatchOrAnimateOnBackInvoked(IOnBackInvokedCallback callback,
            @NonNull TouchTracker touchTracker) {
        if (callback == null) {
            return;
        }

        boolean animationStarted = false;

        if (mBackNavigationInfo != null && mBackNavigationInfo.isAnimationCallback()) {

            final BackMotionEvent backMotionEvent = touchTracker.createProgressEvent();
            if (backMotionEvent != null) {
                // Constraints - absolute values
                float minVelocity = mFlingAnimationUtils.getMinVelocityPxPerSecond();
                float maxVelocity = mFlingAnimationUtils.getHighVelocityPxPerSecond();
                float maxX = touchTracker.getMaxDistance(); // px
                float maxFlingDistance = maxX * MAX_FLING_PROGRESS; // px

                // Current state
                float currentX = backMotionEvent.getTouchX();
                float velocity = MathUtils.constrain(backMotionEvent.getVelocityX(),
                        -maxVelocity, maxVelocity);

                // Target state
                float animationFaction = velocity / maxVelocity; // value between -1 and 1
                float flingDistance = animationFaction * maxFlingDistance; // px
                float endX = MathUtils.constrain(currentX + flingDistance, 0f, maxX);

                if (!Float.isNaN(endX)
                        && currentX != endX
                        && Math.abs(velocity) >= minVelocity) {
                    ValueAnimator animator = ValueAnimator.ofFloat(currentX, endX);

                    mFlingAnimationUtils.apply(
                            /* animator = */ animator,
                            /* currValue = */ currentX,
                            /* endValue = */ endX,
                            /* velocity = */ velocity,
                            /* maxDistance = */ maxFlingDistance
                    );

                    animator.addUpdateListener(animation -> {
                        Float animatedValue = (Float) animation.getAnimatedValue();
                        float progress = touchTracker.getProgress(animatedValue);
                        final BackMotionEvent backEvent = touchTracker.createProgressEvent(
                                progress);
                        dispatchOnBackProgressed(mActiveCallback, backEvent);
                    });

                    animator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            dispatchOnBackInvoked(callback);
                        }
                    });
                    animator.start();
                    animationStarted = true;
                }
            }
        }

        if (!animationStarted) {
            dispatchOnBackInvoked(callback);
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
        TouchTracker activeBackGestureInfo = getActiveTracker();
        if (activeBackGestureInfo != null) {
            activeBackGestureInfo.setTriggerBack(triggerBack);
        }
    }

    private void setSwipeThresholds(
            float linearDistance,
            float maxDistance,
            float nonLinearFactor) {
        mCurrentTracker.setProgressThresholds(linearDistance, maxDistance, nonLinearFactor);
        mQueuedTracker.setProgressThresholds(linearDistance, maxDistance, nonLinearFactor);
    }

    private void invokeOrCancelBack(@NonNull TouchTracker touchTracker) {
        // Make a synchronized call to core before dispatch back event to client side.
        // If the close transition happens before the core receives onAnimationFinished, there will
        // play a second close animation for that transition.
        if (mBackAnimationFinishedCallback != null) {
            try {
                mBackAnimationFinishedCallback.onAnimationFinished(touchTracker.getTriggerBack());
            } catch (RemoteException e) {
                Log.e(TAG, "Failed call IBackAnimationFinishedCallback", e);
            }
            mBackAnimationFinishedCallback = null;
        }

        if (mBackNavigationInfo != null) {
            final IOnBackInvokedCallback callback = mBackNavigationInfo.getOnBackInvokedCallback();
            if (touchTracker.getTriggerBack()) {
                dispatchOrAnimateOnBackInvoked(callback, touchTracker);
            } else {
                dispatchOnBackCancelled(callback);
            }
        }
        finishBackNavigation(touchTracker.getTriggerBack());
    }

    /**
     * Called when the gesture is released, then it could start the post commit animation.
     */
    private void onGestureFinished() {
        TouchTracker activeTouchTracker = getActiveTracker();
        if (!mBackGestureStarted || activeTouchTracker == null) {
            // This can happen when an unfinished gesture has been reset in resetTouchTracker
            ProtoLog.d(WM_SHELL_BACK_PREVIEW,
                    "onGestureFinished called while no gesture is started");
            return;
        }
        boolean triggerBack = activeTouchTracker.getTriggerBack();
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "onGestureFinished() mTriggerBack == %s", triggerBack);

        mBackGestureStarted = false;
        activeTouchTracker.setState(TouchTracker.TouchTrackerState.FINISHED);

        if (mPostCommitAnimationInProgress) {
            ProtoLog.w(WM_SHELL_BACK_PREVIEW, "Animation is still running");
            return;
        }

        if (mBackNavigationInfo == null) {
            // No focus window found or core are running recents animation, inject back key as
            // legacy behavior, or new back gesture was started while previous has not finished yet
            if (!mQueuedTracker.isInInitialState()) {
                ProtoLog.e(WM_SHELL_BACK_PREVIEW, "mBackNavigationInfo is null AND there is "
                        + "another back animation in progress");
            }
            mCurrentTracker.reset();
            if (triggerBack) {
                injectBackKey();
            }
            finishBackNavigation(triggerBack);
            return;
        }

        final int backType = mBackNavigationInfo.getType();
        // Simply trigger and finish back navigation when no animator defined.
        if (!shouldDispatchToAnimator()
                || mShellBackAnimationRegistry.isAnimationCancelledOrNull(backType)) {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Trigger back without dispatching to animator.");
            invokeOrCancelBack(mCurrentTracker);
            mCurrentTracker.reset();
            return;
        } else if (mShellBackAnimationRegistry.isWaitingAnimation(backType)) {
            ProtoLog.w(WM_SHELL_BACK_PREVIEW, "Gesture released, but animation didn't ready.");
            // Supposed it is in post commit animation state, and start the timeout to watch
            // if the animation is ready.
            mShellExecutor.executeDelayed(mAnimationTimeoutRunnable, MAX_ANIMATION_DURATION);
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
        if (mCurrentTracker.getTriggerBack()) {
            dispatchOrAnimateOnBackInvoked(mActiveCallback, mCurrentTracker);
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

        if (mCurrentTracker.isActive() || mCurrentTracker.isFinished()) {
            // Trigger the real back.
            invokeOrCancelBack(mCurrentTracker);
        } else {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW,
                    "mCurrentBackGestureInfo was null when back animation finished");
        }
        resetTouchTracker();
    }

    /**
     * Resets the TouchTracker and potentially starts a new back navigation in case one is queued
     */
    private void resetTouchTracker() {
        TouchTracker temp = mCurrentTracker;
        mCurrentTracker = mQueuedTracker;
        temp.reset();
        mQueuedTracker = temp;

        if (mCurrentTracker.isInInitialState()) {
            if (mBackGestureStarted) {
                mBackGestureStarted = false;
                dispatchOnBackCancelled(mActiveCallback);
                finishBackNavigation(false);
                ProtoLog.d(WM_SHELL_BACK_PREVIEW,
                        "resetTouchTracker -> reset an unfinished gesture");
            } else {
                ProtoLog.d(WM_SHELL_BACK_PREVIEW, "resetTouchTracker -> no queued gesture");
            }
            return;
        }

        if (mCurrentTracker.isFinished() && mCurrentTracker.getTriggerBack()) {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW, "resetTouchTracker -> start queued back navigation "
                    + "AND post commit animation");
            injectBackKey();
            finishBackNavigation(true);
            mCurrentTracker.reset();
        } else if (!mCurrentTracker.isFinished()) {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW,
                    "resetTouchTracker -> queued gesture not finished; do nothing");
        } else {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW, "resetTouchTracker -> reset queued gesture");
            mCurrentTracker.reset();
        }
    }

    /**
     * This should be called after the whole back navigation is completed.
     */
    @VisibleForTesting
    void finishBackNavigation(boolean triggerBack) {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "BackAnimationController: finishBackNavigation()");
        mActiveCallback = null;
        mShouldStartOnNextMoveEvent = false;
        mOnBackStartDispatched = false;
        mShellBackAnimationRegistry.resetDefaultCrossActivity();
        cancelLatencyTracking();
        if (mBackNavigationInfo != null) {
            mBackNavigationInfo.onBackNavigationFinished(triggerBack);
            mBackNavigationInfo = null;
        }
    }

    private void startLatencyTracking() {
        if (mTrackingLatency) {
            cancelLatencyTracking();
        }
        mLatencyTracker.onActionStart(LatencyTracker.ACTION_BACK_SYSTEM_ANIMATION);
        mTrackingLatency = true;
    }

    private void cancelLatencyTracking() {
        if (!mTrackingLatency) {
            return;
        }
        mLatencyTracker.onActionCancel(LatencyTracker.ACTION_BACK_SYSTEM_ANIMATION);
        mTrackingLatency = false;
    }

    private void endLatencyTracking() {
        if (!mTrackingLatency) {
            return;
        }
        mLatencyTracker.onActionEnd(LatencyTracker.ACTION_BACK_SYSTEM_ANIMATION);
        mTrackingLatency = false;
    }

    private void createAdapter() {
        IBackAnimationRunner runner =
                new IBackAnimationRunner.Stub() {
                    @Override
                    public void onAnimationStart(
                            RemoteAnimationTarget[] apps,
                            RemoteAnimationTarget[] wallpapers,
                            RemoteAnimationTarget[] nonApps,
                            IBackAnimationFinishedCallback finishedCallback) {
                        mShellExecutor.execute(
                                () -> {
                                    endLatencyTracking();
                                    if (mBackNavigationInfo == null) {
                                        ProtoLog.e(WM_SHELL_BACK_PREVIEW,
                                                "Lack of navigation info to start animation.");
                                        return;
                                    }
                                    final BackAnimationRunner runner =
                                            mShellBackAnimationRegistry.getAnimationRunnerAndInit(
                                                    mBackNavigationInfo);
                                    if (runner == null) {
                                        if (finishedCallback != null) {
                                            try {
                                                finishedCallback.onAnimationFinished(false);
                                            } catch (RemoteException e) {
                                                Log.w(
                                                        TAG,
                                                        "Failed call IBackNaviAnimationController",
                                                        e);
                                            }
                                        }
                                        return;
                                    }
                                    mActiveCallback = runner.getCallback();
                                    mBackAnimationFinishedCallback = finishedCallback;

                                    ProtoLog.d(
                                            WM_SHELL_BACK_PREVIEW,
                                            "BackAnimationController: startAnimation()");
                                    runner.startAnimation(
                                            apps,
                                            wallpapers,
                                            nonApps,
                                            () ->
                                                    mShellExecutor.execute(
                                                            BackAnimationController.this
                                                                    ::onBackAnimationFinished));

                                    if (apps.length >= 1) {
                                        mCurrentTracker.updateStartLocation();
                                        BackMotionEvent startEvent =
                                                mCurrentTracker.createStartEvent(apps[0]);
                                        // {@code mActiveCallback} is the callback from
                                        // the BackAnimationRunners and not a real app-side
                                        // callback. We also dispatch to the app-side callback
                                        // (which should be a system callback with PRIORITY_SYSTEM)
                                        // to keep consistent with app registered callbacks.
                                        dispatchOnBackStarted(mActiveCallback, startEvent);
                                        tryDispatchAppOnBackStarted(
                                                mBackNavigationInfo.getOnBackInvokedCallback(),
                                                startEvent);
                                    }

                                    // Dispatch the first progress after animation start for
                                    // smoothing the initial animation, instead of waiting for next
                                    // onMove.
                                    final BackMotionEvent backFinish = mCurrentTracker
                                            .createProgressEvent();
                                    dispatchOnBackProgressed(mActiveCallback, backFinish);
                                    if (!mBackGestureStarted) {
                                        // if the down -> up gesture happened before animation
                                        // start, we have to trigger the uninterruptible transition
                                        // to finish the back animation.
                                        startPostCommitAnimation();
                                    }
                                });
                    }

                    @Override
                    public void onAnimationCancelled() {
                        mShellExecutor.execute(
                                () -> {
                                    if (!mShellBackAnimationRegistry.cancel(
                                            mBackNavigationInfo.getType())) {
                                        return;
                                    }
                                    if (!mBackGestureStarted) {
                                        invokeOrCancelBack(mCurrentTracker);
                                    }
                                });
                    }
                };
        mBackAnimationAdapter = new BackAnimationAdapter(runner);
    }

    /**
     * Description of current BackAnimationController state.
     */
    private void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "BackAnimationController state:");
        pw.println(prefix + "  mEnableAnimations=" + mEnableAnimations.get());
        pw.println(prefix + "  mBackGestureStarted=" + mBackGestureStarted);
        pw.println(prefix + "  mPostCommitAnimationInProgress=" + mPostCommitAnimationInProgress);
        pw.println(prefix + "  mShouldStartOnNextMoveEvent=" + mShouldStartOnNextMoveEvent);
        pw.println(prefix + "  mCurrentTracker state:");
        mCurrentTracker.dump(pw, prefix + "    ");
        pw.println(prefix + "  mQueuedTracker state:");
        mQueuedTracker.dump(pw, prefix + "    ");
    }

}
