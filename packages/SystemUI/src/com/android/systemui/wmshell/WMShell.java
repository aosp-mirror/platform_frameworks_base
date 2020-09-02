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

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_ONE_HANDED_ACTIVE;
import static com.android.systemui.shared.system.WindowManagerWrapper.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.view.KeyEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.onehanded.OneHanded;
import com.android.systemui.onehanded.OneHandedEvents;
import com.android.systemui.onehanded.OneHandedGestureHandler.OneHandedGestureEventCallback;
import com.android.systemui.onehanded.OneHandedTransitionCallback;
import com.android.systemui.pip.Pip;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.tracing.ProtoTraceable;
import com.android.systemui.stackdivider.SplitScreen;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.tracing.ProtoTracer;
import com.android.systemui.tracing.nano.SystemUiTraceProto;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.nano.WmShellTraceProto;
import com.android.wm.shell.protolog.ShellProtoLogImpl;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Optional;

import javax.inject.Inject;

/**
 * Proxy in SysUiScope to delegate events to controllers in WM Shell library.
 */
@SysUISingleton
public final class WMShell extends SystemUI implements ProtoTraceable<SystemUiTraceProto> {
    private final CommandQueue mCommandQueue;
    private final DisplayImeController mDisplayImeController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ActivityManagerWrapper mActivityManagerWrapper;
    private final NavigationModeController mNavigationModeController;
    private final ScreenLifecycle mScreenLifecycle;
    private final SysUiState mSysUiState;
    private final Optional<Pip> mPipOptional;
    private final Optional<SplitScreen> mSplitScreenOptional;
    private final Optional<OneHanded> mOneHandedOptional;
    private final ProtoTracer mProtoTracer;

    @Inject
    public WMShell(Context context, CommandQueue commandQueue,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            ActivityManagerWrapper activityManagerWrapper,
            DisplayImeController displayImeController,
            NavigationModeController navigationModeController,
            ScreenLifecycle screenLifecycle,
            SysUiState sysUiState,
            Optional<Pip> pipOptional,
            Optional<SplitScreen> splitScreenOptional,
            Optional<OneHanded> oneHandedOptional,
            ProtoTracer protoTracer) {
        super(context);
        mCommandQueue = commandQueue;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mActivityManagerWrapper = activityManagerWrapper;
        mDisplayImeController = displayImeController;
        mNavigationModeController = navigationModeController;
        mScreenLifecycle = screenLifecycle;
        mSysUiState = sysUiState;
        mPipOptional = pipOptional;
        mSplitScreenOptional = splitScreenOptional;
        mOneHandedOptional = oneHandedOptional;
        mProtoTracer = protoTracer;
        mProtoTracer.add(this);
    }

    @Override
    public void start() {
        // This is to prevent circular init problem by separating registration step out of its
        // constructor. And make sure the initialization of DisplayImeController won't depend on
        // specific feature anymore.
        mDisplayImeController.startMonitorDisplays();
        mPipOptional.ifPresent(this::initPip);
        mSplitScreenOptional.ifPresent(this::initSplitScreen);
        mOneHandedOptional.ifPresent(this::initOneHanded);
    }

    @VisibleForTesting
    void initPip(Pip pip) {
        mCommandQueue.addCallback(new CommandQueue.Callbacks() {
            @Override
            public void showPictureInPictureMenu() {
                pip.showPictureInPictureMenu();
            }
        });
    }

