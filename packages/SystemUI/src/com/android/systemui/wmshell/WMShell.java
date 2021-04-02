/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.wmshell;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BOUNCER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BUBBLES_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_GLOBAL_ACTIONS_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_ONE_HANDED_ACTIVE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_QUICK_SETTINGS_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.WMComponent;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.shared.tracing.ProtoTraceable;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.tracing.ProtoTracer;
import com.android.systemui.tracing.nano.SystemUiTraceProto;
import com.android.wm.shell.ShellCommandHandler;
import com.android.wm.shell.hidedisplaycutout.HideDisplayCutout;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.nano.WmShellTraceProto;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.onehanded.OneHandedGestureHandler.OneHandedGestureEventCallback;
import com.android.wm.shell.onehanded.OneHandedTransitionCallback;
import com.android.wm.shell.onehanded.OneHandedUiEventLogger;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.protolog.ShellProtoLogImpl;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * A SystemUI service that starts with the SystemUI application and sets up any bindings between
 * Shell and SysUI components.  This service starts happens after the {@link WMComponent} has
 * already been initialized and may only reference Shell components that are explicitly exported to
 * SystemUI (see {@link WMComponent}.
 *
 * eg. SysUI application starts
 *     -> SystemUIFactory is initialized
 *       -> WMComponent is created
 *         -> WMShellBaseModule dependencies are injected
 *         -> WMShellModule (form-factory specific) dependencies are injected
 *       -> SysUIComponent is created
 *         -> WMComponents are explicitly provided to SysUIComponent for injection into SysUI code
 *     -> SysUI services are started
 *       -> WMShell starts and binds SysUI with Shell components via exported Shell interfaces
 */
@SysUISingleton
public final class WMShell extends SystemUI
        implements CommandQueue.Callbacks, ProtoTraceable<SystemUiTraceProto> {
    private static final String TAG = WMShell.class.getName();
    private static final int INVALID_SYSUI_STATE_MASK =
            SYSUI_STATE_GLOBAL_ACTIONS_SHOWING
                    | SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING
                    | SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED
                    | SYSUI_STATE_BOUNCER_SHOWING
                    | SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED
                    | SYSUI_STATE_BUBBLES_EXPANDED
                    | SYSUI_STATE_QUICK_SETTINGS_EXPANDED;

    // Shell interfaces
    private final Optional<Pip> mPipOptional;
    private final Optional<LegacySplitScreen> mSplitScreenOptional;
    private final Optional<OneHanded> mOneHandedOptional;
    private final Optional<HideDisplayCutout> mHideDisplayCutoutOptional;
    private final Optional<ShellCommandHandler> mShellCommandHandler;

    private final CommandQueue mCommandQueue;
    private final ConfigurationController mConfigurationController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final NavigationModeController mNavigationModeController;
    private final ScreenLifecycle mScreenLifecycle;
    private final SysUiState mSysUiState;
    private final ProtoTracer mProtoTracer;
    private final Executor mSysUiMainExecutor;

    private boolean mIsSysUiStateValid;
    private KeyguardUpdateMonitorCallback mSplitScreenKeyguardCallback;
    private KeyguardUpdateMonitorCallback mPipKeyguardCallback;
    private KeyguardUpdateMonitorCallback mOneHandedKeyguardCallback;

    @Inject
    public WMShell(Context context,
            Optional<Pip> pipOptional,
            Optional<LegacySplitScreen> splitScreenOptional,
            Optional<OneHanded> oneHandedOptional,
            Optional<HideDisplayCutout> hideDisplayCutoutOptional,
            Optional<ShellCommandHandler> shellCommandHandler,
            CommandQueue commandQueue,
            ConfigurationController configurationController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            NavigationModeController navigationModeController,
            ScreenLifecycle screenLifecycle,
            SysUiState sysUiState,
            ProtoTracer protoTracer,
            @Main Executor sysUiMainExecutor) {
        super(context);
        mCommandQueue = commandQueue;
        mConfigurationController = configurationController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mNavigationModeController = navigationModeController;
        mScreenLifecycle = screenLifecycle;
        mSysUiState = sysUiState;
        mPipOptional = pipOptional;
        mSplitScreenOptional = splitScreenOptional;
        mOneHandedOptional = oneHandedOptional;
        mHideDisplayCutoutOptional = hideDisplayCutoutOptional;
        mProtoTracer = protoTracer;
        mShellCommandHandler = shellCommandHandler;
        mSysUiMainExecutor = sysUiMainExecutor;
    }

    @Override
    public void start() {
        // TODO: Consider piping config change and other common calls to a shell component to
        //  delegate internally
        mProtoTracer.add(this);
        mCommandQueue.addCallback(this);
        mPipOptional.ifPresent(this::initPip);
        mSplitScreenOptional.ifPresent(this::initSplitScreen);
        mOneHandedOptional.ifPresent(this::initOneHanded);
        mHideDisplayCutoutOptional.ifPresent(this::initHideDisplayCutout);
    }

    @VisibleForTesting
    void initPip(Pip pip) {
        mCommandQueue.addCallback(new CommandQueue.Callbacks() {
            @Override
            public void showPictureInPictureMenu() {
                pip.showPictureInPictureMenu();
            }
        });

        mPipKeyguardCallback = new KeyguardUpdateMonitorCallback() {
            @Override
            public void onKeyguardVisibilityChanged(boolean showing) {
                if (showing) {
                    pip.hidePipMenu(null, null);
                }
            }
        };
        mKeyguardUpdateMonitor.registerCallback(mPipKeyguardCallback);

        mSysUiState.addCallback(sysUiStateFlag -> {
            mIsSysUiStateValid = (sysUiStateFlag & INVALID_SYSUI_STATE_MASK) == 0;
            pip.onSystemUiStateChanged(mIsSysUiStateValid, sysUiStateFlag);
        });

        mConfigurationController.addCallback(new ConfigurationController.ConfigurationListener() {
            @Override
            public void onConfigChanged(Configuration newConfig) {
                pip.onConfigurationChanged(newConfig);
            }

            @Override
            public void onDensityOrFontScaleChanged() {
                pip.onDensityOrFontScaleChanged();
            }

            @Override
            public void onOverlayChanged() {
                pip.onOverlayChanged();
            }
        });

        // The media session listener needs to be re-registered when switching users
        UserInfoController userInfoController = Dependency.get(UserInfoController.class);
        userInfoController.addCallback((String name, Drawable picture, String userAccount) ->
                pip.registerSessionListenerForCurrentUser());
    }

    @VisibleForTesting
    void initSplitScreen(LegacySplitScreen legacySplitScreen) {
        mSplitScreenKeyguardCallback = new KeyguardUpdateMonitorCallback() {
            @Override
            public void onKeyguardVisibilityChanged(boolean showing) {
                // Hide the divider when keyguard is showing. Even though keyguard/statusbar is
                // above everything, it is actually transparent except for notifications, so
                // we still need to hide any surfaces that are below it.
                // TODO(b/148906453): Figure out keyguard dismiss animation for divider view.
                legacySplitScreen.onKeyguardVisibilityChanged(showing);
            }
        };
        mKeyguardUpdateMonitor.registerCallback(mSplitScreenKeyguardCallback);
    }

    @VisibleForTesting
    void initOneHanded(OneHanded oneHanded) {
        int currentMode = mNavigationModeController.addListener(mode ->
                oneHanded.setThreeButtonModeEnabled(mode == NAV_BAR_MODE_3BUTTON));
        oneHanded.setThreeButtonModeEnabled(currentMode == NAV_BAR_MODE_3BUTTON);

        oneHanded.registerTransitionCallback(new OneHandedTransitionCallback() {
            @Override
            public void onStartFinished(Rect bounds) {
                mSysUiMainExecutor.execute(() -> {
                    mSysUiState.setFlag(SYSUI_STATE_ONE_HANDED_ACTIVE,
                            true).commitUpdate(DEFAULT_DISPLAY);
                });
            }

            @Override
            public void onStopFinished(Rect bounds) {
                mSysUiMainExecutor.execute(() -> {
                    mSysUiState.setFlag(SYSUI_STATE_ONE_HANDED_ACTIVE,
                            false).commitUpdate(DEFAULT_DISPLAY);
                });
            }
        });

        oneHanded.registerGestureCallback(new OneHandedGestureEventCallback() {
            @Override
            public void onStart() {
                mSysUiMainExecutor.execute(() -> {
                    if (oneHanded.isOneHandedEnabled()) {
                        oneHanded.startOneHanded();
                    } else if (oneHanded.isSwipeToNotificationEnabled()) {
                        mCommandQueue.handleSystemKey(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN);
                    }
                });
            }

            @Override
            public void onStop() {
                mSysUiMainExecutor.execute(() -> {
                    if (oneHanded.isOneHandedEnabled()) {
                        oneHanded.stopOneHanded(
                                OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_GESTURE_OUT);
                    } else if (oneHanded.isSwipeToNotificationEnabled()) {
                        mCommandQueue.handleSystemKey(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP);
                    }
                });
            }
        });

        mOneHandedKeyguardCallback = new KeyguardUpdateMonitorCallback() {
            @Override
            public void onKeyguardBouncerChanged(boolean bouncer) {
                if (bouncer) {
                    oneHanded.stopOneHanded();
                }
            }

            @Override
            public void onKeyguardVisibilityChanged(boolean showing) {
                if (showing) {
                    // When keyguard shown, temperory lock OHM disabled to avoid mis-trigger.
                    oneHanded.setLockedDisabled(true /* locked */, false /* enabled */);
                } else {
                    // Reset locked.
                    oneHanded.setLockedDisabled(false /* locked */, false /* enabled */);
                }
                oneHanded.stopOneHanded();
            }

            @Override
            public void onUserSwitchComplete(int userId) {
                oneHanded.onUserSwitch(userId);
            }
        };
        mKeyguardUpdateMonitor.registerCallback(mOneHandedKeyguardCallback);

        mScreenLifecycle.addObserver(new ScreenLifecycle.Observer() {
            @Override
            public void onScreenTurningOff() {
                oneHanded.stopOneHanded(
                        OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_SCREEN_OFF_OUT);
            }
        });

        mCommandQueue.addCallback(new CommandQueue.Callbacks() {
            @Override
            public void onCameraLaunchGestureDetected(int source) {
                oneHanded.stopOneHanded();
            }

            @Override
            public void setImeWindowStatus(int displayId, IBinder token, int vis,
                    int backDisposition, boolean showImeSwitcher) {
                if (displayId == DEFAULT_DISPLAY && (vis & InputMethodService.IME_VISIBLE) != 0) {
                    oneHanded.stopOneHanded(
                            OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_POP_IME_OUT);
                }
            }
        });

        mConfigurationController.addCallback(new ConfigurationController.ConfigurationListener() {
            @Override
            public void onConfigChanged(Configuration newConfig) {
                oneHanded.onConfigChanged(newConfig);
            }
        });
    }

    @VisibleForTesting
    void initHideDisplayCutout(HideDisplayCutout hideDisplayCutout) {
        mConfigurationController.addCallback(new ConfigurationController.ConfigurationListener() {
            @Override
            public void onConfigChanged(Configuration newConfig) {
                hideDisplayCutout.onConfigurationChanged(newConfig);
            }
        });
    }

    @Override
    public void writeToProto(SystemUiTraceProto proto) {
        if (proto.wmShell == null) {
            proto.wmShell = new WmShellTraceProto();
        }
        // Dump to WMShell proto here
        // TODO: Figure out how we want to synchronize while dumping to proto
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        // Handle commands if provided
        if (mShellCommandHandler.isPresent()
                && mShellCommandHandler.get().handleCommand(args, pw)) {
            return;
        }
        // Handle logging commands if provided
        if (handleLoggingCommand(args, pw)) {
            return;
        }
        // Dump WMShell stuff here if no commands were handled
        mShellCommandHandler.ifPresent(
                shellCommandHandler -> shellCommandHandler.dump(pw));
    }

    @Override
    public void handleWindowManagerLoggingCommand(String[] args, ParcelFileDescriptor outFd) {
        PrintWriter pw = new PrintWriter(new ParcelFileDescriptor.AutoCloseOutputStream(outFd));
        handleLoggingCommand(args, pw);
        pw.flush();
        pw.close();
    }

    private boolean handleLoggingCommand(String[] args, PrintWriter pw) {
        ShellProtoLogImpl protoLogImpl = ShellProtoLogImpl.getSingleInstance();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "enable-text": {
                    String[] groups = Arrays.copyOfRange(args, i + 1, args.length);
                    int result = protoLogImpl.startTextLogging(groups, pw);
                    if (result == 0) {
                        pw.println("Starting logging on groups: " + Arrays.toString(groups));
                    }
                    return true;
                }
                case "disable-text": {
                    String[] groups = Arrays.copyOfRange(args, i + 1, args.length);
                    int result = protoLogImpl.stopTextLogging(groups, pw);
                    if (result == 0) {
                        pw.println("Stopping logging on groups: " + Arrays.toString(groups));
                    }
                    return true;
                }
            }
        }
        return false;
    }
}
