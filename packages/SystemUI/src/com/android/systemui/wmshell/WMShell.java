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

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BOUNCER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BUBBLES_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BUBBLES_MANAGE_MENU_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_DIALOG_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_FREEFORM_ACTIVE_IN_DESKTOP_MODE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_ONE_HANDED_ACTIVE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_QUICK_SETTINGS_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED;

import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.WMComponent;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.model.SysUiState;
import com.android.systemui.notetask.NoteTaskInitializer;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.wm.shell.desktopmode.DesktopMode;
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.onehanded.OneHandedEventCallback;
import com.android.wm.shell.onehanded.OneHandedTransitionCallback;
import com.android.wm.shell.onehanded.OneHandedUiEventLogger;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.recents.RecentTasks;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.sysui.ShellInterface;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
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
public final class WMShell implements
        CoreStartable,
        CommandQueue.Callbacks {
    private static final String TAG = WMShell.class.getName();
    private static final int INVALID_SYSUI_STATE_MASK =
            SYSUI_STATE_DIALOG_SHOWING
                    | SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING
                    | SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED
                    | SYSUI_STATE_BOUNCER_SHOWING
                    | SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED
                    | SYSUI_STATE_BUBBLES_EXPANDED
                    | SYSUI_STATE_BUBBLES_MANAGE_MENU_EXPANDED
                    | SYSUI_STATE_QUICK_SETTINGS_EXPANDED;

    private final Context mContext;
    // Shell interfaces
    private final ShellInterface mShell;
    private final Optional<Pip> mPipOptional;
    private final Optional<SplitScreen> mSplitScreenOptional;
    private final Optional<OneHanded> mOneHandedOptional;
    private final Optional<DesktopMode> mDesktopModeOptional;
    private final Optional<RecentTasks> mRecentTasksOptional;

    private final CommandQueue mCommandQueue;
    private final ConfigurationController mConfigurationController;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ScreenLifecycle mScreenLifecycle;
    private final SysUiState mSysUiState;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final UserTracker mUserTracker;
    private final DisplayTracker mDisplayTracker;
    private final NoteTaskInitializer mNoteTaskInitializer;
    private final Executor mSysUiMainExecutor;

    // Listeners and callbacks. Note that we prefer member variable over anonymous class here to
    // avoid the situation that some implementations, like KeyguardUpdateMonitor, use WeakReference
    // internally and anonymous class could be released after registration.
    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onConfigChanged(Configuration newConfig) {
                    mShell.onConfigurationChanged(newConfig);
                }
            };
    private final KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    mShell.onKeyguardVisibilityChanged(mKeyguardStateController.isShowing(),
                            mKeyguardStateController.isOccluded(),
                            mKeyguardStateController.isAnimatingBetweenKeyguardAndSurfaceBehind());
                }
            };
    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onKeyguardDismissAnimationFinished() {
                    mShell.onKeyguardDismissAnimationFinished();
                }
            };
    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    mShell.onUserChanged(newUser, userContext);
                }

                @Override
                public void onProfilesChanged(@NonNull List<UserInfo> profiles) {
                    mShell.onUserProfilesChanged(profiles);
                }
            };

    private boolean mIsSysUiStateValid;
    private WakefulnessLifecycle.Observer mWakefulnessObserver;

    @Inject
    public WMShell(
            Context context,
            ShellInterface shell,
            Optional<Pip> pipOptional,
            Optional<SplitScreen> splitScreenOptional,
            Optional<OneHanded> oneHandedOptional,
            Optional<DesktopMode> desktopMode,
            Optional<RecentTasks> recentTasks,
            CommandQueue commandQueue,
            ConfigurationController configurationController,
            KeyguardStateController keyguardStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            ScreenLifecycle screenLifecycle,
            SysUiState sysUiState,
            WakefulnessLifecycle wakefulnessLifecycle,
            UserTracker userTracker,
            DisplayTracker displayTracker,
            NoteTaskInitializer noteTaskInitializer,
            @Main Executor sysUiMainExecutor) {
        mContext = context;
        mShell = shell;
        mCommandQueue = commandQueue;
        mConfigurationController = configurationController;
        mKeyguardStateController = keyguardStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mScreenLifecycle = screenLifecycle;
        mSysUiState = sysUiState;
        mPipOptional = pipOptional;
        mSplitScreenOptional = splitScreenOptional;
        mOneHandedOptional = oneHandedOptional;
        mDesktopModeOptional = desktopMode;
        mRecentTasksOptional = recentTasks;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mUserTracker = userTracker;
        mDisplayTracker = displayTracker;
        mNoteTaskInitializer = noteTaskInitializer;
        mSysUiMainExecutor = sysUiMainExecutor;
    }

    @Override
    public void start() {
        // Notify with the initial configuration and subscribe for new config changes
        mShell.onConfigurationChanged(mContext.getResources().getConfiguration());
        mConfigurationController.addCallback(mConfigurationListener);

        // Subscribe to keyguard changes
        mKeyguardStateController.addCallback(mKeyguardStateCallback);
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);

        // Subscribe to user changes
        mUserTracker.addCallback(mUserChangedCallback, mContext.getMainExecutor());

        mCommandQueue.addCallback(this);
        mPipOptional.ifPresent(this::initPip);
        mSplitScreenOptional.ifPresent(this::initSplitScreen);
        mOneHandedOptional.ifPresent(this::initOneHanded);
        mDesktopModeOptional.ifPresent(this::initDesktopMode);
        mRecentTasksOptional.ifPresent(this::initRecentTasks);

        mNoteTaskInitializer.initialize();
    }

    @VisibleForTesting
    void initPip(Pip pip) {
        mCommandQueue.addCallback(new CommandQueue.Callbacks() {
            @Override
            public void showPictureInPictureMenu() {
                pip.showPictureInPictureMenu();
            }
        });

        mSysUiState.addCallback(sysUiStateFlag -> {
            mIsSysUiStateValid = (sysUiStateFlag & INVALID_SYSUI_STATE_MASK) == 0;
            pip.onSystemUiStateChanged(mIsSysUiStateValid, sysUiStateFlag);
        });
    }

    @VisibleForTesting
    void initSplitScreen(SplitScreen splitScreen) {
        mWakefulnessLifecycle.addObserver(new WakefulnessLifecycle.Observer() {
            @Override
            public void onFinishedWakingUp() {
                splitScreen.onFinishedWakingUp();
            }
        });
        mCommandQueue.addCallback(new CommandQueue.Callbacks() {
            @Override
            public void moveFocusedTaskToFullscreen(int displayId) {
                splitScreen.goToFullscreenFromSplit();
            }

            @Override
            public void setSplitscreenFocus(boolean leftOrTop) {
                splitScreen.setSplitscreenFocus(leftOrTop);
            }
        });
    }

    @VisibleForTesting
    void initOneHanded(OneHanded oneHanded) {
        oneHanded.registerTransitionCallback(new OneHandedTransitionCallback() {
            @Override
            public void onStartTransition(boolean isEntering) {
                mSysUiMainExecutor.execute(() -> {
                    mSysUiState.setFlag(SYSUI_STATE_ONE_HANDED_ACTIVE,
                            true).commitUpdate(mDisplayTracker.getDefaultDisplayId());
                });
            }

            @Override
            public void onStartFinished(Rect bounds) {
                mSysUiMainExecutor.execute(() -> {
                    mSysUiState.setFlag(SYSUI_STATE_ONE_HANDED_ACTIVE,
                            true).commitUpdate(mDisplayTracker.getDefaultDisplayId());
                });
            }

            @Override
            public void onStopFinished(Rect bounds) {
                mSysUiMainExecutor.execute(() -> {
                    mSysUiState.setFlag(SYSUI_STATE_ONE_HANDED_ACTIVE,
                            false).commitUpdate(mDisplayTracker.getDefaultDisplayId());
                });
            }
        });

        oneHanded.registerEventCallback(new OneHandedEventCallback() {
            @Override
            public void notifyExpandNotification() {
                mSysUiMainExecutor.execute(
                        () -> mCommandQueue.handleSystemKey(new KeyEvent(KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN)));
            }
        });

        mWakefulnessObserver =
                new WakefulnessLifecycle.Observer() {
                    @Override
                    public void onFinishedWakingUp() {
                        // Reset locked for the case keyguard not shown.
                        oneHanded.setLockedDisabled(false /* locked */, false /* enabled */);
                    }

                    @Override
                    public void onStartedGoingToSleep() {
                        oneHanded.stopOneHanded();
                        // When user press power button going to sleep, temperory lock OHM disabled
                        // to avoid mis-trigger.
                        oneHanded.setLockedDisabled(true /* locked */, false /* enabled */);
                    }
                };
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);

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
                if (displayId == mDisplayTracker.getDefaultDisplayId()
                        && (vis & InputMethodService.IME_VISIBLE) != 0) {
                    oneHanded.stopOneHanded(
                            OneHandedUiEventLogger.EVENT_ONE_HANDED_TRIGGER_POP_IME_OUT);
                }
            }
        });
    }

    void initDesktopMode(DesktopMode desktopMode) {
        desktopMode.addVisibleTasksListener(
                new DesktopModeTaskRepository.VisibleTasksListener() {
                    @Override
                    public void onTasksVisibilityChanged(int displayId, int visibleTasksCount) {
                        if (displayId == Display.DEFAULT_DISPLAY) {
                            mSysUiState.setFlag(SYSUI_STATE_FREEFORM_ACTIVE_IN_DESKTOP_MODE,
                                            visibleTasksCount > 0)
                                    .commitUpdate(mDisplayTracker.getDefaultDisplayId());
                        }
                        // TODO(b/278084491): update sysui state for changes on other displays
                    }
                }, mSysUiMainExecutor);
        mCommandQueue.addCallback(new CommandQueue.Callbacks() {
            @Override
            public void enterDesktop(int displayId) {
                desktopMode.enterDesktop(displayId);
            }
            @Override
            public void moveFocusedTaskToFullscreen(int displayId) {
                desktopMode.moveFocusedTaskToFullscreen(displayId);
            }
            @Override
            public void moveFocusedTaskToStageSplit(int displayId, boolean leftOrTop) {
                desktopMode.moveFocusedTaskToStageSplit(displayId, leftOrTop);
            }
        });
    }

    @VisibleForTesting
    void initRecentTasks(RecentTasks recentTasks) {
        recentTasks.addAnimationStateListener(mSysUiMainExecutor,
                mCommandQueue::onRecentsAnimationStateChanged);
    }

    @Override
    public boolean isDumpCritical() {
        // Dump can't be critical because the shell has to dump on the main thread for
        // synchronization reasons, which isn't reliably fast.
        return false;
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        Log.d(TAG, "Dumping with args: " + String.join(", ", args));

        // Strip out the SysUI "dependency" arg before sending to WMShell
        if (args[0].equals("dependency")) {
            args = Arrays.copyOfRange(args, 1, args.length);
        }

        // Handle commands if provided
        if (mShell.handleCommand(args, pw)) {
            return;
        }
        // Dump WMShell stuff here if no commands were handled
        mShell.dump(pw);
    }
}
