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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
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
import static com.android.systemui.shared.system.WindowManagerWrapper.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.shared.system.InputConsumerController;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.shared.tracing.ProtoTraceable;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.tracing.ProtoTracer;
import com.android.systemui.tracing.nano.SystemUiTraceProto;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.nano.WmShellTraceProto;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.onehanded.OneHandedEvents;
import com.android.wm.shell.onehanded.OneHandedGestureHandler.OneHandedGestureEventCallback;
import com.android.wm.shell.onehanded.OneHandedTransitionCallback;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.phone.PipUtils;
import com.android.wm.shell.protolog.ShellProtoLogImpl;
import com.android.wm.shell.splitscreen.SplitScreen;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Optional;

import javax.inject.Inject;

/**
 * Proxy in SysUiScope to delegate events to controllers in WM Shell library.
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

    private final CommandQueue mCommandQueue;
    private final ConfigurationController mConfigurationController;
    private final InputConsumerController mInputConsumerController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final TaskStackChangeListeners mTaskStackChangeListeners;
    private final NavigationModeController mNavigationModeController;
    private final ScreenLifecycle mScreenLifecycle;
    private final SysUiState mSysUiState;
    // TODO: This is only here because we need to dump state. Remove and replace with a dumper
    //  interface.
    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final Optional<Pip> mPipOptional;
    private final Optional<SplitScreen> mSplitScreenOptional;
    private final Optional<OneHanded> mOneHandedOptional;
    private final ProtoTracer mProtoTracer;
    private boolean mIsSysUiStateValid;
    private KeyguardUpdateMonitorCallback mSplitScreenKeyguardCallback;
    private KeyguardUpdateMonitorCallback mPipKeyguardCallback;
    private KeyguardUpdateMonitorCallback mOneHandedKeyguardCallback;

    @Inject
    public WMShell(Context context, CommandQueue commandQueue,
            ConfigurationController configurationController,
            InputConsumerController inputConsumerController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            TaskStackChangeListeners taskStackChangeListeners,
            NavigationModeController navigationModeController,
            ScreenLifecycle screenLifecycle,
            SysUiState sysUiState,
            Optional<Pip> pipOptional,
            Optional<SplitScreen> splitScreenOptional,
            Optional<OneHanded> oneHandedOptional,
            ShellTaskOrganizer shellTaskOrganizer,
            ProtoTracer protoTracer) {
        super(context);
        mCommandQueue = commandQueue;
        mConfigurationController = configurationController;
        mInputConsumerController = inputConsumerController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mTaskStackChangeListeners = taskStackChangeListeners;
        mNavigationModeController = navigationModeController;
        mScreenLifecycle = screenLifecycle;
        mSysUiState = sysUiState;
        mPipOptional = pipOptional;
        mSplitScreenOptional = splitScreenOptional;
        mOneHandedOptional = oneHandedOptional;
        mShellTaskOrganizer = shellTaskOrganizer;
        mProtoTracer = protoTracer;
        mProtoTracer.add(this);
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
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
            public void onDensityOrFontScaleChanged() {
                pip.onDensityOrFontScaleChanged();
            }

            @Override
            public void onOverlayChanged() {
                pip.onOverlayChanged();
            }
        });

        // TODO: Move this into the shell
        // Handle for system task stack changes.
        mTaskStackChangeListeners.registerTaskStackListener(
                new TaskStackChangeListener() {
                    @Override
                    public void onTaskStackChanged() {
                        pip.onTaskStackChanged();
                    }

                    @Override
                    public void onActivityPinned(String packageName, int userId, int taskId,
                            int stackId) {
                        pip.onActivityPinned(packageName);
                        mInputConsumerController.registerInputConsumer(true /* withSfVsync */);
                    }

                    @Override
                    public void onActivityUnpinned() {
                        final Pair<ComponentName, Integer> topPipActivityInfo =
                                PipUtils.getTopPipActivity(
                                        mContext, ActivityManager.getService());
                        final ComponentName topActivity = topPipActivityInfo.first;
                        pip.onActivityUnpinned(topActivity);
                        mInputConsumerController.unregisterInputConsumer();
                    }

                    @Override
                    public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                            boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
                        pip.onActivityRestartAttempt(task, clearedTask);
                    }
                });

        try {
            RootTaskInfo taskInfo = ActivityTaskManager.getService().getRootTaskInfo(
                    WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (taskInfo != null) {
                // If SystemUI restart, and it already existed a pinned stack,
                // register the pip input consumer to ensure touch can send to it.
                mInputConsumerController.registerInputConsumer(true /* withSfVsync */);
            }
        } catch (RemoteException | UnsupportedOperationException e) {
            Log.e(TAG, "Failed to register pinned stack listener", e);
            e.printStackTrace();
        }

        // Register the listener for input consumer touch events. Only for Phone
        if (pip.getPipTouchHandler() != null) {
            mInputConsumerController.setInputListener(pip.getPipTouchHandler()::handleTouchEvent);
            mInputConsumerController.setRegistrationListener(
                    pip.getPipTouchHandler()::onRegistrationChanged);
        }

        // The media session listener needs to be re-registered when switching users
        UserInfoController userInfoController = Dependency.get(UserInfoController.class);
        userInfoController.addCallback((String name, Drawable picture, String userAccount) ->
                pip.registerSessionListenerForCurrentUser());
    }

    @VisibleForTesting
    void initSplitScreen(SplitScreen splitScreen) {
        mSplitScreenKeyguardCallback = new KeyguardUpdateMonitorCallback() {
            @Override
            public void onKeyguardVisibilityChanged(boolean showing) {
                // Hide the divider when keyguard is showing. Even though keyguard/statusbar is
                // above everything, it is actually transparent except for notifications, so
                // we still need to hide any surfaces that are below it.
                // TODO(b/148906453): Figure out keyguard dismiss animation for divider view.
                splitScreen.onKeyguardVisibilityChanged(showing);
            }
        };
        mKeyguardUpdateMonitor.registerCallback(mSplitScreenKeyguardCallback);

        mTaskStackChangeListeners.registerTaskStackListener(
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

        mOneHandedKeyguardCallback = new KeyguardUpdateMonitorCallback() {
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
        };
        mKeyguardUpdateMonitor.registerCallback(mOneHandedKeyguardCallback);

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
                if (displayId == DEFAULT_DISPLAY && (vis & InputMethodService.IME_VISIBLE) != 0) {
                    oneHanded.stopOneHanded(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_POP_IME_OUT);
                }
            }
        });

        mTaskStackChangeListeners.registerTaskStackListener(
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
        if (handleLoggingCommand(args, pw)) {
            return;
        }
        // Dump WMShell stuff here if no commands were handled
        mShellTaskOrganizer.dump(pw, "");
        pw.println();
        pw.println();
        mPipOptional.ifPresent(pip -> pip.dump(pw));
        mSplitScreenOptional.ifPresent(splitScreen -> splitScreen.dump(pw));
        mOneHandedOptional.ifPresent(oneHanded -> oneHanded.dump(pw));
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
                    int result = protoLogImpl.startTextLogging(mContext, groups, pw);
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
