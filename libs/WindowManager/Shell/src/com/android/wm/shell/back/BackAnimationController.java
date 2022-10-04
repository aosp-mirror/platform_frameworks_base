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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.util.Log;
import android.util.SparseArray;
import android.view.IRemoteAnimationRunner;
import android.view.IWindowFocusObserver;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.RemoteAnimationTarget;
import android.window.BackAnimationAdapter;
import android.window.BackEvent;
import android.window.BackNavigationInfo;
import android.window.IBackAnimationFinishedCallback;
import android.window.IBackAnimationRunner;
import android.window.IOnBackInvokedCallback;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ShellBackgroundThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controls the window animation run when a user initiates a back gesture.
 */
public class BackAnimationController implements RemoteCallable<BackAnimationController> {
    private static final String TAG = "BackAnimationController";
    private static final int SETTING_VALUE_OFF = 0;
    private static final int SETTING_VALUE_ON = 1;
    private static final String PREDICTIVE_BACK_PROGRESS_THRESHOLD_PROP =
            "persist.wm.debug.predictive_back_progress_threshold";
    public static final boolean IS_ENABLED =
            SystemProperties.getInt("persist.wm.debug.predictive_back",
                    SETTING_VALUE_ON) != SETTING_VALUE_OFF;
    private static final int PROGRESS_THRESHOLD = SystemProperties
            .getInt(PREDICTIVE_BACK_PROGRESS_THRESHOLD_PROP, -1);

    // TODO (b/241808055) Find a appropriate time to remove during refactor
    private static final boolean ENABLE_SHELL_TRANSITIONS = Transitions.ENABLE_SHELL_TRANSITIONS;
    /**
     * Max duration to wait for a transition to finish before accepting another gesture start
     * request.
     */
    private static final long MAX_TRANSITION_DURATION = 2000;

    private final AtomicBoolean mEnableAnimations = new AtomicBoolean(false);

    /** True when a back gesture is ongoing */
    private boolean mBackGestureStarted = false;

    /** Tracks if an uninterruptible transition is in progress */
    private boolean mTransitionInProgress = false;
    /** Tracks if we should start the back gesture on the next motion move event */
    private boolean mShouldStartOnNextMoveEvent = false;
    /** @see #setTriggerBack(boolean) */
    private boolean mTriggerBack;

    @Nullable
    private BackNavigationInfo mBackNavigationInfo;
    private final IActivityTaskManager mActivityTaskManager;
    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final ShellExecutor mShellExecutor;
    private final Handler mBgHandler;
    private final Runnable mResetTransitionRunnable = () -> {
        ProtoLog.w(WM_SHELL_BACK_PREVIEW, "Transition didn't finish in %d ms. Resetting...",
                MAX_TRANSITION_DURATION);
        onBackAnimationFinished();
    };

    private IBackAnimationFinishedCallback mBackAnimationFinishedCallback;
    @VisibleForTesting
    BackAnimationAdapter mBackAnimationAdapter;

    private final TouchTracker mTouchTracker = new TouchTracker();

    private final SparseArray<BackAnimationRunner> mAnimationDefinition = new SparseArray<>();
    private final Transitions mTransitions;
    private BackTransitionHandler mBackTransitionHandler;

    @VisibleForTesting
    final IWindowFocusObserver mFocusObserver = new IWindowFocusObserver.Stub() {
        @Override
        public void focusGained(IBinder inputToken) { }
        @Override
        public void focusLost(IBinder inputToken) {
            mShellExecutor.execute(() -> {
                if (!mBackGestureStarted || mTransitionInProgress) {
                    // If an uninterruptible transition is already in progress, we should ignore
                    // this due to the transition may cause focus lost. (alpha = 0)
                    return;
                }
                ProtoLog.i(WM_SHELL_BACK_PREVIEW, "Target window lost focus.");
                setTriggerBack(false);
                onGestureFinished(false);
            });
        }
    };

    /**
     * Helper class to record the touch location for gesture start and latest.
     */
    private static class TouchTracker {
        /**
         * Location of the latest touch event
         */
        private float mLatestTouchX;
        private float mLatestTouchY;
        private int mSwipeEdge;
        private float mProgressThreshold;

        /**
         * Location of the initial touch event of the back gesture.
         */
        private float mInitTouchX;
        private float mInitTouchY;

        void update(float touchX, float touchY, int swipeEdge) {
            mLatestTouchX = touchX;
            mLatestTouchY = touchY;
            mSwipeEdge = swipeEdge;
        }

