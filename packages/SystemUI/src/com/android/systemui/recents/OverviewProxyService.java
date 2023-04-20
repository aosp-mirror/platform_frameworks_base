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
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_OVERVIEW;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;

import static com.android.internal.accessibility.common.ShortcutConstants.CHOOSER_PACKAGE_NAME;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_SUPPORTS_WINDOW_CORNERS;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_SYSUI_PROXY;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_UNLOCK_ANIMATION_CONTROLLER;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_WINDOW_CORNER_RADIUS;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BOUNCER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_DEVICE_DOZING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_TRACING_ENABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_VOICE_INTERACTION_WINDOW_SHOWING;

import android.annotation.FloatRange;
import android.app.ActivityTaskManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
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
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;

import com.android.internal.accessibility.dialog.AccessibilityButtonChooserActivity;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.AssistUtils;
import com.android.internal.app.IVoiceInteractionSessionListener;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.util.ScreenshotHelper;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationBar;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationBarView;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.navigationbar.buttons.KeyButtonView;
import com.android.systemui.recents.OverviewProxyService.OverviewProxyListener;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.NotificationPanelViewController;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.recents.ISystemUiProxy;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.StatusBarWindowCallback;
import com.android.systemui.statusbar.policy.CallbackController;
import com.android.wm.shell.sysui.ShellInterface;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * Class to send information from overview to launcher with a binder.
 */
