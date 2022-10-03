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

import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.RemoteAnimationTarget.MODE_OPENING;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_BACK_PREVIEW;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.HardwareBuffer;
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
import android.view.IWindowFocusObserver;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.BackAnimationAdaptor;
import android.window.BackEvent;
import android.window.BackNavigationInfo;
import android.window.IBackAnimationRunner;
import android.window.IBackNaviAnimationController;
import android.window.IOnBackInvokedCallback;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ShellBackgroundThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.sysui.ShellInit;

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
    private final AtomicBoolean mEnableAnimations = new AtomicBoolean(false);
    // TODO (b/241808055) Find a appropriate time to remove during refactor
    private static final boolean USE_TRANSITION =
            SystemProperties.getInt("persist.wm.debug.predictive_back_ani_trans", 1) != 0;
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
    private final SurfaceControl.Transaction mTransaction;
    private final IActivityTaskManager mActivityTaskManager;
    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final ShellExecutor mShellExecutor;
    private final Handler mBgHandler;
    @Nullable
    private IOnBackInvokedCallback mBackToLauncherCallback;
    private float mTriggerThreshold;
    private float mProgressThreshold;
    private final Runnable mResetTransitionRunnable = () -> {
        finishAnimation();
        mTransitionInProgress = false;
    };

    private RemoteAnimationTarget mAnimationTarget;
    IBackAnimationRunner mIBackAnimationRunner;
    private IBackNaviAnimationController mBackAnimationController;
    private BackAnimationAdaptor mBackAnimationAdaptor;

    private boolean mWaitingAnimationStart;
    private final TouchTracker mTouchTracker = new TouchTracker();
    private final CachingBackDispatcher mCachingBackDispatcher = new CachingBackDispatcher();

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

        int getDeltaFromGestureStart(float touchX) {
            return Math.round(touchX - mInitTouchX);
        }

        void reset() {
            mInitTouchX = 0;
            mInitTouchY = 0;
        }
    }

    /**
     * Cache the temporary callback and trigger result if gesture was finish before received
     * BackAnimationRunner#onAnimationStart/cancel, so there can continue play the animation.
     */
    private class CachingBackDispatcher {
        private IOnBackInvokedCallback mOnBackCallback;
        private boolean mTriggerBack;
        // Whether we are waiting to receive onAnimationStart
        private boolean mWaitingAnimation;

        void startWaitingAnimation() {
            mWaitingAnimation = true;
        }

        boolean set(IOnBackInvokedCallback callback, boolean triggerBack) {
            if (mWaitingAnimation) {
                mOnBackCallback = callback;
                mTriggerBack = triggerBack;
                return true;
            }
            return false;
        }

        boolean consume() {
            boolean consumed = false;
            if (mWaitingAnimation && mOnBackCallback != null) {
                if (mTriggerBack) {
                    final BackEvent backFinish = new BackEvent(
                            mTouchTracker.mLatestTouchX, mTouchTracker.mLatestTouchY, 1,
                            mTouchTracker.mSwipeEdge, mAnimationTarget);
                    dispatchOnBackProgressed(mBackToLauncherCallback, backFinish);
                    dispatchOnBackInvoked(mOnBackCallback);
                } else {
                    final BackEvent backFinish = new BackEvent(
                            mTouchTracker.mLatestTouchX, mTouchTracker.mLatestTouchY, 0,
                            mTouchTracker.mSwipeEdge, mAnimationTarget);
                    dispatchOnBackProgressed(mBackToLauncherCallback, backFinish);
                    dispatchOnBackCancelled(mOnBackCallback);
                }
                startTransition();
                consumed = true;
            }
            mOnBackCallback = null;
            mWaitingAnimation = false;
            return consumed;
        }
    }

    public BackAnimationController(
            @NonNull ShellInit shellInit,
            @NonNull @ShellMainThread ShellExecutor shellExecutor,
            @NonNull @ShellBackgroundThread Handler backgroundHandler,
            Context context) {
        this(shellInit, shellExecutor, backgroundHandler, new SurfaceControl.Transaction(),
                ActivityTaskManager.getService(), context, context.getContentResolver());
    }

    @VisibleForTesting
    BackAnimationController(
            @NonNull ShellInit shellInit,
            @NonNull @ShellMainThread ShellExecutor shellExecutor,
            @NonNull @ShellBackgroundThread Handler bgHandler,
            @NonNull SurfaceControl.Transaction transaction,
            @NonNull IActivityTaskManager activityTaskManager,
            Context context, ContentResolver contentResolver) {
        mShellExecutor = shellExecutor;
        mTransaction = transaction;
        mActivityTaskManager = activityTaskManager;
        mContext = context;
        mContentResolver = contentResolver;
        mBgHandler = bgHandler;
        shellInit.addInitCallback(this::onInit, this);
    }

    private void onInit() {
        setupAnimationDeveloperSettingsObserver(mContentResolver, mBgHandler);
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
        public void setBackToLauncherCallback(IOnBackInvokedCallback callback) {
            executeRemoteCallWithTaskPermission(mController, "setBackToLauncherCallback",
                    (controller) -> controller.setBackToLauncherCallback(callback));
        }

        @Override
        public void clearBackToLauncherCallback() {
            executeRemoteCallWithTaskPermission(mController, "clearBackToLauncherCallback",
                    (controller) -> controller.clearBackToLauncherCallback());
        }

        @Override
        public void onBackToLauncherAnimationFinished() {
            executeRemoteCallWithTaskPermission(mController, "onBackToLauncherAnimationFinished",
                    (controller) -> controller.onBackToLauncherAnimationFinished());
        }

        void invalidate() {
            mController = null;
        }
    }

    @VisibleForTesting
    void setBackToLauncherCallback(IOnBackInvokedCallback callback) {
        mBackToLauncherCallback = callback;
        if (USE_TRANSITION) {
            createAdaptor();
        }
    }

    private void clearBackToLauncherCallback() {
        mBackToLauncherCallback = null;
    }

    @VisibleForTesting
    void onBackToLauncherAnimationFinished() {
        final boolean triggerBack = mTriggerBack;
        IOnBackInvokedCallback callback = mBackNavigationInfo != null
                ? mBackNavigationInfo.getOnBackInvokedCallback() : null;
        // Make sure the notification sequence should be controller > client.
        finishAnimation();
        if (callback != null) {
            if (triggerBack) {
                dispatchOnBackInvoked(callback);
            } else {
                dispatchOnBackCancelled(callback);
            }
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
            finishAnimation();
        }

        mTouchTracker.setGestureStartLocation(touchX, touchY);
        mBackGestureStarted = true;

        try {
            boolean requestAnimation = mEnableAnimations.get();
            mBackNavigationInfo = mActivityTaskManager.startBackNavigation(requestAnimation,
                    mFocusObserver, mBackAnimationAdaptor);
            onBackNavigationInfoReceived(mBackNavigationInfo);
        } catch (RemoteException remoteException) {
            Log.e(TAG, "Failed to initAnimation", remoteException);
            finishAnimation();
        }
    }

    private void onBackNavigationInfoReceived(@Nullable BackNavigationInfo backNavigationInfo) {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Received backNavigationInfo:%s", backNavigationInfo);
        if (backNavigationInfo == null) {
            Log.e(TAG, "Received BackNavigationInfo is null.");
            return;
        }
        int backType = backNavigationInfo.getType();
        IOnBackInvokedCallback targetCallback = null;
        final boolean dispatchToLauncher = shouldDispatchToLauncher(backType);
        if (backType == BackNavigationInfo.TYPE_CROSS_ACTIVITY) {
            HardwareBuffer hardwareBuffer = backNavigationInfo.getScreenshotHardwareBuffer();
            if (hardwareBuffer != null) {
                displayTargetScreenshot(hardwareBuffer,
                        backNavigationInfo.getTaskWindowConfiguration());
            }
            mTransaction.apply();
        } else if (dispatchToLauncher) {
            targetCallback = mBackToLauncherCallback;
            if (USE_TRANSITION) {
                mCachingBackDispatcher.startWaitingAnimation();
            }
        } else if (backType == BackNavigationInfo.TYPE_CALLBACK) {
            targetCallback = mBackNavigationInfo.getOnBackInvokedCallback();
        }
        if (!USE_TRANSITION || !dispatchToLauncher) {
            dispatchOnBackStarted(targetCallback);
        }
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

    private void onMove(float touchX, float touchY, @BackEvent.SwipeEdge int swipeEdge) {
        if (!mBackGestureStarted || mBackNavigationInfo == null) {
            return;
        }
        int deltaX = mTouchTracker.getDeltaFromGestureStart(touchX);
        float progressThreshold = PROGRESS_THRESHOLD >= 0 ? PROGRESS_THRESHOLD : mProgressThreshold;
        float progress = Math.min(Math.max(Math.abs(deltaX) / progressThreshold, 0), 1);
        if (USE_TRANSITION) {
            if (mBackAnimationController != null && mAnimationTarget != null) {
                final BackEvent backEvent = new BackEvent(
                        touchX, touchY, progress, swipeEdge, mAnimationTarget);
                dispatchOnBackProgressed(mBackToLauncherCallback, backEvent);
            }
        } else {
            int backType = mBackNavigationInfo.getType();
            RemoteAnimationTarget animationTarget =
                    mBackNavigationInfo.getDepartingAnimationTarget();

            BackEvent backEvent = new BackEvent(
                    touchX, touchY, progress, swipeEdge, animationTarget);
            IOnBackInvokedCallback targetCallback = null;
            if (shouldDispatchToLauncher(backType)) {
                targetCallback = mBackToLauncherCallback;
            } else if (backType == BackNavigationInfo.TYPE_CROSS_TASK
                    || backType == BackNavigationInfo.TYPE_CROSS_ACTIVITY) {
                // TODO(208427216) Run the actual animation
            } else if (backType == BackNavigationInfo.TYPE_CALLBACK) {
                targetCallback = mBackNavigationInfo.getOnBackInvokedCallback();
            }
            dispatchOnBackProgressed(targetCallback, backEvent);
        }
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
            finishAnimation();
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
            finishAnimation();
            return;
        }

        int backType = mBackNavigationInfo.getType();
        boolean shouldDispatchToLauncher = shouldDispatchToLauncher(backType);
        IOnBackInvokedCallback targetCallback = shouldDispatchToLauncher
                ? mBackToLauncherCallback
                : mBackNavigationInfo.getOnBackInvokedCallback();
        if (mCachingBackDispatcher.set(targetCallback, mTriggerBack)) {
            return;
        }
        if (shouldDispatchToLauncher) {
            startTransition();
        }
        if (mTriggerBack) {
            dispatchOnBackInvoked(targetCallback);
        } else {
            dispatchOnBackCancelled(targetCallback);
        }
        if (backType != BackNavigationInfo.TYPE_RETURN_TO_HOME || !shouldDispatchToLauncher) {
            // Launcher callback missing. Simply finish animation.
            finishAnimation();
        }
    }

    private boolean shouldDispatchToLauncher(int backType) {
        return backType == BackNavigationInfo.TYPE_RETURN_TO_HOME
                && mBackToLauncherCallback != null
                && mEnableAnimations.get()
                && mBackNavigationInfo != null
                && ((USE_TRANSITION && mBackNavigationInfo.isPrepareRemoteAnimation())
                || mBackNavigationInfo.getDepartingAnimationTarget() != null);
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
        mProgressThreshold = progressThreshold;
        mTriggerThreshold = triggerThreshold;
    }

    private void finishAnimation() {
        ProtoLog.d(WM_SHELL_BACK_PREVIEW, "BackAnimationController: finishAnimation()");
        mTouchTracker.reset();
        BackNavigationInfo backNavigationInfo = mBackNavigationInfo;
        boolean triggerBack = mTriggerBack;
        mBackNavigationInfo = null;
        mTriggerBack = false;
        mShouldStartOnNextMoveEvent = false;
        if (backNavigationInfo == null) {
            return;
        }

        if (!USE_TRANSITION) {
            RemoteAnimationTarget animationTarget = backNavigationInfo
                    .getDepartingAnimationTarget();
            if (animationTarget != null) {
                if (animationTarget.leash != null && animationTarget.leash.isValid()) {
                    mTransaction.remove(animationTarget.leash);
                }
            }
            SurfaceControl screenshotSurface = backNavigationInfo.getScreenshotSurface();
            if (screenshotSurface != null && screenshotSurface.isValid()) {
                mTransaction.remove(screenshotSurface);
            }
            mTransaction.apply();
        }
        stopTransition();
        backNavigationInfo.onBackNavigationFinished(triggerBack);
        if (USE_TRANSITION) {
            final IBackNaviAnimationController controller = mBackAnimationController;
            if (controller != null) {
                try {
                    controller.finish(triggerBack);
                } catch (RemoteException r) {
                    // Oh no!
                }
            }
            mBackAnimationController = null;
        }
    }

    private void startTransition() {
        if (mTransitionInProgress) {
            return;
        }
        mTransitionInProgress = true;
        mShellExecutor.executeDelayed(mResetTransitionRunnable, MAX_TRANSITION_DURATION);
    }

    private void stopTransition() {
        if (!mTransitionInProgress) {
            return;
        }
        mShellExecutor.removeCallbacks(mResetTransitionRunnable);
        mTransitionInProgress = false;
    }

    private void createAdaptor() {
        mIBackAnimationRunner = new IBackAnimationRunner.Stub() {
            @Override
            public void onAnimationCancelled() {
                // no op for now
            }
            @Override // Binder interface
            public void onAnimationStart(IBackNaviAnimationController controller, int type,
                    RemoteAnimationTarget[] apps, RemoteAnimationTarget[] wallpapers,
                    RemoteAnimationTarget[] nonApps) {
                mShellExecutor.execute(() -> {
                    mBackAnimationController = controller;
                    for (int i = 0; i < apps.length; i++) {
                        final RemoteAnimationTarget target = apps[i];
                        if (MODE_CLOSING == target.mode) {
                            mAnimationTarget = target;
                        } else if (MODE_OPENING == target.mode) {
                            // TODO Home activity should handle the visibility for itself
                            //  once it finish relayout for orientation change
                            SurfaceControl.Transaction tx =
                                    new SurfaceControl.Transaction();
                            tx.setAlpha(target.leash, 1);
                            tx.apply();
                        }
                    }
                    // TODO animation target should be passed at onBackStarted
                    dispatchOnBackStarted(mBackToLauncherCallback);
                    // TODO This is Workaround for LauncherBackAnimationController, there will need
                    //  to dispatch onBackProgressed twice(startBack & updateBackProgress) to
                    //  initialize the animation data, for now that would happen when onMove
                    //  called, but there will no expected animation if the down -> up gesture
                    //  happen very fast which ACTION_MOVE only happen once.
                    final BackEvent backInit = new BackEvent(
                            mTouchTracker.mLatestTouchX, mTouchTracker.mLatestTouchY, 0,
                            mTouchTracker.mSwipeEdge, mAnimationTarget);
                    dispatchOnBackProgressed(mBackToLauncherCallback, backInit);
                    if (!mCachingBackDispatcher.consume()) {
                        dispatchOnBackProgressed(mBackToLauncherCallback, backInit);
                    }
                });
            }
        };
        mBackAnimationAdaptor = new BackAnimationAdaptor(mIBackAnimationRunner,
                BackNavigationInfo.TYPE_RETURN_TO_HOME);
    }
}