    @VisibleForTesting
    void initSplitScreen(SplitScreen splitScreen) {
        mKeyguardUpdateMonitor.registerCallback(new KeyguardUpdateMonitorCallback() {
            @Override
            public void onKeyguardVisibilityChanged(boolean showing) {
                // Hide the divider when keyguard is showing. Even though keyguard/statusbar is
                // above everything, it is actually transparent except for notifications, so
                // we still need to hide any surfaces that are below it.
                // TODO(b/148906453): Figure out keyguard dismiss animation for divider view.
                splitScreen.onKeyguardVisibilityChanged(showing);
            }
        });

        mActivityManagerWrapper.registerTaskStackListener(
                new TaskStackChangeListener() {
                    @Override
                    public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                            boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
                        if (!wasVisible || task.configuration.windowConfiguration.getWindowingMode()
                                != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                                || !splitScreen.isSplitScreenSupported()) {
                            return;
                        }

                        if (splitScreen.isMinimized()) {
                            splitScreen.onUndockingTask();
                        }
                    }

                    @Override
                    public void onActivityForcedResizable(String packageName, int taskId,
                            int reason) {
                        splitScreen.onActivityForcedResizable(packageName, taskId, reason);
                    }

                    @Override
                    public void onActivityDismissingDockedStack() {
                        splitScreen.onActivityDismissingSplitScreen();
                    }

                    @Override
                    public void onActivityLaunchOnSecondaryDisplayFailed() {
                        splitScreen.onActivityLaunchOnSecondaryDisplayFailed();
                    }
                });
    }

    @VisibleForTesting
    void initOneHanded(OneHanded oneHanded) {
        if (!oneHanded.hasOneHandedFeature()) {
            return;
        }

        int currentMode = mNavigationModeController.addListener(mode ->
                oneHanded.setThreeButtonModeEnabled(mode == NAV_BAR_MODE_3BUTTON));
        oneHanded.setThreeButtonModeEnabled(currentMode == NAV_BAR_MODE_3BUTTON);

        oneHanded.registerTransitionCallback(new OneHandedTransitionCallback() {
            @Override
            public void onStartFinished(Rect bounds) {
                mSysUiState.setFlag(SYSUI_STATE_ONE_HANDED_ACTIVE,
                        true).commitUpdate(DEFAULT_DISPLAY);
            }

            @Override
            public void onStopFinished(Rect bounds) {
                mSysUiState.setFlag(SYSUI_STATE_ONE_HANDED_ACTIVE,
                        false).commitUpdate(DEFAULT_DISPLAY);
            }
        });

        oneHanded.registerGestureCallback(new OneHandedGestureEventCallback() {
            @Override
            public void onStart() {
                if (oneHanded.isOneHandedEnabled()) {
                    oneHanded.startOneHanded();
                } else if (oneHanded.isSwipeToNotificationEnabled()) {
                    mCommandQueue.handleSystemKey(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN);
                }
            }

            @Override
            public void onStop() {
                if (oneHanded.isOneHandedEnabled()) {
                    oneHanded.stopOneHanded(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_GESTURE_OUT);
                } else if (oneHanded.isSwipeToNotificationEnabled()) {
                    mCommandQueue.handleSystemKey(KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP);
                }
            }
        });

        mKeyguardUpdateMonitor.registerCallback(new KeyguardUpdateMonitorCallback() {
            @Override
            public void onKeyguardBouncerChanged(boolean bouncer) {
                if (bouncer) {
                    oneHanded.stopOneHanded();
                }
            }

            @Override
            public void onKeyguardVisibilityChanged(boolean showing) {
                oneHanded.stopOneHanded();
            }
        });

        mScreenLifecycle.addObserver(new ScreenLifecycle.Observer() {
            @Override
            public void onScreenTurningOff() {
                oneHanded.stopOneHanded(
                        OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_SCREEN_OFF_OUT);
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
                if (displayId != DEFAULT_DISPLAY && (vis & InputMethodService.IME_VISIBLE) == 0) {
                    return;
                }
                oneHanded.stopOneHanded(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_POP_IME_OUT);
            }
        });

        mActivityManagerWrapper.registerTaskStackListener(
                new TaskStackChangeListener() {
                    @Override
                    public void onTaskCreated(int taskId, ComponentName componentName) {
                        oneHanded.stopOneHanded(
                                OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_APP_TAPS_OUT);
                    }

                    @Override
                    public void onTaskMovedToFront(int taskId) {
                        oneHanded.stopOneHanded(
                                OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_APP_TAPS_OUT);
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
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "enable-text-logging": {
                    String[] groups = Arrays.copyOfRange(args, i + 1, args.length);
                    startTextLogging(groups);
                    pw.println("Starting logging on groups: " + Arrays.toString(groups));
                    return;
                }
                case "disable-text-logging": {
                    String[] groups = Arrays.copyOfRange(args, i + 1, args.length);
                    stopTextLogging(groups);
                    pw.println("Stopping logging on groups: " + Arrays.toString(groups));
                    return;
                }
            }
        }

        // Dump WMShell stuff here if no commands were handled
    }

    private void startTextLogging(String... groups) {
        ShellProtoLogImpl.getSingleInstance().startTextLogging(mContext, groups);
    }

    private void stopTextLogging(String... groups) {
        ShellProtoLogImpl.getSingleInstance().stopTextLogging(groups);
    }
}