        void setGestureStartLocation(float touchX, float touchY) {
            mInitTouchX = touchX;
            mInitTouchY = touchY;
        }

        void setProgressThreshold(float progressThreshold) {
            mProgressThreshold = progressThreshold;
        }

        float getProgress(float touchX) {
            int deltaX = Math.round(touchX - mInitTouchX);
            float progressThreshold = PROGRESS_THRESHOLD >= 0
                    ? PROGRESS_THRESHOLD : mProgressThreshold;
            return Math.min(Math.max(Math.abs(deltaX) / progressThreshold, 0), 1);
        }

        void reset() {
            mInitTouchX = 0;
            mInitTouchY = 0;
            mSwipeEdge = -1;
        }
    }

    public BackAnimationController(
            @NonNull ShellInit shellInit,
            @NonNull @ShellMainThread ShellExecutor shellExecutor,
            @NonNull @ShellBackgroundThread Handler backgroundHandler,
            Context context,
            Transitions transitions) {
        this(shellInit, shellExecutor, backgroundHandler,
                ActivityTaskManager.getService(), context, context.getContentResolver(),
                transitions);
    }

    @VisibleForTesting
    BackAnimationController(
            @NonNull ShellInit shellInit,
            @NonNull @ShellMainThread ShellExecutor shellExecutor,
            @NonNull @ShellBackgroundThread Handler bgHandler,
            @NonNull IActivityTaskManager activityTaskManager,
            Context context, ContentResolver contentResolver,
            Transitions transitions) {
        mShellExecutor = shellExecutor;
        mActivityTaskManager = activityTaskManager;
        mContext = context;
        mContentResolver = contentResolver;
        mBgHandler = bgHandler;
        shellInit.addInitCallback(this::onInit, this);
        mTransitions = transitions;
    }

    private void onInit() {
        setupAnimationDeveloperSettingsObserver(mContentResolver, mBgHandler);
        createAdapter();
        if (ENABLE_SHELL_TRANSITIONS) {
            mBackTransitionHandler = new BackTransitionHandler(this);
            mTransitions.addHandler(mBackTransitionHandler);
        }
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
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Back animation enabled=%s",
                isEnabled);
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
        public void onBackMotion(
                float touchX, float touchY, int keyAction, @BackEvent.SwipeEdge int swipeEdge) {
            mShellExecutor.execute(() -> onMotionEvent(touchX, touchY, keyAction, swipeEdge));
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
    }

    private static class IBackAnimationImpl extends IBackAnimation.Stub {
        private BackAnimationController mController;

        IBackAnimationImpl(BackAnimationController controller) {
            mController = controller;
        }

        @Override
        public void setBackToLauncherCallback(IOnBackInvokedCallback callback,
                IRemoteAnimationRunner runner) {
            executeRemoteCallWithTaskPermission(mController, "setBackToLauncherCallback",
                    (controller) -> controller.setBackToLauncherCallback(callback, runner));
        }

        @Override
        public void clearBackToLauncherCallback() {
            executeRemoteCallWithTaskPermission(mController, "clearBackToLauncherCallback",
                    (controller) -> controller.clearBackToLauncherCallback());
        }

        void invalidate() {
            mController = null;
        }
    }

    @VisibleForTesting
    void setBackToLauncherCallback(IOnBackInvokedCallback callback, IRemoteAnimationRunner runner) {
        mAnimationDefinition.set(BackNavigationInfo.TYPE_RETURN_TO_HOME,
                new BackAnimationRunner(callback, runner));
    }

    private void clearBackToLauncherCallback() {
        mAnimationDefinition.remove(BackNavigationInfo.TYPE_RETURN_TO_HOME);
    }

    @VisibleForTesting
    void onBackAnimationFinished() {
        if (!mTransitionInProgress) {
            return;
        }

        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "BackAnimationController: onBackAnimationFinished()");

        // Trigger real back.
        if (mBackNavigationInfo != null) {
            IOnBackInvokedCallback callback = mBackNavigationInfo.getOnBackInvokedCallback();
            if (mTriggerBack) {
                dispatchOnBackInvoked(callback);
            } else {
                dispatchOnBackCancelled(callback);
            }
        }