@SysUISingleton
public class OverviewProxyService implements CallbackController<OverviewProxyListener>,
        NavigationModeController.ModeChangedListener, Dumpable {

    private static final String ACTION_QUICKSTEP = "android.intent.action.QUICKSTEP_SERVICE";

    public static final String TAG_OPS = "OverviewProxyService";
    private static final long BACKOFF_MILLIS = 1000;
    private static final long DEFERRED_CALLBACK_MILLIS = 5000;

    // Max backoff caps at 5 mins
    private static final long MAX_BACKOFF_MILLIS = 10 * 60 * 1000;

    private final Context mContext;
    private final Executor mMainExecutor;
    private final ShellInterface mShellInterface;
    private final Lazy<Optional<CentralSurfaces>> mCentralSurfacesOptionalLazy;
    private SysUiState mSysUiState;
    private final Handler mHandler;
    private final Lazy<NavigationBarController> mNavBarControllerLazy;
    private final NotificationShadeWindowController mStatusBarWinController;
    private final Runnable mConnectionRunnable = this::internalConnectToCurrentUser;
    private final ComponentName mRecentsComponentName;
    private final List<OverviewProxyListener> mConnectionCallbacks = new ArrayList<>();
    private final Intent mQuickStepIntent;
    private final ScreenshotHelper mScreenshotHelper;
    private final CommandQueue mCommandQueue;
    private final UserTracker mUserTracker;
    private final KeyguardUnlockAnimationController mSysuiUnlockAnimationController;
    private final UiEventLogger mUiEventLogger;

    private Region mActiveNavBarRegion;
    private SurfaceControl mNavigationBarSurface;

    private IOverviewProxy mOverviewProxy;
    private int mConnectionBackoffAttempts;
    private boolean mBound;
    private boolean mIsEnabled;
    private int mCurrentBoundedUserId = -1;
    private boolean mInputFocusTransferStarted;
    private float mInputFocusTransferStartY;
    private long mInputFocusTransferStartMillis;
    private float mWindowCornerRadius;
    private boolean mSupportsRoundedCornersOnWindows;
    private int mNavBarMode = NAV_BAR_MODE_3BUTTON;

    @VisibleForTesting
    public ISystemUiProxy mSysUiProxy = new ISystemUiProxy.Stub() {
        @Override
        public void startScreenPinning(int taskId) {
            verifyCallerAndClearCallingIdentityPostMain("startScreenPinning", () ->
                    mCentralSurfacesOptionalLazy.get().ifPresent(
                            statusBar -> statusBar.showScreenPinningRequest(taskId,
                                    false /* allowCancel */)));
        }

        @Override
        public void stopScreenPinning() {
            verifyCallerAndClearCallingIdentityPostMain("stopScreenPinning", () -> {
                try {
                    ActivityTaskManager.getService().stopSystemLockTaskMode();
                } catch (RemoteException e) {
                    Log.e(TAG_OPS, "Failed to stop screen pinning");
                }
            });
        }

        // TODO: change the method signature to use (boolean inputFocusTransferStarted)
        @Override
        public void onStatusBarMotionEvent(MotionEvent event) {
            verifyCallerAndClearCallingIdentity("onStatusBarMotionEvent", () -> {
                // TODO move this logic to message queue
                mCentralSurfacesOptionalLazy.get().ifPresent(centralSurfaces -> {
                    if (event.getActionMasked() == ACTION_DOWN) {
                        centralSurfaces.getNotificationPanelViewController()
                                        .startExpandLatencyTracking();
                    }
                    mHandler.post(() -> {
                        int action = event.getActionMasked();
                        if (action == ACTION_DOWN) {
                            mInputFocusTransferStarted = true;
                            mInputFocusTransferStartY = event.getY();
                            mInputFocusTransferStartMillis = event.getEventTime();
                            centralSurfaces.onInputFocusTransfer(
                                    mInputFocusTransferStarted, false /* cancel */,
                                    0 /* velocity */);
                        }
                        if (action == ACTION_UP || action == ACTION_CANCEL) {
                            mInputFocusTransferStarted = false;
                            float velocity = (event.getY() - mInputFocusTransferStartY)
                                    / (event.getEventTime() - mInputFocusTransferStartMillis);
                            centralSurfaces.onInputFocusTransfer(mInputFocusTransferStarted,
                                    action == ACTION_CANCEL,
                                    velocity);
                        }
                        event.recycle();
                    });
                });
            });
        }

        @Override
        public void onBackPressed() {
            verifyCallerAndClearCallingIdentityPostMain("onBackPressed", () -> {
                sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
                sendEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK);
            });
        }

        @Override
        public void onImeSwitcherPressed() {
            // TODO(b/204901476) We're intentionally using DEFAULT_DISPLAY for now since
            // Launcher/Taskbar isn't display aware.
            mContext.getSystemService(InputMethodManager.class)
                    .showInputMethodPickerFromSystem(true /* showAuxiliarySubtypes */,
                            DEFAULT_DISPLAY);
            mUiEventLogger.log(KeyButtonView.NavBarButtonEvent.NAVBAR_IME_SWITCHER_BUTTON_TAP);
        }

        @Override
        public void setHomeRotationEnabled(boolean enabled) {
            verifyCallerAndClearCallingIdentityPostMain("setHomeRotationEnabled", () ->
                    mHandler.post(() -> notifyHomeRotationEnabled(enabled)));
        }

        @Override
        public void notifyTaskbarStatus(boolean visible, boolean stashed) {
            verifyCallerAndClearCallingIdentityPostMain("notifyTaskbarStatus", () ->
                    onTaskbarStatusUpdated(visible, stashed));
        }

        @Override
        public void notifyTaskbarAutohideSuspend(boolean suspend) {
            verifyCallerAndClearCallingIdentityPostMain("notifyTaskbarAutohideSuspend", () ->
                    onTaskbarAutohideSuspend(suspend));
        }

        private boolean sendEvent(int action, int code) {
            long when = SystemClock.uptimeMillis();
            final KeyEvent ev = new KeyEvent(when, when, action, code, 0 /* repeat */,
                    0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                    KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD);

            ev.setDisplayId(mContext.getDisplay().getDisplayId());
            return InputManager.getInstance()
                    .injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        }

        @Override
        public void onOverviewShown(boolean fromHome) {
            verifyCallerAndClearCallingIdentityPostMain("onOverviewShown", () -> {
                for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
                    mConnectionCallbacks.get(i).onOverviewShown(fromHome);
                }
            });
        }

        @Override
        public void onAssistantProgress(@FloatRange(from = 0.0, to = 1.0) float progress) {
            verifyCallerAndClearCallingIdentityPostMain("onAssistantProgress", () ->
                    notifyAssistantProgress(progress));
        }

        @Override
        public void onAssistantGestureCompletion(float velocity) {
            verifyCallerAndClearCallingIdentityPostMain("onAssistantGestureCompletion", () ->
                    notifyAssistantGestureCompletion(velocity));
        }

        @Override
        public void startAssistant(Bundle bundle) {
            verifyCallerAndClearCallingIdentityPostMain("startAssistant", () ->
                    notifyStartAssistant(bundle));
        }

        @Override
        public void notifyAccessibilityButtonClicked(int displayId) {
            verifyCallerAndClearCallingIdentity("notifyAccessibilityButtonClicked", () ->
                    AccessibilityManager.getInstance(mContext)
                            .notifyAccessibilityButtonClicked(displayId));
        }

        @Override
        public void notifyAccessibilityButtonLongClicked() {
            verifyCallerAndClearCallingIdentity("notifyAccessibilityButtonLongClicked",
                    () -> {
                        final Intent intent =
                                new Intent(AccessibilityManager.ACTION_CHOOSE_ACCESSIBILITY_BUTTON);
                        final String chooserClassName = AccessibilityButtonChooserActivity
                                .class.getName();
                        intent.setClassName(CHOOSER_PACKAGE_NAME, chooserClassName);
                        intent.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                    });
        }

        @Override
        public void notifyPrioritizedRotation(@Surface.Rotation int rotation) {
            verifyCallerAndClearCallingIdentityPostMain("notifyPrioritizedRotation", () ->
                    notifyPrioritizedRotationInternal(rotation));
        }

        @Override
        public void handleImageBundleAsScreenshot(Bundle screenImageBundle, Rect locationInScreen,
                Insets visibleInsets, Task.TaskKey task) {
            mScreenshotHelper.provideScreenshot(
                    screenImageBundle,
                    locationInScreen,
                    visibleInsets,
                    task.id,
                    task.userId,
                    task.sourceComponent,
                    SCREENSHOT_OVERVIEW,
                    mHandler,
                    null);
        }

        @Override
        public void expandNotificationPanel() {
            verifyCallerAndClearCallingIdentity("expandNotificationPanel",
                    () -> mCommandQueue.handleSystemKey(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN));
        }

        @Override
        public void toggleNotificationPanel() {
            verifyCallerAndClearCallingIdentityPostMain("toggleNotificationPanel", () ->
                    mCentralSurfacesOptionalLazy.get().ifPresent(CentralSurfaces::togglePanel));
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

        private <T> T verifyCallerAndClearCallingIdentity(String reason, Supplier<T> supplier) {
            if (!verifyCaller(reason)) {
                return null;
            }
            final long token = Binder.clearCallingIdentity();
            try {
                return supplier.get();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void verifyCallerAndClearCallingIdentity(String reason, Runnable runnable) {
            verifyCallerAndClearCallingIdentity(reason, () -> {
                runnable.run();
                return null;
            });
        }

        private void verifyCallerAndClearCallingIdentityPostMain(String reason, Runnable runnable) {
            verifyCallerAndClearCallingIdentity(reason, () -> mHandler.post(runnable));
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

            mCurrentBoundedUserId = mUserTracker.getUserId();
            mOverviewProxy = IOverviewProxy.Stub.asInterface(service);

            Bundle params = new Bundle();
            params.putBinder(KEY_EXTRA_SYSUI_PROXY, mSysUiProxy.asBinder());
            params.putFloat(KEY_EXTRA_WINDOW_CORNER_RADIUS, mWindowCornerRadius);
            params.putBoolean(KEY_EXTRA_SUPPORTS_WINDOW_CORNERS, mSupportsRoundedCornersOnWindows);
            params.putBinder(KEY_EXTRA_UNLOCK_ANIMATION_CONTROLLER,
                    mSysuiUnlockAnimationController.asBinder());
            // Add all the interfaces exposed by the shell
            mShellInterface.createExternalInterfaces(params);

            try {
                Log.d(TAG_OPS, "OverviewProxyService connected, initializing overview proxy");
                mOverviewProxy.onInitialize(params);
            } catch (RemoteException e) {
                mCurrentBoundedUserId = -1;
                Log.e(TAG_OPS, "Failed to call onInitialize()", e);
            }
            dispatchNavButtonBounds();
            dispatchNavigationBarSurface();

            // Force-update the systemui state flags
            updateSystemUiStateFlags();
            notifySystemUiStateFlags(mSysUiState.getFlags());

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
            Log.w(TAG_OPS, "Service disconnected");
            // Do nothing
            mCurrentBoundedUserId = -1;
        }
    };

    private final StatusBarWindowCallback mStatusBarWindowCallback = this::onStatusBarStateChanged;

    // This is the death handler for the binder from the launcher service
    private final IBinder.DeathRecipient mOverviewServiceDeathRcpt
            = this::cleanupAfterDeath;

    private final IVoiceInteractionSessionListener mVoiceInteractionSessionListener =
            new IVoiceInteractionSessionListener.Stub() {
        @Override
        public void onVoiceSessionShown() {
            // Do nothing
        }

        @Override
        public void onVoiceSessionHidden() {
            // Do nothing
        }

        @Override
        public void onVoiceSessionWindowVisibilityChanged(boolean visible) {
            mContext.getMainExecutor().execute(() ->
                    OverviewProxyService.this.onVoiceSessionWindowVisibilityChanged(visible));
        }

        @Override
        public void onSetUiHints(Bundle hints) {
            // Do nothing
        }
    };

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    mConnectionBackoffAttempts = 0;
                    internalConnectToCurrentUser();
                }
            };

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Inject
    public OverviewProxyService(Context context,
            @Main Executor mainExecutor,
            CommandQueue commandQueue,
            ShellInterface shellInterface,
            Lazy<NavigationBarController> navBarControllerLazy,
            Lazy<Optional<CentralSurfaces>> centralSurfacesOptionalLazy,
            NavigationModeController navModeController,
            NotificationShadeWindowController statusBarWinController, SysUiState sysUiState,
            UserTracker userTracker,
            ScreenLifecycle screenLifecycle,
            UiEventLogger uiEventLogger,
            KeyguardUnlockAnimationController sysuiUnlockAnimationController,
            AssistUtils assistUtils,
            DumpManager dumpManager) {
        // b/241601880: This component shouldn't be running for a non-primary user
        if (!Process.myUserHandle().equals(UserHandle.SYSTEM)) {
            Log.e(TAG_OPS, "Unexpected initialization for non-primary user", new Throwable());
        }

        mContext = context;
        mMainExecutor = mainExecutor;
        mShellInterface = shellInterface;
        mCentralSurfacesOptionalLazy = centralSurfacesOptionalLazy;
        mHandler = new Handler();
        mNavBarControllerLazy = navBarControllerLazy;
        mStatusBarWinController = statusBarWinController;
        mUserTracker = userTracker;
        mConnectionBackoffAttempts = 0;
        mRecentsComponentName = ComponentName.unflattenFromString(context.getString(
                com.android.internal.R.string.config_recentsComponentName));
        mQuickStepIntent = new Intent(ACTION_QUICKSTEP)
                .setPackage(mRecentsComponentName.getPackageName());
        mWindowCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(mContext);
        mSupportsRoundedCornersOnWindows = ScreenDecorationsUtils
                .supportsRoundedCornersOnWindows(mContext.getResources());
        mSysUiState = sysUiState;
        mSysUiState.addCallback(this::notifySystemUiStateFlags);
        mUiEventLogger = uiEventLogger;

        dumpManager.registerDumpable(getClass().getSimpleName(), this);

        // Listen for nav bar mode changes
        mNavBarMode = navModeController.addListener(this);

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

        commandQueue.addCallback(new CommandQueue.Callbacks() {

            // Listen for tracing state changes
            @Override
            public void onTracingStateChanged(boolean enabled) {
                mSysUiState.setFlag(SYSUI_STATE_TRACING_ENABLED, enabled)
                        .commitUpdate(mContext.getDisplayId());
            }

            @Override
            public void enterStageSplitFromRunningApp(boolean leftOrTop) {
                if (mOverviewProxy != null) {
                    try {
                        mOverviewProxy.enterStageSplitFromRunningApp(leftOrTop);
                    } catch (RemoteException e) {
                        Log.w(TAG_OPS, "Unable to enter stage split from the current running app");
                    }
                }
            }
        });
        mCommandQueue = commandQueue;

        // Listen for user setup
        mUserTracker.addCallback(mUserChangedCallback, mMainExecutor);

        screenLifecycle.addObserver(mLifecycleObserver);

        // Connect to the service
        updateEnabledState();
        startConnectionToCurrentUser();
        mSysuiUnlockAnimationController = sysuiUnlockAnimationController;

        // Listen for assistant changes
        assistUtils.registerVoiceInteractionSessionListener(mVoiceInteractionSessionListener);
    }

    public void onVoiceSessionWindowVisibilityChanged(boolean visible) {
        mSysUiState.setFlag(SYSUI_STATE_VOICE_INTERACTION_WINDOW_SHOWING, visible)
                .commitUpdate(mContext.getDisplayId());
    }

    /**
     * Called when the navigation bar surface is created or changed
     */
    public void onNavigationBarSurfaceChanged(SurfaceControl navbarSurface) {
        mNavigationBarSurface = navbarSurface;
        dispatchNavigationBarSurface();
    }

    private void dispatchNavigationBarSurface() {
        try {
            if (mOverviewProxy != null) {
                mOverviewProxy.onNavigationBarSurface(mNavigationBarSurface);
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to notify back action", e);
        }
    }

    private void updateSystemUiStateFlags() {
        final NavigationBar navBarFragment =
                mNavBarControllerLazy.get().getDefaultNavigationBar();
        final NavigationBarView navBarView =
                mNavBarControllerLazy.get().getNavigationBarView(mContext.getDisplayId());
        final NotificationPanelViewController panelController =
                mCentralSurfacesOptionalLazy.get().get().getNotificationPanelViewController();
        if (SysUiState.DEBUG) {
            Log.d(TAG_OPS, "Updating sysui state flags: navBarFragment=" + navBarFragment
                    + " navBarView=" + navBarView + " panelController=" + panelController);
        }

        if (navBarFragment != null) {
            navBarFragment.updateSystemUiStateFlags();
        }
        if (navBarView != null) {
            navBarView.updateDisabledSystemUiStateFlags(mSysUiState);
        }
        if (panelController != null) {
            panelController.updateSystemUiStateFlags();
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
            boolean bouncerShowing, boolean isDozing, boolean panelExpanded) {
        mSysUiState.setFlag(SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING,
                        keyguardShowing && !keyguardOccluded)
                .setFlag(SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED,
                        keyguardShowing && keyguardOccluded)
                .setFlag(SYSUI_STATE_BOUNCER_SHOWING, bouncerShowing)
                .setFlag(SYSUI_STATE_DEVICE_DOZING, isDozing)
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
            mHandler.post(() -> {
                mCentralSurfacesOptionalLazy.get().ifPresent(centralSurfaces -> {
                    mInputFocusTransferStarted = false;
                    centralSurfaces.onInputFocusTransfer(false, true /* cancel */, 0 /* velocity */);
                });
            });
        }
        startConnectionToCurrentUser();
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
        if (!isEnabled()) {
            Log.v(TAG_OPS, "Cannot attempt connection, is enabled " + isEnabled());
            return;
        }
        mHandler.removeCallbacks(mConnectionRunnable);
        Intent launcherServiceIntent = new Intent(ACTION_QUICKSTEP)
                .setPackage(mRecentsComponentName.getPackageName());
        try {
            mBound = mContext.bindServiceAsUser(launcherServiceIntent,
                    mOverviewServiceConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
                    UserHandle.of(mUserTracker.getUserId()));
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
    public void addCallback(@NonNull OverviewProxyListener listener) {
        if (!mConnectionCallbacks.contains(listener)) {
            mConnectionCallbacks.add(listener);
        }
        listener.onConnectionChanged(mOverviewProxy != null);
    }

    @Override
    public void removeCallback(@NonNull OverviewProxyListener listener) {
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
            notifyConnectionChanged();
        }
    }

    private void notifyHomeRotationEnabled(boolean enabled) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onHomeRotationEnabled(enabled);
        }
    }

    private void onTaskbarStatusUpdated(boolean visible, boolean stashed) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onTaskbarStatusUpdated(visible, stashed);
        }
    }

    private void onTaskbarAutohideSuspend(boolean suspend) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onTaskbarAutohideSuspend(suspend);
        }
    }

    private void notifyConnectionChanged() {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onConnectionChanged(mOverviewProxy != null);
        }
    }

    private void notifyPrioritizedRotationInternal(@Surface.Rotation int rotation) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onPrioritizedRotation(rotation);
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
            Log.e(TAG_OPS, "Failed to call notifyAssistantVisibilityChanged()", e);
        }
    }

    private final ScreenLifecycle.Observer mLifecycleObserver = new ScreenLifecycle.Observer() {
        /**
         * Notifies the Launcher that screen turned on and ready to use
         */
        @Override
        public void onScreenTurnedOn() {
            try {
                if (mOverviewProxy != null) {
                    mOverviewProxy.onScreenTurnedOn();
                } else {
                    Log.e(TAG_OPS, "Failed to get overview proxy for screen turned on event.");
                }
            } catch (RemoteException e) {
                Log.e(TAG_OPS, "Failed to call onScreenTurnedOn()", e);
            }
        }

        /**
         * Notifies the Launcher that screen is starting to turn on.
         */
        @Override
        public void onScreenTurningOff() {
            try {
                if (mOverviewProxy != null) {
                    mOverviewProxy.onScreenTurningOff();
                } else {
                    Log.e(TAG_OPS, "Failed to get overview proxy for screen turning off event.");
                }
            } catch (RemoteException e) {
                Log.e(TAG_OPS, "Failed to call onScreenTurningOff()", e);
            }
        }

        /**
         * Notifies the Launcher that screen is starting to turn on.
         */
        @Override
        public void onScreenTurningOn() {
            try {
                if (mOverviewProxy != null) {
                    mOverviewProxy.onScreenTurningOn();
                } else {
                    Log.e(TAG_OPS, "Failed to get overview proxy for screen turning on event.");
                }
            } catch (RemoteException e) {
                Log.e(TAG_OPS, "Failed to call onScreenTurningOn()", e);
            }
        }
    };

    void notifyToggleRecentApps() {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onToggleRecentApps();
        }
    }

    public void disable(int displayId, int state1, int state2, boolean animate) {
        try {
            if (mOverviewProxy != null) {
                mOverviewProxy.disable(displayId, state1, state2, animate);
            } else {
                Log.e(TAG_OPS, "Failed to get overview proxy for disable flags.");
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to call disable()", e);
        }
    }

    public void onRotationProposal(int rotation, boolean isValid) {
        try {
            if (mOverviewProxy != null) {
                mOverviewProxy.onRotationProposal(rotation, isValid);
            } else {
                Log.e(TAG_OPS, "Failed to get overview proxy for proposing rotation.");
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to call onRotationProposal()", e);
        }
    }

    public void onSystemBarAttributesChanged(int displayId, int behavior) {
        try {
            if (mOverviewProxy != null) {
                mOverviewProxy.onSystemBarAttributesChanged(displayId, behavior);
            } else {
                Log.e(TAG_OPS, "Failed to get overview proxy for system bar attr change.");
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to call onSystemBarAttributesChanged()", e);
        }
    }

    public void onNavButtonsDarkIntensityChanged(float darkIntensity) {
        try {
            if (mOverviewProxy != null) {
                mOverviewProxy.onNavButtonsDarkIntensityChanged(darkIntensity);
            } else {
                Log.e(TAG_OPS, "Failed to get overview proxy to update nav buttons dark intensity");
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to call onNavButtonsDarkIntensityChanged()", e);
        }
    }

    private void updateEnabledState() {
        final int currentUser = mUserTracker.getUserId();
        mIsEnabled = mContext.getPackageManager().resolveServiceAsUser(mQuickStepIntent,
                MATCH_SYSTEM_ONLY, currentUser) != null;
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mNavBarMode = mode;
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(TAG_OPS + " state:");
        pw.print("  isConnected="); pw.println(mOverviewProxy != null);
        pw.print("  mIsEnabled="); pw.println(isEnabled());
        pw.print("  mRecentsComponentName="); pw.println(mRecentsComponentName);
        pw.print("  mQuickStepIntent="); pw.println(mQuickStepIntent);
        pw.print("  mBound="); pw.println(mBound);
        pw.print("  mCurrentBoundedUserId="); pw.println(mCurrentBoundedUserId);
        pw.print("  mConnectionBackoffAttempts="); pw.println(mConnectionBackoffAttempts);
        pw.print("  mInputFocusTransferStarted="); pw.println(mInputFocusTransferStarted);
        pw.print("  mInputFocusTransferStartY="); pw.println(mInputFocusTransferStartY);
        pw.print("  mInputFocusTransferStartMillis="); pw.println(mInputFocusTransferStartMillis);
        pw.print("  mWindowCornerRadius="); pw.println(mWindowCornerRadius);
        pw.print("  mSupportsRoundedCornersOnWindows="); pw.println(mSupportsRoundedCornersOnWindows);
        pw.print("  mActiveNavBarRegion="); pw.println(mActiveNavBarRegion);
        pw.print("  mNavigationBarSurface="); pw.println(mNavigationBarSurface);
        pw.print("  mNavBarMode="); pw.println(mNavBarMode);
        mSysUiState.dump(pw, args);
    }

    public interface OverviewProxyListener {
        default void onConnectionChanged(boolean isConnected) {}
        default void onPrioritizedRotation(@Surface.Rotation int rotation) {}
        default void onOverviewShown(boolean fromHome) {}
        /** Notify the recents app (overview) is started by 3-button navigation. */
        default void onToggleRecentApps() {}
        default void onHomeRotationEnabled(boolean enabled) {}
        default void onTaskbarStatusUpdated(boolean visible, boolean stashed) {}
        default void onTaskbarAutohideSuspend(boolean suspend) {}
        default void onAssistantProgress(@FloatRange(from = 0.0, to = 1.0) float progress) {}
        default void onAssistantGestureCompletion(float velocity) {}
        default void startAssistant(Bundle bundle) {}
    }
}
