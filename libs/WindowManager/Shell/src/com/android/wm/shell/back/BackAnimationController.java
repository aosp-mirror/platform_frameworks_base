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
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.util.Log;
import android.util.SparseArray;
import android.view.IRemoteAnimationFinishedCallback;
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
import com.android.wm.shell.common.ExternalInterfaceBinder;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ShellBackgroundThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.sysui.ShellController;
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
    public static final boolean IS_ENABLED =
            SystemProperties.getInt("persist.wm.debug.predictive_back",
                    SETTING_VALUE_ON) == SETTING_VALUE_ON;
     /** Flag for U animation features */
    public static boolean IS_U_ANIMATION_ENABLED =
            SystemProperties.getInt("persist.wm.debug.predictive_back_anim",
                    SETTING_VALUE_OFF) == SETTING_VALUE_ON;
    /** Predictive back animation developer option */
    private final AtomicBoolean mEnableAnimations = new AtomicBoolean(false);
    // TODO (b/241808055) Find a appropriate time to remove during refactor
    private static final boolean ENABLE_SHELL_TRANSITIONS = Transitions.ENABLE_SHELL_TRANSITIONS;
    /**
     * Max duration to wait for a transition to finish before accepting another gesture start
     * request.
     */
    private static final long MAX_TRANSITION_DURATION = 2000;

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
    private final ShellController mShellController;
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

    public BackAnimationController(
            @NonNull ShellInit shellInit,
            @NonNull ShellController shellController,
            @NonNull @ShellMainThread ShellExecutor shellExecutor,
            @NonNull @ShellBackgroundThread Handler backgroundHandler,
            Context context,
            Transitions transitions) {
        this(shellInit, shellController, shellExecutor, backgroundHandler,
                ActivityTaskManager.getService(), context, context.getContentResolver(),
                transitions);
    }

    @VisibleForTesting
    BackAnimationController(
            @NonNull ShellInit shellInit,
            @NonNull ShellController shellController,
            @NonNull @ShellMainThread ShellExecutor shellExecutor,
            @NonNull @ShellBackgroundThread Handler bgHandler,
            @NonNull IActivityTaskManager activityTaskManager,
            Context context, ContentResolver contentResolver,
            Transitions transitions) {
        mShellController = shellController;
        mShellExecutor = shellExecutor;
        mActivityTaskManager = activityTaskManager;
        mContext = context;
        mContentResolver = contentResolver;
        mBgHandler = bgHandler;
        shellInit.addInitCallback(this::onInit, this);
        mTransitions = transitions;
    }

    @VisibleForTesting
    void setEnableUAnimation(boolean enable) {
        IS_U_ANIMATION_ENABLED = enable;
    }

    private void onInit() {
        setupAnimationDeveloperSettingsObserver(mContentResolver, mBgHandler);
        createAdapter();
        if (ENABLE_SHELL_TRANSITIONS) {
            mBackTransitionHandler = new BackTransitionHandler(this);
            mTransitions.addHandler(mBackTransitionHandler);
        }
        mShellController.addExternalInterface(KEY_EXTRA_SHELL_BACK_ANIMATION,
                this::createExternalInterface, this);

        initBackAnimationRunners();
    }

    private void initBackAnimationRunners() {
        final IOnBackInvokedCallback dummyCallback = new IOnBackInvokedCallback.Default();
        final IRemoteAnimationRunner dummyRunner = new IRemoteAnimationRunner.Default() {
            @Override
            public void onAnimationStart(int transit, RemoteAnimationTarget[] apps,
                    RemoteAnimationTarget[] wallpapers, RemoteAnimationTarget[] nonApps,
                    IRemoteAnimationFinishedCallback finishedCallback) throws RemoteException {
                // Animation missing. Simply finish animation.
                finishedCallback.onAnimationFinished();
            }
        };

        final BackAnimationRunner dummyBackRunner =
                new BackAnimationRunner(dummyCallback, dummyRunner);
        final CrossTaskBackAnimation crossTaskAnimation = new CrossTaskBackAnimation(mContext);
        mAnimationDefinition.set(BackNavigationInfo.TYPE_CROSS_TASK,
                new BackAnimationRunner(crossTaskAnimation.mCallback, crossTaskAnimation.mRunner));
        // TODO (238474994): register cross activity animation when it's completed.
        mAnimationDefinition.set(BackNavigationInfo.TYPE_CROSS_ACTIVITY, dummyBackRunner);
        // TODO (236760237): register dialog close animation when it's completed.
        mAnimationDefinition.set(BackNavigationInfo.TYPE_DIALOG_CLOSE, dummyBackRunner);
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
                    (controller) -> controller.setBackToLauncherCallback(callback, runner));
        }

        @Override
        public void clearBackToLauncherCallback() {
            executeRemoteCallWithTaskPermission(mController, "clearBackToLauncherCallback",
                    (controller) -> controller.clearBackToLauncherCallback());
        }

        @Override
        public void invalidate() {
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

        mTouchTracker.update(touchX, touchY);
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
            mAnimationDefinition.get(backType).startGesture();
        } else {
            targetCallback = mBackNavigationInfo.getOnBackInvokedCallback();
            dispatchOnBackStarted(targetCallback, mTouchTracker.createStartEvent(null));
        }
    }

    private void onMove(float touchX, float touchY, @BackEvent.SwipeEdge int swipeEdge) {
        if (!mBackGestureStarted || mBackNavigationInfo == null || !mEnableAnimations.get()) {
            return;
        }
        final BackEvent backEvent = mTouchTracker.createProgressEvent();

        int backType = mBackNavigationInfo.getType();
        IOnBackInvokedCallback targetCallback;
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

    private void dispatchOnBackStarted(IOnBackInvokedCallback callback,
            BackEvent backEvent) {
        if (callback == null) {
            return;
        }
        try {
            if (shouldDispatchAnimation(callback)) {
                callback.onBackStarted(backEvent);
            }
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
            if (shouldDispatchAnimation(callback)) {
                callback.onBackCancelled();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "dispatchOnBackCancelled error: ", e);
        }
    }

    private void dispatchOnBackProgressed(IOnBackInvokedCallback callback,
            BackEvent backEvent) {
        if (callback == null) {
            return;
        }
        try {
            if (shouldDispatchAnimation(callback)) {
                callback.onBackProgressed(backEvent);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "dispatchOnBackProgressed error: ", e);
        }
    }

    private boolean shouldDispatchAnimation(IOnBackInvokedCallback callback) {
        return (IS_U_ANIMATION_ENABLED || callback == mAnimationDefinition.get(
                BackNavigationInfo.TYPE_RETURN_TO_HOME).getCallback())
                && mEnableAnimations.get();
    }

    /**
     * Sets to true when the back gesture has passed the triggering threshold, false otherwise.
     */
    public void setTriggerBack(boolean triggerBack) {
        if (mTransitionInProgress) {
            return;
        }
        mTriggerBack = triggerBack;
        mTouchTracker.setTriggerBack(triggerBack);
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
                    if (apps.length >= 1) {
                        final int backType = mBackNavigationInfo.getType();
                        IOnBackInvokedCallback targetCallback = mAnimationDefinition.get(backType)
                                .getCallback();
                        dispatchOnBackStarted(
                                targetCallback, mTouchTracker.createStartEvent(apps[0]));
                    }

                    if (!mBackGestureStarted) {
                        // if the down -> up gesture happened before animation start, we have to
                        // trigger the uninterruptible transition to finish the back animation.
                        final BackEvent backFinish = mTouchTracker.createProgressEvent(1);
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
