/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.recents;

import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_OVERVIEW;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;

import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_INPUT_MONITOR;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_SUPPORTS_WINDOW_CORNERS;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_SYSUI_PROXY;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_WINDOW_CORNER_RADIUS;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BOUNCER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_TRACING_ENABLED;

import android.annotation.FloatRange;
import android.app.ActivityTaskManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.util.ScreenshotHelper;
import com.android.systemui.Dumpable;
import com.android.systemui.model.SysUiState;
import com.android.systemui.pip.PipAnimationController;
import com.android.systemui.pip.PipUI;
import com.android.systemui.recents.OverviewProxyService.OverviewProxyListener;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.recents.IPinnedStackAnimationListener;
import com.android.systemui.shared.recents.ISystemUiProxy;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.phone.NavigationBarFragment;
import com.android.systemui.statusbar.phone.NavigationBarView;
import com.android.systemui.statusbar.phone.NavigationModeController;
import com.android.systemui.statusbar.phone.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarWindowCallback;
import com.android.systemui.statusbar.policy.CallbackController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

/**
 * Class to send information from overview to launcher with a binder.
 */
@Singleton
public class OverviewProxyService implements CallbackController<OverviewProxyListener>,
        NavigationModeController.ModeChangedListener, Dumpable {

    private static final String ACTION_QUICKSTEP = "android.intent.action.QUICKSTEP_SERVICE";

    public static final String TAG_OPS = "OverviewProxyService";
    private static final long BACKOFF_MILLIS = 1000;
    private static final long DEFERRED_CALLBACK_MILLIS = 5000;

    // Max backoff caps at 5 mins
    private static final long MAX_BACKOFF_MILLIS = 10 * 60 * 1000;

    private final Context mContext;
    private final PipUI mPipUI;
    private final Optional<Lazy<StatusBar>> mStatusBarOptionalLazy;
    private final Optional<Divider> mDividerOptional;
    private SysUiState mSysUiState;
    private final Handler mHandler;
    private final NavigationBarController mNavBarController;
    private final NotificationShadeWindowController mStatusBarWinController;
    private final Runnable mConnectionRunnable = this::internalConnectToCurrentUser;
    private final ComponentName mRecentsComponentName;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final List<OverviewProxyListener> mConnectionCallbacks = new ArrayList<>();
    private final Intent mQuickStepIntent;
    private final ScreenshotHelper mScreenshotHelper;

    private Region mActiveNavBarRegion;

    private IOverviewProxy mOverviewProxy;
    private int mConnectionBackoffAttempts;
    private boolean mBound;
    private boolean mIsEnabled;
    private int mCurrentBoundedUserId = -1;
    private float mNavBarButtonAlpha;
    private boolean mInputFocusTransferStarted;
    private float mInputFocusTransferStartY;
    private long mInputFocusTransferStartMillis;
    private float mWindowCornerRadius;
    private boolean mSupportsRoundedCornersOnWindows;
    private int mNavBarMode = NAV_BAR_MODE_3BUTTON;

    private ISystemUiProxy mSysUiProxy = new ISystemUiProxy.Stub() {

        @Override
        public void startScreenPinning(int taskId) {
            if (!verifyCaller("startScreenPinning")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mHandler.post(() -> {
                    mStatusBarOptionalLazy.ifPresent(
                            statusBarLazy -> statusBarLazy.get().showScreenPinningRequest(taskId,
                                    false /* allowCancel */));
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void stopScreenPinning() {
            if (!verifyCaller("stopScreenPinning")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mHandler.post(() -> {
                    try {
                        ActivityTaskManager.getService().stopSystemLockTaskMode();
                    } catch (RemoteException e) {
                        Log.e(TAG_OPS, "Failed to stop screen pinning");
                    }
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        // TODO: change the method signature to use (boolean inputFocusTransferStarted)
        @Override
        public void onStatusBarMotionEvent(MotionEvent event) {
            if (!verifyCaller("onStatusBarMotionEvent")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                // TODO move this logic to message queue
                mStatusBarOptionalLazy.ifPresent(statusBarLazy -> {
                    mHandler.post(()-> {
                        StatusBar statusBar = statusBarLazy.get();
                        int action = event.getActionMasked();
                        if (action == ACTION_DOWN) {
                            mInputFocusTransferStarted = true;
                            mInputFocusTransferStartY = event.getY();
                            mInputFocusTransferStartMillis = event.getEventTime();
                            statusBar.onInputFocusTransfer(
                                    mInputFocusTransferStarted, 0 /* velocity */);
                        }
                        if (action == ACTION_UP || action == ACTION_CANCEL) {
                            mInputFocusTransferStarted = false;
                            statusBar.onInputFocusTransfer(mInputFocusTransferStarted,
                                    (event.getY() - mInputFocusTransferStartY)
                                    / (event.getEventTime() - mInputFocusTransferStartMillis));
                        }
                        event.recycle();
                    });
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void onSplitScreenInvoked() {
            if (!verifyCaller("onSplitScreenInvoked")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mDividerOptional.ifPresent(Divider::onDockedFirstAnimationFrame);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void onOverviewShown(boolean fromHome) {
            if (!verifyCaller("onOverviewShown")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mHandler.post(() -> {
                    for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
                        mConnectionCallbacks.get(i).onOverviewShown(fromHome);
                    }
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public Rect getNonMinimizedSplitScreenSecondaryBounds() {
            if (!verifyCaller("getNonMinimizedSplitScreenSecondaryBounds")) {
                return null;
            }
            long token = Binder.clearCallingIdentity();
            try {
                return mDividerOptional.map(
                        divider -> divider.getView().getNonMinimizedSplitScreenSecondaryBounds())
                        .orElse(null);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setNavBarButtonAlpha(float alpha, boolean animate) {
            if (!verifyCaller("setNavBarButtonAlpha")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mNavBarButtonAlpha = alpha;
                mHandler.post(() -> notifyNavBarButtonAlphaChanged(alpha, animate));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setBackButtonAlpha(float alpha, boolean animate) {
            setNavBarButtonAlpha(alpha, animate);
        }

        @Override
        public void onAssistantProgress(@FloatRange(from = 0.0, to = 1.0) float progress) {
            if (!verifyCaller("onAssistantProgress")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mHandler.post(() -> notifyAssistantProgress(progress));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void onAssistantGestureCompletion(float velocity) {
            if (!verifyCaller("onAssistantGestureCompletion")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mHandler.post(() -> notifyAssistantGestureCompletion(velocity));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void startAssistant(Bundle bundle) {
            if (!verifyCaller("startAssistant")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mHandler.post(() -> notifyStartAssistant(bundle));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public Bundle monitorGestureInput(String name, int displayId) {
            if (!verifyCaller("monitorGestureInput")) {
                return null;
            }
            long token = Binder.clearCallingIdentity();
            try {
                InputMonitor monitor =
                        InputManager.getInstance().monitorGestureInput(name, displayId);
                Bundle result = new Bundle();
                result.putParcelable(KEY_EXTRA_INPUT_MONITOR, monitor);
                return result;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyAccessibilityButtonClicked(int displayId) {
            if (!verifyCaller("notifyAccessibilityButtonClicked")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                AccessibilityManager.getInstance(mContext)
                        .notifyAccessibilityButtonClicked(displayId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void notifyAccessibilityButtonLongClicked() {
            if (!verifyCaller("notifyAccessibilityButtonLongClicked")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                Intent intent = new Intent(AccessibilityManager.ACTION_CHOOSE_ACCESSIBILITY_BUTTON);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                intent.putExtra(AccessibilityManager.EXTRA_SHORTCUT_TYPE,
                        AccessibilityManager.ACCESSIBILITY_BUTTON);
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setShelfHeight(boolean visible, int shelfHeight) {
            if (!verifyCaller("setShelfHeight")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mPipUI.setShelfHeight(visible, shelfHeight);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void handleImageAsScreenshot(Bitmap screenImage, Rect locationInScreen,
                Insets visibleInsets, int taskId) {
            mScreenshotHelper.provideScreenshot(screenImage, locationInScreen, visibleInsets,
                    taskId, SCREENSHOT_OVERVIEW, mHandler, null);
        }

        @Override
        public void setSplitScreenMinimized(boolean minimized) {
            Divider divider = mDividerOptional.get();
            if (divider != null) {
                divider.setMinimized(minimized);
            }
        }

        @Override
        public void notifySwipeToHomeFinished() {
            if (!verifyCaller("notifySwipeToHomeFinished")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mPipUI.setPinnedStackAnimationType(PipAnimationController.ANIM_TYPE_ALPHA);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setPinnedStackAnimationListener(IPinnedStackAnimationListener listener) {
            if (!verifyCaller("setPinnedStackAnimationListener")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mPipUI.setPinnedStackAnimationListener(listener);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void onQuickSwitchToNewTask(@Surface.Rotation int rotation) {
            if (!verifyCaller("onQuickSwitchToNewTask")) {
                return;
            }
            long token = Binder.clearCallingIdentity();
            try {
                mHandler.post(() -> notifyQuickSwitchToNewTask(rotation));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private boolean verifyCaller(String reason) {
            final int callerId = Binder.getCallingUserHandle().getIdentifier();
            if (callerId != mCurrentBoundedUserId) {
                Log.w(TAG_OPS, "Launcher called sysui with invalid user: " + callerId + ", reason: "
                        + reason);
                return false;
            }
            return true;
        }
    };

    private final Runnable mDeferredConnectionCallback = () -> {
        Log.w(TAG_OPS, "Binder supposed established connection but actual connection to service "
            + "timed out, trying again");
        retryConnectionWithBackoff();
    };

    private final BroadcastReceiver mLauncherStateChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateEnabledState();

            // Reconnect immediately, instead of waiting for resume to arrive.
            startConnectionToCurrentUser();
        }
    };

    private final ServiceConnection mOverviewServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (SysUiState.DEBUG) {
                Log.d(TAG_OPS, "Overview proxy service connected");
            }
            mConnectionBackoffAttempts = 0;
            mHandler.removeCallbacks(mDeferredConnectionCallback);
            try {
                service.linkToDeath(mOverviewServiceDeathRcpt, 0);
            } catch (RemoteException e) {
                // Failed to link to death (process may have died between binding and connecting),
                // just unbind the service for now and retry again
                Log.e(TAG_OPS, "Lost connection to launcher service", e);
                disconnectFromLauncherService();
                retryConnectionWithBackoff();
                return;
            }

            mCurrentBoundedUserId = mDeviceProvisionedController.getCurrentUser();
            mOverviewProxy = IOverviewProxy.Stub.asInterface(service);

            Bundle params = new Bundle();
            params.putBinder(KEY_EXTRA_SYSUI_PROXY, mSysUiProxy.asBinder());
            params.putFloat(KEY_EXTRA_WINDOW_CORNER_RADIUS, mWindowCornerRadius);
            params.putBoolean(KEY_EXTRA_SUPPORTS_WINDOW_CORNERS, mSupportsRoundedCornersOnWindows);
            try {
                mOverviewProxy.onInitialize(params);
            } catch (RemoteException e) {
                mCurrentBoundedUserId = -1;
                Log.e(TAG_OPS, "Failed to call onInitialize()", e);
            }
            dispatchNavButtonBounds();

            // Update the systemui state flags
            updateSystemUiStateFlags();

            notifyConnectionChanged();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.w(TAG_OPS, "Null binding of '" + name + "', try reconnecting");
            mCurrentBoundedUserId = -1;
            retryConnectionWithBackoff();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG_OPS, "Binding died of '" + name + "', try reconnecting");
            mCurrentBoundedUserId = -1;
            retryConnectionWithBackoff();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Do nothing
            mCurrentBoundedUserId = -1;
        }
    };

    private final DeviceProvisionedListener mDeviceProvisionedCallback =
                new DeviceProvisionedListener() {
        @Override
        public void onUserSetupChanged() {
            if (mDeviceProvisionedController.isCurrentUserSetup()) {
                internalConnectToCurrentUser();
            }
        }

        @Override
        public void onUserSwitched() {
            mConnectionBackoffAttempts = 0;
            internalConnectToCurrentUser();
        }
    };

    private final StatusBarWindowCallback mStatusBarWindowCallback = this::onStatusBarStateChanged;

    // This is the death handler for the binder from the launcher service
    private final IBinder.DeathRecipient mOverviewServiceDeathRcpt
            = this::cleanupAfterDeath;

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Inject
    public OverviewProxyService(Context context, CommandQueue commandQueue,
            DeviceProvisionedController provisionController,
            NavigationBarController navBarController, NavigationModeController navModeController,
            NotificationShadeWindowController statusBarWinController, SysUiState sysUiState,
            PipUI pipUI, Optional<Divider> dividerOptional,
            Optional<Lazy<StatusBar>> statusBarOptionalLazy) {
        mContext = context;
        mPipUI = pipUI;
        mStatusBarOptionalLazy = statusBarOptionalLazy;
        mHandler = new Handler();
        mNavBarController = navBarController;
        mStatusBarWinController = statusBarWinController;
        mDeviceProvisionedController = provisionController;
        mConnectionBackoffAttempts = 0;
        mDividerOptional = dividerOptional;
        mRecentsComponentName = ComponentName.unflattenFromString(context.getString(
                com.android.internal.R.string.config_recentsComponentName));
        mQuickStepIntent = new Intent(ACTION_QUICKSTEP)
                .setPackage(mRecentsComponentName.getPackageName());
        mWindowCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(mContext.getResources());
        mSupportsRoundedCornersOnWindows = ScreenDecorationsUtils
                .supportsRoundedCornersOnWindows(mContext.getResources());
        mSysUiState = sysUiState;
        mSysUiState.addCallback(this::notifySystemUiStateFlags);

        // Assumes device always starts with back button until launcher tells it that it does not
        mNavBarButtonAlpha = 1.0f;

        // Listen for nav bar mode changes
        mNavBarMode = navModeController.addListener(this);

        // Listen for device provisioned/user setup
        updateEnabledState();
        mDeviceProvisionedController.addCallback(mDeviceProvisionedCallback);

        // Listen for launcher package changes
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart(mRecentsComponentName.getPackageName(),
                PatternMatcher.PATTERN_LITERAL);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        mContext.registerReceiver(mLauncherStateChangedReceiver, filter);

        // Listen for status bar state changes
        statusBarWinController.registerCallback(mStatusBarWindowCallback);
        mScreenshotHelper = new ScreenshotHelper(context);

        // Listen for tracing state changes
        commandQueue.addCallback(new CommandQueue.Callbacks() {
            @Override
            public void onTracingStateChanged(boolean enabled) {
                mSysUiState.setFlag(SYSUI_STATE_TRACING_ENABLED, enabled)
                        .commitUpdate(mContext.getDisplayId());
            }
        });
    }

    public void notifyBackAction(boolean completed, int downX, int downY, boolean isButton,
            boolean gestureSwipeLeft) {
        try {
            if (mOverviewProxy != null) {
                mOverviewProxy.onBackAction(completed, downX, downY, isButton, gestureSwipeLeft);
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to notify back action", e);
        }
    }

    private void updateSystemUiStateFlags() {
        final NavigationBarFragment navBarFragment =
                mNavBarController.getDefaultNavigationBarFragment();
        final NavigationBarView navBarView =
                mNavBarController.getNavigationBarView(mContext.getDisplayId());
        if (SysUiState.DEBUG) {
            Log.d(TAG_OPS, "Updating sysui state flags: navBarFragment=" + navBarFragment
                    + " navBarView=" + navBarView);
        }

        if (navBarFragment != null) {
            navBarFragment.updateSystemUiStateFlags(-1);
        }
        if (navBarView != null) {
            navBarView.updatePanelSystemUiStateFlags();
            navBarView.updateDisabledSystemUiStateFlags();
        }
        if (mStatusBarWinController != null) {
            mStatusBarWinController.notifyStateChangedCallbacks();
        }
    }

    private void notifySystemUiStateFlags(int flags) {
        if (SysUiState.DEBUG) {
            Log.d(TAG_OPS, "Notifying sysui state change to overview service: proxy="
                    + mOverviewProxy + " flags=" + flags);
        }
        try {
            if (mOverviewProxy != null) {
                mOverviewProxy.onSystemUiStateChanged(flags);
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to notify sysui state change", e);
        }
    }

    private void onStatusBarStateChanged(boolean keyguardShowing, boolean keyguardOccluded,
            boolean bouncerShowing) {
        mSysUiState.setFlag(SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING,
                        keyguardShowing && !keyguardOccluded)
                .setFlag(SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED,
                        keyguardShowing && keyguardOccluded)
                .setFlag(SYSUI_STATE_BOUNCER_SHOWING, bouncerShowing)
                .commitUpdate(mContext.getDisplayId());
    }

    /**
     * Sets the navbar region which can receive touch inputs
     */
    public void onActiveNavBarRegionChanges(Region activeRegion) {
        mActiveNavBarRegion = activeRegion;
        dispatchNavButtonBounds();
    }

    private void dispatchNavButtonBounds() {
        if (mOverviewProxy != null && mActiveNavBarRegion != null) {
            try {
                mOverviewProxy.onActiveNavBarRegionChanges(mActiveNavBarRegion);
            } catch (RemoteException e) {
                Log.e(TAG_OPS, "Failed to call onActiveNavBarRegionChanges()", e);
            }
        }
    }

    public void cleanupAfterDeath() {
        if (mInputFocusTransferStarted) {
            mHandler.post(()-> {
                mStatusBarOptionalLazy.ifPresent(statusBarLazy -> {
                    mInputFocusTransferStarted = false;
                    statusBarLazy.get().onInputFocusTransfer(false, 0 /* velocity */);
                });
            });
        }
        startConnectionToCurrentUser();

        // Clean up the minimized state if launcher dies
        Divider divider = mDividerOptional.get();
        if (divider != null) {
            divider.setMinimized(false);
        }
    }

    public void startConnectionToCurrentUser() {
        if (mHandler.getLooper() != Looper.myLooper()) {
            mHandler.post(mConnectionRunnable);
        } else {
            internalConnectToCurrentUser();
        }
    }

    private void internalConnectToCurrentUser() {
        disconnectFromLauncherService();

        // If user has not setup yet or already connected, do not try to connect
        if (!mDeviceProvisionedController.isCurrentUserSetup() || !isEnabled()) {
            Log.v(TAG_OPS, "Cannot attempt connection, is setup "
                + mDeviceProvisionedController.isCurrentUserSetup() + ", is enabled "
                + isEnabled());
            return;
        }
        mHandler.removeCallbacks(mConnectionRunnable);
        Intent launcherServiceIntent = new Intent(ACTION_QUICKSTEP)
                .setPackage(mRecentsComponentName.getPackageName());
        try {
            mBound = mContext.bindServiceAsUser(launcherServiceIntent,
                    mOverviewServiceConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
                    UserHandle.of(mDeviceProvisionedController.getCurrentUser()));
        } catch (SecurityException e) {
            Log.e(TAG_OPS, "Unable to bind because of security error", e);
        }
        if (mBound) {
            // Ensure that connection has been established even if it thinks it is bound
            mHandler.postDelayed(mDeferredConnectionCallback, DEFERRED_CALLBACK_MILLIS);
        } else {
            // Retry after exponential backoff timeout
            retryConnectionWithBackoff();
        }
    }

    private void retryConnectionWithBackoff() {
        if (mHandler.hasCallbacks(mConnectionRunnable)) {
            return;
        }
        final long timeoutMs = (long) Math.min(
                Math.scalb(BACKOFF_MILLIS, mConnectionBackoffAttempts), MAX_BACKOFF_MILLIS);
        mHandler.postDelayed(mConnectionRunnable, timeoutMs);
        mConnectionBackoffAttempts++;
        Log.w(TAG_OPS, "Failed to connect on attempt " + mConnectionBackoffAttempts
                + " will try again in " + timeoutMs + "ms");
    }

    @Override
    public void addCallback(OverviewProxyListener listener) {
        mConnectionCallbacks.add(listener);
        listener.onConnectionChanged(mOverviewProxy != null);
        listener.onNavBarButtonAlphaChanged(mNavBarButtonAlpha, false);
    }

    @Override
    public void removeCallback(OverviewProxyListener listener) {
        mConnectionCallbacks.remove(listener);
    }

    public boolean shouldShowSwipeUpUI() {
        return isEnabled() && !QuickStepContract.isLegacyMode(mNavBarMode);
    }

    public boolean isEnabled() {
        return mIsEnabled;
    }

    public IOverviewProxy getProxy() {
        return mOverviewProxy;
    }

    private void disconnectFromLauncherService() {
        if (mBound) {
            // Always unbind the service (ie. if called through onNullBinding or onBindingDied)
            mContext.unbindService(mOverviewServiceConnection);
            mBound = false;
        }

        if (mOverviewProxy != null) {
            mOverviewProxy.asBinder().unlinkToDeath(mOverviewServiceDeathRcpt, 0);
            mOverviewProxy = null;
            notifyNavBarButtonAlphaChanged(1f, false /* animate */);
            notifyConnectionChanged();
        }
    }

    private void notifyNavBarButtonAlphaChanged(float alpha, boolean animate) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onNavBarButtonAlphaChanged(alpha, animate);
        }
    }

    private void notifyConnectionChanged() {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onConnectionChanged(mOverviewProxy != null);
        }
    }

    public void notifyQuickStepStarted() {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onQuickStepStarted();
        }
    }

    private void notifyQuickSwitchToNewTask(@Surface.Rotation int rotation) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onQuickSwitchToNewTask(rotation);
        }
    }

    public void notifyQuickScrubStarted() {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onQuickScrubStarted();
        }
    }

    private void notifyAssistantProgress(@FloatRange(from = 0.0, to = 1.0) float progress) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onAssistantProgress(progress);
        }
    }

    private void notifyAssistantGestureCompletion(float velocity) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onAssistantGestureCompletion(velocity);
        }
    }

    private void notifyStartAssistant(Bundle bundle) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).startAssistant(bundle);
        }
    }

    public void notifyAssistantVisibilityChanged(float visibility) {
        try {
            if (mOverviewProxy != null) {
                mOverviewProxy.onAssistantVisibilityChanged(visibility);
            } else {
                Log.e(TAG_OPS, "Failed to get overview proxy for assistant visibility.");
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to call onAssistantVisibilityChanged()", e);
        }
    }

    private void updateEnabledState() {
        mIsEnabled = mContext.getPackageManager().resolveServiceAsUser(mQuickStepIntent,
                MATCH_SYSTEM_ONLY,
                ActivityManagerWrapper.getInstance().getCurrentUserId()) != null;
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mNavBarMode = mode;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(TAG_OPS + " state:");
        pw.print("  recentsComponentName="); pw.println(mRecentsComponentName);
        pw.print("  isConnected="); pw.println(mOverviewProxy != null);
        pw.print("  isCurrentUserSetup="); pw.println(mDeviceProvisionedController
                .isCurrentUserSetup());
        pw.print("  connectionBackoffAttempts="); pw.println(mConnectionBackoffAttempts);

        pw.print("  quickStepIntent="); pw.println(mQuickStepIntent);
        pw.print("  quickStepIntentResolved="); pw.println(isEnabled());
        mSysUiState.dump(fd, pw, args);
        pw.print(" mInputFocusTransferStarted="); pw.println(mInputFocusTransferStarted);
    }

    public interface OverviewProxyListener {
        default void onConnectionChanged(boolean isConnected) {}
        default void onQuickStepStarted() {}
        default void onQuickSwitchToNewTask(@Surface.Rotation int rotation) {}
        default void onOverviewShown(boolean fromHome) {}
        default void onQuickScrubStarted() {}
        /** Notify changes in the nav bar button alpha */
        default void onNavBarButtonAlphaChanged(float alpha, boolean animate) {}
        default void onSystemUiStateChanged(int sysuiStateFlags) {}
        default void onAssistantProgress(@FloatRange(from = 0.0, to = 1.0) float progress) {}
        default void onAssistantGestureCompletion(float velocity) {}
        default void startAssistant(Bundle bundle) {}
    }
}