        // In legacy transition, it would use `Task.mBackGestureStarted` in core to handle the
        // following transition when back callback is invoked.
        // If the back callback is not invoked, we should reset the token and finish the whole back
        // navigation without waiting the transition.
        if (!ENABLE_SHELL_TRANSITIONS) {
            finishBackNavigation();
        } else if (!mTriggerBack) {
            // reset the token to prevent it consume next transition.
            mBackTransitionHandler.setDepartingWindowContainerToken(null);
            finishBackNavigation();
        }
    }

    /**
     * Called when a new motion event needs to be transferred to this
     * {@link BackAnimationController}
     */
    public void onMotionEvent(float touchX, float touchY, int keyAction,
            @BackEvent.SwipeEdge int swipeEdge) {
        if (mTransitionInProgress) {
            return;
        }
        mTouchTracker.update(touchX, touchY, swipeEdge);
        if (keyAction == MotionEvent.ACTION_DOWN) {
            if (!mBackGestureStarted) {
                mShouldStartOnNextMoveEvent = true;
            }
        } else if (keyAction == MotionEvent.ACTION_MOVE) {
            if (!mBackGestureStarted && mShouldStartOnNextMoveEvent) {
                // Let the animation initialized here to make sure the onPointerDownOutsideFocus
                // could be happened when ACTION_DOWN, it may change the current focus that we
                // would access it when startBackNavigation.
                onGestureStarted(touchX, touchY);
                mShouldStartOnNextMoveEvent = false;
            }
            onMove(touchX, touchY, swipeEdge);
        } else if (keyAction == MotionEvent.ACTION_UP || keyAction == MotionEvent.ACTION_CANCEL) {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW,
                    "Finishing gesture with event action: %d", keyAction);
            if (keyAction == MotionEvent.ACTION_CANCEL) {
                mTriggerBack = false;
            }
            onGestureFinished(true);
        }
    }

    private void onGestureStarted(float touchX, float touchY) {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "initAnimation mMotionStarted=%b", mBackGestureStarted);
        if (mBackGestureStarted || mBackNavigationInfo != null) {
            Log.e(TAG, "Animation is being initialized but is already started.");
            finishBackNavigation();
        }

        mTouchTracker.setGestureStartLocation(touchX, touchY);
        mBackGestureStarted = true;

        try {
            mBackNavigationInfo = mActivityTaskManager.startBackNavigation(
                    mFocusObserver, mEnableAnimations.get() ? mBackAnimationAdapter : null);
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
        final IOnBackInvokedCallback targetCallback;
        final boolean shouldDispatchToAnimator = shouldDispatchToAnimator(backType);
        if (shouldDispatchToAnimator) {
            targetCallback = mAnimationDefinition.get(backType).getGestureStartedCallback();
        } else {
            targetCallback = mBackNavigationInfo.getOnBackInvokedCallback();
        }
        if (shouldDispatchToAnimator) {
            dispatchOnBackStarted(targetCallback);
        }
    }

    private void onMove(float touchX, float touchY, @BackEvent.SwipeEdge int swipeEdge) {
        if (!mBackGestureStarted || mBackNavigationInfo == null || !mEnableAnimations.get()) {
            return;
        }
        mTouchTracker.update(touchX, touchY, swipeEdge);
        float progress = mTouchTracker.getProgress(touchX);
        int backType = mBackNavigationInfo.getType();

        BackEvent backEvent = new BackEvent(touchX, touchY, progress, swipeEdge);
        IOnBackInvokedCallback targetCallback = null;
        if (shouldDispatchToAnimator(backType)) {
            targetCallback = mAnimationDefinition.get(backType).getCallback();
        } else {
            targetCallback = mBackNavigationInfo.getOnBackInvokedCallback();
        }
        dispatchOnBackProgressed(targetCallback, backEvent);
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
        if (!InputManager.getInstance()
                .injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)) {
            Log.e(TAG, "Inject input event fail");
        }
    }

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

        if (mTransitionInProgress) {
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

        int backType = mBackNavigationInfo.getType();
        boolean shouldDispatchToAnimator = shouldDispatchToAnimator(backType);
        final BackAnimationRunner runner = mAnimationDefinition.get(backType);
        IOnBackInvokedCallback targetCallback = shouldDispatchToAnimator
                ? runner.getCallback() : mBackNavigationInfo.getOnBackInvokedCallback();

        if (shouldDispatchToAnimator) {
            if (runner.onGestureFinished(mTriggerBack)) {
                Log.w(TAG, "Gesture released, but animation didn't ready.");
                return;
            }
            startTransition();
        }
        if (mTriggerBack) {
            dispatchOnBackInvoked(targetCallback);
        } else {
            dispatchOnBackCancelled(targetCallback);
        }
        if (!shouldDispatchToAnimator) {
            // Animation callback missing. Simply finish animation.
            finishBackNavigation();
        }
    }

    private boolean shouldDispatchToAnimator(int backType) {
        return mEnableAnimations.get()
                && mBackNavigationInfo != null
                && mBackNavigationInfo.isPrepareRemoteAnimation()
                && mAnimationDefinition.contains(backType);
    }

    private static void dispatchOnBackStarted(IOnBackInvokedCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.onBackStarted();
        } catch (RemoteException e) {
            Log.e(TAG, "dispatchOnBackStarted error: ", e);
        }
    }

    private static void dispatchOnBackInvoked(IOnBackInvokedCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.onBackInvoked();
        } catch (RemoteException e) {
            Log.e(TAG, "dispatchOnBackInvoked error: ", e);
        }
    }

    private static void dispatchOnBackCancelled(IOnBackInvokedCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.onBackCancelled();
        } catch (RemoteException e) {
            Log.e(TAG, "dispatchOnBackCancelled error: ", e);
        }
    }

    private static void dispatchOnBackProgressed(IOnBackInvokedCallback callback,
            BackEvent backEvent) {
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
        if (mTransitionInProgress) {
            return;
        }
        mTriggerBack = triggerBack;
    }

    private void setSwipeThresholds(float triggerThreshold, float progressThreshold) {
        mTouchTracker.setProgressThreshold(progressThreshold);
    }

    @VisibleForTesting
    void finishBackNavigation() {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "BackAnimationController: finishBackNavigation()");
        BackNavigationInfo backNavigationInfo = mBackNavigationInfo;
        boolean triggerBack = mTriggerBack;
        mBackNavigationInfo = null;
        mTriggerBack = false;
        mShouldStartOnNextMoveEvent = false;
        mTouchTracker.reset();
        if (backNavigationInfo == null) {
            return;
        }
        stopTransition();
        if (mBackAnimationFinishedCallback != null) {
            try {
                mBackAnimationFinishedCallback.onAnimationFinished(triggerBack);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed call IBackAnimationFinishedCallback", e);
            }
            mBackAnimationFinishedCallback = null;
        }
        backNavigationInfo.onBackNavigationFinished(triggerBack);
    }

    private void startTransition() {
        if (mTransitionInProgress) {
            return;
        }
        mTransitionInProgress = true;
        if (ENABLE_SHELL_TRANSITIONS) {
            mBackTransitionHandler.setDepartingWindowContainerToken(
                    mBackNavigationInfo.getDepartingWindowContainerToken());
        }
        mShellExecutor.executeDelayed(mResetTransitionRunnable, MAX_TRANSITION_DURATION);
    }

    void stopTransition() {
        mShellExecutor.removeCallbacks(mResetTransitionRunnable);
        mTransitionInProgress = false;
    }

    /**
     * This should be called from {@link BackTransitionHandler#startAnimation} when the following
     * transition is triggered by the real back callback in {@link #onBackAnimationFinished}.
     * Will consume the default transition and finish current back navigation.
     */
    void finishTransition(Transitions.TransitionFinishCallback finishCallback) {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "BackAnimationController: finishTransition()");
        mShellExecutor.execute(() -> {
            finishBackNavigation();
            finishCallback.onTransitionFinished(null, null);
        });
    }

    private void createAdapter() {
        IBackAnimationRunner runner = new IBackAnimationRunner.Stub() {
            @Override
            public void onAnimationStart(int type, RemoteAnimationTarget[] apps,
                    RemoteAnimationTarget[] wallpapers, RemoteAnimationTarget[] nonApps,
                    IBackAnimationFinishedCallback finishedCallback) {
                mShellExecutor.execute(() -> {
                    final BackAnimationRunner runner = mAnimationDefinition.get(type);
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
                    mBackAnimationFinishedCallback = finishedCallback;

                    ProtoLog.d(WM_SHELL_BACK_PREVIEW, "BackAnimationController: startAnimation()");
                    runner.startAnimation(apps, wallpapers, nonApps,
                            BackAnimationController.this::onBackAnimationFinished);

                    if (!mBackGestureStarted) {
                        // if the down -> up gesture happened before animation start, we have to
                        // trigger the uninterruptible transition to finish the back animation.
                        final BackEvent backFinish = new BackEvent(
                                mTouchTracker.mLatestTouchX, mTouchTracker.mLatestTouchY, 1,
                                mTouchTracker.mSwipeEdge);
                        startTransition();
                        runner.consumeIfGestureFinished(backFinish);
                    }
                });
            }

            @Override
            public void onAnimationCancelled() { }
        };
        mBackAnimationAdapter = new BackAnimationAdapter(runner);
    }
}
