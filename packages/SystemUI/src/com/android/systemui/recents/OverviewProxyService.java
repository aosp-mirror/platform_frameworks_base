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

import static android.app.Flags.keyguardPrivateNotifications;
import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;

import static com.android.internal.accessibility.common.ShortcutConstants.CHOOSER_PACKAGE_NAME;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_SYSUI_PROXY;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_UNFOLD_ANIMATION_FORWARDER;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_UNLOCK_ANIMATION_CONTROLLER;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_AWAKE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BOUNCER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_DEVICE_DOZING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_DEVICE_DREAMING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_GOING_AWAY;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_TRACING_ENABLED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_VOICE_INTERACTION_WINDOW_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_WAKEFULNESS_TRANSITION;

import android.annotation.FloatRange;
import android.app.ActivityTaskManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerGlobal;
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
import com.android.internal.util.ScreenshotHelper;
import com.android.internal.util.ScreenshotRequest;
import com.android.systemui.Dumpable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.ui.view.InWindowLauncherUnlockAnimationManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationBar;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationBarView;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.navigationbar.buttons.KeyButtonView;
import com.android.systemui.recents.OverviewProxyService.OverviewProxyListener;
import com.android.systemui.scene.domain.interactor.SceneInteractor;
import com.android.systemui.scene.shared.flag.SceneContainerFlags;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.recents.ISystemUiProxy;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.smartspace.ISysuiUnlockAnimationController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.StatusBarWindowCallback;
import com.android.systemui.statusbar.policy.CallbackController;
import com.android.systemui.unfold.progress.UnfoldTransitionProgressForwarder;
import com.android.wm.shell.sysui.ShellInterface;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Provider;

import dagger.Lazy;

/**
 * Class to send information from overview to launcher with a binder.
 */
@SysUISingleton
public class OverviewProxyService implements CallbackController<OverviewProxyListener>,
        NavigationModeController.ModeChangedListener, Dumpable {

    @VisibleForTesting
    static final String ACTION_QUICKSTEP = "android.intent.action.QUICKSTEP_SERVICE";

    public static final String TAG_OPS = "OverviewProxyService";
    private static final long BACKOFF_MILLIS = 1000;
    private static final long DEFERRED_CALLBACK_MILLIS = 5000;

    // Max backoff caps at 5 mins
    private static final long MAX_BACKOFF_MILLIS = 10 * 60 * 1000;

    private final Context mContext;
    private final FeatureFlags mFeatureFlags;
    private final SceneContainerFlags mSceneContainerFlags;
    private final Executor mMainExecutor;
    private final ShellInterface mShellInterface;
    private final Lazy<ShadeViewController> mShadeViewControllerLazy;
    private SysUiState mSysUiState;
    private final Handler mHandler;
    private final Lazy<NavigationBarController> mNavBarControllerLazy;
    private final ScreenPinningRequest mScreenPinningRequest;
    private final NotificationShadeWindowController mStatusBarWinController;
    private final Provider<SceneInteractor> mSceneInteractor;

    private final Runnable mConnectionRunnable = () ->
            internalConnectToCurrentUser("runnable: startConnectionToCurrentUser");
    private final ComponentName mRecentsComponentName;
    private final List<OverviewProxyListener> mConnectionCallbacks = new ArrayList<>();
    private final Intent mQuickStepIntent;
    private final ScreenshotHelper mScreenshotHelper;
    private final CommandQueue mCommandQueue;
    private final UserTracker mUserTracker;
    private final ISysuiUnlockAnimationController mSysuiUnlockAnimationController;
    private final Optional<UnfoldTransitionProgressForwarder> mUnfoldTransitionProgressForwarder;
    private final UiEventLogger mUiEventLogger;
    private final DisplayTracker mDisplayTracker;
    private Region mActiveNavBarRegion;

    private final BroadcastDispatcher mBroadcastDispatcher;

    private IOverviewProxy mOverviewProxy;
    private int mConnectionBackoffAttempts;
    private boolean mBound;
    private boolean mIsEnabled;
    private int mCurrentBoundedUserId = -1;
    private boolean mInputFocusTransferStarted;
    private float mInputFocusTransferStartY;
    private long mInputFocusTransferStartMillis;
    private int mNavBarMode = NAV_BAR_MODE_3BUTTON;

    @VisibleForTesting
    public ISystemUiProxy mSysUiProxy = new ISystemUiProxy.Stub() {
        @Override
        public void startScreenPinning(int taskId) {
            verifyCallerAndClearCallingIdentityPostMain("startScreenPinning", () ->
                    mScreenPinningRequest.showPrompt(taskId, false /* allowCancel */));
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
        public void onStatusBarTouchEvent(MotionEvent event) {
            verifyCallerAndClearCallingIdentity("onStatusBarTouchEvent", () -> {
                // TODO move this logic to message queue
                if (event.getActionMasked() == ACTION_DOWN) {
                    mShadeViewControllerLazy.get().startExpandLatencyTracking();
                }
                mHandler.post(() -> {
                    int action = event.getActionMasked();
                    if (action == ACTION_DOWN) {
                        mInputFocusTransferStarted = true;
                        mInputFocusTransferStartY = event.getY();
                        mInputFocusTransferStartMillis = event.getEventTime();

                        // If scene framework is enabled, set the scene container window to
                        // visible and let the touch "slip" into that window.
                        if (mSceneContainerFlags.isEnabled()) {
                            mSceneInteractor.get().setVisible(true, "swipe down on launcher");
                        } else {
                            mShadeViewControllerLazy.get().startInputFocusTransfer();
                        }
                    }
                    if (action == ACTION_UP || action == ACTION_CANCEL) {
                        mInputFocusTransferStarted = false;

                        if (!mSceneContainerFlags.isEnabled()) {
                            float velocity = (event.getY() - mInputFocusTransferStartY)
                                    / (event.getEventTime() - mInputFocusTransferStartMillis);
                            if (action == ACTION_CANCEL) {
                                mShadeViewControllerLazy.get().cancelInputFocusTransfer();
                            } else {
                                mShadeViewControllerLazy.get().finishInputFocusTransfer(velocity);
                            }
                        }
                    }
                    event.recycle();
                });
            });
        }

        @Override
        public void onStatusBarTrackpadEvent(MotionEvent event) {
            verifyCallerAndClearCallingIdentityPostMain("onStatusBarTrackpadEvent", () ->
                    mShadeViewControllerLazy.get().handleExternalTouch(event));
        }

        @Override
        public void animateNavBarLongPress(boolean isTouchDown, boolean shrink, long durationMs) {
            verifyCallerAndClearCallingIdentityPostMain("animateNavBarLongPress", () ->
                    notifyAnimateNavBarLongPress(isTouchDown, shrink, durationMs));
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
            // TODO(b/204901476) We're intentionally using the default display for now since
            // Launcher/Taskbar isn't display aware.
            mContext.getSystemService(InputMethodManager.class)
                    .showInputMethodPickerFromSystem(true /* showAuxiliarySubtypes */,
                            mDisplayTracker.getDefaultDisplayId());
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
            return InputManagerGlobal.getInstance()
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
        public void setAssistantOverridesRequested(int[] invocationTypes) {
            verifyCallerAndClearCallingIdentityPostMain("setAssistantOverridesRequested", () ->
                    notifyAssistantOverrideRequested(invocationTypes));
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
                        mContext.startActivityAsUser(intent, mUserTracker.getUserHandle());
                    });
        }

        @Override
        public void notifyPrioritizedRotation(@Surface.Rotation int rotation) {
            verifyCallerAndClearCallingIdentityPostMain("notifyPrioritizedRotation", () ->
                    notifyPrioritizedRotationInternal(rotation));
        }

        @Override
        public void takeScreenshot(ScreenshotRequest request) {
            mScreenshotHelper.takeScreenshot(request, mHandler, null);
        }

        @Override
        public void expandNotificationPanel() {
            verifyCallerAndClearCallingIdentityPostMain("expandNotificationPanel",
                    () -> mCommandQueue.handleSystemKey(new KeyEvent(KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN)));
        }

        @Override
        public void toggleNotificationPanel() {
            verifyCallerAndClearCallingIdentityPostMain("toggleNotificationPanel", () ->
                    mCommandQueue.togglePanel());
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

    private final BroadcastReceiver mUserEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Objects.equals(intent.getAction(), Intent.ACTION_USER_UNLOCKED)) {
                if (keyguardPrivateNotifications()) {
                    // Start the overview connection to the launcher service
                    // Connect if user hasn't connected yet
                    if (getProxy() == null) {
                        startConnectionToCurrentUser();
                    }
                }
            }
        }
    };

    private final BroadcastReceiver mLauncherStateChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // If adding, bind immediately
            if (Objects.equals(intent.getAction(), ACTION_PACKAGE_ADDED)) {
                updateEnabledAndBinding();
                return;
            }

            // ACTION_PACKAGE_CHANGED
            String[] compsList = intent.getStringArrayExtra(EXTRA_CHANGED_COMPONENT_NAME_LIST);
            if (compsList == null) {
                return;
            }

            // Only rebind for TouchInteractionService component from launcher
            ResolveInfo ri = context.getPackageManager()
                    .resolveService(new Intent(ACTION_QUICKSTEP), 0);
            String interestingComponent = ri.serviceInfo.name;
            for (String component : compsList) {
                if (interestingComponent.equals(component)) {
                    Log.i(TAG_OPS, "Rebinding for component [" + component + "] change");
                    updateEnabledAndBinding();
                    return;
                }
            }
        }
    };

    private final ServiceConnection mOverviewServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG_OPS, "Overview proxy service connected");
            mConnectionBackoffAttempts = 0;
            mHandler.removeCallbacks(mDeferredConnectionCallback);
            try {
                service.linkToDeath(mOverviewServiceDeathRcpt, 0);
            } catch (RemoteException e) {
                // Failed to link to death (process may have died between binding and connecting),
                // just unbind the service for now and retry again
                Log.e(TAG_OPS, "Lost connection to launcher service", e);
                disconnectFromLauncherService("Lost connection to launcher service");
                retryConnectionWithBackoff();
                return;
            }

            mCurrentBoundedUserId = mUserTracker.getUserId();
            mOverviewProxy = IOverviewProxy.Stub.asInterface(service);

            Bundle params = new Bundle();
            params.putBinder(KEY_EXTRA_SYSUI_PROXY, mSysUiProxy.asBinder());
            params.putBinder(KEY_EXTRA_UNLOCK_ANIMATION_CONTROLLER,
                    mSysuiUnlockAnimationController.asBinder());
            mUnfoldTransitionProgressForwarder.ifPresent(
                    unfoldProgressForwarder -> params.putBinder(
                            KEY_EXTRA_UNFOLD_ANIMATION_FORWARDER,
                            unfoldProgressForwarder.asBinder()));
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

            // Force-update the systemui state flags
            updateSystemUiStateFlags();
            notifySystemUiStateFlags(mSysUiState.getFlags());

            notifyConnectionChanged();
            if (mDoneUserChanging != null) {
                mDoneUserChanging.run();
                mDoneUserChanging = null;
            }
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

    private Runnable mDoneUserChanging;
    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanging(int newUser, @NonNull Context userContext,
                        @NonNull Runnable resultCallback) {
                    mConnectionBackoffAttempts = 0;
                    mDoneUserChanging = resultCallback;
                    internalConnectToCurrentUser("User changed");
                }
            };

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Inject
    public OverviewProxyService(Context context,
            @Main Executor mainExecutor,
            CommandQueue commandQueue,
            ShellInterface shellInterface,
            Lazy<NavigationBarController> navBarControllerLazy,
            Lazy<ShadeViewController> shadeViewControllerLazy,
            ScreenPinningRequest screenPinningRequest,
            NavigationModeController navModeController,
            NotificationShadeWindowController statusBarWinController,
            SysUiState sysUiState,
            Provider<SceneInteractor> sceneInteractor,
            UserTracker userTracker,
            WakefulnessLifecycle wakefulnessLifecycle,
            UiEventLogger uiEventLogger,
            DisplayTracker displayTracker,
            KeyguardUnlockAnimationController sysuiUnlockAnimationController,
            InWindowLauncherUnlockAnimationManager inWindowLauncherUnlockAnimationManager,
            AssistUtils assistUtils,
            FeatureFlags featureFlags,
            SceneContainerFlags sceneContainerFlags,
            DumpManager dumpManager,
            Optional<UnfoldTransitionProgressForwarder> unfoldTransitionProgressForwarder,
            BroadcastDispatcher broadcastDispatcher
    ) {
        // b/241601880: This component shouldn't be running for a non-primary user
        if (!Process.myUserHandle().equals(UserHandle.SYSTEM)) {
            Log.e(TAG_OPS, "Unexpected initialization for non-primary user", new Throwable());
        }

        mContext = context;
        mFeatureFlags = featureFlags;
        mSceneContainerFlags = sceneContainerFlags;
        mMainExecutor = mainExecutor;
        mShellInterface = shellInterface;
        mShadeViewControllerLazy = shadeViewControllerLazy;
        mHandler = new Handler();
        mNavBarControllerLazy = navBarControllerLazy;
        mScreenPinningRequest = screenPinningRequest;
        mStatusBarWinController = statusBarWinController;
        mSceneInteractor = sceneInteractor;
        mUserTracker = userTracker;
        mConnectionBackoffAttempts = 0;
        mRecentsComponentName = ComponentName.unflattenFromString(context.getString(
                com.android.internal.R.string.config_recentsComponentName));
        mQuickStepIntent = new Intent(ACTION_QUICKSTEP)
                .setPackage(mRecentsComponentName.getPackageName());
        mSysUiState = sysUiState;
        mSysUiState.addCallback(this::notifySystemUiStateFlags);
        mUiEventLogger = uiEventLogger;
        mDisplayTracker = displayTracker;
        mUnfoldTransitionProgressForwarder = unfoldTransitionProgressForwarder;
        mBroadcastDispatcher = broadcastDispatcher;

        if (!featureFlags.isEnabled(Flags.KEYGUARD_WM_STATE_REFACTOR)) {
            mSysuiUnlockAnimationController = sysuiUnlockAnimationController;
        } else {
            mSysuiUnlockAnimationController = inWindowLauncherUnlockAnimationManager;
        }

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

        if (keyguardPrivateNotifications()) {
            mBroadcastDispatcher.registerReceiver(mUserEventReceiver,
                    new IntentFilter(Intent.ACTION_USER_UNLOCKED),
                    null /* executor */, UserHandle.ALL);
        }

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

        wakefulnessLifecycle.addObserver(mWakefulnessLifecycleObserver);
        // Connect to the service
        updateEnabledAndBinding();

        // Listen for assistant changes
        assistUtils.registerVoiceInteractionSessionListener(mVoiceInteractionSessionListener);
    }

    public void onVoiceSessionWindowVisibilityChanged(boolean visible) {
        mSysUiState.setFlag(SYSUI_STATE_VOICE_INTERACTION_WINDOW_SHOWING, visible)
                .commitUpdate(mContext.getDisplayId());
    }

    private void updateEnabledAndBinding() {
        updateEnabledState();
        startConnectionToCurrentUser();
    }

    private void updateSystemUiStateFlags() {
        final NavigationBar navBarFragment =
                mNavBarControllerLazy.get().getDefaultNavigationBar();
        final NavigationBarView navBarView =
                mNavBarControllerLazy.get().getNavigationBarView(mContext.getDisplayId());
        if (SysUiState.DEBUG) {
            Log.d(TAG_OPS, "Updating sysui state flags: navBarFragment=" + navBarFragment
                    + " navBarView=" + navBarView
                    + " shadeViewController=" + mShadeViewControllerLazy.get());
        }

        if (navBarFragment != null) {
            navBarFragment.updateSystemUiStateFlags();
        }
        if (navBarView != null) {
            navBarView.updateDisabledSystemUiStateFlags(mSysUiState);
        }
        mShadeViewControllerLazy.get().updateSystemUiStateFlags();
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
            boolean keyguardGoingAway, boolean bouncerShowing, boolean isDozing,
            boolean panelExpanded, boolean isDreaming) {
        mSysUiState.setFlag(SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING,
                        keyguardShowing && !keyguardOccluded)
                .setFlag(SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED,
                        keyguardShowing && keyguardOccluded)
                .setFlag(SYSUI_STATE_STATUS_BAR_KEYGUARD_GOING_AWAY,
                        keyguardGoingAway)
                .setFlag(SYSUI_STATE_BOUNCER_SHOWING, bouncerShowing)
                .setFlag(SYSUI_STATE_DEVICE_DOZING, isDozing)
                .setFlag(SYSUI_STATE_DEVICE_DREAMING, isDreaming)
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
                mInputFocusTransferStarted = false;
                mShadeViewControllerLazy.get().cancelInputFocusTransfer();
            });
        }
        startConnectionToCurrentUser();
    }

    public void startConnectionToCurrentUser() {
        Log.v(TAG_OPS, "startConnectionToCurrentUser: connection is restarted");
        if (mHandler.getLooper() != Looper.myLooper()) {
            mHandler.post(mConnectionRunnable);
        } else {
            internalConnectToCurrentUser("startConnectionToCurrentUser");
        }
    }

    private void internalConnectToCurrentUser(String reason) {
        disconnectFromLauncherService(reason);

        // If user has not setup yet or already connected, do not try to connect
        if (!isEnabled()) {
            Log.v(TAG_OPS, "Cannot attempt connection, is enabled " + isEnabled());
            return;
        }
        mHandler.removeCallbacks(mConnectionRunnable);
        try {
            mBound = mContext.bindServiceAsUser(mQuickStepIntent,
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

    private void disconnectFromLauncherService(String disconnectReason) {
        Log.d(TAG_OPS, "disconnectFromLauncherService bound?: " + mBound +
                " currentProxy: " + mOverviewProxy + " disconnectReason: " + disconnectReason,
                new Throwable());
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

    private void notifyAssistantOverrideRequested(int[] invocationTypes) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).setAssistantOverridesRequested(invocationTypes);
        }
    }

    private void notifyAnimateNavBarLongPress(boolean isTouchDown, boolean shrink,
            long durationMs) {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).animateNavBarLongPress(isTouchDown, shrink, durationMs);
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

    private final WakefulnessLifecycle.Observer mWakefulnessLifecycleObserver =
            new WakefulnessLifecycle.Observer() {
                @Override
                public void onStartedWakingUp() {
                    mSysUiState
                            .setFlag(SYSUI_STATE_AWAKE, true)
                            .setFlag(SYSUI_STATE_WAKEFULNESS_TRANSITION, true)
                            .commitUpdate(mContext.getDisplayId());
                }

                @Override
                public void onFinishedWakingUp() {
                    mSysUiState
                            .setFlag(SYSUI_STATE_AWAKE, true)
                            .setFlag(SYSUI_STATE_WAKEFULNESS_TRANSITION, false)
                            .commitUpdate(mContext.getDisplayId());
                }

                @Override
                public void onStartedGoingToSleep() {
                    mSysUiState
                            .setFlag(SYSUI_STATE_AWAKE, false)
                            .setFlag(SYSUI_STATE_WAKEFULNESS_TRANSITION, true)
                            .commitUpdate(mContext.getDisplayId());
                }

                @Override
                public void onFinishedGoingToSleep() {
                    mSysUiState
                            .setFlag(SYSUI_STATE_AWAKE, false)
                            .setFlag(SYSUI_STATE_WAKEFULNESS_TRANSITION, false)
                            .commitUpdate(mContext.getDisplayId());
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

    public void onNavigationBarLumaSamplingEnabled(int displayId, boolean enable) {
        try {
            if (mOverviewProxy != null) {
                mOverviewProxy.onNavigationBarLumaSamplingEnabled(displayId, enable);
            } else {
                Log.e(TAG_OPS, "Failed to get overview proxy to enable/disable nav bar luma"
                        + "sampling");
            }
        } catch (RemoteException e) {
            Log.e(TAG_OPS, "Failed to call onNavigationBarLumaSamplingEnabled()", e);
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
        pw.print("  mActiveNavBarRegion="); pw.println(mActiveNavBarRegion);
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
        default void setAssistantOverridesRequested(int[] invocationTypes) {}
        default void animateNavBarLongPress(boolean isTouchDown, boolean shrink, long durationMs) {}
    }

    /**
     * Shuts down this service at the end of a testcase.
     * <p>
     * The in-production service is never shuts down, and it was not designed with testing in mind.
     * This unregisters the mechanisms by which the service will be revived after a testcase.
     * <p>
     * NOTE: This is a stop-gap introduced when first added some tests to this class. It should
     * probably be replaced by proper lifecycle management on this class.
     */
    @VisibleForTesting()
    void shutdownForTest() {
        mContext.unregisterReceiver(mLauncherStateChangedReceiver);
        mIsEnabled = false;
        mHandler.removeCallbacks(mConnectionRunnable);
        disconnectFromLauncherService("Shutdown for test");
    }
}
